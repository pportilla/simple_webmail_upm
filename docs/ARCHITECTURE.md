# UPM Mail: architecture

UPM Mail is a native Android e-mail client for Universidad Politécnica de Madrid accounts. It talks
IMAP and SMTP directly to the UPM servers. It does not scrape the web gateway.

| Account type | Address | IMAP (incoming) | SMTP (outgoing) |
|---|---|---|---|
| Staff (PDI/PAS) | `@upm.es` | `correo.upm.es:993` SSL/TLS | `smtp.upm.es:587` STARTTLS |
| Students | `@alumnos.upm.es` | `correo.alumnos.upm.es:993` SSL/TLS | `smtp.upm.es:587` STARTTLS |
| External | `@externos.upm.es` | `correo.externos.upm.es:993` SSL/TLS | `smtp.upm.es:587` STARTTLS |

Presets live in `mail/UpmPresets.kt`. The login name is auto-detected: the part before the `@`,
then the full address.

## Tech stack

- Kotlin, Jetpack Compose with Material 3, Navigation Compose, and ViewModels with manual
  dependency injection (`AppContainer`).
- Jakarta Mail 2.1 with Eclipse Angus Mail for IMAP, SMTP and MIME.
- jsoup for HTML sanitising and HTML-to-text conversion.
- Framework APIs only for persistence and background work: `SQLiteOpenHelper` with FTS4,
  `SharedPreferences`, `JobScheduler`, `Notification.Builder`, the Android Keystore, and our own
  `ContentProvider` for attachments. There is no Room, WorkManager, DataStore or AndroidX Core.
  The data layer stays small, and most of it is testable on the plain JVM.
- minSdk 26, target/compile SDK 36. English and Spanish UI.

## Packages (`io.github.pportilla.upmmail`)

| Package | Contents | Android-free? |
|---|---|---|
| `model` | Immutable domain models (`Models.kt`), `MailException` | yes |
| `mail` | IMAP/SMTP engine, MIME parsing/building, HTML tools, quoting, presets | **yes** (JVM-tested with GreenMail) |
| `data.db` | `SqlDatabase` abstraction, schema, DAOs, table-change observer | DAOs yes; only `AndroidSqlDatabase` touches Android |
| `data.repository` | `Repositories.kt` (interfaces = UI contract) and their implementations | interfaces yes |
| `data.security` | Keystore-backed password store | no |
| `data.settings` | `SharedPreferences`-backed `SettingsRepository` | no |
| `data` | `DefaultAppContainer`, `AttachmentProvider` | no |
| `sync` | `SyncEngine` (pure), `SyncJobService` + scheduler (Android) | engine yes |
| `notifications` | New-mail notifications, notification actions | no |
| `ui.*` | Compose screens, one package per screen, plus `ui.components`, `ui.theme`, `ui.navigation` | no |

## Data flow

```
 Compose screen ──► ViewModel ──► Repository interface (data.repository.Repositories.kt)
       ▲                               │
       │ Flow<…> (DB observer)         ▼
       └──────────── SQLite cache ◄── SyncEngine ◄──► ImapClient / SmtpClient (mail)
```

- The UI never talks to the network directly. It observes the SQLite cache via `Flow`s and asks
  repositories for actions.
- User actions (read, star, move, delete) are applied to the cache immediately (optimistic) and
  recorded as **pending operations**. `SyncEngine` replays them against the server. Moves and
  deletes have a short `not_before` delay, so *Undo* can cancel them before they reach the server.
- Sending goes through an **outbox** table. The message is sent after the undo window
  (`AppSettings.undoSendSeconds`), either by the in-app scope or, if the app dies, by a
  `JobScheduler` job with a network constraint. After a successful SMTP send, the message is
  appended to the Sent folder.
- Background checks run as a periodic `JobScheduler` job (interval from settings). While the app
  is visible, the INBOX is polled every 2 minutes.

## Mail engine API (`mail` package)

All `ImapClient`/`SmtpClient` functions are `suspend`. They run on `Dispatchers.IO`, are serialised
per client, reconnect once on a dropped connection, and throw `MailException` (mapped by
`MailErrors.map`).

```kotlin
data class RemoteFolder(val fullName: String, val name: String, val separator: Char,
    val attributes: Set<String> /* lower-case: "\\sent", "\\noselect", … */, val selectable: Boolean, val depth: Int)
data class RemoteFolderStatus(val uidValidity: Long, val uidNext: Long, val messageCount: Int, val unreadCount: Int)
data class RemoteFlags(val seen: Boolean, val flagged: Boolean, val answered: Boolean,
    val forwarded: Boolean /* $Forwarded */, val draft: Boolean, val deleted: Boolean)
data class RemoteMessage(val uid: Long, val messageId: String?, val inReplyTo: String?, val references: List<String>,
    val subject: String, val from: EmailAddress?, val to: List<EmailAddress>, val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>, val replyTo: List<EmailAddress>, val sentDate: Long?, val internalDate: Long?,
    val size: Long, val flags: RemoteFlags, val hasAttachments: Boolean, val listUnsubscribe: String?)
data class FetchResult(val uidValidity: Long, val messages: List<RemoteMessage> /* ascending uid */, val truncated: Boolean)
data class ParsedAttachment(val partPath: String, val fileName: String, val mimeType: String, val size: Long,
    val contentId: String?, val isInline: Boolean)
data class ParsedBody(val html: String?, val text: String?, val attachments: List<ParsedAttachment>)
enum class MessageFlag { SEEN, FLAGGED, ANSWERED, FORWARDED, DRAFT, DELETED }
interface AttachmentSource { val fileName: String; val mimeType: String; fun openStream(): java.io.InputStream }
data class ComposedMessage(val mime: jakarta.mail.internet.MimeMessage, val messageId: String)

class ImapClient(settings: ServerSettings, passwordProvider: () -> String) : java.io.Closeable {
    suspend fun connect()                                                     // login check
    suspend fun listFolders(): List<RemoteFolder>
    suspend fun status(folder: String): RemoteFolderStatus
    suspend fun fetchNewest(folder: String, limit: Int): FetchResult
    suspend fun fetchNewer(folder: String, afterUid: Long, limit: Int): FetchResult   // uid > afterUid; newest `limit`, truncated if more
    suspend fun fetchOlder(folder: String, beforeUid: Long, limit: Int): FetchResult  // newest `limit` with uid < beforeUid; truncated if more
    suspend fun fetchFlags(folder: String, fromUid: Long, toUid: Long): Map<Long, RemoteFlags> // absent uid = expunged
    suspend fun fetchMessages(folder: String, uids: List<Long>): List<RemoteMessage>
    suspend fun fetchBody(folder: String, uid: Long): ParsedBody               // never sets \Seen
    suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: java.io.File)
    suspend fun setFlag(folder: String, uids: List<Long>, flag: MessageFlag, value: Boolean)
    suspend fun move(folder: String, uids: List<Long>, destinationFolder: String)
    suspend fun deletePermanently(folder: String, uids: List<Long>)
    suspend fun deleteAll(folder: String)
    suspend fun append(folder: String, message: MimeMessage, seen: Boolean, draft: Boolean): Long?
    suspend fun search(folder: String, query: String, limit: Int): List<Long>   // uids, newest first
    suspend fun findUidByMessageId(folder: String, messageId: String): Long?
    override fun close()
}
class SmtpClient(settings: ServerSettings, passwordProvider: () -> String) {
    suspend fun verify()
    suspend fun send(message: MimeMessage)
}
object MessageComposer {
    fun build(from: EmailAddress, draft: Draft, attachments: List<AttachmentSource>,
              date: Long = System.currentTimeMillis(), userAgent: String = "UPM Mail for Android"): ComposedMessage
}
object Quoting {
    data class ReplyRecipients(val to: List<EmailAddress>, val cc: List<EmailAddress>)
    fun replySubject(subject: String): String
    fun forwardSubject(subject: String): String
    fun quote(text: String): String
    fun replyRecipients(from: EmailAddress?, to: List<EmailAddress>, cc: List<EmailAddress>,
        replyTo: List<EmailAddress>, selfAddresses: Set<String>, replyAll: Boolean): ReplyRecipients
    fun references(originalMessageId: String?, originalReferences: List<String>): List<String>
}
object HtmlSanitizer {
    fun sanitize(html: String): String
    fun plainTextToHtml(text: String): String
    fun hasRemoteContent(html: String): Boolean
    fun inlineImages(html: String, inlineImages: Map<String, String> /* content id -> data: URI */): String
}
object HtmlText {
    fun toPlainText(html: String): String
    fun snippet(text: String?, html: String?, maxLength: Int = 160): String
}
object FolderRoleDetector { fun detect(fullName: String, attributes: Set<String>): FolderRole }
object AddressParser {
    data class ParseResult(val addresses: List<EmailAddress>, val invalid: List<String>)
    fun parse(input: String): ParseResult
    fun isValidAddress(address: String): Boolean
    fun format(addresses: List<EmailAddress>): String         // RFC 5322 header form, round-trips with parse
}
object Threading { fun threadId(messageId: String?, inReplyTo: String?, references: List<String>, fallback: String): String }
object MailErrors { fun map(t: Throwable): MailException }
```

## UI conventions

- Every screen is a `@Composable` taking an `AppNavigator` (see `ui/navigation/AppNavigator.kt`)
  and gets its ViewModel with `viewModel { XViewModel(appContainer(), …) }`.
- ViewModels expose `StateFlow<XUiState>`. Screens collect them with `collectAsStateWithLifecycle()`.
- Snackbars go through `LocalAppSnackbar.current.show(…)`. Every `Scaffold` uses
  `snackbarHost = { AppSnackbarHost() }`.
- Strings: shared ones are in `values/strings.xml`. Each feature has its own
  `values/strings_<feature>.xml` and `values-es/strings_<feature>.xml`, with keys prefixed by the
  feature (`mailbox_`, `message_`, `compose_`, `setup_`, `settings_`, `notif_`…).
- Shared components are in `ui/components`: `SenderAvatar`, `formatListDate`/`formatFullDate`/`formatFileSize`,
  `folderIcon`/`folderDisplayName`, `FolderPickerDialog`, `Context.errorMessage(t)`, `AppSnackbarHost`.

## Screens and routes

| Route | Screen | Notes |
|---|---|---|
| `setup?add={bool}` | `ui.setup.SetupScreen` | Sign-in with UPM presets, advanced server settings |
| `mailbox` | `ui.mailbox.MailboxScreen` | Drawer (accounts, folders, outbox), list, swipe, multi-select, pull-to-refresh, paging |
| `message/{messageId}` | `ui.message.MessageScreen` | Conversation view, HTML (sanitised WebView), attachments, actions |
| `compose?...` | `ui.compose.ComposeScreen` | Recipient chips with autocomplete, Cc/Bcc, attachments, drafts, undo-send |
| `search?accountId=&folderId=` | `ui.search.SearchScreen` | Instant local search, filters, server search |
| `settings` | `ui.settings.SettingsScreen` | Appearance, behaviour, notifications, accounts, about |
| `settings/account/{id}` | `ui.settings.AccountSettingsScreen` | Name, signature, server settings, password, remove |
