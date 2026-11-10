package com.azl.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlayState(val uri: Uri? = null, val title: String = "", val playing: Boolean = false)

/** One audio player for the whole app. It lives in a service, so sound keeps going when you leave the app. */
object AudioPlayer {
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    private var mp: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    fun progress(): Float = mp?.let { p -> runCatching { if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f }.getOrNull() } ?: 0f

    /** Play [uri], or pause/resume if it is already the current track. Returns false if the file can't be opened. */
    fun toggle(ctx: Context, uri: Uri, title: String): Boolean {
        val s = _state.value
        if (s.uri == uri && mp != null) {
            if (s.playing) { pause(); PlaybackService.sync(ctx) } else resume(ctx)
            return true
        }
        release()
        val p = runCatching { MediaPlayer.create(ctx.applicationContext, uri) }.getOrNull() ?: return false
        p.setWakeMode(ctx.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnCompletionListener { stop(ctx) }
        mp = p
        _state.value = PlayState(uri, title, false)
        resume(ctx)
        return true
    }

    fun resume(ctx: Context) {
        val p = mp ?: return
        if (!requestFocus(ctx)) return
        p.start()
        _state.value = _state.value.copy(playing = true)
        PlaybackService.sync(ctx)
    }

    fun pause() {
        mp?.takeIf { it.isPlaying }?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    fun stop(ctx: Context) {
        release()
        _state.value = PlayState()
        PlaybackService.sync(ctx)
    }

    private fun release() {
        mp?.runCatching { stop(); release() }
        mp = null
    }

    private fun requestFocus(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener { change ->
                    // a call or another app started playing: pause
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pause(); PlaybackService.sync(ctx)
                    }
                }
                .build().also { focus = it }
            return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return true
    }
}

/** Keeps playback alive in the background and shows a notification with play/pause and stop. */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF = 3
        private const val ACTION_TOGGLE = "com.azl.app.PLAY_TOGGLE"
        private const val ACTION_STOP = "com.azl.app.PLAY_STOP"
        private const val ACTION_SYNC = "com.azl.app.PLAY_SYNC"

        /** Show/update the notification, or stop the service when nothing is loaded. */
        fun sync(ctx: Context) {
            val s = AudioPlayer.state.value
            val i = Intent(ctx, PlaybackService::class.java).setAction(ACTION_SYNC)
            if (s.uri == null) {
                ctx.stopService(i)
            } else {
                runCatching { ContextCompat.startForegroundService(ctx, i) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val s = AudioPlayer.state.value
                if (s.playing) AudioPlayer.pause() else AudioPlayer.resume(this)
            }
            ACTION_STOP -> {
                AudioPlayer.stop(this)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val s = AudioPlayer.state.value
        if (s.uri == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF, notification(s), type)
        return START_NOT_STICKY
    }

    private fun notification(s: PlayState): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "التشغيل", NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(if (s.playing) "يشتغل الحين" else "متوقف")
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play, if (s.playing) "إيقاف مؤقت" else "تشغيل", action(ACTION_TOGGLE, 10))
            .addAction(R.drawable.ic_close, "إغلاق", action(ACTION_STOP, 11))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away from recents keeps the sound only while playing
        if (!AudioPlayer.state.value.playing) { AudioPlayer.stop(this); stopSelf() }
        super.onTaskRemoved(rootIntent)
    }
}
