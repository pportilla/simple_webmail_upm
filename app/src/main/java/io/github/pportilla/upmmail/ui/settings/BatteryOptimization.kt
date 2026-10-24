package io.github.pportilla.upmmail.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * True when Android exempts the app from battery optimisation, so instant push keeps its
 * connection in Doze and may restart from the background (true if it cannot be told).
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean =
    try {
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
    } catch (_: RuntimeException) {
        true
    }

/**
 * Opens Android's battery settings, where the user can manage optimisation themselves.
 * This does not request the restricted direct battery-exemption permission.
 */
fun Context.requestIgnoreBatteryOptimizations(): Boolean {
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    for (intent in listOf(list, Intent(Settings.ACTION_SETTINGS))) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        } catch (_: SecurityException) {
            // Some vendors protect battery settings; try general settings.
        }
    }
    return false
}
