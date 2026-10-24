package io.github.pportilla.upmmail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.util.Log
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.ui.safety.SafetyRoot
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.ui.components.AppSnackbarController
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.navigation.AppNavHost
import io.github.pportilla.upmmail.ui.navigation.ComposeArgsState
import io.github.pportilla.upmmail.ui.navigation.LaunchRequest
import io.github.pportilla.upmmail.ui.navigation.composeTextSpill
import io.github.pportilla.upmmail.ui.settings.hasNotificationPermission
import io.github.pportilla.upmmail.ui.settings.markNotificationPermissionAsked
import io.github.pportilla.upmmail.ui.settings.needsNotificationPermission
import io.github.pportilla.upmmail.ui.settings.wasNotificationPermissionAsked
import io.github.pportilla.upmmail.ui.theme.UpmMailTheme
import io.github.pportilla.upmmail.ui.theme.isDark

/** Scrims behind the 3-button navigation bar, the same as enableEdgeToEdge's defaults. */
private val LightNavigationScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

private const val TAG = "MainActivity"

/** Saved state: a share or mailto: link still waiting for the first sign-in. */
private const val STATE_PENDING_COMPOSE = "pendingCompose"

/** Intent extras Navigation reads as explicit deep links (NavController.KEY_DEEP_LINK_*). */
private const val NAVIGATION_EXTRA_PREFIX = "android-support-nav:"

/** Scheme of the implicit deep links Navigation derives from routes. */
private const val NAVIGATION_URI_SCHEME = "android-app"

/**
 * The single activity. It hosts the Compose navigation graph, turns incoming intents (mailto:
 * links, shares, notification taps) into navigation requests, and tells the sync controller when
 * the app is visible.
 */
class MainActivity : ComponentActivity() {

    /**
     * Navigation asked for by the latest intent, until the navigation host has acted on it.
     * After a configuration change or process death the intent has already been handled and must
     * not be handled again; only a compose request still waiting for the first sign-in is saved.
     */
    private var pendingRequest by mutableStateOf<LaunchRequest?>(null)
    private var protectedSafetySurface by mutableStateOf(true)
    private var activityResumed by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        // Android 12 and older have no per-app language: use the one chosen in the app.
        super.attachBaseContext((newBase.applicationContext as? UpmMailApp)?.wrapInLanguage(newBase) ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        intent?.removeNavigationDeepLink()
        if (savedInstanceState == null) {
            if (!intent.isLaunchedFromHistory()) pendingRequest = LaunchRequest.fromIntent(intent, packageName)
        } else {
            pendingRequest = savedInstanceState.getBundle(STATE_PENDING_COMPOSE)
                ?.let { ComposeArgsState.fromBundle(it, composeTextSpill()) }
                ?.let { LaunchRequest.Compose(it) }
        }
        val container = appContainer

        setContent {
            LaunchedEffect(protectedSafetySurface, activityResumed) {
                if (protectedSafetySurface || !activityResumed) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    // Retain protection until the replacement safe surface has actually drawn.
                    // A guardian frame must never become the task's background snapshot.
                    withFrameNanos { }
                    withFrameNanos { }
                    if (!protectedSafetySurface && activityResumed) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
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
                    SafetyRoot(
                        container,
                        onDiscardExternalRequest = { pendingRequest = null },
                        onProtectSurface = { protect ->
                            if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            protectedSafetySurface = protect
                        },
                    ) {
                        AppNavHost(
                            accounts = accounts,
                            launchRequest = pendingRequest,
                            onLaunchRequestHandled = { pendingRequest = null },
                            onFinish = ::finish,
                        )
                    }
                }
                val safety by container.safetyRepository.state.collectAsStateWithLifecycle()
                if (safety.termsAccepted && (safety.audience == Audience.ADULT || safety.guardianUnlocked)) {
                    NotificationPermissionRequest(hasAccounts = !accounts.isNullOrEmpty())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.removeNavigationDeepLink()
        setIntent(intent)
        // A plain launcher intent (app already running) keeps the current screen.
        if (appContainer.safetyRepository.state.value.audience == Audience.ADULT) {
            LaunchRequest.fromIntent(intent, packageName)?.let { pendingRequest = it }
        } else {
            pendingRequest = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (pendingRequest as? LaunchRequest.Compose)?.takeIf {
            appContainer.safetyRepository.state.value.audience == Audience.ADULT
        }?.let { request ->
            outState.putBundle(STATE_PENDING_COMPOSE, ComposeArgsState.toBundle(request.args, composeTextSpill()))
        }
    }

    override fun onStart() {
        super.onStart()
        // The per-app language may have been changed in the system settings meanwhile.
        (application as? UpmMailApp)?.mirrorSystemLanguage()
        appContainer.syncController.onAppForeground()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activityResumed = true
    }

    override fun onPause() {
        // Protect before Android can take a task snapshot, including on pre-Android-13 devices.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activityResumed = false
        appContainer.safetyRepository.lockGuardian()
        if (appContainer.safetyRepository.state.value.audience != Audience.ADULT) pendingRequest = null
        super.onPause()
    }

    override fun onStop() {
        appContainer.safetyRepository.lockGuardian()
        super.onStop()
        if (!isChangingConfigurations) appContainer.syncController.onAppBackground()
    }

    private fun Intent?.isLaunchedFromHistory(): Boolean =
        this != null && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

    /**
     * Navigation acts by itself on deep-link extras and route URIs in the launching intent, and
     * before Navigation 2.10 it accepts them from any app. The app never uses them: remove them,
     * so another app cannot open a screen with arguments of its choice (say, a prefilled forward).
     */
    private fun Intent.removeNavigationDeepLink() {
        try {
            extras?.keySet()?.filter { it.startsWith(NAVIGATION_EXTRA_PREFIX) }?.forEach(::removeExtra)
        } catch (e: RuntimeException) {
            // Unreadable extras: Navigation cannot read them either.
            Log.w(TAG, "Unreadable intent extras", e)
        }
        if (data?.scheme.equals(NAVIGATION_URI_SCHEME, ignoreCase = true)) setDataAndType(null, type)
    }
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
