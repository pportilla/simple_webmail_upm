package io.github.pportilla.upmmail.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.DefaultAppContainer
import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.ui.navigation.DeepLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles the "Mark as read", "Archive" and "Delete" buttons of new-mail notifications. The
 * change is applied to the cache right away and pushed to the server, both inside goAsync's time
 * budget: the process may be frozen or killed right after. If the server cannot be reached in
 * time, a network-constrained job pushes it later.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val messageId = intent.getLongExtra(DeepLinks.EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val container = (context.applicationContext as? UpmMailApp)?.container as? DefaultAppContainer ?: return
        if (container.safetyRepository.state.value.audience != Audience.ADULT) return
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.notifier.cancelForMessages(listOf(messageId))
                if (container.safetyRepository.state.value.audience != Audience.ADULT) return@launch
                container.safetyRepository.requireNetwork()
                val message = container.store.messages.detail(messageId) ?: return@launch
                if (container.safetyRepository.isSenderBlocked(message.summary.from?.address)) return@launch
                val accountId = intent.getLongExtra(DeepLinks.EXTRA_ACCOUNT_ID, -1L).takeIf { it >= 0 }
                    ?: container.store.messages.ref(messageId)?.accountId
                val repository = container.mailRepositoryImpl
                when (action) {
                    ACTION_MARK_READ -> repository.markReadNow(messageId)
                    ACTION_ARCHIVE -> repository.archiveOrDeleteNow(messageId, archive = true)
                    ACTION_DELETE -> repository.archiveOrDeleteNow(messageId, archive = false)
                }
                if (accountId != null) pushNow(container, accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notification action $action failed for message $messageId", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Pushes the change while the broadcast keeps the process alive; what does not get through
     * in time is left to the pending-operations job. The flush itself is never interrupted.
     */
    private suspend fun pushNow(container: DefaultAppContainer, accountId: Long) {
        val flush = container.applicationScope.async(Dispatchers.IO) { container.engine.flushPendingOps(accountId) }
        val result = withTimeoutOrNull(FLUSH_WAIT) {
            try {
                flush.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Pushing the notification action failed: ${e.message}")
                null
            }
        }
        if (result == null || result.blockedBy != null) container.androidSyncController.requestPendingOpsFlush()
    }

    companion object {
        private const val TAG = "NotificationAction"

        /** How long the broadcast waits for the server (goAsync allows about 10 seconds). */
        private const val FLUSH_WAIT = 7_000L

        const val ACTION_MARK_READ = "io.github.pportilla.upmmail.action.MARK_READ"
        const val ACTION_ARCHIVE = "io.github.pportilla.upmmail.action.ARCHIVE"
        const val ACTION_DELETE = "io.github.pportilla.upmmail.action.DELETE"

        private val ACTIONS = setOf(ACTION_MARK_READ, ACTION_ARCHIVE, ACTION_DELETE)
    }
}
