package com.pixtane.holos

import java.util.prefs.Preferences

/** Same settings as on Android, stored in the per-user Java preferences (registry on Windows). */
object Prefs {
    private val sp: Preferences = Preferences.userRoot().node("com/pixtane/holos")
    private val cores = Runtime.getRuntime().availableProcessors()

    private fun save() = runCatching { sp.flush() }

    var steps: Int
        get() = sp.getInt("steps", 3)
        set(v) { sp.putInt("steps", v); save() }

    /** PCs have more cores and memory than phones: default to about half the cores. */
    var workers: Int
        get() = sp.getInt("workers", (cores / 2).coerceIn(1, 6))
        set(v) { sp.putInt("workers", v); save() }

    var threadsPerWorker: Int
        get() = sp.getInt("threads", 1)
        set(v) { sp.putInt("threads", v); save() }

    var compactAudio: Boolean
        get() = sp.getBoolean("compact", true)
        set(v) { sp.putBoolean("compact", v); save() }

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.putFloat("speed", v); save() }

    var volume: Float
        get() = sp.getFloat("volume", 1f)
        set(v) { sp.putFloat("volume", v); save() }

    var lastLang: String
        get() = sp.get("lang", "uk")
        set(v) { sp.put("lang", v); save() }

    var lastVoice: Int
        get() = sp.getInt("voice", 0)
        set(v) { sp.putInt("voice", v); save() }

    fun cpsFor(engineId: String): Float = sp.getFloat("cps_$engineId", 0f)
    fun setCpsFor(engineId: String, v: Float) { sp.putFloat("cps_$engineId", v); save() }

    fun engineFor(lang: String): String? = sp.get("engine2_$lang", null)
    fun setEngineFor(lang: String, id: String) { sp.put("engine2_$lang", id); save() }

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) { sp.putBoolean("auto_update", v); save() }

    var uiLang: String
        get() = sp.get("ui_lang", "uk")
        set(v) { sp.put("ui_lang", v); save() }

    var lastPlayedId: String?
        get() = sp.get("last_played", null)
        set(v) { if (v == null) sp.remove("last_played") else sp.put("last_played", v); save() }

    var exportDir: String?
        get() = sp.get("export_dir", null)
        set(v) { if (v == null) sp.remove("export_dir") else sp.put("export_dir", v); save() }

    var windowW: Int
        get() = sp.getInt("win_w", 1100)
        set(v) { sp.putInt("win_w", v); save() }

    var windowH: Int
        get() = sp.getInt("win_h", 780)
        set(v) { sp.putInt("win_h", v); save() }
}
