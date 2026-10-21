package io.github.pportilla.upmmail.mail

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

/**
 * Creates the plain TCP sockets of one abortable [ImapClient] (Jakarta Mail layers TLS on top,
 * for SSL/TLS and STARTTLS alike) and can close them from any thread. Closing the socket is the
 * only way to wake a thread blocked reading the server's answer: closing the store or the folder
 * waits for that same thread.
 *
 * After [closeAll] the factory refuses new sockets until [reopen], so a connection that was being
 * set up while the client was aborted cannot survive the abort.
 */
internal class AbortableSocketFactory : SocketFactory() {
    private val lock = Any()

    // Guarded by lock.
    private val sockets = ArrayList<Socket>()
    private var refusing = false

    /** Sockets created and not closed yet; for tests. */
    val openCount: Int
        get() = synchronized(lock) { sockets.count { !it.isClosed } }

    override fun createSocket(): Socket {
        val socket = Socket()
        synchronized(lock) {
            if (!refusing) {
                sockets.removeAll { it.isClosed }
                sockets += socket
                return socket
            }
        }
        socket.close()
        throw SocketException("aborted")
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

    /** Closes every socket and refuses new ones until [reopen]. Safe from any thread, any number of times. */
    fun closeAll() {
        val open = synchronized(lock) {
            refusing = true
            sockets.toList().also { sockets.clear() }
        }
        for (socket in open) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Closed anyway.
            }
        }
    }

    /** Allows new sockets again; called when the next command starts. */
    fun reopen() {
        synchronized(lock) { refusing = false }
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
