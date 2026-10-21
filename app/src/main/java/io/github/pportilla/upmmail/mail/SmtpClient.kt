package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.internet.MimeMessage

/** SMTP submission for one account. Functions run on Dispatchers.IO and throw MailException. */
class SmtpClient(
    private val settings: ServerSettings,
    private val passwordProvider: () -> String,
) {
    /** Connects and authenticates, then disconnects. */
    suspend fun verify(): Unit = TODO()

    suspend fun send(message: MimeMessage): Unit = TODO()
}
