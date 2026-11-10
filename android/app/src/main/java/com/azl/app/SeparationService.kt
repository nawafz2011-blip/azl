package com.azl.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground service so separation keeps going when the screen is off or the app is in the background. */
class SeparationService : Service() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URL = "url"
        private const val EXTRA_MODEL = "model"
        private const val NOTIF_WORK = 1
        private const val NOTIF_DONE = 2
        const val ACTION_CANCEL = "com.azl.app.CANCEL"
        private const val ACTION_RESUME = "com.azl.app.RESUME"

        /** Carry on with a job that was interrupted (the app was closed by the phone). */
        fun resume(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SeparationService::class.java).setAction(ACTION_RESUME))
        }

        fun start(ctx: Context, mode: Mode, url: String?, uri: Uri?, model: Model) {
            val i = Intent(ctx, SeparationService::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_MODEL, model.name)
                url?.let { putExtra(EXTRA_URL, it) }
                uri?.let {
                    data = it
                    clipData = ClipData.newRawUri("", it)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Engine.cancel()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_WORK, progressNotification(JobState(stage = "جاري البدء…")), type)
        } catch (e: Exception) {
            // Android may refuse to restart us in the background; the app offers "كمّل" next time it opens
            stopSelf()
            return START_NOT_STICKY
        }
        if (busy) return START_REDELIVER_INTENT
        busy = true

        // null spec = resume the saved job: the user asked to, or Android restarted us after killing the app
        val redelivered = intent == null || (flags and START_FLAG_REDELIVERY) != 0
        val spec: JobSpec? = if (redelivered || intent?.action == ACTION_RESUME) null else JobSpec(
            mode = Mode.valueOf(intent!!.getStringExtra(EXTRA_MODE) ?: Mode.SPEECH.name),
            url = intent.getStringExtra(EXTRA_URL),
            uri = intent.data,
            model = runCatching { Model.valueOf(intent.getStringExtra(EXTRA_MODEL)!!) }.getOrDefault(Model.FAST),
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "azl:separate").apply { acquire(3 * 60 * 60 * 1000L) }

        val nm = getSystemService(NotificationManager::class.java)
        val updates = scope.launch {
            var last = 0L
            JobRepo.state.collectLatest { s ->
                val now = System.currentTimeMillis()
                if (s.running && now - last > 700) {
                    last = now
                    nm.notify(NOTIF_WORK, progressNotification(s))
                }
            }
        }
        scope.launch {
            Engine.run(applicationContext, spec)
            updates.cancel()
            val s = JobRepo.state.value
            nm.notify(NOTIF_DONE, doneNotification(s))
            wakeLock?.let { if (it.isHeld) it.release() }
            ServiceCompat.stopForeground(this@SeparationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            busy = false
            stopSelf()
        }
        // if Android kills the app mid-way, it restarts this service and the job carries on
        return START_REDELIVER_INTENT
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(s: JobState): Notification {
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, SeparationService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, AzlApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(if (s.eta.isNotEmpty()) "${s.stage} باقي ${s.eta}" else s.stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "إلغاء", cancel)
        if (s.progress in 0f..1f) b.setProgress(100, (s.progress * 100).toInt(), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun doneNotification(s: JobState): Notification =
        NotificationCompat.Builder(this, AzlApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(when {
                s.error != null -> "ما تم العزل"
                s.results.any { it.isVideo } -> "تم حفظ الفيديو في الاستوديو ✓"
                else -> "تم العزل ✓"
            })
            .setContentText(s.error ?: s.title)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
