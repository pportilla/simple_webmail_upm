package io.github.pportilla.upmmail.model

/** Broad failure categories the UI can explain to the user in plain words. */
enum class MailErrorKind {
    /** Wrong username or password (or the account is locked). */
    AUTHENTICATION,
    /** No connectivity, DNS failure, timeout, connection reset. */
    NETWORK,
    /** TLS handshake / certificate validation failed. */
    CERTIFICATE,
    /** The server answered with an error (NO/BAD, 5xx...). */
    SERVER,
    /** Folder or message no longer exists on the server. */
    NOT_FOUND,
    /** One or more recipients were rejected by the SMTP server. */
    INVALID_RECIPIENT,
    /** Message too large for the server. */
    TOO_LARGE,
    UNKNOWN,
}

/**
 * The single exception type that crosses layer boundaries. Repositories return
 * `Result.failure(MailException)`; screens map [kind] to a localised message and may show
 * [message] as technical detail.
 */
class MailException(
    val kind: MailErrorKind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: kind.name, cause) {
    /** Network-type failures are worth retrying automatically; the rest need the user. */
    val isTransient: Boolean get() = kind == MailErrorKind.NETWORK
}
