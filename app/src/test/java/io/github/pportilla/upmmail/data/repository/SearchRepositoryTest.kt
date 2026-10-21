package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSearchScope
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.IndexBudget
import io.github.pportilla.upmmail.sync.SyncTestBase
import jakarta.mail.Flags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** The search side of [MailRepositoryImpl]: device results from both sources, opening them, the server search. */
class SearchRepositoryTest : SyncTestBase() {

    @After
    fun closeSearches() {
        engine.serverSearch.closeAll()
    }

    private fun repository(keepAlive: Long = 10 * 60_000L): MailRepositoryImpl {
        val scheduler = FlushScheduler(scope, { engine.flushPendingOps(it) }, clock = clock::now)
        return MailRepositoryImpl(store, engine, scheduler, settings, scope, clock = clock::now, detachedKeepAlive = keepAlive)
    }

    private fun date(minutes: Int) = Date(1_700_000_000_000L + minutes * 60_000L)

    /**
     * An INBOX of 60 messages, the newest 50 cached; among the 10 older ones, uid 3 has
     * "elecciones" in its subject (also copied to Archive) and uid 5 only in its text. The newest
     * message mentions it in its subject too. Everything is in the header index.
     */
    private suspend fun indexedInbox(): Long {
        val older = (1..10).map { i ->
            when (i) {
                3 -> message("Elecciones a Junta de Escuela", date = date(i), messageId = "<old-3@test>")
                5 -> message("Convocatoria", body = "Calendario de las elecciones de junio", date = date(i), messageId = "<old-5@test>")
                else -> message("Aviso $i", date = date(i), messageId = "<old-$i@test>")
            }
        }
        val newer = (11..59).map { i -> message("Message $i", date = date(i), messageId = "<new-$i@test>") }
        val newest = message("Elecciones: resultados provisionales", date = date(100), messageId = "<new-60@test>")
        storeOnServer("INBOX", older + newer + newest)
        storeOnServer("Archive", listOf(message("Elecciones a Junta de Escuela", date = date(3), messageId = "<old-3@test>")))
        engine.syncFolderList(account.id)
        val inbox = engine.inboxOf(account.id).id
        engine.syncFolder(inbox)
        engine.headerIndexer.run(IndexBudget.bulk(clock.now(), charging = true))
        return inbox
    }

    /** Waits until [condition] holds (for work running in the background). */
    private suspend fun eventually(message: String, condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(20)
        }
        assertTrue(message, condition())
    }

    @Test
    fun deviceResultsMergeTheCacheAndTheHeaderIndex() = runBlocking {
        val inbox = indexedInbox()
        val mail = repository()
        assertEquals(50, store.messages.count(inbox))

        val hits = mail.searchLocalHits("elecciones", SearchFilters())
        assertEquals("newest first, one per message", listOf(SearchKey(inbox, 60), SearchKey(inbox, 3)), hits.map { it.key })
        val cached = hits[0] as SearchHit.Cached
        assertEquals("Elecciones: resultados provisionales", cached.message.subject)
        val indexed = hits[1] as SearchHit.Indexed
        assertEquals("the INBOX copy is preferred to the Archive one", "Elecciones a Junta de Escuela", indexed.entry.subject)
        assertEquals("bea@alumnos.upm.es", indexed.entry.from?.address)
        assertEquals(0L, indexed.display.id)

        assertEquals(listOf(SearchKey(inbox, 3)), mail.searchLocalHits("junta escuela", SearchFilters(folderId = inbox)).map { it.key })
        assertTrue(mail.searchLocalHits("elecciones", SearchFilters(starredOnly = true)).isEmpty())
        assertEquals(1, mail.searchLocalHits("elecciones", SearchFilters(), limit = 1).size)

        val status = mail.observeIndexStatus().first().single { it.accountId == account.id }
        assertEquals(status.total, status.indexed)
        assertTrue(status.complete)
        assertFalse(status.running)
    }

    @Test
    fun anIndexOnlyResultOpensAsADetachedRowKeptAliveWhileItsThreadIsShown() = runBlocking {
        val inbox = indexedInbox()
        val mail = repository(keepAlive = 50)
        val listed = store.messages.list(inbox, 100, false).map { it.id }
        val hit = mail.searchLocalHits("junta", SearchFilters()).single() as SearchHit.Indexed

        val opened = mail.openSearchHit(hit).getOrThrow() as HitOpenResult.Opened
        assertEquals(3L, store.messages.ref(opened.messageId)!!.uid)
        assertEquals("the folder's list is unchanged", listed, store.messages.list(inbox, 100, false).map { it.id })
        assertEquals(clock.now(), store.messages.extras(inbox).single().detachedAt)
        val again = mail.searchLocalHits("junta", SearchFilters()).single()
        assertEquals("now found in the cache", SearchHit.Cached::class, again::class)
        assertEquals(opened, mail.openSearchHit(again).getOrThrow())

        val thread = mail.observeThread(opened.messageId)
        assertEquals(listOf("Elecciones a Junta de Escuela"), thread.first().map { it.summary.subject })
        clock.advance(20 * 60_000L)
        val shown = launch(Dispatchers.IO) { thread.collect { } }
        eventually("marked as used when shown") { store.messages.extras(inbox).single().detachedAt == clock.now() }
        clock.advance(20 * 60_000L)
        eventually("and again while shown") { store.messages.extras(inbox).single().detachedAt == clock.now() }
        shown.cancelAndJoin()
        val lastUse = clock.now()
        clock.advance(20 * 60_000L)
        delay(200)
        assertEquals("not once the screen is gone", lastUse, store.messages.extras(inbox).single().detachedAt)
    }

    @Test
    fun openingAResultThatChangedSaysWhy() = runBlocking {
        val inbox = indexedInbox()
        val mail = repository()
        val indexed = mail.searchLocalHits("aviso", SearchFilters(folderId = inbox)).first { it.key.uid == 2L } as SearchHit.Indexed
        changeOnServer("INBOX", "Aviso 2") { it.setFlag(Flags.Flag.DELETED, true) }
        assertEquals(HitOpenResult.Gone, mail.openSearchHit(indexed).getOrThrow())
        assertNull(store.messages.findByUid(inbox, 2))

        val newest = mail.searchLocalHits("resultados", SearchFilters()).single() as SearchHit.Cached
        assertEquals(HitOpenResult.Gone, mail.openSearchHit(SearchHit.Cached(newest.message.copy(id = 999_999), null)).getOrThrow())
        // Last: the deletion reaches the server in the background.
        mail.delete(listOf(newest.message.id)).getOrThrow()
        assertEquals("a pending deletion", HitOpenResult.PendingChange, mail.openSearchHit(newest).getOrThrow())
    }

    @Test
    fun theServerSearchFindsAWordOnlyInTheTextOfAMessageNotDownloaded() = runBlocking {
        val inbox = indexedInbox()
        val mail = repository()
        val listed = store.messages.list(inbox, 100, false).map { it.id }
        val request = ServerSearchRequest("elecciones", ServerSearchScope(listOf(account.id)), setOf(SearchPhase.HEADERS, SearchPhase.TEXT))

        val progress = mail.searchServerProgress(request).toList()
        val last = progress.last()
        assertTrue(last.finished)
        assertNull(last.error)
        assertTrue("streamed", progress.size >= 2)
        assertTrue(progress.any { it.phase == SearchPhase.TEXT && it.folder?.id == inbox })
        val keys = last.hits.map { it.key }
        assertTrue("the text-only match is found: $keys", SearchKey(inbox, 5) in keys)
        assertTrue(SearchKey(inbox, 60) in keys)
        assertEquals("the folder's list is unchanged", listed, store.messages.list(inbox, 100, false).map { it.id })
    }

    @Test
    fun indexNowBuildsTheIndexInTheBackground() = runBlocking {
        storeOnServer("INBOX", (1..30).map { i -> message("Aviso $i", date = date(i), messageId = "<a-$i@test>") })
        engine.syncFolderList(account.id)
        val mail = repository()
        mail.startIndexingNow(allowMetered = false)
        mail.startIndexingNow(allowMetered = false)
        eventually("every message indexed") { store.headerIndex.accountRowCount(account.id) == 30 }
        eventually("and the run over") { !engine.headerIndexer.running.value.contains(account.id) }
        val status = mail.observeIndexStatus().first().single { it.accountId == account.id }
        assertEquals(30, status.indexed)
        assertEquals(30, status.total)
    }
}
