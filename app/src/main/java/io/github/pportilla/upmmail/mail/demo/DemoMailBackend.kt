package io.github.pportilla.upmmail.mail.demo

import io.github.pportilla.upmmail.mail.*
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real, interactive sample mailbox with a local implementation of the mail protocol boundary.
 * No socket, DNS, HTTP, credentials or transport session is used here. It is selected exclusively
 * by the persisted Account.isDemo flag, never by an address or host supplied by a user.
 *
 * Keep one instance per account. All connections, including derived search connections, share
 * its atomic snapshot and local change stream. The normal repositories/engine retain ownership
 * of access authorization, blocked senders, outbox, undo and cached-message projections.
 */
class DemoMailBackend(
    root: File,
    private val account: Account,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Stored(val bytes: ByteArray, var flags: RemoteFlags, val arrived: Long)
    private data class Box(var next: Long = 1, val mail: LinkedHashMap<Long, Stored> = linkedMapOf())
    private data class Snapshot(
        val boxes: LinkedHashMap<String, Box> = linkedMapOf(),
        val accepted: MutableSet<String> = linkedSetOf(),
        val failedOnce: MutableSet<String> = linkedSetOf(),
    )

    private val lock = Any()
    private val directory = File(root, account.id.toString())
    private val file = File(directory, "mailbox-v1.bin")
    private var deleted = false
    private var snapshot: Snapshot? = null
    private val changes = MutableStateFlow(0L to ImapIdler.Change.NEW_MESSAGES)

    init {
        require(account.isDemo && account.id > 0) { "A local sample backend requires a persisted sample account" }
    }

    fun imap(authorize: () -> Unit = {}): ImapTransport = LocalImap(authorize)
    fun smtp(authorize: () -> Unit = {}): SmtpTransport = LocalSmtp(authorize)
    fun idler(authorize: () -> Unit = {}): IdleTransport = LocalIdle(authorize)

    /** Permanently invalidates all connections before removing only this sample account's files. */
    fun delete() = synchronized(lock) {
        deleted = true
        snapshot = null
        changes.value = changes.value.first + 1 to ImapIdler.Change.MESSAGES_REMOVED
        if (directory.exists() && !directory.deleteRecursively()) {
            throw MailException(MailErrorKind.UNKNOWN, "Could not remove the local sample mailbox")
        }
    }

    /** A local incoming-message exercise; callers still enforce account-management authorization. */
    suspend fun simulateIncoming(authorize: () -> Unit = {}) = access(write = true, guard = authorize) { state ->
        add(state, "INBOX", sample(
            "Sample arrival / Mensaje de ejemplo",
            "This message was created on this device. Nothing was received from the Internet.\n\n" +
                "Este mensaje se ha creado en este dispositivo. No se ha recibido nada de Internet.",
        ))
        changed(ImapIdler.Change.NEW_MESSAGES)
    }

    private suspend fun <T> access(write: Boolean = false, guard: () -> Unit = {}, action: (Snapshot) -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            ensurePresent()
            guard()
            val state = snapshot ?: load().also { snapshot = it }
            val previous = if (write) copy(state) else null
            try {
                val result = MailSessions.withEngineClassLoader { action(state) }
                if (write) { guard(); save(state) }
                result
            } catch (e: Throwable) {
                if (previous != null) snapshot = previous
                throw e
            }
        }
    }

    private fun ensurePresent() {
        if (deleted) throw MailException(MailErrorKind.NOT_FOUND, "Sample account was removed")
    }

    private fun copy(state: Snapshot) = Snapshot(
        LinkedHashMap(state.boxes.mapValues { (_, box) ->
            Box(box.next, LinkedHashMap(box.mail.mapValues { (_, mail) -> mail.copy() }))
        }), state.accepted.toMutableSet(), state.failedOnce.toMutableSet(),
    )

    private fun box(state: Snapshot, name: String): Box = state.boxes[name]
        ?: throw MailException(MailErrorKind.NOT_FOUND, "Sample folder does not exist")

    private fun stored(state: Snapshot, folder: String, uid: Long): Stored = box(state, folder).mail[uid]
        ?: throw MailException(MailErrorKind.NOT_FOUND, "Sample message no longer exists")

    private fun mime(mail: Stored): MimeMessage = MimeMessage(MailSessions.mimeSession(), mail.bytes.inputStream()).apply {
        setFlag(Flags.Flag.SEEN, mail.flags.seen)
        setFlag(Flags.Flag.FLAGGED, mail.flags.flagged)
        setFlag(Flags.Flag.ANSWERED, mail.flags.answered)
        setFlag(Flags.Flag.DRAFT, mail.flags.draft)
        setFlag(Flags.Flag.DELETED, mail.flags.deleted)
        setFlags(Flags(HeaderValues.FORWARDED_KEYWORD), mail.flags.forwarded)
    }

    private fun remote(uid: Long, mail: Stored): RemoteMessage {
        val message = mime(mail)
        return RemoteMessage(
            uid, HeaderValues.firstMessageId(message.messageID),
            HeaderValues.firstMessageId(message.getHeader("In-Reply-To", " ")),
            HeaderValues.messageIds(message.getHeader("References", " ")),
            HeaderValues.subject(message.subject), HeaderValues.addresses(message.from).firstOrNull(),
            HeaderValues.addresses(message.getRecipients(Message.RecipientType.TO)),
            HeaderValues.addresses(message.getRecipients(Message.RecipientType.CC)),
            HeaderValues.addresses(message.getRecipients(Message.RecipientType.BCC)),
            HeaderValues.addresses(message.replyTo), message.sentDate?.time, mail.arrived,
            mail.bytes.size.toLong(), mail.flags, MimeParser.hasAttachments(message), null,
        )
    }

    private fun changed(change: ImapIdler.Change) {
        changes.value = changes.value.first + 1 to change
    }

    private inner class LocalImap(private val authorize: () -> Unit) : ImapTransport {
        private val connected = AtomicBoolean(false)
        private var loginCount = 0
        override val isBusy: Boolean get() = false // All bounded local work is serialized by the backend.
        override val isConnected: Boolean get() = connected.get() && !deleted
        override val logins: Int get() = loginCount

        private suspend fun <T> read(write: Boolean = false, action: (Snapshot) -> T): T = access(write, guard = authorize) { state ->
            authorize()
            if (connected.compareAndSet(false, true)) loginCount++
            action(state)
        }

        override suspend fun connect() { read { Unit } }
        override suspend fun listFolders(): List<RemoteFolder> = read { state ->
            state.boxes.keys.map { name ->
                val role = when (name) {
                    "Drafts" -> "\\drafts"; "Sent" -> "\\sent"; "Archive" -> "\\archive"
                    "Trash" -> "\\trash"; "Junk" -> "\\junk"; else -> "\\hasnochildren"
                }
                RemoteFolder(name, name, '/', setOf(role), true, 0)
            }
        }
        override suspend fun status(folder: String): RemoteFolderStatus = read {
            val box = box(it, folder)
            RemoteFolderStatus(UID_VALIDITY, box.next, box.mail.size, box.mail.values.count { mail -> !mail.flags.seen })
        }
        private suspend fun fetch(folder: String, limit: Int, keep: (Long) -> Boolean): FetchResult = read {
            val candidates = box(it, folder).mail.entries.filter { entry -> keep(entry.key) }.sortedBy { entry -> entry.key }
            val chosen = candidates.takeLast(limit.coerceAtLeast(0))
            FetchResult(UID_VALIDITY, chosen.map { entry -> remote(entry.key, entry.value) }, chosen.size < candidates.size)
        }
        override suspend fun fetchNewest(folder: String, limit: Int) = fetch(folder, limit) { true }
        override suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int) = fetch(folder, limit) { it > afterUid }
        override suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int) = fetch(folder, limit) { it < beforeUid }
        override suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags> = read {
            box(it, folder).mail.filterKeys { uid -> uid in fromUid..toUid }.mapValues { entry -> entry.value.flags }
        }
        override suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage> = read {
            val box = box(it, folder)
            uids.distinct().sorted().mapNotNull { uid -> box.mail[uid]?.let { mail -> remote(uid, mail) } }
        }
        override suspend fun fetchByUids(folder: String, uids: List<Long>) =
            FetchResult(UID_VALIDITY, fetchMessages(folder, uids), false)
        override suspend fun fetchFlagsFor(folder: String, uids: List<Long>): Map<Long, RemoteFlags> = read {
            val wanted = uids.toSet()
            box(it, folder).mail.filterKeys { uid -> uid in wanted }.mapValues { entry -> entry.value.flags }
        }
        override suspend fun fetchIndexHeaders(folder: String, uids: List<Long>): HeaderBatch = read {
            val box = box(it, folder)
            val wanted = uids.distinct().filter { uid -> uid > 0 }.sorted()
            HeaderBatch(UID_VALIDITY, wanted.mapNotNull { uid -> box.mail[uid]?.let { remote(uid, it).toIndexedHeader() } },
                wanted.filterNot(box.mail::containsKey))
        }
        override suspend fun fetchBody(folder: String, uid: Long): ParsedBody = read {
            val body = MimeParser.parse(mime(stored(it, folder, uid)))
            // Even a user-created sample MIME never requests remote HTML resources.
            val text = body.text ?: body.html?.let { html -> Jsoup.parse(html).text() }
            body.copy(html = text?.let(HtmlSanitizer::plainTextToHtml), text = text)
        }
        override suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File) {
            read {
                val part = MimeParser.findPart(mime(stored(it, folder, uid)), partPath)
                    ?: throw MailException(MailErrorKind.NOT_FOUND, "Sample attachment no longer exists")
                destination.parentFile?.mkdirs()
                val temporary = File.createTempFile("sample-part-", ".tmp", destination.parentFile)
                try {
                    temporary.outputStream().use { out -> MimeParser.writeContent(part, out) { authorize() } }
                    authorize()
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
        }
        override suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean) {
            read(true) {
                val box = box(it, folder)
                uids.forEach { uid -> box.mail[uid]?.let { mail ->
                    mail.flags = when (flag) {
                        MessageFlag.SEEN -> mail.flags.copy(seen = value)
                        MessageFlag.FLAGGED -> mail.flags.copy(flagged = value)
                        MessageFlag.ANSWERED -> mail.flags.copy(answered = value)
                        MessageFlag.FORWARDED -> mail.flags.copy(forwarded = value)
                        MessageFlag.DRAFT -> mail.flags.copy(draft = value)
                        MessageFlag.DELETED -> mail.flags.copy(deleted = value)
                    }
                } }
                changed(ImapIdler.Change.FLAGS_CHANGED)
            }
        }
        override suspend fun move(folder: String, uids: List<Long>, destinationFolder: String) {
            read(true) {
                val source = box(it, folder)
                val target = box(it, destinationFolder)
                if (folder != destinationFolder) uids.distinct().forEach { uid ->
                    source.mail.remove(uid)?.let { mail -> target.mail[target.next++] = mail }
                }
                changed(ImapIdler.Change.MESSAGES_REMOVED)
            }
        }
        override suspend fun deletePermanently(folder: String, uids: List<Long>) {
            read(true) { state -> uids.forEach { box(state, folder).mail.remove(it) }; changed(ImapIdler.Change.MESSAGES_REMOVED) }
        }
        override suspend fun deleteAll(folder: String) {
            read(true) { box(it, folder).mail.clear(); changed(ImapIdler.Change.MESSAGES_REMOVED) }
        }
        override suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long = read(true) {
            add(it, folder, message, RemoteFlags(seen = seen, draft = draft)).also { changed(ImapIdler.Change.NEW_MESSAGES) }
        }
        override suspend fun search(folder: String, query: String, limit: Int): List<Long> {
            val parsed = ServerQuery.parse(query, SearchField.entries.toSet()) ?: return emptyList()
            return searchUids(folder, parsed, null, limit).uids
        }
        override suspend fun findUidByMessageId(folder: String, messageId: String): Long? = read {
            box(it, folder).mail.entries.lastOrNull { entry -> mime(entry.value).messageID == messageId }?.key
        }
        override suspend fun examine(folder: String): FolderProbe = read {
            val box = box(it, folder); FolderProbe(UID_VALIDITY, box.next, box.mail.size)
        }
        override suspend fun searchUids(folder: String, query: ServerQuery, range: SearchRange?, limit: Int): SearchOutcome = read {
            val box = box(it, folder)
            val term = query.toSearchTerm()
            val matches = box.mail.entries.sortedBy { entry -> entry.key }.filterIndexed { index, entry ->
                val inRange = when (range) {
                    null -> true
                    is SearchRange.Seq -> index + 1 in range.first..range.last
                    is SearchRange.UidsFrom -> entry.key >= range.firstUid
                }
                inRange && (term?.match(mime(entry.value)) != false)
            }.map { entry -> entry.key }.sortedDescending()
            SearchOutcome(UID_VALIDITY, box.next, box.mail.size, matches.take(limit.coerceAtLeast(0)), matches.size)
        }
        override suspend fun searchAllUids(folder: String, fromUid: Long): UidList = read {
            val box = box(it, folder)
            UidList(UID_VALIDITY, box.next, box.mail.size, box.mail.keys.filter { uid -> uid >= fromUid }.sorted().toLongArray())
        }
        override suspend fun searchUndeletedUids(folder: String): UidList = read {
            val box = box(it, folder)
            UidList(UID_VALIDITY, box.next, box.mail.size,
                box.mail.filterValues { mail -> !mail.flags.deleted }.keys.sorted().toLongArray())
        }
        override fun derive(options: ImapOptions): ImapTransport = LocalImap(authorize)
        override fun abort() { connected.set(false) }
        override fun close() { connected.set(false) }
    }

    private inner class LocalSmtp(private val accessAuthorization: () -> Unit) : SmtpTransport {
        override suspend fun verify(policyChanges: StateFlow<*>?, authorize: () -> Unit) {
            access(guard = { accessAuthorization(); authorize() }) { Unit }
        }
        override suspend fun send(message: MimeMessage, policyChanges: StateFlow<*>?, authorize: () -> Unit) {
            // Simulated failure is persisted independently, so Retry can complete after a restart.
            val fail = access(true, guard = { accessAuthorization(); authorize() }) { state ->
                accessAuthorization(); authorize()
                if (message.allRecipients.isNullOrEmpty()) throw MailException(MailErrorKind.INVALID_RECIPIENT, "No recipient")
                if (message.messageID == null) message.saveChanges()
                val id = requireNotNull(message.messageID)
                message.allRecipients.any { it.toString().contains("fail-once@example.invalid", ignoreCase = true) } &&
                    id !in state.accepted && state.failedOnce.add(retryKey(message) { accessAuthorization(); authorize() })
            }
            if (fail) throw MailException(MailErrorKind.NETWORK, "Sample temporary failure; retry sends locally only")
            access(true, guard = { accessAuthorization(); authorize() }) { state ->
                accessAuthorization(); authorize()
                val id = requireNotNull(message.messageID)
                if (state.accepted.add(id)) {
                    val reply = sample(
                        "Re: ${message.subject.orEmpty()}",
                        "SIMULATED REPLY: Your sample message stayed on this device. No email was delivered.\n\n" +
                            "RESPUESTA SIMULADA: Tu mensaje de ejemplo se ha quedado en este dispositivo. No se ha enviado ningún correo.",
                        inReplyTo = id,
                    )
                    authorize() // Recheck immediately before the local acceptance commit.
                    add(state, "INBOX", reply)
                    changed(ImapIdler.Change.NEW_MESSAGES)
                }
            }
        }
    }

    private inner class LocalIdle(private val authorize: () -> Unit) : IdleTransport {
        private val running = AtomicBoolean(false)
        override val idleSupported: Boolean get() = true
        override suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit) {
            check(running.compareAndSet(false, true)) { "Sample IDLE is already active" }
            try {
                access(guard = authorize) { Unit }
                var revision = changes.value.first
                onIdle()
                changes.collect { (next, change) ->
                    synchronized(lock) { ensurePresent(); authorize() }
                    if (next > revision) { revision = next; onChange(change) }
                }
            } finally { running.set(false) }
        }
        override suspend fun refresh(): Boolean = access(guard = authorize) { running.get() }
    }

    private fun encoded(message: MimeMessage): ByteArray {
        val buffer = object : ByteArrayOutputStream() {
            private fun checkSize(extra: Int) {
                if (extra > MAX_MESSAGE - size()) throw MailException(MailErrorKind.UNKNOWN, "Sample attachment is too large")
            }
            override fun write(value: Int) { checkSize(1); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { checkSize(length); super.write(bytes, offset, length) }
        }
        message.writeTo(buffer)
        return buffer.toByteArray()
    }

    private fun add(state: Snapshot, folder: String, message: MimeMessage, flags: RemoteFlags = RemoteFlags()): Long {
        val bytes = encoded(message)
        val box = box(state, folder)
        val uid = box.next++
        box.mail[uid] = Stored(bytes, flags, clock())
        return uid
    }

    /** Retry rebuilds MIME with a new ID/date/boundary; identify the same content instead. */
    private fun retryKey(original: MimeMessage, authorize: () -> Unit): String {
        // A newly composed MimeMessage has object content, not a raw stream. Parse its local
        // wire representation just as we parse received MIME, with the same bounded encoding.
        val message = MimeMessage(MailSessions.mimeSession(), encoded(original).inputStream())
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        field(HeaderValues.addresses(message.from).joinToString(";"))
        for (type in listOf(Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC)) {
            field(HeaderValues.addresses(message.getRecipients(type)).joinToString(";"))
        }
        field(message.subject.orEmpty())
        val body = MimeParser.parse(message)
        field(body.text.orEmpty()); field(body.html.orEmpty())
        body.attachments.forEach { attachment ->
            field(attachment.fileName); field(attachment.mimeType)
            val part = MimeParser.findPart(message, attachment.partPath) ?: return@forEach
            DigestOutputStream(object : OutputStream() { override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }, digest).use { out ->
                MimeParser.writeContent(part, out, authorize)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(subject: String, body: String, from: String = "tutor@example.invalid",
                       inReplyTo: String? = null, attachments: List<AttachmentSource> = emptyList()): MimeMessage =
        MessageComposer.build(EmailAddress(from, if (from.startsWith("tutor")) "Sample tutor / Tutor de ejemplo" else "Alex (sample / ejemplo)"),
            Draft(accountId = account.id, to = listOf(EmailAddress(account.email)), subject = subject, body = body,
                inReplyTo = inReplyTo, references = listOfNotNull(inReplyTo)), attachments, clock()).mime

    private fun seed(): Snapshot {
        val state = Snapshot()
        listOf("INBOX", "Drafts", "Sent", "Archive", "Trash", "Junk", "Projects").forEach { state.boxes[it] = Box() }
        add(state, "INBOX", sample("Welcome to sample mail / Bienvenido al correo de ejemplo",
            "This is a fictional mailbox stored only on this device. Read, reply, search, save drafts and move messages. " +
                "Sending creates a simulated reply, never an Internet delivery. Real accounts remain separate.\n\n" +
                "Este buzón ficticio se guarda solo en este dispositivo. Lee, responde, busca, guarda borradores y mueve mensajes. " +
                "Enviar genera una respuesta simulada, nunca una entrega por Internet. Las cuentas reales permanecen separadas."))
        val project = sample("Study project / Proyecto de estudio", "Shall we read the project notes together?\n\n¿Leemos juntos las notas del proyecto?", "alex@example.invalid")
        add(state, "INBOX", project)
        add(state, "INBOX", sample("Re: Study project / Proyecto de estudio",
            "Let us compare our ideas after reading. This is a fictional conversation.\n\nComparemos nuestras ideas después de leer. Esta conversación es ficticia.",
            inReplyTo = project.messageID))
        val attachment = object : AttachmentSource {
            override val fileName = "sample-notes.txt"
            override val mimeType = "text/plain"
            override fun openStream() = ByteArrayInputStream("SAMPLE FILE / ARCHIVO DE EJEMPLO\nRead, write and learn together. / Leed, escribid y aprended juntos.\n".toByteArray(Charsets.UTF_8))
        }
        add(state, "INBOX", sample("Sample attachment / Adjunto de ejemplo",
            "A harmless text attachment to try downloading and forwarding.\n\nUn adjunto de texto para probar la descarga y el reenvío.", attachments = listOf(attachment)))
        add(state, "INBOX", sample("Read and write safely / Lee y escribe con seguridad",
            "Check sender addresses and links before replying or sharing information. You can block an unwanted sender from a message menu " +
                "and manage blocked addresses in Settings. Report abuse through your provider's official channel.\n\n" +
                "Comprueba las direcciones y los enlaces antes de responder o compartir información. Puedes bloquear un remitente desde " +
                "el menú del mensaje y gestionar los bloqueos en Ajustes. Informa de abusos a través del canal oficial de tu proveedor."))
        add(state, "Projects", sample("Practice search / Practica la búsqueda", "The library workshop meets on Friday. Search for library or biblioteca.\n\nEl taller de la biblioteca se reúne el viernes."), RemoteFlags(seen = true))
        add(state, "Archive", sample("Archived example / Ejemplo archivado", "Move this fictional message between folders.\n\nMueve este mensaje ficticio entre carpetas."), RemoteFlags(seen = true))
        val draft = MessageComposer.build(EmailAddress(account.email, account.displayName), Draft(accountId = account.id,
            to = listOf(EmailAddress("alex@example.invalid")), subject = "Draft example / Borrador de ejemplo",
            body = "This sample draft is ready to edit. / Este borrador de ejemplo está listo para editar."), emptyList(), clock()).mime
        add(state, "Drafts", draft, RemoteFlags(seen = true, draft = true))
        return state
    }

    private fun save(state: Snapshot) {
        directory.mkdirs()
        val temporary = File(directory, "mailbox-v1.tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC); out.writeInt(state.boxes.size)
                state.boxes.forEach { (name, box) ->
                    out.writeUTF(name); out.writeLong(box.next); out.writeInt(box.mail.size)
                    box.mail.forEach { (uid, mail) ->
                        out.writeLong(uid); out.writeLong(mail.arrived); out.writeInt(flagBits(mail.flags))
                        out.writeInt(mail.bytes.size); out.write(mail.bytes)
                    }
                }
                for (set in listOf(state.accepted, state.failedOnce)) { out.writeInt(set.size); set.forEach(out::writeUTF) }
            }
            if (temporary.length() > MAX_SNAPSHOT) throw MailException(MailErrorKind.UNKNOWN, "The sample mailbox is full; remove old sample messages")
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    private fun load(): Snapshot = MailSessions.withEngineClassLoader {
        if (!file.exists()) return@withEngineClassLoader seed().also(::save)
        if (file.length() > MAX_SNAPSHOT) throw MailException(MailErrorKind.UNKNOWN, "Invalid sample mailbox size")
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC)
                val state = Snapshot()
                repeat(input.readInt().also { require(it in 1..30) }) {
                    val name = input.readUTF()
                    val box = Box(input.readLong().also { require(it > 0) })
                    repeat(input.readInt().also { require(it in 0..100_000) }) {
                        val uid = input.readLong().also { require(it in 1 until box.next) }
                        val arrived = input.readLong()
                        val bits = input.readInt()
                        val bytes = ByteArray(input.readInt().also { require(it in 0..MAX_MESSAGE) })
                        input.readFully(bytes)
                        box.mail[uid] = Stored(bytes, flags(bits), arrived)
                    }
                    state.boxes[name] = box
                }
                for (set in listOf(state.accepted, state.failedOnce)) repeat(input.readInt().also { require(it in 0..100_000) }) { set += input.readUTF() }
                require(input.read() == -1)
                state
            }
        } catch (e: Exception) {
            throw MailException(MailErrorKind.UNKNOWN, "Sample mailbox could not be read; remove and recreate the sample account", e)
        }
    }

    private fun flagBits(f: RemoteFlags): Int =
        (if (f.seen) 1 else 0) or (if (f.flagged) 2 else 0) or (if (f.answered) 4 else 0) or
            (if (f.forwarded) 8 else 0) or (if (f.draft) 16 else 0) or (if (f.deleted) 32 else 0)
    private fun flags(bits: Int) = RemoteFlags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0,
        bits and 8 != 0, bits and 16 != 0, bits and 32 != 0)

    private companion object {
        const val MAGIC = 0x55504d31
        const val UID_VALIDITY = 1L
        const val MAX_MESSAGE = 20 * 1024 * 1024
        const val MAX_SNAPSHOT = 80L * 1024 * 1024
    }
}
