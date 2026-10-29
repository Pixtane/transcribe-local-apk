package com.pixtane.holos.importer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Reads EPUB 2/3: spine order for text, nav.xhtml or toc.ncx for chapters. */
object EpubReader {
    fun read(file: File, fallbackTitle: String): ImportedDoc {
        ZipFile(file).use { zip ->
            fun entryText(path: String): String? =
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

            val container = entryText("META-INF/container.xml") ?: throw IOException("Not an EPUB (no container.xml)")
            val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: throw IOException("EPUB has no package file")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = Jsoup.parse(entryText(opfPath) ?: throw IOException("Missing $opfPath"), "", Parser.xmlParser())

            fun resolve(base: String, href: String): String {
                val clean = decode(href.substringBefore('#'))
                val parts = ArrayList<String>()
                if (base.isNotEmpty()) parts.addAll(base.split('/'))
                for (seg in clean.split('/')) when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
                return parts.joinToString("/")
            }

            if (zip.getEntry("META-INF/encryption.xml") != null &&
                entryText("META-INF/encryption.xml")?.contains("EncryptedData") == true &&
                entryText("META-INF/encryption.xml")?.contains("font", ignoreCase = true) != true
            ) throw IOException("This EPUB is DRM-protected")

            val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
            val language = opf.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()

            data class Item(val href: String, val type: String, val props: String)
            val items = HashMap<String, Item>()
            for (it in opf.getElementsByTag("item")) {
                items[it.attr("id")] = Item(resolve(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
            }
            val spineEl = opf.getElementsByTag("spine").firstOrNull()
            val spine = spineEl?.getElementsByTag("itemref")?.mapNotNull { items[it.attr("idref")] }
                ?.filter { it.type.contains("html") || it.href.endsWith("html") || it.href.endsWith("htm") }
                ?: emptyList()
            if (spine.isEmpty()) throw IOException("EPUB has no readable content")

            // Text of every spine document, recording where each file and each id starts.
            val out = HtmlText()
            val fileStart = HashMap<String, Int>()
            val idStart = HashMap<String, Int>()
            for (item in spine) {
                val html = entryText(item.href) ?: continue
                val doc: Document = Jsoup.parse(html)
                val before = out.ids.keys.toSet()
                fileStart[item.href] = out.sb.length
                out.append(doc.body() ?: doc)
                for ((id, off) in out.ids) if (id !in before) idStart.putIfAbsent("${item.href}#$id", off)
                out.ids.clear()
            }

            // Table of contents.
            val toc = ArrayList<Pair<String, String>>() // title, resolved target (file#frag)
            val nav = items.values.firstOrNull { it.props.split(' ').contains("nav") }
            if (nav != null) {
                entryText(nav.href)?.let { html ->
                    val d = Jsoup.parse(html)
                    val navEl = d.select("nav").firstOrNull { it.attr("epub:type").contains("toc") } ?: d.select("nav").firstOrNull()
                    val base = nav.href.substringBeforeLast('/', "")
                    navEl?.select("a[href]")?.forEach { a ->
                        val depth = a.parents().count { it.normalName() == "ol" }
                        if (depth <= 2) toc.add(a.text().trim() to target(resolve(base, a.attr("href")), a.attr("href")))
                    }
                }
            }
            if (toc.isEmpty()) {
                val ncxId = spineEl?.attr("toc")
                val ncx = items[ncxId] ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
                ncx?.let { entryText(it.href) }?.let { xml ->
                    val d = Jsoup.parse(xml, "", Parser.xmlParser())
                    val base = ncx.href.substringBeforeLast('/', "")
                    for (np in d.getElementsByTag("navPoint")) {
                        val depth = np.parents().count { it.normalName() == "navpoint" || it.tagName() == "navPoint" }
                        if (depth > 1) continue
                        val label = np.getElementsByTag("navLabel").firstOrNull()?.text()?.trim() ?: continue
                        val src = np.getElementsByTag("content").firstOrNull()?.attr("src") ?: continue
                        toc.add(label to target(resolve(base, src), src))
                    }
                }
            }

            var chapters = toc.mapNotNull { (t, tgt) ->
                val off = idStart[tgt] ?: fileStart[tgt.substringBefore('#')]
                if (off == null || t.isEmpty()) null else Chapter(t, off)
            }.sortedBy { it.offset }.distinctBy { it.offset }
            if (chapters.size < 2) chapters = ChapterDetector.declutter(out.headings.distinctBy { it.offset })
            val (text, ch) = TextCleanup.normalize(out.sb.toString(), chapters)
            return ImportedDoc(title, text, ch, "EPUB", language)
        }
    }

    private fun target(resolvedPath: String, rawHref: String): String {
        val frag = rawHref.substringAfter('#', "")
        return if (frag.isEmpty()) resolvedPath else "$resolvedPath#${decode(frag)}"
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
