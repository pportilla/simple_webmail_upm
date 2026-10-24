package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.mail.HtmlText
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageDetail
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale

fun normalizedSafetyAddress(address: String): String = address.trim().lowercase(Locale.ROOT)

internal object SafetyDigests {
    private fun addresses(values: List<EmailAddress>): String = digest(values.size,
        *values.map { digest(normalizedSafetyAddress(it.address), it.name) }.toTypedArray())
    fun plainText(message: MessageDetail): String = message.bodyText ?: message.bodyHtml?.let(HtmlText::toPlainText).orEmpty()

    // Approval binds exactly the plain text and headers the guardian reviews. HTML, links and
    // attachments are never rendered or available in a supervised profile.
    fun message(message: MessageDetail): String = digest(
        message.summary.id, message.summary.accountId, message.summary.folderId,
        message.summary.uid, message.messageIdHeader, message.summary.subject,
        message.summary.from?.let { addresses(listOf(it)) }, addresses(message.summary.to),
        addresses(message.cc), addresses(message.bcc), addresses(message.replyTo),
        message.summary.date, message.summary.snippet, plainText(message), message.bodyLoaded,
    )

    fun outgoing(draft: Draft): String = digest(
        draft.accountId, addresses(draft.to), addresses(draft.cc), addresses(draft.bcc),
        draft.subject, draft.body, draft.inReplyTo, digest(draft.references.size, *draft.references.toTypedArray()),
        digest(draft.attachments.size, *draft.attachments.map {
            digest(it.uri, it.fileName, it.mimeType, it.size)
        }.toTypedArray()),
    )

    private fun digest(vararg fields: Any?): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fields.forEach { field ->
                val value = field?.toString()?.toByteArray(Charsets.UTF_8)
                output.writeInt(value?.size ?: -1)
                if (value != null) output.write(value)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
