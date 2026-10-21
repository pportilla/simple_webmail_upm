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
    private val TOO_LARGE_PATTERN = Regex(
        """(?i)(\b552\b|\b5\.3\.4\b|\[TOOBIG]|message size|size limit|too (large|big)|exceeds?\b.*\bsize|size\b.*\bexceed)""",
    )

    /** RFC 5530 codes of a passing failure, and wording like "Temporary authentication failure". */
    private val TEMPORARY_REFUSAL_PATTERN = Regex("""(?i)(\[(UNAVAILABLE|SERVERBUG|INUSE)]|\btempor)""")

    /** Maps Jakarta Mail / IO / TLS exceptions to a MailException with the right kind. */
    fun map(t: Throwable): MailException {
        if (t is MailException) return t
        val chain = causeChain(t)
        val kind = kindOf(t, chain)
        return MailException(kind, describe(t, chain), t)
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

    /** True when the socket may still be open but the peer stopped answering. */
    internal fun isTimeout(t: Throwable): Boolean = causeChain(t).any { it is SocketTimeoutException }

    /**
     * True for a login refused for a passing reason, e.g. "NO [UNAVAILABLE]" while the server's
     * authentication backend is down. Jakarta Mail reports every refused login, and so [map]
     * an AUTHENTICATION failure, as if the password were wrong.
     */
    internal fun isTemporaryLoginRefusal(t: Throwable): Boolean = causeChain(t).any {
        it is AuthenticationFailedException && TEMPORARY_REFUSAL_PATTERN.containsMatchIn(it.message.orEmpty())
    }

    private fun kindOf(t: Throwable, chain: List<Throwable>): MailErrorKind {
        if (chain.any { it is AuthenticationFailedException }) return MailErrorKind.AUTHENTICATION
        if (isCertificateProblem(chain)) return MailErrorKind.CERTIFICATE

        val sendFailure = chain.firstOrNull { it is SendFailedException } as SendFailedException?
        if (sendFailure != null) {
            val rejected = !sendFailure.invalidAddresses.isNullOrEmpty() ||
                chain.any { it is SMTPAddressFailedException }
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
