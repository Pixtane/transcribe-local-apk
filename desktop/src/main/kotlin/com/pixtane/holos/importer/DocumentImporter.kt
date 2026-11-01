package com.pixtane.holos.importer

import com.pixtane.holos.t
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/** Opens a user-picked file (txt, md, pdf, epub, mobi/azw/prc) and turns it into text + chapters. */
object DocumentImporter {
    val EXTENSIONS = listOf("txt", "md", "html", "htm", "xhtml", "pdf", "epub", "mobi", "azw", "azw3", "prc")

    fun import(file: File, onProgress: (String) -> Unit = {}): ImportedDoc {
        if (!file.isFile) throw IOException(t("Could not open the file", "Не вдалося відкрити файл"))
        val name = file.name
        val base = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Document" }
        val ext = name.substringAfterLast('.', "").lowercase()
        onProgress(t("Reading file…", "Читання файлу…"))
        val head = file.inputStream().use { s -> ByteArray(68).also { s.read(it) } }
        val magic = String(head, Charsets.ISO_8859_1)
        return when {
            magic.startsWith("%PDF") || ext == "pdf" ->
                PdfReader.read(file, base) { p, n -> onProgress(t("Reading page $p of $n…", "Читання сторінки $p з $n…")) }
            magic.startsWith("PK") || ext == "epub" -> { onProgress(t("Reading EPUB…", "Читання EPUB…")); EpubReader.read(file, base) }
            magic.length >= 68 && (magic.substring(60, 68) == "BOOKMOBI" || magic.substring(60, 68) == "TEXtREAd") ||
                ext in setOf("mobi", "azw", "azw3", "prc") -> { onProgress(t("Reading MOBI…", "Читання MOBI…")); MobiReader.read(file.readBytes(), base) }
            else -> readText(file, base, ext)
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
}
