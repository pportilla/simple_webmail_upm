package io.github.pportilla.upmmail.mail

object HtmlText {
    /** Readable plain text from HTML: block elements become line breaks, <br> a newline. */
    fun toPlainText(html: String): String = TODO()

    /**
     * One-line preview of at most [maxLength] characters from [text] (preferred) or [html]:
     * whitespace collapsed, quoted ("> ") lines and the attribution line before them dropped.
     */
    fun snippet(text: String?, html: String?, maxLength: Int = 160): String = TODO()
}
