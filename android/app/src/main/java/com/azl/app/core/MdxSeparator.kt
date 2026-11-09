package com.azl.app.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.jtransforms.fft.FloatFFT_1D
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Stereo PCM (44.1 kHz, float) that can be read at any position. Frames outside the range read as 0. */
interface PcmSource {
    val frames: Long
    /** Fill [dst] with [count] interleaved stereo frames starting at [start]. */
    fun read(start: Long, count: Int, dst: FloatArray)
}

/** Receives separated audio, chunk by chunk, as interleaved stereo frames. */
fun interface PcmSink {
    fun write(vocals: FloatArray, music: FloatArray, frames: Int)
}

class CancelledException : Exception("cancelled")

/**
 * Vocal separation with an MDX-Net ONNX model (UVR "Kim_Vocal_2").
 * The model works on spectrograms, so we do the STFT / inverse STFT here.
 * Music = mix - vocals.
 */
class MdxSeparator(
    modelPath: String,
    /** FFT size of the model: 7680 for Kim_Vocal_2, 6144 for UVR_MDXNET_1_9703 */
    private val N_FFT: Int,
    /** frequency bins the model sees: 3072 or 2048 */
    private val DIM_F: Int,
    threads: Int = defaultThreads(),
) : Closeable {

    companion object {
        const val SR = 44100
        const val HOP = 1024
        const val DIM_T = 256
        const val CHUNK = HOP * (DIM_T - 1)          // samples per model call

        /** Phones mix fast and slow cores; using every core makes the fast ones wait. */
        fun defaultThreads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }

    private val TRIM = N_FFT / 2                     // context dropped at each edge
    private val GEN = CHUNK - 2 * TRIM               // useful samples per call
    private val PAD = N_FFT / 2                      // stft(center=True) padding
    private val OLA_LEN = N_FFT + HOP * (DIM_T - 1)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val fft = FloatFFT_1D(N_FFT.toLong())
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() } // periodic hann
    private val olaNorm = FloatArray(OLA_LEN).also { n ->
        for (t in 0 until DIM_T) for (k in 0 until N_FFT) n[t * HOP + k] += window[k] * window[k]
        for (i in n.indices) n[i] = max(n[i], 1e-8f)
    }

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(1, threads))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelPath, opts)
        inputName = session.inputNames.first()
    }

    fun chunkCount(frames: Long): Int = ((frames + GEN - 1) / GEN).toInt().coerceAtLeast(1)

    /**
     * Separate [src] and stream the result to [sink].
     * [progress] gets 0..1, [cancelled] is polled between chunks.
     */
    fun separate(src: PcmSource, sink: PcmSink, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val total = src.frames
        val chunks = chunkCount(total)
        val win = FloatArray(CHUNK * 2)
        val left = FloatArray(CHUNK)
        val right = FloatArray(CHUNK)
        val spec = FloatArray(4 * DIM_F * DIM_T)
        val outL = FloatArray(OLA_LEN)
        val outR = FloatArray(OLA_LEN)

        for (c in 0 until chunks) {
            if (cancelled()) throw CancelledException()
            val start = c.toLong() * GEN
            // model window = [start - TRIM, start - TRIM + CHUNK)
            src.read(start - TRIM, CHUNK, win)
            for (i in 0 until CHUNK) { left[i] = win[2 * i]; right[i] = win[2 * i + 1] }

            stft(left, spec, 0)
            stft(right, spec, 2)

            val result = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec),
                longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())).use { input ->
                session.run(mapOf(inputName to input)).use { out ->
                    val t = out[0] as OnnxTensor
                    val fb = t.floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }

            istft(result, 0, outL)
            istft(result, 2, outR)

            val n = min(GEN.toLong(), total - start).toInt()
            val voc = FloatArray(n * 2)
            val mus = FloatArray(n * 2)
            for (i in 0 until n) {
                val o = PAD + TRIM + i          // position inside the overlap-add buffer
                val vl = outL[o]; val vr = outR[o]
                voc[2 * i] = vl; voc[2 * i + 1] = vr
                mus[2 * i] = left[TRIM + i] - vl
                mus[2 * i + 1] = right[TRIM + i] - vr
            }
            sink.write(voc, mus, n)
            progress((c + 1f) / chunks)
        }
    }

    /** STFT of one channel (torch.stft center=True, reflect pad) into channels [ch] (real) and [ch+1] (imag). */
    private fun stft(x: FloatArray, spec: FloatArray, ch: Int) {
        val frame = FloatArray(N_FFT)
        val len = x.size
        val reIdx = ch * DIM_F * DIM_T
        val imIdx = (ch + 1) * DIM_F * DIM_T
        for (t in 0 until DIM_T) {
            val base = t * HOP - PAD
            for (k in 0 until N_FFT) {
                var j = base + k
                if (j < 0) j = -j else if (j >= len) j = 2 * (len - 1) - j
                frame[k] = x[j] * window[k]
            }
            fft.realForward(frame)
            // packed: [Re0, Re(N/2), Re1, Im1, Re2, Im2, ...]
            spec[reIdx + t] = frame[0]
            spec[imIdx + t] = 0f
            for (f in 1 until DIM_F) {
                spec[reIdx + f * DIM_T + t] = frame[2 * f]
                spec[imIdx + f * DIM_T + t] = frame[2 * f + 1]
            }
        }
    }

    /** Inverse STFT of channels [ch]/[ch+1] (bins above DIM_F are zero) into [out] (overlap-added, normalised). */
    private fun istft(spec: FloatArray, ch: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        val frame = FloatArray(N_FFT)
        val reIdx = ch * DIM_F * DIM_T
        val imIdx = (ch + 1) * DIM_F * DIM_T
        for (t in 0 until DIM_T) {
            java.util.Arrays.fill(frame, 0f)
            frame[0] = spec[reIdx + t]
            frame[1] = 0f // Nyquist bin
            for (f in 1 until DIM_F) {
                frame[2 * f] = spec[reIdx + f * DIM_T + t]
                frame[2 * f + 1] = spec[imIdx + f * DIM_T + t]
            }
            fft.realInverse(frame, true)
            val off = t * HOP
            for (k in 0 until N_FFT) out[off + k] += frame[k] * window[k]
        }
        for (i in out.indices) out[i] /= olaNorm[i]
    }

    override fun close() {
        session.close()
    }
}
