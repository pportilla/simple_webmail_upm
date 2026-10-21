package io.github.pportilla.upmmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModelsTest {

    private fun cached(
        id: Long,
        folderId: Long,
        uid: Long,
        date: Long,
        messageId: String? = "<m$id@x>",
        read: Boolean = false,
    ) = SearchHit.Cached(
        MessageSummary(
            id = id, accountId = 1, folderId = folderId, uid = uid, threadId = "t$id", subject = "Asunto $id", from = null,
            to = emptyList(), date = date, snippet = "vista previa", isRead = read, isStarred = false, isAnswered = false,
            isForwarded = false, isDraft = false, hasAttachments = false,
        ),
        messageId,
    )

    private fun indexed(
        indexId: Long,
        folderId: Long,
        uid: Long,
        date: Long,
        messageId: String? = "<i$indexId@x>",
        role: FolderRole = FolderRole.INBOX,
        read: Boolean = false,
    ) = SearchHit.Indexed(
        IndexedMessage(
            indexId = indexId, accountId = 1, folderId = folderId, folderRole = role, uid = uid, messageIdHeader = messageId,
            subject = "Indexado $indexId", from = EmailAddress("bea@upm.es"), to = emptyList(), date = date, size = 10,
            isRead = read, isStarred = true, isAnswered = false, hasAttachments = true,
        ),
    )

    @Test
    fun hitsExposeTheirSourceForDisplayWithoutAMessageIdForIndexedOnes() {
        val hit = indexed(7, folderId = 3, uid = 40, date = 500)
        assertEquals(SearchKey(3, 40), hit.key)
        assertEquals(0L, hit.display.id)
        assertEquals("Indexado 7", hit.display.subject)
        assertEquals(EmailAddress("bea@upm.es"), hit.display.from)
        assertTrue(hit.display.isStarred && hit.display.hasAttachments && hit.isStarred && hit.hasAttachments)
        assertEquals("<i7@x>", hit.messageIdHeader)
        val local = cached(9, folderId = 3, uid = 41, date = 600)
        assertSame(local.message, local.display)
        assertEquals(SearchKey(3, 41), local.key)
        assertEquals(600L, local.date)
    }

    @Test
    fun oneHitPerKeyCachedFirstElseTheLaterList() {
        val oldIndexed = indexed(1, folderId = 1, uid = 5, date = 100, read = false)
        val newIndexed = indexed(1, folderId = 1, uid = 5, date = 100, read = true)
        val local = cached(30, folderId = 1, uid = 5, date = 100, messageId = "<i1@x>")
        assertEquals(listOf(newIndexed), mergeHits(listOf(oldIndexed), listOf(newIndexed)))
        assertEquals(listOf(local), mergeHits(listOf(local), listOf(newIndexed)))
        assertEquals(listOf(local), mergeHits(listOf(newIndexed), listOf(local)))
        val refreshed = cached(30, folderId = 1, uid = 5, date = 100, messageId = "<i1@x>", read = true)
        assertEquals("a later cached copy replaces an earlier one", listOf(refreshed), mergeHits(listOf(local), listOf(refreshed)))
    }

    @Test
    fun copiesOfOneMessageInSeveralFoldersAppearOnce() {
        val inInbox = indexed(1, folderId = 1, uid = 5, date = 100, messageId = "<same@x>", role = FolderRole.INBOX)
        val inArchive = indexed(2, folderId = 2, uid = 6, date = 200, messageId = "<SAME@x>", role = FolderRole.ARCHIVE)
        val inTrash = indexed(3, folderId = 3, uid = 7, date = 300, messageId = "<same@X>", role = FolderRole.TRASH)
        assertEquals("cached beats indexed", listOf(cached(9, 4, 8, 50, "<Same@x>")), mergeHits(listOf(inTrash, inArchive), listOf(cached(9, 4, 8, 50, "<Same@x>"), inInbox)))
        assertEquals("role order beats date", listOf(inInbox), mergeHits(listOf(inTrash, inArchive, inInbox)))
        assertEquals(listOf(inArchive), mergeHits(listOf(inTrash, inArchive)))

        val sent = indexed(4, folderId = 5, uid = 1, date = 100, messageId = "<r@x>", role = FolderRole.SENT)
        val other = indexed(5, folderId = 6, uid = 1, date = 100, messageId = "<r@x>", role = FolderRole.OTHER)
        val drafts = indexed(6, folderId = 7, uid = 1, date = 100, messageId = "<r@x>", role = FolderRole.DRAFTS)
        val junk = indexed(7, folderId = 8, uid = 1, date = 100, messageId = "<r@x>", role = FolderRole.JUNK)
        assertEquals(listOf(sent), mergeHits(listOf(junk, drafts, other, sent)))
        assertEquals(listOf(other), mergeHits(listOf(junk, drafts, other)))
        assertEquals(listOf(drafts), mergeHits(listOf(junk, drafts)))
        assertEquals(listOf(junk), mergeHits(listOf(junk, inTrash.copy(entry = inTrash.entry.copy(messageIdHeader = "<r@x>")))))
    }

    @Test
    fun tiesBetweenCopiesGoToTheNewerThenTheLowerId() {
        val older = cached(1, folderId = 1, uid = 1, date = 100, messageId = "<x@x>")
        val newer = cached(2, folderId = 2, uid = 1, date = 200, messageId = "<x@x>")
        val newerHigherId = cached(3, folderId = 3, uid = 1, date = 200, messageId = "<x@x>")
        assertEquals(listOf(newer), mergeHits(listOf(older, newerHigherId, newer)))

        val a = indexed(10, folderId = 1, uid = 2, date = 100, messageId = "<y@x>", role = FolderRole.OTHER)
        val b = indexed(11, folderId = 2, uid = 2, date = 300, messageId = "<y@x>", role = FolderRole.OTHER)
        val c = indexed(12, folderId = 3, uid = 2, date = 300, messageId = "<y@x>", role = FolderRole.OTHER)
        assertEquals(listOf(b), mergeHits(listOf(a, c, b)))
    }

    @Test
    fun hitsWithoutMessageIdAreNeverMergedByIt() {
        val first = indexed(1, folderId = 1, uid = 1, date = 100, messageId = null)
        val second = indexed(2, folderId = 1, uid = 2, date = 100, messageId = " ")
        val third = cached(3, folderId = 1, uid = 3, date = 100, messageId = null)
        assertEquals(3, mergeHits(listOf(first, second, third)).size)
    }

    @Test
    fun resultIsNewestFirstThenByFolderAndUidAndHonoursTheLimit() {
        val hits = listOf(
            indexed(1, folderId = 1, uid = 9, date = 100),
            cached(2, folderId = 2, uid = 1, date = 300),
            indexed(3, folderId = 2, uid = 4, date = 100),
            indexed(4, folderId = 2, uid = 3, date = 100),
            cached(5, folderId = 1, uid = 2, date = 200),
        )
        val merged = mergeHits(hits.take(2), hits.drop(2))
        assertEquals(
            listOf(SearchKey(2, 1), SearchKey(1, 2), SearchKey(2, 4), SearchKey(2, 3), SearchKey(1, 9)),
            merged.map { it.key },
        )
        assertEquals(merged.take(2), mergeHits(hits, limit = 2))
        assertTrue(mergeHits(hits, limit = 0).isEmpty())
        assertTrue(mergeHits().isEmpty())
    }
}
