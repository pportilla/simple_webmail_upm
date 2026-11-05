package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings

/**
 * Enforced before account verification and before opening a persisted account's connections.
 * Android's network security configuration cannot enforce TLS on Jakarta Mail's raw sockets.
 * NONE remains readable in old databases and usable by isolated protocol tests, but an app
 * account must explicitly use TLS or required STARTTLS before its credentials can be sent.
 */
fun requireEncryptedTransport(settings: ServerSettings) {
    if (settings.security == SecurityMode.NONE) {
        throw MailException(MailErrorKind.INSECURE_CONNECTION, "Choose SSL/TLS or STARTTLS in the account server settings")
    }
}
