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
| `data.settings` | `SharedPreferences`-backed `SettingsRepository`, `AppLanguage` choices | `AppLanguage` yes |
| `data` | `DefaultAppContainer`, `AttachmentProvider` | no |
| `sync` | `SyncEngine`, `PushWatcher`, `ServerSearch` and `HeaderIndexer` (pure), `SyncJobService` + scheduler, `PushService` + `PushController`, `IndexJobService` + `IndexScheduler` (Android) | engine, watcher, search and indexer yes |
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
- Instant push (the default, `AppSettings.pushEnabled`): `PushService`, a foreground service of
  type `specialUse`, runs one `PushWatcher` per account. Each keeps an IMAP IDLE connection
  (`ImapIdler`) to the INBOX and syncs it through `SyncEngine` (debounced, never concurrently)
  when the server reports a change, so notifications take the usual path. Reconnection uses
  exponential backoff; servers without IDLE are polled every 2 minutes. An inexact
  `setAndAllowWhileIdle` alarm checks the connections in Doze. `PushController` starts the
  service when Android allows it (app visible, boot, app update, periodic job when exempt from
  battery optimisation). The periodic job stays on (15 minutes) as a safety net. Installs from
  before push get it only if they kept the default 15-minute interval.
- Search looks at two local sources at once: the cached messages and a **header index** of every
  folder, built in the background. It then searches the server progressively. See
  [Search and the header index](#search-and-the-header-index).

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

class ImapClient(settings: ServerSettings, passwordProvider: () -> String,
                 options: ImapOptions = ImapOptions.DEFAULT /* read timeout, abortable, keepFolderOpen */) : java.io.Closeable {
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
    // Search and index (see "Search and the header index")
    suspend fun examine(folder: String): FolderProbe                          // fresh EXAMINE: UIDVALIDITY, UIDNEXT, count
    suspend fun searchUids(folder: String, query: ServerQuery, range: SearchRange?, limit: Int): SearchOutcome
    suspend fun searchAllUids(folder: String, fromUid: Long = 1): UidList     // UID SEARCH UID n:*
    suspend fun searchUndeletedUids(folder: String): UidList                 // UID SEARCH UNDELETED
    suspend fun fetchIndexHeaders(folder: String, uids: List<Long>): HeaderBatch     // envelope, flags, size, Content-Type
    suspend fun fetchFlagsFor(folder: String, uids: List<Long>): Map<Long, RemoteFlags>  // absent uid = expunged
    suspend fun fetchByUids(folder: String, uids: List<Long>): FetchResult
    fun derive(options: ImapOptions): ImapClient                              // same login, own connection
    val isBusy: Boolean
    fun abort()                                                               // closes the sockets now; no LOGOUT
    override fun close()
}
class ImapIdler(settings: ServerSettings, passwordProvider: () -> String, folderName: String = "INBOX") {
    enum class Change { NEW_MESSAGES, MESSAGES_REMOVED, FLAGS_CHANGED }
    val idleSupported: Boolean?                                               // null until logged in once
    suspend fun run(onIdle: () -> Unit = {}, onChange: (Change) -> Unit)   // idles until cancelled; returns only without IDLE
    suspend fun refresh(): Boolean                                            // NOOP round trip; a silent connection is closed
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

## Search and the header index

Search has to find old mail that was never downloaded, on a server that has no full-text index
and can take a minute to scan one big folder. It combines three sources:

1. **Cached messages** (`messages` + `messages_fts`): subject, people and the downloaded bodies.
2. **Header index** (`header_index` + `header_index_fts`, `HeaderIndexDao`): subject, sender
   and recipients, date, size and flags of up to 50,000 newest messages of every
   indexable folder (2,000 for Trash and Spam, 100,000 per account), about 625 bytes per
   message. Drafts, virtual folders (`\All`, `\Flagged`) and shared or other users' folders are
   not indexed; a folder that becomes one of them loses its index rows. Folders not listed by
   the server since the upgrade to schema version 2 (`folders.attributes` is NULL) are not
   indexed either: index runs and server searches list the account's folders first. The index
   uses prefix terms of 3 or more characters; shorter ones ("CV", "SO") match whole words only,
   and a short last word that matches nothing is ignored while it may still be being typed.
   Index writes notify only the index tables, so the mailbox lists never re-query for them.
3. **Server search** (`ServerSearch`, `ServerQuery`, `SearchPlanning`): an AND over the words
   (at most 5), each word matching any of its accent variants. The **HEADERS** phase (subject,
   from, to, cc) starts by itself when the device found fewer than 5 results. It searches every
   folder in scope, in windows of 10,000 messages, and skips a folder whose index is complete
   and fresh (not for queries with a word shorter than 3 characters, which the server also
   finds inside longer words). The **TEXT** phase (message bodies, plus the headers when the
   query has several words, so one may be in the subject and another in the text) runs only on
   request (the keyboard's search key or "Search message text"). It searches 2,000-message windows, newest first, in tiers: the
   newest window of every folder (INBOX, Sent and Archive first), then the next four windows of
   each folder, then the rest. A window that times out is split in two. Results are emitted
   after every step. The snippets phase then downloads the previews of the newest five text
   matches. Trash and Spam are searched only on request. A server that cannot be reached (no
   connection, or two connections in a row that time out before logging in) ends the search
   with a connection error. A folder found renumbered is resynced apart from the search.

Results are `SearchHit`s keyed by `SearchKey(folderId, uid)`. A `Cached` hit has a `messages`
row. An `Indexed` hit exists only in the index; opening it downloads the message
(`SyncEngine.materializeIndexed`) as a **detached** row.

**Detached rows.** `messages.detached_at` is not null exactly when the row's uid is outside the
folder's sync window (`window_start_uid..window_end_uid`), or the folder was never synced. Such
rows come from opened search results and previews. They are hidden from the mailbox lists and
thread counts, but shown in their conversation. The value is the time of the last use: an open
conversation renews it every 10 minutes. A row becomes normal when the sync window grows over
it. Each sync keeps at most 200 detached rows per folder, evicting the least recently used ones,
but never one used within the last 30 minutes. Bulk index runs do the same for folders that are
never synced.

**Index state** (`header_index_state`, one row per folder):
- UIDVALIDITY, the UIDNEXT of the last check, and the server's message count;
- `complete`: the last comparison with the server found nothing missing within the cap;
- `capped`: older mail exists beyond the cap;
- `dirty`: our own move or upload added mail to the folder.

Only a fresh STATUS or EXAMINE may reset a folder. A batch fetched under another UIDVALIDITY is
dropped. Our own moves and deletes remove index rows at once. Every sync also feeds what it
fetched into the index.

**`HeaderIndexer` runs.** One run works at a time, and a run pauses while a server search uses
the same account.
- A check per folder: STATUS; the new uids since the stored UIDNEXT; expunges detected from the
  counts.
- A full comparison (`UID SEARCH UNDELETED`) when the folder lost messages or was last compared
  a week ago, or, in runs that fill in older mail (bulk, manual), when it is incomplete.
- Fetching in chunks, always from the folder whose next chunk is newest.

| Run | Started by | Connection | Budget |
|---|---|---|---|
| bulk | job 1101, unmetered network, battery and storage not low | its own | 4 minutes (8 while charging), 60,000 headers |
| manual | "Index now" in the search screen | its own | 10 minutes, 100,000 headers; stops on a metered network unless the user agreed to use mobile data |
| light | job 1102, every 2 hours, any network | main | 1 minute, 500 headers, new mail only, comparisons of folders up to 2,000 messages |
| dirty | 30 seconds after our own changes add mail to an indexed folder | main | 20 seconds, 200 headers |

**Scheduling (`IndexScheduler`, `IndexJobService`).** The bulk job is planned from the index
status and the outcome of the last run (`IndexJobPlan`):
- While the index is being built, it needs no charger. It runs at once when first planned, or
  when accounts or folders were added or removed, then every 15 minutes. The pause doubles, up
  to 6 hours, after each run that left work but got nothing done. A folder the server refuses
  does not count as work left.
- Once the index is complete, it runs every 6 hours while charging.

A job planned by an earlier process is kept, because the periodic mail check starts the process
every 15 minutes. Runs stop below 300 MB of free storage. Both jobs are cancelled while there is
no account.

**Connections per account.** In steady state there are 4:
- main (sync, pending changes, bodies, materialising, light and dirty index runs);
- IDLE (push);
- one pooled, abortable search connection, closed after 2 minutes unused;
- an abortable crawl connection, only during bulk and manual runs.

Read timeouts are never retried. An aborted connection is closed without LOGOUT, so leaving the
search screen (or a stopped index job) ends its network traffic at once.

**JobScheduler ids.**

| Id | Job |
|---|---|
| 1001 | periodic mail check |
| 1002 | outbox |
| 1003 | pending operations |
| 1101 | bulk index |
| 1102 | light index |

**Database versions.** `MailSchema.CREATE_V1` is version 1 exactly as shipped, and it is never
edited. Later versions are steps in `MailSchema.MIGRATIONS`, and a fresh install runs version 1
plus every step, so new and upgraded databases are identical. `MigrationTest` checks that every
version has a step, and `Sqlite318CompatTest` runs the schema and queries on SQLite 3.18 (Android
8): no UPSERT, RETURNING, window functions or FTS5. A step that fails rolls back, but the app then
fails on every start until a fixed build ships. A missing step, or a downgrade (`onDowngrade`),
recreates the database and loses the local data.

**`versionCode`** is `GITHUB_RUN_NUMBER + 100` on CI and 1 for a local build. Android therefore
refuses to install an older build over a newer one (INSTALL_FAILED_VERSION_DOWNGRADE) instead of
letting it wipe the database. A local build cannot be installed over a CI build. Forcing it
(`adb install -d`) wipes the database when the forced build's schema version is older.
Uninstalling always deletes the accounts, drafts, outbox and changes not yet sent.

## UI conventions

- Every screen is a `@Composable` taking an `AppNavigator` (see `ui/navigation/AppNavigator.kt`)
  and gets its ViewModel with `viewModel { XViewModel(appContainer(), …) }`.
- ViewModels expose `StateFlow<XUiState>`. Screens collect them with `collectAsStateWithLifecycle()`.
- Snackbars go through `LocalAppSnackbar.current.show(…)`. Every `Scaffold` uses
  `snackbarHost = { AppSnackbarHost() }`.
- Language (`AppSettings.language`, chosen in Settings): Android 13+ keeps it as the per-app
  language (`LocaleManager`), which the system applies to the whole process; `UpmMailApp` mirrors
  it into the settings (on configuration changes and whenever `MainActivity` starts) and, once,
  hands the system a language chosen before the device was updated from Android 12 or older. On Android 12 and older, `UpmMailApp` applies it itself: its
  `getResources()` (used by notifications and other texts made outside the screens, so services
  take their texts from the application context), `MainActivity.attachBaseContext` and the
  default `Locale` (date formats); a change recreates the activity. `DefaultAppContainer.locale` signals changes (channel names, push notification).
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
| `search?accountId=&folderId=` | `ui.search.SearchScreen` | Instant search of cached mail and the header index, filters, progressive server search, "Index now" |
| `settings` | `ui.settings.SettingsScreen` | Appearance, behaviour, notifications, accounts, about |
| `settings/account/{id}` | `ui.settings.AccountSettingsScreen` | Name, signature, server settings, password, remove |
