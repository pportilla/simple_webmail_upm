package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.NOT_PERSONAL_ATTRIBUTE
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchFilters
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
    fun attributesAreStoredInLowerCaseAndDecideWhatIsIndexed() {
        store.folders.applyServerList(
            accountId,
            listOf(
                entry("INBOX", FolderRole.INBOX),
                entry("Sent", FolderRole.SENT, attributes = setOf("\\Sent", "\\HasNoChildren")),
                entry("Drafts", FolderRole.DRAFTS, attributes = setOf("\\Drafts")),
                entry("Trash", FolderRole.TRASH, attributes = setOf("\\Trash")),
                entry("All Mail", attributes = setOf("\\All")),
                entry("Starred", attributes = setOf("\\Flagged")),
                entry("Shared/Team", attributes = setOf(NOT_PERSONAL_ATTRIBUTE)),
                FolderListEntry("Parent", "Parent", '/', setOf("\\Noselect"), false, 0, FolderRole.OTHER),
                entry("Projects"),
            ),
        )
        val sent = store.folders.findByFullName(accountId, "Sent")!!
        assertEquals(setOf("\\hasnochildren", "\\sent"), store.folders.attributesOf(sent.id))
        assertEquals(
            "\\hasnochildren \\sent",
            store.sql.query("SELECT attributes FROM folders WHERE id = ?", listOf(sent.id)) { it.string("attributes") }.single(),
        )
        assertNull(store.folders.attributesOf(9_999))
        assertFalse(store.folders.hasUnlistedFolders(accountId))
        assertEquals(listOf("INBOX", "Sent", "Trash", "Projects"), store.folders.indexableFolders(accountId).map { it.fullName })

        // Attributes follow the server list.
        store.folders.applyServerList(accountId, listOf(entry("INBOX", FolderRole.INBOX), entry("Sent", FolderRole.SENT)))
        assertEquals(emptySet<String>(), store.folders.attributesOf(sent.id))
        val created = store.folders.insert(accountId, entry("Archive", FolderRole.ARCHIVE, attributes = setOf("\\Archive")))
        assertEquals(setOf("\\archive"), store.folders.attributesOf(created))
    }

    @Test
    fun aFolderTheIndexNoLongerCoversLosesItsIndexRowsAndState() {
        store.folders.applyServerList(accountId, listOf(entry("INBOX", FolderRole.INBOX), entry("Proyectos"), entry("Borradores")))
        val ids = store.folders.getByAccount(accountId).associate { it.fullName to it.id }
        for (name in listOf("INBOX", "Proyectos", "Borradores")) {
            val id = ids.getValue(name)
            store.headerIndex.ensureState(id, 1, 10, 3)
            store.headerIndex.insertHeaders(id, 1, (1L..3L).map { remoteMessage(it, subject = "proyecto $it").toIndexedHeader() })
        }
        store.headerIndex.markDirty(ids.values)
        assertEquals(9, store.headerIndex.accountRowCount(accountId))

        // Proyectos turns out to be a virtual folder, Borradores the Drafts folder.
        store.folders.applyServerList(
            accountId,
            listOf(entry("INBOX", FolderRole.INBOX), entry("Proyectos", attributes = setOf("\\All")), entry("Borradores", FolderRole.DRAFTS)),
        )
        assertEquals(listOf("INBOX"), store.folders.indexableFolders(accountId).map { it.fullName })
        assertNull(store.headerIndex.state(ids.getValue("Proyectos")))
        assertNull(store.headerIndex.state(ids.getValue("Borradores")))
        assertEquals(3, store.headerIndex.accountRowCount(accountId))
        assertEquals(3L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertEquals(listOf(ids.getValue("INBOX")), store.headerIndex.dirtyFolders(accountId))
        assertTrue(store.headerIndex.search("proyecto", SearchFilters(), 10).all { it.folderId == ids.getValue("INBOX") })
    }

    @Test
    fun dirtyFoldersAreOnlyFoldersTheIndexCovers() {
        store.folders.applyServerList(accountId, listOf(entry("INBOX", FolderRole.INBOX), entry("Proyectos")))
        val inbox = store.folders.findByFullName(accountId, "INBOX")!!.id
        val projects = store.folders.findByFullName(accountId, "Proyectos")!!.id
        store.headerIndex.ensureState(inbox, 1, 10, 0)
        store.headerIndex.ensureState(projects, 1, 10, 0)
        store.headerIndex.markDirty(listOf(inbox, projects))
        // A state row left behind by some other path for a folder that became unselectable.
        store.sql.execSQL("UPDATE folders SET selectable = 0 WHERE id = ?", listOf(projects))
        assertEquals(listOf(inbox), store.headerIndex.dirtyFolders())
        assertEquals(listOf(inbox), store.headerIndex.dirtyFolders(accountId))
    }

    @Test
    fun indexabilityRules() {
        assertFalse("attributes not known yet", FolderDao.isIndexable(FolderRole.INBOX, true, null))
        assertTrue(FolderDao.isIndexable(FolderRole.INBOX, true, emptySet()))
        assertTrue(FolderDao.isIndexable(FolderRole.JUNK, true, setOf("\\junk")))
        assertFalse(FolderDao.isIndexable(FolderRole.DRAFTS, true, emptySet()))
        assertFalse(FolderDao.isIndexable(FolderRole.OTHER, false, emptySet()))
        assertFalse(FolderDao.isIndexable(FolderRole.ARCHIVE, true, setOf("\\all")))
        assertFalse(FolderDao.isIndexable(FolderRole.OTHER, true, setOf(NOT_PERSONAL_ATTRIBUTE)))
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
