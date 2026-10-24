package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.smtp.SMTPTransport

/** SMTP submission for one account. Functions run on Dispatchers.IO and throw MailException. */
class SmtpClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
) {
    private val mutex = Mutex()

    init {
        MailSessions.ensureInitialized()
    }

    /** Connects and authenticates, then disconnects. */
    suspend fun verify(policyChanges: StateFlow<*>? = null, authorize: () -> Unit = {}): Unit =
        withTransport(policyChanges, authorize) { }

    /**
     * Sends [message]. A failure that may be retried ([MailException.isTransient]) guarantees
     * that the server did not take the message. When the connection fails after the whole
     * message went out, the server may already have accepted it: that is reported as a
     * non-transient SEND_UNCERTAIN failure, so the message is not sent twice behind the user's
     * back and the user is told to check before sending it again.
     */
    suspend fun send(
        message: MimeMessage,
        policyChanges: StateFlow<*>? = null,
        authorize: () -> Unit = {},
    ): Unit = withTransport(policyChanges, authorize) { transport ->
        val recipients = message.allRecipients
        if (recipients.isNullOrEmpty()) {
            throw MailException(MailErrorKind.INVALID_RECIPIENT, "The message has no recipients")
        }
        // Transport.send would call saveChanges() and replace our Message-ID; the composer has
        // already saved the message, so only do it for messages that were never saved.
        if (message.getHeader("Message-ID") == null) message.saveChanges()
        try {
            authorize()
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

    private suspend fun withTransport(
        policyChanges: StateFlow<*>? = null,
        authorize: () -> Unit = {},
        block: (TrackingTransport) -> Unit,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            coroutineScope {
                val sockets = AbortableSocketFactory()
                val refusal = AtomicReference<MailException?>(null)
                val observer = policyChanges?.let { changes ->
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        changes.collect {
                            try {
                                authorize()
                            } catch (e: Exception) {
                                refusal.compareAndSet(null, MailErrors.map(e))
                                // Do not call SMTPTransport.close here: it waits for the lock
                                // held by sendMessage. Close the actual TCP socket instead.
                                sockets.closeAll()
                            }
                        }
                    }
                }
                try {
                    authorize()
                    MailSessions.withEngineClassLoader {
                        val transport = TrackingTransport(MailSessions.smtpSession(settings, sockets),
                            settings.security == SecurityMode.SSL_TLS, authorize)
                        try {
                            transport.connect(settings.host, settings.port, settings.username, passwordProvider())
                            block(transport)
                        } finally {
                            try { transport.close() } catch (_: Exception) { /* Already closed or revoked. */ }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException || e is VirtualMachineError) throw e
                    val error = MailErrors.map(e)
                    // Once the terminator went out, a lost reply stays uncertain even when the
                    // socket was closed by a guardian. Never automatically resend that mail.
                    if (error.kind == MailErrorKind.SEND_UNCERTAIN) throw error
                    throw refusal.get() ?: error
                } finally {
                    observer?.cancel()
                    sockets.closeAll()
                }
            }
        }
    }

    /**
     * SMTPTransport (or, with [ssl], the equivalent of SMTPSSLTransport) that records when the
     * end of the message data starts going out: from then on the server may accept the message
     * at any moment, whether or not its reply arrives.
     */
    private class TrackingTransport(session: Session, ssl: Boolean, private val authorize: () -> Unit) :
        SMTPTransport(session, null, if (ssl) "smtps" else "smtp", ssl) {
        @Volatile
        var dataTerminated = false
            private set

        override fun mailFrom() { authorize(); super.mailFrom() }
        override fun rcptTo() { authorize(); super.rcptTo() }

        override fun data(): OutputStream {
            authorize()
            val output = super.data()
            authorize()
            return checked(output)
        }

        override fun bdat(): OutputStream {
            authorize()
            val output = super.bdat()
            authorize()
            return checked(output)
        }

        private fun checked(output: OutputStream): OutputStream = object : OutputStream() {
            override fun write(value: Int) { authorize(); output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { authorize(); output.write(bytes, offset, length) }
            override fun flush() { authorize(); output.flush() }
        }

        override fun finishData() {
            authorize()
            dataTerminated = true
            super.finishData()
        }

        override fun finishBdat() {
            authorize()
            dataTerminated = true
            super.finishBdat()
        }
    }
}
