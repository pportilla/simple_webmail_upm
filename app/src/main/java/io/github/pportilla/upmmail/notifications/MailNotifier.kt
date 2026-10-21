package io.github.pportilla.upmmail.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import io.github.pportilla.upmmail.MainActivity
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.sync.SyncListener
import io.github.pportilla.upmmail.ui.components.messageRes
import io.github.pportilla.upmmail.ui.navigation.DeepLinks

/**
 * New-mail and send-failure notifications, with framework APIs only.
 *
 * Each new message gets its own notification (sender, subject, preview, "Mark as read" and
 * "Archive"/"Delete" actions), grouped per account under an InboxStyle summary. New-mail
 * notifications are only posted while the app is in the background, notifications are enabled
 * in the app settings and for the account, and (Android 13+) the permission is granted.
 *
 * Methods may query the database: call them off the main thread.
 */
class MailNotifier(
    private val context: Context,
    private val store: MailStore,
    private val settings: SettingsRepository,
    private val isAppInForeground: () -> Boolean,
) : SyncListener {

    private val manager: NotificationManager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    /**
     * Creates (or renames, after a language change) the notification channels, including the
     * silent one of the push service's ongoing notification.
     */
    fun createChannels() {
        val newMail = NotificationChannel(
            CHANNEL_NEW_MAIL,
            context.getString(R.string.notif_channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_new_mail_description)
            setShowBadge(true)
        }
        val failures = NotificationChannel(
            CHANNEL_SEND_FAILURES,
            context.getString(R.string.notif_channel_send_failures),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_send_failures_description)
        }
        // Minimum importance: no sound, no status bar icon, collapsed at the bottom of the shade.
        val push = NotificationChannel(
            CHANNEL_PUSH,
            context.getString(R.string.notif_channel_push),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.notif_channel_push_description)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(newMail, failures, push))
    }

    // ------------------------------------------------------------------ new mail

    override fun onNewMessages(accountId: Long, folderId: Long, messages: List<MessageSummary>) {
        if (messages.isEmpty() || isAppInForeground() || !canNotify()) return
        val account = store.accounts.get(accountId) ?: return
        if (!account.notificationsEnabled) return
        val hasArchive = store.folders.findByRole(accountId, FolderRole.ARCHIVE) != null
        val posted = messages.sortedByDescending { it.date }.take(MAX_INDIVIDUAL).sortedBy { it.date }.map { message ->
            val notification = buildMessageNotification(account, message, hasArchive)
            post(TAG_MESSAGE, notificationId(message.id), notification)
            Child(notificationId(message.id), message.date, senderOf(notification), subjectOf(notification))
        }
        updateSummary(account, alert = true, added = posted)
    }

    override fun onInboxSynced(accountId: Long, folderId: Long) {
        pruneRead(accountId)
    }

    /** Dismisses the notifications of messages that were read (or removed) in the app. */
    fun cancelForMessages(messageIds: Collection<Long>) {
        if (messageIds.isEmpty()) return
        val wanted = messageIds.mapTo(HashSet()) { notificationId(it) }
        val affected = HashSet<Long>()
        for (active in activeMessageNotifications()) {
            if (active.id in wanted) {
                manager.cancel(active.tag, active.id)
                accountOf(active)?.let { affected += it }
            }
        }
        affected.forEach { accountId ->
            store.accounts.get(accountId)?.let { updateSummary(it, alert = false, removed = wanted) }
        }
    }

    /** Removes every notification of an account (e.g. after it was removed). */
    fun cancelAccount(accountId: Long) {
        activeMessageNotifications().filter { accountOf(it) == accountId }.forEach { manager.cancel(it.tag, it.id) }
        manager.cancel(TAG_SUMMARY, accountId.toInt())
    }

    /** Keeps only notifications of messages that are still unread and visible. */
    private fun pruneRead(accountId: Long) {
        val active = activeMessageNotifications().filter { accountOf(it) == accountId }
        if (active.isEmpty()) {
            manager.cancel(TAG_SUMMARY, accountId.toInt())
            return
        }
        val ids = active.mapNotNull { messageIdOf(it) }
        val stillUnread = store.messages.refs(ids).filter { !it.isRead && !it.hidden }.mapTo(HashSet()) { notificationId(it.id) }
        val gone = active.filter { it.id !in stillUnread }
        if (gone.isEmpty()) return
        gone.forEach { manager.cancel(it.tag, it.id) }
        store.accounts.get(accountId)?.let { updateSummary(it, alert = false, removed = gone.mapTo(HashSet()) { it.id }) }
    }

    private fun buildMessageNotification(account: Account, message: MessageSummary, hasArchive: Boolean): Notification {
        val sender = message.from?.displayName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown_sender)
        val subject = message.subject.ifBlank { context.getString(R.string.no_subject) }
        val bigText = if (message.snippet.isBlank()) subject else subject + "\n" + message.snippet
        val (actionLabel, action) = if (hasArchive) {
            R.string.notif_action_archive to NotificationActionReceiver.ACTION_ARCHIVE
        } else {
            R.string.notif_action_delete to NotificationActionReceiver.ACTION_DELETE
        }
        return Notification.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(account.color)
            .setContentTitle(sender)
            .setContentText(subject)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSubText(account.email)
            .setWhen(message.date)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(account, 1))
            .setGroup(groupKey(account.id))
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setSortKey(sortKey(message.date))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openMessageIntent(account.id, message.id))
            .addAction(
                actionFor(
                    R.string.notif_action_mark_read,
                    NotificationActionReceiver.ACTION_MARK_READ,
                    account.id,
                    message.id,
                ),
            )
            .addAction(actionFor(actionLabel, action, account.id, message.id))
            .addExtras(
                Bundle().apply {
                    putLong(EXTRA_ACCOUNT, account.id)
                    putLong(EXTRA_MESSAGE, message.id)
                },
            )
            .build()
    }

    /** What the group summary needs to know about one message notification. */
    private data class Child(val id: Int, val time: Long, val sender: String, val subject: String)

    /**
     * The group summary of the account's message notifications. notify() and cancel() are
     * asynchronous, so the active list may not show them yet: the children just [added] or
     * [removed] by the caller are applied on top of it.
     */
    private fun updateSummary(
        account: Account,
        alert: Boolean,
        added: List<Child> = emptyList(),
        removed: Set<Int> = emptySet(),
    ) {
        val addedIds = added.mapTo(HashSet()) { it.id }
        val children = activeMessageNotifications()
            .filter { accountOf(it) == account.id && it.id !in removed && it.id !in addedIds }
            .map { Child(it.id, it.notification.`when`, senderOf(it.notification), subjectOf(it.notification)) } + added
        if (children.isEmpty()) {
            manager.cancel(TAG_SUMMARY, account.id.toInt())
            return
        }
        val count = children.size
        val title = context.resources.getQuantityString(R.plurals.notif_new_messages, count, count)
        val style = Notification.InboxStyle().setSummaryText(account.email)
        val newestFirst = children.sortedByDescending { it.time }
        newestFirst.take(MAX_SUMMARY_LINES).forEach { child -> style.addLine(summaryLine(child.sender, child.subject)) }
        val senders = newestFirst.map { it.sender }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val summary = Notification.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(account.color)
            .setContentTitle(title)
            .setContentText(senders)
            .setSubText(account.email)
            .setStyle(style)
            .setNumber(count)
            .setWhen(newestFirst.first().time)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(account, count))
            .setGroup(groupKey(account.id))
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openInboxIntent(account.id))
            .build()
        post(TAG_SUMMARY, account.id.toInt(), summary)
    }

    /** What the lock screen shows when content is hidden: just the count and the account. */
    private fun publicVersion(account: Account, count: Int): Notification =
        Notification.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(account.color)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_new_messages, count, count))
            .setSubText(account.email)
            .build()

    private fun senderOf(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    private fun subjectOf(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun summaryLine(sender: String, subject: String): CharSequence {
        val text = context.getString(R.string.notif_inbox_line, sender, subject)
        val spannable = SpannableString(text)
        val start = text.indexOf(sender)
        if (sender.isNotEmpty() && start >= 0) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, start + sender.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    // ------------------------------------------------------------------ send failures

    override fun onSendFailed(item: OutboxItem) {
        if (!settings.settings.value.notificationsEnabled || !hasPermission()) return
        val subject = item.draft.subject.ifBlank { context.getString(R.string.no_subject) }
        val reason = item.lastError
            ?.let { error -> MailErrorKind.entries.firstOrNull { it.name == error } }
            ?.let { context.getString(it.messageRes()) }
        val text = context.getString(R.string.notif_send_failed_text, subject)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_OUTBOX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(DeepLinks.EXTRA_OPEN_OUTBOX, true)
            .putExtra(DeepLinks.EXTRA_ACCOUNT_ID, item.accountId)
        val pending = PendingIntent.getActivity(
            context,
            requestCode(item.id, REQUEST_SEND_FAILURE),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_SEND_FAILURES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_send_failed_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text, reason).joinToString("\n")))
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        post(TAG_SEND_FAILURE, notificationId(item.id), notification)
    }

    /** Dismisses the failure notification of an outbox message (retried, deleted or edited). */
    fun cancelSendFailure(outboxId: Long) {
        manager.cancel(TAG_SEND_FAILURE, notificationId(outboxId))
    }

    // ------------------------------------------------------------------ helpers

    private fun canNotify(): Boolean =
        settings.settings.value.notificationsEnabled && hasPermission() && manager.areNotificationsEnabled()

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(tag: String, id: Int, notification: Notification) {
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            Log.w(TAG, "Notification not posted", e)
        }
    }

    private fun activeMessageNotifications(): List<StatusBarNotification> =
        try {
            manager.activeNotifications.filter { it.tag?.startsWith(TAG_MESSAGE) == true }
        } catch (e: Exception) {
            emptyList()
        }

    private fun accountOf(notification: StatusBarNotification): Long? =
        notification.notification.extras.getLong(EXTRA_ACCOUNT, -1L).takeIf { it >= 0 }

    private fun messageIdOf(notification: StatusBarNotification): Long? =
        notification.notification.extras.getLong(EXTRA_MESSAGE, -1L).takeIf { it >= 0 }

    private fun openMessageIntent(accountId: Long, messageId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_MESSAGE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(DeepLinks.EXTRA_MESSAGE_ID, messageId)
            .putExtra(DeepLinks.EXTRA_ACCOUNT_ID, accountId)
        return PendingIntent.getActivity(
            context,
            requestCode(messageId, REQUEST_OPEN),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openInboxIntent(accountId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_INBOX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(DeepLinks.EXTRA_ACCOUNT_ID, accountId)
        return PendingIntent.getActivity(
            context,
            requestCode(accountId, REQUEST_INBOX),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionFor(label: Int, action: String, accountId: Long, messageId: Long): Notification.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(DeepLinks.EXTRA_MESSAGE_ID, messageId)
            .putExtra(DeepLinks.EXTRA_ACCOUNT_ID, accountId)
        val slot = when (action) {
            NotificationActionReceiver.ACTION_MARK_READ -> REQUEST_MARK_READ
            else -> REQUEST_MOVE
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(messageId, slot),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(label), pending)
            .build()
    }

    companion object {
        const val CHANNEL_NEW_MAIL = "new_mail"
        const val CHANNEL_SEND_FAILURES = "send_failures"
        const val CHANNEL_PUSH = "push"

        private const val TAG = "MailNotifier"
        private const val TAG_MESSAGE = "message"
        private const val TAG_SUMMARY = "summary"
        private const val TAG_SEND_FAILURE = "send_failure"
        private const val EXTRA_ACCOUNT = "io.github.pportilla.upmmail.notification.ACCOUNT"
        private const val EXTRA_MESSAGE = "io.github.pportilla.upmmail.notification.MESSAGE"

        private const val ACTION_OPEN_MESSAGE = "io.github.pportilla.upmmail.action.OPEN_MESSAGE"
        private const val ACTION_OPEN_INBOX = "io.github.pportilla.upmmail.action.OPEN_INBOX"
        private const val ACTION_OPEN_OUTBOX = "io.github.pportilla.upmmail.action.OPEN_OUTBOX"

        /** Most individual notifications posted by one sync; the summary counts them all. */
        private const val MAX_INDIVIDUAL = 8
        private const val MAX_SUMMARY_LINES = 6

        private const val REQUEST_OPEN = 0
        private const val REQUEST_MARK_READ = 1
        private const val REQUEST_MOVE = 2
        private const val REQUEST_INBOX = 3
        private const val REQUEST_SEND_FAILURE = 4
        private const val REQUEST_SLOTS = 5

        /** Notification id of a message (ids are small positive numbers in practice). */
        fun notificationId(id: Long): Int = (id and 0x7fffffffL).toInt()

        /** Distinct PendingIntent request codes per object and purpose. */
        private fun requestCode(id: Long, slot: Int): Int = ((id % 400_000_000L).toInt() * REQUEST_SLOTS) + slot

        private fun groupKey(accountId: Long) = "io.github.pportilla.upmmail.ACCOUNT_$accountId"

        /** Newer messages sort first inside the group. */
        private fun sortKey(date: Long) = (Long.MAX_VALUE - date).toString().padStart(19, '0')
    }
}
