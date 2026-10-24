package io.github.pportilla.upmmail.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.pportilla.upmmail.MainActivity
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.DefaultAppContainer
import io.github.pportilla.upmmail.mail.ImapIdler
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.notifications.MailNotifier
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Instant push: a foreground service that keeps an IMAP IDLE connection to the INBOX of every
 * account open (one [PushWatcher] each) while push is enabled. Every change a server reports
 * leads to a sync of that INBOX through the sync engine, the same path as the background check,
 * so new-mail notifications behave exactly as before.
 *
 * Connections pause while offline and restart at once on a new network, and restart when
 * accounts, server settings or passwords change. The service stops itself when push is turned
 * off, the last account is removed, or the active profile stops authorizing adult push.
 * Its ongoing notification reports generic connection status on a silent channel.
 */
class PushService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Push failed", error)
        },
    )
    private var container: DefaultAppContainer? = null
    private val watchers = ConcurrentHashMap<Long, PushWatcher>()
    private val status = MutableStateFlow<Status>(Status.Connecting)
    private val connectivity = MutableStateFlow(Connectivity.UNKNOWN)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var networkBlocked = false

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as? UpmMailApp)?.container as? DefaultAppContainer
        if (container == null) {
            stopSelf()
            return
        }
        this.container = container
        if (!pushAllowedBySafety(container.store.safety) || !container.settingsRepository.settings.value.pushEnabled) {
            stopForSafety()
            return
        }
        // The channel must exist before the first startForeground().
        try {
            container.notifier.createChannels()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not create the notification channels", e)
        }
        if (!enterForeground()) return
        container.pushController.onServiceStarted(this)
        if (stopping) return
        followNetwork()
        scope.launch { superviseConnections(container) }
        // On the main thread, like onDestroy: no update can land after the notification is gone.
        // Shown again after a language change.
        scope.launch(Dispatchers.Main) {
            combine(status, container.locale, container.store.safety.state) { current, _, _ -> current }
                .collect { showStatus(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not set up (onCreate could not go to the foreground): already stopping.
        val current = container ?: return START_NOT_STICKY
        if (stopping || !pushAllowedBySafety(current.store.safety) || !current.settingsRepository.settings.value.pushEnabled) {
            stopForSafety()
            return START_NOT_STICKY
        }
        // Every startForegroundService() needs a startForeground(), even for a running service;
        // a sticky restart (null intent) needs one as well.
        return if (enterForeground()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        container?.pushController?.onServiceStopped(this)
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not unregister the network callback", e)
            }
        }
        scope.cancel()
        watchers.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Checks every connection now (keep-alive alarm, network usable again); returns when done. */
    suspend fun refreshConnections() {
        val current = container ?: return
        if (stopping || !pushAllowedBySafety(current.store.safety)) {
            stopForSafety()
            return
        }
        coroutineScope {
            watchers.values.toList().forEach { watcher -> launch { watcher.refresh() } }
        }
    }

    /** End in-flight IDLE sockets before Android's asynchronous stop-service callback. */
    internal fun stopForSafety() {
        stopping = true
        scope.cancel()
        watchers.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ foreground

    private fun enterForeground(): Boolean =
        try {
            val notification = buildNotification(status.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: RuntimeException) {
            // Android 12+ refuses when the start came from the background without an exemption
            // (ForegroundServiceStartNotAllowedException), e.g. after a sticky restart.
            Log.w(TAG, "Push cannot run in the foreground now", e)
            stopSelf()
            false
        }

    private fun showStatus(status: Status) {
        if (stopping) return
        val current = container ?: return
        if (!pushAllowedBySafety(current.store.safety)) {
            stopForSafety()
            return
        }
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(status))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not update the push notification", e)
        }
    }

    private fun buildNotification(status: Status): Notification {
        // The application's texts follow the language chosen in the app; on Android 12 and
        // older the service's own resources stay in the system's language.
        val texts = applicationContext
        // Keep this notification free of account identifiers even while an adult profile is
        // active: a profile transition can happen between building and posting it.
        val title = when (status) {
            Status.Connecting -> texts.getString(R.string.notif_push_connecting)
            Status.Waiting -> texts.getString(R.string.notif_push_waiting)
            Status.Reconnecting -> texts.getString(R.string.notif_push_reconnecting)
            Status.Offline -> texts.getString(R.string.notif_push_offline)
            Status.LoginFailed -> texts.getString(R.string.app_name)
        }
        val text = texts.getString(
            if (status == Status.LoginFailed) R.string.notif_push_login_failed_hint else R.string.notif_push_hint,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, MailNotifier.CHANNEL_PUSH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ------------------------------------------------------------------ connections

    /** Runs one watcher per account, restarting them all whenever their inputs change. */
    private suspend fun superviseConnections(container: DefaultAppContainer) {
        val accounts = container.store.accounts.observeAll()
            .map { list -> list.map { PushAccount(it.id, it.email, it.imap) } }
            .catch { e ->
                Log.w(TAG, "Could not read the accounts", e)
                emit(emptyList())
            }
        // A changed password does not show in the account list; the client provider tells.
        val logins = MutableStateFlow(0)
        scope.launch { container.clients.invalidated.collect { logins.update { it + 1 } } }
        combine(
            container.settingsRepository.settings.map { it.pushEnabled },
            accounts,
            connectivity,
            logins,
            container.store.safety.state,
        ) { enabled, list, network, loginChanges, safety ->
            Plan(enabled && pushAllowedBySafety(container.store.safety), list, network, loginChanges, safety.revision)
        }
            .distinctUntilChanged()
            .collectLatest { plan ->
                watchers.clear()
                when {
                    !plan.enabled || plan.accounts.isEmpty() -> stopForSafety()
                    !plan.network.online -> status.value = Status.Offline
                    else -> runWatchers(container, plan.accounts)
                }
            }
    }

    private suspend fun runWatchers(container: DefaultAppContainer, accounts: List<PushAccount>) {
        coroutineScope {
            val running = accounts.mapNotNull { account ->
                if (!pushAllowedBySafety(container.store.safety)) throw CancellationException("Push authorization changed")
                val idler = try {
                    container.clients.idler(account.id)
                } catch (e: Exception) {
                    // Removed in the meantime; the account list is about to change.
                    Log.d(TAG, "No push for account ${account.id}: ${e.message}")
                    return@mapNotNull null
                }
                val watcher = PushWatcher(
                    connection = idler.asPushConnection().withPushSafety(container.store.safety),
                    sync = { force -> syncInbox(container, account.id, force) },
                    // While the app is visible, read and moved messages are mostly the user's own
                    // doing (the visible INBOX is polled anyway); in the background they let
                    // notifications of mail read elsewhere go away.
                    isRelevant = { change ->
                        change == ImapIdler.Change.NEW_MESSAGES || !container.androidSyncController.isForeground
                    },
                    keepAwake = wakeful("UpmMail:push-${account.id}"),
                    onFailure = { e -> Log.d(TAG, "Push for account ${account.id}: ${e.kind} ${e.message}") },
                )
                watchers[account.id] = watcher
                launch { watcher.run() }
                account to watcher
            }
            if (running.isEmpty()) return@coroutineScope
            combine(running.map { (account, watcher) -> watcher.state.map { account.email to it } }) { states ->
                statusOf(states.toList())
            }.collect { status.value = it }
        }
    }

    /** The INBOX sync of the background check; a poll (not forced) just after another sync is skipped. */
    private suspend fun syncInbox(container: DefaultAppContainer, accountId: Long, force: Boolean) {
        if (!pushAllowedBySafety(container.store.safety)) throw CancellationException("Push authorization changed")
        val engine = container.engine
        val inbox = engine.inboxOf(accountId)
        val age = inbox.lastSyncedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        if (force || age > RECENT_SYNC) engine.syncFolder(inbox.id)
    }

    /** Holds a partial wake lock (with a timeout, in case a sync hangs) while a sync runs. */
    private fun wakeful(tag: String): suspend (suspend () -> Unit) -> Unit {
        val lock = try {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply { setReferenceCounted(false) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No wake lock for push", e)
            null
        }
        return { block ->
            lock?.acquire(SYNC_WAKE_LOCK_TIMEOUT)
            try {
                block()
            } finally {
                try {
                    if (lock?.isHeld == true) lock.release()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not release the wake lock", e)
                }
            }
        }
    }

    private fun statusOf(states: List<Pair<String, PushState>>): Status {
        if (states.any { it.second == PushState.LOGIN_FAILED }) return Status.LoginFailed
        return when {
            states.any { it.second == PushState.RETRYING } -> Status.Reconnecting
            states.any { it.second == PushState.CONNECTING } -> Status.Connecting
            else -> Status.Waiting
        }
    }

    // ------------------------------------------------------------------ network

    /** Tracks the default network: offline pauses push, a new network restarts it at once. */
    private fun followNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.value = Connectivity(online = true, network = network)
            }

            override fun onLost(network: Network) {
                connectivity.update { current -> if (current.network == network) Connectivity.OFFLINE else current }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                // Doze or Data Saver blocked the app's traffic for a while: check at once that
                // the connections survived.
                val wasBlocked = networkBlocked
                networkBlocked = blocked
                if (wasBlocked && !blocked) {
                    scope.launch { withTimeoutOrNull(PushController.ALARM_WORK_TIMEOUT) { refreshConnections() } }
                }
            }
        }
        try {
            val active = manager.activeNetwork
            connectivity.value = if (active == null) {
                Connectivity.OFFLINE
            } else {
                Connectivity(online = true, network = active)
            }
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot follow the network; assuming it is available", e)
            connectivity.value = Connectivity.UNKNOWN
        }
    }

    /** The default network, or none when offline. */
    private data class Connectivity(val online: Boolean, val network: Network?) {
        companion object {
            val UNKNOWN = Connectivity(online = true, network = null)
            val OFFLINE = Connectivity(online = false, network = null)
        }
    }

    /** What a push connection depends on: a change restarts it. */
    private data class PushAccount(val id: Long, val email: String, val imap: ServerSettings)

    private data class Plan(
        val enabled: Boolean,
        val accounts: List<PushAccount>,
        val network: Connectivity,
        val loginChanges: Int,
        val safetyRevision: Long,
    )

    /** What the ongoing notification says. */
    private sealed interface Status {
        data object Connecting : Status

        data object Waiting : Status

        data object Reconnecting : Status

        data object Offline : Status

        data object LoginFailed : Status
    }

    private companion object {
        const val TAG = "PushService"
        const val NOTIFICATION_ID = 1

        /** A poll is skipped when the INBOX was synced this recently. */
        const val RECENT_SYNC = 60_000L

        const val SYNC_WAKE_LOCK_TIMEOUT = 2 * 60_000L
    }
}
