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
- **Search** that finds old mail too:
  - **Instant results** from the mail on the device and from an index of the subjects, senders
    and recipients of all your folders. The app builds the index in the background on Wi-Fi: about 0.9 KB per
    message, without message text or attachments. *Index now* in the search screen builds it at
    once.
  - **Server search** when the device finds little. Subjects, senders and recipients of every
    folder are searched on the UPM server automatically. *Search message text* (or the
    keyboard's search key) also looks inside the messages, newest first, with progress per
    folder and a *Stop* button. Results appear as they are found.
  - Trash and Spam are searched only when you ask.
  - Filters: unread, starred, attachments, this folder. Recent searches are remembered.
- **Notifications** for new mail, with *Mark as read* and *Archive* actions. New mail arrives
  **instantly (push)**: the app keeps a connection to the server open with IMAP IDLE, shown by a
  small silent notification that you can hide. You can also choose checks every 15/30/60 minutes,
  or only when you open the app.
- `mailto:` links and **share-to-email** from other apps.
- Light/dark theme in UPM blue, optional Material You colours, English and Spanish UI.
- **Language**: the app follows your phone's language, or choose English or Español in
  *Settings > Appearance > Language* (on Android 13+ also in the system's app language settings).

## Install

Download the latest build on your phone and open it:
**[upm-mail-debug.apk](https://github.com/pportilla/simple_webmail_upm/releases/download/latest-debug/upm-mail-debug.apk)**
(see also the [latest-debug release page](https://github.com/pportilla/simple_webmail_upm/releases/tag/latest-debug)).
Android will ask you to allow your browser to install unknown apps. Requires Android 8.0 or newer.
Every push rebuilds this APK, and newer builds install over older ones without losing your
accounts. Android refuses to install an older build over a newer one. Uninstalling the app to get
around that deletes the accounts, drafts, outbox and changes not yet sent from the phone.

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
the APK to the phone and open it (allow "install unknown apps"). A local build has version code 1,
and CI builds count up from the workflow run number. So a local build cannot be installed over a
CI build. Forcing it (`adb install -d`) deletes the local data when the local build's database
version is older.

Every push also builds the debug APK on GitHub Actions (`.github/workflows/android.yml`). The APK
can be downloaded from the run's *Artifacts* section.

The repository contains a public debug keystore (`app/debug.keystore`) so that every build,
local or CI, has the same signature and installs as an update over the previous one. It is not a
secret, and it must not be used to publish the app on a store.

## Project structure

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). In short: Jetpack Compose UI → ViewModels →
repositories → a SQLite cache kept in sync with the server by a sync engine built on Jakarta Mail.
