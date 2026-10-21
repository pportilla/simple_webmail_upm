package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.activation.CommandMap
import jakarta.activation.DataContentHandler
import jakarta.activation.DataSource
import jakarta.activation.MailcapCommandMap
import jakarta.mail.Session
import org.eclipse.angus.mail.handlers.message_rfc822
import org.eclipse.angus.mail.handlers.multipart_mixed
import org.eclipse.angus.mail.handlers.text_html
import org.eclipse.angus.mail.handlers.text_plain
import org.eclipse.angus.mail.handlers.text_xml
import java.util.Locale
import java.util.Properties
import javax.net.SocketFactory

/**
 * Jakarta Mail plumbing shared by the IMAP/SMTP clients, the MIME parser and the composer.
 *
 * Jakarta Mail and Jakarta Activation locate their providers (stream encoders, mailcap registry,
 * content handlers) through the thread context class loader and `META-INF` resources. On Android
 * the context class loader of worker threads is often not the APK's loader, which shows up as
 * "No provider of jakarta.mail.util.StreamProvider was found" or "no object DCH for MIME type".
 * Everything here makes the engine independent of that: the system properties are set before
 * any Jakarta class reads them, the content handlers are registered programmatically and
 * [withEngineClassLoader] pins the context class loader around every Jakarta Mail call.
 */
internal object MailSessions {
    const val CONNECTION_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 60_000
    private const val IMAP_FETCH_SIZE = 262_144

    private val engineClassLoader: ClassLoader = MailSessions::class.java.classLoader
        ?: ClassLoader.getSystemClassLoader()

    init {
        // Several of these are read once into static finals by Jakarta Mail classes, so they must
        // be in place before the first MimeUtility / MimeBodyPart / IMAPBodyPart is loaded.
        setSystemPropertyIfAbsent("mail.mime.decodetext.strict", "false")
        setSystemPropertyIfAbsent("mail.mime.base64.ignoreerrors", "true")
        setSystemPropertyIfAbsent("mail.mime.decodefilename", "true")
        setSystemPropertyIfAbsent("mail.mime.encodefilename", "true")
        setSystemPropertyIfAbsent("mail.mime.charset", "UTF-8")
        setSystemPropertyIfAbsent("mail.mime.multipart.ignoremissingboundaryparameter", "true")
        setSystemPropertyIfAbsent("mail.mime.parameters.strict", "false")
        setSystemPropertyIfAbsent("mail.mime.applefilenames", "true")
        setSystemPropertyIfAbsent("mail.mime.windowsfilenames", "true")
        setSystemPropertyIfAbsent("mail.mime.allowencodedmessages", "true")
        setSystemPropertyIfAbsent("mail.mime.ignoreunknownencoding", "true")
        setSystemPropertyIfAbsent("mail.mime.uudecode.ignoreerrors", "true")
        setSystemPropertyIfAbsent("mail.mime.uudecode.ignoremissingbeginend", "true")
        withEngineClassLoader { CommandMap.setDefaultCommandMap(EngineCommandMap()) }
    }

    /** Forces the one-time static initialisation; cheap after the first call. */
    fun ensureInitialized() = Unit

    /** Runs [block] with the engine's class loader as the thread context class loader. */
    inline fun <T> withEngineClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = engineClassLoaderForInline()
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Exposed for the inline function above; not meant to be called directly. */
    @PublishedApi
    internal fun engineClassLoaderForInline(): ClassLoader = engineClassLoader

    private val sharedMimeSession: Session by lazy { withEngineClassLoader { Session.getInstance(baseProperties()) } }

    /** A session with only the MIME settings, for building and parsing messages offline. */
    fun mimeSession(): Session = sharedMimeSession

    /**
     * [socketFactory], if given, creates the plain TCP sockets (TLS is still layered on top as
     * usual). It is never bypassed, so whoever owns it can close every socket of the session.
     */
    fun imapSession(settings: ServerSettings, socketFactory: SocketFactory? = null): Session {
        val props = baseProperties()
        val protocol = imapProtocol(settings.security)
        val p = "mail.$protocol"
        props["mail.store.protocol"] = protocol
        props["$p.host"] = settings.host
        props["$p.port"] = settings.port.toString()
        props["$p.connectiontimeout"] = CONNECTION_TIMEOUT_MS.toString()
        props["$p.timeout"] = READ_TIMEOUT_MS.toString()
        if (socketFactory != null) {
            props["$p.socketFactory"] = socketFactory
            props["$p.socketFactory.fallback"] = "false"
        }
        // Never set \Seen implicitly when reading bodies or parts.
        props["$p.peek"] = "true"
        props["$p.fetchsize"] = IMAP_FETCH_SIZE.toString()
        props["$p.partialfetch"] = "true"
        // Some servers announce wrong part sizes in BODYSTRUCTURE; trusting them truncates parts.
        props["$p.ignorebodystructuresize"] = "true"
        // A search the server rejects must fail instead of silently downloading every message.
        props["$p.throwsearchexception"] = "true"
        props["$p.ssl.checkserveridentity"] = "true"
        // Android ignores the "LDAPS" endpoint check that checkserveridentity relies on, so the
        // certificate must also be matched against the host name explicitly.
        props["$p.ssl.hostnameverifier.class"] = HOSTNAME_VERIFIER
        applySecurity(props, protocol, settings.security)
        return withEngineClassLoader { Session.getInstance(props) }
    }

    fun smtpSession(settings: ServerSettings): Session {
        val props = baseProperties()
        val protocol = smtpProtocol(settings.security)
        val p = "mail.$protocol"
        props["mail.transport.protocol"] = protocol
        props["$p.host"] = settings.host
        props["$p.port"] = settings.port.toString()
        props["$p.connectiontimeout"] = CONNECTION_TIMEOUT_MS.toString()
        props["$p.timeout"] = READ_TIMEOUT_MS.toString()
        props["$p.auth"] = "true"
        // Do not wait for the server's answer to QUIT once the message has been accepted.
        props["$p.quitwait"] = "false"
        props["$p.ssl.checkserveridentity"] = "true"
        // Android ignores the "LDAPS" endpoint check that checkserveridentity relies on, so the
        // certificate must also be matched against the host name explicitly.
        props["$p.ssl.hostnameverifier.class"] = HOSTNAME_VERIFIER
        applySecurity(props, protocol, settings.security)
        return withEngineClassLoader { Session.getInstance(props) }
    }

    /** Angus Mail's built-in SAN/CN matcher (pure Java, so it works on Android and the JVM). */
    private const val HOSTNAME_VERIFIER = "MailHostnameVerifier"

    fun imapProtocol(security: SecurityMode): String = if (security == SecurityMode.SSL_TLS) "imaps" else "imap"

    fun smtpProtocol(security: SecurityMode): String = if (security == SecurityMode.SSL_TLS) "smtps" else "smtp"

    private fun applySecurity(props: Properties, protocol: String, security: SecurityMode) {
        val p = "mail.$protocol"
        when (security) {
            SecurityMode.SSL_TLS -> {
                props["$p.ssl.enable"] = "true"
            }
            SecurityMode.STARTTLS -> {
                props["$p.starttls.enable"] = "true"
                props["$p.starttls.required"] = "true"
            }
            SecurityMode.NONE -> {
                props["$p.starttls.enable"] = "false"
            }
        }
    }

    private fun baseProperties(): Properties = Properties().apply {
        this["mail.mime.address.strict"] = "false"
        this["mail.mime.charset"] = "UTF-8"
    }

    private fun setSystemPropertyIfAbsent(key: String, value: String) {
        try {
            if (System.getProperty(key) == null) System.setProperty(key, value)
        } catch (_: SecurityException) {
            // Not allowed to change it; Jakarta Mail keeps its default.
        }
    }

    /**
     * Content handlers for the MIME types Jakarta Mail itself needs, created directly instead of
     * through the mailcap registry and reflection. Anything else is delegated to the standard
     * mailcap lookup (which may legitimately return null).
     */
    private class EngineCommandMap : MailcapCommandMap() {
        init {
            // Also register the standard mailcap lines, so that code asking the map for the
            // command list sees them. Failing here is harmless: createDataContentHandler below
            // does not depend on the registry.
            try {
                MAILCAP_LINES.forEach { addMailcap(it) }
            } catch (_: Throwable) {
                // The MailcapRegistryProvider could not be loaded (the Android case).
            }
        }

        override fun createDataContentHandler(mimeType: String?): DataContentHandler? =
            builtIn(mimeType) ?: safeSuper { super.createDataContentHandler(mimeType) }

        override fun createDataContentHandler(mimeType: String?, ds: DataSource?): DataContentHandler? =
            builtIn(mimeType) ?: safeSuper { super.createDataContentHandler(mimeType, ds) }

        private fun builtIn(mimeType: String?): DataContentHandler? {
            val base = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when {
                base == "text/plain" -> text_plain()
                base == "text/html" -> text_html()
                base == "text/xml" -> text_xml()
                base == "message/rfc822" -> message_rfc822()
                base.startsWith("multipart/") -> multipart_mixed()
                else -> null
            }
        }

        private inline fun safeSuper(block: () -> DataContentHandler?): DataContentHandler? =
            try {
                block()
            } catch (_: Throwable) {
                null
            }

        companion object {
            private val MAILCAP_LINES = listOf(
                "text/plain;;\t\tx-java-content-handler=org.eclipse.angus.mail.handlers.text_plain",
                "text/html;;\t\tx-java-content-handler=org.eclipse.angus.mail.handlers.text_html",
                "text/xml;;\t\tx-java-content-handler=org.eclipse.angus.mail.handlers.text_xml",
                "multipart/*;;\t\tx-java-content-handler=org.eclipse.angus.mail.handlers.multipart_mixed; " +
                    "x-java-fallback-entry=true",
                "message/rfc822;;\tx-java-content-handler=org.eclipse.angus.mail.handlers.message_rfc822",
            )
        }
    }
}
