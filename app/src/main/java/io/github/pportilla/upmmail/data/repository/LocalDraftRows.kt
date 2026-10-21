package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.db.UnsyncedDraft
import io.github.pportilla.upmmail.data.db.UnsyncedDrafts
import io.github.pportilla.upmmail.mail.HtmlText
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.MessageSummary

/**
 * Drafts whose newest version is only on this device (never uploaded, upload waiting, or upload
 * failed; see DraftDao) are listed in the account's Drafts folder next to the server's drafts,
 * in place of the older server copy they replace. So a saved draft can always be found again,
 * offline too.
 *
 * Their rows use ids from [ID_BASE] up, which no cached message can have (message ids count up
 * from 1). Opening such a row edits the local draft (ComposeRepositoryImpl.draftFromServerMessage),
 * deleting it discards the draft (MailRepositoryImpl.delete); other actions do not apply to it.
 */
internal object LocalDraftRows {
    const val ID_BASE = 1L shl 50

    fun rowId(draftId: Long): Long = ID_BASE + draftId

    /** The local draft a row id stands for, or null for a cached message. */
    fun draftId(rowId: Long): Long? = if (rowId > ID_BASE) rowId - ID_BASE else null

    /**
     * The cached rows of [folder] (a Drafts folder) with the unsynced drafts of its account added
     * and the server copies they replace left out; newest first, at most [limit] rows.
     */
    fun merge(cached: List<MessageSummary>, local: UnsyncedDrafts, folder: Folder, from: EmailAddress?, limit: Int): List<MessageSummary> {
        if (local.drafts.isEmpty()) return cached
        val shown = cached.filter { it.id !in local.replacedMessageIds }
        return (shown + local.drafts.map { summaryOf(it, folder, from) })
            .sortedWith(compareByDescending<MessageSummary> { it.date }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(1))
    }

    private fun summaryOf(draft: UnsyncedDraft, folder: Folder, from: EmailAddress?) = MessageSummary(
        id = rowId(draft.id),
        accountId = draft.accountId,
        folderId = folder.id,
        uid = 0,
        threadId = "local-draft-${draft.id}",
        subject = draft.subject,
        from = from,
        to = draft.to,
        date = draft.updatedAt,
        snippet = runCatching { HtmlText.snippet(draft.bodyStart, null) }.getOrDefault(""),
        isRead = true,
        isStarred = false,
        isAnswered = false,
        isForwarded = false,
        isDraft = true,
        hasAttachments = draft.hasAttachments,
    )
}
