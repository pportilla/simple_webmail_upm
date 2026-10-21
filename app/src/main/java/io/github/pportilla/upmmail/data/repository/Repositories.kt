package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutgoingAttachment
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * Contracts between the UI and the data layer. Every suspend function is main-safe (it switches
 * to an IO dispatcher internally). Failures are reported as Result.failure(MailException).
 */

interface AccountRepository {
    /** All accounts ordered by sortOrder. */
    val accounts: Flow<List<Account>>

    suspend fun getAccount(id: Long): Account?

    /**
     * Verifies the credentials against IMAP and SMTP and stores the account, then starts its
     * first sync in the background.
     *
     * When [imap] / [smtp] are null the UPM presets for [email] are used and the login name is
     * auto-detected (each candidate of UpmPresets is tried in order). When they are given they are
     * used verbatim.
     */
    suspend fun addAccount(
        email: String,
        password: String,
        displayName: String,
        imap: ServerSettings? = null,
        smtp: ServerSettings? = null,
    ): Result<Account>

    /**
     * Saves edited account details. When [newPassword] is non-null it replaces the stored one.
     * When [verify] is true the (possibly new) server settings are tested first and nothing is
     * saved if the login fails.
     */
    suspend fun updateAccount(account: Account, newPassword: String? = null, verify: Boolean = true): Result<Account>

    /**
     * Deletes the account, its cached mail, downloaded attachments and stored password. With the
     * last account, the address history and the recent searches go too.
     */
    suspend fun removeAccount(id: Long)
}

/** Opaque handle returned by reversible actions (archive, delete, move). */
data class UndoHandle(val id: Long, val count: Int)

interface MailRepository {
    /** Folders of the account: INBOX first, then the other roles, then the rest alphabetically. */
    fun observeFolders(accountId: Long): Flow<List<Folder>>

    suspend fun getFolder(folderId: Long): Folder?

    suspend fun findFolderByRole(accountId: Long, role: FolderRole): Folder?

    /**
     * Newest-first messages of a folder, at most [limit] rows. With [conversations] = true one row
     * is returned per thread (its newest message) and MessageSummary.threadCount is filled in.
     * Messages hidden by a pending (undoable) move are excluded. A Drafts folder also lists the
     * drafts saved only on this device (not uploaded yet, or the upload failed), in place of the
     * older server copy they replace; [ComposeRepository.draftFromServerMessage] opens such a row.
     */
    fun observeMessages(folderId: Long, limit: Int, conversations: Boolean): Flow<List<MessageSummary>>

    fun observeMessage(messageId: Long): Flow<MessageDetail?>

    /**
     * Every cached message of the thread [messageId] belongs to, oldest first, across the
     * account's folders. Trash and Junk copies are excluded unless [messageId] itself lives there.
     */
    fun observeThread(messageId: Long): Flow<List<MessageDetail>>

    /** Folder ids currently being synchronised, for progress indicators. */
    val syncingFolders: StateFlow<Set<Long>>

    /** Pushes pending local changes, then fetches new mail and flag changes for one folder. */
    suspend fun refreshFolder(folderId: Long): Result<Unit>

    /** Refreshes the folder list, sends the outbox and refreshes the INBOX of the account. */
    suspend fun refreshAccount(accountId: Long): Result<Unit>

    /**
     * Downloads up to [count] messages older than the oldest cached one.
     * Returns true if older messages may still exist on the server.
     */
    suspend fun loadOlder(folderId: Long, count: Int = 50): Result<Boolean>

    /** Downloads the body and attachment list if they are not cached yet. */
    suspend fun ensureBodyLoaded(messageId: Long): Result<Unit>

    suspend fun setRead(messageIds: List<Long>, read: Boolean)

    suspend fun setStarred(messageIds: List<Long>, starred: Boolean)

    /** Moves to the account's Archive folder. Fails with NOT_FOUND if the account has none. */
    suspend fun archive(messageIds: List<Long>): Result<UndoHandle>

    /**
     * Moves to Trash. Messages already in Trash or Junk are deleted permanently instead and the
     * returned handle is null (nothing to undo); so are listed drafts only on this device.
     */
    suspend fun delete(messageIds: List<Long>): Result<UndoHandle?>

    suspend fun moveTo(messageIds: List<Long>, folderId: Long): Result<UndoHandle>

    /** Reverts an archive / delete / move if it has not reached the server yet (a few seconds). */
    suspend fun undo(handle: UndoHandle)

    suspend fun markFolderRead(folderId: Long)

    /** Permanently deletes everything in a Trash or Junk folder. */
    suspend fun emptyFolder(folderId: Long): Result<Unit>

    /** Downloads the attachment if needed and returns the local copy. */
    suspend fun downloadAttachment(attachmentId: Long): Result<File>

    /** Instant search over the local cache (subject, people, snippet and cached bodies). */
    suspend fun searchLocal(query: String, filters: SearchFilters, limit: Int = 200): List<MessageSummary>

    /**
     * Server-side IMAP SEARCH (subject, from, to and body) in [folderId], or in the INBOX when
     * null. Matches are added to the local cache and returned newest first.
     */
    suspend fun searchServer(query: String, accountId: Long, folderId: Long?, limit: Int = 50): Result<List<MessageSummary>>

    suspend fun recentSearches(): List<String>

    suspend fun addRecentSearch(query: String)

    suspend fun clearRecentSearches()
}

interface ComposeRepository {
    /**
     * Builds the initial draft for the compose screen: recipients, subject prefix, quoted text
     * and the account signature, according to [mode]. [sourceMessageId] is required for replies
     * and forwards (its body is downloaded if needed). Forwards carry the original attachments.
     */
    suspend fun createDraft(accountId: Long, mode: ComposeMode, sourceMessageId: Long?): Result<Draft>

    /** A locally saved draft, or null if it no longer exists. */
    suspend fun loadDraft(draftId: Long): Draft?

    /**
     * Opens a row of the Drafts folder for editing: a server draft (mode EDIT_DRAFT), or the local
     * draft the row stands for. A server draft with a version saved on this device opens that one.
     */
    suspend fun draftFromServerMessage(messageId: Long): Result<Draft>

    /**
     * Saves the draft locally and returns its id. With [upload] = true it is also stored in the
     * server Drafts folder (replacing the previous server copy) in the background.
     */
    suspend fun saveDraft(draft: Draft, upload: Boolean): Long

    /** Deletes the local draft and its server copy, if any. */
    suspend fun discardDraft(draft: Draft)

    /**
     * Queues the message, deletes the local draft and returns the outbox id. Sending happens after
     * [delaySeconds] (the undo window), or as soon as the network is available after that.
     */
    suspend fun send(draft: Draft, delaySeconds: Int): Long

    /** Cancels a queued message still inside its undo window and returns its draft, else null. */
    suspend fun cancelSend(outboxId: Long): Draft?

    /** Messages waiting in or failed from the outbox; all accounts when [accountId] is null. */
    fun observeOutbox(accountId: Long?): Flow<List<OutboxItem>>

    suspend fun retrySend(outboxId: Long)

    suspend fun deleteOutboxItem(outboxId: Long)

    /** Reads name, size and type of a picked/shared content URI; null if unreadable. */
    suspend fun describeAttachment(uri: String): OutgoingAttachment?
}

interface ContactRepository {
    /**
     * Address suggestions for [query] (matched against names and addresses, by word prefix),
     * ranked by how often and how recently they were used. Device contacts are included when the
     * READ_CONTACTS permission is granted. Addresses in [exclude] (lower-case) are skipped.
     */
    suspend fun suggest(query: String, exclude: Set<String> = emptySet(), limit: Int = 8): List<ContactSuggestion>
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun update(transform: (AppSettings) -> AppSettings)
}

interface SyncController {
    /** (Re)schedules the periodic background check according to the current settings. */
    fun schedulePeriodicSync()

    /** Called when the app becomes visible: syncs now and keeps polling the INBOX while visible. */
    fun onAppForeground()

    fun onAppBackground()

    /** Makes sure queued outbox messages are sent once due and the network is available. */
    fun requestOutboxSend()
}

/** Manual dependency container, created once by the Application. */
interface AppContainer {
    val accountRepository: AccountRepository
    val mailRepository: MailRepository
    val composeRepository: ComposeRepository
    val contactRepository: ContactRepository
    val settingsRepository: SettingsRepository
    val syncController: SyncController

    /** Lives as long as the process; for work that must outlive a screen (undo, saving drafts). */
    val applicationScope: CoroutineScope
}
