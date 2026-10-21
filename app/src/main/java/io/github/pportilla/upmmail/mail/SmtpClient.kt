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
import org.eclipse.angus.mail.smtp.SMTPSSLTransport
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

    suspend fun send(message: MimeMessage): Unit = withTransport { transport ->
        val recipients = message.allRecipients
        if (recipients.isNullOrEmpty()) {
            throw MailException(MailErrorKind.INVALID_RECIPIENT, "The message has no recipients")
        }
        // Transport.send would call saveChanges() and replace our Message-ID; the composer has
        // already saved the message, so only do it for messages that were never saved.
        if (message.getHeader("Message-ID") == null) message.saveChanges()
        transport.sendMessage(message, recipients)
    }

    private suspend fun withTransport(block: (SMTPTransport) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                MailSessions.withEngineClassLoader {
                    val transport = if (settings.security == SecurityMode.SSL_TLS) {
                        SMTPSSLTransport(session, null)
                    } else {
                        SMTPTransport(session, null)
                    }
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
}
