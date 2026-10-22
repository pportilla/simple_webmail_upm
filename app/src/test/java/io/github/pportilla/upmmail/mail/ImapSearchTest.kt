package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** The search and index functions of ImapClient against GreenMail. */
class ImapSearchTest : GreenMailTestBase() {

    private fun <T> client(options: ImapOptions = ImapOptions.SEARCH, block: suspend (ImapClient) -> T): T = runBlocking {
        val client = ImapClient(imapSettings(), { password }, options)
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun query(text: String, fields: Set<SearchField> = ServerQuery.HEADER_FIELDS) =
        ServerQuery.parse(text, fields)!!

    /** Removes the first [count] messages of [folder] for good, so uids no longer equal sequence numbers. */
    private fun expungeFirst(folder: String, count: Int) = withRawStore { store ->
        val open = store.getFolder(folder)
        open.open(Folder.READ_WRITE)
        open.setFlags(1, count, Flags(Flags.Flag.DELETED), true)
        open.close(true)
    }

    private fun markDeleted(folder: String, vararg seqs: Int) = withRawStore { store ->
        val open = store.getFolder(folder)
        open.open(Folder.READ_WRITE)
        open.setFlags(seqs, Flags(Flags.Flag.DELETED), true)
        open.close(false)
    }

    /** Twelve messages; after expunging the first two, uids 3..12 sit at sequence numbers 1..10. */
    private fun seedTwelve(): List<Long> {
        val messages = (1..12).map { n ->
            val subject = if (n % 4 == 0) "Elecciones $n" else "Aviso $n"
            plainMessage(subject, body = if (n == 5) "Hablamos de las elecciones" else "Nada $n")
        }
        append("INBOX", *messages.toTypedArray())
        expungeFirst("INBOX", 2)
        return (3L..12L).toList()
    }

    @Test
    fun sequenceWindowSearchesOnlyThoseMessages() = client { client ->
        seedTwelve()
        val probe = client.examine("INBOX")
        assertEquals(10, probe.messageCount)
        assertEquals(13, probe.uidNext)

        // Sequence numbers 4..10 are uids 6..12; subject matches are uids 4, 8 and 12.
        val window = client.searchUids("INBOX", query("elecciones"), SearchRange.Seq(4, 10), 10)
        assertEquals(listOf(12L, 8L), window.uids)
        assertEquals(2, window.totalMatches)
        assertEquals(probe.uidValidity, window.uidValidity)

        val older = client.searchUids("INBOX", query("elecciones"), SearchRange.Seq(1, 3), 10)
        assertEquals(listOf(4L), older.uids)

        val whole = client.searchUids("INBOX", query("elecciones"), null, 2)
        assertEquals(listOf(12L, 8L), whole.uids)
        assertEquals(3, whole.totalMatches)

        // The body match (uid 5) is only found by a text search.
        val text = client.searchUids("INBOX", query("elecciones", ServerQuery.TEXT_FIELDS), null, 10)
        assertEquals(listOf(5L), text.uids)

        val newer = client.searchUids("INBOX", query("elecciones"), SearchRange.UidsFrom(9), 10)
        assertEquals(listOf(12L), newer.uids)
        assertTrue(client.searchUids("INBOX", query("elecciones"), SearchRange.UidsFrom(13), 10).uids.isEmpty())
    }

    @Test
    fun wordsAreAndedAndAccentsMatchBothWays() = client { client ->
        append(
            "INBOX",
            plainMessage("Revisión del acta"),
            plainMessage("Revision of the budget"),
            plainMessage("Acta de la junta"),
        )
        val uids = client.searchAllUids("INBOX").uids.toList()
        assertEquals(3, uids.size)
        assertEquals(listOf(uids[1], uids[0]), client.searchUids("INBOX", query("revision"), null, 10).uids)
        assertEquals(listOf(uids[1], uids[0]), client.searchUids("INBOX", query("revisión"), null, 10).uids)
        assertEquals(listOf(uids[0]), client.searchUids("INBOX", query("revision acta"), null, 10).uids)
        assertEquals(listOf(uids[2], uids[0]), client.searchUids("INBOX", query("acta"), null, 10).uids)
        // GreenMail refuses a phrase with spaces in FROM/TO/CC (real servers take any string).
        assertEquals(listOf(uids[2]), client.searchUids("INBOX", query("\"acta de la\"", setOf(SearchField.SUBJECT)), null, 10).uids)
    }

    /** Uids of the messages of INBOX matching [text], newest first. */
    private suspend fun ImapClient.find(text: String, fields: Set<SearchField> = ServerQuery.HEADER_FIELDS): List<Long> =
        searchUids("INBOX", query(text, fields), null, 50).uids

    private fun localDate(year: Int, month: Int, day: Int, hour: Int, minute: Int): Date =
        Date.from(LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant())

    @Test
    fun peopleAndSubjectAreSearchedInTheirOwnFields() = client { client ->
        // GreenMail compares FROM, TO and CC with whole addresses; real servers match any part of
        // the address or the name (ImapAbortTest shows what is sent for names and accents).
        append(
            "INBOX",
            plainMessage("Acta de la junta", from = "Ana García <agarcia@upm.es>", to = "carlos@upm.es"),
            plainMessage("Convocatoria", body = "Adjunto el acta", from = "Bea Gómez <bea@alumnos.upm.es>", cc = "Carlos Ruiz <cruiz@upm.es>"),
            plainMessage("Re: acta", from = "Jose Garcia <jgarcia@upm.es>", to = "Íñigo Muñoz <imunoz@upm.es>"),
            plainMessage("Revisión de notas", from = "Carlos Ruiz <cruiz@upm.es>"),
        )
        val (u1, u2, u3, u4) = client.searchAllUids("INBOX").uids.toList()

        // Several senders are any of them.
        assertEquals(listOf(u1), client.find("from:agarcia@upm.es"))
        assertEquals(listOf(u3, u1), client.find("de:AGARCIA@upm.es from:jgarcia@upm.es"))
        // Recipients are To or Cc.
        assertEquals(listOf(u2, u1), client.find("to:carlos@upm.es to:cruiz@upm.es"))
        assertEquals("not the sender", listOf(u2), client.find("para:cruiz@upm.es"))

        // Only the subject, also when the words are looked for in the text.
        assertEquals(listOf(u3, u1), client.find("subject:acta"))
        assertEquals(listOf(u2), client.find("acta", ServerQuery.TEXT_FIELDS))
        assertEquals(listOf(u3, u1), client.find("subject:acta", ServerQuery.TEXT_FIELDS))
        assertEquals(listOf(u1), client.find("subject:\"acta de la\""))
        // Spanish accents both ways.
        assertEquals(listOf(u4), client.find("asunto:revision"))
        assertEquals(listOf(u4), client.find("subject:REVISIÓN"))

        // Everything together.
        assertEquals(listOf(u1), client.find("from:agarcia@upm.es to:carlos@upm.es"))
        assertEquals(listOf(u3), client.find("from:agarcia@upm.es from:jgarcia@upm.es subject:acta -junta"))
        assertEquals(listOf(u1), client.find("junta from:agarcia@upm.es from:cruiz@upm.es"))
        assertEquals(emptyList<Long>(), client.find("from:bea@alumnos.upm.es subject:acta"))
    }

    @Test
    fun unreadStarredAndExcludedWords() = client { client ->
        append("INBOX", plainMessage("Elecciones a rector", body = "Votad"), plainMessage("Elecciones: borrador del censo", body = "Revisad"))
        append("INBOX", plainMessage("Elecciones leídas", body = "Censo definitivo"), flags = Flags(Flags.Flag.SEEN))
        append("INBOX", plainMessage("Elecciones destacadas", body = "El borrador"), flags = Flags(Flags.Flag.FLAGGED))
        val (unread, draft, read, starred) = client.searchAllUids("INBOX").uids.toList()

        assertEquals(listOf(starred, draft, unread), client.find("elecciones is:unread"))
        assertEquals(listOf(starred), client.find("es:destacado"))
        assertEquals(listOf(starred), client.find("is:unread is:starred elecciones"))
        // Exclusions: nowhere in the message, the text included, in the headers search too.
        assertEquals(listOf(read, unread), client.find("elecciones -borrador"))
        assertEquals(listOf(read, unread), client.find("elecciones -borrador", ServerQuery.TEXT_FIELDS + ServerQuery.HEADER_FIELDS))
        assertEquals(listOf(read), client.find("censo -borrador", ServerQuery.TEXT_FIELDS))
        assertEquals(listOf(starred, unread), client.find("-censo"))
        // As the header index checks them: in the headers only.
        val headersOnly = client.searchUids("INBOX", query("elecciones -borrador").headerExclusionsOnly(), null, 50).uids
        assertEquals(listOf(starred, read, unread), headersOnly)
    }

    @Test
    fun datesAskForADayMoreOnEachSide() = client { client ->
        // The search keeps the whole local days (ServerSearchTest); the server is asked for more,
        // as it may read the Date header's day in the sender's zone.
        append(
            "INBOX",
            plainMessage("Mayo", date = localDate(2026, 5, 30, 23, 30)),
            plainMessage("Último de mayo", date = localDate(2026, 5, 31, 23, 30)),
            plainMessage("Primero de junio", date = localDate(2026, 6, 1, 0, 10)),
            plainMessage("Fin de junio", date = localDate(2026, 6, 30, 23, 50)),
            plainMessage("Julio", date = localDate(2026, 7, 1, 0, 5)),
            plainMessage("Dos de julio", date = localDate(2026, 7, 2, 0, 5)),
        )
        val uids = client.searchAllUids("INBOX").uids.toList()
        val (may, mayLast, juneFirst, juneLast, july) = uids
        val julySecond = uids[5]

        assertEquals(listOf(july, juneLast, juneFirst, mayLast), client.find("after:2026-06-01 before:2026-06-30"))
        assertEquals(listOf(julySecond, july, juneLast, juneFirst, mayLast), client.find("desde:1/6/2026"))
        assertEquals(listOf(juneFirst, mayLast, may), client.find("hasta:31/5/2026"))
        assertEquals(listOf(juneLast), client.find("junio after:2026-06-03"))
        assertEquals(emptyList<Long>(), client.find("after:2026-07-04"))
    }

    @Test
    fun uidListsAndUndeletedUids() = client { client ->
        val uids = seedTwelve()
        assertEquals(uids, client.searchAllUids("INBOX").uids.toList())
        assertEquals((9L..12L).toList(), client.searchAllUids("INBOX", fromUid = 9).uids.toList())
        assertEquals(0, client.searchAllUids("INBOX", fromUid = 13).uids.size)

        markDeleted("INBOX", 1, 4)
        val undeleted = client.searchUndeletedUids("INBOX")
        assertEquals(uids - setOf(3L, 6L), undeleted.uids.toList())
        assertEquals(10, undeleted.messageCount)
        assertEquals(13, undeleted.uidNext)

        createFolder("Vacia")
        val empty = client.searchUndeletedUids("Vacia")
        assertEquals(0, empty.uids.size)
        assertEquals(0, empty.messageCount)
    }

    @Test
    fun indexHeadersCarryTheEnvelopeAndGuessAttachments() = client { client ->
        val sent = Date(1_700_000_000_000L)
        val plain = plainMessage(
            "Convocatoria de elecciones",
            from = "Bea Gómez <bea@alumnos.upm.es>",
            to = "ana@upm.es, Carlos <carlos@upm.es>",
            cc = "dani@upm.es",
            messageId = "<conv-1@upm.es>",
            date = sent,
        )
        val mixed = plainMessage("Con adjunto").apply {
            setContent(
                MimeMultipart("mixed").apply {
                    addBodyPart(MimeBodyPart().apply { setText("Ver adjunto", "UTF-8") })
                    addBodyPart(attachment("acta.pdf", "application/pdf", ByteArray(2_000) { it.toByte() }))
                },
            )
            saveChanges()
        }
        val alternative = alternativeMessage("Solo texto", "hola", "<p>hola</p>")
        val pdfOnly = plainMessage("Un PDF suelto").apply {
            dataHandler = DataHandler(ByteArrayDataSource(ByteArray(500) { 1 }, "application/pdf"))
            saveChanges()
        }
        append("INBOX", plain, mixed, alternative, pdfOnly)
        append("INBOX", plainMessage("Leído"), flags = Flags(Flags.Flag.SEEN))
        val uids = client.searchAllUids("INBOX").uids.toList()

        val batch = client.fetchIndexHeaders("INBOX", uids.reversed() + 9_999L)
        assertEquals(client.examine("INBOX").uidValidity, batch.uidValidity)
        assertEquals(uids, batch.headers.map { it.uid })
        assertEquals(listOf(9_999L), batch.missingUids)

        val first = batch.headers[0]
        assertEquals("Convocatoria de elecciones", first.subject)
        assertEquals("<conv-1@upm.es>", first.messageId)
        assertEquals(EmailAddress("bea@alumnos.upm.es", "Bea Gómez"), first.from)
        assertEquals(listOf("ana@upm.es", "carlos@upm.es"), first.to.map { it.address })
        assertEquals(listOf("dani@upm.es"), first.cc.map { it.address })
        assertEquals(sent.time, first.sentDate)
        assertNotNull(first.internalDate)
        assertTrue(first.size > 0)
        assertFalse(first.flags.seen)

        assertEquals(listOf(false, true, false, true, false), batch.headers.map { it.likelyHasAttachments })
        assertTrue(batch.headers[4].flags.seen)
    }

    @Test
    fun expungedMessagesAreReportedMissing() = client { client ->
        seedTwelve()
        val batch = client.fetchIndexHeaders("INBOX", listOf(1L, 2L, 3L, 4L))
        assertEquals(listOf(3L, 4L), batch.headers.map { it.uid })
        assertEquals(listOf(1L, 2L), batch.missingUids)
    }

    @Test
    fun flagsOfSparseUidsLeaveOutExpungedOnes() = client { client ->
        seedTwelve()
        markDeleted("INBOX", 2)
        withRawStore { store ->
            val open = store.getFolder("INBOX")
            open.open(Folder.READ_WRITE)
            open.setFlags(intArrayOf(10), Flags(Flags.Flag.FLAGGED), true)
            open.close(false)
        }
        val flags = client.fetchFlagsFor("INBOX", listOf(12L, 1L, 4L, 7L, 999L))
        assertEquals(setOf(4L, 7L, 12L), flags.keys)
        assertTrue("\\Deleted messages are still there", flags.getValue(4L).deleted)
        assertTrue(flags.getValue(12L).flagged)
        assertFalse(flags.getValue(7L).flagged)
        assertTrue(client.fetchFlagsFor("INBOX", emptyList()).isEmpty())
    }

    @Test
    fun keptFolderSeesFlagChangesMadeElsewhere() = client { client ->
        seedTwelve()
        assertFalse(client.fetchFlagsFor("INBOX", listOf(5L)).getValue(5L).seen)
        assertFalse(client.fetchIndexHeaders("INBOX", listOf(5L)).headers.single().flags.seen)
        withRawStore { store ->
            val open = store.getFolder("INBOX")
            open.open(Folder.READ_WRITE)
            open.setFlags(intArrayOf(3), Flags(Flags.Flag.SEEN), true)
            open.close(false)
        }
        assertTrue(client.fetchFlagsFor("INBOX", listOf(5L)).getValue(5L).seen)
        assertTrue(client.fetchIndexHeaders("INBOX", listOf(5L)).headers.single().flags.seen)
    }

    @Test
    fun fetchByUidsReportsTheFolderValidity() = client(ImapOptions.DEFAULT) { client ->
        seedTwelve()
        val status = client.status("INBOX")
        val result = client.fetchByUids("INBOX", listOf(12L, 3L, 1L))
        assertEquals(status.uidValidity, result.uidValidity)
        assertEquals(listOf(3L, 12L), result.messages.map { it.uid })
        assertFalse(result.truncated)
        assertEquals(status.uidValidity, client.fetchByUids("INBOX", emptyList()).uidValidity)
    }

    @Test
    fun derivedClientKeepsWorkingAlongsideTheMainOne() = client(ImapOptions.DEFAULT) { main ->
        seedTwelve()
        val search = main.derive(ImapOptions.SEARCH)
        try {
            assertEquals(listOf(12L, 8L, 4L), search.searchUids("INBOX", query("elecciones"), null, 10).uids)
            assertEquals(10, main.status("INBOX").messageCount)
            assertEquals(10, search.status("INBOX").messageCount)
            assertEquals(listOf(12L, 8L, 4L), search.searchUids("INBOX", query("elecciones"), null, 10).uids)
            // Writes from the main client are seen by the kept folder of the other.
            main.deletePermanently("INBOX", listOf(8L))
            assertEquals(listOf(12L, 4L), search.searchUids("INBOX", query("elecciones"), null, 10).uids)
            assertEquals(9, search.examine("INBOX").messageCount)
        } finally {
            search.close()
        }
    }
}
