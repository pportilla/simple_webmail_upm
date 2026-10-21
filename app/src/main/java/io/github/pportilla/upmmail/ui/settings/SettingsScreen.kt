package io.github.pportilla.upmmail.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.BuildConfig
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.settings.AppLanguage
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.SwipeAction
import io.github.pportilla.upmmail.model.ThemeMode
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.components.avatarInitial
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.setup.findActivity
import io.github.pportilla.upmmail.ui.setup.openWebPage
import io.github.pportilla.upmmail.ui.theme.supportsDynamicColor

/** Public repository of the app. */
const val SOURCE_CODE_URL = "https://github.com/pportilla/simple_webmail_upm"

/** Widest the settings list gets on tablets. */
private val MaxListWidth = 720.dp

/**
 * App settings: appearance, reading, swipe actions, sending, notifications, accounts and
 * information about the app. Changes apply immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navigator: AppNavigator) {
    val container = appContainer()
    val viewModel = viewModel { SettingsViewModel(container) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var systemNotificationsAllowed by remember { mutableStateOf(context.areAppNotificationsEnabled()) }
    var backgroundAllowed by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    // Both can change in the system settings, or in the system dialogs opened from here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemNotificationsAllowed = context.areAppNotificationsEnabled()
        backgroundAllowed = context.isIgnoringBatteryOptimizations()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        systemNotificationsAllowed = context.areAppNotificationsEnabled()
    }

    val openUrl: (String) -> Unit = { url ->
        if (!context.openWebPage(url)) snackbar.show(context.getString(R.string.settings_no_browser))
    }
    val openSystemNotificationSettings = {
        if (!context.openAppNotificationSettings()) snackbar.show(context.getString(R.string.settings_cannot_open_system_settings))
    }
    val changeLanguage: (String) -> Unit = { language ->
        val activity = context.findActivity()
        val app = context.applicationContext as? UpmMailApp
        if (activity != null && app != null) app.changeLanguage(activity, language)
    }
    val allowBackground = {
        if (!context.requestIgnoreBatteryOptimizations()) snackbar.show(context.getString(R.string.settings_cannot_open_system_settings))
    }
    // Ask with the system dialog while Android still shows it; afterwards only the system
    // settings can turn notifications back on.
    val allowNotifications = {
        val activity = context.findActivity()
        val canAsk = needsNotificationPermission &&
            !context.hasNotificationPermission() &&
            activity != null &&
            (!context.wasNotificationPermissionAsked() ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (canAsk) {
            context.markNotificationPermissionAsked()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSystemNotificationSettings()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost() },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
        ) {
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier
                    .widthIn(max = MaxListWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                appearanceSection(state.settings, viewModel, onSelectLanguage = changeLanguage)
                readingSection(state.settings, viewModel) {
                    viewModel.forgetTrustedImageSenders()
                    snackbar.show(context.getString(R.string.settings_trusted_senders_cleared))
                }
                swipeSection(state.settings, viewModel)
                sendingSection(state.settings, viewModel)
                notificationsSection(
                    settings = state.settings,
                    viewModel = viewModel,
                    systemAllowed = systemNotificationsAllowed,
                    onAllow = allowNotifications,
                    onEnable = { enabled ->
                        viewModel.setNotificationsEnabled(enabled)
                        if (enabled && !systemNotificationsAllowed) allowNotifications()
                    },
                    onOpenSystemSettings = openSystemNotificationSettings,
                    backgroundAllowed = backgroundAllowed,
                    onAllowBackground = allowBackground,
                )
                accountsSection(
                    state = state,
                    onOpenAccount = navigator::openAccountSettings,
                    onAddAccount = navigator::openAddAccount,
                )
                aboutSection(openUrl)
            }
        }
    }
}

private fun LazyListScope.appearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onSelectLanguage: (String) -> Unit,
) {
    item(key = "appearance") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_appearance))
            ChoicePreference(
                title = stringResource(R.string.settings_theme),
                icon = Icons.Outlined.Palette,
                options = SettingsOptions.themes,
                selected = settings.themeMode,
                optionLabel = { themeLabel(it) },
                onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
            )
            if (supportsDynamicColor) {
                SwitchPreference(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = stringResource(R.string.settings_dynamic_color_summary),
                    icon = Icons.Outlined.Wallpaper,
                    checked = settings.dynamicColor,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(dynamicColor = enabled) } },
                )
            }
            ChoicePreference(
                title = stringResource(R.string.settings_language),
                icon = Icons.Outlined.Language,
                options = AppLanguage.choices,
                selected = AppLanguage.normalize(settings.language),
                optionLabel = { languageLabel(it) },
                onSelect = onSelectLanguage,
            )
        }
    }
}

private fun LazyListScope.readingSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onForgetTrustedSenders: () -> Unit,
) {
    item(key = "reading") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_reading))
            SwitchPreference(
                title = stringResource(R.string.settings_conversation_view),
                summary = stringResource(R.string.settings_conversation_view_summary),
                icon = Icons.Outlined.Forum,
                checked = settings.conversationView,
                onCheckedChange = { enabled -> viewModel.update { it.copy(conversationView = enabled) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_show_snippets),
                summary = stringResource(R.string.settings_show_snippets_summary),
                icon = Icons.AutoMirrored.Outlined.ShortText,
                checked = settings.showSnippets,
                onCheckedChange = { enabled -> viewModel.update { it.copy(showSnippets = enabled) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_remote_images),
                summary = stringResource(R.string.settings_remote_images_summary),
                icon = Icons.Outlined.Image,
                checked = settings.loadRemoteImages,
                onCheckedChange = { enabled -> viewModel.update { it.copy(loadRemoteImages = enabled) } },
            )
            val trustedCount = settings.trustedImageSenders.size
            if (trustedCount > 0 && !settings.loadRemoteImages) {
                ForgetTrustedSendersRow(count = trustedCount, onConfirm = onForgetTrustedSenders)
            }
            SwitchPreference(
                title = stringResource(R.string.settings_confirm_delete),
                summary = stringResource(R.string.settings_confirm_delete_summary),
                icon = Icons.Outlined.DeleteOutline,
                checked = settings.confirmDelete,
                onCheckedChange = { enabled -> viewModel.update { it.copy(confirmDelete = enabled) } },
            )
            ChoicePreference(
                title = stringResource(R.string.settings_default_reply),
                icon = if (settings.defaultReplyAll) Icons.AutoMirrored.Outlined.ReplyAll else Icons.AutoMirrored.Outlined.Reply,
                options = listOf(false, true),
                selected = settings.defaultReplyAll,
                optionLabel = { replyAll -> stringResource(if (replyAll) R.string.action_reply_all else R.string.action_reply) },
                onSelect = { replyAll -> viewModel.update { it.copy(defaultReplyAll = replyAll) } },
            )
        }
    }
}

@Composable
private fun ForgetTrustedSendersRow(count: Int, onConfirm: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    ClickablePreference(
        title = stringResource(R.string.settings_trusted_senders),
        summary = pluralStringResource(R.plurals.settings_trusted_senders_summary, count, count),
        icon = Icons.Outlined.Block,
        onClick = { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_trusted_senders_confirm_title)) },
            text = { Text(stringResource(R.string.settings_trusted_senders_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onConfirm()
                    },
                ) { Text(stringResource(R.string.settings_trusted_senders_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun LazyListScope.swipeSection(settings: AppSettings, viewModel: SettingsViewModel) {
    item(key = "swipe") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_swipe))
            // Start-to-end is a swipe to the right in left-to-right languages.
            val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
            ChoicePreference(
                title = stringResource(if (ltr) R.string.settings_swipe_right else R.string.settings_swipe_left),
                icon = if (ltr) Icons.Outlined.SwipeRight else Icons.Outlined.SwipeLeft,
                options = SettingsOptions.withCurrent(SettingsOptions.swipeActions, settings.swipeStartToEnd),
                selected = settings.swipeStartToEnd,
                optionLabel = { swipeLabel(it) },
                optionIcon = ::swipeIcon,
                onSelect = { action -> viewModel.update { it.copy(swipeStartToEnd = action) } },
            )
            ChoicePreference(
                title = stringResource(if (ltr) R.string.settings_swipe_left else R.string.settings_swipe_right),
                icon = if (ltr) Icons.Outlined.SwipeLeft else Icons.Outlined.SwipeRight,
                options = SettingsOptions.withCurrent(SettingsOptions.swipeActions, settings.swipeEndToStart),
                selected = settings.swipeEndToStart,
                optionLabel = { swipeLabel(it) },
                optionIcon = ::swipeIcon,
                onSelect = { action -> viewModel.update { it.copy(swipeEndToStart = action) } },
            )
        }
    }
}

private fun LazyListScope.sendingSection(settings: AppSettings, viewModel: SettingsViewModel) {
    item(key = "sending") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_sending))
            ChoicePreference(
                title = stringResource(R.string.settings_undo_send),
                icon = Icons.AutoMirrored.Outlined.Undo,
                options = SettingsOptions.withCurrent(SettingsOptions.undoSendSeconds, settings.undoSendSeconds),
                selected = settings.undoSendSeconds,
                optionLabel = { seconds -> undoSendLabel(seconds) },
                onSelect = { seconds -> viewModel.update { it.copy(undoSendSeconds = seconds) } },
            )
        }
    }
}

private fun LazyListScope.notificationsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    systemAllowed: Boolean,
    onAllow: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    backgroundAllowed: Boolean,
    onAllowBackground: () -> Unit,
) {
    item(key = "notifications") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_notifications))
            SwitchPreference(
                title = stringResource(R.string.settings_notifications),
                summary = stringResource(R.string.settings_notifications_summary),
                icon = Icons.Outlined.NotificationsActive,
                checked = settings.notificationsEnabled,
                onCheckedChange = onEnable,
            )
            if (settings.notificationsEnabled && !systemAllowed) {
                ClickablePreference(
                    title = stringResource(R.string.settings_notifications_blocked),
                    summary = stringResource(R.string.settings_notifications_blocked_summary),
                    icon = Icons.Outlined.NotificationsOff,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = onAllow,
                )
            }
            val mailCheck = MailCheck.of(settings)
            ChoicePreference(
                title = stringResource(R.string.settings_sync_interval),
                icon = if (mailCheck == MailCheck.Push) Icons.Outlined.Bolt else Icons.Outlined.Schedule,
                options = SettingsOptions.withCurrent(SettingsOptions.mailChecks, mailCheck),
                selected = mailCheck,
                optionLabel = { check -> mailCheckLabel(check) },
                onSelect = viewModel::setMailCheck,
            )
            // Without the exemption, Doze can hold push back for long stretches.
            if (settings.pushEnabled && !backgroundAllowed) {
                ClickablePreference(
                    title = stringResource(R.string.settings_background),
                    summary = stringResource(R.string.settings_background_summary),
                    icon = Icons.Outlined.BatteryAlert,
                    onClick = onAllowBackground,
                )
            }
            ClickablePreference(
                title = stringResource(R.string.settings_system_notifications),
                summary = stringResource(R.string.settings_system_notifications_summary),
                icon = Icons.Outlined.SettingsApplications,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = onOpenSystemSettings,
            )
        }
    }
}

private fun LazyListScope.accountsSection(
    state: SettingsUiState,
    onOpenAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
) {
    item(key = "accounts-header") {
        PreferenceSectionHeader(stringResource(R.string.settings_section_accounts))
    }
    items(state.accounts, key = { "account-${it.id}" }) { account ->
        AccountRow(account = account, onClick = { onOpenAccount(account.id) })
    }
    if (state.accountsLoaded) {
        item(key = "add-account") {
            ClickablePreference(
                title = stringResource(R.string.settings_add_account),
                icon = Icons.Outlined.PersonAdd,
                onClick = onAddAccount,
            )
        }
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit) {
    val name = account.displayName.ifBlank { account.email }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (name != account.email) {
            { Text(account.email) }
        } else {
            null
        },
        leadingContent = { AccountAvatar(account) },
        trailingContent = if (!account.notificationsEnabled) {
            {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = stringResource(R.string.settings_account_notifications_off),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(),
        modifier = Modifier.clickableRow(onClick),
    )
}

private fun LazyListScope.aboutSection(openUrl: (String) -> Unit) {
    item(key = "about") {
        Column {
            PreferenceSectionHeader(stringResource(R.string.settings_section_about))
            ClickablePreference(
                title = stringResource(R.string.settings_version),
                summary = BuildConfig.VERSION_NAME,
                icon = Icons.Outlined.Info,
            )
            ClickablePreference(
                title = stringResource(R.string.settings_webmail_staff),
                summary = UpmPresets.STAFF_WEBMAIL_URL.removePrefix("https://"),
                icon = Icons.Outlined.Work,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { openUrl(UpmPresets.STAFF_WEBMAIL_URL) },
            )
            ClickablePreference(
                title = stringResource(R.string.settings_webmail_students),
                summary = UpmPresets.STUDENT_WEBMAIL_URL.removePrefix("https://"),
                icon = Icons.Outlined.School,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { openUrl(UpmPresets.STUDENT_WEBMAIL_URL) },
            )
            ClickablePreference(
                title = stringResource(R.string.settings_source_code),
                summary = SOURCE_CODE_URL.removePrefix("https://"),
                icon = Icons.Outlined.Code,
                trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = { openUrl(SOURCE_CODE_URL) },
            )
            LicensesRow(openUrl)
            ClickablePreference(
                title = stringResource(R.string.settings_unofficial),
                summary = stringResource(R.string.settings_unofficial_summary),
                icon = Icons.Outlined.Public,
            )
        }
    }
}

/** One open-source component shown in the licences dialog. */
private data class LibraryNotice(val name: Int, val license: Int, val url: String)

private val Libraries = listOf(
    LibraryNotice(R.string.settings_lib_angus, R.string.settings_license_angus, "https://eclipse-ee4j.github.io/angus-mail/"),
    LibraryNotice(R.string.settings_lib_jsoup, R.string.settings_license_mit, "https://jsoup.org/license"),
    LibraryNotice(R.string.settings_lib_androidx, R.string.settings_license_apache, "https://developer.android.com/jetpack/androidx"),
    LibraryNotice(R.string.settings_lib_kotlin, R.string.settings_license_apache, "https://kotlinlang.org/"),
)

@Composable
private fun LicensesRow(openUrl: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    ClickablePreference(
        title = stringResource(R.string.settings_licenses),
        summary = stringResource(R.string.settings_licenses_summary),
        icon = Icons.Outlined.Gavel,
        onClick = { show = true },
    )
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Libraries.forEach { library ->
                        ListItem(
                            headlineContent = { Text(stringResource(library.name)) },
                            supportingContent = { Text(stringResource(library.license)) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickableRow { openUrl(library.url) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/** Circle in the account's colour with its initial. */
@Composable
internal fun AccountAvatar(account: Account, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(account.color)),
    ) {
        Text(
            text = avatarInitial(account.displayName, account.email),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

/** "System default" is translated; each language is named in itself. */
@Composable
private fun languageLabel(language: String): String = stringResource(
    when (language) {
        AppLanguage.ENGLISH -> R.string.settings_language_english
        AppLanguage.SPANISH -> R.string.settings_language_spanish
        else -> R.string.settings_language_system
    },
)

@Composable
private fun swipeLabel(action: SwipeAction): String = stringResource(
    when (action) {
        SwipeAction.NONE -> R.string.settings_swipe_none
        SwipeAction.ARCHIVE -> R.string.action_archive
        SwipeAction.DELETE -> R.string.action_delete
        SwipeAction.TOGGLE_READ -> R.string.settings_swipe_toggle_read
        SwipeAction.TOGGLE_STAR -> R.string.settings_swipe_toggle_star
    },
)

private fun swipeIcon(action: SwipeAction): ImageVector? = when (action) {
    SwipeAction.NONE -> null
    SwipeAction.ARCHIVE -> Icons.Outlined.Archive
    SwipeAction.DELETE -> Icons.Outlined.Delete
    SwipeAction.TOGGLE_READ -> Icons.Outlined.MarkEmailRead
    SwipeAction.TOGGLE_STAR -> Icons.Outlined.StarOutline
}

@Composable
private fun undoSendLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.settings_undo_send_off)
    } else {
        pluralStringResource(R.plurals.settings_undo_send_seconds, seconds, seconds)
    }

@Composable
private fun mailCheckLabel(check: MailCheck): String = when (check) {
    MailCheck.Push -> stringResource(R.string.settings_sync_push)
    is MailCheck.Every -> syncIntervalLabel(check.minutes)
}

@Composable
private fun syncIntervalLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.settings_sync_manual)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.settings_sync_every_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.settings_sync_every_minutes, minutes, minutes)
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
