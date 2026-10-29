package com.pixtane.holos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.tts.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HolosApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReport.install(this)
        Prefs.init(this)
        ModelManager.init(this)
        RecordingRepository.init(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNTH, t("Voice generation", "Генерація голосу"), NotificationManager.IMPORTANCE_LOW).apply {
                description = t("Progress while text is being turned into speech", "Прогрес перетворення тексту на мовлення")
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_SYNTH = "synthesis"
        lateinit var instance: HolosApp
            private set
        val scope: CoroutineScope get() = instance.appScope
    }
}
