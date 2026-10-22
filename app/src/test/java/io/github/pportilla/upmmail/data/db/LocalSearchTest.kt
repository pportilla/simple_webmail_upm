package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The criteria of [SearchQuery] in local search. The same mail is stored once as cached messages
 * ([MessageDao]) and once as header index rows ([HeaderIndexDao]); both must find the same.
 */
class LocalSearchTest {
    private val cached = newStore()
    private val indexed = newStore()
    private var accountId = 0L
    private var inbox = 0L
    private var archive = 0L
    private var sent = 0L
    private var trash = 0L

    private val ana = EmailAddress("ana.garcia@upm.es", "Ana García")
    private val anaTorres = EmailAddress("atorres@upm.es", "Ana Torres")
    private val luis = EmailAddress("luis.perez@upm.es", "Luis Pérez")
    private val marta = EmailAddress("marta@alumnos.upm.es", "Marta López")
    private val carlos = EmailAddress("carlos.nunez@upm.es", "Carlos Núñez")
    private val pedro = EmailAddress("pgarciamartin@upm.es", "Pedro García Martín")
    private val me = EmailAddress("yo@upm.es", "Yo")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Date of each message of [setUp], by uid. */
    private val dates = HashMap<Long, Long>()

    private fun mail(
        uid: Long,
        from: EmailAddress?,
        to: List<EmailAddress>,
        subject: String,
        date: Long,
        cc: List<EmailAddress> = emptyList(),
        seen: Boolean = true,
        flagged: Boolean = false,
        attachments: Boolean = false,
    ): RemoteMessage {
        dates[uid] = date
        return remoteMessage(
            uid, subject = subject, from = from, to = to, cc = cc, date = date, messageId = "<m$uid@upm.es>",
            seen = seen, flagged = flagged, hasAttachments = attachments,
        )
    }

    @Before
    fun setUp() {
        val mail = mapOf(
            FolderRole.INBOX to listOf(
                mail(1, ana, listOf(luis), "Acta de la reunión de departamento", at(2026, 3, 1)),
                mail(2, luis, listOf(ana), "Re: Acta de la reunión", at(2026, 3, 2, 23, 59), cc = listOf(marta)),
                mail(3, marta, listOf(carlos), "Horario de tutorías", at(2026, 3, 3, 0, 0), seen = false),
                mail(4, carlos, listOf(marta), "Presupuesto 2026", at(2026, 3, 4), cc = listOf(ana), flagged = true, attachments = true),
                mail(5, pedro, listOf(luis), "Convocatoria de elecciones", at(2026, 3, 5), seen = false),
            ),
            FolderRole.ARCHIVE to listOf(
                mail(6, ana, listOf(carlos, marta), "Elecciones a la Junta de Escuela", at(2026, 2, 10)),
                mail(7, anaTorres, listOf(luis), "Informe anual", at(2026, 1, 15)),
            ),
            FolderRole.SENT to listOf(mail(8, me, listOf(ana), "Envío del acta", at(2026, 3, 6))),
            FolderRole.TRASH to listOf(mail(9, ana, listOf(luis), "Acta antigua", at(2026, 3, 7))),
        )
        // Both stores are built alike, so their account and folder ids are the same.
        for (store in listOf(cached, indexed)) {
            accountId = store.addTestAccount(email = "yo@upm.es").id
            inbox = store.addTestFolder(accountId, "INBOX", FolderRole.INBOX).id
            archive = store.addTestFolder(accountId, "Archive", FolderRole.ARCHIVE).id
            sent = store.addTestFolder(accountId, "Sent", FolderRole.SENT).id
            trash = store.addTestFolder(accountId, "Trash", FolderRole.TRASH).id
            val folders = mapOf(FolderRole.INBOX to inbox, FolderRole.ARCHIVE to archive, FolderRole.SENT to sent, FolderRole.TRASH to trash)
            for ((role, messages) in mail) {
                val folderId = folders.getValue(role)
                if (store === cached) {
                    store.messages.upsertHeaders(accountId, folderId, messages)
                } else {
                    store.headerIndex.ensureState(folderId, VALIDITY, 100, messages.size)
                    store.headerIndex.insertHeaders(folderId, VALIDITY, messages.map { it.toIndexedHeader() })
                }
            }
        }
    }

    /** Uids found, newest first; the cache and the index must agree. */
    private fun search(query: String, filters: SearchFilters = SearchFilters()): List<Long> {
        val fromCache = cached.messages.searchHits(query, filters, 50).map { it.key.uid }
        val fromIndex = indexed.headerIndex.search(query, filters, 50).map { it.uid }
        assertEquals("cache and index disagree on '$query'", fromCache, fromIndex)
        return fromCache
    }

    @Test
    fun fromMatchesTheSenderOnly() {
        assertEquals("never Ana as a recipient (2, 4, 8)", listOf(1L, 6L, 7L), search("from:ana"))
        assertEquals(listOf(1L, 6L), search("from:ana.garcia@upm.es"))
        assertEquals("a name, accents optional", listOf(1L, 6L), search("from:\"Ana Garcia\""))
        assertEquals(listOf(1L, 6L), search("de:\"ANA GARCÍA\""))
        assertEquals("a surname in a name or an address", listOf(5L, 1L, 6L), search("from:garcía"))
        assertTrue(search("from:garcia@").containsAll(listOf(1L, 6L)))
        assertEquals("part of an address", listOf(1L, 6L), search("from:ana.garcia@"))
        assertEquals(listOf(4L), search("from:nunez"))
        assertEquals("the domain of a student", listOf(3L), search("from:alumnos.upm.es"))
        assertTrue("luis is never the sender of 1", 1L !in search("from:luis"))
    }

    @Test
    fun toMatchesToAndCcOnly() {
        assertEquals("To or Cc, never Ana as the sender (1, 6)", listOf(8L, 4L, 2L), search("to:ana"))
        assertEquals(listOf(8L, 4L, 2L), search("para:ana.garcia@upm.es"))
        assertEquals("Cc too", listOf(4L, 2L, 6L), search("to:marta"))
        assertEquals("accents optional", listOf(3L, 6L), search("to:NÚÑEZ"))
        assertEquals(listOf(5L, 1L, 7L), search("to:Pérez"))
        assertEquals("Sent keeps its recipients", listOf(8L), search("to:ana", SearchFilters(folderId = sent)))
    }

    @Test
    fun severalPeopleMatchAnyOfThemAndCombineWithTheRest() {
        assertEquals(listOf(4L, 1L, 6L, 7L), search("from:ana from:carlos"))
        assertEquals(listOf(4L, 3L, 2L, 6L), search("to:carlos to:marta"))
        assertEquals("from a specific contact to a specific contact", listOf(1L, 7L), search("from:ana to:luis"))
        assertEquals(listOf(1L), search("from:ana.garcia@upm.es to:luis.perez@upm.es"))
        assertEquals(listOf(6L), search("from:ana to:carlos"))
        assertEquals(listOf(1L, 6L, 7L), search("from:ana from:nadie"))
        assertTrue(search("from:carlos to:luis").isEmpty())
    }

    @Test
    fun subjectMatchesTheSubjectOnly() {
        assertEquals(listOf(8L, 2L, 1L), search("subject:acta"))
        assertEquals("all values must be there", listOf(2L, 1L), search("asunto:acta subject:reunion"))
        assertEquals("a phrase", listOf(2L, 1L), search("subject:\"acta de la\""))
        assertTrue("people are not the subject", search("subject:luis").isEmpty())
        assertEquals(listOf(5L, 2L, 1L, 7L), search("luis"))
        assertEquals("words still match anywhere", listOf(2L, 1L), search("acta luis"))
    }

    @Test
    fun excludedWordsDropMatchesAnywhere() {
        assertEquals(listOf(8L), search("acta -reunión"))
        assertEquals("in the people too", listOf(8L, 1L), search("acta -marta"))
        assertEquals("only an exclusion", listOf(8L, 5L, 4L, 3L, 6L, 7L), search("-reunion"))
        assertEquals(listOf(1L, 7L), search("from:ana -junta"))
        assertEquals("several", listOf(8L), search("acta -reunion -departamento"))
        assertEquals("a short excluded word is a whole word: Re, not reunión", listOf(8L, 1L), search("acta -re"))
    }

    @Test
    fun datesAreWholeLocalDaysBothInclusive() {
        assertEquals("23:59 and 00:00", listOf(3L, 2L), search("after:2026-03-02 before:2026-03-03"))
        assertEquals("one day", listOf(2L), search("desde:2/3/2026 hasta:2/3/2026"))
        assertEquals(listOf(6L, 7L), search("before:2026-02-28"))
        assertEquals("Trash is left out", listOf(8L, 5L), search("after:2026-03-05"))
        assertTrue("an empty range", search("after:2026-03-05 before:2026-03-01").isEmpty())
        assertEquals(listOf(6L), search("from:ana subject:elecciones after:2026-01-01"))
    }

    @Test
    fun datesFollowTheGivenZone() {
        val day = LocalDate.of(2026, 3, 3)
        val query = SearchQuery(after = day, before = day)
        for (zone in listOf(ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Etc/GMT+12"), ZoneOffset.UTC)) {
            val expected = dates.filter { (uid, date) -> uid != 9L && Instant.ofEpochMilli(date).atZone(zone).toLocalDate() == day }
                .entries.sortedByDescending { it.value }.map { it.key }
            assertEquals("$zone", expected, cached.messages.searchHits(query, SearchFilters(), 50, zone).map { it.key.uid })
            assertEquals("$zone", expected, indexed.headerIndex.search(query, SearchFilters(), 50, zone).map { it.uid })
        }
    }

    @Test
    fun flagsComeFromTheOperatorsOrTheChips() {
        assertEquals(listOf(5L, 3L), search("is:unread"))
        assertEquals(listOf(4L), search("es:destacado"))
        assertEquals(listOf(4L), search("has:attachment"))
        assertEquals(listOf(4L), search("to:ana is:starred"))
        assertEquals(listOf(3L), search("from:marta", SearchFilters(unreadOnly = true)))
        assertTrue(search("acta", SearchFilters(unreadOnly = true)).isEmpty())
        assertEquals(listOf(4L), search("tiene:adjunto from:carlos", SearchFilters(starredOnly = true)))
        assertEquals(listOf(4L), search("presupuesto", SearchFilters(withAttachments = true)))
    }

    @Test
    fun scopeFiltersStillApply() {
        assertEquals(listOf(6L, 7L), search("from:ana", SearchFilters(folderId = archive)))
        assertEquals("Trash only when asked for", listOf(9L), search("from:ana", SearchFilters(folderId = trash)))
        assertEquals(listOf(1L, 6L, 7L), search("from:ana", SearchFilters(accountId = accountId)))
        assertTrue(search("from:ana", SearchFilters(accountId = accountId + 1)).isEmpty())
    }

    @Test
    fun nothingSearchableFindsNothing() {
        for (query in listOf("", "   ", "from:", "from:@", "***", "\"\"", "subject:-")) {
            assertTrue("'$query'", search(query).isEmpty())
        }
        assertTrue("chips alone are not a search", search("", SearchFilters(unreadOnly = true)).isEmpty())
        assertEquals("a word without letters is ignored next to a criterion", listOf(1L, 6L, 7L), search("from:ana ***"))
    }

    @Test
    fun theCacheAlsoSearchesTheTextButNeverBcc() {
        val bcc = mail(20, me, listOf(luis), "Aviso", at(2026, 3, 8)).copy(bcc = listOf(carlos))
        val id = cached.messages.upsertHeaders(accountId, sent, listOf(bcc)).single()
        indexed.headerIndex.insertHeaders(sent, VALIDITY, listOf(bcc.toIndexedHeader()))
        // Bcc is known for our own mail only, and neither the index nor the server searches it.
        for (query in listOf("to:carlos", "carlos", "aviso to:carlos")) {
            assertTrue(query, cached.messages.searchHits(query, SearchFilters(), 10).none { it.key.uid == 20L })
            assertTrue(query, indexed.headerIndex.search(query, SearchFilters(), 10).none { it.uid == 20L })
        }
        assertEquals(listOf(20L), cached.messages.searchHits("aviso to:luis", SearchFilters(), 10).map { it.key.uid })
        assertTrue(cached.messages.searchHits("from:carlos", SearchFilters(), 10).none { it.key.uid == 20L })

        cached.messages.storeBody(id, null, "Hablamos con Marta del presupuesto", "Hablamos", emptyList(), "Hablamos con Marta del presupuesto")
        assertEquals(listOf(20L, 4L), cached.messages.searchHits("presupuesto", SearchFilters(), 10).map { it.key.uid })
        assertTrue("the text is not the subject", cached.messages.searchHits("subject:hablamos", SearchFilters(), 10).isEmpty())
        assertTrue("nor the people", cached.messages.searchHits("from:marta to:carlos", SearchFilters(), 10).none { it.key.uid == 20L })
        assertEquals("an exclusion looks in the text", listOf(5L), cached.messages.searchHits("to:luis -presupuesto -acta -informe", SearchFilters(), 10).map { it.key.uid })
    }

    @Test
    fun theIndexWaitsForAShortLastWordOrValueBeingTyped() {
        fun indexSearch(query: String) = indexed.headerIndex.search(query, SearchFilters(), 50).map { it.uid }
        assertEquals("'from:ana.garcia' being typed", listOf(1L, 6L, 7L), indexSearch("from:ana.g"))
        assertEquals(listOf(1L, 6L), indexSearch("from:ana.gar"))
        assertEquals(listOf(1L, 6L), cached.messages.searchHits("from:ana.g", SearchFilters(), 50).map { it.key.uid })
        assertEquals("'to:luis' being typed: searched without it", listOf(5L, 1L, 6L), indexSearch("from:garcia to:lu"))
        assertTrue("finished: a space after it", indexSearch("from:garcia to:lu ").isEmpty())
        assertTrue("an only value is never dropped", indexSearch("to:lu").isEmpty())
        assertEquals("a subject word", listOf(8L, 2L, 1L), indexSearch("subject:acta subject:ju"))
        assertEquals("a short word that matches is kept", listOf(6L), indexSearch("elecciones a"))
    }

    @Test
    fun theLimitHoldsAfterEveryFilter() {
        val store = newStore()
        val account = store.addTestAccount().id
        val folder = store.addTestFolder(account, "INBOX", FolderRole.INBOX).id
        store.headerIndex.ensureState(folder, VALIDITY, 1_000, 600)
        val messages = (1L..600L).map { uid ->
            mail(uid, if (uid % 3 == 0L) ana else luis, listOf(marta), "Aviso $uid", 1_700_000_000_000L + uid * 60_000L, seen = uid % 2 == 0L)
        }
        store.headerIndex.insertHeaders(folder, VALIDITY, messages.map { it.toIndexedHeader() })
        // The newest 300 are cached too: the index must look past them all.
        store.messages.upsertHeaders(account, folder, messages.takeLast(300))
        val fromIndex = store.headerIndex.search("from:ana -nada", SearchFilters(unreadOnly = true), 20).map { it.uid }
        val expected = (300L downTo 1L).filter { it % 3 == 0L && it % 2 == 1L }.take(20)
        assertEquals(expected, fromIndex)
        assertEquals(
            "the cache too",
            (600L downTo 301L).filter { it % 3 == 0L }.take(30),
            store.messages.searchHits("from:ana", SearchFilters(), 30).map { it.key.uid },
        )
    }

    @Test
    fun copiesInTheCacheAreSkippedWithoutShorteningTheResults() {
        val store = newStore()
        val account = store.addTestAccount().id
        val messages = (1L..30L).map { uid -> mail(uid, ana, listOf(luis), "Copia $uid", 1_700_000_000_000L + uid * 60_000L) }
        for (name in listOf("INBOX", "Archive", "Projects")) {
            store.messages.upsertHeaders(account, store.addTestFolder(account, name).id, messages)
        }
        val hits = store.messages.searchHits("from:ana", SearchFilters(), 12)
        assertEquals((30L downTo 19L).toList(), hits.map { it.key.uid })
        assertEquals(12, hits.map { it.messageIdHeader }.distinct().size)
    }

    @Test
    fun answersFullyOnlyWhatACompleteIndexAnswersExactly() {
        for (query in listOf(
            "elecciones", "junta electoral", "from:ana", "to:luis.perez@upm.es", "from:ana.garcia@upm.es to:garcia@",
            "subject:\"acta de la junta\"", "after:2026-01-01", "from:ana before:2026-03-01", "\"acta de la reunión\"",
        )) {
            assertTrue(query, HeaderIndexDao.answersFully(query))
        }
        for (query in listOf(
            "", "cv", "elecciones cv", "from:jo", "to:ana.garcia@upm.e", "subject:\"junta de\"", "acta -borrador",
            "from:ana is:unread", "from:ana is:starred", "from:ana has:attachment", "***", "from:@",
        )) {
            assertFalse(query, HeaderIndexDao.answersFully(query))
        }
    }

    private companion object {
        const val VALIDITY = 7L
    }
}
