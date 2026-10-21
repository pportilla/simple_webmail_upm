package io.github.pportilla.upmmail.ui.message

import io.github.pportilla.upmmail.mail.Quoting
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import java.io.ByteArrayOutputStream
import java.util.Locale

/*
 * Pure helpers of the conversation screen. Nothing here touches Android, so it is covered by
 * plain JVM unit tests.
 */

/** A parsed `mailto:` link (RFC 6068). Addresses are kept as written, one entry per address. */
data class MailtoLink(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
)

/** Where a List-Unsubscribe header points to. */
sealed interface UnsubscribeTarget {
    /** A web page that unsubscribes (opened in the browser). */
    data class Web(val url: String) : UnsubscribeTarget

    /** An address to write to; the message is prepared in the compose screen. */
    data class Mail(val link: MailtoLink) : UnsubscribeTarget
}

/** Coarse file categories, used to pick an attachment icon. */
enum class AttachmentKind { IMAGE, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, AUDIO, VIDEO, CALENDAR, GENERIC }

/** One row of the conversation: a message, or a stack of collapsed older messages. */
sealed interface ThreadRow {
    data class Message(val id: Long) : ThreadRow

    data class Hidden(val ids: List<Long>) : ThreadRow
}

/** Short names of a message's recipients for the "to me, Ana" line. */
data class RecipientSummary(
    /** Short names in display order, at most the requested number. */
    val names: List<String>,
    /** Recipients not included in [names]. */
    val more: Int,
) {
    val isEmpty: Boolean get() = names.isEmpty()
}

private val CID_REFERENCE = Regex("""(?i)cid:([^"'\s)>]+)""")
private val ANGLE_BRACKETED = Regex("<([^<>]*)>")

private val IMAGE_EXTENSIONS = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "jpe" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "svg" to "image/svg+xml",
    "ico" to "image/x-icon",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
)
private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "docm", "dot", "dotx", "odt", "ott", "rtf", "txt", "md", "pages", "tex", "epub")
private val SPREADSHEET_EXTENSIONS = setOf("xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "ots", "csv", "tsv", "numbers")
private val PRESENTATION_EXTENSIONS = setOf("ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "otp", "key")
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "zst", "lz", "lzma", "cab", "jar")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "wma", "amr", "mid", "midi")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp", "wmv", "mpg", "mpeg", "ogv")
private val CALENDAR_EXTENSIONS = setOf("ics", "ical", "ifb", "vcs")

/**
 * Parses a `mailto:` URI. Percent-escapes are decoded as UTF-8 and, unlike form encoding, '+'
 * stays a plus sign. Returns null when [uri] is not a mailto: URI.
 */
fun parseMailto(uri: String): MailtoLink? {
    val value = uri.trim()
    if (!value.startsWith("mailto:", ignoreCase = true)) return null
    val rest = value.substring("mailto:".length)
    val path = rest.substringBefore('?')
    val query = if ('?' in rest) rest.substringAfter('?') else ""
    val to = ArrayList(splitMailtoAddresses(percentDecode(path)))
    val cc = ArrayList<String>()
    val bcc = ArrayList<String>()
    var subject: String? = null
    var body: String? = null
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val name = percentDecode(pair.substringBefore('=')).trim().lowercase(Locale.ROOT)
        val raw = if ('=' in pair) pair.substringAfter('=') else ""
        val decoded = percentDecode(raw)
        when (name) {
            "to" -> to += splitMailtoAddresses(decoded)
            "cc" -> cc += splitMailtoAddresses(decoded)
            "bcc" -> bcc += splitMailtoAddresses(decoded)
            "subject" -> if (subject == null) subject = decoded
            "body" -> if (body == null) body = decoded.replace("\r\n", "\n").replace('\r', '\n')
        }
    }
    return MailtoLink(to = to, cc = cc, bcc = bcc, subject = subject, body = body)
}

private fun splitMailtoAddresses(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** Every URI of a List-Unsubscribe header (RFC 2369: comma-separated, in angle brackets). */
fun parseListUnsubscribe(header: String?): List<String> {
    if (header.isNullOrBlank()) return emptyList()
    val bracketed = ANGLE_BRACKETED.findAll(header).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    if (bracketed.isNotEmpty()) return bracketed.map { it.filterNot(Char::isWhitespace) }
    // Some senders forget the brackets.
    return header.split(',').map { it.trim() }.filter { it.contains(':') }
}

/** The best unsubscribe method of a List-Unsubscribe header: https, then mailto:, then http. */
fun chooseUnsubscribeTarget(header: String?): UnsubscribeTarget? {
    val uris = parseListUnsubscribe(header)
    uris.firstOrNull { it.startsWith("https://", ignoreCase = true) }?.let { return UnsubscribeTarget.Web(it) }
    uris.firstNotNullOfOrNull { uri -> parseMailto(uri)?.takeIf { it.to.isNotEmpty() } }?.let { return UnsubscribeTarget.Mail(it) }
    uris.firstOrNull { it.startsWith("http://", ignoreCase = true) }?.let { return UnsubscribeTarget.Web(it) }
    return null
}

/** File category from the MIME type, falling back to the file name extension. */
fun attachmentKind(mimeType: String, fileName: String): AttachmentKind {
    val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = fileName.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)
    return when {
        mime == "application/pdf" || extension == "pdf" -> AttachmentKind.PDF
        mime == "text/calendar" || mime == "application/ics" || extension in CALENDAR_EXTENSIONS -> AttachmentKind.CALENDAR
        mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> AttachmentKind.IMAGE
        mime.startsWith("audio/") || extension in AUDIO_EXTENSIONS -> AttachmentKind.AUDIO
        mime.startsWith("video/") || extension in VIDEO_EXTENSIONS -> AttachmentKind.VIDEO
        isSpreadsheetMime(mime) || extension in SPREADSHEET_EXTENSIONS -> AttachmentKind.SPREADSHEET
        isPresentationMime(mime) || extension in PRESENTATION_EXTENSIONS -> AttachmentKind.PRESENTATION
        isDocumentMime(mime) || extension in DOCUMENT_EXTENSIONS -> AttachmentKind.DOCUMENT
        isArchiveMime(mime) || extension in ARCHIVE_EXTENSIONS -> AttachmentKind.ARCHIVE
        else -> AttachmentKind.GENERIC
    }
}

private fun isSpreadsheetMime(mime: String): Boolean =
    mime == "text/csv" || mime == "text/tab-separated-values" || mime.contains("spreadsheet") ||
        mime.contains("ms-excel") || mime.contains("officedocument.spreadsheetml")

private fun isPresentationMime(mime: String): Boolean =
    mime.contains("presentation") || mime.contains("ms-powerpoint") || mime.contains("officedocument.presentationml")

private fun isDocumentMime(mime: String): Boolean =
    mime == "application/msword" || mime == "application/rtf" || mime == "text/rtf" || mime == "text/plain" ||
        mime == "text/markdown" || mime.contains("wordprocessingml") || mime.contains("opendocument.text") ||
        mime == "application/epub+zip"

private fun isArchiveMime(mime: String): Boolean =
    mime in setOf(
        "application/zip", "application/x-zip-compressed", "application/x-rar-compressed", "application/vnd.rar",
        "application/x-7z-compressed", "application/gzip", "application/x-gzip", "application/x-tar",
        "application/x-bzip2", "application/x-xz", "application/zstd", "application/java-archive",
    )

/** Content-ID in its canonical form for comparisons: without angle brackets, lower case. */
fun normalizeContentId(contentId: String): String =
    contentId.trim().removePrefix("<").removeSuffix(">").trim().lowercase(Locale.ROOT)

/** Content-IDs referenced as `cid:` URLs anywhere in [html], normalised with [normalizeContentId]. */
fun referencedContentIds(html: String?): Set<String> {
    if (html.isNullOrEmpty() || !html.contains("cid:", ignoreCase = true)) return emptySet()
    return CID_REFERENCE.findAll(html)
        .map { normalizeContentId(percentDecode(it.groupValues[1])) }
        .filter { it.isNotEmpty() }
        .toSet()
}

/** MIME type to use in a `data:` URI for an inline image, guessing from the name when needed. */
fun inlineImageMime(declared: String, fileName: String): String {
    val mime = declared.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (mime.startsWith("image/")) return mime
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return IMAGE_EXTENSIONS[extension] ?: mime.ifEmpty { "application/octet-stream" }
}

/** Whether the message is a draft (flagged as such or stored in the Drafts folder). */
fun MessageDetail.isDraftMessage(): Boolean = summary.isDraft || folderRole == FolderRole.DRAFTS

/** Messages expanded when the conversation opens: the opened one and every unread one. */
fun initialExpandedIds(messages: List<MessageSummary>, openedId: Long): Set<Long> {
    if (messages.isEmpty()) return emptySet()
    val result = LinkedHashSet<Long>()
    result += messages.firstOrNull { it.id == openedId }?.id ?: messages.last().id
    messages.filter { !it.isRead }.mapTo(result) { it.id }
    return result
}

/**
 * Groups long runs of collapsed messages so the opened message stays near the top: the first
 * message is always shown, and a run of [minRun] or more collapsed messages after it (not
 * including the last message) becomes one [ThreadRow.Hidden] row, except its last message.
 */
fun threadRows(ids: List<Long>, expanded: Set<Long>, revealAll: Boolean, minRun: Int = 4): List<ThreadRow> {
    if (revealAll || ids.size <= minRun + 1) return ids.map { ThreadRow.Message(it) }
    val rows = ArrayList<ThreadRow>()
    rows += ThreadRow.Message(ids.first())
    var index = 1
    while (index < ids.size) {
        val id = ids[index]
        if (id in expanded || index == ids.lastIndex) {
            rows += ThreadRow.Message(id)
            index++
            continue
        }
        var end = index
        while (end + 1 < ids.lastIndex && ids[end + 1] !in expanded) end++
        val run = ids.subList(index, end + 1)
        if (run.size >= minRun) {
            rows += ThreadRow.Hidden(run.dropLast(1))
            rows += ThreadRow.Message(run.last())
        } else {
            run.forEach { rows += ThreadRow.Message(it) }
        }
        index = end + 1
    }
    return rows
}

/** A short, friendly name: the first name when there is a display name, else the local part. */
fun shortName(address: EmailAddress): String {
    val name = address.name?.trim()?.trim('"', '\'')?.trim()
    if (!name.isNullOrEmpty() && !name.contains('@')) {
        // "Pérez García, Ana" is written surname first.
        val given = if (name.contains(',')) name.substringAfter(',').trim().ifEmpty { name } else name
        return given.split(' ').firstOrNull { it.isNotBlank() } ?: given
    }
    return address.address.substringBefore('@').ifEmpty { address.address }
}

/**
 * Recipients of a message (To, then Cc, then Bcc, without duplicates) as short names, the
 * user first and called [meLabel].
 */
fun recipientSummary(
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    bcc: List<EmailAddress>,
    selfAddresses: Set<String>,
    meLabel: String,
    maxNames: Int = 3,
): RecipientSummary {
    val seen = HashSet<String>()
    val all = (to + cc + bcc).filter { it.address.isNotBlank() && seen.add(it.address.trim().lowercase(Locale.ROOT)) }
    val (self, others) = all.partition { it.address.trim().lowercase(Locale.ROOT) in selfAddresses }
    val names = (if (self.isNotEmpty()) listOf(meLabel) else emptyList()) + others.map(::shortName)
    val shown = names.take(maxNames.coerceAtLeast(1))
    return RecipientSummary(shown, names.size - shown.size)
}

/** True when "reply all" would reach more people than "reply". */
fun replyAllAddsRecipients(
    from: EmailAddress?,
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    replyTo: List<EmailAddress>,
    selfAddresses: Set<String>,
): Boolean {
    val one = Quoting.replyRecipients(from, to, cc, replyTo, selfAddresses, replyAll = false)
    val all = Quoting.replyRecipients(from, to, cc, replyTo, selfAddresses, replyAll = true)
    fun keys(r: Quoting.ReplyRecipients) =
        (r.to + r.cc).map { it.address.trim().lowercase(Locale.ROOT) }.toSet()
    return (keys(all) - keys(one)).isNotEmpty()
}

/**
 * Lets wide HTML e-mails shrink to the screen width. The sanitiser pins `initial-scale=1`, which
 * stops the WebView's overview mode from zooming out fixed-width (600 px) newsletters; without
 * it they fit the width and stay pinch-zoomable.
 */
fun fitWideContent(sanitizedHtml: String): String =
    sanitizedHtml.replaceFirst(
        "content=\"width=device-width, initial-scale=1\"",
        "content=\"width=device-width\"",
    )

/** Colours of the plain-text document, as CSS colour values. */
data class PlainTextColors(
    val text: String,
    val link: String,
    val quote: String,
    val quoteBorder: String,
)

/** A complete document around [fragment] (from HtmlSanitizer.plainTextToHtml) for the WebView. */
fun plainTextDocument(fragment: String, colors: PlainTextColors): String = buildString(fragment.length + 700) {
    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<style>")
    append("html,body{margin:0;padding:0;background:transparent;}")
    append("body{color:").append(colors.text).append(";font-family:sans-serif;font-size:15px;line-height:1.45;")
    append("overflow-wrap:anywhere;word-wrap:break-word;-webkit-text-size-adjust:none;}")
    append("a{color:").append(colors.link).append(";}")
    append("blockquote{margin:0 0 0 0.8ex !important;padding-left:1ex !important;color:").append(colors.quote)
    append(";border-left:2px solid ").append(colors.quoteBorder).append(" !important;}")
    append("</style></head><body>")
    append(fragment)
    append("</body></html>")
}

/**
 * Decodes %XX escapes as UTF-8. '+' is literal and malformed escapes are kept as written,
 * which is what RFC 6068 (mailto) and RFC 2392 (cid) ask for.
 */
internal fun percentDecode(value: String): String {
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
        val codePoint = value.codePointAt(index)
        bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        index += Character.charCount(codePoint)
    }
    return bytes.toString(Charsets.UTF_8.name())
}
