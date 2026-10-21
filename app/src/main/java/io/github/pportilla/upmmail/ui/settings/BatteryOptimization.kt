package io.github.pportilla.upmmail.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Asks Android to exempt the app from battery optimisation: the system dialog if available,
 * otherwise the list of optimised apps. Returns false if neither could be opened.
 */
fun Context.requestIgnoreBatteryOptimizations(): Boolean {
    val dialog = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    for (intent in listOf(dialog, list)) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        } catch (_: SecurityException) {
            // Some vendors protect the dialog; try the next one.
        }
    }
    return false
}
