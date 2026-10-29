package com.pixtane.holos.importer

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter

/**
 * Converts (X)HTML into plain text with paragraph breaks, recording where element ids and
 * headings start so tables of contents can be mapped to character offsets.
 */
class HtmlText {
    val sb = StringBuilder()
    val ids = HashMap<String, Int>()
    val headings = ArrayList<Chapter>()

    private val skip = setOf("script", "style", "head", "title", "noscript", "svg", "math", "nav")
    private val block = setOf(
        "p", "div", "section", "article", "header", "footer", "blockquote", "pre", "table", "tr",
        "ul", "ol", "dl", "dt", "dd", "figure", "figcaption", "aside", "main", "body", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "mbp:pagebreak",
    )
    private val line = setOf("li", "br", "td", "th")
    private val headingTags = setOf("h1", "h2", "h3")

    fun append(root: Element) {
        root.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when (node) {
                    is TextNode -> appendText(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in skip) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        if (tag in block) paragraph()
                        else if (tag in line) newline()
                        if (tag == "br") newline()
                        val id = node.id()
                        if (id.isNotEmpty()) ids.putIfAbsent(id, sb.length)
                        node.attr("name").takeIf { it.isNotEmpty() && tag == "a" }?.let { ids.putIfAbsent(it, sb.length) }
                        node.attr("filepos").takeIf { it.isNotEmpty() }?.let { ids.putIfAbsent("filepos$it", sb.length) }
                        if (tag in headingTags) {
                            val t = node.text().trim()
                            if (t.isNotEmpty() && t.length <= 120) headings.add(Chapter(t, sb.length))
                        }
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in block) paragraph() else if (tag == "li" || tag == "tr") newline()
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        })
        paragraph()
    }

    private fun appendText(raw: String) {
        var lastSpace = sb.isEmpty() || sb.last().isWhitespace()
        for (c in raw) {
            val ws = c.isWhitespace() || c == ' '
            if (ws) {
                if (!lastSpace) { sb.append(' '); lastSpace = true }
            } else {
                sb.append(c); lastSpace = false
            }
        }
    }

    private fun trimTrailingSpaces() {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
    }

    fun paragraph() {
        trimTrailingSpaces()
        if (sb.isEmpty()) return
        if (sb.endsWith("\n\n")) return
        sb.append(if (sb.endsWith("\n")) "\n" else "\n\n")
    }

    fun newline() {
        trimTrailingSpaces()
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
    }
}
