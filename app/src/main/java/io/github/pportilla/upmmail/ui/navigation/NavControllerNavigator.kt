package io.github.pportilla.upmmail.ui.navigation

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** String routes of the navigation graph and builders for concrete destinations. */
internal object Routes {
    const val ARG_ADD = "add"
    const val ARG_MESSAGE_ID = "messageId"
    const val ARG_KEY = "key"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_FOLDER_ID = "folderId"
    const val ARG_QUERY = "query"

    const val SETUP = "setup?$ARG_ADD={$ARG_ADD}"
    const val MAILBOX = "mailbox"
    const val MESSAGE = "message/{$ARG_MESSAGE_ID}"
    const val COMPOSE = "compose?$ARG_KEY={$ARG_KEY}"
    const val SEARCH = "search?$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}&$ARG_FOLDER_ID={$ARG_FOLDER_ID}&$ARG_QUERY={$ARG_QUERY}"
    const val SETTINGS = "settings"
    const val ACCOUNT_SETTINGS = "settings/account/{$ARG_ACCOUNT_ID}"

    /** Route arguments use -1 for "none". */
    const val NONE = -1L

    fun setup(add: Boolean) = "setup?$ARG_ADD=$add"

    fun message(messageId: Long) = "message/$messageId"

    fun compose(key: String) = "compose?$ARG_KEY=$key"

    /** The search route; [query] (any text: quotes, spaces, "&") is encoded and left out when blank. */
    fun search(accountId: Long?, folderId: Long?, query: String? = null): String {
        val base = "search?$ARG_ACCOUNT_ID=${accountId ?: NONE}&$ARG_FOLDER_ID=${folderId ?: NONE}"
        return if (query.isNullOrBlank()) base else "$base&$ARG_QUERY=${Uri.encode(query)}"
    }

    fun accountSettings(accountId: Long) = "settings/account/$accountId"
}

/**
 * ComposeArgs can carry whole message bodies and lists of URIs, which do not belong in a route.
 * They wait here under a random key until the compose destination picks them up and copies them
 * into its back stack entry's saved state (see [ComposeArgsState]), which survives process death.
 */
internal object ComposeArgsRegistry {
    private val pending = ConcurrentHashMap<String, ComposeArgs>()

    fun put(args: ComposeArgs): String {
        val key = UUID.randomUUID().toString()
        pending[key] = args
        return key
    }

    fun take(key: String): ComposeArgs? = pending.remove(key)
}

/** Stores ComposeArgs field by field in a SavedStateHandle or a Bundle (see [ComposeArgsCodec]). */
internal object ComposeArgsState {
    fun write(handle: SavedStateHandle, args: ComposeArgs, spill: TextSpill?) {
        ComposeArgsCodec.write(args, spill) { key, value -> handle[key] = value }
    }

    fun read(handle: SavedStateHandle, spill: TextSpill?): ComposeArgs? =
        ComposeArgsCodec.read(spill) { key -> handle.get<Any>(key) }

    fun toBundle(args: ComposeArgs, spill: TextSpill?): Bundle = Bundle().apply {
        ComposeArgsCodec.write(args, spill) { key, value ->
            when (value) {
                null -> putString(key, null)
                is Long -> putLong(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is List<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
            }
        }
    }

    fun fromBundle(bundle: Bundle, spill: TextSpill?): ComposeArgs? =
        ComposeArgsCodec.read(spill) { key ->
            @Suppress("DEPRECATION")
            bundle.get(key)
        }
}

/** Where compose bodies too long for saved state are kept (see [TextSpill]). */
internal fun Context.composeTextSpill(): TextSpill = TextSpill(File(noBackupFilesDir, "compose_args"))

/**
 * Swallows the second of two identical navigations in quick succession (a double tap on a list
 * row would otherwise open the same screen twice).
 */
internal class NavigationGuard(private val windowMillis: Long = 600) {
    private var lastTarget: String? = null
    private var lastAt = 0L

    fun allow(target: String): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (target == lastTarget && now - lastAt < windowMillis) return false
        lastTarget = target
        lastAt = now
        return true
    }

    fun reset() {
        lastTarget = null
    }
}

/**
 * [AppNavigator] on top of the Navigation Compose controller.
 *
 * Each destination gets its own instance bound to its back stack entry ([ownerEntryId]), so a
 * late or repeated [back] from a screen that is no longer on top does nothing instead of popping
 * someone else's screen. Forward navigation is allowed from any instance: e.g. "Undo" on the
 * send snackbar reopens the draft after the compose screen has already closed.
 */
internal class NavControllerNavigator(
    private val controller: NavHostController,
    private val ownerEntryId: String?,
    private val guard: NavigationGuard,
    private val finishActivity: () -> Unit,
) : AppNavigator {

    override fun openMailbox(clearBackStack: Boolean) = safely {
        guard.reset()
        if (clearBackStack) {
            controller.navigate(Routes.MAILBOX) {
                popUpTo(controller.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        } else if (!controller.popBackStack(Routes.MAILBOX, inclusive = false)) {
            controller.navigate(Routes.MAILBOX) { launchSingleTop = true }
        }
    }

    override fun openMessage(messageId: Long) {
        // No launchSingleTop: it would reuse the entry (and its ViewModel) of another message.
        navigate(Routes.message(messageId))
    }

    override fun openCompose(args: ComposeArgs) = safely {
        if (guard.allow("compose:" + args.hashCode())) {
            controller.navigate(Routes.compose(ComposeArgsRegistry.put(args)))
        }
    }

    override fun openSearch(accountId: Long?, folderId: Long?, query: String?) {
        navigate(Routes.search(accountId, folderId, query))
    }

    override fun openSettings() {
        navigate(Routes.SETTINGS, singleTop = true)
    }

    override fun openAccountSettings(accountId: Long) {
        navigate(Routes.accountSettings(accountId))
    }

    override fun openAddAccount() {
        navigate(Routes.setup(add = true), singleTop = true)
    }

    override fun back() = safely {
        val current = controller.currentBackStackEntry
        when {
            current == null -> finishActivity()
            // A late or repeated call from a screen that is no longer on top.
            ownerEntryId != null && current.id != ownerEntryId -> Unit
            controller.previousBackStackEntry == null -> finishActivity()
            else -> {
                guard.reset()
                controller.popBackStack()
            }
        }
    }

    private fun navigate(route: String, singleTop: Boolean = false) = safely {
        if (guard.allow(route)) controller.navigate(route) { launchSingleTop = singleTop }
    }

    /**
     * Navigation requests can arrive late (e.g. "Undo" on a snackbar after the activity was
     * recreated); a controller that can no longer navigate must not crash the app.
     */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Navigation request ignored", e)
        }
    }

    private companion object {
        const val TAG = "AppNavigator"
    }
}
