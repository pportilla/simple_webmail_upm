package io.github.pportilla.upmmail.mail.demo

import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Serializes construction and deletion for each persisted sample-account identity. */
class DemoBackendRegistry(private val root: File, private val lookup: (Long) -> Account?) {
    private val backends = ConcurrentHashMap<Long, DemoMailBackend>()

    fun get(accountId: Long): DemoMailBackend = checkNotNull(backends.compute(accountId) { _, existing ->
        // Re-read inside the same per-key lock used by remove. A captured Account may already
        // have been deleted, and must never recreate a mailbox after its cleanup completed.
        val account = lookup(accountId)?.takeIf { it.isDemo && it.id == accountId }
            ?: throw MailException(MailErrorKind.NOT_FOUND, "Sample account was removed")
        existing ?: DemoMailBackend(root, account)
    })

    /** The caller removes the database row first. Retained clients are then invalidated. */
    fun remove(accountId: Long) {
        require(accountId > 0)
        backends.compute(accountId) { _, existing ->
            if (existing != null) existing.delete()
            else {
                val directory = File(root, accountId.toString())
                if (directory.exists() && !directory.deleteRecursively()) {
                    throw MailException(MailErrorKind.UNKNOWN, "Could not remove the local sample mailbox")
                }
            }
            null
        }
    }
}
