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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * IMAP access for one account. All functions run on Dispatchers.IO, are serialised (one command
 * at a time per client), reconnect once on a dropped connection and throw MailException.
 *
 * The connection is opened lazily and kept between calls; a connection idle for a few minutes
 * is replaced by a fresh one rather than trusted. Folders are opened for each operation
 * (read-only for reads, so the server never sets \Seen) and closed afterwards. [close] drops
 * the connection; a later call reconnects.
 */
class ImapClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
) : Closeable {

    private val session: Session by lazy { MailSessions.imapSession(settings) }
    private val mutex = Mutex()
    private val stateLock = Any()

    // Guarded by stateLock.
    private var store: IMAPStore? = null
    private var lastActivityAt = 0L
    private var generation = 0

    /** Capabilities treated as absent (upper case), so tests can exercise the fallbacks. */
    internal var disabledCapabilities: Set<String> = emptySet()

    /** False makes every message take the local parsing path, so tests can exercise it. */
    internal var trustServerStructure = true

    init {
        MailSessions.ensureInitialized()
    }

    /** Connects and logs in (used to validate credentials). */
    suspend fun connect() {
        // A real login check: never answer from an already open connection.
        discardStore()
        execute { }
    }

    suspend fun listFolders(): List<RemoteFolder> = execute { store ->
        val prefixes = personalNamespacePrefixes(store)
        val listed = store.defaultFolder.list("*")
        val folders = ArrayList<RemoteFolder>(listed.size + 1)
        for (folder in listed) {
            val imapFolder = folder as? IMAPFolder ?: continue
            val attributes = imapFolder.attributes.orEmpty().map { it.lowercase(Locale.ROOT) }.toSet()
            if ("\\nonexistent" in attributes) continue
            val fullName = imapFolder.fullName
            if (fullName.isEmpty()) continue
            val separator = safeSeparator(imapFolder)
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

    suspend fun status(folder: String): RemoteFolderStatus = execute { store ->
        val imapFolder = store.getFolder(folder) as IMAPFolder
        val status = try {
            imapFolder.doCommand { protocol -> protocol.status(imapFolder.fullName, STATUS_ITEMS) } as Status?
        } catch (e: MessagingException) {
            if (MailErrors.isConnectionDrop(e)) throw e
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
    suspend fun fetchNewest(folder: String, limit: Int): FetchResult = execute { store ->
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
    suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int): FetchResult = execute { store ->
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
    suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int): FetchResult = execute { store ->
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
    suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags> {
        val from = maxOf(fromUid, 1L)
        val to = minOf(toUid, UIDFolder.MAXUID)
        if (from > to) return emptyMap()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                val messages = open.getMessagesByUID(from, to)
                if (messages.isNotEmpty()) open.fetch(messages, flagsProfile())
                val result = LinkedHashMap<Long, RemoteFlags>(messages.size)
                for (message in messages) {
                    checkCancelled()
                    if (message.isExpunged) continue
                    val uid = open.getUID(message)
                    if (uid in from..to) result[uid] = HeaderValues.flags(message.flags)
                }
                result
            }
        }
    }

    suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage> {
        val wanted = uids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return emptyList()
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                wanted.chunked(UID_CHUNK)
                    .flatMap { chunk ->
                        val messages = open.getMessagesByUID(chunk.toLongArray()).filterNotNull()
                        fetchAndMap(open, messages)
                    }
                    .sortedBy { it.uid }
            }
        }
    }

    /** Downloads and parses the body. Never sets \Seen. */
    suspend fun fetchBody(folder: String, uid: Long): ParsedBody = execute { store ->
        store.withFolder(folder, Folder.READ_ONLY) { open ->
            val message = messageByUid(open, uid)
            withStructure(message) { part -> MimeParser.parse(part) }
        }
    }

    /** Writes the decoded content of part [partPath] to [destination]. */
    suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File): Unit = execute { store ->
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

    suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean) {
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
    suspend fun move(folder: String, uids: List<Long>, destinationFolder: String) {
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

    suspend fun deletePermanently(folder: String, uids: List<Long>) {
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
    suspend fun deleteAll(folder: String): Unit = execute { store ->
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
    suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long? =
        execute(retryAfterMutation = false) { store ->
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
    suspend fun search(folder: String, query: String, limit: Int): List<Long> {
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

    suspend fun findUidByMessageId(folder: String, messageId: String): Long? {
        val id = messageId.trim()
        if (id.isEmpty()) return null
        return execute { store ->
            store.withFolder(folder, Folder.READ_ONLY) { open ->
                uidSearch(open, HeaderTerm("Message-ID", id)).maxOrNull()
            }
        }
    }

    override fun close() {
        val old = synchronized(stateLock) {
            generation++
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
                try {
                    attempt(context, retryAfterMutation, block)
                } catch (drop: RetryableDrop) {
                    try {
                        attempt(context, retryAfterMutation, block)
                    } catch (again: RetryableDrop) {
                        throw MailErrors.map(again.cause ?: again)
                    }
                }
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
            val dropped = MailErrors.isConnectionDrop(e)
            if (dropped || MailErrors.isTimeout(e)) discardStore()
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
            lastActivityAt = System.currentTimeMillis()
        }
        return fresh to false
    }

    private fun discardStore() {
        val old = synchronized(stateLock) { store.also { store = null } } ?: return
        closeInBackground(old)
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

    // ---------------------------------------------------------------------------------------
    // Helpers

    /**
     * Asks the store's connection. Call it before opening a folder: an open folder holds the
     * only pooled connection, and the store would log in a second one to answer.
     */
    private fun hasCapability(store: IMAPStore, capability: String): Boolean =
        capability.uppercase(Locale.ROOT) !in disabledCapabilities && store.hasCapability(capability)

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
            folder.fetch(array, messageProfile())
            for (message in array) {
                val imapMessage = message as? IMAPMessage ?: continue
                toRemoteMessage(folder, imapMessage)?.let(result::add)
            }
        }
        result.sortBy { it.uid }
        return result
    }

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
    private fun uidSearch(folder: IMAPFolder, term: SearchTerm): List<Long> {
        @Suppress("UNCHECKED_CAST")
        val result = folder.doCommand { protocol ->
            val charset = if (SearchSequence.isAscii(term) || protocol.supportsUtf8()) null else "UTF-8"
            val args = SearchSequence(protocol).generateSequence(term, charset?.let(MimeUtility::javaCharset))
            args.writeAtom("ALL")
            val command = if (charset == null) "UID SEARCH" else "UID SEARCH CHARSET $charset"
            val responses = protocol.command(command, args)
            val last = responses.last()
            val uids = ArrayList<Long>()
            // Untagged responses other than SEARCH (EXISTS, EXPUNGE...) still go to the folder.
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
            protocol.handleResult(last)
            uids
        } as List<Long>
        return result.distinct()
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
        const val FETCH_CHUNK = 100
        const val STALE_CONNECTION_MS = 4 * 60 * 1000L
        val STATUS_ITEMS = arrayOf("MESSAGES", "UNSEEN", "UIDNEXT", "UIDVALIDITY")

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
