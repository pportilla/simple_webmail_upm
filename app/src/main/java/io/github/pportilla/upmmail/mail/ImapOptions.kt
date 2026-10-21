package io.github.pportilla.upmmail.mail

/**
 * How an [ImapClient] uses its connection.
 *
 * [readTimeoutMillis] is how long the server may stay silent while a command runs. An
 * [abortable] client can be stopped while a command is running: cancelling the caller (or
 * [ImapClient.abort]) closes the TCP socket at once instead of waiting for the server. A client
 * that keeps folders open ([keepFolderOpen]) leaves the last folder it read selected, so a
 * series of commands on one folder costs one EXAMINE instead of an EXAMINE and a CLOSE each.
 */
data class ImapOptions(
    val readTimeoutMillis: Int = MailSessions.READ_TIMEOUT_MS,
    val abortable: Boolean = false,
    val keepFolderOpen: Boolean = false,
) {
    companion object {
        /** The account's main connection: sync, sending of pending changes, message bodies. */
        val DEFAULT = ImapOptions()

        /** Server search: a server without a full-text index can take a minute over one window. */
        val SEARCH = ImapOptions(readTimeoutMillis = 75_000, abortable = true, keepFolderOpen = true)

        /** Building the header index in the background. */
        val INDEX = ImapOptions(readTimeoutMillis = 60_000, abortable = true, keepFolderOpen = true)
    }
}
