package io.github.pportilla.upmmail.ui.navigation

/** Intent extras understood by MainActivity (used by notifications). */
object DeepLinks {
    /** Long: local message id to open. */
    const val EXTRA_MESSAGE_ID = "io.github.pportilla.upmmail.extra.MESSAGE_ID"

    /** Long: account whose INBOX should be shown (e.g. tapping a summary notification). */
    const val EXTRA_ACCOUNT_ID = "io.github.pportilla.upmmail.extra.ACCOUNT_ID"

    /** Boolean: show the outbox (e.g. after a send failure notification). */
    const val EXTRA_OPEN_OUTBOX = "io.github.pportilla.upmmail.extra.OPEN_OUTBOX"

    /**
     * Virtual folder id of the local outbox. Stored in AppSettings.lastFolderId to make the
     * mailbox show queued/failed outgoing messages instead of a server folder.
     */
    const val OUTBOX_FOLDER_ID = -2L
}
