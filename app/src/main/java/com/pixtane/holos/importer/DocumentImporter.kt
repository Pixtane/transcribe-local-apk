package com.pixtane.holos.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/** Opens a user-picked file (txt, md, pdf, epub, mobi/azw/prc) and turns it into text + chapters. */
object DocumentImporter {
    val MIME_TYPES = arrayOf(
        "text/*", "application/pdf", "application/epub+zip", "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook", "application/x-mobi8-ebook", "application/octet-stream",
    )

    fun import(context: Context, uri: Uri, onProgress: (String) -> Unit = {}): ImportedDoc {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val base = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Document" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val tmp = File(context.cacheDir, "import.bin")
        onProgress("Reading file…")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: throw IOException("Could not open the file")
        try {
            val head = tmp.inputStream().use { s -> ByteArray(68).also { s.read(it) } }
            val magic = String(head, Charsets.ISO_8859_1)
            return when {
                magic.startsWith("%PDF") || ext == "pdf" ->
                    PdfReader.read(context, tmp, base) { p, n -> onProgress("Reading page $p of $n…") }
                magic.startsWith("PK") || ext == "epub" -> { onProgress("Reading EPUB…"); EpubReader.read(tmp, base) }
                magic.length >= 68 && (magic.substring(60, 68) == "BOOKMOBI" || magic.substring(60, 68) == "TEXtREAd") ||
                    ext in setOf("mobi", "azw", "azw3", "prc") -> { onProgress("Reading MOBI…"); MobiReader.read(tmp.readBytes(), base) }
                else -> readText(tmp, base, ext)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun readText(file: File, title: String, ext: String): ImportedDoc {
        val bytes = file.readBytes()
        val text = decode(bytes)
        if (ext in setOf("html", "htm", "xhtml")) {
            val out = HtmlText(); out.append(org.jsoup.Jsoup.parse(text).body())
            val chapters = out.headings.takeIf { it.size >= 2 } ?: ChapterDetector.detect(out.sb.toString())
            val (t, ch) = TextCleanup.normalize(out.sb.toString(), chapters)
            return ImportedDoc(title, t, ch, "HTML")
        }
        val (t, ch) = TextCleanup.normalize(text, ChapterDetector.detect(text))
        return ImportedDoc(title, t, ch, if (ext == "md") "Markdown" else "Text")
    }

    /** UTF-8 (with or without BOM), UTF-16 BOM, else Windows-1251 for Cyrillic legacy files. */
    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val utf8 = Charsets.UTF_8.newDecoder()
        return try {
            utf8.decode(java.nio.ByteBuffer.wrap(b)).toString().removePrefix("﻿")
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(b, Charset.forName("windows-1251"))
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
