package io.github.pportilla.upmmail.ui.search

import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    private fun message(id: Long, read: Boolean = true, starred: Boolean = false, attachments: Boolean = false) =
        MessageSummary(
            id = id,
            accountId = 1,
            folderId = 1,
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

    @Test
    fun filtersMapToRepositoryFilters() {
        val all = toSearchFilters(SearchFilterState(unreadOnly = true, thisFolderOnly = false), accountId = 3, folderId = 7)
        assertEquals(SearchFilters(accountId = 3, folderId = null, unreadOnly = true), all)
        val folder = toSearchFilters(SearchFilterState(starredOnly = true, withAttachments = true, thisFolderOnly = true), null, 7)
        assertEquals(SearchFilters(accountId = null, folderId = 7, starredOnly = true, withAttachments = true), folder)
    }

    @Test
    fun flagFilters() {
        val unreadStarred = message(1, read = false, starred = true)
        assertTrue(matchesFilters(unreadStarred, SearchFilterState(unreadOnly = true, starredOnly = true)))
        assertTrue(!matchesFilters(unreadStarred, SearchFilterState(withAttachments = true)))
        assertTrue(matchesFilters(message(2), SearchFilterState()))
        assertTrue(!matchesFilters(message(2), SearchFilterState(unreadOnly = true)))
    }

    @Test
    fun serverResultsAreDedupedAgainstLocalOnes() {
        val local = listOf(message(1), message(2))
        val server = listOf(message(2), message(3), message(3), message(4))
        val merged = mergeResults(local, server, localIdsAtServerSearch = setOf(1, 2), filters = SearchFilterState())
        assertEquals(listOf(1L, 2L), merged.local.map { it.id })
        assertEquals(listOf(3L, 4L), merged.fromServer.map { it.id })
    }

    @Test
    fun serverMatchesStayInTheirSectionWhenLocalSearchRunsAgain() {
        // After the server search, message 3 is cached and the re-run local search finds it too,
        // now marked as read: it stays under "From server" with the fresh state.
        val local = listOf(message(1), message(3, read = true))
        val server = listOf(message(3, read = false), message(4))
        val merged = mergeResults(local, server, localIdsAtServerSearch = setOf(1), filters = SearchFilterState())
        assertEquals(listOf(1L), merged.local.map { it.id })
        assertEquals(listOf(3L, 4L), merged.fromServer.map { it.id })
        assertTrue(merged.fromServer.first().isRead)
    }

    @Test
    fun serverResultsHonourFlagFilters() {
        val server = listOf(message(5, read = false), message(6, read = true), message(7, read = false, attachments = true))
        val unread = mergeResults(emptyList(), server, emptySet(), SearchFilterState(unreadOnly = true))
        assertEquals(listOf(5L, 7L), unread.fromServer.map { it.id })
        val withFiles = mergeResults(emptyList(), server, emptySet(), SearchFilterState(withAttachments = true))
        assertEquals(listOf(7L), withFiles.fromServer.map { it.id })
    }

    @Test
    fun queriesAreNormalised() {
        assertEquals("ana pérez", normalizeQuery("  ana \t  pérez \n"))
        assertEquals("", normalizeQuery("   "))
    }
}
