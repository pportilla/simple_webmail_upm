package io.github.pportilla.upmmail.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import java.io.ByteArrayOutputStream
import java.util.Locale

object HtmlSanitizer {
    /** Elements removed together with their content. */
    private val REMOVED_ELEMENTS = setOf(
        "script", "noscript", "iframe", "frame", "frameset", "object", "embed", "applet", "param",
        "input", "button", "textarea", "select", "option", "optgroup", "datalist", "keygen",
        "base", "link", "meta", "foreignobject", "template", "portal", "dialog",
    )

    /** Elements dropped while keeping their content (a form around a whole newsletter...). */
    private val UNWRAPPED_ELEMENTS = setOf("form")

    private val DANGEROUS_SCHEMES = listOf("javascript:", "vbscript:", "livescript:", "mocha:")
    private val DANGEROUS_DATA = listOf("data:text/html", "data:application/xhtml", "data:text/xml", "data:application/xml")
    private val ALWAYS_REMOVED_ATTRIBUTES = setOf("srcdoc", "formaction", "action", "ping", "manifest", "codebase")

    private val CSS_REMOTE_URL = Regex("""(?i)url\(\s*['"]?\s*(https?:|//)""")
    private val CSS_REMOTE_IMPORT = Regex("""(?i)@import\s+(url\(\s*)?['"]?\s*(https?:|//)""")
    private val CSS_EXPRESSION = Regex("""(?i)(expression\s*\(|-moz-binding|behavior\s*:)""")
    private val CID_REFERENCE = Regex("""(?i)cid:([^"'\s)>]+)""")

    private val URL_PATTERN = Regex(
        """(?i)\b((?:https?://|www\.)[^\s<>"]+)|([A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,})""",
    )
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"*"
    private const val BLOCKQUOTE_OPEN =
        "<blockquote type=\"cite\" style=\"margin:0 0 0 0.8ex;border-left:2px solid #aaa;padding-left:1ex\">"

    private const val DEFAULT_STYLE =
        "body{margin:0;overflow-wrap:anywhere;word-wrap:break-word;}" +
            "img{max-width:100%;height:auto;}" +
            "table{max-width:100%;}" +
            "pre{white-space:pre-wrap;}" +
            "blockquote{margin:0 0 0 0.8ex;border-left:2px solid #aaa;padding-left:1ex;}"

    /**
     * Safe HTML for the read-only message WebView: removes scripts, iframes, objects, forms,
     * event-handler attributes, javascript: URLs, meta refresh and base tags; keeps styles,
     * tables and images (remote loads are blocked by the WebView until the user allows them).
     */
    fun sanitize(html: String): String {
        val document = Jsoup.parse(html)
        val comments = ArrayList<Node>()
        document.forEachNode { if (it is Comment) comments += it }
        comments.forEach { it.remove() }

        // Snapshot first: removing while iterating would skip siblings.
        for (element in document.allElements.toList()) {
            if (element.parent() == null && element !== document) continue
            val name = element.normalName().lowercase(Locale.ROOT)
            when {
                name in REMOVED_ELEMENTS -> element.remove()
                name in UNWRAPPED_ELEMENTS -> {
                    cleanAttributes(element)
                    element.unwrap()
                }
                else -> cleanAttributes(element)
            }
        }

        val head = document.head()
        head.prependChild(Element("style").appendChild(DataNode(DEFAULT_STYLE)))
        head.prependChild(Element("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1"))
        head.prependChild(Element("meta").attr("charset", "utf-8"))
        if (document.childNodes().none { it is DocumentType }) {
            document.prependChild(DocumentType("html", "", ""))
        }
        document.outputSettings()
            .prettyPrint(false)
            .charset(Charsets.UTF_8)
            .escapeMode(Entities.EscapeMode.base)
        return document.outerHtml()
    }

    /** Escapes plain text, keeps line breaks, linkifies URLs/e-mails and renders "> " quotes as blockquotes. */
    fun plainTextToHtml(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(text.length + text.length / 4)
        var depth = 0
        var lineOpen = false
        for (line in lines) {
            val (lineDepth, content) = splitQuote(line)
            if (lineDepth != depth) {
                while (depth < lineDepth) {
                    out.append(BLOCKQUOTE_OPEN)
                    depth++
                }
                while (depth > lineDepth) {
                    out.append("</blockquote>")
                    depth--
                }
                lineOpen = false
            }
            if (lineOpen) out.append("<br>\n")
            out.append(renderLine(content))
            lineOpen = true
        }
        while (depth > 0) {
            out.append("</blockquote>")
            depth--
        }
        return out.toString()
    }

    /** True if [html] references remote (http/https) images, stylesheets or backgrounds. */
    fun hasRemoteContent(html: String): Boolean {
        if (html.isBlank()) return false
        val document = Jsoup.parse(html)
        for (element in document.allElements) {
            val name = element.normalName()
            for (attribute in element.attributes()) {
                val key = attribute.key.lowercase(Locale.ROOT)
                val value = attribute.value
                val remote = when (key) {
                    "src", "background", "poster", "lowsrc", "dynsrc" -> isRemoteUrl(value)
                    "data" -> name == "object" && isRemoteUrl(value)
                    "srcset" -> value.split(',').any { isRemoteUrl(it.trim().substringBefore(' ')) }
                    "href", "xlink:href" -> (name == "link" || name == "image" || name == "use") && isRemoteUrl(value)
                    "style" -> CSS_REMOTE_URL.containsMatchIn(value)
                    else -> false
                }
                if (remote) return true
            }
            if (name == "style") {
                val css = element.data()
                if (CSS_REMOTE_URL.containsMatchIn(css) || CSS_REMOTE_IMPORT.containsMatchIn(css)) return true
            }
        }
        return false
    }

    /** Replaces cid: references with the given data: URIs (key = Content-ID without brackets). */
    fun inlineImages(html: String, inlineImages: Map<String, String>): String {
        if (inlineImages.isEmpty() || !html.contains("cid:", ignoreCase = true)) return html
        val byLowerKey = inlineImages.entries.associate { (key, value) ->
            key.trim().removePrefix("<").removeSuffix(">").lowercase(Locale.ROOT) to value
        }
        return CID_REFERENCE.replace(html) { match ->
            val raw = match.groupValues[1]
            val decoded = percentDecode(raw).trim().removePrefix("<").removeSuffix(">")
            inlineImages[decoded]
                ?: inlineImages[raw]
                ?: byLowerKey[decoded.lowercase(Locale.ROOT)]
                ?: match.value
        }
    }

    private fun cleanAttributes(element: Element) {
        val toRemove = ArrayList<String>()
        for (attribute in element.attributes()) {
            val key = attribute.key.lowercase(Locale.ROOT)
            val normalized = normalizeForCheck(attribute.value)
            val dangerous = key.startsWith("on") ||
                key in ALWAYS_REMOVED_ATTRIBUTES ||
                DANGEROUS_SCHEMES.any { normalized.contains(it) } ||
                DANGEROUS_DATA.any { normalized.contains(it) } ||
                (key == "style" && CSS_EXPRESSION.containsMatchIn(normalized))
            if (dangerous) toRemove += attribute.key
        }
        toRemove.forEach { element.removeAttr(it) }
    }

    /** Lower case without whitespace, control and soft-hyphen characters used to obfuscate schemes. */
    private fun normalizeForCheck(value: String): String {
        val builder = StringBuilder(value.length)
        for (c in value) {
            if (c.code <= 0x20 || c == '\u00AD' || c == '\u200B' || c == '\uFEFF' || c.code == 0x7F) continue
            builder.append(c.lowercaseChar())
        }
        return builder.toString()
    }

    private fun isRemoteUrl(value: String): Boolean {
        val url = value.trim().lowercase(Locale.ROOT)
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")
    }

    /** Quote depth of a plain-text line ("> > text" is 2) and the line without the markers. */
    private fun splitQuote(line: String): Pair<Int, String> {
        var depth = 0
        var index = 0
        while (true) {
            var probe = index
            while (probe < line.length && line[probe] == ' ' && probe - index < 3) probe++
            if (probe < line.length && line[probe] == '>') {
                depth++
                index = probe + 1
            } else {
                break
            }
        }
        if (depth == 0) return 0 to line
        val rest = line.substring(index)
        return depth to if (rest.startsWith(" ")) rest.substring(1) else rest
    }

    private fun renderLine(line: String): String {
        val out = StringBuilder(line.length + 16)
        var last = 0
        for (match in URL_PATTERN.findAll(line)) {
            var text = match.value
            // Trailing punctuation and unbalanced closing brackets are not part of the link.
            while (text.isNotEmpty() && (text.last() in TRAILING_PUNCTUATION || isUnbalancedClose(text))) {
                text = text.dropLast(1)
            }
            if (text.isEmpty()) continue
            val start = match.range.first
            out.append(escapeText(line.substring(last, start)))
            val href = when {
                match.groups[2] != null -> "mailto:$text"
                text.startsWith("www.", ignoreCase = true) -> "http://$text"
                else -> text
            }
            out.append("<a href=\"").append(escapeAttribute(href)).append("\">")
                .append(escapeText(text)).append("</a>")
            last = start + text.length
        }
        out.append(escapeText(line.substring(last)))
        return out.toString()
    }

    private fun isUnbalancedClose(text: String): Boolean {
        val close = text.last()
        val open = when (close) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            '>' -> '<'
            else -> return false
        }
        return text.count { it == close } > text.count { it == open }
    }

    /** HTML-escapes [text], keeping runs of spaces and tabs visible. */
    private fun escapeText(text: String): String {
        val out = StringBuilder(text.length + 8)
        var previousSpace = true // a leading space must not collapse either
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\t' -> out.append("&nbsp;&nbsp;&nbsp; ")
                ' ' -> out.append(if (previousSpace) "&nbsp;" else " ")
                else -> out.append(c)
            }
            previousSpace = c == ' ' || c == '\t'
        }
        return out.toString()
    }

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** RFC 2392 cid URLs are percent-encoded; '+' is literal (unlike form encoding). */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    index += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return bytes.toString("UTF-8")
    }
}
