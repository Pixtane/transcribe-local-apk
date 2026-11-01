package com.pixtane.holos.ui

import com.pixtane.holos.HolosApp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

/** Native Windows dialogs and shell helpers. */
object DesktopOs {
    /** Set by Main to the app window so dialogs are modal to it. */
    @Volatile var window: Frame? = null

    fun openFile(title: String, extensions: List<String>): File? {
        val d = FileDialog(window, title, FileDialog.LOAD)
        // Windows ignores FilenameFilter; a "*.pdf;*.epub" pattern in the file box filters instead.
        if (HolosApp.isWindows) d.file = extensions.joinToString(";") { "*.$it" }
        d.filenameFilter = FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        d.isVisible = true
        val name = d.file ?: return null
        return File(d.directory, name).takeIf { it.isFile }
    }

    fun saveFile(title: String, dir: File?, suggested: String): File? {
        val d = FileDialog(window, title, FileDialog.SAVE)
        dir?.takeIf { it.isDirectory }?.let { d.directory = it.absolutePath }
        d.file = suggested
        d.isVisible = true
        val name = d.file ?: return null
        val ext = suggested.substringAfterLast('.', "")
        val fixed = if (ext.isNotEmpty() && !name.lowercase().endsWith(".$ext")) "$name.$ext" else name
        return File(d.directory, fixed)
    }

    /** Opens Explorer with [file] selected. */
    fun showInFolder(file: File) {
        runCatching {
            if (HolosApp.isWindows) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    }

    fun open(file: File) {
        runCatching { java.awt.Desktop.getDesktop().open(file) }
    }

    fun browse(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    val musicDir: File get() = File(System.getProperty("user.home"), "Music").let { if (it.isDirectory) File(it, "Holos") else File(System.getProperty("user.home"), "Holos") }
}
