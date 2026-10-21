package io.github.pportilla.upmmail.ui.setup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings

/**
 * Editable text of one server (host, port, security, login name), shared by the sign-in screen
 * and the account settings. Field text is kept verbatim; [toSettings] validates it.
 */
@Stable
class ServerFormState(
    val kind: ServerKind,
    host: String = "",
    port: String = "",
    security: SecurityMode = if (kind == ServerKind.IMAP) SecurityMode.SSL_TLS else SecurityMode.STARTTLS,
    username: String = "",
) {
    var host by mutableStateOf(host)
    var port by mutableStateOf(port)
    var security by mutableStateOf(security)
        private set
    var username by mutableStateOf(username)

    /** Changes the security mode, moving the port along when it was the old mode's default. */
    fun changeSecurity(value: SecurityMode) {
        if (value == security) return
        port = portAfterSecurityChange(kind, port, security, value)
        security = value
    }

    /** Replaces every field with [settings]. */
    fun load(settings: ServerSettings) {
        host = settings.host
        port = settings.port.toString()
        security = settings.security
        username = settings.username
    }

    fun errors(): ServerFieldErrors = validateServerFields(host, port, username)

    /** The settings in the form, or null while a field is invalid. */
    fun toSettings(): ServerSettings? = serverSettingsOf(host, port, security, username)

    internal fun save(): List<String> = listOf(kind.name, host, port, security.name, username)

    companion object {
        fun from(kind: ServerKind, settings: ServerSettings): ServerFormState =
            ServerFormState(kind).apply { load(settings) }

        internal fun restore(values: List<String>): ServerFormState {
            val kind = ServerKind.entries.firstOrNull { it.name == values[0] } ?: ServerKind.IMAP
            return ServerFormState(
                kind = kind,
                host = values[1],
                port = values[2],
                security = SecurityMode.entries.firstOrNull { it.name == values[3] } ?: SecurityMode.SSL_TLS,
                username = values[4],
            )
        }

        /** Number of entries [save] produces. */
        internal const val SAVED_SIZE = 5
    }
}
