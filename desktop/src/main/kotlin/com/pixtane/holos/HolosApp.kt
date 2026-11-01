package com.pixtane.holos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide state for the desktop app (the Android Application's counterpart). */
object HolosApp {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** %LOCALAPPDATA%\Holos on Windows, ~/.local/share/holos elsewhere. */
    val dataDir: File by lazy {
        val win = System.getenv("LOCALAPPDATA")
        val dir = if (!win.isNullOrBlank()) File(win, "Holos")
        else File(System.getProperty("user.home"), ".local/share/holos")
        dir.apply { mkdirs() }
    }
    val cacheDir: File by lazy { File(dataDir, "cache").apply { mkdirs() } }

    val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
}
