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

    companion object {
        private const val PREFS_NAME = "upmmail_settings"
        private const val THEME = "theme_mode"
        private const val DYNAMIC_COLOR = "dynamic_color"
        private const val PUSH = "push_enabled"
        private const val SYNC_INTERVAL = "sync_interval_minutes"
        private const val LANGUAGE = "language"
        private const val LANGUAGE_IN_SYSTEM = "language_in_system"
        private const val NOTIFICATIONS = "notifications_enabled"
        private const val SWIPE_START = "swipe_start_to_end"
        private const val SWIPE_END = "swipe_end_to_start"
        private const val REMOTE_IMAGES = "load_remote_images"
        private const val TRUSTED_SENDERS = "trusted_image_senders"
        private const val CONFIRM_DELETE = "confirm_delete"
        private const val UNDO_SEND = "undo_send_seconds"
        private const val CONVERSATIONS = "conversation_view"
        private const val SNIPPETS = "show_snippets"
        private const val REPLY_ALL = "default_reply_all"
        private const val LAST_ACCOUNT = "last_account_id"
        private const val LAST_FOLDER = "last_folder_id"

        /**
         * The stored [AppSettings.language], read without a repository: the app needs it in
         * Application.attachBaseContext, before its container exists.
         */
        fun storedLanguage(context: Context): String = AppLanguage.normalize(prefsOf(context).getString(LANGUAGE, null))

        /**
         * Whether Android 13+ keeps the language already ([markLanguageInSystem]). Until then, a
         * language chosen while the device ran an older version is still to be handed over.
         */
        fun isLanguageInSystem(context: Context): Boolean = prefsOf(context).getBoolean(LANGUAGE_IN_SYSTEM, false)

        fun markLanguageInSystem(context: Context) {
            prefsOf(context).edit().putBoolean(LANGUAGE_IN_SYSTEM, true).apply()
        }

        private fun prefsOf(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun read(prefs: SharedPreferences): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                themeMode = enumOf(prefs.getString(THEME, null), defaults.themeMode),
                dynamicColor = prefs.getBoolean(DYNAMIC_COLOR, defaults.dynamicColor),
                // Persistent foreground work starts only after the user selects instant push.
                pushEnabled = prefs.getBoolean(PUSH, defaults.pushEnabled),
                syncIntervalMinutes = prefs.getInt(SYNC_INTERVAL, defaults.syncIntervalMinutes).coerceAtLeast(0),
                language = AppLanguage.normalize(prefs.getString(LANGUAGE, null)),
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

        private fun write(editor: SharedPreferences.Editor, settings: AppSettings): SharedPreferences.Editor = editor
            .putString(THEME, settings.themeMode.name)
            .putBoolean(DYNAMIC_COLOR, settings.dynamicColor)
            .putBoolean(PUSH, settings.pushEnabled)
            .putInt(SYNC_INTERVAL, settings.syncIntervalMinutes)
            .putString(LANGUAGE, settings.language)
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

        private inline fun <reified E : Enum<E>> enumOf(value: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == value } ?: default
    }
}
