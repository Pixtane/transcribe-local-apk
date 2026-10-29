package com.pixtane.holos.importer

/** A chapter starts at [offset] (a character index into the document text). */
data class Chapter(val title: String, val offset: Int)

data class ImportedDoc(
    val title: String,
    val text: String,
    val chapters: List<Chapter>,
    val format: String,
    val language: String? = null,
)

object TextCleanup {
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{Ll})")
    private val TRAILING_SPACES = Regex("[ \\t\\u00A0]+\\n")
    private val MANY_BLANK_LINES = Regex("\\n{3,}")

    /**
     * Normalizes extracted text while keeping chapter offsets valid: every edit is applied
     * piece by piece so offsets can be remapped.
     */
    fun normalize(text: String, chapters: List<Chapter>): Pair<String, List<Chapter>> {
        if (chapters.isEmpty()) return clean(text) to emptyList()
        val sorted = chapters.sortedBy { it.offset }
        val sb = StringBuilder()
        val out = ArrayList<Chapter>()
        val bounds = sorted.map { it.offset.coerceIn(0, text.length) } + text.length
        if (bounds[0] > 0) sb.append(clean(text.substring(0, bounds[0])))
        for ((k, ch) in sorted.withIndex()) {
            val a = bounds[k]; val b = bounds[k + 1]
            if (b < a) continue
            val piece = clean(text.substring(a, b))
            if (sb.isNotEmpty() && piece.isNotEmpty()) {
                while (sb.isNotEmpty() && sb.last().isWhitespace()) sb.setLength(sb.length - 1)
                sb.append("\n\n")
            }
            val title = ch.title.replace(Regex("\\s+"), " ").trim()
            if (piece.isNotBlank() && title.isNotEmpty()) out.add(Chapter(title, sb.length))
            sb.append(piece)
        }
        val finalText = sb.toString().trimEnd()
        return finalText to out.filter { it.offset < finalText.length }.distinctBy { it.offset }
    }

    fun clean(s: String): String {
        var t = s.replace("\r\n", "\n").replace('\r', '\n').replace('­', '\u0000').replace("\u0000", "")
        t = HYPHEN_BREAK.replace(t, "$1$2")
        t = TRAILING_SPACES.replace(t, "\n")
        t = MANY_BLANK_LINES.replace(t, "\n\n")
        return t.trim()
    }
}

/** Finds chapter headings in plain text ("Chapter 3", "Розділ 2", "Kapitel 1", "# Title" …). */
object ChapterDetector {
    private val HEADING = Regex(
        "^(?:#{1,3}\\s+.+|" +
            "(?:chapter|part|book|prologue|epilogue|introduction|preface|afterword|" +
            "глава|розділ|частина|книга|пролог|епілог|вступ|передмова|післямова|" +
            "kapitel|teil|buch|prolog|epilog|einleitung|vorwort|nachwort)" +
            "(?:\\s+[\\p{L}\\p{N}IVXLCDM.:—–-]+.*)?|" +
            "[IVXLC]{1,7}\\.?)$",
        RegexOption.IGNORE_CASE,
    )

    private val BARE = Regex(
        "^(?:chapter|part|book|глава|розділ|частина|книга|kapitel|teil|buch)?\\s*[\\p{N}IVXLCDM]+\\.?$",
        RegexOption.IGNORE_CASE,
    )

    /** "CHAPTER I." followed by a short title line becomes "CHAPTER I. Down the Rabbit-Hole". */
    fun enrich(text: String, chapters: List<Chapter>): List<Chapter> = chapters.map { c ->
        if (!BARE.matches(c.title.trim())) return@map c
        val lineEnd = text.indexOf('\n', c.offset).let { if (it < 0) text.length else it }
        var k = lineEnd
        while (k < text.length && text[k].isWhitespace()) k++
        val nextEnd = text.indexOf('\n', k).let { if (it < 0) text.length else it }
        val next = text.substring(k, nextEnd).trim()
        if (next.isNotEmpty() && next.length <= 70 && !next.endsWith(",") && k - lineEnd <= 3) c.copy(title = "${c.title.trim()} $next") else c
    }

    /** Drops headings packed tightly together (a printed table of contents, not chapters). */
    fun declutter(list: List<Chapter>): List<Chapter> {
        if (list.size < 3) return list
        val sorted = list.sortedBy { it.offset }
        return sorted.filterIndexed { i, c ->
            val prevGap = if (i > 0) c.offset - sorted[i - 1].offset else Int.MAX_VALUE
            val nextGap = if (i < sorted.size - 1) sorted[i + 1].offset - c.offset else Int.MAX_VALUE
            prevGap >= 150 && nextGap >= 150
        }
    }

    fun detect(text: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        var lineStart = 0
        var prevBlank = true
        val n = text.length
        while (lineStart <= n) {
            var end = text.indexOf('\n', lineStart)
            if (end < 0) end = n
            val line = text.substring(lineStart, end).trim()
            val nextBlank = end >= n || text.indexOf('\n', end + 1).let { nx -> text.substring(end + 1, if (nx < 0) n else nx).isBlank() }
            if (line.length in 1..80 && (prevBlank || nextBlank) && HEADING.matches(line) && !line.endsWith(",")) {
                val title = line.trimStart('#', ' ')
                out.add(Chapter(title, lineStart + (text.substring(lineStart, end).length - text.substring(lineStart, end).trimStart().length)))
            }
            prevBlank = line.isEmpty()
            lineStart = end + 1
        }
        // A "chapter" list with one entry or absurdly many tiny entries is noise.
        if (out.size < 2) return emptyList()
        val avg = n / out.size
        return if (avg < 300) emptyList() else enrich(text, declutter(out))
    }
}
