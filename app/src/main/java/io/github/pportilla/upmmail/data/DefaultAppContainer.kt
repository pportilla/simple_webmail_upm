package io.github.pportilla.upmmail.data

import android.app.Application
import android.util.Log
import io.github.pportilla.upmmail.data.db.AndroidSqlDatabase
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.repository.AccountRepository
import io.github.pportilla.upmmail.data.repository.AccountRepositoryImpl
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.repository.ComposeRepository
import io.github.pportilla.upmmail.data.repository.ComposeRepositoryImpl
import io.github.pportilla.upmmail.data.repository.ContactRepository
import io.github.pportilla.upmmail.data.repository.ContactRepositoryImpl
import io.github.pportilla.upmmail.data.repository.MailRepository
import io.github.pportilla.upmmail.data.repository.MailRepositoryImpl
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.data.security.KeystorePasswordStore
import io.github.pportilla.upmmail.data.security.PasswordStore
import io.github.pportilla.upmmail.data.settings.PrefsSettingsRepository
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.notifications.MailNotifier
import io.github.pportilla.upmmail.sync.AndroidSyncController
import io.github.pportilla.upmmail.sync.FlushResult
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.PushController
import io.github.pportilla.upmmail.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app's object graph, created once in Application.onCreate. Everything heavy is lazy; the
 * database file is only opened by the first query, which always runs off the main thread.
 */
class DefaultAppContainer(private val app: Application) : AppContainer {

    override val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Uncaught error in the application scope", error)
        },
    )

    val files = LocalFiles(app.filesDir)

    val store: MailStore by lazy { MailStore(AndroidSqlDatabase(app)) }

    override val settingsRepository: SettingsRepository by lazy { PrefsSettingsRepository(app) }

    val passwordStore: PasswordStore by lazy { KeystorePasswordStore(app) }

    val clients: MailClientProvider by lazy { MailClientProvider({ store.accounts.get(it) }, passwordStore) }

    val notifier: MailNotifier by lazy {
        MailNotifier(app, store, settingsRepository) { androidSyncController.isForeground }
    }

    val engine: SyncEngine by lazy {
        SyncEngine(
            store = store,
            clients = clients,
            files = files,
            attachmentOpener = AndroidAttachmentOpener(app, files),
            listener = notifier,
        )
    }

    val flushScheduler: FlushScheduler by lazy {
        FlushScheduler(
            scope = applicationScope,
            flush = { accountId -> engine.flushPendingOps(accountId) },
            onBlocked = { _, error ->
                // Offline: let JobScheduler retry once the network is back. Login problems
                // wait for the user (the next sync shows the error).
                if (error.kind == MailErrorKind.NETWORK) androidSyncController.requestPendingOpsFlush()
            },
            onFlushed = { _, result -> refreshTouchedFolders(result) },
        )
    }

    /**
     * Folders that got messages from pushed operations (move targets, Drafts, Sent) are synced,
     * so the moved or uploaded mail shows up there without a manual refresh. Only folders that
     * are being synchronised already; never the INBOX, whose new mail would be notified.
     */
    private fun refreshTouchedFolders(result: FlushResult) {
        if (result.touchedFolders.isEmpty()) return
        applicationScope.launch(Dispatchers.IO) {
            for (folderId in result.touchedFolders) {
                try {
                    val folder = store.folders.get(folderId) ?: continue
                    if (folder.role == FolderRole.INBOX || folder.lastSyncedAt == null) continue
                    engine.syncFolder(folderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The folder's next refresh brings the messages.
                }
            }
        }
    }

    val androidSyncController: AndroidSyncController by lazy {
        AndroidSyncController(
            context = app,
            scope = applicationScope,
            store = store,
            engine = engine,
            settings = settingsRepository,
            flushScheduler = { flushScheduler },
            onForeground = { pushController.onAppForeground() },
        ).also { it.start() }
    }

    val pushController: PushController by lazy {
        PushController(app, applicationScope, store, settingsRepository)
    }

    override val syncController: SyncController get() = androidSyncController

    val mailRepositoryImpl: MailRepositoryImpl by lazy {
        MailRepositoryImpl(
            store = store,
            engine = engine,
            flushScheduler = flushScheduler,
            settings = settingsRepository,
            scope = applicationScope,
            onMessagesRead = { ids -> notifier.cancelForMessages(ids) },
            discardLocalDraft = { draftId -> composeRepositoryImpl.discardLocalDraft(draftId) },
        )
    }

    override val mailRepository: MailRepository get() = mailRepositoryImpl

    override val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(
            store = store,
            passwords = passwordStore,
            clients = clients,
            engine = engine,
            settings = settingsRepository,
            files = files,
            scope = applicationScope,
            onAccountRemoved = { accountId -> notifier.cancelAccount(accountId) },
        )
    }

    override val composeRepository: ComposeRepository get() = composeRepositoryImpl

    val composeRepositoryImpl: ComposeRepositoryImpl by lazy {
        ComposeRepositoryImpl(
            store = store,
            engine = engine,
            flushScheduler = flushScheduler,
            syncController = syncController,
            files = files,
            texts = AndroidComposeTexts(app),
            importer = AndroidAttachmentImporter(app, files),
            onOutboxItemHandled = { outboxId -> notifier.cancelSendFailure(outboxId) },
        )
    }

    override val contactRepository: ContactRepository by lazy {
        ContactRepositoryImpl(store, AndroidDeviceContacts(app))
    }

    private val appLocale = MutableStateFlow(currentLocale())

    /**
     * The language of the texts made outside the screens (notifications, quotes in replies): it
     * changes with the language chosen in the app, the per-app language set in the system
     * settings (Android 13+) or the system language.
     */
    val locale: StateFlow<Locale> = appLocale.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) { housekeeping() }
        // Channel names are shown in the system settings: keep them in the app's language.
        applicationScope.launch(Dispatchers.IO) {
            locale.drop(1).collect { step("notification channels") { notifier.createChannels() } }
        }
    }

    /** Reads the app's language again after a change; returns whether it changed. */
    fun onLocaleChanged(): Boolean {
        val new = currentLocale()
        return appLocale.getAndUpdate { new } != new
    }

    private fun currentLocale(): Locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()

    /** Work for every process start: channels, push, interrupted sends, queued work, stale files. */
    private suspend fun housekeeping() {
        step("notification channels") { notifier.createChannels() }
        step("push") { pushController.start() }
        step("stale sends") { engine.failStaleSending() }
        step("outbox") { androidSyncController.requestOutboxSend() }
        step("pending operations") { store.pendingOps.accountsWithPending().forEach { flushScheduler.schedule(it) } }
        // Drafts and queued messages own their files (see LocalFiles): never deleted here.
        step("attachment cache") {
            val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
            files.cleanupAttachments(referenced) { ids -> store.messages.existingIds(ids) }
            files.trimAttachments(referenced, ATTACHMENT_CACHE_MAX_BYTES, System.currentTimeMillis(), ATTACHMENT_MIN_AGE)
        }
        step("compose files") {
            val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
            files.cleanupCompose(referenced, System.currentTimeMillis(), COMPOSE_FILE_MIN_AGE)
        }
        step("cached bodies") { store.messages.dropOldBodies(BODIES_KEPT_PER_FOLDER) }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Startup step '$name' failed", e)
        }
    }

    private companion object {
        const val TAG = "AppContainer"

        /** Unreferenced compose copies younger than this may belong to an unsaved compose screen. */
        const val COMPOSE_FILE_MIN_AGE = 24 * 60 * 60_000L

        /** Size of the downloaded-attachment cache beyond which the least recently used files go. */
        const val ATTACHMENT_CACHE_MAX_BYTES = 200L * 1024 * 1024

        /** Downloads younger than this may be open in a viewer: never trimmed. */
        const val ATTACHMENT_MIN_AGE = 60 * 60_000L

        /** Downloaded bodies kept per folder (the newest messages); older ones are downloaded again when opened. */
        const val BODIES_KEPT_PER_FOLDER = 1_000
    }
}
