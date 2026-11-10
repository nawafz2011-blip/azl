package com.azl.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.azl.app.core.CancelledException
import com.azl.app.core.MdxSeparator
import com.azl.app.core.PcmFileSource
import com.azl.app.core.PcmFileWriter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

enum class Mode { SPEECH, SONG }

data class ResultItem(
    val label: String,
    val uri: Uri,
    val mime: String,
    /** loudness per slice (0..1) for the waveform; null for video */
    val peaks: FloatArray? = null,
) {
    val isVideo get() = mime.startsWith("video")
}

data class JobState(
    val running: Boolean = false,
    /** 0 = get the clip, 1 = read audio, 2 = separate, 3 = save */
    val step: Int = 0,
    val stage: String = "",
    /** time left, e.g. "دقيقتين و 10 ثواني"; empty when unknown */
    val eta: String = "",
    /** 0..1, or -1 for "working, unknown progress" */
    val progress: Float = -1f,
    val error: String? = null,
    val results: List<ResultItem> = emptyList(),
    val title: String = "",
    /** true when this run picked up a job that was interrupted */
    val resumed: Boolean = false,
)

class UserError(msg: String) : Exception(msg)

/** What to separate. Saved to disk so an interrupted job can carry on. */
data class JobSpec(val mode: Mode, val url: String?, val uri: Uri?, val model: Model)

/**
 * The job folder lives in the app's files (not the cache) together with job.json,
 * which records what is already done. If Android kills the app, the next run reads it and carries on.
 */
private class JobFile(val dir: File) {
    private val f = File(dir, "job.json")
    val json: JSONObject = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    fun save() = f.writeText(json.toString())
    fun exists() = f.exists()

    var spec: JobSpec?
        get() = runCatching {
            JobSpec(Mode.valueOf(json.getString("mode")), json.optString("url").ifEmpty { null },
                json.optString("uri").ifEmpty { null }?.let(Uri::parse), Model.valueOf(json.getString("model")))
        }.getOrNull()
        set(v) {
            json.put("mode", v!!.mode.name); json.put("url", v.url ?: ""); json.put("uri", v.uri?.toString() ?: "")
            json.put("model", v.model.name)
        }
    var title: String
        get() = json.optString("title")
        set(v) { json.put("title", v) }
    var input: String
        get() = json.optString("input")
        set(v) { json.put("input", v) }
    var decoded: Boolean
        get() = json.optBoolean("decoded")
        set(v) { json.put("decoded", v) }
    var separated: Boolean
        get() = json.optBoolean("separated")
        set(v) { json.put("separated", v) }
}

/** The whole pipeline: get the media → decode → separate → encode → save to the gallery. */
object Engine {

    private fun cancelled() = JobRepo.cancel

    private fun checkCancel() {
        if (cancelled()) throw CancelledException()
    }

    private fun workDir(ctx: Context) = File(ctx.filesDir, "job")

    /** An interrupted job waiting to be resumed, with its title (may be empty). */
    fun pending(ctx: Context): Pair<JobSpec, String>? {
        val jf = JobFile(workDir(ctx))
        if (!jf.exists()) return null
        val spec = jf.spec ?: return null
        return spec to jf.title
    }

    fun discardPending(ctx: Context) {
        if (!JobRepo.state.value.running) workDir(ctx).deleteRecursively()
    }

    /** Start [spec] from scratch, or (spec == null) resume the saved job. */
    fun run(ctx: Context, newSpec: JobSpec?) {
        val work = workDir(ctx)
        if (newSpec != null) {
            work.deleteRecursively(); work.mkdirs()
            JobFile(work).apply { spec = newSpec; save() }
        }
        val jf = JobFile(work)
        val spec = jf.spec
        if (spec == null) { work.deleteRecursively(); JobRepo.update { JobState() }; return }
        val resumed = newSpec == null
        JobRepo.cancel = false
        try {
            JobRepo.update { JobState(running = true, stage = "جاري تجهيز المحرك…", title = jf.title, resumed = resumed) }
            AzlApp.awaitReady()
            AzlApp.initError?.let { throw UserError("تعذّر تشغيل المحرك: ${it.message}") }

            // 1) source file (a half-downloaded link continues where it stopped)
            val input = File(work, jf.input).takeIf { jf.input.isNotEmpty() && it.isFile } ?: run {
                val (file, title) = if (spec.url != null) download(spec.url, spec.mode, work) else copyLocal(ctx, spec.uri!!, work)
                jf.input = file.name; jf.title = title; jf.save()
                file
            }
            val title = jf.title
            JobRepo.update { it.copy(title = title) }
            checkCancel()

            // 2) decode audio to raw PCM (skipped if it was already done)
            val probe = Ffmpeg.probe(ctx, input)
            if (!probe.hasAudio) throw UserError("الملف ما فيه صوت")
            val mixPcm = File(work, "mix.pcm")
            if (!jf.decoded || !mixPcm.isFile) {
                JobRepo.update { it.copy(step = 1, stage = "جاري قراءة الصوت…", eta = "", progress = 0f) }
                val (code, log) = Ffmpeg.run(ctx, listOf("-y", "-i", input.path, "-vn", "-f", "f32le",
                    "-ac", "2", "-ar", "44100", mixPcm.path), probe.duration) { p ->
                    JobRepo.update { it.copy(progress = p) }
                }
                if (code != 0 || mixPcm.length() == 0L) throw RuntimeException("decode failed: $log")
                jf.decoded = true; jf.save()
            }
            checkCancel()

            // 3) model (downloaded once)
            if (!ModelManager.ready(ctx, spec.model)) {
                val mb = spec.model.size / 1_000_000
                JobRepo.update { it.copy(step = 1, stage = "جاري تحميل نموذج العزل ($mb ميقا، أول مرة بس)…", progress = 0f) }
                ModelManager.ensure(ctx, spec.model, { p -> JobRepo.update { it.copy(progress = p) } }, ::cancelled)
            }

            // 4) separate, chunk by chunk; after an interruption it carries on from the last finished chunk
            val voicePcm = File(work, "voice.pcm")
            val musicPcm = File(work, "music.pcm")
            if (!jf.separated) {
                JobRepo.update { it.copy(step = 2, stage = "جاري عزل الصوت عن الموسيقى…", eta = "", progress = 0f) }
                MdxSeparator(ModelManager.file(ctx, spec.model).path, spec.model.nFft, spec.model.dimF).use { sep ->
                    val gen = sep.framesPerChunk.toLong()
                    val doneFrames = if (voicePcm.isFile && musicPcm.isFile) minOf(voicePcm.length(), musicPcm.length()) / 8 else 0L
                    val startChunk = (doneFrames / gen).toInt()
                    truncate(voicePcm, startChunk * gen * 8)
                    truncate(musicPcm, startChunk * gen * 8)
                    if (startChunk > 0) JobRepo.update { it.copy(stage = "نكمّل العزل من وين وقف…") }
                    PcmFileSource(mixPcm).use { src ->
                        PcmFileWriter(voicePcm, append = true).use { vw ->
                            PcmFileWriter(musicPcm, append = true).use { mw ->
                                val t0 = System.currentTimeMillis()
                                val chunks = sep.chunkCount(src.frames)
                                sep.separate(src, { v, m, n ->
                                    vw.write(v, n); mw.write(m, n)
                                    vw.flush(); mw.flush()   // what is on disk is what we can resume from
                                }, { p ->
                                    val doneNow = p - startChunk.toFloat() / chunks
                                    val left = if (doneNow > 0) ((System.currentTimeMillis() - t0) / 1000.0 * (1 - p) / doneNow).toLong() else 0
                                    JobRepo.update { it.copy(progress = p, eta = if (left > 0) fmtEta(left) else "") }
                                }, ::cancelled, startChunk)
                            }
                        }
                    }
                }
                jf.separated = true; jf.save()
            }
            mixPcm.delete()
            checkCancel()

            // 5) encode
            JobRepo.update { it.copy(step = 3, stage = "جاري حفظ الملفات…", eta = "", progress = -1f) }
            val voicePeaks = peaksOf(voicePcm)
            val musicPeaks = peaksOf(musicPcm)
            val voiceMp3 = File(work, "voice.mp3")
            val musicMp3 = File(work, "music.mp3")
            encodeMp3(ctx, voicePcm, voiceMp3)
            encodeMp3(ctx, musicPcm, musicMp3)

            var video: File? = null
            val wantVideo = ctx.getSharedPreferences("azl", Context.MODE_PRIVATE).getBoolean("make_video", true)
            if (spec.mode == Mode.SPEECH && probe.hasVideo && wantVideo) {
                video = File(work, "video.mp4")
                val base = listOf("-y", "-i", input.path, "-i", voiceMp3.path, "-map", "0:v:0", "-map", "1:a:0",
                    "-shortest", "-movflags", "+faststart")
                var r = Ffmpeg.run(ctx, base + listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "160k", video.path))
                if (r.first != 0) {
                    JobRepo.update { it.copy(stage = "جاري تجهيز الفيديو…", progress = 0f) }
                    r = Ffmpeg.run(ctx, base + listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", video.path), probe.duration) { p ->
                        JobRepo.update { it.copy(progress = p) }
                    }
                }
                if (r.first != 0) video = null
            }
            checkCancel()

            // 6) gallery: the video first
            val name = safeName(title)
            val results = mutableListOf<ResultItem>()
            video?.let {
                results += ResultItem("الفيديو بدون موسيقى",
                    MediaSaver.save(ctx, it, "$name - بدون موسيقى.mp4", "video/mp4", true), "video/mp4")
            }
            results += ResultItem("الصوت البشري فقط",
                MediaSaver.save(ctx, voiceMp3, "$name - صوت.mp3", "audio/mpeg", false), "audio/mpeg", voicePeaks)
            results += ResultItem("الموسيقى فقط",
                MediaSaver.save(ctx, musicMp3, "$name - موسيقى.mp3", "audio/mpeg", false), "audio/mpeg", musicPeaks)

            History.add(ctx, HistoryEntry(System.currentTimeMillis(), title, spec.mode, results))
            JobRepo.update { it.copy(running = false, stage = "تم ✓", eta = "", progress = 1f, results = results) }
        } catch (e: CancelledException) {
            JobRepo.update { JobState(running = false, error = "تم الإلغاء") }
        } catch (e: UserError) {
            JobRepo.update { JobState(running = false, error = e.message) }
        } catch (e: Throwable) {
            android.util.Log.e("azl", "job failed", e)
            val msg = if (e is OutOfMemoryError) "ذاكرة الجوال ما كفّت. جرّب مقطع أقصر"
            else "صار خطأ أثناء المعالجة: ${e.message?.take(200) ?: e.javaClass.simpleName}"
            JobRepo.update { JobState(running = false, error = msg) }
        } finally {
            // finished, failed or cancelled: nothing to resume. (If Android kills the app, this never runs,
            // so the folder stays and the job can carry on.)
            work.deleteRecursively()
        }
    }

    private fun truncate(f: File, bytes: Long) {
        if (!f.exists()) return
        if (f.length() > bytes) RandomAccessFile(f, "rw").use { it.setLength(bytes) }
    }

    private fun encodeMp3(ctx: Context, pcm: File, out: File) {
        val (code, log) = Ffmpeg.run(ctx, listOf("-y", "-f", "f32le", "-ac", "2", "-ar", "44100", "-i", pcm.path,
            "-c:a", "libmp3lame", "-b:a", "192k", out.path))
        if (code != 0) throw RuntimeException("mp3 failed: $log")
    }

    private fun download(url: String, mode: Mode, work: File): Pair<File, String> {
        JobRepo.update { it.copy(stage = "جاري تحميل المقطع…", progress = -1f) }
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-o", "${work.absolutePath}/source.%(ext)s")
            addOption("--no-simulate")
            addOption("--print", "after_move:title")
            // --print hides the progress bar; bring it back so the percentage shows
            addOption("--progress")
            addOption("--newline")
            if (mode == Mode.SPEECH) {
                addOption("-f", "bv*+ba/b")
                addOption("-S", "res:1080,vcodec:h264,acodec:aac,ext:mp4:m4a")
                addOption("--merge-output-format", "mp4")
            } else {
                addOption("-f", "bestaudio/best")
            }
        }
        val pid = UUID.randomUUID().toString()
        JobRepo.processId = pid
        val resp = try {
            YoutubeDL.getInstance().execute(req, pid) { progress, eta, _ ->
                if (progress > 0) JobRepo.update {
                    it.copy(progress = progress / 100f, eta = if (eta > 0) fmtEta(eta) else "")
                }
            }
        } catch (e: Exception) {
            if (cancelled()) throw CancelledException()
            throw UserError(friendly(e.message ?: ""))
        } finally {
            JobRepo.processId = null
        }
        val file = work.listFiles()
            ?.filter { it.name.startsWith("source.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw UserError("تعذّر تحميل المقطع")
        val title = resp.out.trim().lines().lastOrNull { it.isNotBlank() } ?: "عزل"
        return file to title
    }

    private fun copyLocal(ctx: Context, uri: Uri, work: File): Pair<File, String> {
        JobRepo.update { it.copy(stage = "جاري تجهيز الملف…", progress = -1f) }
        val name = displayName(ctx, uri) ?: "ملف"
        val ext = name.substringAfterLast('.', "").take(6).ifEmpty { "bin" }
        val out = File(work, "source.$ext")
        val total = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) JobRepo.update { it.copy(progress = done.toFloat() / total) }
                    checkCancel()
                }
            }
        } ?: throw UserError("تعذّر فتح الملف")
        return out to name.substringBeforeLast('.')
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private const val PEAKS = 160

    /** Loudness per slice of a raw f32le stereo file, for the waveform. */
    private fun peaksOf(f: File): FloatArray {
        val peaks = FloatArray(PEAKS)
        val total = (f.length() / 8).coerceAtLeast(1)
        val buf = ByteArray(1 shl 20)
        var frame = 0L
        f.inputStream().buffered(1 shl 20).use { input ->
            while (true) {
                val n = input.readNBytesCompat(buf)
                if (n <= 0) break
                val fb = java.nio.ByteBuffer.wrap(buf, 0, n - n % 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                while (fb.remaining() >= 2) {
                    val v = maxOf(kotlin.math.abs(fb.get()), kotlin.math.abs(fb.get()))
                    val bkt = (frame * PEAKS / total).toInt().coerceIn(0, PEAKS - 1)
                    if (v > peaks[bkt]) peaks[bkt] = v
                    frame++
                }
            }
        }
        return normalize(peaks)
    }

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun normalize(p: FloatArray): FloatArray {
        val max = (p.maxOrNull() ?: 0f).coerceAtLeast(1e-4f)
        return FloatArray(p.size) { (p[it] / max).coerceIn(0f, 1f) }
    }

    fun fmtEta(s: Long): String = when {
        s < 60 -> "$s ثانية"
        s < 3600 -> "${s / 60} دقيقة و ${s % 60} ثانية"
        else -> "${s / 3600} ساعة و ${(s % 3600) / 60} دقيقة"
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80).ifEmpty { "عزل" }

    private fun friendly(msg: String): String = when {
        msg.contains("Unsupported URL", true) -> "الرابط هذا غير مدعوم"
        msg.contains("Private video", true) -> "الفيديو خاص"
        msg.contains("confirm your age", true) -> "الفيديو مقيّد بالعمر"
        msg.contains("Sign in to confirm", true) -> "يوتيوب طلب تأكيد إنك مو روبوت. جرّب بعد شوي"
        msg.contains("Video unavailable", true) -> "الفيديو غير متوفر"
        msg.contains("Unable to resolve host", true) || msg.contains("Network is unreachable", true) ->
            "ما فيه اتصال بالإنترنت"
        else -> "تعذّر تحميل الرابط. تأكد أنه رابط صحيح لمقطع عام"
    }

    fun cancel() {
        JobRepo.cancel = true
        JobRepo.processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        JobRepo.process?.destroy()
    }
}
