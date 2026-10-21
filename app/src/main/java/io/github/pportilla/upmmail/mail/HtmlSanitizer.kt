package io.github.pportilla.upmmail.mail

object HtmlSanitizer {
    /**
     * Safe HTML for the read-only message WebView: removes scripts, iframes, objects, forms,
     * event-handler attributes, javascript: URLs, meta refresh and base tags; keeps styles,
     * tables and images (remote loads are blocked by the WebView until the user allows them).
     */
    fun sanitize(html: String): String = TODO()

    /** Escapes plain text, keeps line breaks, linkifies URLs/e-mails and renders "> " quotes as blockquotes. */
    fun plainTextToHtml(text: String): String = TODO()

    /** True if [html] references remote (http/https) images, stylesheets or backgrounds. */
    fun hasRemoteContent(html: String): Boolean = TODO()

    /** Replaces cid: references with the given data: URIs (key = Content-ID without brackets). */
    fun inlineImages(html: String, inlineImages: Map<String, String>): String = TODO()
}
