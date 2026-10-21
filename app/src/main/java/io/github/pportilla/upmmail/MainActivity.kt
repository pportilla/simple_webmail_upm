package io.github.pportilla.upmmail

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.ui.components.AppSnackbarController
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.navigation.AppNavHost
import io.github.pportilla.upmmail.ui.navigation.LaunchRequest
import io.github.pportilla.upmmail.ui.settings.hasNotificationPermission
import io.github.pportilla.upmmail.ui.settings.markNotificationPermissionAsked
import io.github.pportilla.upmmail.ui.settings.needsNotificationPermission
import io.github.pportilla.upmmail.ui.settings.wasNotificationPermissionAsked
import io.github.pportilla.upmmail.ui.theme.UpmMailTheme
import io.github.pportilla.upmmail.ui.theme.isDark

/** Scrims behind the 3-button navigation bar, the same as enableEdgeToEdge's defaults. */
private val LightNavigationScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * The single activity. It hosts the Compose navigation graph, turns incoming intents (mailto:
 * links, shares, notification taps) into navigation requests, and tells the sync controller when
 * the app is visible.
 */
class MainActivity : ComponentActivity() {

    /**
     * Navigation asked for by the latest intent, until the navigation host has acted on it.
     * Plain (not saved) state on purpose: after a configuration change or process death the
     * intent has already been handled and must not be handled again.
     */
    private var pendingRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && !intent.isLaunchedFromHistory()) {
            pendingRequest = LaunchRequest.fromIntent(intent, packageName)
        }
        val container = appContainer

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle<List<Account>?>(initialValue = null)
            val darkTheme = settings.themeMode.isDark()

            // The app theme can differ from the system one: keep the bar icons readable.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { darkTheme },
                )
                onDispose {}
            }

            UpmMailTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                // Above the NavHost, so snackbars (e.g. "Sent - Undo") survive navigation.
                val snackbarScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }
                val snackbar = remember(snackbarHostState, snackbarScope) {
                    AppSnackbarController(snackbarHostState, snackbarScope)
                }
                CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
                    AppNavHost(
                        accounts = accounts,
                        launchRequest = pendingRequest,
                        onLaunchRequestHandled = { pendingRequest = null },
                        onFinish = ::finish,
                    )
                }
                NotificationPermissionRequest(hasAccounts = !accounts.isNullOrEmpty())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A plain launcher intent (app already running) keeps the current screen.
        LaunchRequest.fromIntent(intent, packageName)?.let { pendingRequest = it }
    }

    override fun onStart() {
        super.onStart()
        appContainer.syncController.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appContainer.syncController.onAppBackground()
    }

    private fun Intent?.isLaunchedFromHistory(): Boolean =
        this != null && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
}

/**
 * Asks once for the Android 13+ notification permission, right after the first account has been
 * added (asking before there is any mail to notify about would only get it denied).
 */
@Composable
private fun NotificationPermissionRequest(hasAccounts: Boolean) {
    if (!needsNotificationPermission) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Nothing to do: the notifier checks the permission each time it posts.
    }
    LaunchedEffect(hasAccounts) {
        if (!hasAccounts || context.wasNotificationPermissionAsked()) return@LaunchedEffect
        context.markNotificationPermissionAsked()
        if (!context.hasNotificationPermission()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
