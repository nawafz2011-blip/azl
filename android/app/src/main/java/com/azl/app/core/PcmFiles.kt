package com.azl.app.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw f32le stereo file (what `ffmpeg -f f32le -ac 2` produces) read as a [PcmSource]. */
class PcmFileSource(file: File) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val frames: Long = raf.length() / 8

    override fun read(start: Long, count: Int, dst: FloatArray) {
        java.util.Arrays.fill(dst, 0, count * 2, 0f)
        val from = maxOf(start, 0L)
        val to = minOf(start + count, frames)
        if (to <= from) return
        val n = (to - from).toInt()
        val bytes = ByteArray(n * 8)
        raf.seek(from * 8)
        raf.readFully(bytes)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fb.get(dst, ((from - start) * 2).toInt(), n * 2)
    }

    override fun close() = raf.close()
}

/** Writes interleaved stereo floats as raw f32le. */
class PcmFileWriter(file: File, append: Boolean = false) : Closeable {
    private val fos = FileOutputStream(file, append)
    private val out = BufferedOutputStream(fos, 1 shl 20)

    /** Push everything to disk, so a resumed job can trust the file length. */
    fun flush() { out.flush(); runCatching { fos.fd.sync() } }

    fun write(samples: FloatArray, frames: Int) {
        val bb = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(samples, 0, frames * 2)
        out.write(bb.array())
    }

    override fun close() = out.close()
}
