package com.azl.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class AzlApp : Application() {

    companion object {
        const val CHANNEL_WORK = "work"
        const val CHANNEL_DONE = "done"
        private const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000

        private val ready = CountDownLatch(1)
        @Volatile var initError: Throwable? = null

        /** Blocks until Python, yt-dlp and ffmpeg are unpacked. */
        fun awaitReady() = ready.await()
    }

    override fun onCreate() {
        super.onCreate()
        // صباحي / ليلي / تلقائي
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences("azl", Context.MODE_PRIVATE)
                .getInt("theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        createChannels()
        thread(name = "engine-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (e: Throwable) {
                initError = e
            } finally {
                ready.countDown()
            }
            if (initError == null) updateYtDlp()
        }
    }

    /** YouTube changes often, so yt-dlp updates itself once a day. */
    private fun updateYtDlp() {
        val prefs = getSharedPreferences("azl", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_update", 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("ytdlp_update", System.currentTimeMillis()).apply()
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "المعالجة", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "اكتمال العزل", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
