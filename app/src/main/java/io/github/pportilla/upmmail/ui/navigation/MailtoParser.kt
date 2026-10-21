package io.github.pportilla.upmmail.ui.navigation

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The fields of a `mailto:` URI that the compose screen can prefill. */
data class MailtoData(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
)

/**
 * Parser for `mailto:` URIs (RFC 6068), lenient with what real web pages produce:
 *
 * - addresses in the path and in any number of `to`, `cc` and `bcc` fields, separated by commas
 *   (or semicolons, which Outlook-generated links use),
 * - case-insensitive field names, a `mailto://` prefix,
 * - percent-encoded UTF-8, falling back to Latin-1 for old pages that encode "é" as `%E9`,
 * - `+` is kept literally (RFC 6068): it is common in addresses such as `ana+list@upm.es`.
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object MailtoParser {
    private const val SCHEME = "mailto:"

    fun isMailto(uri: String): Boolean = uri.trimStart().startsWith(SCHEME, ignoreCase = true)

    /** The fields of [uri], or null if it is not a mailto: URI. */
    fun parse(uri: String): MailtoData? {
        val trimmed = uri.trim()
        if (!isMailto(trimmed)) return null
        var rest = trimmed.substring(SCHEME.length)
        if (rest.startsWith("//")) rest = rest.substring(2)
        // An unencoded '#' starts a fragment, which mailto: does not use.
        rest = rest.substringBefore('#')

        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")

        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        val bcc = ArrayList<String>()
        var subject: String? = null
        var body: String? = null

        // In the path, separators are literal; an encoded comma belongs to a (quoted) address.
        splitAddresses(path).mapNotNullTo(to) { percentDecode(it).trim().takeIf(String::isNotEmpty) }

        for (field in query.split('&')) {
            if (field.isEmpty()) continue
            val name = percentDecode(field.substringBefore('=')).trim().lowercase()
            val value = percentDecode(field.substringAfter('=', ""))
            when (name) {
                "to" -> to += splitAddresses(value)
                "cc" -> cc += splitAddresses(value)
                "bcc" -> bcc += splitAddresses(value)
                "subject" -> if (subject == null) subject = singleLine(value)
                "body" -> if (body == null) body = normalizeNewlines(value)
            }
        }
        return MailtoData(
            to = distinctAddresses(to),
            cc = distinctAddresses(cc),
            bcc = distinctAddresses(bcc),
            subject = subject?.takeIf { it.isNotBlank() },
            body = body?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Splits a header-style address list on commas and semicolons, except inside quoted names
     * and angle brackets (`"Garcia, Ana" <ana@upm.es>` stays one address).
     */
    fun splitAddresses(value: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        var angleDepth = 0
        for (c in value) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                inQuotes && c == '\\' -> {
                    current.append(c)
                    escaped = true
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    current.append(c)
                }
                !inQuotes && c == '<' -> {
                    angleDepth++
                    current.append(c)
                }
                !inQuotes && c == '>' -> {
                    if (angleDepth > 0) angleDepth--
                    current.append(c)
                }
                !inQuotes && angleDepth == 0 && (c == ',' || c == ';') -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        result += current.toString()
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Joins address lists for ComposeArgs ("a@x, b@y"), dropping blanks and case-insensitive
     * duplicates; null when nothing is left.
     */
    fun joinAddresses(vararg lists: List<String>): String? =
        distinctAddresses(lists.flatMap { list -> list.flatMap(::splitAddresses) })
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    /**
     * Decodes %XX escapes. Runs of escaped bytes are decoded as UTF-8, or as ISO-8859-1 when they
     * are not valid UTF-8. Malformed escapes are kept as they are.
     */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 <= text.lastIndex) {
                val hi = hexValue(text[i + 1])
                val lo = hexValue(text[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            flushBytes(bytes, out)
            out.append(c)
            i++
        }
        flushBytes(bytes, out)
        return out.toString()
    }

    private fun flushBytes(bytes: ByteArrayOutputStream, out: StringBuilder) {
        if (bytes.size() == 0) return
        val raw = bytes.toByteArray()
        bytes.reset()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) {
            String(raw, StandardCharsets.ISO_8859_1)
        }
        out.append(decoded)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun singleLine(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    private fun distinctAddresses(addresses: List<String>): List<String> =
        addresses.filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }
}
