package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.queryLong
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.repository.ComposeRepositoryImpl
import io.github.pportilla.upmmail.data.repository.LocalDraftRows
import io.github.pportilla.upmmail.data.repository.MailRepositoryImpl
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MessageSummary
import jakarta.mail.Flags
import jakarta.mail.Folder as ImapFolder
import jakarta.mail.Multipart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drafts and queued mail keep their content and attachments, and saved drafts stay findable. */
class DraftsAndFilesTest : SyncTestBase() {

    private val pdf = ByteArray(3_000) { (it % 251).toByte() }

    private fun repositories(): Pair<ComposeRepositoryImpl, MailRepositoryImpl> {
        val compose = composeRepository()
        val scheduler = FlushScheduler(scope, { engine.flushPendingOps(it) }, clock = clock::now)
        val mail = MailRepositoryImpl(
            store, engine, scheduler, settings, scope,
            discardLocalDraft = { compose.discardLocalDraft(it) },
            clock = clock::now,
        )
        return compose to mail
    }

    private suspend fun draftsFolder(): Folder {
        engine.syncFolderList(account.id)
        return store.folders.findByRole(account.id, FolderRole.DRAFTS)!!
    }

    private suspend fun listed(mail: MailRepositoryImpl, folder: Folder): List<MessageSummary> =
        mail.observeMessages(folder.id, 50, conversations = false).first()

    private fun offline() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        store.accounts.update(account.copy(imap = account.imap.copy(port = closedPort)))
    }

    private fun online() = store.accounts.update(account)

    /** (subject, attachment file names) of the messages in a server folder. */
    private fun received(folderName: String, user: String): List<Pair<String, List<String>>> = withServer(user) { server ->
        val folder = server.getFolder(folderName)
        folder.open(ImapFolder.READ_ONLY)
        try {
            folder.messages.map { message ->
                val content = message.content
                val names = if (content is Multipart) (0 until content.count).mapNotNull { content.getBodyPart(it).fileName } else emptyList()
                message.subject to names
            }
        } finally {
            folder.close(false)
        }
    }

    @Test
    fun forwardedAttachmentsOutliveTheOriginalAndTheAttachmentCache() = runBlocking {
        appendMessages("INBOX", listOf(message("Informe", attachment = "informe.pdf" to pdf, messageId = "<informe@test>")))
        engine.syncFolderList(account.id)
        val inbox = engine.inboxOf(account.id).id
        engine.syncFolder(inbox)
        val source = store.messages.list(inbox, 10, false).single()
        val compose = composeRepository()

        val forward = compose.createDraft(account.id, ComposeMode.FORWARD, source.id).getOrThrow()
        val attached = forward.attachments.single()
        assertTrue("owned by the draft, not the cache", files.isComposeFile(LocalFiles.fileOf(attached.uri)!!))
        assertEquals(pdf.size.toLong(), attached.size)
        val bob = listOf(EmailAddress("bob@upm.es"))
        val draftId = compose.saveDraft(forward.copy(to = bob), upload = false)
        val again = compose.createDraft(account.id, ComposeMode.FORWARD, source.id).getOrThrow()
        compose.send(again.copy(to = bob, subject = "Fwd: Informe (cola)"), delaySeconds = 30)

        // The original is archived (its row goes), the process restarts and cleans up its files.
        store.messages.deleteIds(listOf(source.id))
        val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
        files.cleanupAttachments(referenced) { store.messages.existingIds(it) }
        files.trimAttachments(referenced, maxBytes = 0, now = clock.now() + 86_400_000L, minAgeMillis = 0)
        files.cleanupCompose(referenced, now = clock.now() + 3 * 86_400_000L, minAgeMillis = 86_400_000L)
        assertTrue("the attachment cache is empty", files.attachmentsDir.walkTopDown().none { it.isFile })

        clock.advance(31_000)
        assertEquals(1, engine.sendOutbox().sent)
        compose.send(compose.loadDraft(draftId)!!, delaySeconds = 0)
        assertEquals(1, engine.sendOutbox().sent)
        val inBob = received("INBOX", "bob").sortedBy { it.first }
        assertEquals(listOf("Fwd: Informe" to listOf("informe.pdf"), "Fwd: Informe (cola)" to listOf("informe.pdf")), inBob)
        assertTrue("sent copies' files are released", files.composeDir.listFiles().orEmpty().none { it.walkTopDown().any(java.io.File::isFile) })
    }

    @Test
    fun anEditedServerDraftIsListedAgainRightAfterItsUpload() = runBlocking {
        createFolder("Drafts")
        appendMessages("Drafts", listOf(message("Borrador", from = anaEmail, to = "bob@upm.es", messageId = "<d1@test>")), Flags(Flags.Flag.DRAFT))
        val drafts = draftsFolder()
        engine.syncFolder(drafts.id)
        val row = store.messages.list(drafts.id, 10, false).single()
        val (compose, mail) = repositories()

        val opened = compose.draftFromServerMessage(row.id).getOrThrow()
        assertEquals(0L, opened.id)
        compose.saveDraft(opened.copy(subject = "Borrador editado"), upload = true)
        engine.flushPendingOps(account.id)

        assertEquals(listOf("Borrador editado"), serverMessages("Drafts").map { it.first })
        // No sync of the folder needed: the new copy is in the cache, the old one is gone.
        assertEquals(listOf("Borrador editado"), store.messages.list(drafts.id, 10, false).map { it.subject })
        val shown = listed(mail, drafts).single()
        assertEquals("Borrador editado", shown.subject)
        assertNull("the server copy stands for the draft", LocalDraftRows.draftId(shown.id))
        engine.syncFolder(drafts.id)
        assertEquals(listOf(shown.id), store.messages.list(drafts.id, 10, false).map { it.id })
    }

    @Test
    fun aDraftEditedOfflineIsListedAndReopenedWithItsChanges() = runBlocking {
        createFolder("Drafts")
        appendMessages("Drafts", listOf(message("Versión 1", body = "Texto 1", from = anaEmail, to = "bob@upm.es")), Flags(Flags.Flag.DRAFT))
        val drafts = draftsFolder()
        engine.syncFolder(drafts.id)
        val row = store.messages.list(drafts.id, 10, false).single()
        val (compose, mail) = repositories()
        val opened = compose.draftFromServerMessage(row.id).getOrThrow()

        offline()
        compose.saveDraft(opened.copy(subject = "Versión 2", body = "Texto 2"), upload = true)
        assertEquals(MailErrorKind.NETWORK, engine.flushPendingOps(account.id).blockedBy?.kind)

        // The new version is listed in place of the server copy it replaces.
        val shown = listed(mail, drafts).single()
        assertEquals("Versión 2", shown.subject)
        assertNotNull(LocalDraftRows.draftId(shown.id))
        // Opening it, or the server copy (from a search), continues the local version.
        assertEquals("Texto 2", compose.draftFromServerMessage(row.id).getOrThrow().body)
        val reopened = compose.draftFromServerMessage(shown.id).getOrThrow()
        assertEquals("Texto 2", reopened.body)
        // The reopened screen saves without a local id: the same draft is updated, not a second one.
        val saved = compose.saveDraft(reopened.copy(id = 0, subject = "Versión 3"), upload = true)
        assertEquals(LocalDraftRows.draftId(shown.id), saved)
        assertEquals(1L, store.sql.queryLong("SELECT COUNT(*) AS v FROM drafts"))
        assertEquals(listOf("Versión 3"), listed(mail, drafts).map { it.subject })

        online()
        engine.flushPendingOps(account.id)
        assertEquals("one copy on the server, the newest", listOf("Versión 3"), serverMessages("Drafts").map { it.first })
        val synced = listed(mail, drafts).single()
        assertEquals("Versión 3", synced.subject)
        assertNull(LocalDraftRows.draftId(synced.id))
    }

    @Test
    fun aDraftWhoseUploadFailsStaysListedAndIsUploadedWhenSavedAgain() = runBlocking {
        // No Drafts folder yet: the upload cannot succeed.
        engine.syncFolderList(account.id)
        val (compose, mail) = repositories()
        val id = compose.saveDraft(draftTo("bob@upm.es", "Sin carpeta"), upload = true)
        val result = engine.flushPendingOps(account.id)
        assertNull(result.blockedBy)
        assertNotNull("the draft is kept", compose.loadDraft(id))
        assertTrue("not retried by every flush", store.pendingOps.forAccount(account.id).isEmpty())
        assertTrue(store.pendingOps.accountsWithPending().isEmpty())

        createFolder("Drafts")
        val drafts = draftsFolder()
        engine.syncFolder(drafts.id)
        val shown = listed(mail, drafts).single()
        assertEquals("Sin carpeta", shown.subject)
        assertEquals(id, LocalDraftRows.draftId(shown.id))

        val reopened = compose.draftFromServerMessage(shown.id).getOrThrow()
        compose.saveDraft(reopened.copy(id = 0), upload = true)
        engine.flushPendingOps(account.id)
        assertEquals(listOf("Sin carpeta"), serverMessages("Drafts").map { it.first })
        assertNull(LocalDraftRows.draftId(listed(mail, drafts).single().id))
    }

    @Test
    fun deletingADraftOnlyOnThisDeviceDiscardsIt() = runBlocking {
        createFolder("Drafts")
        val drafts = draftsFolder()
        engine.syncFolder(drafts.id)
        val (compose, mail) = repositories()
        val id = compose.saveDraft(draftTo("bob@upm.es", "Local"), upload = false)
        val shown = listed(mail, drafts).single()
        assertEquals(id, LocalDraftRows.draftId(shown.id))
        mail.setStarred(listOf(shown.id), true)
        assertTrue("other actions do not apply to it", store.pendingOps.forAccount(account.id).isEmpty())

        assertNull("nothing to undo", mail.delete(listOf(shown.id)).getOrThrow())
        assertNull(compose.loadDraft(id))
        assertTrue(listed(mail, drafts).isEmpty())
    }
}
