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
import io.github.pportilla.upmmail.data.safety.Audience
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
 * "Archive"/"Delete" actions, which on Android 12+ ask to unlock the phone first), grouped per
 * account under an InboxStyle summary. New-mail notifications are only posted while the app is
 * in the background, notifications are enabled in the app settings and for the account, and
 * (Android 13+) the permission is granted. Send failures only need the permission.
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
        val safetyRevision = store.safety.state.value.revision
        if (messages.isEmpty() || isAppInForeground() || !canNotify()) return
        val account = store.accounts.get(accountId) ?: return
        if (!account.notificationsEnabled) return
        val hasArchive = store.folders.findByRole(accountId, FolderRole.ARCHIVE) != null
        val visible = messages.filterNot { store.safety.isSenderBlocked(it.from?.address) }
        if (visible.isEmpty()) return
        val posted = visible.sortedByDescending { it.date }.take(MAX_INDIVIDUAL).sortedBy { it.date }.map { message ->
            val notification = buildMessageNotification(account, message, hasArchive)
            post(TAG_MESSAGE, notificationId(message.id), notification, safetyRevision)
            Child(notificationId(message.id), message.date, senderOf(notification), subjectOf(notification))
        }
        updateSummary(account, alert = true, added = posted, sourceRevision = safetyRevision)
    }

    override fun onInboxSynced(accountId: Long, folderId: Long) {
        if (!adultNotificationsAllowed()) {
            clearPrivateNotifications()
            return
        }
        pruneRead(accountId)
    }

    /** Called on every safety-policy change, including revocation and new sender blocks. */
    fun clearPrivateNotifications() {
        try {
            manager.activeNotifications.filter {
                it.tag == TAG_MESSAGE || it.tag == TAG_SUMMARY || it.tag == TAG_SEND_FAILURE
            }.forEach { manager.cancel(it.tag, it.id) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not clear mail notifications", e)
        }
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

    private fun accountLabel(account: Account): String =
        if (account.isDemo) context.getString(R.string.demo_account_label, account.email) else account.email

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
            .setSubText(accountLabel(account))
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
        sourceRevision: Long? = null,
    ) {
        val safetyRevision = store.safety.state.value.revision
        if (sourceRevision != null && sourceRevision != safetyRevision) return
        if (!adultNotificationsAllowed()) {
            clearPrivateNotifications()
            return
        }
        val addedIds = added.mapTo(HashSet()) { it.id }
        val children = activeMessageNotifications()
            .filter { accountOf(it) == account.id && it.id !in removed && it.id !in addedIds }
            .filter { active ->
                messageIdOf(active)?.let { store.messages.detail(it) }?.let {
                    !store.safety.isSenderBlocked(it.summary.from?.address)
                } == true
            }
            .map { Child(it.id, it.notification.`when`, senderOf(it.notification), subjectOf(it.notification)) } + added
        if (children.isEmpty()) {
            manager.cancel(TAG_SUMMARY, account.id.toInt())
            return
        }
        val count = children.size
        val title = context.resources.getQuantityString(R.plurals.notif_new_messages, count, count)
        val style = Notification.InboxStyle().setSummaryText(accountLabel(account))
        val newestFirst = children.sortedByDescending { it.time }
        newestFirst.take(MAX_SUMMARY_LINES).forEach { child -> style.addLine(summaryLine(child.sender, child.subject)) }
        val senders = newestFirst.map { it.sender }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val summary = Notification.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(account.color)
            .setContentTitle(title)
            .setContentText(senders)
            .setSubText(accountLabel(account))
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
        post(TAG_SUMMARY, account.id.toInt(), summary, safetyRevision)
    }

    /** What the lock screen shows when content is hidden: just the count and the account. */
    private fun publicVersion(account: Account, count: Int): Notification =
        Notification.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(account.color)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_new_messages, count, count))
            .setSubText(accountLabel(account))
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

    /**
     * Posted whatever the new-mail setting says: that switch is about incoming mail, while a
     * message that was not sent always needs the user (the channel can still be muted).
     */
    override fun onSendFailed(item: OutboxItem) {
        val safetyRevision = store.safety.state.value.revision
        if (!adultNotificationsAllowed() || !hasPermission()) return
        if ((item.draft.to + item.draft.cc + item.draft.bcc).any { store.safety.isSenderBlocked(it.address) }) return
        val account = store.accounts.get(item.accountId) ?: return
        val subject = item.draft.subject.ifBlank { context.getString(R.string.no_subject) }
        val reason = item.lastError
            ?.let { error -> MailErrorKind.entries.firstOrNull { it.name == error } }
            ?.let { context.getString(it.messageRes()) }
        val text = if (account.isDemo) subject else context.getString(R.string.notif_send_failed_text, subject)
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
            .setContentTitle(context.getString(if (account.isDemo) R.string.demo_outbox_failed else R.string.notif_send_failed_title))
            .setSubText(accountLabel(account))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text, reason).joinToString("\n")))
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        post(TAG_SEND_FAILURE, notificationId(item.id), notification, safetyRevision)
    }

    /** Dismisses the failure notification of an outbox message (retried, deleted or edited). */
    fun cancelSendFailure(outboxId: Long) {
        manager.cancel(TAG_SEND_FAILURE, notificationId(outboxId))
    }

    // ------------------------------------------------------------------ helpers

    private fun canNotify(): Boolean =
        adultNotificationsAllowed() && settings.settings.value.notificationsEnabled && hasPermission() && manager.areNotificationsEnabled()

    private fun adultNotificationsAllowed(): Boolean = store.safety.state.value.let {
        it.audience == Audience.ADULT && it.termsAccepted
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(tag: String, id: Int, notification: Notification, safetyRevision: Long) {
        // Check again immediately before delivery: a profile may have changed while building it.
        if (!adultNotificationsAllowed() || store.safety.state.value.revision != safetyRevision) return
        try {
            manager.notify(tag, id, notification)
            // A concurrent policy change may clear active notifications before this post lands.
            // Cancel this exact delivery as well so stale private content cannot remain visible.
            if (!adultNotificationsAllowed() || store.safety.state.value.revision != safetyRevision) {
                manager.cancel(tag, id)
            }
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
            .apply {
                // Both actions change mail: never from a locked phone without unlocking it first.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
            }
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
