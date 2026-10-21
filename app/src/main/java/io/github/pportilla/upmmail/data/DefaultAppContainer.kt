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
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.notifications.MailNotifier
import io.github.pportilla.upmmail.sync.AndroidSyncController
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.PushController
import io.github.pportilla.upmmail.sync.SyncEngine
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
        )
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

    override val composeRepository: ComposeRepository by lazy {
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
        step("attachment cache") { files.cleanupAttachments { ids -> store.messages.existingIds(ids) } }
        step("compose files") {
            val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
            files.cleanupCompose(referenced, System.currentTimeMillis(), COMPOSE_FILE_MIN_AGE)
        }
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
    }
}
