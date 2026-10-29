package com.pixtane.holos.importer

import com.pixtane.holos.t
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/** Extracts text page by page; PDF bookmarks (outline) become chapters. */
object PdfReader {
    private val PAGE_NUMBER_LINE = Regex("(?m)^\\s*(?:-\\s*)?\\d{1,4}(?:\\s*-)?\\s*$")

    fun read(context: Context, file: File, fallbackTitle: String, onProgress: (Int, Int) -> Unit): ImportedDoc {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) {
                throw IOException(t("This PDF does not allow text extraction", "Цей PDF забороняє копіювання тексту"))
            }
            val pages = doc.numberOfPages
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val sb = StringBuilder()
            val pageStart = IntArray(pages + 1)
            for (p in 1..pages) {
                pageStart[p - 1] = sb.length
                stripper.startPage = p
                stripper.endPage = p
                val raw = runCatching { stripper.getText(doc) }.getOrDefault("")
                sb.append(PAGE_NUMBER_LINE.replace(raw, "").trim())
                sb.append("\n\n")
                if (p % 5 == 0 || p == pages) onProgress(p, pages)
            }
            pageStart[pages] = sb.length
            if (sb.isBlank()) throw IOException(t("This PDF has no text layer (scanned images need OCR)", "У цьому PDF немає текстового шару (скановані сторінки потребують розпізнавання)"))

            val chapters = ArrayList<Chapter>()
            fun walk(node: PDOutlineNode, depth: Int) {
                var item: PDOutlineItem? = node.firstChild
                while (item != null) {
                    val page: PDPage? = runCatching {
                        item!!.findDestinationPage(doc) ?: (item!!.destination as? PDPageDestination)?.page
                    }.getOrNull()
                    if (page != null) {
                        val idx = doc.pages.indexOf(page)
                        if (idx >= 0) chapters.add(Chapter(item.title ?: "", pageStart[idx]))
                    }
                    if (depth < 1) walk(item, depth + 1)
                    item = item.nextSibling
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }

            val title = doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
            var ch: List<Chapter> = chapters.filter { it.title.isNotBlank() }.sortedBy { it.offset }.distinctBy { it.offset }
            if (ch.size < 2) ch = ChapterDetector.detect(sb.toString())
            val (text, finalCh) = TextCleanup.normalize(sb.toString(), ch)
            return ImportedDoc(title, text, finalCh, "PDF")
        }
    }
}
