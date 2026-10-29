package com.pixtane.holos

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Minimal crash diagnostics: Java crashes are written to a file by an uncaught-exception
 * handler; native crashes and low-memory kills are read back from ApplicationExitInfo.
 * A short breadcrumb trail (what the generator was doing) is kept on disk as well.
 */
object CrashReport {
    private const val TAG = "Holos"
    private lateinit var crashFile: File
    private lateinit var crumbFile: File
    private val crumbs = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(context: Context) {
        crashFile = File(context.filesDir, "last_crash.txt")
        crumbFile = File(context.filesDir, "breadcrumbs.txt")
        // Keep the previous run's breadcrumbs for the report, then start a fresh trail.
        runCatching { if (crumbFile.exists()) crumbFile.copyTo(File(context.filesDir, "breadcrumbs_prev.txt"), true) }
        runCatching { crumbFile.delete() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile.writeText("Thread: ${thread.name}\n${e.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    @Synchronized
    fun crumb(msg: String) {
        Log.i(TAG, msg)
        crumbs.addLast("${fmt.format(Date())} $msg")
        while (crumbs.size > 40) crumbs.removeFirst()
        runCatching { crumbFile.writeText(crumbs.joinToString("\n")) }
    }

    /** Returns a report about the previous run if it ended in a crash, once. */
    fun takeReport(context: Context): String? {
        val sb = StringBuilder()
        if (crashFile.exists()) {
            sb.append(crashFile.readText().take(6000))
            crashFile.delete()
        } else if (Build.VERSION.SDK_INT >= 30) {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
            val seen = context.getSharedPreferences("holos", Context.MODE_PRIVATE).getLong("exit_seen", 0L)
            if (info != null && info.timestamp > seen) {
                context.getSharedPreferences("holos", Context.MODE_PRIVATE).edit().putLong("exit_seen", info.timestamp).apply()
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: low memory"
                    ApplicationExitInfo.REASON_CRASH -> "Crash"
                    ApplicationExitInfo.REASON_ANR -> "App not responding"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed: excessive resource use"
                    else -> null
                }
                if (reason != null) {
                    sb.append(reason)
                    info.description?.let { sb.append(": ").append(it) }
                    sb.append("\nPSS ${info.pss / 1024} MB, RSS ${info.rss / 1024} MB")
                }
            }
        }
        if (sb.isEmpty()) return null
        val prev = File(context.filesDir, "breadcrumbs_prev.txt")
        if (prev.exists()) sb.append("\n\nLast steps:\n").append(prev.readText().takeLast(3000))
        sb.append("\n\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), app ${BuildConfig.VERSION_NAME}")
        return sb.toString()
    }
}
