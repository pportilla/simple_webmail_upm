package io.github.pportilla.upmmail.data.safety

import android.content.Context
import io.github.pportilla.upmmail.UpmMailApp

/** Android callbacks may outlive a screen or profile; check again at the external boundary. */
fun Context.hasAdultExternalAccess(): Boolean = runCatching {
    val state = (applicationContext as? UpmMailApp)?.container?.safetyRepository?.state?.value
    state?.mailboxAccessAllowed == true
}.getOrDefault(false)

fun Context.requireAdultExternalAccess() {
    if (!hasAdultExternalAccess()) throw SecurityException("External content is disabled for this profile")
}
