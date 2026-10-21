# UPM Mail

An unofficial native Android e-mail app for **Universidad Politécnica de Madrid** accounts
(`@upm.es`, `@alumnos.upm.es`, `@externos.upm.es`). It connects directly to the UPM mail servers
over IMAP and SMTP with TLS, so the mailbox is the same one you see in the UPM webmail.

> Not affiliated with or endorsed by UPM. Your password is stored encrypted on the device
> (Android Keystore) and is sent only to the UPM mail servers.

## Features

- **Sign in with just your UPM address and password.** Servers, ports and security are
  pre-configured for staff, students and external accounts. The login name format is detected
  automatically. Advanced server settings are available if needed.
- **Multiple accounts**, with an account switcher in the navigation drawer.
- **All your folders**: Inbox, Sent, Drafts, Archive, Trash, Spam and your own folders, with
  unread counts. English and Spanish folder names are recognised.
- **Modern message list**: avatars, previews, stars, attachment and thread indicators,
  pull-to-refresh, infinite scrolling into older mail, and configurable swipe actions (archive,
  delete, read/unread, star).
- **Multi-select**: archive, delete, move, mark read/unread or star many messages at once.
- **Undo** for archive, delete, move and **send**.
- **Conversation view**: replies are grouped into threads, with expandable messages.
- **Safe HTML rendering**: scripts and active content are stripped, and remote images stay blocked
  until you allow them (per message or per sender). Inline images and dark mode are supported, and
  messages can be printed.
- **Attachments**: open, share or save them, and attach files from any app.
- **Compose** with recipient chips and **address autocompletion** from your mail history and
  (optionally) your phone contacts. Also: Cc/Bcc, signature, reply, reply-all and forward with
  quoting, drafts that sync to the server Drafts folder, and an outbox that sends when you are back
  online.
- **Search**: instant local search with filters (unread, starred, attachments, folder), plus
  server-side search for older mail. Recent searches are remembered.
- **Notifications** for new mail, with *Mark as read* and *Archive* actions. Background checks run
  every 15/30/60 minutes or manually only.
- `mailto:` links and **share-to-email** from other apps.
- Light/dark theme in UPM blue, optional Material You colours, English and Spanish UI.

## UPM server settings

| Account | Incoming (IMAP) | Outgoing (SMTP) |
|---|---|---|
| Staff `@upm.es` | `correo.upm.es`, port 993, SSL/TLS | `smtp.upm.es`, port 587, STARTTLS |
| Students `@alumnos.upm.es` | `correo.alumnos.upm.es`, port 993, SSL/TLS | `smtp.upm.es`, port 587, STARTTLS |
| External `@externos.upm.es` | `correo.externos.upm.es`, port 993, SSL/TLS | `smtp.upm.es`, port 587, STARTTLS |

The username is your UPM login. The app tries it without `@domain` first, then the full address.

## Building

Requirements: Android Studio (Narwhal or newer) or the command line with JDK 17 and the Android
SDK (platform 36).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM tests (mail engine against an in-memory GreenMail server, database, sync)
```

To install on a phone with USB debugging enabled, run `./gradlew installDebug`. You can also copy
the APK to the phone and open it (allow "install unknown apps").

Every push also builds the debug APK on GitHub Actions (`.github/workflows/android.yml`). The APK
can be downloaded from the run's *Artifacts* section.

The repository contains a public debug keystore (`app/debug.keystore`) so that every build,
local or CI, has the same signature and installs as an update over the previous one. It is not a
secret, and it must not be used to publish the app on a store.

## Project structure

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). In short: Jetpack Compose UI → ViewModels →
repositories → a SQLite cache kept in sync with the server by a sync engine built on Jakarta Mail.
