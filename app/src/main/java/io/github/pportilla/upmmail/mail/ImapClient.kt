package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.util.SharedFileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.iap.Argument
import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPInputStream
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPSSLStore
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.eclipse.angus.mail.imap.protocol.SearchSequence
import org.eclipse.angus.mail.imap.protocol.Status
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * IMAP access for one account. All functions run on Dispatchers.IO, are serialised (one command
 * at a time per client), reconnect once on a dropped connection and throw MailException. A
 * server that stops answering (read timeout) is not retried.
 *
 * The connection is opened lazily and kept between calls; a connection idle for a few minutes
 * is replaced by a fresh one rather than trusted. Folders are opened for each operation
 * (read-only for reads, so the server never sets \Seen) and closed afterwards, unless [options]
 * keep the last folder read open for the next call. [close] drops the connection; a later call
 * reconnects.
 *
 * With [ImapOptions.abortable], cancelling a caller closes the connection's socket at once, so a
 * slow server search stops when the user leaves the screen; the next call logs in again.
 */
class ImapClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
    private val options: ImapOptions,
) : ImapTransport {

    /** A client with [ImapOptions.DEFAULT]; also keeps `ImapClient(settings) { password }` working. */
    constructor(settings: ServerSettings, passwordProvider: () -> String) :
        this(settings, passwordProvider, ImapOptions.DEFAULT)

    /** The sockets of an abortable client; null for the others. */
    private val sockets: AbortableSocketFactory? = if (options.abortable) AbortableSocketFactory() else null
    private val session: Session by lazy { createSession() }
    private val mutex = Mutex()
    private val stateLock = Any()

    // Guarded by stateLock.
    private var store: IMAPStore? = null
    private var lastActivityAt = 0L
    private var generation = 0
    private var loginCount = 0

    /** The folder left open for the next call ([ImapOptions.keepFolderOpen]); always on [store]. */
    private var kept: KeptFolder? = null

    private class KeptFolder(val store: IMAPStore, val name: String, val folder: IMAPFolder)

    /** Capabilities treated as absent (upper case), so tests can exercise the fallbacks. */
    internal var disabledCapabilities: Set<String> = emptySet()

    /** False makes every message take the local parsing path, so tests can exercise it. */
    internal var trustServerStructure = true

    /** Protocol trace for tests; must be set before the first call. */
    internal var debugOut: PrintStream? = null

    /** True while a call is running on this client. */
    override val isBusy: Boolean
        get() = mutex.isLocked

    /** Whether a logged-in connection is open (the server may still have dropped it silently). */
    override val isConnected: Boolean
        get() = synchronized(stateLock) { store != null }

    /**
     * Connections this client has opened and logged in on. A call that started without a
     * connection and timed out without adding one never got past connecting and logging in.
     */
    override val logins: Int
        get() = synchronized(stateLock) { loginCount }

    init {
        MailSessions.ensureInitialized()
    }

    /** Connects and logs in (used to validate credentials). */
    override suspend fun connect() {
        // A real login check: never answer from an already open connection.
        discardStore()
        execute { }
    }

    /**
     * Every folder of the account. Folders in a shared or other users' namespace (RFC 2342) carry
     * [NOT_PERSONAL_ATTRIBUTE].
     */
    override suspend fun listFolders(): List<RemoteFolder> = execute { store ->
        releaseKeptFolder()
        val prefixes = personalNamespacePrefixes(store)
        val foreignPrefixes = foreignNamespacePrefixes(store)
        val listed = store.defaultFolder.list("*")
        val folders = ArrayList<RemoteFolder>(listed.size + 1)
        for (folder in listed) {
            val imapFolder = folder as? IMAPFolder ?: continue
            val listedAttributes = imapFolder.attributes.orEmpty().map { it.lowercase(Locale.ROOT) }.toSet()
            if ("\\nonexistent" in listedAttributes) continue
            val fullName = imapFolder.fullName
            if (fullName.isEmpty()) continue
            val separator = safeSeparator(imapFolder)
            val attributes = if (isForeign(fullName, foreignPrefixes, prefixes)) {
                listedAttributes + NOT_PERSONAL_ATTRIBUTE
            } else {
                listedAttributes
            }
            folders += RemoteFolder(
                fullName = fullName,
                name = imapFolder.name.ifEmpty { fullName },
                separator = separator,
                attributes = attributes,
                selectable = "\\noselect" !in attributes,
                depth = depthOf(fullName, separator, prefixes),
            )
        }
        if (folders.none { it.fullName.equals(INBOX, ignoreCase = true) }) {
            val separator = folders.firstOrNull()?.separator ?: '/'
            folders.add(0, RemoteFolder(INBOX, INBOX, separator, emptySet(), selectable = true, depth = 0))
        }
        folders
    }

    override suspend fun status(folder: String): RemoteFolderStatus = execute { store ->
        val imapFolder = store.getFolder(folder) as IMAPFolder
        // A folder kept open holds the connection: it asks for another folder's status itself,
        // instead of the store logging in a second connection. The selected folder is never asked
        // this way (RFC 3501 discourages STATUS on it).
        val asking = keptFolderOtherThan(store, folder) ?: imapFolder.also { releaseKeptFolder() }
        val status = try {
            asking.doCommand { protocol -> protocol.status(imapFolder.fullName, STATUS_ITEMS) } as Status?
        } catch (e: MessagingException) {
            if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e)) throw e
            releaseKeptFolder()
            if (!imapFolder.exists()) throw FolderNotFoundException(imapFolder, "$folder does not exist")
            null
        }
        if (status != null && status.total >= 0 && status.unseen >= 0 && status.uidnext >= 0 &&
            status.uidvalidity >= 0
        ) {
            RemoteFolderStatus(status.uidvalidity, status.uidnext, status.total, status.unseen)
        } else {
            // The server's STATUS answer was incomplete; ask the selected folder instead.
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                RemoteFolderStatus(open.uidValidity, open.uidNext, open.messageCount, open.unreadMessageCount)
            }
        }
    }

    /** The newest [limit] messages of [folder]. */
    override suspend fun fetchNewest(folder: String, limit: Int): FetchResult = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val uidValidity = open.uidValidity
            val count = open.messageCount
            if (count <= 0 || limit <= 0) {
                FetchResult(uidValidity, emptyList(), truncated = count > 0)
            } else {
                val start = maxOf(1, count - limit + 1)
                val messages = open.getMessages(start, count).toList()
                FetchResult(uidValidity, fetchAndMap(open, messages), truncated = start > 1)
            }
        }
    }

    /** Messages with uid > [afterUid]; the newest [limit] of them, truncated = true if there were more. */
    override suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int): FetchResult = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val uidValidity = open.uidValidity
            val from = maxOf(afterUid, 0L) + 1
            if (from > UIDFolder.MAXUID) {
                FetchResult(uidValidity, emptyList(), truncated = false)
            } else {
                // A range past the last uid makes IMAP return the last message: filter it out.
                val candidates = open.getMessagesByUID(from, UIDFolder.MAXUID)
                    .map { it to open.getUID(it) }
                    .filter { (_, uid) -> uid > afterUid }
                    .sortedBy { (_, uid) -> uid }
                val selected = if (limit <= 0) emptyList() else candidates.takeLast(limit)
                FetchResult(uidValidity, fetchAndMap(open, selected.map { it.first }), candidates.size > selected.size)
            }
        }
    }

    /** The newest [limit] messages with uid < [beforeUid]; truncated = true if even older ones exist. */
    override suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int): FetchResult = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val uidValidity = open.uidValidity
            if (beforeUid <= 1) {
                FetchResult(uidValidity, emptyList(), truncated = false)
            } else {
                // Only UIDs are fetched for the whole range; the full profile only for the page.
                val candidates = open.getMessagesByUID(1, beforeUid - 1)
                    .map { it to open.getUID(it) }
                    .filter { (_, uid) -> uid in 1 until beforeUid }
                    .sortedBy { (_, uid) -> uid }
                val selected = if (limit <= 0) emptyList() else candidates.takeLast(limit)
                FetchResult(uidValidity, fetchAndMap(open, selected.map { it.first }), candidates.size > selected.size)
            }
        }
    }

    /** Flags of every message present in [fromUid]..[toUid]; a missing uid was expunged. */
    override suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags> {
        val from = maxOf(fromUid, 1L)
        val to = minOf(toUid, UIDFolder.MAXUID)
        if (from > to) return emptyMap()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                val messages = open.getMessagesByUID(from, to)
                forgetFetched(messages.asList())
                if (messages.isNotEmpty()) open.fetch(messages, flagsProfile())
                val result = LinkedHashMap<Long, RemoteFlags>(messages.size)
                for (message in messages) {
                    checkCancelled()
                    if (message.isExpunged) continue
                    val uid = open.getUID(message)
                    if (uid in from..to) result[uid] = HeaderValues.flags(message.flags)
                }
                forgetFetched(messages.asList())
                result
            }
        }
    }

    override suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage> {
        val wanted = uids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return emptyList()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open -> fetchByUidsIn(open, wanted) }
        }
    }

    /** Like [fetchMessages], with the folder's UIDVALIDITY to check the uids against. */
    override suspend fun fetchByUids(folder: String, uids: List<Long>): FetchResult {
        val wanted = uids.filter { it > 0 }.distinct()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                FetchResult(open.uidValidity, fetchByUidsIn(open, wanted), truncated = false)
            }
        }
    }

    /**
     * Flags of the messages of [uids] that still exist, in chunks of [UID_CHUNK] for sparse uid
     * sets; a uid missing from the result was expunged. \Deleted messages are included.
     */
    override suspend fun fetchFlagsFor(folder: String, uids: List<Long>): Map<Long, RemoteFlags> {
        val wanted = uids.filter { it in 1..UIDFolder.MAXUID }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                val result = LinkedHashMap<Long, RemoteFlags>(wanted.size)
                for (chunk in wanted.chunked(UID_CHUNK)) {
                    checkCancelled()
                    val requested = chunk.toHashSet()
                    val messages = open.getMessagesByUID(chunk.toLongArray()).filterNotNull().filter { !it.isExpunged }
                    if (messages.isEmpty()) continue
                    forgetFetched(messages)
                    open.fetch(messages.toTypedArray(), flagsProfile())
                    for (message in messages) {
                        if (message.isExpunged) continue
                        val uid = open.getUID(message)
                        if (uid in requested) result[uid] = HeaderValues.flags(message.flags)
                    }
                    forgetFetched(messages)
                }
                result
            }
        }
    }

    /**
     * The fields the header index keeps, for [uids] in chunks of [INDEX_CHUNK]. Only ENVELOPE,
     * INTERNALDATE, RFC822.SIZE, FLAGS and the Content-Type header are fetched (no BODYSTRUCTURE),
     * so the attachment flag is a guess from the top-level type. A message whose fields cannot be
     * parsed still gets an (empty) entry, so it is not fetched again and again; uids the server
     * no longer has are listed in [HeaderBatch.missingUids].
     */
    override suspend fun fetchIndexHeaders(folder: String, uids: List<Long>): HeaderBatch {
        val wanted = uids.filter { it in 1..UIDFolder.MAXUID }.distinct()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                val headers = ArrayList<IndexedHeader>(wanted.size)
                val missing = ArrayList<Long>()
                for (chunk in wanted.chunked(INDEX_CHUNK)) {
                    checkCancelled()
                    val found = open.getMessagesByUID(chunk.toLongArray())
                    val present = ArrayList<Message>(chunk.size)
                    val presentUids = ArrayList<Long>(chunk.size)
                    for (index in chunk.indices) {
                        val message = found.getOrNull(index)
                        if (message == null || message.isExpunged) {
                            missing += chunk[index]
                        } else {
                            present += message
                            presentUids += chunk[index]
                        }
                    }
                    if (present.isEmpty()) continue
                    forgetFetched(present)
                    open.fetch(present.toTypedArray(), indexProfile())
                    for (index in present.indices) {
                        val message = present[index] as? IMAPMessage
                        if (message == null || message.isExpunged) {
                            missing += presentUids[index]
                        } else {
                            headers += toIndexedHeader(presentUids[index], message)
                        }
                    }
                    forgetFetched(present)
                }
                headers.sortBy { it.uid }
                missing.sort()
                HeaderBatch(open.uidValidity, headers, missing)
            }
        }
    }

    /** Downloads and parses the body. Never sets \Seen. */
    override suspend fun fetchBody(folder: String, uid: Long): ParsedBody = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val message = messageByUid(open, uid)
            withStructure(message) { part -> MimeParser.parse(part) }
        }
    }

    /** Writes the decoded content of part [partPath] to [destination]. */
    override suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File): Unit = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val message = messageByUid(open, uid)
            withStructure(message) { root ->
                val part = MimeParser.findPart(root, partPath)
                    ?: throw MailException(MailErrorKind.NOT_FOUND, "Part $partPath of message $uid not found")
                writeAtomically(destination) { out ->
                    if (root is IMAPMessage && MimeParser.isPlainEncapsulatedMessage(part)) {
                        // BODY[n] of a message/rfc822 part should be the whole message, but some
                        // servers return only its body; HEADER followed by TEXT is unambiguous.
                        val section = partPath.trim()
                        val stream = SequenceInputStream(
                            IMAPInputStream(root, "$section.HEADER", -1, true),
                            IMAPInputStream(root, "$section.TEXT", -1, true),
                        )
                        MimeParser.copy(stream, out) { checkCancelled() }
                    } else {
                        MimeParser.writeContent(part, out) { checkCancelled() }
                    }
                }
            }
        }
    }

    override suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean) {
        val wanted = uids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return
        execute { store ->
            store.withFolder(folder, Folder.READ_WRITE) { open ->
                requireWritable(open)
                val flags = flagsOf(flag)
                if (flag == MessageFlag.FORWARDED && !acceptsKeyword(open, HeaderValues.FORWARDED_KEYWORD)) {
                    return@withFolder
                }
                for (chunk in wanted.chunked(UID_CHUNK)) {
                    checkCancelled()
                    val messages = open.getMessagesByUID(chunk.toLongArray()).filterNotNull().toTypedArray()
                    if (messages.isEmpty()) continue
                    try {
                        open.setFlags(messages, flags, value)
                    } catch (e: MessagingException) {
                        // A keyword the server refuses is not worth failing for.
                        if (flag != MessageFlag.FORWARDED || MailErrors.isConnectionDrop(e)) throw e
                    }
                }
            }
        }
    }

    /** Uses MOVE when supported, otherwise COPY + \Deleted + (UID) EXPUNGE. */
    override suspend fun move(folder: String, uids: List<Long>, destinationFolder: String) {
        val wanted = uids.filter { it > 0 }.distinct()
        if (wanted.isEmpty() || folder == destinationFolder) return
        execute(retryAfterMutation = false) { store ->
            val canMove = hasCapability(store, "MOVE")
            val uidPlus = hasCapability(store, "UIDPLUS")
            store.withFolder(folder, Folder.READ_WRITE) { open ->
                requireWritable(open)
                val destination = store.getFolder(destinationFolder)
                for (chunk in wanted.chunked(UID_CHUNK)) {
                    checkCancelled()
                    val messages = open.getMessagesByUID(chunk.toLongArray()).filterNotNull().toTypedArray()
                    if (messages.isEmpty()) continue
                    if (canMove) {
                        // MOVE is atomic, so repeating it after a dropped connection is harmless.
                        open.moveMessages(messages, destination)
                    } else {
                        mutationStarted = true
                        open.copyMessages(messages, destination)
                        open.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                        expunge(open, messages, uidPlus)
                    }
                }
            }
        }
    }

    override suspend fun deletePermanently(folder: String, uids: List<Long>) {
        val wanted = uids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return
        execute { store ->
            val uidPlus = hasCapability(store, "UIDPLUS")
            store.withFolder(folder, Folder.READ_WRITE) { open ->
                requireWritable(open)
                for (chunk in wanted.chunked(UID_CHUNK)) {
                    checkCancelled()
                    val messages = open.getMessagesByUID(chunk.toLongArray()).filterNotNull().toTypedArray()
                    if (messages.isEmpty()) continue
                    open.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                    expunge(open, messages, uidPlus)
                }
            }
        }
    }

    /** Permanently deletes every message in [folder]. */
    override suspend fun deleteAll(folder: String): Unit = execute { store ->
        store.withFolder(folder, Folder.READ_WRITE) { open ->
            requireWritable(open)
            val count = open.messageCount
            if (count > 0) {
                open.setFlags(1, count, Flags(Flags.Flag.DELETED), true)
                open.expunge()
            }
        }
    }

    /** Appends [message]; returns its new uid when the server reports it (UIDPLUS). */
    override suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long? =
        execute(retryAfterMutation = false) { store ->
            releaseKeptFolder()
            val target = store.getFolder(folder) as IMAPFolder
            // Checks the connection and the folder before anything is sent, so that a stale
            // connection is retried without risking a duplicate message.
            if (!target.exists()) throw FolderNotFoundException(target, "$folder does not exist")
            message.setFlag(Flags.Flag.SEEN, seen)
            message.setFlag(Flags.Flag.DRAFT, draft)
            mutationStarted = true
            val result = target.appendUIDMessages(arrayOf<Message>(message))
            result.firstOrNull()?.uid?.takeIf { it > 0 }
        }

    /** Server-side search in subject, from, to, cc and body. Uids newest first, at most [limit]. */
    override suspend fun search(folder: String, query: String, limit: Int): List<Long> {
        val text = query.trim()
        if (text.isEmpty() || limit <= 0) return emptyList()
        val term = OrTerm(
            arrayOf(
                SubjectTerm(text),
                FromStringTerm(text),
                RecipientStringTerm(Message.RecipientType.TO, text),
                RecipientStringTerm(Message.RecipientType.CC, text),
                BodyTerm(text),
            ),
        )
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                uidSearch(open, term).sortedDescending().take(limit)
            }
        }
    }

    override suspend fun findUidByMessageId(folder: String, messageId: String): Long? {
        val id = messageId.trim()
        if (id.isEmpty()) return null
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                uidSearch(open, HeaderTerm("Message-ID", id)).maxOrNull()
            }
        }
    }

    /**
     * Opens [folder] read-only with a fresh EXAMINE and reports what the server said. A client
     * that keeps folders open leaves it open, so the sequence numbers of the probe stay valid for
     * the searches that follow.
     */
    override suspend fun examine(folder: String): FolderProbe = execute { store ->
        releaseKeptFolder()
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            FolderProbe(open.uidValidity, open.uidNext, open.messageCount)
        }
    }

    /**
     * Server-side search for [query] within [range] of [folder] (null: the whole folder). Uids
     * newest first, at most [limit]. A [SearchRange.Seq] beyond the folder's last message is
     * clamped, and one entirely past it is answered without a command. If the server refuses
     * `CHARSET UTF-8` ([BADCHARSET]), the query is searched again with its ASCII spellings only.
     */
    override suspend fun searchUids(folder: String, query: ServerQuery, range: SearchRange?, limit: Int): SearchOutcome =
        execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                val uidValidity = open.uidValidity
                val uidNext = open.uidNext
                val count = open.messageCount
                val prefix = rangeAtoms(range, count)
                if (prefix == null) {
                    SearchOutcome(uidValidity, uidNext, count, emptyList(), totalMatches = 0)
                } else {
                    var charsetRejected = false
                    val found = when (val raw = uidSearchRaw(open, prefix, query.toSearchTerm(), allowCharsetFallback = true)) {
                        is RawSearch.Uids -> raw.uids
                        RawSearch.CharsetRejected -> {
                            charsetRejected = true
                            query.asciiOnly()?.let { ascii -> uidSearch(open, prefix, ascii.toSearchTerm()) }.orEmpty()
                        }
                    }
                    // "UID n:*" also matches the newest message when every uid is below n.
                    val from = (range as? SearchRange.UidsFrom)?.firstUid ?: 0L
                    val matches = found.filter { it >= from }.sortedDescending()
                    SearchOutcome(
                        uidValidity = uidValidity,
                        uidNext = uidNext,
                        messageCount = count,
                        uids = matches.take(limit.coerceAtLeast(0)),
                        totalMatches = matches.size,
                        charsetRejected = charsetRejected,
                    )
                }
            }
        }

    /** Every uid from [fromUid] on (`UID SEARCH UID n:*`), ascending. */
    override suspend fun searchAllUids(folder: String, fromUid: Long): UidList = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val count = open.messageCount
            val from = maxOf(1L, fromUid)
            val uids = if (count <= 0 || from > UIDFolder.MAXUID) {
                LongArray(0)
            } else {
                uidSearch(open, listOf("UID", "$from:*"), null).filter { it >= from }.sorted().toLongArray()
            }
            UidList(open.uidValidity, open.uidNext, count, uids)
        }
    }

    /** Every uid not marked \Deleted (`UID SEARCH UNDELETED`), ascending. */
    override suspend fun searchUndeletedUids(folder: String): UidList = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val count = open.messageCount
            val uids = if (count <= 0) LongArray(0) else uidSearch(open, listOf("UNDELETED"), null).sorted().toLongArray()
            UidList(open.uidValidity, open.uidNext, count, uids)
        }
    }

    /**
     * A client with the same account and login but other [options], and a connection of its own.
     * The test hooks are copied.
     */
    override fun derive(options: ImapOptions): ImapClient = ImapClient(settings, passwordProvider, options).also {
        it.disabledCapabilities = disabledCapabilities
        it.trustServerStructure = trustServerStructure
        it.debugOut = debugOut
    }

    /**
     * Stops the running call, if any, by closing the connection's sockets: the call fails at once
     * (a cancelled caller sees the cancellation) and nothing more is sent, not even LOGOUT. The
     * next call logs in again. A client that is not [ImapOptions.abortable] cannot reach its
     * sockets, so there this is the same as [close].
     */
    override fun abort() {
        val sockets = sockets ?: return close()
        val old = synchronized(stateLock) {
            generation++
            kept = null
            store.also { store = null }
        }
        sockets.closeAll()
        // The sockets are closed, so closing the store only releases it: nothing reaches the server.
        old?.let(::closeInBackground)
    }

    /** Drops the connection (LOGOUT in the background); an abortable client that is busy is aborted instead. */
    override fun close() {
        if (sockets != null && isBusy) {
            abort()
            return
        }
        val old = synchronized(stateLock) {
            generation++
            kept = null
            store.also { store = null }
        } ?: return
        closeInBackground(old)
    }

    // ---------------------------------------------------------------------------------------
    // Connection handling

    /** State of one attempt of an operation. */
    private class Operation(private val context: CoroutineContext) {
        /** Set before the first non-idempotent server command; disables the automatic retry. */
        var mutationStarted = false

        fun checkCancelled() = context.ensureActive()
    }

    private class RetryableDrop(cause: Throwable) : Exception(cause)

    private suspend fun <T> execute(retryAfterMutation: Boolean = true, block: Operation.(IMAPStore) -> T): T =
        withContext(Dispatchers.IO) {
            val context = coroutineContext
            mutex.withLock {
                context.ensureActive()
                val sockets = sockets
                try {
                    if (sockets == null) {
                        runAttempts(context, retryAfterMutation, block)
                    } else {
                        sockets.reopen()
                        coroutineScope {
                            val inFlight = AtomicBoolean(true)
                            // A thread blocked reading the server's answer only wakes up when its
                            // socket is closed, so a cancelled caller closes it from here.
                            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    if (inFlight.get()) abort()
                                }
                            }
                            try {
                                runAttempts(context, retryAfterMutation, block)
                            } finally {
                                inFlight.set(false)
                                watcher.cancel()
                            }
                        }
                    }
                } catch (e: MailException) {
                    // A call that failed because its caller was cancelled (and its socket closed)
                    // reports the cancellation, not the closed connection.
                    context.ensureActive()
                    throw e
                }
            }
        }

    private fun <T> runAttempts(
        context: CoroutineContext,
        retryAfterMutation: Boolean,
        block: Operation.(IMAPStore) -> T,
    ): T =
        try {
            attempt(context, retryAfterMutation, block)
        } catch (drop: RetryableDrop) {
            try {
                attempt(context, retryAfterMutation, block)
            } catch (again: RetryableDrop) {
                throw MailErrors.map(again.cause ?: again)
            }
        }

    private fun <T> attempt(
        context: CoroutineContext,
        retryAfterMutation: Boolean,
        block: Operation.(IMAPStore) -> T,
    ): T {
        context.ensureActive()
        val operation = Operation(context)
        val startGeneration = synchronized(stateLock) { generation }
        var reused = false
        try {
            val result = MailSessions.withEngineClassLoader {
                val (connected, wasReused) = obtainStore()
                reused = wasReused
                operation.block(connected)
            }
            val closedMeanwhile = synchronized(stateLock) {
                lastActivityAt = System.currentTimeMillis()
                generation != startGeneration
            }
            // close() was called while this operation ran: do not keep its connection alive.
            if (closedMeanwhile) discardStore()
            return result
        } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError) throw e
            if (e is MailException) throw e
            // A server that stopped answering is not asked again: the same command would most
            // likely time out again, doubling the wait.
            val timedOut = MailErrors.isTimeout(e)
            val dropped = !timedOut && MailErrors.isConnectionDrop(e)
            // LOGOUT would wait for the server to finish the command first; an abortable client
            // can simply close the socket.
            if (timedOut && sockets != null) abort() else if (dropped || timedOut) discardStore()
            val sameGeneration = synchronized(stateLock) { generation == startGeneration }
            if (dropped && reused && sameGeneration && (retryAfterMutation || !operation.mutationStarted)) {
                throw RetryableDrop(e)
            }
            throw MailErrors.map(e)
        }
    }

    private fun obtainStore(): Pair<IMAPStore, Boolean> {
        synchronized(stateLock) {
            val existing = store
            if (existing != null) {
                if (System.currentTimeMillis() - lastActivityAt < STALE_CONNECTION_MS) return existing to true
                // A connection idle for a while may have been dropped silently by a NAT or the
                // server; a fresh login is quicker than waiting for a read timeout on it.
                store = null
                kept = null
                closeInBackground(existing)
            }
        }
        val fresh = if (settings.security == SecurityMode.SSL_TLS) {
            IMAPSSLStore(session, null)
        } else {
            IMAPStore(session, null)
        }
        try {
            fresh.connect(settings.host, settings.port, settings.username, passwordProvider())
        } catch (e: Throwable) {
            closeQuietly(fresh)
            throw e
        }
        synchronized(stateLock) {
            store?.let(::closeInBackground)
            store = fresh
            kept = null
            lastActivityAt = System.currentTimeMillis()
            loginCount++
        }
        return fresh to false
    }

    private fun discardStore() {
        val old = synchronized(stateLock) {
            kept = null
            store.also { store = null }
        } ?: return
        closeInBackground(old)
    }

    private fun createSession(): Session {
        val base = MailSessions.imapSession(settings, sockets)
        val session = if (options.readTimeoutMillis == MailSessions.READ_TIMEOUT_MS) {
            base
        } else {
            val props = Properties()
            props.putAll(base.properties)
            props["mail.${MailSessions.imapProtocol(settings.security)}.timeout"] = options.readTimeoutMillis.toString()
            MailSessions.withEngineClassLoader { Session.getInstance(props) }
        }
        debugOut?.let { out ->
            session.debugOut = out
            session.debug = true
        }
        return session
    }

    /** LOGOUT can block on a dead socket, so it never runs on the caller's thread. */
    private fun closeInBackground(target: IMAPStore) {
        val thread = Thread({ closeQuietly(target) }, "imap-close")
        thread.isDaemon = true
        thread.start()
    }

    private fun closeQuietly(target: IMAPStore) {
        try {
            MailSessions.withEngineClassLoader { target.close() }
        } catch (_: Throwable) {
            // Already closed or unreachable: nothing left to release.
        }
    }

    private inline fun <T> IMAPStore.withFolder(name: String, mode: Int, block: (IMAPFolder) -> T): T {
        if (options.keepFolderOpen && mode == Folder.READ_ONLY) {
            val folder = keptFolder(name)
            try {
                return block(folder)
            } catch (e: Throwable) {
                if (MailErrors.isTimeout(e) || MailErrors.isConnectionDrop(e)) forgetKeptFolder(folder, e)
                throw e
            }
        }
        releaseKeptFolder()
        val folder = getFolder(name) as IMAPFolder
        folder.open(mode)
        var failure: Throwable? = null
        try {
            return block(folder)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            closeFolder(folder, failure)
        }
    }

    private fun closeFolder(folder: IMAPFolder, failure: Throwable?) {
        try {
            if (!folder.isOpen) return
            if (failure != null && (MailErrors.isTimeout(failure) || MailErrors.isConnectionDrop(failure))) {
                // The connection is unusable; do not wait for the server to answer CLOSE.
                folder.forceClose()
            } else {
                folder.close(false)
            }
        } catch (_: Throwable) {
            // Closing is best effort; the connection is discarded if it is broken.
        }
    }

    /**
     * [name] open read-only on this store: the folder the previous call left open if it is the
     * same, otherwise a newly opened one, which stays open for the next call.
     */
    private fun IMAPStore.keptFolder(name: String): IMAPFolder {
        val current = synchronized(stateLock) { kept }
        if (current != null && current.store === this && current.name == name) return current.folder
        releaseKeptFolder()
        val folder = getFolder(name) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        synchronized(stateLock) { kept = KeptFolder(this, name, folder) }
        return folder
    }

    /** The folder left open, unless it is [name] itself or belongs to another connection. */
    private fun keptFolderOtherThan(store: IMAPStore, name: String): IMAPFolder? {
        val current = synchronized(stateLock) { kept } ?: return null
        return current.folder.takeIf { current.store === store && current.name != name }
    }

    /**
     * Closes the folder left open, before a call that needs another folder read-write or the
     * store itself: while a folder holds the connection, the store would log in a second one.
     */
    private fun releaseKeptFolder() {
        val current = synchronized(stateLock) { kept.also { kept = null } } ?: return
        try {
            current.folder.close(false)
        } catch (e: Exception) {
            // The next command would fail the same way; let the caller's retry logic see it now.
            if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e)) throw e
        }
    }

    private fun forgetKeptFolder(folder: IMAPFolder, failure: Throwable) {
        synchronized(stateLock) { if (kept?.folder === folder) kept = null }
        closeFolder(folder, failure)
    }

    /**
     * A folder left open caches every message it fetched. Dropping their data before a fetch makes
     * the fetch ask the server again (Angus skips items it already holds, such as stale flags), and
     * dropping it afterwards keeps memory flat over a long crawl.
     */
    private fun forgetFetched(messages: Collection<Message>) {
        if (!options.keepFolderOpen) return
        for (message in messages) (message as? IMAPMessage)?.invalidateHeaders()
    }

    // ---------------------------------------------------------------------------------------
    // Helpers

    /**
     * Asks the store's connection. Call it before opening a folder: an open folder holds the
     * only pooled connection, and the store would log in a second one to answer.
     */
    private fun hasCapability(store: IMAPStore, capability: String): Boolean {
        if (capability.uppercase(Locale.ROOT) in disabledCapabilities) return false
        releaseKeptFolder()
        return store.hasCapability(capability)
    }

    private fun expunge(folder: IMAPFolder, messages: Array<Message>, uidPlus: Boolean) {
        if (uidPlus) folder.expunge(messages) else folder.expunge()
    }

    private fun requireWritable(folder: IMAPFolder) {
        if (folder.mode != Folder.READ_WRITE) throw MessagingException("${folder.fullName} is read-only")
    }

    private fun acceptsKeyword(folder: IMAPFolder, keyword: String): Boolean {
        val permanent = try {
            folder.permanentFlags
        } catch (_: Exception) {
            null
        } ?: return true
        return permanent.contains(Flags.Flag.USER) ||
            permanent.userFlags.any { it.equals(keyword, ignoreCase = true) }
    }

    private fun flagsOf(flag: MessageFlag): Flags = when (flag) {
        MessageFlag.SEEN -> Flags(Flags.Flag.SEEN)
        MessageFlag.FLAGGED -> Flags(Flags.Flag.FLAGGED)
        MessageFlag.ANSWERED -> Flags(Flags.Flag.ANSWERED)
        MessageFlag.DRAFT -> Flags(Flags.Flag.DRAFT)
        MessageFlag.DELETED -> Flags(Flags.Flag.DELETED)
        MessageFlag.FORWARDED -> Flags(HeaderValues.FORWARDED_KEYWORD)
    }

    private fun messageByUid(folder: IMAPFolder, uid: Long): IMAPMessage {
        val message = folder.getMessageByUID(uid) as IMAPMessage?
        if (message == null || message.isExpunged) {
            throw MessageRemovedException("Message $uid no longer exists in ${folder.fullName}")
        }
        message.peek = true
        return message
    }

    /**
     * Runs [block] on [message] using its BODYSTRUCTURE. If the server's BODYSTRUCTURE cannot be
     * parsed, the whole message is downloaded to a temporary file and parsed locally instead;
     * part paths are the same either way.
     */
    private fun <T> Operation.withStructure(message: IMAPMessage, block: (Part) -> T): T {
        val structureLoaded = trustServerStructure && try {
            message.folder.fetch(arrayOf(message), FetchProfile().apply { add(FetchProfile.Item.CONTENT_INFO) })
            message.contentType
            true
        } catch (e: MessagingException) {
            if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e) || e is MessageRemovedException) throw e
            false
        }
        if (structureLoaded) return block(message)
        // Parsed from a file, the parts read their bytes from it instead of a copy of the whole
        // message in memory.
        val temp = try {
            File.createTempFile("upm-mime-", ".eml")
        } catch (e: IOException) {
            throw MailException(MailErrorKind.UNKNOWN, "Cannot create a temporary file: ${e.message}", e)
        }
        try {
            LocalFileOutputStream(BufferedOutputStream(FileOutputStream(temp), 64 * 1024), temp).use { out ->
                MimeParser.copy(message.mimeStream, out) { checkCancelled() }
            }
            val input = TemporaryMimeFile(temp)
            try {
                return block(MimeMessage(MailSessions.mimeSession(), input))
            } finally {
                input.release()
            }
        } catch (e: LocalWriteException) {
            throw MailException(MailErrorKind.UNKNOWN, e.message, e.cause)
        } finally {
            temp.delete()
        }
    }

    /** The stream every part of a message parsed from [file] shares; [release] closes them all. */
    private class TemporaryMimeFile(file: File) : SharedFileInputStream(file) {
        private val shared: RandomAccessFile = `in`

        fun release() {
            try {
                shared.close()
            } catch (_: IOException) {
                // Only being read: nothing is lost.
            }
        }
    }

    private fun Operation.fetchAndMap(folder: IMAPFolder, messages: List<Message>): List<RemoteMessage> {
        if (messages.isEmpty()) return emptyList()
        val result = ArrayList<RemoteMessage>(messages.size)
        for (chunk in messages.chunked(FETCH_CHUNK)) {
            checkCancelled()
            val array = chunk.toTypedArray()
            forgetFetched(chunk)
            folder.fetch(array, messageProfile())
            for (message in array) {
                val imapMessage = message as? IMAPMessage ?: continue
                toRemoteMessage(folder, imapMessage)?.let(result::add)
            }
            forgetFetched(chunk)
        }
        result.sortBy { it.uid }
        return result
    }

    private fun Operation.fetchByUidsIn(folder: IMAPFolder, uids: List<Long>): List<RemoteMessage> =
        uids.chunked(UID_CHUNK)
            .flatMap { chunk -> fetchAndMap(folder, folder.getMessagesByUID(chunk.toLongArray()).filterNotNull()) }
            .sortedBy { it.uid }

    /** Only what ENVELOPE, INTERNALDATE, RFC822.SIZE, FLAGS and the Content-Type header give: no lazy fetches. */
    private fun toIndexedHeader(uid: Long, message: IMAPMessage): IndexedHeader = IndexedHeader(
        uid = uid,
        messageId = safe { message.messageID }?.let { HeaderValues.firstMessageId(it) ?: HeaderValues.unfold(it) },
        subject = safe { message.subject }?.let(HeaderValues::cleanDecodedSubject).orEmpty(),
        from = safe { HeaderValues.addresses(message.from).firstOrNull() },
        to = safe { HeaderValues.addresses(message.getRecipients(Message.RecipientType.TO)) }.orEmpty(),
        cc = safe { HeaderValues.addresses(message.getRecipients(Message.RecipientType.CC)) }.orEmpty(),
        sentDate = safe { message.sentDate?.time },
        internalDate = safe { message.receivedDate?.time },
        size = safe { message.sizeLong }?.takeIf { it >= 0 } ?: 0L,
        flags = safe { HeaderValues.flags(message.flags) } ?: RemoteFlags(),
        likelyHasAttachments = likelyHasAttachments(safe { message.getHeader("Content-Type")?.firstOrNull() }),
    )

    private fun toRemoteMessage(folder: IMAPFolder, message: IMAPMessage): RemoteMessage? {
        if (message.isExpunged) return null
        val uid = folder.getUID(message)
        if (uid <= 0) return null
        val from = safe { HeaderValues.addresses(message.from).firstOrNull() }
        val replyTo = safe { HeaderValues.addresses(message.replyTo) }.orEmpty()
        val references = safe { message.getHeader("References")?.joinToString(" ") }
        return RemoteMessage(
            uid = uid,
            messageId = safe { message.messageID }?.let { HeaderValues.firstMessageId(it) ?: HeaderValues.unfold(it) },
            // The In-Reply-To header is fetched too: some servers leave it out of the ENVELOPE.
            inReplyTo = (safe { message.getHeader("In-Reply-To")?.firstOrNull() } ?: safe { message.inReplyTo })
                ?.let { HeaderValues.firstMessageId(it) ?: HeaderValues.unfold(it) },
            references = HeaderValues.messageIds(references),
            subject = safe { message.subject }?.let(HeaderValues::cleanDecodedSubject).orEmpty(),
            from = from,
            to = safe { HeaderValues.addresses(message.getRecipients(Message.RecipientType.TO)) }.orEmpty(),
            cc = safe { HeaderValues.addresses(message.getRecipients(Message.RecipientType.CC)) }.orEmpty(),
            bcc = safe { HeaderValues.addresses(message.getRecipients(Message.RecipientType.BCC)) }.orEmpty(),
            // IMAP servers report From as Reply-To when there is none; keep only a real one.
            replyTo = if (from != null && replyTo.size == 1 && replyTo[0].address.equals(from.address, true)) {
                emptyList()
            } else {
                replyTo
            },
            sentDate = safe { message.sentDate?.time },
            internalDate = safe { message.receivedDate?.time },
            size = safe { message.sizeLong }?.takeIf { it >= 0 } ?: 0L,
            flags = safe { HeaderValues.flags(message.flags) } ?: RemoteFlags(),
            hasAttachments = safe { MimeParser.hasAttachments(message) } ?: false,
            listUnsubscribe = safe { HeaderValues.unfold(message.getHeader("List-Unsubscribe", ", ")) },
        )
    }

    /** Field extraction that tolerates malformed headers but not a dead connection. */
    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e)) throw e
        null
    }

    /** UID SEARCH, so that the matches come back as UIDs in a single round trip. */
    private fun uidSearch(folder: IMAPFolder, term: SearchTerm): List<Long> = uidSearch(folder, emptyList(), term)

    private fun uidSearch(folder: IMAPFolder, prefixAtoms: List<String>, term: SearchTerm?): List<Long> =
        (uidSearchRaw(folder, prefixAtoms, term, allowCharsetFallback = false) as RawSearch.Uids).uids

    /** What one UID SEARCH command answered. */
    private sealed interface RawSearch {
        class Uids(val uids: List<Long>) : RawSearch

        /** The server refused `CHARSET UTF-8` with [BADCHARSET]. */
        data object CharsetRejected : RawSearch
    }

    /**
     * `UID SEARCH [CHARSET UTF-8] <prefixAtoms> <term> ALL`. The prefix atoms are search keys sent
     * as they are, such as a sequence set ("48001:50000") or "UID 120:*". CHARSET is only sent for
     * a non-ASCII [term] to a server without UTF8=ACCEPT; when [allowCharsetFallback], the
     * server's [BADCHARSET] refusal of it is returned as [RawSearch.CharsetRejected] instead of
     * failing. Untagged answers other than SEARCH (EXISTS, EXPUNGE, "* OK Searched 50%") still go
     * to the folder.
     */
    private fun uidSearchRaw(
        folder: IMAPFolder,
        prefixAtoms: List<String>,
        term: SearchTerm?,
        allowCharsetFallback: Boolean,
    ): RawSearch = folder.doCommand { protocol ->
        val charset = if (term == null || SearchSequence.isAscii(term) || protocol.supportsUtf8()) null else "UTF-8"
        val args = Argument()
        for (atom in prefixAtoms) args.writeAtom(atom)
        if (term != null) args.append(SearchSequence(protocol).generateSequence(term, charset?.let(MimeUtility::javaCharset)))
        args.writeAtom("ALL")
        val command = if (charset == null) "UID SEARCH" else "UID SEARCH CHARSET $charset"
        val responses = protocol.command(command, args)
        val last = responses.last()
        val uids = ArrayList<Long>()
        val unconsumed = arrayOfNulls<Response>(responses.size)
        for (index in responses.indices) {
            val response = responses[index]
            if (last.isOK && response is IMAPResponse && response.keyEquals("SEARCH")) {
                while (true) {
                    val uid = response.readLong()
                    if (uid < 0) break
                    uids += uid
                }
            } else {
                unconsumed[index] = response
            }
        }
        protocol.notifyResponseHandlers(unconsumed)
        if (charset != null && allowCharsetFallback && last.isNO &&
            last.toString().contains("[BADCHARSET", ignoreCase = true)
        ) {
            return@doCommand RawSearch.CharsetRejected
        }
        protocol.handleResult(last)
        RawSearch.Uids(uids.distinct())
    } as RawSearch

    /** The search keys that limit a search to [range]; null when the range holds no message. */
    private fun rangeAtoms(range: SearchRange?, messageCount: Int): List<String>? = when (range) {
        null -> emptyList()
        is SearchRange.Seq -> {
            val first = maxOf(1, range.first)
            val last = minOf(range.last, messageCount)
            if (first > last) null else listOf("$first:$last")
        }
        is SearchRange.UidsFrom -> {
            // "*" means nothing in an empty folder; some servers even refuse it.
            val from = maxOf(1L, range.firstUid)
            if (messageCount <= 0 || from > UIDFolder.MAXUID) null else listOf("UID", "$from:*")
        }
    }

    /**
     * Name prefixes ("Shared/", "Other Users/") of the shared and other users' namespaces
     * (RFC 2342); empty when the server has none or no NAMESPACE.
     */
    private fun foreignNamespacePrefixes(store: IMAPStore): List<String> {
        val namespaces = ArrayList<Folder>()
        for (lookup in listOf({ store.sharedNamespaces }, { store.getUserNamespaces(null) })) {
            try {
                namespaces += lookup()
            } catch (e: MessagingException) {
                if (MailErrors.isConnectionDrop(e) || MailErrors.isTimeout(e)) throw e
            }
        }
        return namespaces.mapNotNull { namespace ->
            val name = namespace.fullName
            if (name.isEmpty()) null else name + safeSeparator(namespace as IMAPFolder)
        }
    }

    /** True when [fullName] lies in a foreign namespace, and not in a more specific personal one. */
    private fun isForeign(fullName: String, foreignPrefixes: List<String>, personalPrefixes: List<String>): Boolean {
        if (fullName.equals(INBOX, ignoreCase = true)) return false
        val foreign = foreignPrefixes
            .filter { fullName.startsWith(it) || fullName == it.dropLast(1) }
            .maxOfOrNull { it.length } ?: return false
        val personal = personalPrefixes.filter { fullName.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        return foreign > personal
    }

    private fun personalNamespacePrefixes(store: IMAPStore): List<String> = try {
        store.personalNamespaces
            .mapNotNull { namespace ->
                val name = namespace.fullName
                if (name.isEmpty()) null else name + safeSeparator(namespace as IMAPFolder)
            }
    } catch (e: MessagingException) {
        if (MailErrors.isConnectionDrop(e)) throw e
        emptyList()
    }

    private fun safeSeparator(folder: IMAPFolder): Char = try {
        folder.separator
    } catch (e: MessagingException) {
        if (MailErrors.isConnectionDrop(e)) throw e
        '/'
    }

    private fun depthOf(fullName: String, separator: Char, prefixes: List<String>): Int {
        if (separator == '\u0000') return 0
        val prefix = prefixes.firstOrNull { fullName.startsWith(it) && fullName.length > it.length }
        val relative = if (prefix != null) fullName.substring(prefix.length) else fullName
        return relative.count { it == separator }
    }

    private fun writeAtomically(destination: File, write: (OutputStream) -> Unit) {
        val target = destination.absoluteFile
        val directory = target.parentFile ?: throw MailException(MailErrorKind.UNKNOWN, "Invalid destination $target")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw MailException(MailErrorKind.UNKNOWN, "Cannot create $directory")
        }
        val temp = try {
            File.createTempFile(".dl-", ".part", directory)
        } catch (e: IOException) {
            throw MailException(MailErrorKind.UNKNOWN, "Cannot write to $directory: ${e.message}", e)
        }
        try {
            LocalFileOutputStream(BufferedOutputStream(FileOutputStream(temp), 64 * 1024), temp).use { out ->
                write(out)
                out.flush()
            }
        } catch (e: Throwable) {
            temp.delete()
            // Anything else failed while reading from the server (connection lost, message
            // expunged...): that is for the caller's error mapping and reconnection.
            if (e is LocalWriteException) throw MailException(MailErrorKind.UNKNOWN, e.message, e.cause)
            throw e
        }
        try {
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
            throw MailException(MailErrorKind.UNKNOWN, "Cannot save $target: ${e.message}", e)
        }
    }

    /** Distinguishes local disk errors (full storage...) from network errors while downloading. */
    private class LocalWriteException(message: String, cause: IOException) : IOException(message, cause)

    private class LocalFileOutputStream(out: OutputStream, private val file: File) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }

        override fun write(b: Int) = guard { out.write(b) }

        override fun flush() = guard { out.flush() }

        override fun close() = guard { out.close() }

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                throw LocalWriteException("Cannot write ${file.name}: ${e.message}", e)
            }
        }
    }

    private companion object {
        const val INBOX = "INBOX"
        const val UID_CHUNK = 500

        /** Messages per index fetch: about 200 KB of answer, one database transaction per chunk. */
        const val INDEX_CHUNK = 250
        const val FETCH_CHUNK = 100
        const val STALE_CONNECTION_MS = 4 * 60 * 1000L
        val STATUS_ITEMS = arrayOf("MESSAGES", "UNSEEN", "UIDNEXT", "UIDVALIDITY")

        /** Top-level types of a message that most likely carries attachments. */
        val ATTACHMENT_TYPE_PREFIXES = listOf("application/", "image/", "audio/", "video/")

        /**
         * A guess from the top-level Content-Type alone (no BODYSTRUCTURE): multipart/mixed, or a
         * message that is a single file. Messages with only inline images, and files inside
         * multipart/signed or multipart/related, are missed.
         */
        fun likelyHasAttachments(contentType: String?): Boolean {
            val type = HeaderValues.unfold(contentType)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                ?: return false
            return type == "multipart/mixed" || ATTACHMENT_TYPE_PREFIXES.any { type.startsWith(it) }
        }

        fun indexProfile(): FetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.SIZE)
            add(UIDFolder.FetchProfileItem.UID)
            add("Content-Type")
        }

        fun messageProfile(): FetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(FetchProfile.Item.SIZE)
            add(UIDFolder.FetchProfileItem.UID)
            add("References")
            add("In-Reply-To")
            add("List-Unsubscribe")
        }

        fun flagsProfile(): FetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }
    }
}
