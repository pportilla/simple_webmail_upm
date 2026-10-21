package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.FolderRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolderAndOpsDaoTest {
    private lateinit var store: MailStore
    private var accountId = 0L

    @Before
    fun setUp() {
        store = newStore()
        accountId = store.addTestAccount().id
    }

    private fun op(type: PendingOpType, folder: String, uids: List<Long>, target: String? = null, notBefore: Long = 0) =
        PendingOp(accountId = accountId, type = type, folder = folder, uids = uids, target = target, flag = "SEEN", value = true, notBefore = notBefore)

    private fun entry(name: String, role: FolderRole = FolderRole.OTHER, attributes: Set<String> = emptySet(), depth: Int = 0) =
        FolderListEntry(name, name.substringAfterLast('/'), '/', attributes, true, depth, role)

    @Test
    fun serverListKeepsIdsStableAndRemovesVanishedFolders() {
        store.folders.applyServerList(
            accountId,
            listOf(entry("INBOX", FolderRole.INBOX), entry("Projects"), entry("Projects/2026", depth = 1), entry("Old")),
        )
        val before = store.folders.getByAccount(accountId).associate { it.fullName to it.id }
        val oldId = before.getValue("Old")
        store.messages.upsertHeaders(accountId, oldId, listOf(remoteMessage(1)))

        store.folders.applyServerList(
            accountId,
            listOf(entry("INBOX", FolderRole.INBOX), entry("Projects"), entry("Projects/2026", depth = 1), entry("Sent", FolderRole.SENT)),
        )
        val after = store.folders.getByAccount(accountId).associate { it.fullName to it.id }
        assertEquals(before.getValue("INBOX"), after.getValue("INBOX"))
        assertEquals(before.getValue("Projects/2026"), after.getValue("Projects/2026"))
        assertNull(after["Old"])
        assertEquals("messages of a vanished folder go too", 0, store.messages.count(oldId))
    }

    @Test
    fun foldersAreOrderedInboxRolesThenTree() {
        store.folders.applyServerList(
            accountId,
            listOf(
                entry("Zeta"),
                entry("Trash", FolderRole.TRASH),
                entry("Projects-old"),
                entry("Projects/B", depth = 1),
                entry("Projects"),
                entry("Sent", FolderRole.SENT),
                entry("INBOX", FolderRole.INBOX),
                entry("Drafts", FolderRole.DRAFTS),
            ),
        )
        assertEquals(
            listOf("INBOX", "Drafts", "Sent", "Trash", "Projects", "Projects/B", "Projects-old", "Zeta"),
            store.folders.getByAccount(accountId).map { it.fullName },
        )
    }

    @Test
    fun duplicateRolesPreferSpecialUseAttribute() {
        store.folders.applyServerList(
            accountId,
            listOf(
                entry("INBOX", FolderRole.INBOX),
                entry("Sent", FolderRole.SENT),
                entry("Elementos enviados", FolderRole.SENT, attributes = setOf("\\sent")),
            ),
        )
        assertEquals("Elementos enviados", store.folders.findByRole(accountId, FolderRole.SENT)!!.fullName)
        assertEquals(FolderRole.OTHER, store.folders.findByFullName(accountId, "Sent")!!.role)
    }

    @Test
    fun inboxNamespaceIsFlattened() {
        val normalised = FolderDao.normaliseDepth(
            listOf(
                FolderListEntry("INBOX", "INBOX", '.', emptySet(), true, 0, FolderRole.INBOX),
                FolderListEntry("INBOX.Sent", "Sent", '.', emptySet(), true, 1, FolderRole.SENT),
                FolderListEntry("INBOX.Projects.A", "A", '.', emptySet(), true, 2, FolderRole.OTHER),
            ),
        )
        assertEquals(listOf(0, 0, 1), normalised.map { it.depth })
    }

    @Test
    fun countersAreAdjustedAndClamped() {
        val inbox = store.addTestFolder(accountId, "INBOX", FolderRole.INBOX)
        store.folders.updateAfterSync(inbox.id, 77, unreadCount = 2, totalCount = 10)
        store.folders.adjustCounts(inbox.id, -5, -1)
        val folder = store.folders.get(inbox.id)!!
        assertEquals(0, folder.unreadCount)
        assertEquals(9, folder.totalCount)
        assertEquals(77L, store.folders.syncState(inbox.id)!!.uidValidity)
    }

    @Test
    fun pendingOpsKeepOrderAndCanBeClaimedOnce() {
        val a = store.pendingOps.insert(op(PendingOpType.FLAG, "INBOX", listOf(3, 1)))
        val b = store.pendingOps.insert(op(PendingOpType.MOVE, "INBOX", listOf(2), target = "Archive", notBefore = 99))
        val ops = store.pendingOps.forAccount(accountId)
        assertEquals(listOf(a, b), ops.map { it.id })
        assertEquals(listOf(3L, 1L), ops[0].uids)
        assertEquals(0L, store.pendingOps.nextNotBefore(accountId))

        assertTrue(store.pendingOps.claim(a))
        assertFalse("already in flight", store.pendingOps.claim(a))
        store.pendingOps.release(a, attempts = 1)
        assertEquals(1, store.pendingOps.get(a)!!.attempts)
        assertTrue(store.pendingOps.claim(a))
        store.pendingOps.resetInFlight()
        assertFalse(store.pendingOps.get(a)!!.inFlight)
    }

    @Test
    fun undoGroupCancelsWaitingOpsOnly() {
        val a = store.pendingOps.insert(op(PendingOpType.MOVE, "INBOX", listOf(1), target = "Trash"))
        val b = store.pendingOps.insert(op(PendingOpType.MOVE, "Other", listOf(5), target = "Trash"))
        store.pendingOps.setUndoGroup(listOf(a, b), a)
        assertTrue(store.pendingOps.claim(b))
        val (cancelled, inFlight) = store.pendingOps.cancelGroup(a)
        assertEquals(listOf(a), cancelled.map { it.id })
        assertEquals(listOf(b), inFlight.map { it.id })
        assertEquals(listOf(b), store.pendingOps.forAccount(accountId).map { it.id })
    }

    @Test
    fun protectedUidsCoverFlagMoveAndFolderWideOps() {
        store.pendingOps.insert(op(PendingOpType.FLAG, "INBOX", listOf(4)))
        store.pendingOps.insert(op(PendingOpType.MOVE, "INBOX", listOf(7), target = "X"))
        store.pendingOps.insert(op(PendingOpType.FLAG, "Other", listOf(9)))
        val inbox = store.pendingOps.protectedUids(accountId, "INBOX")
        assertEquals(setOf(4L, 7L), inbox.uids)
        assertFalse(9L in inbox)
        store.pendingOps.insert(PendingOp(accountId = accountId, type = PendingOpType.MARK_FOLDER_READ, folder = "Other"))
        assertTrue(123L in store.pendingOps.protectedUids(accountId, "Other"))
    }

    @Test
    fun recentSearchesAreDistinctNewestFirstAndBounded() {
        var time = 1_000L
        val timed = MailStore(JdbcSqlDatabase.inMemory(), TableObserver()) { time }
        for (i in 1..12) {
            time += 10
            timed.recentSearches.add("query $i")
        }
        time += 10
        timed.recentSearches.add("  QUERY   5 ")
        val list = timed.recentSearches.list()
        assertEquals(RecentSearchDao.MAX_ENTRIES, list.size)
        assertEquals("QUERY 5", list.first())
        assertEquals(1, list.count { it.equals("query 5", ignoreCase = true) })
        timed.recentSearches.clear()
        assertTrue(timed.recentSearches.list().isEmpty())
    }
}
