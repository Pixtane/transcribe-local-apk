package com.pixtane.holos.update

import com.pixtane.holos.BuildInfo
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import com.pixtane.holos.Prefs
import com.pixtane.holos.playback.PlayerBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess

/**
 * Updates the Windows app from GitHub releases tagged `windows-v1.0.N`: downloads the MSI and
 * runs it (per-user install, no admin rights), then restarts Holos.
 */
object Updater {
    private const val REPO = "Pixtane/transcribe-local-apk"
    private const val TAG_PREFIX = "windows-v"

    data class Release(val build: Int, val version: String, val notes: String, val url: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Ready(val release: Release, val msi: File) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    private val dir get() = File(HolosApp.dataDir, "updates").apply { mkdirs() }

    /** Development builds (number 0) never update themselves. */
    val enabled get() = BuildInfo.BUILD > 0 && HolosApp.isWindows

    fun init() {
        if (!enabled) return
        HolosApp.scope.launch {
            delay(5_000)
            while (true) {
                check(auto = true)
                delay(6 * 3600_000L)
            }
        }
    }

    fun check(auto: Boolean = false) {
        if (!enabled || job?.isActive == true) return
        job = HolosApp.scope.launch(Dispatchers.IO) {
            if (!auto) _state.value = State.Checking
            try {
                val r = latest()
                if (r == null || r.build <= BuildInfo.BUILD) {
                    _state.value = if (auto) State.Idle else State.UpToDate
                    return@launch
                }
                _state.value = State.Available(r)
                if (auto && Prefs.autoUpdate) download(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReport.crumb("update check failed: $e")
                if (!auto) _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Downloads (if needed) and installs the available version now. */
    fun updateNow() {
        when (val s = _state.value) {
            is State.Ready -> install(s.msi, s.release)
            is State.Available -> start { download(s.release); (_state.value as? State.Ready)?.let { install(it.msi, it.release) } }
            is State.Failed -> s.release?.let { r -> start { download(r); (_state.value as? State.Ready)?.let { install(it.msi, it.release) } } }
            else -> Unit
        }
    }

    private fun start(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = HolosApp.scope.launch(Dispatchers.IO) {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName, (_state.value as? State.Downloading)?.release)
            }
        }
    }

    private fun latest(): Release? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Holos-Windows/${BuildInfo.VERSION}")
        }
        try {
            if (conn.responseCode != 200) throw IOException("GitHub HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tag = o.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX) || o.optBoolean("draft") || o.optBoolean("prerelease")) continue
                val version = tag.removePrefix(TAG_PREFIX)
                val build = version.substringAfterLast('.').toIntOrNull() ?: continue
                val assets = o.optJSONArray("assets") ?: continue
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (a.optString("name").endsWith(".msi") && (best == null || build > best.build)) {
                        best = Release(build, version, o.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
                    }
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(r: Release) {
        val dest = File(dir, "Holos-${r.build}.msi")
        if (dest.length() == r.size && r.size > 0) { _state.value = State.Ready(r, dest); return }
        dir.listFiles()?.forEach { if (it != dest) it.delete() }
        val part = File(dest.path + ".part")
        _state.value = State.Downloading(r, 0, r.size)
        val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Holos-Windows/${BuildInfo.VERSION}")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            var done = 0L
            FileOutputStream(part).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 512 * 1024) { last = done; _state.value = State.Downloading(r, done, r.size) }
                    }
                }
            }
            if (r.size > 0 && done != r.size) throw IOException("Download incomplete")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save the update")
            _state.value = State.Ready(r, dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun install(msi: File, r: Release) {
        _state.value = State.Installing(r)
        PlayerBus.player?.release()
        val exe = ProcessHandle.current().info().command().orElse(null)
        // Wait for this process to exit, install quietly with a progress bar, then start Holos again.
        val relaunch = if (exe != null && exe.lowercase().endsWith("holos.exe")) " & start \"\" \"$exe\"" else ""
        val cmd = "timeout /t 2 /nobreak >nul & msiexec /i \"${msi.absolutePath}\" /passive /norestart$relaunch"
        try {
            ProcessBuilder("cmd.exe", "/c", cmd).start()
            CrashReport.crumb("installing update ${r.version}")
            exitProcess(0)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Could not start the installer", r)
        }
    }
}
