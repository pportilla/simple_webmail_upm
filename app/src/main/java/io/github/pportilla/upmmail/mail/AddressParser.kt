package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.mail.internet.MimeUtility

object AddressParser {
    data class ParseResult(val addresses: List<EmailAddress>, val invalid: List<String>)

    private val LOCAL_ATOM = Regex("""[\p{L}\p{N}!#$%&'*+/=?^_`{|}~\-]+""")
    private val DOMAIN_LABEL = Regex("""[\p{L}\p{N}](?:[\p{L}\p{N}\-]{0,61}[\p{L}\p{N}])?""")
    private val TLD = Regex("""\p{L}{2,63}|xn--[a-z0-9\-]{1,59}""", RegexOption.IGNORE_CASE)
    private val PLAIN_PHRASE = Regex("""[A-Za-z0-9!#$%&'*+/=?^_`{|}~\- ]+""")
    private val WHITESPACE = Regex("\\s+")
    private val BRACKETED_ADDRESS = Regex("""[^\s<>@,;"]+@[^\s<>@,;"]+""")

    /** A parsed address and whether it was written in angle brackets. */
    private class Candidate(val address: EmailAddress, val bracketed: Boolean)

    /**
     * Lenient parse of "Ana <ana@upm.es>, b@upm.es; c@x.es"; unparseable tokens go to [ParseResult.invalid].
     *
     * A bare address must pass [isValidAddress]. An address written in angle brackets only needs
     * the local@domain shape, so that header addresses such as `<root@localhost>` (which
     * [format] writes in brackets for this reason) survive a format/parse round trip.
     */
    fun parse(input: String): ParseResult {
        val addresses = ArrayList<EmailAddress>()
        val invalid = ArrayList<String>()
        for (token in split(input)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            val parsed = parseToken(trimmed)
            val acceptable = !parsed.isNullOrEmpty() && parsed.all { candidate ->
                isValidAddress(candidate.address.address) ||
                    (candidate.bracketed && BRACKETED_ADDRESS.matches(candidate.address.address))
            }
            if (acceptable) {
                parsed!!.mapTo(addresses) { it.address }
            } else {
                invalid += trimmed
            }
        }
        return ParseResult(addresses, invalid)
    }

    /** Syntactic check of a bare address (local@domain.tld). */
    fun isValidAddress(address: String): Boolean {
        val value = address.trim()
        if (value.isEmpty() || value.length > 254) return false
        if (value.any { it.isWhitespace() || it.isISOControl() }) return false
        val at = value.lastIndexOf('@')
        if (at <= 0 || at == value.lastIndex) return false
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        if (local.length > 64) return false
        val localOk = if (local.length >= 2 && local.startsWith('"') && local.endsWith('"')) {
            local.length > 2
        } else {
            local.split('.').all { LOCAL_ATOM.matches(it) }
        }
        if (!localOk) return false
        val labels = domain.split('.')
        if (labels.size < 2 || domain.length > 253) return false
        if (!labels.all { it.length in 1..63 && DOMAIN_LABEL.matches(it) }) return false
        return TLD.matches(labels.last())
    }

    /** RFC 5322 header form ("Ana Pérez" <ana@upm.es>, b@upm.es); round-trips with [parse]. */
    fun format(addresses: List<EmailAddress>): String = addresses.joinToString(", ") { formatOne(it) }

    private fun formatOne(address: EmailAddress): String {
        val email = address.address.trim()
        val name = address.name?.takeIf { it.isNotBlank() }
            ?: return if (isValidAddress(email)) email else "<$email>"
        val phrase = if (PLAIN_PHRASE.matches(name) && name == name.trim()) name else quote(name)
        return "$phrase <$email>"
    }

    private fun quote(name: String): String =
        "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Splits on ',', ';' and line breaks that are outside quotes, angle brackets and comments. */
    private fun split(input: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var angle = 0
        var paren = 0
        var index = 0
        while (index < input.length) {
            val c = input[index]
            when {
                inQuotes && c == '\\' && index + 1 < input.length -> {
                    current.append(c).append(input[index + 1])
                    index += 2
                    continue
                }
                c == '"' && paren == 0 -> inQuotes = !inQuotes
                !inQuotes && c == '<' -> angle++
                !inQuotes && c == '>' && angle > 0 -> angle--
                !inQuotes && angle == 0 && c == '(' -> paren++
                !inQuotes && angle == 0 && c == ')' && paren > 0 -> paren--
                !inQuotes && angle == 0 && paren == 0 && (c == ',' || c == ';' || c == '\n' || c == '\r') -> {
                    tokens += current.toString()
                    current.setLength(0)
                    index++
                    continue
                }
            }
            current.append(c)
            index++
        }
        tokens += current.toString()
        return tokens
    }

    private fun parseToken(raw: String): List<Candidate>? {
        var token = stripMailto(raw.trim())
        token = stripGroupLabel(token)
        val open = lastIndexOutsideQuotes(token, '<')
        if (open >= 0) {
            val close = token.indexOf('>', open + 1)
            if (close < 0) return null
            val address = stripMailto(token.substring(open + 1, close).trim())
            val before = token.substring(0, open).trim()
            val after = token.substring(close + 1).trim()
            val name = cleanName(before).ifEmpty { cleanName(after.removeSurrounding("(", ")")) }
            return listOf(Candidate(EmailAddress(address, name.ifEmpty { null }), bracketed = true))
        }
        // "ana@upm.es (Ana Pérez)"
        val commentStart = token.indexOf('(')
        if (commentStart > 0 && token.endsWith(")")) {
            val address = token.substring(0, commentStart).trim()
            val name = cleanName(token.substring(commentStart + 1, token.length - 1))
            if (!address.contains(' ')) return listOf(bare(EmailAddress(address, name.ifEmpty { null })))
        }
        if (token.startsWith("\"")) {
            // A quoted local part ("john doe"@x.es) or a quoted name followed by a bare address.
            val closing = closingQuote(token)
            if (closing > 0) {
                val rest = token.substring(closing + 1).trim()
                if (rest.startsWith("@")) return listOf(bare(EmailAddress(token, null)))
                if (rest.isNotEmpty() && !rest.contains(' ')) {
                    return listOf(bare(EmailAddress(rest, cleanName(token.substring(0, closing + 1)).ifEmpty { null })))
                }
            }
            return null
        }
        val words = token.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size <= 1) return listOf(bare(EmailAddress(token, null)))
        // Several bare addresses separated by spaces.
        if (words.all { isValidAddress(it) }) return words.map { bare(EmailAddress(it, null)) }
        // "Ana Pérez ana@upm.es": the last word is the address, the rest the name.
        val last = words.last().removeSurrounding("<", ">")
        if (isValidAddress(last)) {
            val name = cleanName(words.dropLast(1).joinToString(" "))
            return listOf(bare(EmailAddress(last, name.ifEmpty { null })))
        }
        return null
    }

    private fun bare(address: EmailAddress) = Candidate(address, bracketed = false)

    private fun stripMailto(value: String): String =
        if (value.startsWith("mailto:", ignoreCase = true)) value.substring(7).trim() else value

    /** Drops an RFC 5322 group label ("Team: a@x.es") but not a colon inside a quoted name. */
    private fun stripGroupLabel(value: String): String {
        val colon = indexOutsideQuotes(value, ':')
        if (colon <= 0) return value
        val at = value.indexOf('@')
        val angle = value.indexOf('<')
        val limit = listOf(at, angle).filter { it >= 0 }.minOrNull() ?: return value
        return if (colon < limit) value.substring(colon + 1).trim() else value
    }

    private fun cleanName(raw: String): String {
        var name = raw.trim()
        if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
            name = unescape(name.substring(1, name.length - 1))
        } else if (name.length >= 2 && name.startsWith('\'') && name.endsWith('\'')) {
            name = name.substring(1, name.length - 1)
        } else if ('"' in name) {
            // Partially quoted phrases such as Ana "La Jefa" Pérez.
            name = unescape(name.replace(Regex("(?<!\\\\)\""), ""))
        }
        if ("=?" in name) {
            name = try {
                MimeUtility.decodeText(name)
            } catch (_: Exception) {
                name
            }
        }
        return name
    }

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                out.append(value[index + 1])
                index += 2
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }

    private fun closingQuote(value: String): Int {
        var index = 1
        while (index < value.length) {
            when (value[index]) {
                '\\' -> index++
                '"' -> return index
            }
            index++
        }
        return -1
    }

    private fun lastIndexOutsideQuotes(value: String, target: Char): Int {
        var inQuotes = false
        var result = -1
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when {
                inQuotes && c == '\\' -> index++
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == target -> result = index
            }
            index++
        }
        return result
    }

    private fun indexOutsideQuotes(value: String, target: Char): Int {
        var inQuotes = false
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when {
                inQuotes && c == '\\' -> index++
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == target -> return index
            }
            index++
        }
        return -1
    }
}
