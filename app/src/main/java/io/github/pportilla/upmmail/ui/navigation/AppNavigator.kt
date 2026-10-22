package io.github.pportilla.upmmail.ui.navigation

import io.github.pportilla.upmmail.model.ComposeMode

/** Arguments of the compose screen. -1 / null / empty mean "not set". */
data class ComposeArgs(
    val accountId: Long = -1,
    val mode: ComposeMode = ComposeMode.NEW,
    /** Message replied to or forwarded (REPLY, REPLY_ALL, FORWARD). */
    val sourceMessageId: Long = -1,
    /** Local draft to reopen (e.g. after undoing a send). */
    val draftId: Long = -1,
    /** Message of the server Drafts folder to edit (EDIT_DRAFT). */
    val serverDraftMessageId: Long = -1,
    /** Prefill from mailto: links and share intents. Comma-separated addresses. */
    val to: String? = null,
    val cc: String? = null,
    val bcc: String? = null,
    val subject: String? = null,
    val body: String? = null,
    /** content:// URIs shared from other apps. */
    val attachmentUris: List<String> = emptyList(),
)

/**
 * Navigation actions available to every screen. Screens never touch the NavController directly,
 * which keeps them previewable and testable.
 */
interface AppNavigator {
    /** Goes to the mailbox; with [clearBackStack] the previous screens (e.g. setup) are dropped. */
    fun openMailbox(clearBackStack: Boolean = false)

    /** Opens the conversation containing [messageId]. */
    fun openMessage(messageId: Long)

    fun openCompose(args: ComposeArgs = ComposeArgs())

    /**
     * Opens the search of [accountId] (every account when null), offering [folderId] as the
     * "This folder" scope; with [query] the search box starts with that text, already searched.
     */
    fun openSearch(accountId: Long?, folderId: Long?, query: String? = null)

    fun openSettings()

    fun openAccountSettings(accountId: Long)

    /** Shows the sign-in screen to add another account. */
    fun openAddAccount()

    fun back()
}
