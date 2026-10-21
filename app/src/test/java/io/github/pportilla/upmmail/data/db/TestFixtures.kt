package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** A controllable clock for tests. */
class TestClock(start: Long = 1_750_000_000_000L) {
    private val time = AtomicLong(start)

    fun now(): Long = time.get()

    fun advance(millis: Long) {
        time.addAndGet(millis)
    }
}

/** Settings kept in memory. */
class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> get() = state

    override fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

fun newStore(clock: () -> Long = System::currentTimeMillis): MailStore =
    MailStore(JdbcSqlDatabase.inMemory(), TableObserver(), clock)

fun MailStore.addTestAccount(
    email: String = "ana@upm.es",
    imap: ServerSettings = ServerSettings("imap.example.org", 993, SecurityMode.SSL_TLS, "ana"),
    smtp: ServerSettings = ServerSettings("smtp.example.org", 587, SecurityMode.STARTTLS, "ana@upm.es"),
    signature: String = "",
): Account {
    val id = accounts.insert(
        Account(
            id = 0,
            email = email,
            displayName = "Ana Pérez",
            imap = imap,
            smtp = smtp,
            signature = signature,
            color = 0xFF003F87.toInt(),
            notificationsEnabled = true,
            sortOrder = 0,
        ),
    )
    return accounts.get(id)!!
}

fun MailStore.addTestFolder(accountId: Long, fullName: String, role: FolderRole = FolderRole.OTHER): Folder {
    val id = folders.insert(
        accountId,
        FolderListEntry(fullName, fullName.substringAfterLast('/'), '/', emptySet(), true, 0, role),
    )
    return folders.get(id)!!
}

fun remoteMessage(
    uid: Long,
    subject: String = "Subject $uid",
    from: EmailAddress? = EmailAddress("bea@alumnos.upm.es", "Bea Gómez"),
    to: List<EmailAddress> = listOf(EmailAddress("ana@upm.es", "Ana Pérez")),
    cc: List<EmailAddress> = emptyList(),
    date: Long = 1_700_000_000_000L + uid * 60_000L,
    messageId: String? = "<m$uid@example.org>",
    inReplyTo: String? = null,
    references: List<String> = emptyList(),
    seen: Boolean = false,
    flagged: Boolean = false,
    size: Long = 1_000,
    hasAttachments: Boolean = false,
): RemoteMessage = RemoteMessage(
    uid = uid,
    messageId = messageId,
    inReplyTo = inReplyTo,
    references = references,
    subject = subject,
    from = from,
    to = to,
    cc = cc,
    bcc = emptyList(),
    replyTo = emptyList(),
    sentDate = date,
    internalDate = date,
    size = size,
    flags = RemoteFlags(seen = seen, flagged = flagged),
    hasAttachments = hasAttachments,
    listUnsubscribe = null,
)
