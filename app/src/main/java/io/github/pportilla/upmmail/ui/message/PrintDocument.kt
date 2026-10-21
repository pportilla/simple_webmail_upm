package io.github.pportilla.upmmail.ui.message

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

/** Builds the HTML handed to the print framework: a small header block followed by the body. */
object PrintDocument {
    /** One "label: value" line of the printed header, e.g. From / To / Date. */
    data class HeaderLine(val label: String, val value: String)

    private const val PRINT_STYLE =
        "@media print{body{background:#fff !important;}}" +
            ".upm-print-header{font-family:sans-serif;color:#000;background:#fff;margin:0 0 16px 0;" +
            "padding:0 0 8px 0;border-bottom:1px solid #999;}" +
            ".upm-print-header h1{font-size:20px;line-height:1.3;margin:0 0 8px 0;font-weight:600;}" +
            ".upm-print-header p{font-size:12px;line-height:1.4;margin:0;}" +
            ".upm-print-header b{font-weight:600;}"

    private const val PLAIN_STYLE =
        "body{margin:0;color:#000;background:#fff;font-family:sans-serif;font-size:13px;line-height:1.45;" +
            "overflow-wrap:anywhere;word-wrap:break-word;}" +
            "a{color:#0b57d0;}"

    /**
     * [body] is either a complete, already sanitised HTML document ([isHtml] = true) or a
     * fragment produced by HtmlSanitizer.plainTextToHtml. Header values are escaped.
     */
    fun build(body: String, isHtml: Boolean, subject: String, header: List<HeaderLine>): String {
        val document: Document = if (isHtml) {
            Jsoup.parse(body)
        } else {
            Jsoup.parse("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>").also {
                it.head().appendElement("style").appendChild(DataNode(PLAIN_STYLE))
                it.body().append(body)
            }
        }
        document.head().appendElement("style").appendChild(DataNode(PRINT_STYLE))
        val block = Element("div").addClass("upm-print-header")
        block.appendElement("h1").text(subject)
        for (line in header) {
            if (line.value.isBlank()) continue
            val paragraph = block.appendElement("p")
            paragraph.appendElement("b").text(line.label + ": ")
            paragraph.appendText(line.value)
        }
        document.body().prependChild(block)
        document.outputSettings()
            .prettyPrint(false)
            .charset(Charsets.UTF_8)
            .escapeMode(Entities.EscapeMode.base)
        return document.outerHtml()
    }
}
