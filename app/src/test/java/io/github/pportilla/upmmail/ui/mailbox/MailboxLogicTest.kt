package io.github.pportilla.upmmail.ui.mailbox

import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.model.SwipeAction
import io.github.pportilla.upmmail.ui.navigation.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxLogicTest {

    private fun account(id: Long, email: String = "user$id@upm.es") = Account(
        id = id,
        email = email,
        displayName = "User $id",
        imap = ServerSettings("correo.upm.es", 993, SecurityMode.SSL_TLS, "user"),
        smtp = ServerSettings("smtp.upm.es", 587, SecurityMode.STARTTLS, "user@upm.es"),
        signature = "",
        color = 0,
        notificationsEnabled = true,
        sortOrder = id.toInt(),
    )

    private fun folder(
        id: Long,
        role: FolderRole = FolderRole.OTHER,
        fullName: String = "F$id",
        depth: Int = 0,
        selectable: Boolean = true,
        unread: Int = 0,
        total: Int = 0,
        accountId: Long = 1,
        lastSyncedAt: Long? = null,
    ) = Folder(
        id = id,
        accountId = accountId,
        fullName = fullName,
        displayName = fullName.substringAfterLast('/'),
        role = role,
        depth = depth,
        unreadCount = unread,
        totalCount = total,
        selectable = selectable,
        lastSyncedAt = lastSyncedAt,
    )

    private fun message(id: Long, read: Boolean = true, starred: Boolean = false) = MessageSummary(
        id = id,
        accountId = 1,
        folderId = 1,
        uid = id,
        threadId = "t$id",
        subject = "Subject $id",
        from = EmailAddress("a@upm.es", "Ana"),
        to = emptyList(),
        date = id,
        snippet = "",
        isRead = read,
        isStarred = starred,
        isAnswered = false,
        isForwarded = false,
        isDraft = false,
        hasAttachments = false,
    )

    // ---------------------------------------------------------------- account / folder resolution

    @Test
    fun resolveAccountPrefersStoredThenFirst() {
        val accounts = listOf(account(1), account(2))
        assertEquals(2L, resolveAccount(accounts, 2)?.id)
        assertEquals(1L, resolveAccount(accounts, 99)?.id)
        assertEquals(1L, resolveAccount(accounts, -1)?.id)
        assertNull(resolveAccount(emptyList(), 1))
    }

    @Test
    fun storedFolderIsRestored() {
        val folders = listOf(folder(10, FolderRole.INBOX), folder(11, FolderRole.SENT))
        val result = resolveView(1, 1, true, folders, lastAccountId = 1, lastFolderId = 11)
        assertEquals(MailboxView.FolderView(folders[1]), result.view)
        assertNull(result.persistFolderId)
    }

    @Test
    fun missingFolderFallsBackToInboxAndPersistsIt() {
        val folders = listOf(folder(10, FolderRole.SENT), folder(11, FolderRole.INBOX))
        val result = resolveView(1, 1, true, folders, lastAccountId = 1, lastFolderId = 99)
        assertEquals(MailboxView.FolderView(folders[1]), result.view)
        assertEquals(11L, result.persistFolderId)
    }

    @Test
    fun folderOfAnotherAccountIsNotRestored() {
        val folders = listOf(folder(10, FolderRole.INBOX), folder(11, FolderRole.SENT))
        // The stored account no longer exists: account 1 is shown, starting at its INBOX.
        val result = resolveView(1, 1, true, folders, lastAccountId = 7, lastFolderId = 11)
        assertEquals(10L, result.persistFolderId)
    }

    @Test
    fun staleFolderListNeverOverridesTheSelection() {
        // Settings already point at account 2 but the folders are still those of account 1.
        val folders = listOf(folder(10, FolderRole.INBOX, accountId = 1))
        val result = resolveView(2, 1, true, folders, lastAccountId = 2, lastFolderId = 20)
        assertEquals(MailboxView.Loading, result.view)
        assertNull(result.persistFolderId)
        val notLoaded = resolveView(2, 2, false, emptyList(), lastAccountId = 2, lastFolderId = 20)
        assertEquals(MailboxView.Loading, notLoaded.view)
    }

    @Test
    fun outboxIsShownWithoutWaitingForFolders() {
        val result = resolveView(3, null, false, emptyList(), lastAccountId = 3, lastFolderId = DeepLinks.OUTBOX_FOLDER_ID)
        assertEquals(MailboxView.Outbox(3), result.view)
        // The outbox of another account is not shown for this one.
        val other = resolveView(3, 3, true, listOf(folder(30, FolderRole.INBOX, accountId = 3)), 4, DeepLinks.OUTBOX_FOLDER_ID)
        assertTrue(other.view is MailboxView.FolderView)
    }

    @Test
    fun emptyOrUnselectableFoldersKeepLoading() {
        assertEquals(MailboxView.Loading, resolveView(1, 1, true, emptyList(), 1, -1).view)
        assertEquals(MailboxView.Loading, resolveView(null, null, true, emptyList(), -1, -1).view)
        val noSelect = listOf(folder(10, selectable = false))
        assertEquals(MailboxView.Loading, resolveView(1, 1, true, noSelect, 1, 10).view)
        val fallback = resolveView(1, 1, true, listOf(folder(10, selectable = false), folder(11)), 1, 10)
        assertEquals(11L, fallback.persistFolderId)
    }

    // ---------------------------------------------------------------- drawer

    @Test
    fun drawerPutsRolesFirstInFixedOrder() {
        val folders = listOf(
            folder(1, FolderRole.INBOX, "INBOX"),
            folder(2, FolderRole.TRASH, "Trash"),
            folder(3, FolderRole.SENT, "Sent"),
            folder(4, FolderRole.OTHER, "Projects"),
            folder(5, FolderRole.DRAFTS, "Drafts"),
            folder(6, FolderRole.JUNK, "Spam"),
            folder(7, FolderRole.ARCHIVE, "Archive"),
        )
        val arranged = arrangeDrawerFolders(folders)
        assertEquals(listOf(1L, 5L, 3L, 7L, 6L, 2L), arranged.roles.map { it.folder.id })
        assertEquals(listOf(4L), arranged.others.map { it.folder.id })
    }

    @Test
    fun duplicateRolesAndHierarchyGoToOthers() {
        val folders = listOf(
            folder(1, FolderRole.INBOX, "INBOX"),
            folder(2, FolderRole.TRASH, "Trash"),
            folder(3, FolderRole.TRASH, "Papelera"),
            folder(4, FolderRole.OTHER, "Work", depth = 0, selectable = false),
            folder(5, FolderRole.OTHER, "Work/2025", depth = 1),
            folder(6, FolderRole.OTHER, "Work/2025/Q1", depth = 2),
            folder(7, FolderRole.OTHER, "Personal", depth = 0),
        )
        val others = arrangeDrawerFolders(folders).others
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), others.map { it.folder.id })
        assertEquals(listOf(0, 0, 1, 2, 0), others.map { it.indent })
        assertTrue(others.all { it.pathLabel == null })
    }

    @Test
    fun orphanSubfoldersShowTheirPath() {
        val folders = listOf(
            folder(1, FolderRole.INBOX, "INBOX"),
            // Child of INBOX, whose parent is in the role section.
            folder(2, FolderRole.OTHER, "INBOX/Projects", depth = 1),
            folder(3, FolderRole.OTHER, "INBOX/Projects/Old", depth = 2),
        )
        val others = arrangeDrawerFolders(folders).others
        assertEquals(listOf(0, 1), others.map { it.indent })
        assertEquals("INBOX/Projects", others[0].pathLabel)
        assertNull(others[1].pathLabel)
        // Flattened "INBOX." namespaces have depth 0 and keep their short names.
        val flat = arrangeDrawerFolders(
            listOf(
                folder(1, FolderRole.INBOX, "INBOX"),
                folder(2, FolderRole.OTHER, "INBOX.Work", depth = 0),
                folder(3, FolderRole.OTHER, "INBOX.Work.Old", depth = 1),
                folder(4, FolderRole.OTHER, "INBOX.Workshop", depth = 0),
            ),
        ).others
        assertEquals(listOf(0, 1, 0), flat.map { it.indent })
        assertTrue(flat.all { it.pathLabel == null })
    }

    @Test
    fun badgeCountsDependOnRole() {
        assertEquals(4, drawerBadgeCount(folder(1, FolderRole.INBOX, unread = 4, total = 10)))
        assertEquals(10, drawerBadgeCount(folder(1, FolderRole.DRAFTS, unread = 0, total = 10)))
        assertEquals(0, drawerBadgeCount(folder(1, FolderRole.SENT, unread = 3, total = 10)))
        assertEquals(0, drawerBadgeCount(folder(1, FolderRole.TRASH, unread = 3, total = 10)))
        assertEquals(2, drawerBadgeCount(folder(1, FolderRole.JUNK, unread = 2, total = 10)))
        assertEquals(0, drawerBadgeCount(folder(1, FolderRole.OTHER, unread = -5)))
    }

    // ---------------------------------------------------------------- paging

    @Test
    fun pagingGrowsTheLimitWhileTheCacheHasRows() {
        val paging = PagingState(folderId = 1, limit = 50, shownLimit = 50)
        assertEquals(PagingStep.GROW_LIMIT, nextPagingStep(paging, shownRows = 50))
        assertEquals(PagingStep.LOAD_OLDER, nextPagingStep(paging, shownRows = 37))
    }

    @Test
    fun pagingWaitsForTheCurrentRequestAndStopsAtTheEnd() {
        assertEquals(PagingStep.NOTHING, nextPagingStep(PagingState(1, limit = 100, shownLimit = 50), 50))
        assertEquals(PagingStep.NOTHING, nextPagingStep(PagingState(1, limit = 50, shownLimit = 50, loadingOlder = true), 10))
        assertEquals(PagingStep.NOTHING, nextPagingStep(PagingState(1, limit = 50, shownLimit = 50, failed = true), 10))
        assertEquals(PagingStep.NOTHING, nextPagingStep(PagingState(1, limit = 50, shownLimit = 50, endReached = true), 10))
        // Even at the server's end, rows still cached beyond the limit are shown.
        assertEquals(PagingStep.GROW_LIMIT, nextPagingStep(PagingState(1, limit = 50, shownLimit = 50, endReached = true), 50))
    }

    // ---------------------------------------------------------------- rows and actions

    @Test
    fun recipientNamesAreShortForSeveralRecipients() {
        val ana = EmailAddress("ana.perez@upm.es", "Ana Pérez")
        val luis = EmailAddress("luis@upm.es", null)
        val me = EmailAddress("Me@UPM.es", "Myself")
        assertEquals("", recipientNames(emptyList(), null, "me"))
        assertEquals("Ana Pérez", recipientNames(listOf(ana), null, "me"))
        assertEquals("me", recipientNames(listOf(me), "me@upm.es", "me"))
        assertEquals("Ana, luis, me", recipientNames(listOf(ana, luis, me), "me@upm.es", "me"))
        assertEquals("Ana, luis", recipientNames(listOf(EmailAddress("x@y.es", "\"Ana\" Q"), luis), null, "me"))
        assertEquals("Ana, luis", recipientNames(listOf(EmailAddress("x@y.es", "Pérez García, Ana"), luis), null, "me"))
    }

    @Test
    fun selectionActionsOfferWhatChangesSomething() {
        assertEquals(SelectionActions(markRead = true, star = true), selectionActions(listOf(message(1, read = false), message(2))))
        assertEquals(SelectionActions(markRead = false, star = false), selectionActions(listOf(message(1, starred = true))))
        assertEquals(SelectionActions(markRead = false, star = true), selectionActions(listOf(message(1), message(2, starred = true))))
    }

    @Test
    fun archiveSwipeIsDisabledInsideTheArchive() {
        assertEquals(SwipeAction.NONE, effectiveSwipeAction(SwipeAction.ARCHIVE, FolderRole.ARCHIVE))
        assertEquals(SwipeAction.ARCHIVE, effectiveSwipeAction(SwipeAction.ARCHIVE, FolderRole.INBOX))
        assertEquals(SwipeAction.DELETE, effectiveSwipeAction(SwipeAction.DELETE, FolderRole.ARCHIVE))
    }

    @Test
    fun roleHelpers() {
        assertTrue(showsRecipients(FolderRole.SENT))
        assertTrue(showsRecipients(FolderRole.DRAFTS))
        assertFalse(showsRecipients(FolderRole.INBOX))
        assertFalse(showsRecipients(null))
        assertTrue(deletesPermanently(FolderRole.TRASH))
        assertTrue(deletesPermanently(FolderRole.JUNK))
        assertFalse(deletesPermanently(FolderRole.ARCHIVE))
    }

    @Test
    fun staleness() {
        val now = 10_000_000L
        assertTrue(isStale(folder(1, lastSyncedAt = null), now))
        assertTrue(isStale(folder(1, lastSyncedAt = now - STALE_AFTER_MILLIS), now))
        assertFalse(isStale(folder(1, lastSyncedAt = now - 1_000), now))
        // A clock that went backwards counts as stale.
        assertTrue(isStale(folder(1, lastSyncedAt = now + 60_000), now))
    }
}
