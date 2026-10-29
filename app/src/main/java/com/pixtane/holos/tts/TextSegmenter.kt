package com.pixtane.holos.tts

/** A sentence-sized piece of the source text: [start, end) offsets into the original string. */
data class Seg(val start: Int, val end: Int, val paraEnd: Boolean) {
    val length get() = end - start
}

/**
 * Splits text into sentences (and long sentences into clauses) while keeping exact
 * character offsets, so the player can highlight what is being spoken.
 */
object TextSegmenter {
    private const val MAX_LEN = 240

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "vs", "e.g", "i.e", "approx", "no", "fig", "inc", "ltd", "jr", "sr",
        "z.b", "bzw", "ca", "nr", "str", "evtl", "ggf", "vgl", "inkl", "u.a", "d.h", "s",
        "ім", "т.д", "т.п", "проф", "р", "рр", "ст", "див", "напр", "тис", "млн", "млрд", "грн", "вул", "м", "с", "т",
    )

    fun split(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        for (p in paragraphs(text)) sentences(text, p.first, p.last + 1, out)
        return out
    }

    /** Paragraph ranges. Single line breaks inside a sentence (PDF copy/paste) are treated as spaces. */
    private fun paragraphs(text: String): List<IntRange> {
        val res = ArrayList<IntRange>()
        val n = text.length
        var start = 0
        var i = 0
        while (i < n) {
            if (text[i] == '\n') {
                var j = i
                var newlines = 0
                while (j < n && text[j].isWhitespace()) { if (text[j] == '\n') newlines++; j++ }
                val prevEnd = lastNonSpace(text, start, i)
                val lineStart = text.lastIndexOf('\n', i - 1).let { if (it < start) start else it + 1 }
                val lineLen = i - lineStart
                val prev = if (prevEnd >= 0) text[prevEnd] else ' '
                val next = if (j < n) text[j] else ' '
                val hardBreak = newlines >= 2 ||
                    next in "-–—•*·" ||
                    (next.isDigit() && j + 1 < n && text[j + 1] in ".)") ||
                    // A line continuing in lowercase is a soft wrap (e.g. text copied from a PDF).
                    (!next.isLowerCase() && (prev in ".!?…:;\"»”)" || lineLen < 40))
                if (hardBreak) {
                    if (i > start) res.add(start until i)
                    start = j
                }
                i = j
            } else i++
        }
        if (start < n) res.add(start until n)
        return res
    }

    private fun lastNonSpace(text: String, from: Int, to: Int): Int {
        var k = to - 1
        while (k >= from && text[k].isWhitespace()) k--
        return k
    }

    private fun sentences(text: String, ps: Int, pe: Int, out: MutableList<Seg>) {
        var s = ps
        var i = ps
        while (i < pe) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < pe && text[j] in ".!?…") j++
                while (j < pe && text[j] in "\"'»”’)]") j++
                if (j >= pe) break
                if (text[j].isWhitespace()) {
                    var m = j
                    while (m < pe && text[m].isWhitespace()) m++
                    if (m < pe && startsSentence(text[m]) && !(c == '.' && isAbbreviation(text, s, i))) {
                        add(text, s, j, false, out)
                        s = m; i = m
                        continue
                    }
                }
                i = j
                continue
            }
            i++
        }
        add(text, s, pe, true, out)
    }

    private fun startsSentence(c: Char) =
        c.isUpperCase() || c.isDigit() || c in "\"'«„“‘([—–-"

    private fun isAbbreviation(text: String, from: Int, dot: Int): Boolean {
        var k = dot - 1
        while (k >= from && (text[k].isLetter() || text[k] == '.')) k--
        val token = text.substring(k + 1, dot).lowercase()
        if (token.isEmpty()) return false
        if (token.length == 1 && token[0].isLetter()) return true // initials: "J. K. Rowling"
        return token in ABBREVIATIONS
    }

    private fun add(text: String, a0: Int, b0: Int, paraEnd: Boolean, out: MutableList<Seg>) {
        var a = a0
        var b = b0
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a >= b || (a until b).none { text[it].isLetterOrDigit() }) {
            if (paraEnd && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out.add(last.copy(paraEnd = true))
            }
            return
        }
        var st = a
        while (b - st > MAX_LEN) {
            val limit = st + MAX_LEN
            val minCut = st + MAX_LEN / 3
            var cut = -1
            for (k in limit downTo minCut) {
                val ch = text[k - 1]
                if ((ch == ',' || ch == ';' || ch == ':' || ch == '—' || ch == '–' || ch == ')') && text[k].isWhitespace()) {
                    cut = k; break
                }
            }
            if (cut < 0) {
                for (k in limit downTo minCut) if (text[k].isWhitespace()) { cut = k; break }
            }
            if (cut < 0) cut = limit
            out.add(Seg(st, cut, false))
            st = cut
            while (st < b && text[st].isWhitespace()) st++
        }
        if (st < b) out.add(Seg(st, b, paraEnd))
    }
}
