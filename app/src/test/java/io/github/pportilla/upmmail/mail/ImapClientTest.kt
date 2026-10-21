package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ImapClientTest : GreenMailTestBase() {
    @get:Rule
    val temp = TemporaryFolder()

    private fun <T> client(block: suspend (ImapClient) -> T): T = runBlocking {
        val client = newImapClient()
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun seed(count: Int, folder: String = "INBOX") {
        val messages = (1..count).map { plainMessage("Message $it", body = "Body $it") }
        append(folder, *messages.toTypedArray())
    }

    @Test
    fun connectSucceedsWithRightPassword() = client { it.connect() }

    @Test
    fun wrongPasswordIsAuthenticationError() = runBlocking {
        val client = newImapClient(pass = "wrong")
        try {
            client.connect()
            fail("login should fail")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.AUTHENTICATION, e.kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun unreachableServerIsNetworkError() = runBlocking {
        val settings = imapSettings()
        greenMail.stop()
        val client = ImapClient(settings) { password }
        try {
            client.listFolders()
            fail("should not connect")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NETWORK, e.kind)
        } finally {
            client.close()
            greenMail.start()
        }
    }

    @Test
    fun listsFoldersIncludingNestedOnes() {
        createFolder("Projects")
        createFolder("Projects.2024")
        createFolder("Enviados")
        val folders = client { it.listFolders() }
        val byName = folders.associateBy { it.fullName }
        assertNotNull(byName["INBOX"])
        assertEquals(0, byName.getValue("Projects").depth)
        val nested = byName.getValue("Projects.2024")
        assertEquals(1, nested.depth)
        assertEquals("2024", nested.name)
        assertEquals('.', nested.separator)
        assertTrue(nested.selectable)
        assertEquals(io.github.pportilla.upmmail.model.FolderRole.SENT, FolderRoleDetector.detect("Enviados", byName.getValue("Enviados").attributes))
    }

    @Test
    fun statusReportsCountsAndUids() {
        seed(3)
        append("INBOX", plainMessage("Read one"), flags = Flags(Flags.Flag.SEEN))
        val status = client { it.status("INBOX") }
        assertEquals(4, status.messageCount)
        assertEquals(3, status.unreadCount)
        assertTrue(status.uidValidity > 0)
        assertTrue(status.uidNext > 4)
    }

    @Test
    fun statusOfMissingFolderIsNotFound() = client { client ->
        try {
            client.status("Nope")
            fail("expected NOT_FOUND")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun fetchNewestReturnsLastMessagesAscending() {
        seed(5)
        client { client ->
            val page = client.fetchNewest("INBOX", 3)
            assertTrue(page.truncated)
            assertEquals(listOf("Message 3", "Message 4", "Message 5"), page.messages.map { it.subject })
            assertEquals(page.messages.map { it.uid }.sorted(), page.messages.map { it.uid })
            assertTrue(page.uidValidity > 0)

            val all = client.fetchNewest("INBOX", 10)
            assertFalse(all.truncated)
            assertEquals(5, all.messages.size)

            val empty = client.fetchNewest("INBOX", 0)
            assertTrue(empty.messages.isEmpty())
        }
    }

    @Test
    fun fetchNewestOnEmptyFolder() {
        createFolder("Empty")
        val page = client { it.fetchNewest("Empty", 10) }
        assertTrue(page.messages.isEmpty())
        assertFalse(page.truncated)
    }

    @Test
    fun fetchNewerHonoursRangeLimitAndTruncation() {
        seed(5)
        client { client ->
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            val newer = client.fetchNewer("INBOX", uids[1], 2)
            assertEquals(listOf(uids[3], uids[4]), newer.messages.map { it.uid })
            assertTrue(newer.truncated)

            val rest = client.fetchNewer("INBOX", uids[1], 10)
            assertEquals(uids.drop(2), rest.messages.map { it.uid })
            assertFalse(rest.truncated)

            // Past the last uid IMAP would return the last message; it must be filtered out.
            val none = client.fetchNewer("INBOX", uids.last(), 10)
            assertTrue(none.messages.isEmpty())
            assertFalse(none.truncated)

            val fromStart = client.fetchNewer("INBOX", 0, 10)
            assertEquals(uids, fromStart.messages.map { it.uid })
        }
    }

    @Test
    fun fetchOlderHonoursRangeLimitAndTruncation() {
        seed(5)
        client { client ->
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            val older = client.fetchOlder("INBOX", uids[3], 2)
            assertEquals(listOf(uids[1], uids[2]), older.messages.map { it.uid })
            assertTrue(older.truncated)

            val all = client.fetchOlder("INBOX", uids[3], 10)
            assertEquals(uids.take(3), all.messages.map { it.uid })
            assertFalse(all.truncated)

            assertTrue(client.fetchOlder("INBOX", 1, 10).messages.isEmpty())
            assertTrue(client.fetchOlder("INBOX", uids[0], 10).messages.isEmpty())
        }
    }

    @Test
    fun mapsEnvelopeAndHeaders() {
        val original = plainMessage(
            subject = "Reunión de departamento ✓",
            from = "\"Pérez, Ana\" <ana.perez@upm.es>",
            to = "Bea <bea@upm.es>, carlos@upm.es",
            cc = "dani@alumnos.upm.es",
            messageId = "<reply-1@upm.es>",
            headers = mapOf(
                "In-Reply-To" to "<root-1@upm.es>",
                "References" to "<root-1@upm.es>\r\n <middle-1@upm.es>",
                "List-Unsubscribe" to "<mailto:leave@lists.upm.es>,\r\n <https://lists.upm.es/leave>",
                "Reply-To" to "lista@lists.upm.es",
            ),
        )
        append("INBOX", original)
        val message = client { it.fetchNewest("INBOX", 1) }.messages.single()
        assertEquals("Reunión de departamento ✓", message.subject)
        assertEquals("ana.perez@upm.es", message.from?.address)
        assertEquals("Pérez, Ana", message.from?.name)
        assertEquals(listOf("bea@upm.es", "carlos@upm.es"), message.to.map { it.address })
        assertEquals("Bea", message.to.first().name)
        assertEquals(listOf("dani@alumnos.upm.es"), message.cc.map { it.address })
        assertEquals(listOf("lista@lists.upm.es"), message.replyTo.map { it.address })
        assertEquals("<reply-1@upm.es>", message.messageId)
        assertEquals("<root-1@upm.es>", message.inReplyTo)
        assertEquals(listOf("<root-1@upm.es>", "<middle-1@upm.es>"), message.references)
        assertEquals("<mailto:leave@lists.upm.es>, <https://lists.upm.es/leave>", message.listUnsubscribe)
        assertEquals(1_700_000_000_000L, message.sentDate)
        assertNotNull(message.internalDate)
        assertTrue(message.size > 0)
        assertFalse(message.hasAttachments)
        assertFalse(message.flags.seen)
    }

    @Test
    fun replyToEqualToFromIsDropped() {
        append("INBOX", plainMessage("No reply-to"))
        val message = client { it.fetchNewest("INBOX", 1) }.messages.single()
        assertTrue(message.replyTo.isEmpty())
    }

    @Test
    fun setFlagIsVisibleInFetchFlags() {
        seed(3)
        client { client ->
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            client.setFlag("INBOX", listOf(uids[0], uids[1]), MessageFlag.SEEN, true)
            client.setFlag("INBOX", listOf(uids[1]), MessageFlag.FLAGGED, true)
            client.setFlag("INBOX", listOf(uids[2]), MessageFlag.ANSWERED, true)
            client.setFlag("INBOX", listOf(uids[2]), MessageFlag.FORWARDED, true)
            client.setFlag("INBOX", emptyList(), MessageFlag.SEEN, true)

            var flags = client.fetchFlags("INBOX", uids.first(), uids.last())
            assertEquals(3, flags.size)
            assertEquals(RemoteFlags(seen = true), flags[uids[0]])
            assertEquals(RemoteFlags(seen = true, flagged = true), flags[uids[1]])
            assertEquals(RemoteFlags(answered = true, forwarded = true), flags[uids[2]])

            client.setFlag("INBOX", listOf(uids[0]), MessageFlag.SEEN, false)
            flags = client.fetchFlags("INBOX", uids.first(), uids.last())
            assertFalse(flags.getValue(uids[0]).seen)

            // A range beyond the last uid returns nothing rather than the last message.
            assertTrue(client.fetchFlags("INBOX", uids.last() + 1, uids.last() + 100).isEmpty())
            assertTrue(client.fetchFlags("INBOX", 10, 5).isEmpty())
        }
    }

    @Test
    fun expungedMessagesDisappearFromFetchFlags() {
        seed(3)
        client { client ->
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            client.deletePermanently("INBOX", listOf(uids[1]))
            val flags = client.fetchFlags("INBOX", uids.first(), uids.last())
            assertEquals(setOf(uids[0], uids[2]), flags.keys)
        }
    }

    @Test
    fun fetchMessagesIgnoresMissingUids() {
        seed(3)
        client { client ->
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            val messages = client.fetchMessages("INBOX", listOf(uids[2], 9999L, uids[0]))
            assertEquals(listOf(uids[0], uids[2]), messages.map { it.uid })
            assertTrue(client.fetchMessages("INBOX", emptyList()).isEmpty())
        }
    }

    @Test
    fun moveUsesMoveCommand() = assertMoveWorks(disabled = emptySet())

    @Test
    fun moveFallsBackToCopyAndUidExpunge() = assertMoveWorks(disabled = setOf("MOVE"))

    @Test
    fun moveFallsBackToCopyAndExpungeWithoutUidplus() = assertMoveWorks(disabled = setOf("MOVE", "UIDPLUS"))

    private fun assertMoveWorks(disabled: Set<String>) {
        seed(3)
        createFolder("Archive")
        client { client ->
            client.disabledCapabilities = disabled
            val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
            client.move("INBOX", listOf(uids[0], uids[2]), "Archive")
            assertEquals(listOf("Message 2"), client.fetchNewest("INBOX", 10).messages.map { it.subject })
            assertEquals(
                listOf("Message 1", "Message 3"),
                client.fetchNewest("Archive", 10).messages.map { it.subject },
            )
        }
    }

    @Test
    fun moveToMissingFolderIsNotFound() {
        seed(1)
        client { client ->
            val uid = client.fetchNewest("INBOX", 1).messages.single().uid
            try {
                client.move("INBOX", listOf(uid), "Does not exist")
                fail("expected NOT_FOUND")
            } catch (e: MailException) {
                assertEquals(MailErrorKind.NOT_FOUND, e.kind)
            }
            assertEquals(1, client.fetchNewest("INBOX", 10).messages.size)
        }
    }

    @Test
    fun deleteAllEmptiesTheFolder() {
        seed(4, folder = "Trash")
        client { client ->
            client.deleteAll("Trash")
            assertTrue(client.fetchNewest("Trash", 10).messages.isEmpty())
            assertEquals(0, client.status("Trash").messageCount)
            client.deleteAll("Trash")
        }
    }

    @Test
    fun appendReturnsUidAndMessageIsFoundByMessageId() {
        createFolder("Drafts")
        val composed = MessageComposer.build(
            from = io.github.pportilla.upmmail.model.EmailAddress(email, "Ana"),
            draft = io.github.pportilla.upmmail.model.Draft(
                accountId = 1,
                to = listOf(io.github.pportilla.upmmail.model.EmailAddress("bea@upm.es")),
                subject = "Borrador",
                body = "Texto",
            ),
            attachments = emptyList(),
        )
        client { client ->
            val uid = client.append("Drafts", composed.mime, seen = true, draft = true)
            assertNotNull(uid)
            assertEquals(uid, client.findUidByMessageId("Drafts", composed.messageId))
            val flags = client.fetchFlags("Drafts", uid!!, uid).getValue(uid)
            assertTrue(flags.seen)
            assertTrue(flags.draft)
            val stored = client.fetchMessages("Drafts", listOf(uid)).single()
            assertEquals(composed.messageId, stored.messageId)
            assertNull(client.findUidByMessageId("Drafts", "<nope@upm.es>"))
        }
    }

    @Test
    fun appendToMissingFolderIsNotFound() = client { client ->
        val message = plainMessage("x")
        try {
            client.append("Missing", message, seen = true, draft = false)
            fail("expected NOT_FOUND")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun searchFindsSubjectFromAndBody() {
        append(
            "INBOX",
            plainMessage("Horario de tutorías", body = "Nos vemos el lunes"),
            plainMessage("Otro asunto", body = "Contiene la palabra presupuesto", from = "carlos@upm.es"),
            plainMessage("Tercero", body = "Nada", from = "Dani <dani@alumnos.upm.es>"),
        )
        client { client ->
            val all = client.fetchNewest("INBOX", 10).messages
            val bySubject = all.associate { it.subject to it.uid }
            assertEquals(listOf(bySubject.getValue("Horario de tutorías")), client.search("INBOX", "tutorías", 10))
            assertEquals(listOf(bySubject.getValue("Otro asunto")), client.search("INBOX", "presupuesto", 10))
            // GreenMail matches FROM/TO against whole addresses (real servers match substrings).
            assertEquals(listOf(bySubject.getValue("Tercero")), client.search("INBOX", "dani@alumnos.upm.es", 10))
            val everyone = client.search("INBOX", email, 10)
            assertEquals(all.map { it.uid }.sortedDescending(), everyone)
            assertEquals(everyone.take(2), client.search("INBOX", email, 2))
            assertTrue(client.search("INBOX", "   ", 10).isEmpty())
            assertTrue(client.search("INBOX", "inexistente", 10).isEmpty())
        }
    }

    @Test
    fun fetchBodyOfAlternativePrefersHtmlAndKeepsText() {
        append("INBOX", alternativeMessage("Alt", "Plain version", "<p>HTML <b>version</b></p>"))
        client { client ->
            val uid = client.fetchNewest("INBOX", 1).messages.single().uid
            val body = client.fetchBody("INBOX", uid)
            assertTrue(body.html!!.contains("<b>version</b>"))
            assertEquals("Plain version", body.text)
            assertTrue(body.attachments.isEmpty())
            // Reading the body never marks the message as read.
            assertFalse(client.fetchFlags("INBOX", uid, uid).getValue(uid).seen)
        }
    }

    @Test
    fun fetchBodyOfMixedMessageListsAttachments() {
        val pdf = Random(1).nextBytes(40_000)
        val message = plainMessage("Con adjuntos")
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(MimeBodyPart().apply { setText("Ver adjuntos", "UTF-8") })
        mixed.addBodyPart(attachment("informe año.pdf", "application/pdf", pdf))
        mixed.addBodyPart(attachment("notas.txt", "text/plain", "una nota".toByteArray()))
        mixed.addBodyPart(MimeBodyPart().apply { setContent(byteArrayOf(1, 2, 3), "application/octet-stream") })
        message.setContent(mixed)
        message.saveChanges()
        append("INBOX", message)

        client { client ->
            val remote = client.fetchNewest("INBOX", 1).messages.single()
            assertTrue(remote.hasAttachments)
            val body = client.fetchBody("INBOX", remote.uid)
            assertEquals("Ver adjuntos", body.text)
            assertNull(body.html)
            assertEquals(listOf("2", "3", "4"), body.attachments.map { it.partPath })
            val report = body.attachments[0]
            assertEquals("informe año.pdf", report.fileName)
            assertEquals("application/pdf", report.mimeType)
            assertFalse(report.isInline)
            assertTrue(report.size in 30_000L..45_000L)
            assertEquals("notas.txt", body.attachments[1].fileName)
            assertEquals("attachment-4.bin", body.attachments[2].fileName)

            val target = temp.root.resolve("download/informe.pdf")
            client.downloadPart("INBOX", remote.uid, "2", target)
            assertArrayEquals(pdf, target.readBytes())
            assertTrue(target.parentFile!!.listFiles()!!.all { it.name == "informe.pdf" })

            try {
                client.downloadPart("INBOX", remote.uid, "9", temp.root.resolve("x"))
                fail("expected NOT_FOUND")
            } catch (e: MailException) {
                assertEquals(MailErrorKind.NOT_FOUND, e.kind)
            }
        }
    }

    @Test
    fun rawUtf8HeadersAreReadAsUtf8() {
        // Some web forms and bulk mailers put UTF-8 straight into headers instead of encoding it.
        val raw = (
            "From: Ana Pérez <ana@upm.es>\r\nTo: b@upm.es\r\nSubject: Reunión de departamento\r\n" +
                "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n" +
                "--b\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nHola\r\n" +
                "--b\r\nContent-Type: application/pdf\r\nContent-Disposition: attachment; filename=\"Presentación.pdf\"\r\n\r\n" +
                "JVBERi0x\r\n--b--\r\n"
            ).toByteArray(Charsets.UTF_8)
        append("INBOX", jakarta.mail.internet.MimeMessage(session(), java.io.ByteArrayInputStream(raw)))

        client { client ->
            val remote = client.fetchNewest("INBOX", 1).messages.single()
            assertEquals("Reunión de departamento", remote.subject)
            assertEquals("Ana Pérez", remote.from?.name)
            assertEquals(listOf("Presentación.pdf"), client.fetchBody("INBOX", remote.uid).attachments.map { it.fileName })
        }
    }

    @Test
    fun messageWithoutUsableStructureIsParsedFromATemporaryFile() {
        val pdf = Random(5).nextBytes(300_000)
        val message = plainMessage("Sin estructura")
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(MimeBodyPart().apply { setText("Ver adjunto", "UTF-8") })
        mixed.addBodyPart(attachment("informe año.pdf", "application/pdf", pdf))
        message.setContent(mixed)
        message.saveChanges()
        append("INBOX", message)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        fun leftovers() = tempDir.list { _, name -> name.startsWith("upm-mime-") }.orEmpty().toSet()
        val before = leftovers()

        client { client ->
            client.trustServerStructure = false
            val uid = client.fetchNewest("INBOX", 1).messages.single().uid
            val body = client.fetchBody("INBOX", uid)
            assertEquals("Ver adjunto", body.text)
            assertEquals(listOf("2"), body.attachments.map { it.partPath })
            assertEquals("informe año.pdf", body.attachments.single().fileName)

            val target = temp.root.resolve("informe.pdf")
            client.downloadPart("INBOX", uid, "2", target)
            assertArrayEquals(pdf, target.readBytes())
        }
        assertEquals("temporary copies are deleted", before, leftovers())
    }

    @Test
    fun fetchBodyOfRelatedMessageMarksInlineImages() {
        val png = Random(2).nextBytes(2_000)
        val message = plainMessage("Con imagen")
        val related = MimeMultipart("related")
        related.addBodyPart(
            MimeBodyPart().apply { setText("<p>Logo: <img src=\"cid:logo@upm.es\"></p>", "UTF-8", "html") },
        )
        related.addBodyPart(
            MimeBodyPart().apply {
                setContent(png, "image/png")
                setHeader("Content-Transfer-Encoding", "base64")
                contentID = "<logo@upm.es>"
                fileName = "logo.png"
            },
        )
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText("Logo", "UTF-8") })
        alternative.addBodyPart(MimeBodyPart().apply { setContent(related) })
        message.setContent(alternative)
        message.saveChanges()
        append("INBOX", message)

        client { client ->
            val remote = client.fetchNewest("INBOX", 1).messages.single()
            assertFalse(remote.hasAttachments)
            val body = client.fetchBody("INBOX", remote.uid)
            assertTrue(body.html!!.contains("cid:logo@upm.es"))
            assertEquals("Logo", body.text)
            val image = body.attachments.single()
            assertEquals("2.2", image.partPath)
            assertTrue(image.isInline)
            assertEquals("logo@upm.es", image.contentId)
            assertEquals("image/png", image.mimeType)
            val target = temp.root.resolve("logo.png")
            client.downloadPart("INBOX", remote.uid, image.partPath, target)
            assertArrayEquals(png, target.readBytes())
        }
    }

    @Test
    fun fetchBodyListsAttachedMessagesAsEml() {
        val inner = plainMessage("Mensaje original", body = "Contenido reenviado")
        val outer = plainMessage("Fwd: Mensaje original")
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(MimeBodyPart().apply { setText("Te reenvío esto", "UTF-8") })
        mixed.addBodyPart(MimeBodyPart().apply { setContent(inner, "message/rfc822") })
        outer.setContent(mixed)
        outer.saveChanges()
        append("INBOX", outer)

        client { client ->
            val remote = client.fetchNewest("INBOX", 1).messages.single()
            assertTrue(remote.hasAttachments)
            val body = client.fetchBody("INBOX", remote.uid)
            assertEquals("Te reenvío esto", body.text)
            val eml = body.attachments.single()
            assertEquals("Mensaje original.eml", eml.fileName)
            assertEquals("message/rfc822", eml.mimeType)
            val target = temp.root.resolve("original.eml")
            client.downloadPart("INBOX", remote.uid, eml.partPath, target)
            val parsed = jakarta.mail.internet.MimeMessage(session(), target.inputStream())
            assertEquals("Mensaje original", parsed.subject)
        }
    }

    @Test
    fun fetchBodyDecodesLatin1AndEncodedSubjects() {
        val latin1 = "Información del año académico"
        val message = plainMessage("x")
        message.setText(latin1, "ISO-8859-1")
        message.setHeader("Content-Transfer-Encoding", "8bit")
        message.setHeader("Subject", MimeUtility.encodeText("Matrícula 2025/26: último aviso", "ISO-8859-1", "Q"))
        message.saveChanges()
        assertTrue(message.contentType.contains("ISO-8859-1", ignoreCase = true))
        append("INBOX", message)

        client { client ->
            val remote = client.fetchNewest("INBOX", 1).messages.single()
            assertEquals("Matrícula 2025/26: último aviso", remote.subject)
            assertEquals(latin1, client.fetchBody("INBOX", remote.uid).text)
        }
    }

    @Test
    fun fetchBodyOfMissingMessageIsNotFound() {
        seed(1)
        client { client ->
            try {
                client.fetchBody("INBOX", 999)
                fail("expected NOT_FOUND")
            } catch (e: MailException) {
                assertEquals(MailErrorKind.NOT_FOUND, e.kind)
            }
        }
    }

    @Test
    fun operationsAfterCloseReconnect() = runBlocking {
        seed(2)
        val client = newImapClient()
        assertEquals(2, client.fetchNewest("INBOX", 10).messages.size)
        client.close()
        client.close()
        assertEquals(2, client.fetchNewest("INBOX", 10).messages.size)
        client.close()
    }

    @Test
    fun readOnlyChecksKeepMessagesUnread() {
        seed(1)
        client { client ->
            val uid = client.fetchNewest("INBOX", 1).messages.single().uid
            client.fetchBody("INBOX", uid)
            client.fetchMessages("INBOX", listOf(uid))
            client.downloadPart("INBOX", uid, "1", temp.root.resolve("body.txt"))
        }
        withRawStore { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            assertFalse(inbox.messages.single().isSet(Flags.Flag.SEEN))
            inbox.close(false)
        }
    }
}
