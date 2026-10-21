package io.github.pportilla.upmmail.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

object HtmlText {
    private val SKIPPED_ELEMENTS = setOf(
        "head", "script", "style", "title", "template", "noscript", "svg", "math", "object", "iframe",
        "select", "option", "button", "input", "textarea", "img", "audio", "video", "canvas", "map",
    )
    private val PARAGRAPH_ELEMENTS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "dl", "table", "pre", "address", "figure",
        "hr", "fieldset",
    )
    private val LINE_ELEMENTS = setOf(
        "div", "li", "tr", "dt", "dd", "section", "article", "header", "footer", "nav", "aside", "main",
        "form", "center", "caption", "figcaption", "tbody", "thead", "tfoot", "details", "summary",
        "blockquote",
    )
    private val CELL_ELEMENTS = setOf("td", "th")
    private val COLLAPSIBLE = Regex("[ \\t\\r\\n\\f]+")
    private val WHITESPACE = Regex("[\\s\\u00A0]+")
    private val INVISIBLE = Regex("[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\u3164\\uFEFF\\uFFA0]")

    private val WROTE_WORDS = Regex(
        // Explicit letter look-arounds: \b is ASCII-only on recent JVMs but not on Android.
        """(?i)(?<![\p{L}\p{N}])(wrote|writes|escribió|escribio|escribe|escrigué|schrieb|a écrit|ha scritto|scrisse|escreveu|skrev|kirjoitti|napisał|написал|napsal|yazdı|schreef)(?![\p{L}\p{N}])""",
    )
    private val ATTRIBUTION_START = Regex("""(?i)^(on|el|am|le|il|em|op|den|w dniu|dne)\s""")
    private val ORIGINAL_MESSAGE = Regex(
        """(?i)^-{2,}\s*(original message|mensaje original|missatge original|ursprüngliche nachricht|message d'origine|messaggio originale|mensagem original|oorspronkelijk bericht)\s*-{2,}$""",
    )
    private val OUTLOOK_RULE = Regex("^_{10,}$")
    private val HEADER_FROM = Regex("""(?i)^(from|de|von|da|van|od):\s*\S.*""")
    private val HEADER_FOLLOWING = Regex(
        """(?i)^(sent|enviado|enviado el|date|fecha|gesendet|datum|envoyé|inviato|to|para|an|à|subject|asunto)(\s+el)?:\s*.*""",
    )

    /** Readable plain text from HTML: block elements become line breaks, <br> a newline. */
    fun toPlainText(html: String): String {
        if (html.isBlank()) return ""
        val document = Jsoup.parse(html)
        val builder = TextBuilder()
        visit(document.body(), builder, preformatted = false)
        return builder.render()
    }

    /**
     * One-line preview of at most [maxLength] characters from [text] (preferred) or [html]:
     * whitespace collapsed, quoted ("> ") lines and the attribution line before them dropped.
     */
    fun snippet(text: String?, html: String?, maxLength: Int = 160): String {
        if (maxLength <= 0) return ""
        val source = when {
            !text.isNullOrBlank() -> text
            !html.isNullOrBlank() -> toPlainText(html)
            else -> return ""
        }
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }
        val kept = ArrayList<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line == "--" || line == "-- " || ORIGINAL_MESSAGE.matches(line) || OUTLOOK_RULE.matches(line)) break
            if (HEADER_FROM.matches(line) && lines.getOrNull(index + 1)?.let { HEADER_FOLLOWING.matches(it) } == true) {
                break
            }
            if (line.startsWith(">")) {
                dropAttribution(kept)
            } else {
                kept += line
            }
            index++
        }
        var result = collapse(kept.joinToString(" "))
        if (result.isEmpty()) {
            // Nothing but quoted text: show it rather than an empty preview.
            result = collapse(lines.joinToString(" ") { it.trimStart('>', ' ') })
        }
        return truncate(result, maxLength)
    }

    private fun dropAttribution(kept: MutableList<String>) {
        while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.lastIndex)
        val last = kept.lastOrNull() ?: return
        if (!last.endsWith(":")) return
        val looksLikeAttribution = WROTE_WORDS.containsMatchIn(last) || ATTRIBUTION_START.containsMatchIn(last)
        if (!looksLikeAttribution) return
        kept.removeAt(kept.lastIndex)
        // Plain-text mailers wrap long attributions: "On Mon, ... Ana <ana@upm.es>\nwrote:".
        if (!ATTRIBUTION_START.containsMatchIn(last)) {
            val previous = kept.lastOrNull()
            if (previous != null && ATTRIBUTION_START.containsMatchIn(previous) && !previous.endsWith(":")) {
                kept.removeAt(kept.lastIndex)
            }
        }
    }

    private fun collapse(value: String): String =
        value.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim()

    private fun truncate(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        if (maxLength == 1) return "…"
        var end = maxLength - 1
        val lastSpace = value.lastIndexOf(' ', end)
        if (lastSpace > 0 && end - lastSpace <= 20) end = lastSpace
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).trimEnd() + "…"
    }

    private fun visit(node: Node, builder: TextBuilder, preformatted: Boolean) {
        when (node) {
            is TextNode -> {
                if (preformatted) builder.preformatted(node.wholeText) else builder.text(node.wholeText)
            }
            is Element -> {
                val name = node.normalName().lowercase(Locale.ROOT)
                when {
                    name in SKIPPED_ELEMENTS -> return
                    name == "br" -> {
                        builder.lineBreak()
                        return
                    }
                }
                val paragraph = name in PARAGRAPH_ELEMENTS
                val line = name in LINE_ELEMENTS
                if (paragraph || line) builder.blockBoundary(paragraph)
                if (name == "blockquote") builder.depth++
                if (name == "li") {
                    val parentName = node.parent()?.normalName()
                    val marker = if (parentName == "ol") "${node.elementSiblingIndex() + 1}. " else "• "
                    builder.text(marker)
                }
                if (name in CELL_ELEMENTS) builder.space()
                val pre = preformatted || name == "pre" || name == "textarea"
                for (child in node.childNodes()) visit(child, builder, pre)
                if (name in CELL_ELEMENTS) builder.space()
                if (name == "blockquote") {
                    builder.blockBoundary(paragraph = false)
                    builder.depth--
                }
                if (paragraph || line) builder.blockBoundary(paragraph)
            }
        }
    }

    /** Accumulates lines with their quote depth. */
    private class TextBuilder {
        private val lines = ArrayList<Pair<Int, StringBuilder>>()
        private var current: StringBuilder? = null
        private var pendingSpace = false
        private var blankLineWanted = false
        var depth = 0

        fun text(raw: String) {
            val collapsed = raw.replace(COLLAPSIBLE, " ")
            if (collapsed.isEmpty()) return
            val startsWithSpace = collapsed.first() == ' '
            val endsWithSpace = collapsed.last() == ' '
            val words = collapsed.trim(' ').replace('\u00A0', ' ')
            if (startsWithSpace) space()
            if (words.isNotEmpty()) {
                val line = current ?: openLine()
                if (pendingSpace && line.isNotEmpty() && line.last() != ' ') line.append(' ')
                pendingSpace = false
                line.append(words)
            }
            if (endsWithSpace) space()
        }

        fun preformatted(raw: String) {
            val segments = raw.replace("\r\n", "\n").split('\n')
            segments.forEachIndexed { index, segment ->
                if (index > 0) lineBreak()
                if (segment.isNotEmpty()) {
                    val line = current ?: openLine()
                    if (pendingSpace && line.isNotEmpty()) line.append(' ')
                    pendingSpace = false
                    line.append(segment.replace('\u00A0', ' '))
                }
            }
        }

        fun space() {
            if (current?.isNotEmpty() == true) pendingSpace = true
        }

        fun lineBreak() {
            if (current == null) lines += depth to StringBuilder() else current = null
            pendingSpace = false
        }

        fun blockBoundary(paragraph: Boolean) {
            current = null
            pendingSpace = false
            if (paragraph && lines.isNotEmpty()) blankLineWanted = true
        }

        private fun openLine(): StringBuilder {
            if (blankLineWanted) {
                val last = lines.lastOrNull()
                if (last != null && last.second.isNotEmpty()) lines += minOf(last.first, depth) to StringBuilder()
                blankLineWanted = false
            }
            val line = StringBuilder()
            lines += depth to line
            current = line
            return line
        }

        fun render(): String {
            val rendered = ArrayList<String>(lines.size)
            for ((depth, content) in lines) {
                val text = content.toString().trimEnd()
                val line = when {
                    depth == 0 -> text
                    text.isEmpty() -> ">".repeat(depth)
                    else -> ">".repeat(depth) + " " + text
                }
                // Never more than one empty line in a row.
                if (line.isEmpty() && (rendered.isEmpty() || rendered.last().isEmpty())) continue
                rendered += line
            }
            while (rendered.isNotEmpty() && rendered.last().isBlank()) rendered.removeAt(rendered.lastIndex)
            return rendered.joinToString("\n")
        }
    }
}
