package io.github.pportilla.upmmail.ui.compose

import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.OutgoingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLogicTest {

    // ------------------------------------------------------------------ tokenizer

    @Test
    fun `typing a comma commits the address before it`() {
        val update = RecipientTokenizer.onTextChanged("ana@upm.es", "ana@upm.es,")
        assertEquals(listOf(Recipient(EmailAddress("ana@upm.es"), valid = true)), update.committed)
        assertEquals("", update.remaining)
    }

    @Test
    fun `semicolon commits and keeps the unfinished tail`() {
        val update = RecipientTokenizer.onTextChanged("ana@upm.es", "ana@upm.es; lu")
        assertEquals(listOf("ana@upm.es"), update.committed.map { it.address.address })
        assertEquals("lu", update.remaining)
    }

    @Test
    fun `comma inside a quoted name does not split`() {
        val update = RecipientTokenizer.onTextChanged("\"Pérez", "\"Pérez,")
        assertTrue(update.committed.isEmpty())
        assertEquals("\"Pérez,", update.remaining)

        val done = RecipientTokenizer.onTextChanged("\"Pérez, Ana\" <ana@upm.es>", "\"Pérez, Ana\" <ana@upm.es>,")
        assertEquals(listOf(Recipient(EmailAddress("ana@upm.es", "Pérez, Ana"), valid = true)), done.committed)
    }

    @Test
    fun `a name followed by a comma becomes an invalid chip`() {
        val update = RecipientTokenizer.onTextChanged("Ana", "Ana,")
        assertEquals(listOf(Recipient(EmailAddress("Ana"), valid = false)), update.committed)
    }

    @Test
    fun `space after a complete address commits it but not after a name`() {
        val address = RecipientTokenizer.onTextChanged("luis@alumnos.upm.es", "luis@alumnos.upm.es ")
        assertEquals(listOf("luis@alumnos.upm.es"), address.committed.map { it.address.address })
        assertEquals("", address.remaining)

        val name = RecipientTokenizer.onTextChanged("Ana", "Ana ")
        assertTrue(name.committed.isEmpty())
        assertEquals("Ana ", name.remaining)

        val incomplete = RecipientTokenizer.onTextChanged("ana@upm", "ana@upm ")
        assertTrue(incomplete.committed.isEmpty())
    }

    @Test
    fun `pasting a list commits every address`() {
        val update = RecipientTokenizer.onTextChanged("", "Ana Pérez <ana@upm.es>, luis@upm.es")
        assertEquals(listOf("ana@upm.es", "luis@upm.es"), update.committed.map { it.address.address })
        assertEquals("Ana Pérez", update.committed.first().address.name)
        assertEquals("", update.remaining)

        // Typed (one character at a time), the tail stays editable.
        val typed = RecipientTokenizer.onTextChanged("ana@upm.es, l", "ana@upm.es, lu")
        assertEquals(listOf("ana@upm.es"), typed.committed.map { it.address.address })
        assertEquals("lu", typed.remaining)

        val spaced = RecipientTokenizer.onTextChanged("", "a@upm.es b@upm.es")
        assertEquals(listOf("a@upm.es", "b@upm.es"), spaced.committed.map { it.address.address })
    }

    @Test
    fun `pasting something that is not an address list keeps it as text`() {
        val update = RecipientTokenizer.onTextChanged("", "write to ana@upm")
        assertTrue(update.committed.isEmpty())
        assertEquals("write to ana@upm", update.remaining)
    }

    @Test
    fun `commitAll keeps invalid entries as invalid chips`() {
        val chips = RecipientTokenizer.commitAll("ana@upm.es, nonsense")
        assertEquals(listOf(true, false), chips.map { it.valid })
        assertEquals("nonsense", chips[1].address.address)
        assertTrue(RecipientTokenizer.commitAll("   ").isEmpty())
    }

    @Test
    fun `pending text with an address counts for the send button`() {
        assertTrue(RecipientTokenizer.hasValidAddress("ana@upm.es"))
        assertFalse(RecipientTokenizer.hasValidAddress("ana"))
        assertFalse(RecipientTokenizer.hasValidAddress("ana@upm"))
        assertTrue(RecipientTokenizer.isCompleteAddressList("a@upm.es; b@upm.es"))
        assertFalse(RecipientTokenizer.isCompleteAddressList("ana pe"))
    }

    @Test
    fun `merge skips duplicates case-insensitively and across fields`() {
        val existing = listOf(Recipient.of(EmailAddress("Ana@UPM.es")))
        val merged = mergeRecipients(
            existing,
            listOf(Recipient.of(EmailAddress("ana@upm.es")), Recipient.of(EmailAddress("luis@upm.es")), Recipient.of(EmailAddress("jefe@upm.es"))),
            taken = setOf("jefe@upm.es"),
        )
        assertEquals(listOf("Ana@UPM.es", "luis@upm.es"), merged.map { it.address.address })
    }

    @Test
    fun `editable text of a chip round-trips`() {
        val chip = Recipient.of(EmailAddress("ana@upm.es", "Ana Pérez"))
        assertEquals("Ana Pérez <ana@upm.es>", chip.toEditableText())
        assertEquals(listOf(chip), RecipientTokenizer.commitAll(chip.toEditableText()))
        val comma = Recipient.of(EmailAddress("ana@upm.es", "Pérez, Ana"))
        assertEquals("\"Pérez, Ana\" <ana@upm.es>", comma.toEditableText())
        assertEquals(listOf(comma), RecipientTokenizer.commitAll(comma.toEditableText()))
        assertEquals("luis@upm.es", Recipient.of(EmailAddress("luis@upm.es")).toEditableText())
        assertEquals("bad", Recipient.invalid(" bad ").toEditableText())
    }

    // ------------------------------------------------------------------ signatures

    private val oldSig = "Ana\nETSI Informáticos"
    private val newSig = "Ana Pérez\nRectorado"

    @Test
    fun `swap replaces an untouched signature`() {
        val body = "Hola\n\n-- \n$oldSig\n\nOn Monday, Luis wrote:\n> hi"
        assertEquals("Hola\n\n-- \n$newSig\n\nOn Monday, Luis wrote:\n> hi", Signatures.swap(body, oldSig, newSig))
    }

    @Test
    fun `swap leaves an edited signature alone`() {
        val body = "Hola\n\n-- \nAna (edited)\nETSI Informáticos"
        assertEquals(body, Signatures.swap(body, oldSig, newSig))
    }

    @Test
    fun `swap removes the signature when the new account has none`() {
        assertEquals("", Signatures.swap("\n\n-- \n$oldSig", oldSig, ""))
        assertEquals("Hola", Signatures.swap("Hola\n\n-- \n$oldSig", oldSig, null))
        assertEquals("\n\n> quoted", Signatures.swap("\n\n-- \n$oldSig\n\n> quoted", oldSig, " "))
    }

    @Test
    fun `swap inserts a signature before the quote or at the end`() {
        val quote = "On Monday, Luis wrote:\n> hi"
        assertEquals("\n\n-- \n$newSig\n\n$quote", Signatures.swap("\n\n$quote", null, newSig, quoteAnchor = quote))
        assertEquals("Hola\n\n-- \n$newSig\n\n$quote", Signatures.swap("Hola\n\n$quote", "", newSig, quoteAnchor = quote))
        assertEquals("\n\n-- \n$newSig", Signatures.swap("", null, newSig))
        assertEquals("Hola\n\n-- \n$newSig", Signatures.swap("Hola  \n", null, newSig))
        // Quoted text but no known anchor: no safe place.
        assertEquals("Hola\n> hi", Signatures.swap("Hola\n> hi", null, newSig))
    }

    @Test
    fun `signature block must be a whole-line block`() {
        val body = "x-- \nAna\nETSI Informáticos"
        assertEquals(-1, Signatures.indexOfBlock(body, Signatures.block(oldSig)!!))
        assertNull(Signatures.block("   "))
    }

    @Test
    fun `quoted part of a generated reply`() {
        val generated = "\n\n-- \n$oldSig\n\nOn Monday, Luis wrote:\n> hi"
        assertEquals("On Monday, Luis wrote:\n> hi", Signatures.quotedPart(generated, oldSig))
        assertEquals("On Monday, Luis wrote:\n> hi", Signatures.quotedPart("\n\nOn Monday, Luis wrote:\n> hi", null))
        assertNull(Signatures.quotedPart("\n\n-- \n$oldSig", oldSig))
        assertNull(Signatures.quotedPart("", null))
    }

    @Test
    fun `strip removes only an untouched signature`() {
        assertEquals("Hola\n\n", Signatures.strip("Hola\n\n-- \n$oldSig", oldSig))
        assertEquals("Hola", Signatures.strip("Hola", oldSig))
    }

    // ------------------------------------------------------------------ body helpers

    @Test
    fun `prefill body goes before the signature`() {
        assertEquals("Hello\n\n-- \nSig", prependBody("Hello\n", "\n\n-- \nSig"))
        assertEquals("Hello", prependBody("Hello", ""))
        assertEquals("\n\n-- \nSig", prependBody("  ", "\n\n-- \nSig"))
        assertEquals("Hello\n\nOld text", prependBody("Hello", "Old text"))
    }

    @Test
    fun `cursor starts after the user's text`() {
        assertEquals(0, initialBodyCursor(""))
        assertEquals(0, initialBodyCursor("\n\n-- \nSig\n\nOn X wrote:\n> q"))
        assertEquals(5, initialBodyCursor("Hello\n\n-- \nSig"))
        assertEquals(0, initialBodyCursor("-- \nSig"))
        assertEquals(7, initialBodyCursor("my text\n\nOn X, Y wrote:\n> quote"))
        assertEquals(0, initialBodyCursor("\n\nOn X wrote:\n> q"))
        assertEquals(10, initialBodyCursor("plain text"))
        val forward = "---------- Forwarded message ----------\nFrom: x"
        assertEquals(0, initialBodyCursor("\n\n$forward", quoteAnchor = forward))
    }

    // ------------------------------------------------------------------ close decisions

    private val generated = ComposeContent(accountId = 1, body = "\n\n-- \nSig")

    private fun decide(
        content: ComposeContent,
        origin: DraftOrigin = DraftOrigin.CREATED,
        initial: ComposeContent = generated,
        lastUploaded: ComposeContent? = null,
        hasLocalCopy: Boolean = false,
    ) = closeAction(
        content = content,
        initial = initial,
        generated = generated,
        lastUploaded = lastUploaded,
        origin = origin,
        hasLocalCopy = hasLocalCopy,
        empty = isEffectivelyEmpty(content, generated.body, "Sig", "Sig"),
    )

    @Test
    fun `untouched or empty new messages leave no draft`() {
        assertEquals(CloseAction.NOTHING, decide(generated))
        assertEquals(CloseAction.NOTHING, decide(generated.copy(body = "  \n\n-- \nSig\n")))
        assertEquals(CloseAction.DISCARD, decide(generated, hasLocalCopy = true))
    }

    @Test
    fun `new messages with content are saved`() {
        assertEquals(CloseAction.SAVE, decide(generated.copy(body = "Hola\n\n-- \nSig")))
        assertEquals(CloseAction.SAVE, decide(generated.copy(subject = "Exam")))
        assertEquals(CloseAction.SAVE, decide(generated.copy(to = listOf(EmailAddress("a@upm.es")))))
    }

    @Test
    fun `untouched prefilled message is dropped silently`() {
        val shared = generated.copy(to = listOf(EmailAddress("a@upm.es")), subject = "Link")
        assertEquals(CloseAction.NOTHING, decide(shared, initial = shared))
        assertEquals(CloseAction.SAVE, decide(shared.copy(subject = "Link!"), initial = shared))
    }

    @Test
    fun `server draft unchanged needs nothing, cleared one is discarded`() {
        val draft = ComposeContent(accountId = 1, to = listOf(EmailAddress("a@upm.es")), body = "text")
        val decideDraft = { content: ComposeContent ->
            closeAction(
                content = content,
                initial = draft,
                generated = draft,
                lastUploaded = draft,
                origin = DraftOrigin.SERVER_DRAFT,
                hasLocalCopy = false,
                empty = isEffectivelyEmpty(content, draft.body, null, null),
            )
        }
        assertEquals(CloseAction.NOTHING, decideDraft(draft))
        assertEquals(CloseAction.SAVE, decideDraft(draft.copy(body = "more text")))
        assertEquals(CloseAction.DISCARD, decideDraft(ComposeContent(accountId = 1)))
    }

    @Test
    fun `reopened local draft is uploaded on close`() {
        val draft = ComposeContent(accountId = 1, subject = "Hi")
        val action = closeAction(draft, draft, draft, null, DraftOrigin.LOCAL_DRAFT, hasLocalCopy = true, empty = false)
        assertEquals(CloseAction.SAVE, action)
    }

    @Test
    fun `emptiness ignores the signature of the current account`() {
        val switched = ComposeContent(accountId = 2, body = "\n\n-- \nOther")
        assertTrue(isEffectivelyEmpty(switched, "\n\n-- \nSig", "Other", "Sig"))
        assertFalse(isEffectivelyEmpty(switched.copy(body = "Hi\n\n-- \nOther"), "\n\n-- \nSig", "Other", "Sig"))
        val attachment = OutgoingAttachment("file:///x", "x.pdf", "application/pdf", 10)
        assertFalse(isEffectivelyEmpty(switched.copy(attachments = listOf(attachment)), "", "Other", "Sig"))
    }

    // ------------------------------------------------------------------ focus, attachments, highlight

    @Test
    fun `initial focus follows the kind of message`() {
        val empty = ComposeContent(accountId = 1)
        val withTo = empty.copy(to = listOf(EmailAddress("a@upm.es")))
        assertEquals(FocusTarget.TO, initialFocus(ComposeMode.NEW, DraftOrigin.CREATED, empty))
        assertEquals(FocusTarget.TO, initialFocus(ComposeMode.FORWARD, DraftOrigin.CREATED, empty))
        assertEquals(FocusTarget.SUBJECT, initialFocus(ComposeMode.NEW, DraftOrigin.CREATED, withTo))
        assertEquals(FocusTarget.BODY, initialFocus(ComposeMode.NEW, DraftOrigin.CREATED, withTo.copy(subject = "s")))
        assertEquals(FocusTarget.BODY, initialFocus(ComposeMode.REPLY, DraftOrigin.CREATED, withTo.copy(subject = "Re: s")))
        assertEquals(FocusTarget.BODY, initialFocus(ComposeMode.EDIT_DRAFT, DraftOrigin.SERVER_DRAFT, withTo))
    }

    @Test
    fun `attachment sizes and kinds`() {
        val files = listOf(
            OutgoingAttachment("u1", "a.pdf", "application/pdf", 15L * 1024 * 1024),
            OutgoingAttachment("u2", "b.bin", "application/octet-stream", 6L * 1024 * 1024),
            OutgoingAttachment("u3", "c", "", -1),
        )
        assertEquals(21L * 1024 * 1024, totalAttachmentSize(files))
        assertTrue(totalAttachmentSize(files) > LARGE_MESSAGE_BYTES)
        assertEquals(AttachmentKind.PDF, attachmentKind("application/octet-stream", "Acta.PDF"))
        assertEquals(AttachmentKind.IMAGE, attachmentKind("image/jpeg", "photo.jpg"))
        assertEquals(AttachmentKind.SPREADSHEET, attachmentKind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "notas.xlsx"))
        assertEquals(AttachmentKind.DOCUMENT, attachmentKind("application/msword", "tfg.doc"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKind("application/zip", "code.zip"))
        assertEquals(AttachmentKind.TEXT, attachmentKind("text/plain", "notes.txt"))
        assertEquals(AttachmentKind.OTHER, attachmentKind("application/octet-stream", "data.bin"))
    }

    @Test
    fun `match ranges are word prefixes ignoring case and accents`() {
        assertEquals(listOf(0..3), matchRanges("José García", "jose"))
        assertEquals(listOf(5..7), matchRanges("José García", "gar"))
        assertEquals(listOf(0..1, 5..6), matchRanges("José García", "jo ga"))
        assertTrue(matchRanges("Rosario", "sa").isEmpty())
        assertTrue(matchRanges("Ana", "").isEmpty())
    }
}
