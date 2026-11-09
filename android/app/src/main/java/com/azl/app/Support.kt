package com.azl.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Shared job state between the service and the screen. */
object JobRepo {
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state

    @Volatile var cancel = false
    @Volatile var processId: String? = null
    @Volatile var process: Process? = null

    fun update(f: (JobState) -> JobState) = _state.update(f)
}

/** Runs the ffmpeg that ships inside youtubedl-android. */
object Ffmpeg {
    data class Probe(val hasAudio: Boolean, val hasVideo: Boolean, val duration: Double)

    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun libPath(ctx: Context): String {
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return "${pk.path}/ffmpeg/usr/lib:${pk.path}/python/usr/lib"
    }

    private val timeRe = Regex("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")

    /** Returns exit code and the tail of the log. */
    fun run(ctx: Context, args: List<String>, duration: Double = 0.0, onProgress: ((Float) -> Unit)? = null): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(bin(ctx).path, "-hide_banner", "-nostdin") + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
        val p = pb.start()
        JobRepo.process = p
        val log = StringBuilder()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                log.append(line).append('\n')
                if (log.length > 6000) log.delete(0, log.length - 4000)
                if (onProgress != null && duration > 0) {
                    timeRe.find(line)?.let { m ->
                        val t = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
                        onProgress((t / duration).coerceIn(0.0, 1.0).toFloat())
                    }
                }
            }
            return p.waitFor() to log.toString()
        } finally {
            JobRepo.process = null
        }
    }

    fun probe(ctx: Context, f: File): Probe {
        val log = run(ctx, listOf("-i", f.path)).second
        val d = Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(log)
        val duration = d?.let { it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toDouble() } ?: 0.0
        val lines = log.lines()
        val hasAudio = lines.any { it.contains("Stream #") && it.contains("Audio:") }
        val hasVideo = lines.any { it.contains("Stream #") && it.contains("Video:") && !it.contains("attached pic") }
        return Probe(hasAudio, hasVideo, duration)
    }
}

/** Separation models. Each is downloaded once and kept in the app's files. */
enum class Model(val fileName: String, val size: Long, val nFft: Int, val dimF: Int) {
    /** default: ~3x faster, almost the same quality */
    FAST("UVR_MDXNET_1_9703.onnx", 29704436L, 6144, 2048),
    /** best quality, slower */
    BEST("Kim_Vocal_2.onnx", 66759214L, 7680, 3072),
}

object ModelManager {
    private fun urls(m: Model) = listOf(
        "https://github.com/nawafz2011-blip/azl/releases/download/model/${m.fileName}",
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/${m.fileName}",
    )

    fun file(ctx: Context, m: Model) = File(ctx.filesDir, "models/${m.fileName}")
    fun ready(ctx: Context, m: Model) = file(ctx, m).length() == m.size

    fun ensure(ctx: Context, m: Model, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (ready(ctx, m)) return
        val dest = file(ctx, m).apply { parentFile?.mkdirs() }
        val part = File(dest.path + ".part")
        var lastError: Exception? = null
        for (u in urls(m)) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                c.instanceFollowRedirects = true
                if (c.responseCode != 200) { lastError = RuntimeException("HTTP ${c.responseCode}"); continue }
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            if (cancelled()) throw com.azl.app.core.CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done.toFloat() / m.size)
                        }
                    }
                }
                if (part.length() == m.size && part.renameTo(dest)) return
                lastError = RuntimeException("size ${part.length()}")
            } catch (e: com.azl.app.core.CancelledException) {
                part.delete(); throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        part.delete()
        throw UserError("تعذّر تحميل نموذج العزل. تأكد من الإنترنت وجرّب مرة ثانية" +
            (lastError?.message?.let { " ($it)" } ?: ""))
    }
}

/** Saves results to the gallery: videos → Movies/عزل, audio → Music/عزل. */
object MediaSaver {
    private const val FOLDER = "عزل"

    fun save(ctx: Context, file: File, name: String, mime: String, isVideo: Boolean): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: throw RuntimeException("MediaStore insert failed")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC)
            val dir = File(base, FOLDER).apply { mkdirs() }
            var out = File(dir, name)
            var i = 1
            while (out.exists()) out = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.')).also { i++ }
            file.copyTo(out)
            MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf(mime), null)
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
        }
    }
}

/** Checks GitHub Releases for a newer APK. */
object Updater {
    private const val API = "https://api.github.com/repos/nawafz2011-blip/azl/releases/latest"

    data class Update(val version: String, val apkUrl: String)

    /** Downloads the new APK into the app's cache; returns the file. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "azl.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        return out
    }

    fun check(): Update? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 10000
        if (c.responseCode != 200) return null
        val json = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = json.getString("tag_name")                     // v1.0.<code>
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
        }
        Update(tag.removePrefix("v"), apk ?: json.getString("html_url"))
    }.getOrNull()
}
