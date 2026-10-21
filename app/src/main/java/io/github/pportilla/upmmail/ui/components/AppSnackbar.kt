package io.github.pportilla.upmmail.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One snackbar queue for the whole app, so a message raised on one screen (e.g. "Sending… Undo"
 * from the compose screen) is still visible after navigating back to another screen.
 */
class AppSnackbarController(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /**
     * Shows [message]. When [actionLabel] is given, [onAction] runs if the user taps it;
     * [onDismiss] runs if the snackbar times out or is swiped away instead. [timeoutMillis], when
     * given, replaces [duration]: the snackbar stays exactly that long.
     */
    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        timeoutMillis: Long? = null,
        onDismiss: (() -> Unit)? = null,
        onAction: (() -> Unit)? = null,
    ) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = if (timeoutMillis != null) {
                // Cancelling showSnackbar at the timeout removes the snackbar.
                withTimeoutOrNull(timeoutMillis) {
                    hostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        duration = SnackbarDuration.Indefinite,
                    )
                } ?: SnackbarResult.Dismissed
            } else {
                hostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = actionLabel == null && duration == SnackbarDuration.Indefinite,
                    duration = duration,
                )
            }
            when (result) {
                SnackbarResult.ActionPerformed -> onAction?.invoke()
                SnackbarResult.Dismissed -> onDismiss?.invoke()
            }
        }
    }
}

val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarController> {
    error("AppSnackbarController not provided")
}

/** Put this in every screen's `Scaffold(snackbarHost = { AppSnackbarHost() })`. */
@Composable
fun AppSnackbarHost(modifier: Modifier = Modifier) {
    SnackbarHost(hostState = LocalAppSnackbar.current.hostState, modifier = modifier)
}
