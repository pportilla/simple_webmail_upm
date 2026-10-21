package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.mail.Address
import jakarta.mail.Flags
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import java.util.Locale

/** Helpers that turn raw header values into the engine's models. */
internal object HeaderValues {
    private val MESSAGE_ID = Regex("<[^<>\\s]+>")
    private val WHITESPACE = Regex("\\s+")
    private val LINE_BREAKS = Regex("[\\t ]*\\r?\\n[\\t ]*|\\t")
    const val FORWARDED_KEYWORD = "\$Forwarded"

    /** Every `<id>` token of a References / In-Reply-To value, in order, without duplicates. */
    fun messageIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val found = MESSAGE_ID.findAll(value).map { it.value }.toMutableList()
        if (found.isEmpty()) {
            // Some mailers omit the brackets; accept bare tokens that look like ids.
            value.split(Regex("[\\s,]+"))
                .filter { '@' in it && it.length > 2 }
                .mapTo(found) { "<${it.trim('<', '>')}>" }
        }
        val seen = HashSet<String>()
        return found.filter { seen.add(it.lowercase(Locale.ROOT)) }
    }

    /** The first message id in [value], or null. */
    fun firstMessageId(value: String?): String? = messageIds(value).firstOrNull()

    /** A header value with folding whitespace collapsed, or null if blank. */
    fun unfold(value: String?): String? = value?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Decoded subject without line breaks; "" when absent. */
    fun subject(value: String?): String {
        if (value == null) return ""
        val decoded = if ("=?" in value) {
            try {
                MimeUtility.decodeText(value)
            } catch (_: Exception) {
                value
            }
        } else {
            value
        }
        return cleanDecodedSubject(decoded)
    }

    /** A subject Jakarta Mail already decoded, with line breaks and tabs collapsed and raw UTF-8 repaired. */
    fun cleanDecodedSubject(value: String): String = repairUtf8(value).replace(LINE_BREAKS, " ").trim()

    /**
     * Header text whose raw 8-bit UTF-8 bytes were read one byte per character (as Jakarta Mail
     * reads IMAP strings and unencoded headers), e.g. "ReuniÃ³n", decoded as UTF-8 ("Reunión").
     * Anything else is returned unchanged: real Latin-1 text such as "Año" is not valid UTF-8.
     */
    fun repairUtf8(value: String): String {
        if (value.none { it.code >= 0x80 } || value.any { it.code > 0xFF }) return value
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        return if (MimeParser.isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else value
    }

    /** Converts Jakarta addresses (groups expanded, unparseable entries dropped). */
    fun addresses(values: Array<out Address>?): List<EmailAddress> {
        if (values == null) return emptyList()
        val result = ArrayList<EmailAddress>()
        for (value in values) {
            val internet = value as? InternetAddress ?: continue
            if (internet.isGroup) {
                val members = try {
                    internet.getGroup(false)
                } catch (_: Exception) {
                    null
                }
                members?.forEach { member -> toEmailAddress(member)?.let(result::add) }
            } else {
                toEmailAddress(internet)?.let(result::add)
            }
        }
        return result
    }

    private fun toEmailAddress(address: InternetAddress): EmailAddress? {
        val email = address.address?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith(":;") } ?: return null
        val personal = address.personal
            ?.let { if ("=?" in it) subject(it) else repairUtf8(it) }
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.trim('"', '\'')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(email, ignoreCase = true) }
        return EmailAddress(email, personal)
    }

    fun flags(flags: Flags?): RemoteFlags {
        if (flags == null) return RemoteFlags()
        return RemoteFlags(
            seen = flags.contains(Flags.Flag.SEEN),
            flagged = flags.contains(Flags.Flag.FLAGGED),
            answered = flags.contains(Flags.Flag.ANSWERED),
            forwarded = flags.userFlags.any { it.equals(FORWARDED_KEYWORD, ignoreCase = true) },
            draft = flags.contains(Flags.Flag.DRAFT),
            deleted = flags.contains(Flags.Flag.DELETED),
        )
    }
}
