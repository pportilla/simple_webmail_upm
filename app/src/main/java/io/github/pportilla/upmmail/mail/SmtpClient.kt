package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.smtp.SMTPTransport

/** SMTP submission for one account. Functions run on Dispatchers.IO and throw MailException. */
class SmtpClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
) {
    private val session: Session by lazy { MailSessions.smtpSession(settings) }
    private val mutex = Mutex()

    init {
        MailSessions.ensureInitialized()
    }

    /** Connects and authenticates, then disconnects. */
    suspend fun verify(): Unit = withTransport { }

    /**
     * Sends [message]. A failure that may be retried ([MailException.isTransient]) guarantees
     * that the server did not take the message. When the connection fails after the whole
     * message went out, the server may already have accepted it: that is reported as a
     * non-transient SEND_UNCERTAIN failure, so the message is not sent twice behind the user's
     * back and the user is told to check before sending it again.
     */
    suspend fun send(message: MimeMessage): Unit = withTransport { transport ->
        val recipients = message.allRecipients
        if (recipients.isNullOrEmpty()) {
            throw MailException(MailErrorKind.INVALID_RECIPIENT, "The message has no recipients")
        }
        // Transport.send would call saveChanges() and replace our Message-ID; the composer has
        // already saved the message, so only do it for messages that were never saved.
        if (message.getHeader("Message-ID") == null) message.saveChanges()
        try {
            transport.sendMessage(message, recipients)
        } catch (e: Exception) {
            if (!transport.dataTerminated) throw e
            val error = MailErrors.map(e)
            // A reply (2xx/4xx/5xx) is a clear answer; only a lost reply leaves the outcome open.
            if (error.kind != MailErrorKind.NETWORK) throw error
            throw MailException(
                MailErrorKind.SEND_UNCERTAIN,
                "No answer from the server after the message was transmitted; it may have been sent " +
                    "(${error.message})",
                e,
            )
        }
    }

    private suspend fun withTransport(block: (TrackingTransport) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                MailSessions.withEngineClassLoader {
                    val transport = TrackingTransport(session, settings.security == SecurityMode.SSL_TLS)
                    try {
                        transport.connect(settings.host, settings.port, settings.username, passwordProvider())
                        block(transport)
                    } finally {
                        try {
                            transport.close()
                        } catch (_: Exception) {
                            // The message was handed over (or the failure is reported below).
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is VirtualMachineError) throw e
                throw MailErrors.map(e)
            }
        }
    }

    /**
     * SMTPTransport (or, with [ssl], the equivalent of SMTPSSLTransport) that records when the
     * end of the message data starts going out: from then on the server may accept the message
     * at any moment, whether or not its reply arrives.
     */
    private class TrackingTransport(session: Session, ssl: Boolean) :
        SMTPTransport(session, null, if (ssl) "smtps" else "smtp", ssl) {
        @Volatile
        var dataTerminated = false
            private set

        override fun finishData() {
            dataTerminated = true
            super.finishData()
        }

        override fun finishBdat() {
            dataTerminated = true
            super.finishBdat()
        }
    }
}
