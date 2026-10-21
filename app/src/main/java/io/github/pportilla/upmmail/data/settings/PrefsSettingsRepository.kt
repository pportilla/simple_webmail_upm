package io.github.pportilla.upmmail.data.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SettingsRepository] backed by SharedPreferences. [settings] changes synchronously in
 * [update]; the write to disk happens asynchronously with apply().
 */
class PrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read(prefs))
    private val lock = Any()

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val old = state.value
            val new = transform(old)
            if (new == old) return
            state.value = new
            write(prefs.edit(), new).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "upmmail_settings"
        const val THEME = "theme_mode"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val SYNC_INTERVAL = "sync_interval_minutes"
        const val NOTIFICATIONS = "notifications_enabled"
        const val SWIPE_START = "swipe_start_to_end"
        const val SWIPE_END = "swipe_end_to_start"
        const val REMOTE_IMAGES = "load_remote_images"
        const val TRUSTED_SENDERS = "trusted_image_senders"
        const val CONFIRM_DELETE = "confirm_delete"
        const val UNDO_SEND = "undo_send_seconds"
        const val CONVERSATIONS = "conversation_view"
        const val SNIPPETS = "show_snippets"
        const val REPLY_ALL = "default_reply_all"
        const val LAST_ACCOUNT = "last_account_id"
        const val LAST_FOLDER = "last_folder_id"

        fun read(prefs: SharedPreferences): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                themeMode = enumOf(prefs.getString(THEME, null), defaults.themeMode),
                dynamicColor = prefs.getBoolean(DYNAMIC_COLOR, defaults.dynamicColor),
                syncIntervalMinutes = prefs.getInt(SYNC_INTERVAL, defaults.syncIntervalMinutes).coerceAtLeast(0),
                notificationsEnabled = prefs.getBoolean(NOTIFICATIONS, defaults.notificationsEnabled),
                swipeStartToEnd = enumOf(prefs.getString(SWIPE_START, null), defaults.swipeStartToEnd),
                swipeEndToStart = enumOf(prefs.getString(SWIPE_END, null), defaults.swipeEndToStart),
                loadRemoteImages = prefs.getBoolean(REMOTE_IMAGES, defaults.loadRemoteImages),
                // Copy: the set returned by SharedPreferences must not be modified or kept.
                trustedImageSenders = prefs.getStringSet(TRUSTED_SENDERS, null)?.toSet() ?: defaults.trustedImageSenders,
                confirmDelete = prefs.getBoolean(CONFIRM_DELETE, defaults.confirmDelete),
                undoSendSeconds = prefs.getInt(UNDO_SEND, defaults.undoSendSeconds).coerceAtLeast(0),
                conversationView = prefs.getBoolean(CONVERSATIONS, defaults.conversationView),
                showSnippets = prefs.getBoolean(SNIPPETS, defaults.showSnippets),
                defaultReplyAll = prefs.getBoolean(REPLY_ALL, defaults.defaultReplyAll),
                lastAccountId = prefs.getLong(LAST_ACCOUNT, defaults.lastAccountId),
                lastFolderId = prefs.getLong(LAST_FOLDER, defaults.lastFolderId),
            )
        }

        fun write(editor: SharedPreferences.Editor, settings: AppSettings): SharedPreferences.Editor = editor
            .putString(THEME, settings.themeMode.name)
            .putBoolean(DYNAMIC_COLOR, settings.dynamicColor)
            .putInt(SYNC_INTERVAL, settings.syncIntervalMinutes)
            .putBoolean(NOTIFICATIONS, settings.notificationsEnabled)
            .putString(SWIPE_START, settings.swipeStartToEnd.name)
            .putString(SWIPE_END, settings.swipeEndToStart.name)
            .putBoolean(REMOTE_IMAGES, settings.loadRemoteImages)
            .putStringSet(TRUSTED_SENDERS, settings.trustedImageSenders.map { it.lowercase() }.toSet())
            .putBoolean(CONFIRM_DELETE, settings.confirmDelete)
            .putInt(UNDO_SEND, settings.undoSendSeconds)
            .putBoolean(CONVERSATIONS, settings.conversationView)
            .putBoolean(SNIPPETS, settings.showSnippets)
            .putBoolean(REPLY_ALL, settings.defaultReplyAll)
            .putLong(LAST_ACCOUNT, settings.lastAccountId)
            .putLong(LAST_FOLDER, settings.lastFolderId)

        inline fun <reified E : Enum<E>> enumOf(value: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == value } ?: default
    }
}
