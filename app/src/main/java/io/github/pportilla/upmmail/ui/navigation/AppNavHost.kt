package io.github.pportilla.upmmail.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.data.safety.Audience
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.compose.ComposeScreen
import io.github.pportilla.upmmail.ui.mailbox.MailboxScreen
import io.github.pportilla.upmmail.ui.message.MessageScreen
import io.github.pportilla.upmmail.ui.search.SearchScreen
import io.github.pportilla.upmmail.ui.settings.AccountSettingsScreen
import io.github.pportilla.upmmail.ui.settings.SettingsScreen
import io.github.pportilla.upmmail.ui.setup.SetupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val ENTER_MILLIS = 280
private const val EXIT_MILLIS = 200

/**
 * The app's navigation graph.
 *
 * @param accounts the configured accounts, null until the first database read. The start
 *   destination (sign-in or mailbox) is chosen from the first non-null value and kept, a
 *   progress indicator is shown until then.
 * @param launchRequest navigation asked for by the latest intent; [onLaunchRequestHandled] is
 *   called once it has been acted upon (or dropped), so it is never handled twice.
 * @param onFinish closes the activity when a screen goes back from the root.
 */
@Composable
fun AppNavHost(
    accounts: List<Account>?,
    launchRequest: LaunchRequest?,
    onLaunchRequestHandled: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safety by appContainer().safetyRepository.state.collectAsStateWithLifecycle()
    // A restored route or notification cannot bypass the separate supervised surface.
    if (!safety.termsAccepted || safety.audience == Audience.UNCONFIGURED ||
        (safety.audience == Audience.SUPERVISED && !safety.guardianUnlocked)) return
    val navController = rememberNavController()
    var savedStart by rememberSaveable { mutableStateOf<String?>(null) }
    val startRoute = savedStart ?: accounts?.let { if (it.isEmpty()) Routes.SETUP else Routes.MAILBOX }
    if (startRoute == null) {
        LoadingSplash(modifier)
        return
    }
    SideEffect {
        if (savedStart == null) savedStart = startRoute
    }

    val container = appContainer()
    val settingsRepository = container.settingsRepository
    val accountRepository = container.accountRepository
    // Read through State objects so the graph builder below never captures changing values
    // (a new builder lambda would rebuild the graph).
    val accountsState = rememberUpdatedState(accounts)
    val currentOnFinish by rememberUpdatedState(onFinish)
    val finish = remember { { currentOnFinish() } }
    val guard = remember { NavigationGuard() }
    val rootNavigator = remember(navController) { NavControllerNavigator(navController, null, guard, finish) }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backEnter() },
        popExitTransition = { backExit() },
    ) {
        composable(
            route = Routes.SETUP,
            arguments = listOf(
                navArgument(Routes.ARG_ADD) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val adding = entry.arguments?.getBoolean(Routes.ARG_ADD) ?: false
            SetupScreen(
                navigator = rememberEntryNavigator(navController, entry, guard, finish),
                isAddingAccount = adding && !accountsState.value.isNullOrEmpty(),
            )
        }
        composable(Routes.MAILBOX) { entry ->
            MailboxScreen(navigator = rememberEntryNavigator(navController, entry, guard, finish))
        }
        composable(
            route = Routes.MESSAGE,
            arguments = listOf(navArgument(Routes.ARG_MESSAGE_ID) { type = NavType.LongType }),
        ) { entry ->
            MessageScreen(
                messageId = entry.arguments?.getLong(Routes.ARG_MESSAGE_ID) ?: Routes.NONE,
                navigator = rememberEntryNavigator(navController, entry, guard, finish),
            )
        }
        composable(
            route = Routes.COMPOSE,
            arguments = listOf(
                navArgument(Routes.ARG_KEY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            ComposeScreen(
                args = rememberComposeArgs(entry),
                navigator = rememberEntryNavigator(navController, entry, guard, finish),
            )
        }
        composable(
            route = Routes.SEARCH,
            arguments = listOf(
                navArgument(Routes.ARG_ACCOUNT_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NONE
                },
                navArgument(Routes.ARG_FOLDER_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NONE
                },
                navArgument(Routes.ARG_QUERY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            SearchScreen(
                accountId = entry.arguments?.getLong(Routes.ARG_ACCOUNT_ID)?.takeIf { it != Routes.NONE },
                folderId = entry.arguments?.getLong(Routes.ARG_FOLDER_ID)?.takeIf { it != Routes.NONE },
                navigator = rememberEntryNavigator(navController, entry, guard, finish),
                initialQuery = entry.arguments?.getString(Routes.ARG_QUERY),
            )
        }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(navigator = rememberEntryNavigator(navController, entry, guard, finish))
        }
        composable(
            route = Routes.ACCOUNT_SETTINGS,
            arguments = listOf(navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.LongType }),
        ) { entry ->
            AccountSettingsScreen(
                accountId = entry.arguments?.getLong(Routes.ARG_ACCOUNT_ID) ?: Routes.NONE,
                navigator = rememberEntryNavigator(navController, entry, guard, finish),
            )
        }
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val hasAccounts = accounts?.isNotEmpty()

    // Every account was removed: back to the sign-in screen, with nothing to go back to.
    LaunchedEffect(hasAccounts, currentRoute) {
        if (hasAccounts != false || currentRoute == null) return@LaunchedEffect
        if (isSetupRoot(navController, currentRoute)) return@LaunchedEffect
        // Right after the first sign-in the collected list can lag behind the database while
        // the sign-in screen already opens the mailbox: ask the database itself.
        val reallyEmpty = try {
            accountRepository.accounts.first().isEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (reallyEmpty) {
            navController.navigate(Routes.setup(add = false)) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    LaunchedEffect(launchRequest, hasAccounts, currentRoute) {
        val request = launchRequest ?: return@LaunchedEffect
        if (hasAccounts == null || currentRoute == null) return@LaunchedEffect
        if (!hasAccounts) {
            // A shared file or mailto: link waits until the user has signed in; notification
            // targets cannot exist without an account.
            if (request !is LaunchRequest.Compose) onLaunchRequestHandled()
            return@LaunchedEffect
        }
        // The first account was just added and the sign-in screen is about to open the mailbox
        // (clearing the back stack); act once that has happened.
        if (isSetupRoot(navController, currentRoute)) return@LaunchedEffect
        handleLaunchRequest(request, rootNavigator, settingsRepository)
        onLaunchRequestHandled()
    }
}

private fun isSetupRoot(navController: NavHostController, currentRoute: String): Boolean =
    currentRoute == Routes.SETUP && navController.previousBackStackEntry == null

private fun handleLaunchRequest(
    request: LaunchRequest,
    navigator: AppNavigator,
    settingsRepository: SettingsRepository,
) {
    when (request) {
        is LaunchRequest.Compose -> navigator.openCompose(request.args)
        is LaunchRequest.OpenMessage -> {
            // Going back from the message shows the INBOX of its account.
            request.accountId?.let { accountId ->
                settingsRepository.update { current ->
                    if (current.lastAccountId == accountId) current else current.copy(lastAccountId = accountId, lastFolderId = -1)
                }
            }
            navigator.openMailbox()
            navigator.openMessage(request.messageId)
        }
        is LaunchRequest.OpenMailbox -> {
            settingsRepository.update { current ->
                current.copy(
                    lastAccountId = request.accountId ?: current.lastAccountId,
                    lastFolderId = if (request.outbox) DeepLinks.OUTBOX_FOLDER_ID else -1,
                )
            }
            navigator.openMailbox()
        }
    }
}

@Composable
private fun rememberEntryNavigator(
    navController: NavHostController,
    entry: NavBackStackEntry,
    guard: NavigationGuard,
    finish: () -> Unit,
): AppNavigator = remember(navController, entry.id) {
    NavControllerNavigator(navController, entry.id, guard, finish)
}

/**
 * The arguments of a compose destination: taken from [ComposeArgsRegistry] the first time and
 * kept in the entry's saved state, so they survive configuration changes and process death.
 */
@Composable
private fun rememberComposeArgs(entry: NavBackStackEntry): ComposeArgs {
    val context = LocalContext.current
    return remember(entry.id) {
        val handle = entry.savedStateHandle
        val spill = context.composeTextSpill()
        val fresh = entry.arguments?.getString(Routes.ARG_KEY)?.let(ComposeArgsRegistry::take)
        val forged = entry.arguments?.keySet()?.let(ComposeArgsCodec::containsOwnKeys) == true
        when {
            fresh != null -> {
                ComposeArgsState.write(handle, fresh, spill)
                fresh
            }
            // Deep-link arguments also seed the saved state: never trust it then.
            forged -> ComposeArgs()
            else -> ComposeArgsState.read(handle, spill)
                ?.let { saved ->
                    // Only shared content:// URIs of other apps ever get here.
                    saved.copy(
                        attachmentUris = saved.attachmentUris.filter {
                            LaunchRequest.isAcceptableAttachment(Uri.parse(it), context.packageName)
                        },
                    )
                }
                ?: ComposeArgs()
        }
    }
}

@Composable
private fun LoadingSplash(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.loading)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

// Subtle transitions: a short fade with a slide of a tenth of the width, mirrored for RTL.

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardEnter(): EnterTransition =
    fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)) +
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            initialOffset = { it / 10 },
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExit(): ExitTransition =
    fadeOut(tween(EXIT_MILLIS)) +
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            targetOffset = { it / 20 },
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backEnter(): EnterTransition =
    fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)) +
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            initialOffset = { it / 20 },
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExit(): ExitTransition =
    fadeOut(tween(EXIT_MILLIS)) +
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            targetOffset = { it / 10 },
        )
