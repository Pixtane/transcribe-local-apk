package com.pixtane.holos.synth

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import com.pixtane.holos.R
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.ui.MainActivity
import com.pixtane.holos.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps generation running with the screen off: foreground notification + partial wake lock. */
class SynthesisService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashReport.crumb("SynthesisService start (action=${intent?.action})")
        if (intent?.action == ACTION_PAUSE) {
            SynthesisManager.status.value.recordingId?.let { SynthesisManager.pause(it) }
        }
        val types = buildList {
            if (Build.VERSION.SDK_INT >= 35) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            if (Build.VERSION.SDK_INT >= 29) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            if (Build.VERSION.SDK_INT < 29) add(0)
        }
        val notification = build(SynthesisManager.status.value)
        val started = types.any { type ->
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
                .onFailure { CrashReport.crumb("startForeground(type=$type) failed: $it") }
                .isSuccess
        }
        if (!started) {
            // Generation still runs in-process; we just lose the screen-off guarantee.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "holos:synthesis")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (watcher == null) {
            watcher = HolosApp.scope.launch(Dispatchers.Main) {
                SynthesisManager.status.collect { st ->
                    if (st.running) {
                        runCatching { NotificationManagerCompat.from(this@SynthesisService).notify(NOTIFICATION_ID, build(st)) }
                    } else if (SynthesisManager.isIdle()) {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(st: SynthesisManager.Status): Notification {
        val rec = st.recordingId?.let { RecordingRepository.get(it) }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, SynthesisService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = if (st.total > 0) st.done * 100 / st.total else 0
        val detail = when {
            st.loadingEngines -> "Loading voice engines…"
            st.etaSec >= 0 -> "$pct% · ${formatDuration(st.etaSec * 1000L)} left · ${st.charsPerSec.toInt()} chars/s"
            else -> "$pct%"
        }
        return NotificationCompat.Builder(this, HolosApp.CHANNEL_SYNTH)
            .setSmallIcon(R.drawable.ic_stat_holos)
            .setContentTitle(rec?.let { "Generating · ${it.title}" } ?: "Preparing voice")
            .setContentText(detail)
            .setProgress(st.total.coerceAtLeast(1), st.done, st.loadingEngines)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 time limit for dataSync/mediaProcessing reached; progress is saved per sentence.
        SynthesisManager.status.value.recordingId?.let { SynthesisManager.pause(it) }
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PAUSE = "com.pixtane.holos.PAUSE_SYNTH"
    }
}
