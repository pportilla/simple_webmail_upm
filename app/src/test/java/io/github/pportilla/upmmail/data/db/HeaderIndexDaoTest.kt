package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.IndexedHeader
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class HeaderIndexDaoTest {
    private lateinit var store: MailStore
    private val index get() = store.headerIndex
    private var accountId = 0L
    private var inbox = 0L
    private var archive = 0L
    private var sent = 0L
    private var trash = 0L

    @Before
    fun setUp() {
        store = newStore()
        accountId = store.addTestAccount().id
        inbox = store.addTestFolder(accountId, "INBOX", FolderRole.INBOX).id
        archive = store.addTestFolder(accountId, "Archive", FolderRole.ARCHIVE).id
        sent = store.addTestFolder(accountId, "Sent", FolderRole.SENT).id
        trash = store.addTestFolder(accountId, "Trash", FolderRole.TRASH).id
        for (folder in listOf(inbox, archive, sent, trash)) index.ensureState(folder, VALIDITY, 10_000, 100)
    }

    private fun header(
        uid: Long,
        subject: String = "Asunto $uid",
        from: EmailAddress? = EmailAddress("bea@alumnos.upm.es", "Bea Gómez"),
        to: List<EmailAddress> = listOf(EmailAddress("ana@upm.es", "Ana Pérez")),
        messageId: String? = "<m$uid@example.org>",
        flags: RemoteFlags = RemoteFlags(),
        attachments: Boolean = false,
    ): IndexedHeader = remoteMessage(uid, subject = subject, from = from, to = to, messageId = messageId)
        .toIndexedHeader()
        .copy(flags = flags, likelyHasAttachments = attachments)

    private fun search(query: String, filters: SearchFilters = SearchFilters(), limit: Int = 50): List<Long> =
        index.search(query, filters, limit).map { it.uid }

    @Test
    fun insertsDeduplicateAndUpdateFlagsOfKnownRows() {
        assertEquals(2, index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2))))
        assertEquals(1, index.insertHeaders(inbox, VALIDITY, listOf(header(1, flags = RemoteFlags(seen = true, flagged = true)), header(3))))
        val rows = index.findByUids(inbox, listOf(1L, 2L, 3L)).associateBy { it.uid }
        assertEquals(3, rows.size)
        assertTrue(rows.getValue(1).isRead)
        assertTrue(rows.getValue(1).isStarred)
        assertFalse(rows.getValue(2).isRead)
        assertEquals(3, index.state(inbox)!!.indexedCount)
        assertEquals("one full-text entry per row", 3L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
    }

    @Test
    fun deletedMessagesLoseTheirRow() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2)))
        assertEquals(0, index.insertHeaders(inbox, VALIDITY, listOf(header(1, flags = RemoteFlags(deleted = true)), header(9, flags = RemoteFlags(deleted = true)))))
        assertEquals(listOf(2L), index.indexedUids(inbox).toList())
        assertEquals(1, index.state(inbox)!!.indexedCount)
        assertEquals(1L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
    }

    @Test
    fun recipientsAreKeptForSentOnlyButAlwaysSearchable() {
        val carlos = EmailAddress("carlos@upm.es", "Carlos Núñez")
        index.insertHeaders(sent, VALIDITY, listOf(header(1, to = listOf(carlos))))
        index.insertHeaders(inbox, VALIDITY, listOf(header(1, to = listOf(carlos))))
        assertEquals(listOf(carlos), index.findByUids(sent, listOf(1L)).single().to)
        assertTrue(index.findByUids(inbox, listOf(1L)).single().to.isEmpty())
        assertEquals("people are folded", 2, index.search("nunez", SearchFilters(), 10).size)
        assertEquals(2, index.search("bea gomez", SearchFilters(), 10).size)
        assertEquals(EmailAddress("bea@alumnos.upm.es", "Bea Gómez"), index.findByUids(inbox, listOf(1L)).single().from)
    }

    @Test
    fun everyoneOnALongCcListIsSearchable() {
        val cc = (1..40).map { EmailAddress("persona$it@upm.es", "Persona $it") } + EmailAddress("zcast@upm.es", "Zoe Castañeda")
        index.insertHeaders(inbox, VALIDITY, listOf(header(1).copy(cc = cc)))
        assertEquals(listOf(1L), search("castaneda"))
        assertEquals(listOf(1L), search("zcast"))
        assertEquals(listOf(1L), search("persona37"))
    }

    @Test
    fun staleBatchesAreDroppedWithoutResettingTheFolder() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1)))
        assertEquals(-1, index.insertHeaders(inbox, VALIDITY + 1, listOf(header(2))))
        assertEquals(listOf(1L), index.indexedUids(inbox).toList())
        assertEquals(VALIDITY, index.state(inbox)!!.uidValidity)

        val never = store.addTestFolder(accountId, "Never", FolderRole.OTHER).id
        assertEquals("no state row: never indexed", -1, index.insertHeaders(never, VALIDITY, listOf(header(1))))
        assertEquals(-1, index.insertHeaders(999, VALIDITY, listOf(header(1))))
        assertEquals(-1, index.upsertFromSync(never, VALIDITY, listOf(remoteMessage(1))))
    }

    @Test
    fun ensureStateKeepsTheRowUntilTheValidityChanges() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2)))
        index.updateState(inbox, uidNext = 20, complete = true)
        val kept = index.ensureState(inbox, VALIDITY, 5_000, 7)!!
        assertEquals("an existing row keeps its own uid_next", 20L, kept.uidNext)
        assertTrue(kept.complete)

        val reset = index.ensureState(inbox, VALIDITY + 1, 30, 7)!!
        assertEquals(VALIDITY + 1, reset.uidValidity)
        assertEquals(30L, reset.uidNext)
        assertEquals(7, reset.serverCount)
        assertFalse(reset.complete)
        assertEquals(0, reset.indexedCount)
        assertTrue(index.indexedUids(inbox).isEmpty())
        assertEquals(0L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertNull("the folder is gone", index.ensureState(999, VALIDITY, 1, 1))
    }

    @Test
    fun folderAndAccountRemovalEmptyTheFullTextTable() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2)))
        index.insertHeaders(archive, VALIDITY, listOf(header(3)))
        store.folders.delete(inbox)
        assertEquals(1L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertNull(index.state(inbox))
        store.accounts.delete(accountId)
        assertEquals(0L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertEquals(0L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_state"))
    }

    @Test
    fun searchFoldsAccentsAndAndsWords() {
        index.insertHeaders(
            inbox,
            VALIDITY,
            listOf(
                header(1, subject = "Elecciones a la Junta de Escuela"),
                header(2, subject = "Revisión de exámenes"),
                header(3, subject = "Junta electoral: calendario"),
            ),
        )
        assertEquals(listOf(1L), search("ELECCIÓN"))
        assertEquals(listOf(2L), search("revision"))
        assertEquals(listOf(3L, 1L), search("junta"))
        assertEquals(listOf(3L), search("junta electoral"))
        assertEquals("prefixes from three letters", listOf(3L, 1L), search("ele"))
        assertTrue(search("junta exámenes").isEmpty())
    }

    @Test
    fun shortWordsMatchWholeWordsOnly() {
        index.insertHeaders(
            inbox,
            VALIDITY,
            listOf(header(1, subject = "Acta de la reunión"), header(2, subject = "Delegación de alumnos"), header(3, subject = "Examen de SO")),
        )
        assertEquals("a short word must be a whole word", listOf(1L), search("acta de"))
        assertEquals(listOf(2L), search("delega de"))
        assertEquals("an acronym on its own", listOf(3L), search("SO"))
        assertEquals("only short words", listOf(3L, 2L, 1L), search("de"))
        assertEquals(listOf(1L), search("de la"))
        assertTrue("two letters are not a prefix", search("ac").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun aShortLastWordStillBeingTypedDoesNotHideTheMatches() {
        index.insertHeaders(
            inbox,
            VALIDITY,
            listOf(header(1, subject = "Elecciones a la junta"), header(2, subject = "Elecciones de delegados"), header(3, subject = "Examen de SO")),
        )
        assertEquals("'junta' is being typed", listOf(2L, 1L), search("elecciones j"))
        assertEquals(listOf(2L, 1L), search("elecciones ju"))
        assertEquals(listOf(1L), search("elecciones jun"))
        assertEquals("a short word that matches is kept", listOf(2L), search("elecciones de"))
        assertEquals(listOf(3L), search("examen so"))
        assertTrue("a word followed by a space is finished", search("elecciones j ").isEmpty())
        assertTrue("an only word is never dropped", search("j").isEmpty())
    }

    @Test
    fun searchHonoursScopeAndFilters() {
        val other = store.addTestAccount(email = "luis@upm.es").id
        val otherInbox = store.addTestFolder(other, "INBOX", FolderRole.INBOX).id
        index.ensureState(otherInbox, VALIDITY, 100, 1)
        index.insertHeaders(inbox, VALIDITY, listOf(header(1, subject = "convocatoria"), header(2, subject = "convocatoria", flags = RemoteFlags(seen = true))))
        index.insertHeaders(archive, VALIDITY, listOf(header(3, subject = "convocatoria", flags = RemoteFlags(flagged = true), attachments = true)))
        index.insertHeaders(trash, VALIDITY, listOf(header(4, subject = "convocatoria")))
        index.insertHeaders(otherInbox, VALIDITY, listOf(header(5, subject = "convocatoria")))

        assertEquals("trash is left out", listOf(5L, 3L, 2L, 1L), search("convocatoria"))
        assertEquals(listOf(4L), search("convocatoria", SearchFilters(folderId = trash)))
        assertEquals(listOf(3L, 2L, 1L), search("convocatoria", SearchFilters(accountId = accountId)))
        assertEquals(listOf(5L, 3L, 1L), search("convocatoria", SearchFilters(unreadOnly = true)))
        assertEquals(listOf(3L), search("convocatoria", SearchFilters(starredOnly = true)))
        assertEquals(listOf(3L), search("convocatoria", SearchFilters(withAttachments = true)))
        val hit = index.search("convocatoria", SearchFilters(folderId = archive), 1).single()
        assertEquals(FolderRole.ARCHIVE, hit.folderRole)
        assertEquals(accountId, hit.accountId)
        assertEquals("<m3@example.org>", hit.messageIdHeader)
    }

    @Test
    fun cachedMessagesAreLeftOutEvenWhenHiddenAndTheLimitHoldsAfterThat() {
        index.insertHeaders(inbox, VALIDITY, (1L..6L).map { header(it, subject = "presupuesto $it") })
        val ids = store.messages.upsertHeaders(accountId, inbox, listOf(remoteMessage(6), remoteMessage(5)))
        store.messages.setHidden(listOf(ids[1]), true)
        assertEquals("uid 6 is cached, uid 5 is being moved", listOf(4L, 3L), search("presupuesto", limit = 2))
        assertEquals(listOf(4L, 3L, 2L, 1L), search("presupuesto"))
    }

    @Test
    fun manyCandidatesAreServedNewestFirst() {
        val headers = (1L..2_500L).map { uid ->
            // Dates out of uid order, so the date index decides the order.
            val date = 1_700_000_000_000L + ((uid * 7_919) % 2_500) * 60_000L
            header(uid, subject = "boletín $uid").copy(sentDate = date, internalDate = date)
        }
        index.insertHeaders(archive, VALIDITY, headers)
        val expected = headers.sortedByDescending { it.sentDate }.take(30).map { it.uid }
        assertEquals(expected, search("boletin", limit = 30))
        assertEquals(expected.take(5), search("bol", limit = 5))
        assertEquals(30, index.search("boletin", SearchFilters(unreadOnly = true), 30).size)
    }

    @Test
    fun applyDiffDeletesWhatTheServerLostAndEnforcesTheCap() {
        index.insertHeaders(inbox, VALIDITY, (1L..10L).map { header(it) } + header(15))
        // The server has 2..9 (1 and 10 expunged); 15 arrived after the UIDNEXT seen (12).
        val result = index.applyDiff(inbox, longArrayOf(2, 3, 4, 5, 6, 7, 8, 9), below = 12, keepNewest = 5)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 15L), result.remaining.toList())
        assertEquals(5, result.removed)
        assertEquals(result.remaining.toList(), index.indexedUids(inbox).toList())
        assertEquals(6, index.state(inbox)!!.indexedCount)
        assertEquals(listOf(9L, 15L), index.indexedUids(inbox, fromUid = 9).toList())

        val unsorted = index.applyDiff(inbox, longArrayOf(9, 5, 7, 6, 8), below = 12, keepNewest = 100)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 15L), unsorted.remaining.toList())
        assertEquals(0, unsorted.removed)
    }

    @Test
    fun rowDeletionHelpers() {
        index.insertHeaders(inbox, VALIDITY, (1L..6L).map { header(it) })
        assertEquals(2, index.deleteMissingInRange(inbox, 2L..5L, setOf(2L, 4L)))
        assertEquals(listOf(1L, 2L, 4L, 6L), index.indexedUids(inbox).toList())
        assertEquals(0, index.deleteMissingInRange(inbox, 5L..4L, emptySet()))
        assertEquals(2, index.deleteByMessageIds(inbox, listOf("<M1@EXAMPLE.ORG>", " <m4@example.org> ", "<nope@x>")))
        assertEquals(1, index.deleteUids(inbox, listOf(6L, 99L)))
        assertEquals(listOf(2L), index.indexedUids(inbox).toList())
        assertEquals(1, index.state(inbox)!!.indexedCount)
        assertEquals(2L, index.maxUid(inbox))
        assertEquals(1_700_000_000_000L + 2 * 60_000L, index.oldestDate(inbox))
        assertNull(index.maxUid(archive))
    }

    @Test
    fun clearAndResetFolder() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1)))
        index.updateState(inbox, capped = true)
        index.clearRows(inbox)
        val cleared = index.state(inbox)!!
        assertTrue(cleared.complete)
        assertFalse(cleared.capped)
        assertEquals(0, cleared.indexedCount)
        index.resetFolder(inbox)
        assertNull(index.state(inbox))
    }

    @Test
    fun flagsFollowServerAndLocalChanges() {
        index.insertHeaders(inbox, VALIDITY, (1L..4L).map { header(it) })
        index.updateFlags(inbox, mapOf(1L to RemoteFlags(seen = true, answered = true), 2L to RemoteFlags(deleted = true)))
        index.setFlag(inbox, listOf(3L), LocalFlag.STARRED, true)
        index.setFlag(inbox, listOf(3L), LocalFlag.FORWARDED, true)
        val rows = index.findByUids(inbox, (1L..4L).toList()).associateBy { it.uid }
        assertTrue(rows.getValue(1).isRead && rows.getValue(1).isAnswered)
        assertNull("deleted on the server", rows[2])
        assertTrue(rows.getValue(3).isStarred)
        index.markFolderRead(inbox)
        assertTrue(index.findByUids(inbox, (1L..4L).toList()).all { it.isRead })
    }

    @Test
    fun markDirtyTouchesOnlyIndexedFolders() {
        val never = store.addTestFolder(accountId, "Never", FolderRole.OTHER).id
        index.markDirty(listOf(sent, never))
        assertEquals(listOf(sent), index.dirtyFolders())
        assertEquals(listOf(sent), index.dirtyFolders(accountId))
        assertNull(index.state(never))
        assertEquals(setOf(sent), runBlocking { index.observeDirty().first() })
        index.updateState(sent, dirty = false)
        assertTrue(index.dirtyFolders().isEmpty())
    }

    @Test
    fun statusCountsIndexableFoldersCapsTotalsAndNeedsEveryFolderComplete() {
        val drafts = store.addTestFolder(accountId, "Drafts", FolderRole.DRAFTS).id
        store.folders.updateAfterSync(drafts, 1, 0, 40)
        val noState = store.addTestFolder(accountId, "Projects", FolderRole.OTHER).id
        store.folders.updateAfterSync(noState, 1, 0, 30)
        index.updateState(inbox, serverCount = 120)
        index.updateState(trash, serverCount = 5_000)
        index.updateState(archive, serverCount = 60_000)
        index.updateState(sent, serverCount = 10)
        index.insertHeaders(inbox, VALIDITY, (1L..3L).map { header(it) })

        val status = index.status().single()
        assertEquals(accountId, status.accountId)
        assertEquals("drafts left out, trash and archive capped", 120 + 2_000 + 50_000 + 10 + 30, status.total)
        assertEquals(3, status.indexed)
        assertFalse(status.complete)

        store.folders.delete(noState)
        for (folder in listOf(inbox, archive, sent, trash)) index.updateState(folder, complete = true)
        assertTrue(index.status().single().complete)
        val newcomer = store.addTestAccount(email = "nuevo@upm.es").id
        assertEquals("folders not listed yet", IndexStatus(newcomer, 0, 0, false), index.status().single { it.accountId == newcomer })
    }

    @Test
    fun observeStatusFollowsIndexChanges() = runBlocking {
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.IO) { index.observeStatus().collect { seen += it.single().indexed } }
        waitFor { seen.isNotEmpty() }
        index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2)))
        waitFor { seen.lastOrNull() == 2 }
        job.cancel()
        assertEquals(0, seen.first())
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            delay(10)
        }
    }

    @Test
    fun accountRowCountSumsTheFolders() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1), header(2)))
        index.insertHeaders(sent, VALIDITY, listOf(header(1)))
        assertEquals(3, index.accountRowCount(accountId))
        assertEquals(0, index.accountRowCount(accountId + 100))
        assertEquals(2, index.statesOf(accountId).count { it.indexedCount > 0 })
    }

    @Test
    fun lookups() {
        index.insertHeaders(inbox, VALIDITY, listOf(header(1, messageId = "<Same@X>")))
        index.insertHeaders(archive, VALIDITY, listOf(header(7, messageId = "<same@x>")))
        val copies = index.findByMessageId(accountId, "<SAME@x>")
        assertEquals(setOf(inbox, archive), copies.map { it.folderId }.toSet())
        val first = copies.first()
        assertEquals(first, index.get(first.indexId))
        assertNull(index.get(9_999))
        assertTrue(index.findByUids(inbox, emptyList()).isEmpty())
        index.mergeSegments()
        assertEquals(2, index.search("asunto", SearchFilters(), 10).size)
    }

    @Test
    fun capsByRole() {
        assertEquals(HeaderIndexDao.BIN_CAP, HeaderIndexDao.capOf(FolderRole.TRASH))
        assertEquals(HeaderIndexDao.BIN_CAP, HeaderIndexDao.capOf(FolderRole.JUNK))
        assertEquals(HeaderIndexDao.FOLDER_CAP, HeaderIndexDao.capOf(FolderRole.INBOX))
        assertNotNull(index.state(inbox))
    }

    /** About the size of a big UPM mailbox; device timings are checked by hand (< 300 ms). */
    @Test
    fun searchStaysFastWithFiftyThousandRows() {
        val words = listOf(
            "reunión", "acta", "convocatoria", "departamento", "examen", "horario", "proyecto", "beca", "tesis",
            "seminario", "matrícula", "presupuesto", "informe", "plazo", "curso", "máster", "grado", "factura",
        )
        val people = listOf("Ana Pérez", "Luis García", "Elena Martín", "José Núñez", "Marta López", "Pablo Ruiz")
        val random = Random(7)
        val headers = (1L..50_000L).map { uid ->
            val subject = buildString {
                append(words[random.nextInt(words.size)]).append(" de ").append(words[random.nextInt(words.size)])
                when (random.nextInt(100)) {
                    in 0..1 -> append(" elecciones a rector")
                    2 -> append(" junta electoral")
                }
                append(' ').append(uid)
            }
            fun person(): EmailAddress = people[random.nextInt(people.size)].let { name ->
                EmailAddress(SearchText.fold(name.lowercase().replace(' ', '.')) + "@upm.es", name)
            }
            val flags = RemoteFlags(seen = random.nextInt(10) > 0, flagged = random.nextInt(200) == 0)
            val to = List(1 + random.nextInt(2)) { person() }
            header(uid, subject = subject, from = person(), to = to, messageId = "<b$uid@upm.es>", flags = flags, attachments = random.nextInt(1_000) == 0)
        }
        headers.chunked(1_000).forEach { assertTrue(index.insertHeaders(archive, VALIDITY, it) > 0) }
        index.mergeSegments()

        val queries = listOf(
            "elecciones", "ele", "upm", "junta electoral", "de", "elecciones a",
            // Criteria without words: the newest matches first, fast however many there are.
            "from:ana", "to:luis", "from:ana to:luis", "from:ana.perez@upm.es", "subject:elecciones", "is:starred",
            "has:attachment", "is:unread from:marta", "after:2023-11-15 before:2023-11-20", "-reunion", "from:garcia -acta",
            "from:\"jose nunez\" from:pablo subject:tesis", "elecciones to:elena -junta is:unread",
        )
        for (query in queries) {
            index.search(query, SearchFilters(), 200)
            val best = (1..3).minOf {
                val start = System.nanoTime()
                val hits = index.search(query, SearchFilters(), 200)
                assertTrue("$query finds something", hits.isNotEmpty())
                (System.nanoTime() - start) / 1_000_000
            }
            println("Search of 50,000 index rows for '$query': $best ms")
            assertTrue("'$query' took $best ms", best < 150)
        }
        val newest = index.search("from:ana", SearchFilters(), 200)
        assertEquals(200, newest.size)
        assertEquals(headers.filter { it.from?.name == "Ana Pérez" }.sortedByDescending { it.sentDate }.take(200).map { it.uid }, newest.map { it.uid })
    }

    private companion object {
        const val VALIDITY = 42L
    }
}
