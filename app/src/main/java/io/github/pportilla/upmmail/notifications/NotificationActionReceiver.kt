package io.github.pportilla.upmmail.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.DefaultAppContainer
import io.github.pportilla.upmmail.ui.navigation.DeepLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Mark as read", "Archive" and "Delete" buttons of new-mail notifications. The
 * change is applied to the cache right away (inside goAsync's time budget) and pushed to the
 * server by the app's flush scheduler, with a network-constrained job as a fallback.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val messageId = intent.getLongExtra(DeepLinks.EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val container = (context.applicationContext as? UpmMailApp)?.container as? DefaultAppContainer ?: return
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.notifier.cancelForMessages(listOf(messageId))
                val repository = container.mailRepositoryImpl
                when (action) {
                    ACTION_MARK_READ -> repository.markReadNow(messageId)
                    ACTION_ARCHIVE -> repository.archiveOrDeleteNow(messageId, archive = true)
                    ACTION_DELETE -> repository.archiveOrDeleteNow(messageId, archive = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notification action $action failed for message $messageId", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationAction"

        const val ACTION_MARK_READ = "io.github.pportilla.upmmail.action.MARK_READ"
        const val ACTION_ARCHIVE = "io.github.pportilla.upmmail.action.ARCHIVE"
        const val ACTION_DELETE = "io.github.pportilla.upmmail.action.DELETE"

        private val ACTIONS = setOf(ACTION_MARK_READ, ACTION_ARCHIVE, ACTION_DELETE)
    }
}
