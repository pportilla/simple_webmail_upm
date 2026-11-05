package io.github.pportilla.upmmail.mail

import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.File

/** Protocol boundary shared by real mail and the explicitly labelled, local sample mailbox. */
interface ImapTransport : Closeable {
    val isBusy: Boolean
    val isConnected: Boolean
    val logins: Int
    suspend fun connect()
    suspend fun listFolders(): List<RemoteFolder>
    suspend fun status(folder: String): RemoteFolderStatus
    suspend fun fetchNewest(folder: String, limit: Int): FetchResult
    suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int): FetchResult
    suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int): FetchResult
    suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags>
    suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage>
    suspend fun fetchByUids(folder: String, uids: List<Long>): FetchResult
    suspend fun fetchFlagsFor(folder: String, uids: List<Long>): Map<Long, RemoteFlags>
    suspend fun fetchIndexHeaders(folder: String, uids: List<Long>): HeaderBatch
    suspend fun fetchBody(folder: String, uid: Long): ParsedBody
    suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File)
    suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean)
    suspend fun move(folder: String, uids: List<Long>, destinationFolder: String)
    suspend fun deletePermanently(folder: String, uids: List<Long>)
    suspend fun deleteAll(folder: String)
    suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long?
    suspend fun search(folder: String, query: String, limit: Int): List<Long>
    suspend fun findUidByMessageId(folder: String, messageId: String): Long?
    suspend fun examine(folder: String): FolderProbe
    suspend fun searchUids(folder: String, query: ServerQuery, range: SearchRange?, limit: Int): SearchOutcome
    suspend fun searchAllUids(folder: String, fromUid: Long = 1): UidList
    suspend fun searchUndeletedUids(folder: String): UidList
    fun derive(options: ImapOptions): ImapTransport
    fun abort()
}

interface SmtpTransport {
    suspend fun verify(policyChanges: StateFlow<*>? = null, authorize: () -> Unit = {})
    suspend fun send(message: MimeMessage, policyChanges: StateFlow<*>? = null, authorize: () -> Unit = {})
}

interface IdleTransport {
    val idleSupported: Boolean?
    suspend fun run(onIdle: () -> Unit = {}, onChange: (ImapIdler.Change) -> Unit)
    suspend fun refresh(): Boolean
}
