package com.pixtane.holos

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves uncaught errors to disk; the next start shows them so they can be copied and sent. */
object CrashReport {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val crumbs = ArrayDeque<String>()
    private val crashFile get() = File(HolosApp.dataDir, "crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    @Synchronized
    fun crumb(msg: String) {
        crumbs.addLast("${fmt.format(Date())} $msg")
        while (crumbs.size > 40) crumbs.removeFirst()
    }

    @Synchronized
    fun write(thread: Thread?, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        crashFile.writeText(buildString {
            appendLine("Holos ${BuildInfo.VERSION} (Windows app) · ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Java ${System.getProperty("java.version")} · ${Runtime.getRuntime().availableProcessors()} cores")
            appendLine("Thread: ${thread?.name}")
            appendLine()
            appendLine(sw.toString())
            appendLine("Last steps:")
            crumbs.forEach { appendLine(it) }
        })
    }

    /** The report from the previous run, if it crashed (shown once). */
    fun takeReport(): String? {
        val f = crashFile
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
