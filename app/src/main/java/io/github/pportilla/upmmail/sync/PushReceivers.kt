package io.github.pportilla.upmmail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.pportilla.upmmail.UpmMailApp
import io.github.pportilla.upmmail.data.DefaultAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts push after a reboot or an app update. Both broadcasts are among the few moments when
 * Android 12+ allows starting a foreground service from the background.
 */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = context.pushContainer() ?: return
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.pushController.startIfWanted()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The keep-alive alarm of [PushController]. The broadcast is kept open while the connections
 * are checked, so the alarm's wake lock keeps the device awake for exactly that long.
 */
class PushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.pushContainer() ?: return
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.pushController.onAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}

private fun Context.pushContainer(): DefaultAppContainer? =
    (applicationContext as? UpmMailApp)?.container as? DefaultAppContainer
