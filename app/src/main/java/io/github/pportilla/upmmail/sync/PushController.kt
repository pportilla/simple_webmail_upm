package io.github.pportilla.upmmail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.data.safety.SafetyPolicy
import io.github.pportilla.upmmail.mail.ImapIdler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Supervised mail uses periodic/manual checks; guardian unlock never enables instant push. */
internal fun pushAllowedBySafety(safety: SafetyPolicy): Boolean =
    safety.state.value.audience == Audience.ADULT &&
        runCatching { safety.requireNetwork() }.isSuccess

/** Also guards retries/keep-alives and cancels a live connection when its profile is revoked. */
internal fun PushConnection.withPushSafety(safety: SafetyPolicy): PushConnection {
    val delegate = this
    return object : PushConnection {
        override suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit) =
            withPushAuthorization(safety) { delegate.run(onIdle, onChange) }

        override suspend fun refresh(): Boolean = withPushAuthorization(safety) { delegate.refresh() }
    }
}

private suspend fun <T> withPushAuthorization(safety: SafetyPolicy, action: suspend () -> T): T = coroutineScope {
    if (!pushAllowedBySafety(safety)) throw CancellationException("Push authorization is unavailable")
    val connectionScope = this
    val authorization = launch(start = CoroutineStart.UNDISPATCHED) {
        safety.state.collect {
            if (!pushAllowedBySafety(safety)) connectionScope.cancel("Push authorization changed")
        }
    }
    try {
        if (!pushAllowedBySafety(safety)) throw CancellationException("Push authorization changed")
        action()
    } finally {
        authorization.cancel()
    }
}

/**
 * Decides when [PushService] runs: while push is enabled in the settings and there is at least
 * one account, and the current adult profile authorizes network access.
 *
 * Android 12+ lets an app start a foreground service only while it is visible or in a few
 * exempt situations (boot, app update, exemption from battery optimisation). Starts are
 * attempted at each such chance (app start, [onAppForeground], [PushBootReceiver], the periodic
 * job) and a refusal is only logged: the periodic check keeps working meanwhile.
 *
 * While the service runs, an inexact alarm that may fire in Doze wakes the device every
 * [ALARM_INTERVAL] to check its connections ([onAlarm]).
 */
class PushController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: MailStore,
    private val settings: SettingsRepository,
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var service: PushService? = null

    /** Follows settings, accounts and safety authorization, including profile transitions. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            combine(
                settings.settings.map { it.pushEnabled }.distinctUntilChanged(),
                store.accounts.observeAll()
                    .map { it.isNotEmpty() }
                    .catch { e ->
                        Log.w(TAG, "Could not read the accounts", e)
                        emit(false)
                    },
                store.safety.state.map { pushAllowedBySafety(store.safety) }.distinctUntilChanged(),
            ) { enabled, hasAccounts, authorized -> enabled && hasAccounts && authorized }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted) startService() else stopService() }
        }
    }

    /** The app became visible: a moment when Android always allows starting the service. */
    fun onAppForeground() {
        scope.launch(Dispatchers.IO) { startIfWanted() }
    }

    /** Starts the service unless it runs already or push is not wanted. Queries the database. */
    fun startIfWanted() {
        if (service == null && isWanted()) startService()
    }

    /** Push is enabled, authorized and there is an account. Queries the database. */
    fun isWanted(): Boolean =
        pushAllowedBySafety(store.safety) && settings.settings.value.pushEnabled &&
            try {
                store.accounts.count() > 0
            } catch (e: Exception) {
                Log.w(TAG, "Could not count the accounts", e)
                false
            }

    private fun startService() {
        // Alarm/foreground callbacks may race with a guardian switching the active profile.
        if (!isWanted()) return
        try {
            context.startForegroundService(Intent(context, PushService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): not allowed from the
            // background right now. The next app start, boot or app update tries again.
            Log.i(TAG, "Push cannot start from the background now", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Push service refused", e)
        }
    }

    private fun stopService() {
        cancelAlarm()
        // Cancel sockets before waiting for Android to dispatch Service.onDestroy().
        service?.stopForSafety()
        try {
            context.stopService(Intent(context, PushService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop the push service", e)
        }
    }

    // ------------------------------------------------------------------ service link

    internal fun onServiceStarted(service: PushService) {
        this.service = service
        if (pushAllowedBySafety(store.safety) && settings.settings.value.pushEnabled) {
            scheduleAlarm()
        } else {
            stopService()
        }
    }

    internal fun onServiceStopped(service: PushService) {
        if (this.service === service) this.service = null
    }

    // ------------------------------------------------------------------ keep-alive alarm

    /**
     * Alarm: checks the connections of the running service (holding the alarm's wake lock
     * until done, at most [ALARM_WORK_TIMEOUT]), or tries to start it if it died.
     */
    suspend fun onAlarm() {
        if (!isWanted()) {
            stopService()
            return
        }
        val running = service
        if (running == null) {
            startIfWanted()
            return
        }
        scheduleAlarm()
        withTimeoutOrNull(ALARM_WORK_TIMEOUT) { running.refreshConnections() }
    }

    private fun scheduleAlarm() {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            // Inexact and allowed while idle: no exact-alarm permission needed. Android may
            // deliver it up to 75% of the delay late (and Doze may space alarms further apart).
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL,
                alarmIntent(),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not schedule the push alarm", e)
        }
    }

    private fun cancelAlarm() {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel the push alarm", e)
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PushAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "PushController"

        /**
         * Time between connection checks while the device sleeps (the idle connection's own checks
         * only count awake time). Delivered up to 75% late, a connection stays silent for at most
         * about 14 minutes, so that mobile networks keep its NAT mapping.
         */
        const val ALARM_INTERVAL = 8 * 60_000L

        /** How long one alarm may keep the device awake. */
        const val ALARM_WORK_TIMEOUT = 25_000L
    }
}
