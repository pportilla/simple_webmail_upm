package io.github.pportilla.upmmail.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** SharedPreferences file for small UI flags that are not user settings. */
private const val UI_STATE_PREFS = "ui_state"
private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

/** Runtime notification permission exists from Android 13 (API 33) on. */
val needsNotificationPermission: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** True once the app has shown the system notification-permission prompt. */
fun Context.wasNotificationPermissionAsked(): Boolean =
    getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)

fun Context.markNotificationPermissionAsked() {
    getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)
        .apply()
}

fun Context.hasNotificationPermission(): Boolean =
    !needsNotificationPermission ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** False when the user blocked the app's notifications (or did not grant the permission). */
fun Context.areAppNotificationsEnabled(): Boolean =
    try {
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
    } catch (_: RuntimeException) {
        true
    }

/** Opens the system notification settings of this app (or its app info page as a fallback). */
fun Context.openAppNotificationSettings(): Boolean {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    for (intent in listOf(notificationSettings, appDetails)) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    return false
}
