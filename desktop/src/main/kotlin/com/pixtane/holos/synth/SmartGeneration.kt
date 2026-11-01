package com.pixtane.holos.synth

import com.pixtane.holos.HolosApp
import com.pixtane.holos.data.RecordingRepository
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Smart generation" on a PC: runs while the computer is on mains power and the battery is not
 * nearly empty. Desktops without a battery always qualify.
 */
object PowerConditions {
    private const val MIN_BATTERY_PERCENT = 5
    @Volatile private var cachedAt = 0L
    @Volatile private var cached = true

    fun ok(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (now - cachedAt < 5_000) return cached
        cached = compute()
        cachedAt = now
        return cached
    }

    fun invalidate() { cachedAt = 0L }

    private fun compute(): Boolean {
        if (!HolosApp.isWindows) return true
        return try {
            val s = PowerStatus()
            if (!Kernel32Power.INSTANCE.GetSystemPowerStatus(s)) return true
            val noBattery = (s.BatteryFlag.toInt() and 128) != 0 || (s.BatteryFlag.toInt() and 0xff) == 255
            if (noBattery) return true
            val onMains = s.ACLineStatus.toInt() == 1
            val percent = s.BatteryLifePercent.toInt() and 0xff
            onMains && (percent == 255 || percent >= MIN_BATTERY_PERCENT)
        } catch (e: Throwable) {
            true
        }
    }
}

/** SYSTEM_POWER_STATUS from kernel32 (GetSystemPowerStatus). */
@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class PowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

@Suppress("FunctionName")
interface Kernel32Power : Library {
    fun GetSystemPowerStatus(status: PowerStatus): Boolean

    companion object {
        val INSTANCE: Kernel32Power by lazy { Native.load("kernel32", Kernel32Power::class.java) }
    }
}

/** Polls the power state and starts smart recordings when it allows. */
object SmartScheduler {
    private var job: Job? = null

    fun init() = ensureScheduled()

    @Synchronized
    fun ensureScheduled() {
        if (job?.isActive == true) return
        if (pending().isEmpty()) return
        job = HolosApp.scope.launch {
            while (true) {
                val waiting = pending()
                if (waiting.isEmpty()) break
                PowerConditions.invalidate()
                if (PowerConditions.ok()) {
                    for (r in waiting) if (!SynthesisManager.isQueuedOrActive(r.id)) SynthesisManager.enqueue(r.id)
                }
                delay(30_000)
            }
        }
    }

    private fun pending() = RecordingRepository.list.value.filter {
        it.smart && !it.isComplete && it.id !in SynthesisManager.paused.value
    }
}
