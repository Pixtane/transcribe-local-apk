package com.pixtane.holos.synth

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import com.pixtane.holos.R
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.t
import kotlinx.coroutines.delay

/**
 * "Smart generation": long recordings are only generated while the phone is charging, the
 * battery is not nearly empty and the phone is not hot. The user only sees "activates when
 * charging"; the other guards are silent.
 */
object PowerConditions {
    private const val MIN_BATTERY_PERCENT = 5
    private const val HOT_C = 41f
    private const val COOL_AGAIN_C = 39f

    @Volatile private var overheated = false
    @Volatile private var cachedAt = 0L
    @Volatile private var cached = false

    /** True when smart generation may run now (cached for a few seconds). */
    fun ok(context: Context = HolosApp.instance): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < 5_000) return cached
        cached = compute(context)
        cachedAt = now
        return cached
    }

    fun invalidate() { cachedAt = 0L }

    private fun compute(context: Context): Boolean {
        val b = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return false
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level < 0) 100 else level * 100 / scale
        val tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        // Hysteresis so generation does not flap around the limit.
        overheated = if (overheated) tempC > COOL_AGAIN_C else tempC >= HOT_C
        val thermalOk = if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
        } else true
        return charging && percent >= MIN_BATTERY_PERCENT && !overheated && thermalOk
    }
}

object SmartScheduler {
    private const val WORK_NAME = "smart-generation"
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            PowerConditions.invalidate()
            kick()
        }
    }

    fun init(context: Context) {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        ensureScheduled(context)
    }

    /** Smart recordings that still need audio and were not paused by the user. */
    fun pending(): List<String> = RecordingRepository.list.value
        .filter { it.smart && !it.isComplete && it.id !in SynthesisManager.paused.value }
        .map { it.id }

    /** Starts any pending smart recordings if conditions allow (cheap; safe to call often). */
    fun kick() {
        val ids = pending()
        if (ids.isEmpty()) return
        if (PowerConditions.ok()) {
            for (id in ids) if (!SynthesisManager.isQueuedOrActive(id)) SynthesisManager.enqueue(id)
        }
    }

    /** Make sure the system wakes us when the phone is plugged in and work is pending. */
    fun ensureScheduled(context: Context = HolosApp.instance, append: Boolean = false) {
        if (pending().isEmpty()) return
        val request = OneTimeWorkRequestBuilder<SmartGenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

/** Runs while the phone charges; keeps the process alive and feeds smart recordings to the synthesizer. */
class SmartGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CrashReport.crumb("Smart generation worker started")
        val foreground = runCatching { setForeground(foregroundInfo()) }.isSuccess
        val started = SystemClock.elapsedRealtime()
        while (true) {
            PowerConditions.invalidate()
            if (!PowerConditions.ok(applicationContext)) break
            if (SmartScheduler.pending().isEmpty()) return Result.success()
            SmartScheduler.kick()
            delay(15_000)
            // Without a foreground notification the system allows ~10 minutes per run.
            if (!foreground && SystemClock.elapsedRealtime() - started > 9 * 60_000L) break
        }
        SmartScheduler.ensureScheduled(applicationContext, append = true)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, HolosApp.CHANNEL_SYNTH)
            .setSmallIcon(R.drawable.ic_stat_holos)
            .setContentTitle(t("Smart generation", "Розумна генерація"))
            .setContentText(t("Generating while charging", "Генерація під час заряджання"))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(43, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(43, n)
    }
}
