package io.github.pportilla.upmmail.ui.message

import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiStateTest {

    private val inbox = folder(1, FolderRole.INBOX)
    private val sent = folder(2, FolderRole.SENT)
    private val archive = folder(3, FolderRole.ARCHIVE)
    private val trash = folder(4, FolderRole.TRASH)
    private val junk = folder(5, FolderRole.JUNK)
    private val drafts = folder(6, FolderRole.DRAFTS)
    private val allFolders = listOf(inbox, sent, archive, trash, junk, drafts)
    private val self = setOf("yo@upm.es")

    private fun build(
        messages: List<MessageDetail>,
        openedId: Long = messages.last().summary.id,
        local: LocalState = LocalState(),
        settings: AppSettings = AppSettings(),
        folders: List<Folder> = allFolders,
    ) = buildMessageUiState(openedId, ThreadLoad.Loaded(messages), local, settings, folders, self)

    @Test
    fun `loading and not found`() {
        assertEquals(ThreadStatus.LOADING, buildMessageUiState(1, ThreadLoad.Loading, LocalState(), AppSettings(), emptyList(), self).status)
        assertEquals(ThreadStatus.NOT_FOUND, buildMessageUiState(1, ThreadLoad.NotFound, LocalState(), AppSettings(), emptyList(), self).status)
    }

    @Test
    fun `every removed message closes the screen`() {
        val state = build(listOf(message(1, inbox)), local = LocalState(removed = setOf(1L)))
        assertEquals(ThreadStatus.CLOSED, state.status)
    }

    @Test
    fun `single message is always expanded and not collapsible`() {
        val state = build(listOf(message(1, inbox)))
        val item = (state.rows.single() as ThreadRowUi.Message).item
        assertTrue(item.expanded)
        assertFalse(item.collapsible)
        assertEquals(BodyState.Loading, item.body)
        assertEquals(1L, state.replyTarget?.id)
    }

    @Test
    fun `inbox conversation actions`() {
        val messages = listOf(message(1, inbox), message(2, sent, from = EmailAddress("yo@upm.es")), message(3, inbox))
        val state = build(messages, openedId = 3)
        assertEquals(ThreadStatus.READY, state.status)
        assertEquals(inbox, state.folder)
        assertTrue(state.canArchive)
        assertFalse(state.deleteIsPermanent)
        assertEquals(2, state.actionCount)
        assertEquals(SpamAction.Report(junk), state.spamAction)
        assertTrue(state.canMarkUnread)
        assertNull(state.openedDraftId)
        val items = state.rows.map { (it as ThreadRowUi.Message).item }
        assertTrue(items[1].fromSelf)
        // Conversation view: deleting one inbox message would take the other one along.
        assertFalse(items[0].canDeleteSingly)
        assertTrue(items[1].canDeleteSingly)
        assertTrue(build(messages, settings = AppSettings(conversationView = false)).rows.all {
            (it as ThreadRowUi.Message).item.canDeleteSingly
        })
    }

    @Test
    fun `archive is hidden in the archive and without an archive folder`() {
        assertFalse(build(listOf(message(1, archive))).canArchive)
        assertFalse(build(listOf(message(1, inbox)), folders = allFolders - archive).canArchive)
    }

    @Test
    fun `junk offers not spam and deletes for good`() {
        val state = build(listOf(message(1, junk)))
        assertEquals(SpamAction.NotSpam(inbox), state.spamAction)
        assertTrue(state.deleteIsPermanent)
        assertNull(build(listOf(message(1, inbox)), folders = allFolders - junk).spamAction)
        assertNull(build(listOf(message(1, sent))).spamAction)
        assertTrue(build(listOf(message(1, inbox)), folders = allFolders - trash).deleteIsPermanent)
    }

    @Test
    fun `opened draft offers edit instead of replies`() {
        val state = build(listOf(message(1, inbox), message(2, drafts)), openedId = 2)
        assertEquals(2L, state.openedDraftId)
        assertEquals(1L, state.replyTarget?.id)
        assertFalse(state.canArchive)
        assertTrue(state.rows.map { (it as ThreadRowUi.Message).item }.last().isDraft)
    }

    @Test
    fun `inline images are hidden from the attachment list`() {
        val inline = attachment(10, messageId = 1, contentId = "logo@x", inline = true)
        val bigInline = attachment(11, messageId = 1, contentId = "big@x", inline = true)
        val pdf = attachment(12, messageId = 1, contentId = null, inline = false, mime = "application/pdf", name = "acta.pdf")
        val messages = listOf(message(1, inbox, attachments = listOf(inline, bigInline, pdf)))

        val before = (build(messages).rows.single() as ThreadRowUi.Message).item
        assertEquals(listOf(12L), before.attachments.map { it.attachment.id })
        assertEquals(AttachmentKind.PDF, before.attachments.single().kind)

        // After rendering, inline images that could not be embedded are listed.
        val rendered = RenderedBody("<html></html>", isHtml = true, hasRemoteContent = false, hiddenAttachmentIds = setOf(10L))
        val after = (build(messages, local = LocalState(rendered = mapOf(1L to rendered))).rows.single() as ThreadRowUi.Message).item
        assertEquals(listOf(11L, 12L), after.attachments.map { it.attachment.id })
        assertEquals(BodyState.Ready(rendered), after.body)
    }

    @Test
    fun `remote images banner follows the settings`() {
        val messages = listOf(message(1, inbox, from = EmailAddress("News@Lists.UPM.es")))
        val rendered = RenderedBody("<html></html>", isHtml = true, hasRemoteContent = true, hiddenAttachmentIds = emptySet())
        val local = LocalState(rendered = mapOf(1L to rendered))
        fun item(settings: AppSettings, state: LocalState = local) =
            (build(messages, local = state, settings = settings).rows.single() as ThreadRowUi.Message).item

        assertTrue(item(AppSettings()).showRemoteImagesBanner)
        assertFalse(item(AppSettings()).remoteImagesAllowed)
        assertTrue(item(AppSettings(loadRemoteImages = true)).remoteImagesAllowed)
        assertTrue(item(AppSettings(trustedImageSenders = setOf("news@lists.upm.es"))).remoteImagesAllowed)
        assertTrue(item(AppSettings(), local.copy(imagesShown = setOf(1L))).remoteImagesAllowed)
        assertFalse(item(AppSettings(), local.copy(imagesShown = setOf(1L))).showRemoteImagesBanner)
    }

    @Test
    fun `body errors show only while the body is missing`() {
        val error = IllegalStateException("offline")
        val missing = (build(listOf(message(1, inbox, bodyLoaded = false)), local = LocalState(bodyErrors = mapOf(1L to error))).rows.single() as ThreadRowUi.Message).item
        assertEquals(BodyState.Error(error), missing.body)
        val loaded = (build(listOf(message(1, inbox)), local = LocalState(bodyErrors = mapOf(1L to error))).rows.single() as ThreadRowUi.Message).item
        assertEquals(BodyState.Loading, loaded.body)
    }

    @Test
    fun `newsletter offers unsubscribe`() {
        val messages = listOf(message(1, inbox, listUnsubscribe = "<https://x.es/u>", from = EmailAddress("n@x.es", "Boletín")))
        val state = build(messages)
        assertEquals(UnsubscribeTarget.Web("https://x.es/u"), state.unsubscribe)
        assertEquals("Boletín", state.unsubscribeSender)
        assertNull(build(listOf(message(1, inbox))).unsubscribe)
    }

    @Test
    fun `star state and collapsed rows`() {
        val messages = (1L..8L).map { message(it, inbox, starred = it == 2L) }
        val state = build(messages, local = LocalState(expanded = setOf(8L)))
        assertTrue(state.isStarred)
        assertTrue(state.rows.any { it is ThreadRowUi.Hidden })
        val expanded = state.rows.filterIsInstance<ThreadRowUi.Message>().filter { it.item.expanded }.map { it.item.id }
        assertEquals(listOf(8L), expanded)
    }

    private fun folder(id: Long, role: FolderRole) = Folder(
        id = id,
        accountId = 1,
        fullName = role.name,
        displayName = role.name,
        role = role,
        depth = 0,
        unreadCount = 0,
        totalCount = 0,
        selectable = true,
        lastSyncedAt = null,
    )

    private fun attachment(
        id: Long,
        messageId: Long,
        contentId: String?,
        inline: Boolean,
        mime: String = "image/png",
        name: String = "img$id.png",
    ) = Attachment(
        id = id,
        messageId = messageId,
        partPath = "2",
        fileName = name,
        mimeType = mime,
        size = 1000,
        contentId = contentId,
        isInline = inline,
        localPath = null,
    )

    private fun message(
        id: Long,
        folder: Folder,
        from: EmailAddress = EmailAddress("ana@upm.es", "Ana"),
        starred: Boolean = false,
        bodyLoaded: Boolean = true,
        attachments: List<Attachment> = emptyList(),
        listUnsubscribe: String? = null,
    ) = MessageDetail(
        summary = MessageSummary(
            id = id,
            accountId = 1,
            folderId = folder.id,
            uid = id,
            threadId = "t",
            subject = "Asunto",
            from = from,
            to = listOf(EmailAddress("yo@upm.es")),
            date = id * 1000,
            snippet = "snippet $id",
            isRead = true,
            isStarred = starred,
            isAnswered = false,
            isForwarded = false,
            isDraft = folder.role == FolderRole.DRAFTS,
            hasAttachments = attachments.isNotEmpty(),
        ),
        cc = emptyList(),
        bcc = emptyList(),
        replyTo = emptyList(),
        messageIdHeader = "<$id@x>",
        inReplyTo = null,
        references = emptyList(),
        bodyHtml = if (bodyLoaded) "<p>Hola $id</p>" else null,
        bodyText = if (bodyLoaded) "Hola $id" else null,
        bodyLoaded = bodyLoaded,
        attachments = attachments,
        listUnsubscribe = listUnsubscribe,
        folderRole = folder.role,
    )
}
