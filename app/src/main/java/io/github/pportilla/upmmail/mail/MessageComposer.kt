package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.ContentDisposition
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.internet.ParameterList
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Locale
import java.util.UUID

object MessageComposer {
    private const val CHARSET = "UTF-8"
    private const val FALLBACK_DOMAIN = "localhost.localdomain"
    private const val MAX_REFERENCES_LINE = 76
    private const val ATTACHMENT = "attachment"

    /**
     * Builds the MIME message for [draft] sent by [from]: multipart/alternative (text/plain +
     * generated text/html) wrapped in multipart/mixed when there are attachments, UTF-8
     * everywhere, In-Reply-To/References for replies and a fresh Message-ID.
     */
    fun build(
        from: EmailAddress,
        draft: Draft,
        attachments: List<AttachmentSource>,
        date: Long = System.currentTimeMillis(),
        userAgent: String = "UPM Mail for Android",
    ): ComposedMessage = MailSessions.withEngineClassLoader {
        val messageId = newMessageId(from.address)
        val message = FixedIdMimeMessage(MailSessions.mimeSession(), messageId)

        message.setFrom(internetAddress(from))
        setRecipients(message, Message.RecipientType.TO, draft.to)
        setRecipients(message, Message.RecipientType.CC, draft.cc)
        setRecipients(message, Message.RecipientType.BCC, draft.bcc)
        message.setSubject(singleLine(draft.subject), CHARSET)
        message.sentDate = Date(date)
        draft.inReplyTo?.trim()?.takeIf { it.isNotEmpty() }?.let { message.setHeader("In-Reply-To", singleLine(it)) }
        val references = draft.references.map { it.trim() }.filter { it.isNotEmpty() }
        if (references.isNotEmpty()) message.setHeader("References", foldReferences(references))
        if (userAgent.isNotBlank()) message.setHeader("User-Agent", singleLine(userAgent))

        val body = normalizeNewlines(draft.body)
        val alternative = MimeMultipart("alternative").apply {
            addBodyPart(textPart(body, "plain"))
            addBodyPart(textPart(htmlDocument(body), "html"))
        }
        if (attachments.isEmpty()) {
            message.setContent(alternative)
        } else {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { setContent(alternative) })
            attachments.forEach { mixed.addBodyPart(attachmentPart(it)) }
            message.setContent(mixed)
        }
        message.saveChanges()
        ComposedMessage(message, messageId)
    }

    /** "<uuid@domain-of-from>". */
    internal fun newMessageId(fromAddress: String): String {
        val domain = fromAddress.substringAfterLast('@', "")
            .trim()
            .trimEnd('>')
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
            ?: FALLBACK_DOMAIN
        return "<${UUID.randomUUID()}@$domain>"
    }

    /** The generated HTML alternative: the plain text rendered by HtmlSanitizer.plainTextToHtml. */
    internal fun htmlDocument(text: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n" +
            "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n" +
            "</head>\n<body>\n<div dir=\"auto\">" + HtmlSanitizer.plainTextToHtml(text) + "</div>\n</body>\n</html>\n"

    private fun textPart(content: String, subtype: String): MimeBodyPart = MimeBodyPart().apply {
        setText(content, CHARSET, subtype)
        setHeader("Content-Transfer-Encoding", "quoted-printable")
    }

    private fun attachmentPart(source: AttachmentSource): MimeBodyPart {
        val fileName = MimeParser.sanitizeFileName(source.fileName) ?: "attachment"
        val mimeType = source.mimeType.trim().lowercase(Locale.ROOT).takeIf { isValidMimeType(it) }
            ?: "application/octet-stream"
        return MimeBodyPart().apply {
            dataHandler = DataHandler(ReopeningDataSource(source, fileName, mimeType))
            // RFC 2231 in Content-Disposition (the standard); RFC 2047 in the Content-Type name
            // parameter for older mail clients that only look there.
            val disposition = ContentDisposition(ATTACHMENT, ParameterList().apply { set("filename", fileName, CHARSET) })
            setHeader("Content-Disposition", disposition.toString())
            val contentType = ContentType(mimeType).apply {
                parameterList = ParameterList().apply {
                    if (isAscii(fileName)) {
                        set("name", fileName)
                    } else {
                        // An encoded-word is plain ASCII; ParameterList quotes it as needed.
                        set("name", MimeUtility.encodeText(fileName, CHARSET, "B"))
                    }
                }
            }
            setHeader("Content-Type", contentType.toString())
            setHeader("Content-Transfer-Encoding", "base64")
        }
    }

    private fun setRecipients(message: MimeMessage, type: Message.RecipientType, addresses: List<EmailAddress>) {
        val converted = addresses
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address.trim().lowercase(Locale.ROOT) }
            .map(::internetAddress)
        if (converted.isNotEmpty()) message.setRecipients(type, converted.toTypedArray())
    }

    private fun internetAddress(address: EmailAddress): InternetAddress {
        val name = address.name?.let(::singleLine)?.takeIf { it.isNotEmpty() }
        return InternetAddress(address.address.trim(), name, CHARSET)
    }

    /** References folded over several lines, one id per line when they do not fit. */
    private fun foldReferences(references: List<String>): String {
        val builder = StringBuilder()
        var lineLength = "References: ".length
        references.forEachIndexed { index, reference ->
            if (index > 0) {
                if (lineLength + 1 + reference.length > MAX_REFERENCES_LINE) {
                    builder.append("\r\n ")
                    lineLength = 1
                } else {
                    builder.append(' ')
                    lineLength++
                }
            }
            builder.append(reference)
            lineLength += reference.length
        }
        return builder.toString()
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun isAscii(value: String): Boolean = value.all { it.code in 0x20..0x7E }

    private fun isValidMimeType(value: String): Boolean =
        Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(value)

    /** A MimeMessage whose Message-ID survives saveChanges(). */
    private class FixedIdMimeMessage(session: Session, private val messageId: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", messageId)
        }
    }

    /** Opens a fresh stream on every call, so the message can be written more than once. */
    private class ReopeningDataSource(
        private val source: AttachmentSource,
        private val fileName: String,
        private val mimeType: String,
    ) : DataSource {
        override fun getInputStream(): InputStream = source.openStream()

        override fun getOutputStream(): OutputStream =
            throw UnsupportedOperationException("Attachments are read-only")

        override fun getContentType(): String = mimeType

        override fun getName(): String = fileName
    }
}
