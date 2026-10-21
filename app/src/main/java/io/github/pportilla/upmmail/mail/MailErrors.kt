package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.StoreClosedException
import org.eclipse.angus.mail.iap.ConnectionException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.eclipse.angus.mail.util.MailConnectException
import org.eclipse.angus.mail.util.SocketConnectException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

object MailErrors {
    // A bare 552 is not enough: to RCPT it means "mailbox full" (see isTemporarySmtpRefusal).
    private val TOO_LARGE_PATTERN = Regex(
        """(?i)(\b5\.3\.4\b|\[TOOBIG]|message size|size limit|too (large|big)|exceeds?\b.*\bsize|size\b.*\bexceed)""",
    )

    /** SMTP reply "452 4.5.3 Too many recipients": the same message would be refused again. */
    private val TOO_MANY_RECIPIENTS_PATTERN = Regex("""(?i)((?<![\d.])4\.5\.3(?!\.?\d)|too many recipients)""")

    /** An SMTP reply with a 4xx (transient negative) code, as AUTH failures report it. */
    private val SMTP_TEMPORARY_REPLY = Regex("""^\s*4\d\d\b""")

    /** RFC 5530 codes of a passing failure, and wording like "Temporary authentication failure". */
    private val TEMPORARY_REFUSAL_PATTERN = Regex("""(?i)(\[(UNAVAILABLE|SERVERBUG|INUSE)]|\btempor)""")

    /** Maps Jakarta Mail / IO / TLS exceptions to a MailException with the right kind. */
    fun map(t: Throwable): MailException {
        if (t is MailException) return t
        val chain = causeChain(t)
        val temporary = isTemporarySmtpRefusal(chain)
        val kind = if (temporary) MailErrorKind.SERVER else kindOf(t, chain)
        return MailException(kind, describe(t, chain), t, temporary)
    }

    /**
     * True for failures that mean the connection is gone (as opposed to a slow or refusing
     * server), so a fresh connection is likely to succeed.
     */
    internal fun isConnectionDrop(t: Throwable): Boolean {
        val chain = causeChain(t)
        if (chain.any { it is MailConnectException || it is SocketTimeoutException || it is UnknownHostException }) {
            return false
        }
        if (chain.any { it is AuthenticationFailedException }) return false
        return chain.any {
            it is StoreClosedException ||
                it is FolderClosedException ||
                it is ConnectionException ||
                it is EOFException ||
                (it is SocketException && it !is ConnectException && it !is NoRouteToHostException) ||
                (it is javax.net.ssl.SSLException && it !is SSLHandshakeException) ||
                (it is IllegalStateException && it.message.orEmpty().let { m -> "closed" in m || "Not connected" in m }) ||
                (it is MessagingException && it.message == "connection failure")
        }
    }

    /**
     * True when the socket may still be open but the peer stopped answering. Angus Mail turns a
     * read timeout during a folder command into a synthetic BYE and then into a
     * FolderClosedException (or StoreClosedException) that keeps only the text of the timeout, not
     * the exception; those count as timeouts too, while [isConnectionDrop] still matches them.
     */
    internal fun isTimeout(t: Throwable): Boolean = causeChain(t).any {
        it is SocketTimeoutException ||
            ((it is FolderClosedException || it is StoreClosedException || it is ConnectionException) &&
                it.message.orEmpty().contains("SocketTimeoutException")) ||
            (it is ConnectionException && it.response?.exception is SocketTimeoutException)
    }

    /**
     * True when the server could not be reached at all: no connection could be opened (refused,
     * no route, unknown host, or connecting timed out). Unlike a slow answer, this concerns every
     * folder of the account alike.
     */
    internal fun isConnectFailure(t: Throwable): Boolean = causeChain(t).any {
        it is MailConnectException || it is SocketConnectException || it is UnknownHostException ||
            it is ConnectException || it is NoRouteToHostException
    }

    /**
     * True for a login refused for a passing reason, e.g. "NO [UNAVAILABLE]" while the server's
     * authentication backend is down. Jakarta Mail reports every refused login, and so [map]
     * an AUTHENTICATION failure, as if the password were wrong.
     */
    internal fun isTemporaryLoginRefusal(t: Throwable): Boolean = causeChain(t).any {
        it is AuthenticationFailedException && TEMPORARY_REFUSAL_PATTERN.containsMatchIn(it.message.orEmpty())
    }

    /**
     * True for an SMTP reply that refuses for a passing reason: a 4xx code to AUTH, MAIL FROM,
     * RCPT, DATA or the end of the data, or a 552 to RCPT ("mailbox full", which RFC 5321 says to
     * treat as 452). The server accepted nothing, so the message can be sent again later.
     * "Too many recipients" is not temporary: the same message would be refused again.
     */
    private fun isTemporarySmtpRefusal(chain: List<Throwable>): Boolean {
        if (chain.any { TOO_MANY_RECIPIENTS_PATTERN.containsMatchIn(it.message.orEmpty()) }) return false
        val auth = chain.firstOrNull { it is AuthenticationFailedException }
        if (auth != null) return SMTP_TEMPORARY_REPLY.containsMatchIn(auth.message.orEmpty())
        val sendFailure = chain.firstOrNull { it is SendFailedException } as SendFailedException? ?: return false
        if (!sendFailure.invalidAddresses.isNullOrEmpty()) return false
        val commandCodes = chain.filterIsInstance<SMTPSendFailedException>().map { it.returnCode }
        if (commandCodes.isNotEmpty()) return commandCodes.all { it in 400..499 }
        val recipients = chain.filterIsInstance<SMTPAddressFailedException>()
        return recipients.isNotEmpty() && recipients.all {
            it.returnCode in 400..499 || (it.returnCode == 552 && !isTooLarge(listOf(it)))
        }
    }

    private fun kindOf(t: Throwable, chain: List<Throwable>): MailErrorKind {
        val authIndex = chain.indexOfFirst { it is AuthenticationFailedException }
        if (authIndex >= 0) {
            // SMTP reports a connection lost (or a TLS failure) during AUTH as a refused login.
            val causes = chain.subList(authIndex + 1, chain.size)
            if (causes.none { it is IOException || isNetwork(it) }) return MailErrorKind.AUTHENTICATION
        }
        if (isCertificateProblem(chain)) return MailErrorKind.CERTIFICATE

        val sendFailure = chain.firstOrNull { it is SendFailedException } as SendFailedException?
        if (sendFailure != null) {
            // Only permanent (5xx) refusals mean a wrong address; 4xx and 552 recipients are
            // valid but unsent (see isTemporarySmtpRefusal).
            val rejected = !sendFailure.invalidAddresses.isNullOrEmpty() ||
                chain.any { it is SMTPAddressFailedException && it.returnCode in 500..599 && it.returnCode != 552 }
            if (isTooLarge(chain)) return MailErrorKind.TOO_LARGE
            if (rejected) return MailErrorKind.INVALID_RECIPIENT
            if (chain.any { it is SMTPSenderFailedException }) return MailErrorKind.SERVER
            if (sendFailure.message.orEmpty().contains("No recipient", ignoreCase = true)) {
                return MailErrorKind.INVALID_RECIPIENT
            }
        }
        if (chain.any { it is SMTPSendFailedException } && isTooLarge(chain)) return MailErrorKind.TOO_LARGE

        if (chain.any { it is FolderNotFoundException || it is MessageRemovedException }) {
            return MailErrorKind.NOT_FOUND
        }
        // Angus reports a message expunged while its content was being read this way.
        if (chain.any { it.message.orEmpty().contains("expunged message", ignoreCase = true) }) {
            return MailErrorKind.NOT_FOUND
        }
        // Local files (attachments being sent) that vanished are not a network problem.
        if (chain.any { it is FileNotFoundException || it is SecurityException }) return MailErrorKind.UNKNOWN

        if (chain.any { isNetwork(it) }) return MailErrorKind.NETWORK
        if (chain.any { it is IOException }) return MailErrorKind.NETWORK

        if (t is MessagingException || chain.any { it is MessagingException }) {
            if (isTooLarge(chain)) return MailErrorKind.TOO_LARGE
            return MailErrorKind.SERVER
        }
        if (chain.any { it is org.eclipse.angus.mail.iap.ProtocolException }) return MailErrorKind.SERVER
        return MailErrorKind.UNKNOWN
    }

    private fun isNetwork(t: Throwable): Boolean =
        t is UnknownHostException ||
            t is ConnectException ||
            t is NoRouteToHostException ||
            t is SocketTimeoutException ||
            t is SocketException ||
            t is MailConnectException ||
            t is StoreClosedException ||
            t is FolderClosedException ||
            t is ConnectionException ||
            (t is IllegalStateException && t.message.orEmpty().let { "closed" in it || "Not connected" in it }) ||
            (t is MessagingException && t.message == "connection failure")

    private fun isCertificateProblem(chain: List<Throwable>): Boolean {
        val certificate = chain.any {
            it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException
        }
        if (certificate) return true
        val handshake = chain.firstOrNull { it is SSLHandshakeException } ?: return false
        // A handshake cut short by the network (common on mobile) is not a certificate problem.
        val causes = causeChain(handshake).drop(1)
        return causes.none { it is EOFException || it is SocketException || it is SocketTimeoutException }
    }

    private fun isTooLarge(chain: List<Throwable>): Boolean = chain.any { e ->
        (e is SMTPSendFailedException && e.returnCode == 552) ||
            (e is MessagingException && TOO_LARGE_PATTERN.containsMatchIn(e.message.orEmpty()))
    }

    private fun describe(t: Throwable, chain: List<Throwable>): String {
        val top = t.message?.trim()?.takeIf { it.isNotEmpty() } ?: t.javaClass.simpleName
        val root = chain.last()
        if (root === t) return top
        val rootText = root.message?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "${root.javaClass.simpleName}: $it" }
            ?: root.javaClass.simpleName
        return if (top.contains(root.message.orEmpty().ifEmpty { "\u0000" })) top else "$top ($rootText)"
    }

    /** [t] followed by its causes (MessagingException exposes its "next exception" as cause). */
    private fun causeChain(t: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>()
        var current: Throwable? = t
        while (current != null && result.none { it === current } && result.size < 16) {
            result += current
            current = current.cause
        }
        return result
    }
}
