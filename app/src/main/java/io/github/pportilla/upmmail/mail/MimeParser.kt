package io.github.pportilla.upmmail.mail

import jakarta.mail.Message
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Walks the MIME structure of a message (an IMAP message whose BODYSTRUCTURE is known, or a
 * fully parsed MimeMessage) and extracts the readable body and the attachment list.
 *
 * Part paths follow IMAP section numbering: for a non-multipart message the body is "1"; for a
 * multipart message child i (1-based) is "i" and child j of a nested multipart i is "i.j".
 * message/rfc822 parts are listed as attachments and never descended into.
 *
 * Only text/plain and text/html body parts are downloaded by [parse]; attachments are described
 * from the structure. Each body field is capped at [MAX_BODY_CHARS] characters so that a
 * pathological message cannot exhaust memory or the SQLite cursor window.
 */
internal object MimeParser {
    const val MAX_BODY_CHARS = 700_000
    private const val MAX_TEXT_PART_BYTES = 4 * 1024 * 1024
    private const val MAX_DEPTH = 24
    private const val MAX_PARTS = 400
    private const val COPY_BUFFER = 64 * 1024

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val SIGNATURE_TYPES = setOf(
        "application/pkcs7-signature",
        "application/x-pkcs7-signature",
        "application/pgp-signature",
    )
    private val META_CHARSET = Regex("""(?i)<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_.:\-]+)""")
    private val CHARSET_PARAM = Regex("""(?i)charset\s*=\s*"?([^";\s]+)""")
    private val CID_REFERENCE = Regex("""(?i)cid:([^"'\s)>]+)""")
    private val UNSAFE_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")

    private enum class SegmentKind { HTML, TEXT, ALTERNATIVE_TEXT }

    private class Segment(val kind: SegmentKind, val content: String)

    private class Collector(val readContent: Boolean) {
        val segments = ArrayList<Segment>()
        val attachments = ArrayList<ParsedAttachment>()
        var visitedParts = 0
    }

    private class PartInfo(
        val mimeType: String,
        val disposition: String?,
        val fileName: String?,
        val contentId: String?,
    )

    /** Downloads the body parts of [message] and describes its attachments. */
    fun parse(message: Part): ParsedBody {
        val collector = Collector(readContent = true)
        walk(message, "", inRelated = false, collector, depth = 0)
        val segments = collector.segments
        val hasHtml = segments.any { it.kind == SegmentKind.HTML }
        val html = if (hasHtml) {
            segments
                .filter { it.kind != SegmentKind.ALTERNATIVE_TEXT }
                .joinToString("\n") {
                    if (it.kind == SegmentKind.HTML) it.content else HtmlSanitizer.plainTextToHtml(it.content)
                }
                .let(::capBody)
        } else {
            null
        }
        val textSegments = segments.filter { it.kind != SegmentKind.HTML }
        val text = if (textSegments.isEmpty()) {
            null
        } else {
            textSegments.joinToString("\n") { it.content }.let(::capBody)
        }
        return ParsedBody(html = html, text = text, attachments = resolveInline(collector.attachments, html))
    }

    /**
     * An inline image the HTML does not reference would never be shown, so it is offered as a
     * regular attachment instead.
     */
    private fun resolveInline(attachments: List<ParsedAttachment>, html: String?): List<ParsedAttachment> {
        if (attachments.none { it.isInline }) return attachments.toList()
        val referenced = html?.let { body ->
            CID_REFERENCE.findAll(body).map { decodeCid(it.groupValues[1]).lowercase(Locale.ROOT) }.toSet()
        }.orEmpty()
        return attachments.map { attachment ->
            val id = attachment.contentId?.lowercase(Locale.ROOT)
            if (attachment.isInline && (id == null || id !in referenced)) attachment.copy(isInline = false) else attachment
        }
    }

    private fun decodeCid(raw: String): String {
        val decoded = if ('%' in raw) {
            try {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                raw
            }
        } else {
            raw
        }
        return decoded.trim().removePrefix("<").removeSuffix(">")
    }

    /**
     * Whether [message] has attachments, judged from its structure only (no content is read for
     * IMAP messages whose BODYSTRUCTURE has been fetched). Inline images referenced by
     * Content-ID and detached signatures do not count.
     */
    fun hasAttachments(message: Part): Boolean {
        val collector = Collector(readContent = false)
        walk(message, "", inRelated = false, collector, depth = 0)
        return collector.attachments.any { !it.isInline && it.mimeType !in SIGNATURE_TYPES }
    }

    /** The part at [partPath] (see the class documentation), or null if there is none. */
    fun findPart(message: Part, partPath: String): Part? {
        val indexes = partPath.trim().split('.').map { it.toIntOrNull() ?: return null }
        if (indexes.isEmpty() || indexes.any { it < 1 }) return null
        val rootMultipart = multipartOf(message)
        if (rootMultipart == null) return if (indexes == listOf(1)) message else null
        var current: Part = message
        var multipart: Multipart? = rootMultipart
        for (index in indexes) {
            val mp = multipart ?: return null
            if (index > mp.count) return null
            current = mp.getBodyPart(index - 1)
            multipart = multipartOf(current)
        }
        return current
    }

    /**
     * Copies the decoded content of [part] to [out]. A message/rfc822 part is written as the
     * complete encapsulated message (an .eml file). [checkCancelled] is called between chunks.
     */
    fun writeContent(part: Part, out: OutputStream, checkCancelled: () -> Unit) {
        val stream = if (mimeTypeOf(part) == "message/rfc822") openEncapsulatedMessage(part) else openDecoded(part)
        copy(stream, out, checkCancelled)
    }

    /** Copies [input] to [out] and closes [input]; [checkCancelled] is called between chunks. */
    fun copy(input: InputStream, out: OutputStream, checkCancelled: () -> Unit) {
        input.use {
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                checkCancelled()
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    /** True for message/rfc822 parts stored without a transfer encoding (the normal case). */
    fun isPlainEncapsulatedMessage(part: Part): Boolean =
        mimeTypeOf(part) == "message/rfc822" && encodingOf(part) == null

    /** Decoded text of a text part, honouring its charset with sensible fallbacks. */
    fun readText(part: Part): String {
        val bytes = try {
            openDecoded(part).use { readUpTo(it, MAX_TEXT_PART_BYTES) }
        } catch (e: Exception) {
            if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e) || e is MessageRemovedException) throw e
            // The transfer encoding is broken beyond repair: show the content as it came.
            rawStream(part)?.use { readUpTo(it, MAX_TEXT_PART_BYTES) } ?: throw e
        }
        val mimeType = mimeTypeOf(part)
        val declared = charsetOf(part) ?: if (mimeType == "text/html") sniffHtmlCharset(bytes) else null
        return decodeBytes(bytes, declared)
    }

    /** Lower-case base MIME type of [part]; text/plain when missing, as RFC 2045 says. */
    fun mimeTypeOf(part: Part): String {
        val raw = try {
            part.contentType
        } catch (_: Exception) {
            null
        }?.trim()
        if (raw.isNullOrEmpty()) return "text/plain"
        val base = try {
            ContentType(raw).baseType
        } catch (_: Exception) {
            raw.substringBefore(';')
        }.trim().lowercase(Locale.ROOT)
        return when {
            base.isEmpty() || base == "text" -> "text/plain"
            '/' !in base -> "application/octet-stream"
            else -> base
        }
    }

    /**
     * Decodes [bytes] with the [declared] charset. Unknown or unsupported charsets (and
     * "us-ascii", which is routinely mislabelled 8-bit text) fall back to UTF-8 when the bytes
     * are valid UTF-8 and to windows-1252 otherwise.
     */
    fun decodeBytes(bytes: ByteArray, declared: String?): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val charset = resolveCharset(declared)
        if (charset != null) return String(bytes, charset)
        return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else String(bytes, WINDOWS_1252)
    }

    private fun walk(part: Part, path: String, inRelated: Boolean, collector: Collector, depth: Int) {
        if (++collector.visitedParts > MAX_PARTS) return
        val multipart = if (depth < MAX_DEPTH) multipartOf(part) else null
        if (multipart == null) {
            leaf(part, path.ifEmpty { "1" }, inRelated, collector)
            return
        }
        val children = (0 until multipart.count).mapNotNull { index ->
            try {
                multipart.getBodyPart(index) to childPath(path, index + 1)
            } catch (e: MessagingException) {
                if (MailErrors.isConnectionDrop(e)) throw e
                null
            }
        }
        when (mimeTypeOf(part).substringAfter('/')) {
            "alternative" -> walkAlternative(children, inRelated, collector, depth)
            "related" -> walkRelated(part, children, inRelated, collector, depth)
            else -> children.forEach { (child, childPath) -> walk(child, childPath, inRelated, collector, depth + 1) }
        }
    }

    private fun walkAlternative(
        children: List<Pair<Part, String>>,
        inRelated: Boolean,
        collector: Collector,
        depth: Int,
    ) {
        // The richest rendering is the last one that contains HTML; the plain text one is kept as
        // the text version of the same content.
        val htmlIndex = children.indexOfLast { containsHtml(it.first, depth + 1) }
        val textIndex = children.indexOfFirst { isBodyLeaf(it.first, "text/plain") }
        val fallbackIndex = if (htmlIndex < 0 && textIndex < 0) children.lastIndex else -1
        children.forEachIndexed { index, (child, childPath) ->
            when {
                index == htmlIndex || index == fallbackIndex -> walk(child, childPath, inRelated, collector, depth + 1)
                index == textIndex -> if (htmlIndex < 0) {
                    walk(child, childPath, inRelated, collector, depth + 1)
                } else if (collector.readContent) {
                    collector.segments += Segment(SegmentKind.ALTERNATIVE_TEXT, normalizeNewlines(readText(child)))
                }
                // Other renderings that are not plain/html (e.g. text/calendar invitations) are
                // useful as attachments; further plain/html renderings are redundant.
                multipartOf(child) == null && !isBodyLeaf(child, "text/plain") && !isBodyLeaf(child, "text/html") ->
                    leaf(child, childPath, inRelated, collector)
            }
        }
    }

    private fun walkRelated(
        part: Part,
        children: List<Pair<Part, String>>,
        inRelated: Boolean,
        collector: Collector,
        depth: Int,
    ) {
        val start = contentTypeParameter(part, "start")?.trim()?.removePrefix("<")?.removeSuffix(">")
        val rootIndex = start
            ?.let { id -> children.indexOfFirst { infoOf(it.first).contentId == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        children.forEachIndexed { index, (child, childPath) ->
            walk(child, childPath, inRelated = inRelated || index != rootIndex, collector, depth + 1)
        }
    }

    private fun leaf(part: Part, path: String, inRelated: Boolean, collector: Collector) {
        val info = infoOf(part)
        val isBodyType = info.mimeType == "text/plain" || info.mimeType == "text/html"
        val isBody = isBodyType && info.disposition != Part.ATTACHMENT && info.fileName == null
        if (isBody) {
            if (collector.readContent) {
                val kind = if (info.mimeType == "text/html") SegmentKind.HTML else SegmentKind.TEXT
                val text = readText(part)
                collector.segments += Segment(kind, if (kind == SegmentKind.TEXT) normalizeNewlines(text) else text)
            }
            return
        }
        // Inside multipart/related a Content-ID makes the part inline whatever its disposition
        // says (some mailers mark referenced images as attachments).
        val isInline = info.contentId != null && (inRelated || info.disposition == Part.INLINE)
        collector.attachments += ParsedAttachment(
            partPath = path,
            fileName = attachmentFileName(part, info, path),
            mimeType = info.mimeType,
            size = estimatedSize(part),
            contentId = info.contentId,
            isInline = isInline,
        )
    }

    private fun containsHtml(part: Part, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        val multipart = multipartOf(part) ?: return isBodyLeaf(part, "text/html")
        return (0 until multipart.count).any { index ->
            try {
                containsHtml(multipart.getBodyPart(index), depth + 1)
            } catch (e: MessagingException) {
                if (MailErrors.isConnectionDrop(e)) throw e
                false
            }
        }
    }

    private fun isBodyLeaf(part: Part, mimeType: String): Boolean {
        if (multipartOf(part) != null) return false
        val info = infoOf(part)
        return info.mimeType == mimeType && info.disposition != Part.ATTACHMENT && info.fileName == null
    }

    private fun childPath(parent: String, index: Int): String = if (parent.isEmpty()) "$index" else "$parent.$index"

    /** The multipart content of [part], or null if it is not (or not parseable as) a multipart. */
    private fun multipartOf(part: Part): Multipart? {
        if (!mimeTypeOf(part).startsWith("multipart/")) return null
        val content = try {
            part.content
        } catch (e: Exception) {
            if (MailErrors.isConnectionDrop(e)) throw e
            null
        }
        if (content is Multipart) return content.takeIf { safeCount(it) > 0 }
        return try {
            MimeMultipart(part.dataHandler.dataSource).takeIf { safeCount(it) > 0 }
        } catch (e: Exception) {
            if (MailErrors.isConnectionDrop(e)) throw e
            null
        }
    }

    private fun safeCount(multipart: Multipart): Int = try {
        multipart.count
    } catch (e: MessagingException) {
        if (MailErrors.isConnectionDrop(e)) throw e
        0
    }

    private fun infoOf(part: Part): PartInfo {
        val disposition = try {
            part.disposition
        } catch (_: Exception) {
            (part as? MimePart)?.let { rawHeader(it, "Content-Disposition") }?.substringBefore(';')
        }?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val fileName = try {
            part.fileName
        } catch (_: Exception) {
            null
        }?.let(::decodeIfEncoded)?.trim()?.takeIf { it.isNotEmpty() }
        val contentId = try {
            (part as? MimePart)?.contentID
        } catch (_: Exception) {
            null
        }?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.takeIf { it.isNotEmpty() }
        return PartInfo(mimeTypeOf(part), disposition, fileName, contentId)
    }

    private fun rawHeader(part: MimePart, name: String): String? = try {
        part.getHeader(name, null)
    } catch (_: Exception) {
        null
    }

    private fun decodeIfEncoded(value: String): String =
        if ("=?" in value) {
            try {
                MimeUtility.decodeText(value)
            } catch (_: Exception) {
                value
            }
        } else {
            value
        }

    private fun attachmentFileName(part: Part, info: PartInfo, path: String): String {
        val sanitized = info.fileName?.let(::sanitizeFileName)
        if (info.mimeType == "message/rfc822") {
            val base = sanitized ?: nestedSubject(part)?.let(::sanitizeFileName) ?: "message"
            return if (base.lowercase(Locale.ROOT).endsWith(".eml")) base else "$base.eml"
        }
        return sanitized ?: "attachment-${path.replace('.', '-')}.${extensionFor(info.mimeType)}"
    }

    private fun nestedSubject(part: Part): String? = try {
        (part.content as? Message)?.subject?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        if (MailErrors.isConnectionDrop(e)) throw e
        null
    }

    /** A file name safe for every file system: no separators, control or reserved characters. */
    fun sanitizeFileName(name: String): String? {
        val cleaned = name
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) return null
        return if (cleaned.length <= 120) {
            cleaned
        } else {
            val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 }
            if (extension != null) cleaned.take(119 - extension.length) + "." + extension else cleaned.take(120)
        }
    }

    private fun estimatedSize(part: Part): Long {
        val size = try {
            part.size
        } catch (_: Exception) {
            -1
        }
        if (size < 0) return -1
        val encoding = encodingOf(part)
        // base64 encodes 57 bytes in 76 characters plus a line break.
        return if (encoding == "base64") size.toLong() * 57 / 78 else size.toLong()
    }

    private fun encodingOf(part: Part): String? {
        val raw = try {
            (part as? MimePart)?.encoding
        } catch (_: Exception) {
            null
        }?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            "base64" in raw || "base-64" in raw -> "base64"
            "quoted" in raw -> "quoted-printable"
            raw == "uuencode" || raw == "x-uuencode" || raw == "x-uue" -> raw
            else -> null
        }
    }

    /** The content of [part] with its Content-Transfer-Encoding removed. */
    private fun openDecoded(part: Part): InputStream {
        val raw = rawStream(part) ?: return part.inputStream
        val encoding = encodingOf(part) ?: return raw
        return try {
            MimeUtility.decode(raw, encoding)
        } catch (_: MessagingException) {
            raw
        }
    }

    private fun openEncapsulatedMessage(part: Part): InputStream {
        val raw = rawStream(part) ?: return part.inputStream
        // message/rfc822 must not be encoded, but some mailers base64 it anyway.
        val encoding = encodingOf(part) ?: return raw
        return try {
            MimeUtility.decode(raw, encoding)
        } catch (_: MessagingException) {
            raw
        }
    }

    /**
     * The content with its transfer encoding intact. Unlike Part.getInputStream this does not go
     * through the DataHandler, which would turn a removed message into an anonymous IOException.
     */
    private fun rawStream(part: Part): InputStream? = when (part) {
        is MimeBodyPart -> part.rawInputStream
        is MimeMessage -> part.rawInputStream
        else -> null
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = try {
                input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            } catch (e: Exception) {
                // A broken encoding mid-way: keep what was decoded so far unless the connection died.
                if (MailErrors.isConnectionDrop(e) || out.size() == 0) throw e
                -1
            }
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun charsetOf(part: Part): String? = contentTypeParameter(part, "charset")?.trim('"', '\'', ' ')
        ?.takeIf { it.isNotEmpty() }

    private fun contentTypeParameter(part: Part, name: String): String? {
        val raw = try {
            part.contentType
        } catch (_: Exception) {
            null
        } ?: return null
        val parsed = try {
            ContentType(raw).getParameter(name)
        } catch (_: Exception) {
            null
        }
        if (parsed != null) return parsed
        return if (name == "charset") CHARSET_PARAM.find(raw)?.groupValues?.get(1) else null
    }

    private fun sniffHtmlCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return META_CHARSET.find(head)?.groupValues?.get(1)
    }

    private fun resolveCharset(declared: String?): Charset? {
        val name = declared?.trim()?.trim('"', '\'')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (name) {
            // Mislabelled 8-bit text is common; let the content decide.
            "us-ascii", "ascii", "ansi_x3.4-1968", "iso646-us", "unknown-8bit", "x-unknown", "unknown", "default" -> null
            // windows-1252 is a superset of latin-1 for the printable range (WHATWG behaviour).
            "iso-8859-1", "iso8859-1", "iso_8859-1", "latin1", "latin-1", "l1", "cp819" -> WINDOWS_1252
            "utf8", "utf-8", "unicode-1-1-utf-8" -> Charsets.UTF_8
            else -> lookupCharset(name) ?: lookupCharset(MimeUtility.javaCharset(name))
        }
    }

    private fun lookupCharset(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        return try {
            if (Charset.isSupported(name)) Charset.forName(name) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        if (bytes.all { it >= 0 }) return true
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun capBody(text: String): String {
        if (text.length <= MAX_BODY_CHARS) return text
        var end = MAX_BODY_CHARS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** A file extension for [mimeType], used to name attachments that came without a name. */
    fun extensionFor(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
        "application/pdf" -> "pdf"
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        "image/tiff" -> "tiff"
        "image/heic" -> "heic"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "text/csv" -> "csv"
        "text/calendar", "application/ics" -> "ics"
        "text/vcard", "text/x-vcard", "text/directory" -> "vcf"
        "text/xml", "application/xml" -> "xml"
        "text/rtf", "application/rtf" -> "rtf"
        "application/json" -> "json"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/gzip", "application/x-gzip" -> "gz"
        "application/x-7z-compressed" -> "7z"
        "application/x-rar-compressed", "application/vnd.rar" -> "rar"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-powerpoint" -> "ppt"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "application/vnd.oasis.opendocument.text" -> "odt"
        "application/vnd.oasis.opendocument.spreadsheet" -> "ods"
        "application/vnd.oasis.opendocument.presentation" -> "odp"
        "application/pkcs7-signature", "application/x-pkcs7-signature" -> "p7s"
        "application/pkcs7-mime", "application/x-pkcs7-mime" -> "p7m"
        "application/pgp-signature" -> "asc"
        "application/pgp-encrypted" -> "pgp"
        "application/ms-tnef", "application/vnd.ms-tnef" -> "dat"
        "message/rfc822" -> "eml"
        "message/delivery-status", "message/disposition-notification", "text/rfc822-headers" -> "txt"
        "audio/mpeg" -> "mp3"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp4" -> "m4a"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        else -> "bin"
    }
}
