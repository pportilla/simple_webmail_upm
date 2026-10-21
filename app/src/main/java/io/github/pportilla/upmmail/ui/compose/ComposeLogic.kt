package io.github.pportilla.upmmail.ui.compose

import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.OutgoingAttachment
import java.text.Normalizer
import java.util.Locale

/*
 * Android-free rules of the compose screen: recipient tokenising, signature handling, when a
 * draft is worth keeping and where the cursor starts. Kept apart from the ViewModel so they can
 * be unit-tested on the plain JVM.
 */

/** The three recipient lists of a message. */
enum class RecipientKind { TO, CC, BCC }

/**
 * A committed recipient (one chip). Invalid entries keep what the user typed in
 * [EmailAddress.address] so it can be shown in error colour and edited back into text.
 */
data class Recipient(val address: EmailAddress, val valid: Boolean) {
    /** Lower-case address, used to avoid duplicates and as the autocomplete exclusion key. */
    val key: String get() = address.address.trim().lowercase(Locale.ROOT)

    /** What the chip shows: the name when there is one, otherwise the address. */
    val label: String get() = address.displayName

    /** The chip as editable text, e.g. `Ana Pérez <ana@upm.es>`; it parses back to the same chip. */
    fun toEditableText(): String {
        if (!valid) return address.address
        val name = address.name?.takeIf { it.isNotBlank() } ?: return address.address
        // Quotes only when the name would not survive parsing unquoted.
        return if (name.any { it in NAME_SPECIALS }) AddressParser.format(listOf(address)) else "$name <${address.address}>"
    }

    companion object {
        /** A chip for [address], valid when it is a syntactically correct bare address. */
        fun of(address: EmailAddress): Recipient {
            val trimmed = address.copy(address = address.address.trim(), name = address.name?.trim()?.ifEmpty { null })
            return Recipient(trimmed, AddressParser.isValidAddress(trimmed.address))
        }

        /** An entry that could not be parsed at all. */
        fun invalid(text: String): Recipient = Recipient(EmailAddress(text.trim()), valid = false)

        private const val NAME_SPECIALS = ",;:<>\"()[]\\@"
    }
}

/** Turns what is typed in a recipient input into chips. */
object RecipientTokenizer {

    /** Chips to add and the text left in the input. */
    data class Update(val committed: List<Recipient>, val remaining: String)

    /**
     * Processes an edit of the input from [old] to [new]:
     * - text before a `,`, `;` or line break (outside quotes and angle brackets) becomes chips;
     * - pasting one or more complete addresses (anything inserted at once containing `@` that
     *   parses without errors) commits them all;
     * - a space typed right after a complete bare address commits it.
     *
     * Anything else stays in the input, so names can be typed to search contacts.
     */
    fun onTextChanged(old: String, new: String): Update {
        if (new == old) return Update(emptyList(), new)
        val inserted = new.length - old.length
        val (complete, tail) = splitComplete(new)
        if (complete.isNotEmpty()) {
            val committed = complete.flatMap { parse(it) }
            val remaining = tail.trimStart()
            // A pasted list usually ends without a separator: its last address is complete too.
            if (inserted > 1 && isCompleteAddressList(remaining)) return Update(committed + parse(remaining), "")
            return Update(committed, remaining)
        }
        if (inserted > 1 && '@' in new) {
            val parsed = AddressParser.parse(new)
            if (parsed.invalid.isEmpty() && parsed.addresses.isNotEmpty() &&
                parsed.addresses.all { AddressParser.isValidAddress(it.address) }
            ) {
                return Update(parsed.addresses.map(Recipient::of), "")
            }
        }
        if (inserted == 1 && new.endsWith(' ') && new.dropLast(1) == old) {
            val candidate = old.trim()
            if (!candidate.contains(' ') && AddressParser.isValidAddress(candidate.removePrefix("mailto:"))) {
                return Update(parse(candidate), "")
            }
        }
        return Update(emptyList(), new)
    }

    /** Everything in [text] as chips (used on focus loss, IME action and before sending). */
    fun commitAll(text: String): List<Recipient> = if (text.isBlank()) emptyList() else parse(text)

    /** True if [text] holds at least one valid address (pending input that Send would commit). */
    fun hasValidAddress(text: String): Boolean {
        if (text.isBlank() || '@' !in text) return false
        return AddressParser.parse(text).addresses.any { AddressParser.isValidAddress(it.address) }
    }

    /** True if [text] is a complete list of valid addresses (nothing that looks like a search). */
    fun isCompleteAddressList(text: String): Boolean {
        if (text.isBlank()) return false
        val parsed = AddressParser.parse(text)
        return parsed.invalid.isEmpty() && parsed.addresses.isNotEmpty() &&
            parsed.addresses.all { AddressParser.isValidAddress(it.address) }
    }

    private fun parse(token: String): List<Recipient> {
        if (token.isBlank()) return emptyList()
        val parsed = AddressParser.parse(token)
        return parsed.addresses.map(Recipient::of) + parsed.invalid.map(Recipient::invalid)
    }

    /**
     * Splits [text] at separators outside quotes, angle brackets and comments. Returns the
     * complete tokens (blank ones dropped) and the unfinished tail after the last separator.
     */
    internal fun splitComplete(text: String): Pair<List<String>, String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var angle = 0
        var paren = 0
        var index = 0
        var sawSeparator = false
        while (index < text.length) {
            val c = text[index]
            when {
                inQuotes && c == '\\' && index + 1 < text.length -> {
                    current.append(c).append(text[index + 1])
                    index += 2
                    continue
                }
                c == '"' && paren == 0 -> inQuotes = !inQuotes
                !inQuotes && c == '<' -> angle++
                !inQuotes && c == '>' && angle > 0 -> angle--
                !inQuotes && angle == 0 && c == '(' -> paren++
                !inQuotes && angle == 0 && c == ')' && paren > 0 -> paren--
                !inQuotes && angle == 0 && paren == 0 && (c == ',' || c == ';' || c == '\n' || c == '\r') -> {
                    sawSeparator = true
                    if (current.isNotBlank()) tokens += current.toString()
                    current.setLength(0)
                    index++
                    continue
                }
            }
            current.append(c)
            index++
        }
        return if (sawSeparator) tokens to current.toString() else emptyList<String>() to text
    }
}

/** Adds [incoming] to [existing], skipping addresses already present in [taken] (lower-case keys). */
fun mergeRecipients(existing: List<Recipient>, incoming: List<Recipient>, taken: Set<String>): List<Recipient> {
    val seen = HashSet(taken)
    existing.mapTo(seen) { it.key }
    val added = incoming.filter { it.key.isNotEmpty() && seen.add(it.key) }
    return existing + added
}

/** The signature block as [io.github.pportilla.upmmail.data.repository.ComposeRepository] writes it. */
object Signatures {
    /** The standard signature delimiter line: dash, dash, space. */
    const val SEPARATOR = "-- \n"

    /** The block for [signature] ("-- \n" + text), or null when the signature is blank. */
    fun block(signature: String?): String? =
        signature?.trimEnd()?.takeIf { it.isNotBlank() }?.let { SEPARATOR + it }

    /** Index of [block] in [body] as a whole-line block, or -1. */
    fun indexOfBlock(body: String, block: String): Int {
        var from = 0
        while (from <= body.length - block.length) {
            val index = body.indexOf(block, from)
            if (index < 0) return -1
            val startsLine = index == 0 || body[index - 1] == '\n'
            val end = index + block.length
            val endsLine = end == body.length || body[end] == '\n' || body[end] == '\r'
            if (startsLine && endsLine) return index
            from = index + 1
        }
        return -1
    }

    /**
     * Replaces the [old] signature with [new] in [body] when the old block is still there
     * unchanged. When there was no old signature, the new one is inserted before [quoteAnchor]
     * (the quoted / forwarded text) if found, or appended when the body has no quoted text.
     * Returns [body] unchanged when the user edited the signature or there is no safe place.
     */
    fun swap(body: String, old: String?, new: String?, quoteAnchor: String? = null): String {
        val oldBlock = block(old)
        val newBlock = block(new)
        if (oldBlock == newBlock) return body
        if (oldBlock != null) {
            val index = indexOfBlock(body, oldBlock)
            if (index < 0) return body
            val before = body.substring(0, index)
            val after = body.substring(index + oldBlock.length)
            return if (newBlock != null) {
                before + newBlock + after
            } else {
                // Drop the block with the blank lines that introduced it.
                val head = before.trimEnd('\n', '\r')
                if (head.isEmpty() && after.isNotEmpty()) "\n\n" + after.trimStart('\n', '\r') else head + after
            }
        }
        newBlock ?: return body
        if (!quoteAnchor.isNullOrBlank()) {
            val index = body.indexOf(quoteAnchor)
            if (index >= 0) {
                val before = body.substring(0, index).trimEnd('\n', '\r', ' ')
                val lead = if (before.isEmpty()) "\n\n" else before + "\n\n"
                return lead + newBlock + "\n\n" + body.substring(index)
            }
        }
        if (body.lineSequence().any { it.startsWith(">") }) return body
        val trimmed = body.trimEnd()
        return (if (trimmed.isEmpty()) "\n\n" else trimmed + "\n\n") + newBlock
    }

    /** [body] without the [signature] block (if present unchanged). */
    fun strip(body: String, signature: String?): String {
        val block = block(signature) ?: return body
        val index = indexOfBlock(body, block)
        if (index < 0) return body
        return body.substring(0, index) + body.substring(index + block.length)
    }

    /**
     * The quoted / forwarded part of a freshly created reply or forward body
     * ("\n\n" + signature block + "\n\n" + quote), or null when there is none.
     */
    fun quotedPart(generatedBody: String, signature: String?): String? {
        val block = block(signature)
        val rest = if (block != null) {
            val index = indexOfBlock(generatedBody, block)
            if (index < 0) return null
            generatedBody.substring(index + block.length)
        } else {
            generatedBody
        }
        return rest.trimStart('\n', '\r').takeIf { it.isNotBlank() }
    }
}

/** Puts [prefix] (e.g. text shared from another app) before the signature / quoted text of [body]. */
fun prependBody(prefix: String, body: String): String {
    val head = prefix.trimEnd()
    if (head.isEmpty()) return body
    val rest = body.trimStart('\n', '\r')
    return if (rest.isBlank()) head else head + "\n\n" + rest
}

/**
 * Where the cursor starts in the body: right after the text the user wrote (before the signature
 * and the quoted text), so replies start at the top and drafts continue where they ended.
 * [quoteAnchor] is the quoted / forwarded text when known (it has no "> " markers for forwards).
 */
fun initialBodyCursor(body: String, quoteAnchor: String? = null): Int {
    var end = body.length
    val signature = when {
        body.startsWith(Signatures.SEPARATOR) -> 0
        else -> body.indexOf("\n" + Signatures.SEPARATOR).let { if (it >= 0) it + 1 else -1 }
    }
    if (signature >= 0) end = signature
    if (!quoteAnchor.isNullOrBlank()) {
        val quote = body.indexOf(quoteAnchor)
        if (quote in 0 until end) end = quote
    }
    val lines = body.substring(0, end).split('\n')
    var offset = 0
    for ((i, line) in lines.withIndex()) {
        if (line.startsWith(">")) {
            // The attribution line ("On ..., X wrote:") right above the quote belongs to it.
            val previous = lines.getOrNull(i - 1)
            end = if (previous != null && previous.isNotBlank()) offset - previous.length - 1 else offset
            break
        }
        offset += line.length + 1
    }
    end = end.coerceIn(0, body.length)
    return body.substring(0, end).trimEnd().length
}

/** The editable content of a draft, compared to know whether something changed. */
data class ComposeContent(
    val accountId: Long,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachments: List<OutgoingAttachment> = emptyList(),
) {
    val hasRecipients: Boolean get() = to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()
}

/** Where the draft on screen came from. */
enum class DraftOrigin {
    /** Built by createDraft (new message, reply, forward). */
    CREATED,

    /** A locally saved draft (e.g. restored after undoing a send). */
    LOCAL_DRAFT,

    /** A message of the server Drafts folder. */
    SERVER_DRAFT,
}

/** What leaving the screen does with the draft. */
enum class CloseAction { NOTHING, DISCARD, SAVE }

/**
 * True when [content] holds nothing the user wrote: no recipients, subject or attachments, and
 * a body that is blank or just the generated signature / quote.
 */
fun isEffectivelyEmpty(content: ComposeContent, generatedBody: String, signature: String?, generatedSignature: String?): Boolean {
    if (content.hasRecipients || content.subject.isNotBlank() || content.attachments.isNotEmpty()) return false
    val body = Signatures.strip(content.body, signature).trim()
    if (body.isEmpty()) return true
    return body == Signatures.strip(generatedBody, generatedSignature).trim()
}

/**
 * Decides what closing the screen does.
 *
 * @param initial the content when the screen became ready (prefills included).
 * @param generated the content as loaded or created, before prefills.
 * @param lastUploaded the content of the known server copy, null if unknown / none.
 * @param hasLocalCopy whether a local draft row exists for this screen.
 * @param empty result of [isEffectivelyEmpty] for [content].
 */
fun closeAction(
    content: ComposeContent,
    initial: ComposeContent,
    generated: ComposeContent,
    lastUploaded: ComposeContent?,
    origin: DraftOrigin,
    hasLocalCopy: Boolean,
    empty: Boolean,
): CloseAction {
    if (lastUploaded != null && content == lastUploaded) return CloseAction.NOTHING
    return when (origin) {
        DraftOrigin.CREATED -> when {
            // Nothing of the user's: no draft. A copy saved meanwhile goes away too.
            content == initial || empty -> if (hasLocalCopy) CloseAction.DISCARD else CloseAction.NOTHING
            else -> CloseAction.SAVE
        }
        DraftOrigin.LOCAL_DRAFT, DraftOrigin.SERVER_DRAFT -> when {
            content == generated -> if (empty) CloseAction.NOTHING else CloseAction.SAVE
            // The user cleared an existing draft completely.
            empty -> CloseAction.DISCARD
            else -> CloseAction.SAVE
        }
    }
}

/** Which field gets the focus when the screen opens. */
enum class FocusTarget { TO, CC, BCC, SUBJECT, BODY }

/** To first for new messages and forwards, the body for replies and drafts with recipients. */
fun initialFocus(mode: ComposeMode, origin: DraftOrigin, content: ComposeContent): FocusTarget = when {
    !content.hasRecipients -> FocusTarget.TO
    origin == DraftOrigin.CREATED && mode == ComposeMode.NEW && content.subject.isBlank() -> FocusTarget.SUBJECT
    else -> FocusTarget.BODY
}

/** Total size of [attachments] in bytes (unknown sizes count as zero). */
fun totalAttachmentSize(attachments: List<OutgoingAttachment>): Long = attachments.sumOf { it.size.coerceAtLeast(0) }

/** Above this the server may refuse the message (attachments grow by a third when encoded). */
const val LARGE_MESSAGE_BYTES: Long = 20L * 1024 * 1024

/** Broad attachment categories, for the row icon. */
enum class AttachmentKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, SPREADSHEET, PRESENTATION, DOCUMENT, OTHER }

/** The category of an attachment from its MIME type, falling back to the file extension. */
fun attachmentKind(mimeType: String, fileName: String): AttachmentKind {
    val type = mimeType.lowercase(Locale.ROOT)
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        type.startsWith("image/") -> AttachmentKind.IMAGE
        type.startsWith("video/") -> AttachmentKind.VIDEO
        type.startsWith("audio/") -> AttachmentKind.AUDIO
        type == "application/pdf" || extension == "pdf" -> AttachmentKind.PDF
        extension in setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz") ||
            type.contains("zip") || type.contains("compressed") || type.contains("x-tar") -> AttachmentKind.ARCHIVE
        extension in setOf("xls", "xlsx", "ods", "csv") || type.contains("spreadsheet") || type.contains("ms-excel") ||
            type == "text/csv" -> AttachmentKind.SPREADSHEET
        extension in setOf("ppt", "pptx", "odp", "key") || type.contains("presentation") || type.contains("powerpoint") ->
            AttachmentKind.PRESENTATION
        extension in setOf("doc", "docx", "odt", "rtf", "pages") || type.contains("wordprocessing") || type.contains("msword") ||
            type.contains("opendocument.text") -> AttachmentKind.DOCUMENT
        type.startsWith("text/") || extension in setOf("txt", "md", "log", "tex") -> AttachmentKind.TEXT
        else -> AttachmentKind.OTHER
    }
}

/**
 * Ranges of [text] where a word starts with one of the words of [query], ignoring case and
 * accents ("jose" matches "José"), for bolding autocomplete matches.
 */
fun matchRanges(text: String, query: String): List<IntRange> {
    val terms = query.split(' ', ',', ';', '<', '>', '"').map(::fold).filter { it.isNotEmpty() }
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val folded = fold(text)
    val ranges = ArrayList<IntRange>()
    for (term in terms) {
        var from = 0
        while (from <= folded.length - term.length) {
            val index = folded.indexOf(term, from)
            if (index < 0) break
            val wordStart = index == 0 || !folded[index - 1].isLetterOrDigit()
            if (wordStart) {
                ranges += index until index + term.length
                break
            }
            from = index + 1
        }
    }
    if (ranges.isEmpty()) return emptyList()
    ranges.sortBy { it.first }
    val merged = ArrayList<IntRange>()
    var current = ranges.first()
    for (range in ranges.drop(1)) {
        current = if (range.first <= current.last + 1) {
            current.first..maxOf(current.last, range.last)
        } else {
            merged += current
            range
        }
    }
    merged += current
    return merged
}

/** Lower-case without diacritics, one output character per input character. */
private fun fold(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) {
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
        out.append(base.lowercaseChar())
    }
    return out.toString()
}
