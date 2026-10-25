package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPSSLStore
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Watches one folder (the INBOX) with IMAP IDLE (RFC 2177), so that the server reports new mail
 * within seconds instead of the app asking every few minutes.
 *
 * [run] opens a connection of its own (never shared with [ImapClient]), opens the folder
 * read-only and idles until cancelled, reporting every change the server announces. Every
 * [refreshIntervalMillis], and on [refresh], the IDLE command is ended with a NOOP round trip
 * before idling again. This keeps NAT mappings alive, stays far below the 30 minutes after
 * which servers may log idle clients out, and finds dead connections: one that does not answer
 * within [checkTimeoutMillis] is closed, so [run] fails and the caller can reconnect.
 *
 * A thread blocked in IDLE only wakes up when the server sends something, so cancellation and
 * failed checks close the socket from another thread. One [run] at a time per instance.
 */
class ImapIdler(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
    private val folderName: String = INBOX,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MS,
    private val checkTimeoutMillis: Long = CHECK_TIMEOUT_MS,
) : IdleTransport {
    /** A change announced by the server. */
    enum class Change {
        /** New messages (EXISTS). */
        NEW_MESSAGES,

        /** Messages were expunged, e.g. moved or deleted on another device. */
        MESSAGES_REMOVED,

        /** Flags changed, e.g. a message was read on another device. */
        FLAGS_CHANGED,
    }

    /** Whether the server advertises IDLE; null until [run] has logged in once. */
    @Volatile
    override var idleSupported: Boolean? = null
        private set

    /** Capabilities treated as absent (upper case), so tests can exercise the polling fallback. */
    internal var disabledCapabilities: Set<String> = emptySet()

    private val active = AtomicReference<Connection?>(null)
    private val checkLock = Mutex()

    init {
        MailSessions.ensureInitialized()
    }

    /**
     * Connects, opens the folder and idles until cancelled. [onIdle] is called each time an IDLE
     * command is about to start (after connecting and after every check), [onChange] for every
     * change the server reports. Both are called on background threads and must return quickly.
     *
     * Returns normally only when the server does not support IDLE ([idleSupported] is then
     * false): the caller should poll instead. A failed or lost connection throws [MailException].
     */
    override suspend fun run(onIdle: () -> Unit, onChange: (Change) -> Unit) {
        val connection = Connection()
        try {
            coroutineScope {
                // Closing the socket is the only way to wake a thread blocked in IDLE (or login).
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.abort()
                    }
                }
                val supported = withContext(Dispatchers.IO) { connection.open(onChange) }
                idleSupported = supported
                if (supported) {
                    active.set(connection)
                    launch {
                        while (true) {
                            delay(refreshIntervalMillis)
                            checkLock.withLock { check(connection) }
                        }
                    }
                    withContext(Dispatchers.IO) { connection.idleLoop(onIdle) }
                }
                closer.cancel()
            }
        } catch (e: Exception) {
            // When cancelled, report the cancellation rather than the socket closed because of it.
            currentCoroutineContext().ensureActive()
            throw if (e is CancellationException) e else MailErrors.map(e)
        } finally {
            active.compareAndSet(connection, null)
            connection.abort()
            withContext(NonCancellable + Dispatchers.IO) { connection.release() }
        }
    }

    /**
     * Ends the current IDLE command and checks the connection with a NOOP; [run] then idles
     * again. Returns true if the server answered. A connection that does not answer within the
     * check timeout, or whose check is cancelled, is closed, which makes [run] throw. Returns
     * false at once when [run] is not idling.
     */
    override suspend fun refresh(): Boolean {
        val connection = active.get() ?: return false
        return checkLock.withLock { check(connection) }
    }

    private suspend fun check(connection: Connection): Boolean = coroutineScope {
        val answered = AtomicBoolean(false)
        val watchdog = launch {
            try {
                delay(checkTimeoutMillis)
            } finally {
                if (!answered.get()) connection.abort()
            }
        }
        try {
            withContext(Dispatchers.IO) { connection.noop() }
            answered.set(true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connection.abort()
            false
        } finally {
            watchdog.cancel()
        }
    }

    /** One connection: its own session, store and folder, and the sockets they run on. */
    private inner class Connection {
        private val sockets = TrackingSocketFactory()
        private val session = MailSessions.imapSession(settings, sockets)

        @Volatile
        private var store: IMAPStore? = null

        @Volatile
        private var folder: IMAPFolder? = null

        /** Logs in and opens the folder read-only. Returns false, opening nothing, if the server cannot IDLE. */
        fun open(onChange: (Change) -> Unit): Boolean = MailSessions.withEngineClassLoader {
            val store = if (settings.security == SecurityMode.SSL_TLS) {
                IMAPSSLStore(session, null)
            } else {
                IMAPStore(session, null)
            }
            this.store = store
            store.connect(settings.host, settings.port, settings.username, passwordProvider())
            // Asked before the folder takes over the store's connection: later it would open another one.
            if ("IDLE" in disabledCapabilities || !store.hasCapability("IDLE")) return@withEngineClassLoader false
            val folder = store.getFolder(folderName) as IMAPFolder
            folder.addMessageCountListener(
                object : MessageCountListener {
                    override fun messagesAdded(e: MessageCountEvent) = onChange(Change.NEW_MESSAGES)

                    override fun messagesRemoved(e: MessageCountEvent) = onChange(Change.MESSAGES_REMOVED)
                },
            )
            folder.addMessageChangedListener { onChange(Change.FLAGS_CHANGED) }
            folder.open(Folder.READ_ONLY)
            this.folder = folder
            true
        }

        /** Idles until the connection fails; a check ends one IDLE command, then the next starts. */
        fun idleLoop(onIdle: () -> Unit): Nothing {
            val folder = checkNotNull(folder) { "Folder not open" }
            while (true) {
                onIdle()
                MailSessions.withEngineClassLoader { folder.idle() }
            }
        }

        /** Ends the running IDLE command (Jakarta Mail sends DONE and waits for it) and sends NOOP. */
        fun noop() {
            val folder = folder ?: throw MessagingException("Folder not open")
            MailSessions.withEngineClassLoader {
                folder.doCommand { protocol ->
                    protocol.noop()
                    null
                }
            }
        }

        /** Closes the sockets; safe from any thread, at any time, any number of times. */
        fun abort() = sockets.closeAll()

        /** Releases the store and its event threads. Called after [abort], so it cannot block on the network. */
        fun release() {
            val store = store ?: return
            try {
                MailSessions.withEngineClassLoader { store.close() }
            } catch (_: Exception) {
                // The connection is gone already; nothing else to release.
            }
        }
    }

    /**
     * Creates the plain sockets of one connection (TLS is layered on top by Jakarta Mail) and
     * can close them all from any thread, including sockets requested after [closeAll].
     */
    private class TrackingSocketFactory : SocketFactory() {
        private val sockets = ArrayList<Socket>()
        private var closed = false

        override fun createSocket(): Socket {
            val socket = Socket()
            synchronized(sockets) {
                if (!closed) {
                    sockets += socket
                    return socket
                }
            }
            socket.close()
            throw SocketException("Connection closed")
        }

        // Jakarta Mail only uses createSocket() above; the others complete the contract.

        override fun createSocket(host: String, port: Int): Socket =
            connect(InetSocketAddress(host, port), null)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            connect(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            connect(InetSocketAddress(host, port), null)

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            connect(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

        fun closeAll() {
            val open = synchronized(sockets) {
                closed = true
                sockets.toList()
            }
            for (socket in open) {
                try {
                    socket.close()
                } catch (_: IOException) {
                    // Closed anyway.
                }
            }
        }

        private fun connect(remote: SocketAddress, local: SocketAddress?): Socket {
            val socket = createSocket()
            try {
                if (local != null) socket.bind(local)
                socket.connect(remote)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return socket
        }
    }

    companion object {
        const val INBOX = "INBOX"

        /** How long one IDLE command lasts at most before the connection is checked. */
        const val REFRESH_INTERVAL_MS = 9 * 60_000L

        /** How long a check may take before the connection is considered dead. */
        const val CHECK_TIMEOUT_MS = 20_000L
    }
}
