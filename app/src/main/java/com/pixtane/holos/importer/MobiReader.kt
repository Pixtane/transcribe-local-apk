package com.pixtane.holos.importer

import com.pixtane.holos.t
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

/**
 * Reads Mobipocket / Kindle (.mobi, .azw, .prc) books without DRM: PalmDOC or HUFF/CDIC
 * compressed text records are decoded and the resulting HTML converted to plain text.
 */
object MobiReader {
    fun read(data: ByteArray, fallbackTitle: String): ImportedDoc {
        if (data.size < 78 + 8) throw IOException(t("File is too small to be a MOBI book", "Файл замалий для книжки MOBI"))
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") throw IOException(t("Not a MOBI book", "Це не книжка MOBI"))
        val numRecords = u16(data, 76)
        val offsets = IntArray(numRecords + 1) { i -> if (i < numRecords) u32(data, 78 + i * 8).toInt() else data.size }
        fun record(i: Int): ByteArray = data.copyOfRange(offsets[i], offsets[i + 1])

        val r0 = record(0)
        val compression = u16(r0, 0)
        val textRecords = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw IOException(t("This book is DRM-protected and cannot be read", "Книжку захищено DRM, її неможливо прочитати"))

        var charset: Charset = Charsets.ISO_8859_1
        var title = fallbackTitle
        var extraFlags = 0
        var huffFirst = 0
        var huffCount = 0
        val hasMobiHeader = r0.size > 20 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (hasMobiHeader) {
            val headerLen = u32(r0, 20).toInt()
            charset = when (u32(r0, 28).toInt()) {
                65001 -> Charsets.UTF_8
                1252 -> Charset.forName("windows-1252")
                else -> Charsets.ISO_8859_1
            }
            val nameOff = u32(r0, 84).toInt()
            val nameLen = u32(r0, 88).toInt()
            if (nameOff > 0 && nameLen in 1..1000 && nameOff + nameLen <= r0.size) {
                title = String(r0, nameOff, nameLen, charset).trim().ifEmpty { fallbackTitle }
            }
            huffFirst = u32(r0, 112).toInt()
            huffCount = u32(r0, 116).toInt()
            if (headerLen >= 0xE4 && r0.size >= 0xF4) extraFlags = u16(r0, 0xF2)
        }

        val huff = if (compression == 17480) {
            Huffcdic().also { h ->
                h.loadHuff(record(huffFirst))
                for (i in 1 until huffCount) h.loadCdic(record(huffFirst + i))
            }
        } else null

        val out = ByteArrayOutputStream(data.size * 2)
        for (i in 1..textRecords.coerceAtMost(numRecords - 1)) {
            val rec = record(i)
            val size = rec.size - trailingSize(rec, rec.size, extraFlags)
            if (size <= 0) continue
            val body = rec.copyOfRange(0, size)
            val decoded = when (compression) {
                1 -> body
                2 -> palmDoc(body)
                17480 -> huff!!.unpack(body)
                else -> throw IOException("Unsupported MOBI compression ($compression)")
            }
            out.write(decoded)
        }
        // Table-of-contents links point at byte offsets ("filepos"); drop an anchor at each
        // target so the offsets survive HTML-to-text conversion.
        val raw = out.toByteArray()
        val latin = String(raw, Charsets.ISO_8859_1)
        val tocLinks = FILEPOS_LINK.findAll(latin).mapNotNull { m ->
            val target = m.groupValues[1].toLongOrNull()?.toInt() ?: return@mapNotNull null
            if (target <= m.range.first || target >= raw.size) return@mapNotNull null
            val inner = String(raw, m.groups[2]!!.range.first, m.groups[2]!!.range.count(), charset)
            target to Jsoup.parse(inner).text().trim()
        }.filter { it.second.isNotEmpty() }.toList()
        val marked = ByteArrayOutputStream(raw.size + tocLinks.size * 24)
        var last = 0
        for (t in tocLinks.map { it.first }.distinct().sorted()) {
            marked.write(raw, last, t - last)
            marked.write("<a id=\"hfp$t\"></a>".toByteArray(Charsets.ISO_8859_1))
            last = t
        }
        marked.write(raw, last, raw.size - last)

        // MOBI markup puts a non-standard <guide> in <head>, which confuses HTML parsers.
        val html = String(marked.toByteArray(), charset).replace(HEAD, "")
        val doc = Jsoup.parse(html)
        val text = HtmlText()
        text.append(doc.body() ?: doc)
        var chapters: List<Chapter> = tocLinks.mapNotNull { (t, title) -> text.ids["hfp$t"]?.let { Chapter(title, it) } }
            .sortedBy { it.offset }.distinctBy { it.offset }
        if (chapters.size < 2) chapters = ChapterDetector.declutter(text.headings.distinctBy { it.offset })
        if (chapters.size < 2) chapters = ChapterDetector.detect(text.sb.toString())
        val (clean, ch0) = TextCleanup.normalize(text.sb.toString(), chapters)
        val ch = ChapterDetector.enrich(clean, ch0)
        if (clean.isBlank()) throw IOException(t("No readable text found in this book", "У цій книжці немає тексту для читання"))
        return ImportedDoc(title, clean, ch, "MOBI")
    }

    private val HEAD = Regex("(?is)<head>.*?</head>")
    private val FILEPOS_LINK = Regex("(?is)<a\\s[^>]*filepos\\s*=\\s*[\"']?0*(\\d+)[\"']?[^>]*>(.*?)</a>")

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    /** Size of the trailing entries appended to each text record (MOBI "extra data flags"). */
    private fun trailingSize(data: ByteArray, size: Int, flags: Int): Int {
        fun entrySize(end: Int): Int {
            var bitpos = 0; var result = 0; var p = end
            if (p <= 0) return 0
            while (true) {
                val v = data[p - 1].toInt() and 0xff
                result = result or ((v and 0x7f) shl bitpos)
                bitpos += 7; p--
                if ((v and 0x80) != 0 || bitpos >= 28 || p == 0) return result
            }
        }
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += entrySize(size - num)
            f = f shr 1
        }
        if (flags and 1 != 0 && size - num - 1 >= 0) num += (data[size - num - 1].toInt() and 0x3) + 1
        return num.coerceIn(0, size)
    }

    fun palmDoc(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStreamEx(src.size * 2)
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xff
            when {
                c == 0 || c in 0x09..0x7f -> out.write(c)
                c in 1..8 -> { var k = 0; while (k < c && i < src.size) { out.write(src[i++].toInt() and 0xff); k++ } }
                c in 0x80..0xbf -> {
                    if (i >= src.size) break
                    val pair = ((c shl 8) or (src[i++].toInt() and 0xff)) and 0x3fff
                    val dist = pair shr 3
                    val len = (pair and 7) + 3
                    out.copyBack(dist, len)
                }
                else -> { out.write(' '.code); out.write(c xor 0x80) }
            }
        }
        return out.toByteArray()
    }

    private class ByteArrayOutputStreamEx(size: Int) : ByteArrayOutputStream(size) {
        fun copyBack(dist: Int, len: Int) {
            if (dist <= 0 || dist > count) return
            for (k in 0 until len) write(buf[count - dist].toInt())
        }
    }

    /** HUFF/CDIC decompressor (port of the well-known Mobipocket reference algorithm). */
    private class Huffcdic {
        private val dict1Len = IntArray(256)
        private val dict1Term = BooleanArray(256)
        private val dict1Max = LongArray(256)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val dictionary = ArrayList<ByteArray?>()
        private val dictFlag = ArrayList<Boolean>()

        fun loadHuff(h: ByteArray) {
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "HUFF") throw IOException("Bad HUFF record")
            val off1 = u32(h, 8).toInt()
            val off2 = u32(h, 12).toInt()
            for (i in 0 until 256) {
                val v = u32(h, off1 + i * 4)
                val len = (v and 0x1f).toInt()
                dict1Len[i] = len
                dict1Term[i] = (v and 0x80) != 0L
                dict1Max[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
            }
            for (len in 1..32) {
                val mn = u32(h, off2 + (len - 1) * 8)
                val mx = u32(h, off2 + (len - 1) * 8 + 4)
                minCode[len] = mn shl (32 - len)
                maxCode[len] = ((mx + 1) shl (32 - len)) - 1
            }
        }

        fun loadCdic(c: ByteArray) {
            if (String(c, 0, 4, Charsets.ISO_8859_1) != "CDIC") throw IOException("Bad CDIC record")
            val phrases = u32(c, 8).toInt()
            val bits = u32(c, 12).toInt()
            val n = minOf(1 shl bits, phrases - dictionary.size)
            for (k in 0 until n) {
                val off = u16(c, 16 + k * 2)
                val blen = u16(c, 16 + off)
                val start = 18 + off
                dictionary.add(c.copyOfRange(start, start + (blen and 0x7fff)))
                dictFlag.add((blen and 0x8000) != 0)
            }
        }

        fun unpack(input: ByteArray, depth: Int = 0): ByteArray {
            if (depth > 32) return ByteArray(0)
            var bitsLeft = input.size.toLong() * 8
            val data = input.copyOf(input.size + 8)
            var pos = 0
            var x = u64(data, pos)
            var n = 32
            val out = ByteArrayOutputStream()
            while (true) {
                if (n <= 0) {
                    pos += 4
                    x = u64(data, pos)
                    n += 32
                }
                val code = (x ushr n) and 0xFFFFFFFFL
                val idx = (code ushr 24).toInt()
                var len = dict1Len[idx]
                var max = dict1Max[idx]
                if (!dict1Term[idx]) {
                    while (len < 32 && code < minCode[len]) len++
                    max = maxCode[len]
                }
                n -= len
                bitsLeft -= len
                if (bitsLeft < 0 || len == 0) break
                val r = ((max - code) ushr (32 - len)).toInt()
                if (r < 0 || r >= dictionary.size) break
                var slice = dictionary[r] ?: break
                if (!dictFlag[r]) {
                    dictionary[r] = null
                    slice = unpack(slice, depth + 1)
                    dictionary[r] = slice
                    dictFlag[r] = true
                }
                out.write(slice)
            }
            return out.toByteArray()
        }

        private fun u64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xff)
            return v
        }
    }
}
