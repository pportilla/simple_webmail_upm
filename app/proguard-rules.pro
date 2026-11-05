# Jakarta Mail/Activation discover these SPI entry points from resource descriptors.
# Keep their names and reflective constructors, while allowing method optimization.
# Everything else remains eligible for shrinking and obfuscation.
-keep,allowoptimization class jakarta.mail.Provider
-keep,allowoptimization interface jakarta.mail.util.StreamProvider
-keep,allowoptimization interface jakarta.activation.spi.MailcapRegistryProvider
-keep,allowoptimization interface jakarta.activation.spi.MimeTypeRegistryProvider

# MailSessions selects Angus' built-in verifier by its simple class name.
# Keep both nesting endpoints so R8 full mode preserves that name and metadata.
-keep,allowoptimization class org.eclipse.angus.mail.util.SocketFetcher
-keep,allowoptimization class org.eclipse.angus.mail.util.SocketFetcher$MailHostnameVerifier

-keep,allowoptimization class org.eclipse.angus.mail.** extends jakarta.mail.Provider {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.mail.util.MailStreamProvider {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.activation.MailcapRegistryProviderImpl {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.activation.MimeTypeRegistryProviderImpl {
    public <init>();
}

# META-INF/javamail*.providers name these Store/Transport classes literally.
# Include all registered protocols so registry discovery remains consistent.
-keep,allowoptimization class org.eclipse.angus.mail.imap.IMAPStore {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep,allowoptimization class org.eclipse.angus.mail.imap.IMAPSSLStore {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep,allowoptimization class org.eclipse.angus.mail.smtp.SMTPTransport {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep,allowoptimization class org.eclipse.angus.mail.smtp.SMTPSSLTransport {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep,allowoptimization class org.eclipse.angus.mail.pop3.POP3Store {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep,allowoptimization class org.eclipse.angus.mail.pop3.POP3SSLStore {
    public <init>(jakarta.mail.Session, jakarta.mail.URLName);
}

# MIME content handlers are instantiated by name from META-INF/mailcap.
-keep,allowoptimization class org.eclipse.angus.mail.handlers.text_plain {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.mail.handlers.text_html {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.mail.handlers.text_xml {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.mail.handlers.multipart_mixed {
    public <init>();
}
-keep,allowoptimization class org.eclipse.angus.mail.handlers.message_rfc822 {
    public <init>();
}

# Optional desktop/server integrations absent from the Android runtime.
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
# Angus' optional Java SASL integration is disabled by default and unsupported on Android.
# Normal IMAP/SMTP authentication uses its built-in mechanisms instead.
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.naming.**
-dontwarn org.graalvm.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.slf4j.**
-dontwarn com.google.re2j.**
