package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.internet.MimeMessage
import java.io.Closeable
import java.io.File

/**
 * IMAP access for one account. All functions run on Dispatchers.IO, are serialised (one command
 * at a time per client), reconnect once on a dropped connection and throw MailException.
 */
class ImapClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
) : Closeable {

    /** Connects and logs in (used to validate credentials). */
    suspend fun connect(): Unit = TODO()

    suspend fun listFolders(): List<RemoteFolder> = TODO()

    suspend fun status(folder: String): RemoteFolderStatus = TODO()

    /** The newest [limit] messages of [folder]. */
    suspend fun fetchNewest(folder: String, limit: Int): FetchResult = TODO()

    /** Messages with uid > [afterUid]; the newest [limit] of them, truncated = true if there were more. */
    suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int): FetchResult = TODO()

    /** The newest [limit] messages with uid < [beforeUid]; truncated = true if even older ones exist. */
    suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int): FetchResult = TODO()

    /** Flags of every message present in [fromUid]..[toUid]; a missing uid was expunged. */
    suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags> = TODO()

    suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage> = TODO()

    /** Downloads and parses the body. Never sets \Seen. */
    suspend fun fetchBody(folder: String, uid: Long): ParsedBody = TODO()

    /** Writes the decoded content of part [partPath] to [destination]. */
    suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File): Unit = TODO()

    suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean): Unit = TODO()

    /** Uses MOVE when supported, otherwise COPY + \Deleted + (UID) EXPUNGE. */
    suspend fun move(folder: String, uids: List<Long>, destinationFolder: String): Unit = TODO()

    suspend fun deletePermanently(folder: String, uids: List<Long>): Unit = TODO()

    /** Permanently deletes every message in [folder]. */
    suspend fun deleteAll(folder: String): Unit = TODO()

    /** Appends [message]; returns its new uid when the server reports it (UIDPLUS). */
    suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long? = TODO()

    /** Server-side search in subject, from, to, cc and body. Uids newest first, at most [limit]. */
    suspend fun search(folder: String, query: String, limit: Int): List<Long> = TODO()

    suspend fun findUidByMessageId(folder: String, messageId: String): Long? = TODO()

    override fun close(): Unit = TODO()
}
