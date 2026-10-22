package io.github.pportilla.upmmail.ui.search

import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.IndexedMessage
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSearchScope
import io.github.pportilla.upmmail.model.StepOutcome
import io.github.pportilla.upmmail.model.StepProblem
import io.github.pportilla.upmmail.sync.ServerSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SearchLogicTest {

    private fun message(id: Long, read: Boolean = true, starred: Boolean = false, attachments: Boolean = false, folderId: Long = 1) =
        MessageSummary(
            id = id,
            accountId = 1,
            folderId = folderId,
            uid = id,
            threadId = "t$id",
            subject = "Subject $id",
            from = EmailAddress("a@upm.es"),
            to = emptyList(),
            date = id,
            snippet = "",
            isRead = read,
            isStarred = starred,
            isAnswered = false,
            isForwarded = false,
            isDraft = false,
            hasAttachments = attachments,
        )

    /** A cached hit; its Message-ID is "<m[id]@upm.es>" unless given. */
    private fun cached(id: Long, read: Boolean = true, folderId: Long = 1, mid: String? = "<m$id@upm.es>") =
        SearchHit.Cached(message(id, read = read, folderId = folderId), mid)

    private fun indexed(uid: Long, folderId: Long = 2, read: Boolean = true, mid: String? = "<m$uid@upm.es>") =
        SearchHit.Indexed(
            IndexedMessage(
                indexId = uid + 1000,
                accountId = 1,
                folderId = folderId,
                folderRole = FolderRole.ARCHIVE,
                uid = uid,
                messageIdHeader = mid,
                subject = "Archived $uid",
                from = EmailAddress("b@upm.es"),
                to = emptyList(),
                date = uid,
                size = 1000,
                isRead = read,
                isStarred = false,
                isAnswered = false,
                hasAttachments = false,
            ),
        )

    private fun folder(id: Long, role: FolderRole = FolderRole.OTHER) =
        Folder(id, 1, "F$id", "F$id", role, 0, 0, 100, true, null)

    private fun problem(folderId: Long, phase: SearchPhase, outcome: StepOutcome, error: MailException? = null) =
        StepProblem(folder(folderId), phase, outcome, 1, 2000, error)

    private val scope = ServerSearchScope(listOf(1L))

    private fun progress(
        request: ServerSearchRequest,
        hits: List<SearchHit> = emptyList(),
        problems: List<StepProblem> = emptyList(),
        error: MailException? = null,
        truncated: Boolean = false,
    ) = ServerSearchProgress(request, null, null, 0, 0, hits, problems, finished = true, truncated = truncated, error = error)

    private fun session(local: List<SearchHit> = emptyList()) = ServerSession.start(1, "elecciones", scope, local)

    @Test
    fun filtersMapToRepositoryFilters() {
        val all = toSearchFilters(SearchFilterState(unreadOnly = true, thisFolderOnly = false), accountId = 3, folderId = 7)
        assertEquals(SearchFilters(accountId = 3, folderId = null, unreadOnly = true), all)
        val folder = toSearchFilters(SearchFilterState(starredOnly = true, withAttachments = true, thisFolderOnly = true), null, 7)
        assertEquals(SearchFilters(accountId = null, folderId = 7, starredOnly = true, withAttachments = true), folder)
    }

    @Test
    fun flagFiltersApplyToBothKindsOfHits() {
        val unreadStarred = SearchHit.Cached(message(1, read = false, starred = true), null)
        assertTrue(matchesFilters(unreadStarred, SearchFilterState(unreadOnly = true, starredOnly = true)))
        assertFalse(matchesFilters(unreadStarred, SearchFilterState(withAttachments = true)))
        assertTrue(matchesFilters(indexed(2), SearchFilterState()))
        assertFalse(matchesFilters(indexed(2), SearchFilterState(unreadOnly = true)))
        assertTrue(matchesFilters(indexed(3, read = false), SearchFilterState(unreadOnly = true)))
    }

    @Test
    fun serverHitsAlreadyShownLocallyStayInTheLocalSection() {
        val local = listOf(cached(1), cached(2))
        // 2 again; 9 is another copy (same Message-ID) of local message 1; 3 and 4 are new.
        val server = listOf(cached(2), indexed(9, mid = "<M1@UPM.ES>"), indexed(3), indexed(4))
        val sections = mergeSections(local, server, local.mapTo(HashSet()) { it.key }, setOf("<m1@upm.es>", "<m2@upm.es>"), SearchFilterState())
        assertEquals(listOf(SearchKey(1, 1), SearchKey(1, 2)), sections.local.map { it.key })
        assertEquals(listOf(SearchKey(2, 3), SearchKey(2, 4)), sections.fromServer.map { it.key })
        assertEquals(2, sections.serverOnly)
    }

    @Test
    fun serverMatchesStayInTheirSectionWithTheFresherLocalCopy() {
        // After the server search, index-only match 3 was opened (now cached as message 30) and the
        // re-run local search finds it, read; it stays under "From server" as the cached copy.
        val opened = SearchHit.Cached(message(30, read = true, folderId = 2).copy(uid = 3), "<m3@upm.es>")
        val local = listOf(cached(1), opened)
        val server = listOf(indexed(3, read = false), indexed(4))
        val sections = mergeSections(local, server, setOf(SearchKey(1, 1)), setOf("<m1@upm.es>"), SearchFilterState())
        assertEquals(listOf(SearchKey(1, 1)), sections.local.map { it.key })
        assertEquals(listOf<SearchHit>(opened, indexed(4)), sections.fromServer)
    }

    @Test
    fun aCachedServerHitIsNotReplacedByAnIndexOnlyLocalOne() {
        val server = listOf(cached(5, folderId = 2).let { SearchHit.Cached(it.message.copy(uid = 7), it.messageIdHeader) })
        val local = listOf(indexed(7))
        val sections = mergeSections(local, server, emptySet(), emptySet(), SearchFilterState())
        assertTrue(sections.fromServer.single() is SearchHit.Cached)
        assertTrue(sections.local.isEmpty())
    }

    @Test
    fun localCopiesOfServerSectionMessagesAreNotShownTwice() {
        // A sync cached a copy of server match 4 in the INBOX: shown once, in the server section.
        val local = listOf(cached(40, mid = "<m4@upm.es>"), cached(1))
        val sections = mergeSections(local, listOf(indexed(4)), setOf(SearchKey(1, 1)), emptySet(), SearchFilterState())
        assertEquals(listOf(SearchKey(1, 1)), sections.local.map { it.key })
        assertEquals(listOf(SearchKey(2, 4)), sections.fromServer.map { it.key })
    }

    @Test
    fun flagFiltersApplyToBothSectionsButNotToTheServerOnlyCount() {
        val local = listOf(cached(1, read = false), cached(2, read = true))
        val server = listOf(indexed(5, read = false), indexed(6, read = true), indexed(7, read = true))
        val sections = mergeSections(local, server, emptySet(), emptySet(), SearchFilterState(unreadOnly = true))
        assertEquals(listOf(SearchKey(1, 1)), sections.local.map { it.key })
        assertEquals(listOf(SearchKey(2, 5)), sections.fromServer.map { it.key })
        assertEquals(3, sections.serverOnly)
        assertTrue(flagsApproximate(SearchFilterState(unreadOnly = true), sections.local + sections.fromServer))
        assertFalse(flagsApproximate(SearchFilterState(unreadOnly = true), sections.local))
        assertFalse(flagsApproximate(SearchFilterState(withAttachments = true), sections.fromServer))
    }

    @Test
    fun deviceResultsTheServerRuledOutAreLeftOut() {
        // "-borrador": index-only 3 and cached 2 (text not downloaded) have it in their text.
        val local = listOf(indexed(3), cached(2), cached(1))
        val shown = local.mapTo(HashSet()) { it.key }
        val refuted = setOf(SearchKey(2, 3), SearchKey(1, 2))
        val sections = mergeSections(local, listOf(indexed(4)), shown, emptySet(), SearchFilterState(), refuted = refuted)
        assertEquals(listOf(SearchKey(1, 1)), sections.local.map { it.key })
        assertEquals(listOf(SearchKey(2, 4)), sections.fromServer.map { it.key })
    }

    @Test
    fun ruledOutResultsAddUpOverTheRuns() {
        val running = session().request(setOf(SearchPhase.HEADERS)).request(setOf(SearchPhase.TEXT))
        val headers = running.queue.first()
        val during = running.onProgress(progress(headers).copy(finished = false, refuted = setOf(SearchKey(2, 3))))
        assertEquals("the running search's count at once", setOf(SearchKey(2, 3)), during.allRefuted)
        val headersDone = progress(headers, hits = listOf(indexed(4))).copy(refuted = setOf(SearchKey(2, 3), SearchKey(2, 5)))
        val afterHeaders = during.onFinished(headers, headersDone)
        assertEquals(setOf(SearchKey(2, 3), SearchKey(2, 5)), afterHeaders.refuted)
        val text = afterHeaders.queue.single()
        val afterText = afterHeaders.onFinished(text, progress(text, hits = listOf(indexed(6))))
        assertEquals("kept by a run that rules nothing out", setOf(SearchKey(2, 3), SearchKey(2, 5)), afterText.allRefuted)
        val textFailed = progress(text, error = MailException(MailErrorKind.NETWORK, "down")).copy(refuted = setOf(SearchKey(2, 7)))
        val failed = afterHeaders.onFinished(text, textFailed)
        assertEquals(setOf(SearchKey(2, 3), SearchKey(2, 5), SearchKey(2, 7)), failed.refuted)
        val stopped = afterHeaders.onProgress(progress(text).copy(finished = false, refuted = setOf(SearchKey(2, 8)))).stop()
        assertEquals(setOf(SearchKey(2, 3), SearchKey(2, 5), SearchKey(2, 8)), stopped.refuted)
    }

    @Test
    fun theServerIsSearchedByItselfOnlyForFewLocalResults() {
        assertTrue(shouldAutoSearch("elecciones", localCount = 0, alreadySearched = false))
        assertTrue(shouldAutoSearch("elecciones", localCount = AUTO_SERVER_MAX_LOCAL - 1, alreadySearched = false))
        assertFalse(shouldAutoSearch("elecciones", localCount = AUTO_SERVER_MAX_LOCAL, alreadySearched = false))
        assertFalse(shouldAutoSearch("elecciones", localCount = 0, alreadySearched = true))
        assertFalse("nothing the server can search for", shouldAutoSearch("a", localCount = 0, alreadySearched = false))
        assertFalse(shouldAutoSearch("  ", localCount = 0, alreadySearched = false))
    }

    @Test
    fun rowKeysAreUniqueAcrossSections() {
        val keys = listOf(SearchKey(1, 2), SearchKey(12, 2), SearchKey(1, 22))
        val rows = keys.map { hitKey(SECTION_LOCAL, it) } + keys.map { hitKey(SECTION_SERVER, it) }
        assertEquals(rows.size, rows.toSet().size)
        assertEquals("server-12-2", hitKey(SECTION_SERVER, SearchKey(12, 2)))
    }

    @Test
    fun problemsAreToldApartBySkippedFailedAndCharset() {
        val problems = listOf(
            problem(1, SearchPhase.TEXT, StepOutcome.TIMED_OUT),
            problem(1, SearchPhase.TEXT, StepOutcome.SKIPPED),
            problem(2, SearchPhase.HEADERS, StepOutcome.FAILED, MailException(MailErrorKind.SERVER, "NO")),
            problem(3, SearchPhase.TEXT, StepOutcome.FAILED, MailException(MailErrorKind.SERVER, ServerSearch.CHARSET_REJECTED)),
            // Gone from the server: nothing to tell or retry.
            problem(4, SearchPhase.HEADERS, StepOutcome.SKIPPED, MailException(MailErrorKind.NOT_FOUND, "gone")),
            // Renumbered during the search: worth another try.
            problem(5, SearchPhase.TEXT, StepOutcome.SKIPPED, MailException(MailErrorKind.SERVER, "UIDVALIDITY changed")),
        )
        assertEquals(2, skippedParts(problems))
        assertEquals(2, failedParts(problems))
        assertEquals(listOf(3L), problems.filter(::charsetRejected).map { it.folder.id })
        assertEquals(listOf(1L, 1L, 2L, 5L), problems.filter(::retryable).map { it.folder.id })
        assertEquals(0, skippedParts(emptyList()))
    }

    @Test
    fun indexCoverageOfTheSearchedAccounts() {
        val statuses = listOf(
            IndexStatus(accountId = 1, indexed = 1_000, total = 4_000, complete = false),
            IndexStatus(accountId = 2, indexed = 500, total = 500, complete = true),
        )
        assertEquals(IndexSummary(1_500, 4_500, running = false), indexSummary(statuses, accountIds = null))
        assertNull("complete", indexSummary(statuses, setOf(2L)))
        assertEquals(IndexSummary(1_000, 4_000, running = false), indexSummary(statuses, setOf(1L)))
        assertEquals(3_000L * INDEX_BYTES_PER_MESSAGE, indexSummary(statuses, setOf(1L))!!.remainingBytes)
        val running = listOf(statuses[1].copy(running = true))
        assertEquals("a run is shown even on a full index", IndexSummary(500, 500, running = true), indexSummary(running, null))
        assertNull("all there, only not verified yet", indexSummary(listOf(IndexStatus(1, 200, 200, complete = false)), null))
        assertNull("folders not known yet", indexSummary(listOf(IndexStatus(1, 0, 0, complete = false)), null))
        assertNull(indexSummary(emptyList(), null))
        assertEquals("12.345", formatCount(12_345, Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun aHeadersSearchThenTheTextSearchQueuesOnlyWhatIsMissing() {
        val start = session(local = listOf(cached(1))).request(setOf(SearchPhase.HEADERS))
        assertEquals(setOf(SearchKey(1, 1)), start.localKeys)
        assertEquals(setOf("<m1@upm.es>"), start.localMids)
        val headers = start.queue.single()
        assertEquals(ServerSearchRequest("elecciones", scope, setOf(SearchPhase.HEADERS)), headers)
        assertTrue(start.running)
        assertFalse(start.textRequested)
        assertEquals("queued once", start, start.request(setOf(SearchPhase.HEADERS)))

        // Asking for the text while the headers run adds the text alone, after them.
        val both = start.onProgress(progress(headers, hits = listOf(indexed(3)))).request(setOf(SearchPhase.TEXT))
        assertEquals(listOf(setOf(SearchPhase.HEADERS), setOf(SearchPhase.TEXT)), both.queue.map { it.phases })
        assertTrue(both.textRequested)
        assertEquals("the running search's hits are shown", listOf(SearchKey(2, 3)), both.allHits.map { it.key })

        val afterHeaders = both.onFinished(headers, progress(headers, hits = listOf(indexed(3))))
        assertEquals(setOf(SearchPhase.HEADERS), afterHeaders.done)
        assertEquals(listOf(setOf(SearchPhase.TEXT)), afterHeaders.queue.map { it.phases })
        val text = afterHeaders.queue.single()
        val finished = afterHeaders.onFinished(text, progress(text, hits = listOf(indexed(8), indexed(3))))
        assertFalse(finished.running)
        assertEquals(setOf(SearchPhase.HEADERS, SearchPhase.TEXT), finished.done)
        assertEquals(listOf(SearchKey(2, 8), SearchKey(2, 3)), finished.allHits.map { it.key })
        assertEquals("nothing left to search", finished, finished.request(setOf(SearchPhase.TEXT)))
    }

    @Test
    fun theTextSearchIncludesTheHeadersWhenTheyWereNotSearched() {
        val s = session().request(setOf(SearchPhase.TEXT))
        assertEquals(setOf(SearchPhase.HEADERS, SearchPhase.TEXT), s.queue.single().phases)
        assertFalse(s.binsOffered)
    }

    @Test
    fun trashAndSpamGetThePhasesTheFoldersGot() {
        val headers = session().request(setOf(SearchPhase.HEADERS))
        val done = headers.onFinished(headers.queue.single(), progress(headers.queue.single()))
        assertTrue(done.binsOffered)
        val bins = done.withBins()
        assertFalse(bins.binsOffered)
        val binsRequest = bins.queue.single()
        assertEquals(scope.copy(trashAndJunkOnly = true), binsRequest.scope)
        assertEquals(setOf(SearchPhase.HEADERS), binsRequest.phases)
        val binsDone = bins.onFinished(binsRequest, progress(binsRequest, hits = listOf(indexed(4, folderId = 9))))
        assertEquals(setOf(SearchPhase.HEADERS), binsDone.binsDone)
        assertEquals(setOf(SearchPhase.HEADERS), binsDone.done)
        assertEquals("hits are appended", listOf(SearchKey(9, 4)), binsDone.allHits.map { it.key })

        // The text search now covers the bins too.
        val text = binsDone.request(setOf(SearchPhase.TEXT))
        assertEquals(
            listOf(scope to setOf(SearchPhase.TEXT), scope.copy(trashAndJunkOnly = true) to setOf(SearchPhase.TEXT)),
            text.queue.map { it.scope to it.phases },
        )

        val scoped = ServerSession.start(2, "x", ScopeOfFolder, emptyList()).request(setOf(SearchPhase.HEADERS))
        val scopedDone = scoped.onFinished(scoped.queue.single(), progress(scoped.queue.single()))
        assertFalse("one folder: no bins", scopedDone.binsOffered)
        assertEquals(scopedDone, scopedDone.withBins())
    }

    @Test
    fun retryRunsTheFolderThatTookTooLongAgainAndReplacesItsProblems() {
        val text = session().request(setOf(SearchPhase.TEXT))
        val request = text.queue.single()
        val slow = problem(7, SearchPhase.TEXT, StepOutcome.TIMED_OUT)
        val charset = problem(7, SearchPhase.TEXT, StepOutcome.FAILED, MailException(MailErrorKind.SERVER, ServerSearch.CHARSET_REJECTED))
        val done = text.onFinished(request, progress(request, problems = listOf(slow, charset)))
        assertEquals(1, skippedParts(done.allProblems))
        assertTrue(done.canRetry)

        val retry = done.retry()
        val again = retry.queue.single()
        assertEquals(ServerSearchScope(listOf(1L), folderId = 7), again.scope)
        assertEquals(setOf(SearchPhase.TEXT), again.phases)
        assertEquals("queued once", retry, retry.retry())

        val fixed = retry.onFinished(again, progress(again, hits = listOf(indexed(2, folderId = 7))))
        assertEquals("the folder's problems are replaced", emptyList<StepProblem>(), fixed.allProblems)
        assertEquals(setOf(SearchPhase.HEADERS, SearchPhase.TEXT), fixed.done)
        assertEquals(listOf(SearchKey(7, 2)), fixed.allHits.map { it.key })
        assertFalse(fixed.canRetry)

        // Problems in several folders: the whole phase runs again.
        val two = text.onFinished(request, progress(request, problems = listOf(slow, problem(8, SearchPhase.HEADERS, StepOutcome.TIMED_OUT))))
        assertEquals(listOf(scope to setOf(SearchPhase.HEADERS, SearchPhase.TEXT)), two.retry().queue.map { it.scope to it.phases })
        // Only the charset: retrying does not help.
        assertFalse(text.onFinished(request, progress(request, problems = listOf(charset))).canRetry)
    }

    @Test
    fun anErrorEndsTheQueueAndRetryRunsItAgain() {
        val queued = session().request(setOf(SearchPhase.HEADERS)).request(setOf(SearchPhase.TEXT))
        val first = queued.queue.first()
        val offline = MailException(MailErrorKind.NETWORK, "offline")
        val failed = queued.onFinished(first, progress(first, hits = listOf(indexed(1)), error = offline))
        assertFalse(failed.running)
        assertEquals(offline, failed.error)
        assertEquals("what was found stays", listOf(SearchKey(2, 1)), failed.allHits.map { it.key })
        assertTrue(failed.done.isEmpty())
        assertTrue(failed.canRetry)
        val retried = failed.retry()
        assertNull(retried.error)
        assertEquals(queued.queue, retried.queue)

        val thrown = queued.onFinished(first, null, failure = offline)
        assertEquals(offline, thrown.error)
        // A new request replaces the failed ones.
        val resumed = failed.request(setOf(SearchPhase.TEXT))
        assertNull(resumed.error)
        assertEquals(listOf(setOf(SearchPhase.HEADERS, SearchPhase.TEXT)), resumed.queue.map { it.phases })
    }

    @Test
    fun stopKeepsWhatWasFoundAndTheLimitEndsTheSearch() {
        val running = session().request(setOf(SearchPhase.TEXT)).let { it.onProgress(progress(it.queue.single(), hits = listOf(indexed(5)))) }
        val stopped = running.stop()
        assertTrue(stopped.stopped)
        assertFalse(stopped.running)
        assertEquals(listOf(SearchKey(2, 5)), stopped.allHits.map { it.key })
        assertTrue("nothing finished", stopped.done.isEmpty())
        assertFalse(stopped.request(setOf(SearchPhase.TEXT)).stopped)

        val limited = session().request(setOf(SearchPhase.HEADERS)).request(setOf(SearchPhase.TEXT))
        val first = limited.queue.first().copy(limit = 2)
        val full = limited.copy(queue = listOf(first) + limited.queue.drop(1))
            .onFinished(first, progress(first, hits = listOf(indexed(1), indexed(2)), truncated = true))
        assertFalse(full.running)
        assertTrue(full.truncated)

        val dropped = stopped.drop(SearchKey(2, 5))
        assertTrue(dropped.allHits.isEmpty())
    }

    @Test
    fun queriesAreNormalised() {
        assertEquals("ana pérez", normalizeQuery("  ana \t  pérez \n"))
        assertEquals("", normalizeQuery("   "))
    }

    private companion object {
        val ScopeOfFolder = ServerSearchScope(listOf(1L), folderId = 3)
    }
}
