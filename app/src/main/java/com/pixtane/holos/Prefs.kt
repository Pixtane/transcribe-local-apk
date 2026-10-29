package com.pixtane.holos

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper over SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("holos", Context.MODE_PRIVATE)
    }

    private val cores = Runtime.getRuntime().availableProcessors()

    /** Flow-matching denoising steps: fewer = faster, more = cleaner audio. */
    var steps: Int
        get() = sp.getInt("steps", 3)
        set(v) = sp.edit().putInt("steps", v).apply()

    /** Number of TTS engine instances generating sentences in parallel. */
    var workers: Int
        get() = sp.getInt("workers", (cores / 2 - 1).coerceIn(1, 3))
        set(v) = sp.edit().putInt("workers", v).apply()

    var threadsPerWorker: Int
        get() = sp.getInt("threads", 1)
        set(v) = sp.edit().putInt("threads", v).apply()

    /** Store audio at 22.05 kHz instead of 44.1 kHz (half the disk space). */
    var compactAudio: Boolean
        get() = sp.getBoolean("compact", true)
        set(v) = sp.edit().putBoolean("compact", v).apply()

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    var lastLang: String
        get() = sp.getString("lang", "uk") ?: "uk"
        set(v) = sp.edit().putString("lang", v).apply()

    var lastVoice: Int
        get() = sp.getInt("voice", 0)
        set(v) = sp.edit().putInt("voice", v).apply()

    /** Measured characters per second (benchmark or real generation), 0 if unknown. */
    var measuredCps: Float
        get() = sp.getFloat("cps", 0f)
        set(v) = sp.edit().putFloat("cps", v).apply()

    var lastPlayedId: String?
        get() = sp.getString("last_played", null)
        set(v) = sp.edit().putString("last_played", v).apply()
}
