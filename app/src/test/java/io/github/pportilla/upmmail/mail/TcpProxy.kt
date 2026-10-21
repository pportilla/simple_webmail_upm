package io.github.pportilla.upmmail.mail

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Forwards local connections to [targetPort], counting them, and can break a connection in the
 * middle of what the server sends (see [cutAfter]).
 */
class TcpProxy(private val targetPort: Int) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val untilCut = AtomicLong(-1)

    @Volatile
    private var cutEvery = -1L

    val port: Int get() = server.localPort

    /** Connections accepted so far. */
    val connections = AtomicInteger()

    init {
        thread(isDaemon = true, name = "tcp-proxy") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                connections.incrementAndGet()
                val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
                sockets += client
                sockets += upstream
                thread(isDaemon = true) { pump(client.getInputStream(), upstream.getOutputStream(), client, upstream, false) }
                thread(isDaemon = true) { pump(upstream.getInputStream(), client.getOutputStream(), client, upstream, true) }
            }
        }
    }

    /**
     * Resets the connection (both sides) once [bytes] more bytes came from the server. With
     * [everyConnection], each later connection is cut after [bytes] of its own as well.
     */
    fun cutAfter(bytes: Long, everyConnection: Boolean = false) {
        cutEvery = if (everyConnection) bytes else -1
        untilCut.set(bytes)
    }

    private fun pump(input: InputStream, output: OutputStream, client: Socket, upstream: Socket, fromServer: Boolean) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val left = if (fromServer) untilCut.get() else -1
                if (left in 0..read.toLong()) {
                    output.write(buffer, 0, left.toInt())
                    output.flush()
                    untilCut.set(cutEvery)
                    reset(client)
                    reset(upstream)
                    return
                }
                if (left > 0) untilCut.addAndGet(-read.toLong())
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // One side went away; the other is closed below.
        }
        close(client)
        close(upstream)
    }

    private fun reset(socket: Socket) {
        try {
            socket.setSoLinger(true, 0)
        } catch (_: IOException) {
            // Already closed.
        }
        close(socket)
    }

    private fun close(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    override fun close() {
        server.close()
        sockets.forEach(::close)
    }
}
