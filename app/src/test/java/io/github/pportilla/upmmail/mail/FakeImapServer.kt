package io.github.pportilla.upmmail.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A scripted IMAP server for what GreenMail cannot do: commands that are never answered,
 * progress lines, [BADCHARSET], NAMESPACE, a server that never greets.
 *
 * The session commands are answered here (CAPABILITY, LOGIN, NOOP, LOGOUT, EXAMINE/SELECT, CLOSE,
 * UNSELECT, STATUS, NAMESPACE, LIST); every other command goes to [handler], and one the handler
 * does not answer is never answered. Literals (`{n}`) are accepted and recorded inline, so
 * [commands] reads like the command the client meant: `UID SEARCH CHARSET UTF-8 BODY revisión ALL`.
 */
internal class FakeImapServer(
    private val greet: Boolean = true,
    private val capabilities: String = "IMAP4rev1 UIDPLUS",
    private val handler: Exchange.() -> Unit = { reply("$tag BAD Unexpected command") },
) : Closeable {
    /** What EXAMINE and STATUS report. */
    @Volatile
    var exists = 0

    @Volatile
    var uidValidity = 7L

    @Volatile
    var uidNext = 1L

    @Volatile
    var namespace = "* NAMESPACE ((\"\" \"/\")) NIL NIL"

    /** The untagged LIST answers. */
    @Volatile
    var listing: List<String> = listOf("* LIST (\\HasNoChildren) \"/\" INBOX")

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    private val received = CopyOnWriteArrayList<String>()
    private val connections = CopyOnWriteArrayList<Socket>()

    /** Connections accepted so far. */
    val accepted = AtomicInteger()

    /** Connections the client has not closed yet. */
    val open = AtomicInteger()

    /** Every command received, without its tag, in order. */
    val commands: List<String>
        get() = received.toList()

    /** One command being answered. */
    class Exchange(val tag: String, val command: String, private val out: OutputStream) {
        fun reply(vararg lines: String) = send(out, *lines)
    }

    init {
        thread(isDaemon = true, name = "fake-imap") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                accepted.incrementAndGet()
                open.incrementAndGet()
                connections += socket
                thread(isDaemon = true, name = "fake-imap-connection") { serve(socket) }
            }
        }
    }

    /** Commands received with this verb: "LOGIN", "EXAMINE", "UID SEARCH", "UID FETCH"... */
    fun count(verb: String): Int = commands.count { verbOf(it) == verb }

    override fun close() {
        server.close()
        for (socket in connections) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Closed anyway.
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                if (!greet) {
                    // Accepts the connection and says nothing, like a server that hangs.
                    while (input.read() >= 0) continue
                    return
                }
                send(out, "* OK [CAPABILITY $capabilities] Fake ready")
                while (true) {
                    val line = readCommand(input, out) ?: return
                    val tag = line.substringBefore(' ')
                    val command = line.substringAfter(' ', "")
                    received += command
                    val exchange = Exchange(tag, command, out)
                    when (verbOf(command)) {
                        "CAPABILITY" -> exchange.reply("* CAPABILITY $capabilities", "$tag OK Done")
                        "LOGIN" -> exchange.reply("$tag OK [CAPABILITY $capabilities] Logged in")
                        "NOOP", "CLOSE", "UNSELECT", "CHECK" -> exchange.reply("$tag OK Done")
                        "LOGOUT" -> {
                            exchange.reply("* BYE Logging out", "$tag OK Done")
                            return
                        }
                        "EXAMINE", "SELECT" -> exchange.reply(
                            "* $exists EXISTS",
                            "* 0 RECENT",
                            "* OK [UIDVALIDITY $uidValidity] UIDs valid",
                            "* OK [UIDNEXT $uidNext] Predicted next UID",
                            "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)",
                            "$tag OK [READ-ONLY] Done",
                        )
                        "STATUS" -> {
                            val mailbox = command.substringAfter(' ').substringBeforeLast(" (")
                            exchange.reply(
                                "* STATUS $mailbox (MESSAGES $exists UNSEEN 0 UIDNEXT $uidNext UIDVALIDITY $uidValidity)",
                                "$tag OK Done",
                            )
                        }
                        "NAMESPACE" -> exchange.reply(namespace, "$tag OK Done")
                        "LIST" -> exchange.reply(*listing.toTypedArray(), "$tag OK Done")
                        else -> handler(exchange)
                    }
                }
            }
        } catch (_: IOException) {
            // The client closed the connection.
        } finally {
            open.decrementAndGet()
        }
    }

    /** One command line, with its literals read and put inline. */
    private fun readCommand(input: InputStream, out: OutputStream): String? {
        val text = StringBuilder()
        while (true) {
            val line = readLine(input) ?: return null
            val literal = LITERAL.find(line)
            if (literal == null) {
                text.append(line)
                return text.toString()
            }
            text.append(line, 0, literal.range.first)
            val size = literal.groupValues[1].toInt()
            if (literal.groupValues[2].isEmpty()) send(out, "+ Ready for literal")
            val bytes = input.readNBytes(size)
            if (bytes.size < size) return null
            text.append(String(bytes, Charsets.UTF_8))
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    }

    companion object {
        private val LITERAL = Regex("""\{(\d+)(\+?)}$""")

        fun verbOf(command: String): String {
            val words = command.split(' ')
            val first = words.first().uppercase(Locale.ROOT)
            return if (first == "UID" && words.size > 1) "UID ${words[1].uppercase(Locale.ROOT)}" else first
        }

        private fun send(out: OutputStream, vararg lines: String) {
            synchronized(out) {
                for (line in lines) out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }
}
