# Google Play release checklist

Updated **25 October 2026 (Europe/Madrid)** for `io.github.pportilla.upmmail`.
**Version 6 (1.0.3) is approved/live in closed Alpha and available internally.** Version **8
(1.1.0)** replaces the unpublished version-7 candidate after runtime testing found an attachment-save
failure on return from the system file picker during a transient access check. Version 8 passed
**704 tests**, lint with zero errors, signed-artifact validation, focused UI/attachment checks
and 13 exact-release TLS checks. Six closed-release/listing/audience changes were submitted at
**14:33:01 Europe/Madrid on 25 October 2026**; Console reload and submission activity 4 confirm **In review** for closed Alpha, store listing
and app content, with no running-check banner. Google approval is still pending. The
internal release remains an unpublished draft; public evidence and both final listings are verified. Version-7 tests and metrics are
preserved as superseded evidence in [release status](RELEASE_STATUS.md), not v8 validation.

**Hold publication of this update on every track, including internal testing, until at least
28 October 2026 at 00:00 UTC (01:00 Europe/Madrid).** Managed publishing is on, but does not
automatically protect internal releases. The English/Spanish significant-change notice remains
submitted for that effective date. No automatic publication is scheduled.

## Identity and current scope

| Item | Confirmed scope or remaining evidence |
| --- | --- |
| App / publisher | Independent, unofficial **UPM Mail**, developer **zango**, support **p.portilla89@gmail.com**, [repository/support website](https://github.com/pportilla/simple_webmail_upm). Keep the non-affiliation statement visible. |
| Account / distribution | Existing verified personal Play developer account; free, Communication category, no ads or billing; current scope 178 countries/regions. No new developer account is needed. |
| Audience | Only **13–15, 16–17 and 18+** are saved for the proposed update, pending review. This supersedes the earlier all-age intent. Normal teens receive ordinary mail; known under-13 profiles remain unsupported. Target audience and IARC ratings are separate. |
| Version | Validated version code **8**, name **1.1.0**; uploaded and submitted for closed review, with focused runtime validation passed; approval pending. Preserve the exact validated/uploaded AAB. For any later upload, use a code above every code already used in Console. |
| Signing | Preserve the existing dedicated upload key outside Git and back it up securely. Never use the repository's public debug key or replace the existing key casually. |
| Review access | Public **Try sample mail** needs no personal mailbox credentials. All five version-8 instructions are saved and reread: public sample, ordinary mail, blocking/reporting, sample arrival/retry and access/reset. Old DOB/guardian-PIN instructions are removed. Exact final UI/attachment and TLS evidence is recorded; actual regional Play screens were not exercised. |
| Public policies | Existing HTTPS [privacy](https://pportilla.github.io/simple_webmail_upm/privacy.html) and [acceptable-use](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) URLs remain. Version-8 policies, reviewer guide and FGS evidence are deployed at `3614a831cb632f18783210881d2bd4545933c8cb`; 18 canonical URLs, including historical archives, passed HTTP/hash verification. |
| Production gate | At least **12 genuine closed testers continuously opted in for 14 days**, then a production-access application. Three configured addresses and internal participation do not satisfy it. Last recorded qualifying opt-ins: zero; no new enrollment check is claimed. |

## General 13+ behavior to verify

Setup uses one acknowledgment of age 13+, mailbox authorization and acceptable-use rules. There
is no birth-date form, guardian PIN, family dashboard, contact approval or per-message approval
for ordinary eligible 13–17-year-olds. Keep ordinary protections: blocking, deliberate reporting
with a payload preview, TLS verification, permission choices and truthful privacy disclosures.
The UGC policy covers client apps and one-to-one blocking; being an email client does not remove
those duties. [UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937),
[target audience](https://support.google.com/googleplay/android-developer/answer/9867159).

Legacy teen migration preserves accounts, drafts and blocked senders. It holds old queued mail
for review before ordinary access is enabled; previously `SENDING` rows retain an uncertain-send
outcome rather than being silently retried. Known legacy under-13 profiles stay blocked. Removing
a local guardian system is not permission to send old mail automatically or discard stored mail.

The offline sample keeps the shared repositories and mail engine. Sample sends/reports are local
simulations, even if a user types a real recipient. A sample-only account never runs IMAP IDLE or
the persistent foreground service. Explicit browser/export actions still have their ordinary OS
behavior, and coexisting real accounts can still use the network. Do not claim that the whole app
collects no data or that sample success verifies a UPM login.

### Play Age Signals

The included SDK is **`com.google.android.play:age-signals:0.0.4`**. Request access, then retrieve
signals when shared. Optional age-sharing refusal, absent data or SDK errors create no new
restriction and do not clear an existing explicit restriction. A verification requirement, valid
under-13 range or pending/declined significant-change approval can restrict access. A qualifying
explicit response can clear the hold. Normal eligible teens use the same interface as adults.
[Request flow](https://developer.android.com/google/play/age-signals/request-age-signals),
[response definitions](https://developer.android.com/google/play/age-signals/understand-age-signals-responses).

Persist only the coarse access restriction, not raw Play age ranges, installation IDs, age source
or identity documents. The app introduces no developer server for these signals. Transient checks
pause network/send work; an already eligible user can still use cached mail and drafts. Unknown
responses and failed persistence must not erase a known hold. Do not claim a fresh regional
response before every background action or that sideload tests exercise actual Play verification.

Google says the API is not a universal Play policy requirement; applicable regional obligations
still need consideration for this 13+ app. The private focused review in
`release-output/general-release-7/policy-review.md` records concrete US/Brazil findings and limits;
SDK inclusion is not a blanket legal-compliance claim. Actual regional Play account behavior
requires appropriate Play-distributed testing. [Play guidance](https://support.google.com/googleplay/android-developer/answer/16569691),
[Age Signals testing](https://developer.android.com/google/play/age-signals/test-age-signals-api).

## Significant-change notice and mandatory release hold

The change from supervised teen mail to ordinary 13+ mail is being treated as a significant
change in Play's applicable flow. The Console date picker on 25 October offered **28 October
2026** as its earliest effective date. Google's guide says a change takes effect at **00:00 UTC**;
approval before that time can concern the prior experience. The statement that notices must be
more than two days apart is about spacing between changes, not a universal 48-hour countdown.
[Significant-change guide](https://developer.android.com/google/play/age-signals/notify-significant-changes).

- [x] Enable managed publishing for the upcoming update.
- [x] Save the intended 13–15, 16–17 and 18+ audience selections, pending review.
- [x] Submit and verify the English/Spanish significant-change notice: effective **28 October
      2026**, editable until **26 October**; confirmed **25 October at 13:29:26 Europe/Madrid**.
      Exact confirmation and owner-authorized terms records are private.
- [x] Validate the exact version-8 signed bundle and discard both unpublished v7 drafts.
- [x] Upload exact version 8; save closed release 6 with proposed 100% rollout and internal
      release 7 as an unpublished draft. Neither saved draft is a live version-8 release.
- [x] Finish matching EN/ES listings/screenshots and FGS evidence; submit the six v8 changes at
      **14:33:01 Europe/Madrid on 25 October 2026**, with managed publishing on. Console shows
      **In review** in submission activity 4 after reload; no running-check banner remains.
- [x] Save version 8 **as an unpublished internal draft**. Version 7 was never submitted or published;
      keep the replacement held too. Do not use internal rollout
      to bypass the date hold or assume managed publishing automatically holds that track.
- [ ] At or after **28 October 2026, 00:00 UTC / 01:00 Europe/Madrid**, recheck the notice, review
      result, platform requirements and managed-publishing state before deliberate publication.

No publication job or automatic schedule has been created. Review approval alone does not waive
the date hold. Existing version-6 testing can continue while version 8 is held.

## Exact version-8 validation

Do not mark these complete using version-7 or earlier results.

- [x] Freeze **271** unchanged inputs, committed at `02b67de977ed3dd8156a8cac9d38bbe40ef5a65e`,
      and record exact AAB/universal/source hashes in `RELEASE_STATUS.md`. Code8/name1.1.0, API levels,
      non-debuggable release, disabled backup and offline EN/ES availability passed validation.
- [x] Pass **704 release tests**, zero failures/errors/skips; lint **0 errors / 42 warnings / 2 hints**.
      Signatures and R8 mapping evidence passed; R8 8.11.26 is enabled with **4,369,820 DEX bytes**.
- [x] Verify 16 KB bundle/ELF/ZIP structure and the unchanged upload certificate. This is not a
      16 KB runtime-device test.
- [x] Inspect the exact uploaded v8: **High**, **98% optimization / 98% obfuscation / 98% shrinking**,
      **2.4 MB** new-install, **1.14 MB** update, **18,029** devices and **Supports 16 KB**.
- [x] Retest uncached attachment export on exact v8 after **31 seconds** in the system picker:
      all **101 bytes** and the expected content hash matched.
- [x] Verify fresh v8 13+ setup/sample entry and ordinary EN/ES navigation. Preserve the earlier
      17 v7 migration/general-mail checks as scoped history; do not claim a full v8 migration rerun.
- [x] Pass automated eligibility/request-window/persistence/transport tests, and observe real
      foreground cancellation in the exact-v8 TLS fixture. The emulator used the SDK
      unavailable-service fallback; actual Google regional verification was not exercised.
- [x] Pass **13 exact-v8 TLS checks**, including MIME attachment bytes, pre-DATA cancellation and
      once-only delivery, warm manual refresh, wrong-hostname rejection and IDLE/manual stop.
      Preserve the continuous **98.998256-second** v8 video; local fixtures do not verify UPM.
- [x] Capture and visually inspect **ten** actual EN/ES v8 screenshots, five per locale, with no
      guardian UI, keyboard or blank frames. Existing external-mailto screenshot protection is
      documented separately; ordinary compose captures correctly.

API 36 is configured; inspect the final bundle against the current [target API policy](https://support.google.com/googleplay/android-developer/answer/11926878)
and [16 KB guidance](https://developer.android.com/guide/practices/page-sizes). The existing App
Signing enrollment and upload key are retained. A Play install cannot update an installation with
a different app-signing certificate; do not suggest uninstalling without accounting for local
messages, drafts and queued work.

## Listing and Console declarations

Use `fastlane/metadata/android/en-US/` and `es-ES/` for store text and genuine runtime screenshots.
Use the version-specific `changelogs/8.txt`. These files do not publish themselves. Contact fields
are entered in Console; do not rely on unsupported locale `contact_email.txt` metadata.

- [x] Save/reload the general 13+ EN/ES descriptions and version-8 notes; both descriptions match
      local metadata and all ten screenshots are saved in 01–05 order. No unsaved listing changes
      or removed guardian/under-13 marketing remain in the submitted listing.
- [x] Update version labels and reread all five App access entries using the no-credentials sample route and any
      explicit Play eligibility screen. Do not provide a fictional password, suggest bypassing a platform hold,
      or claim the sample tests production mail transport. [Review access](https://support.google.com/googleplay/android-developer/answer/15748846).
- [x] Deploy version-8 page labels and exact reviewer/FGS evidence. All **18** canonical URLs
      returned HTTP 200 and matching hashes at **14:26:17 Europe/Madrid on 25 October 2026**;
      earlier evidence remains separately archived.
- [x] Recheck the existing IARC summary: Communication with blocking/reporting remains accurate;
      it contains no in-app parental-control promise to remove. Regional ratings remain separate
      from the saved 13+ target-audience declaration.
- [x] Reconcile the saved Data safety export/preview with live mail and the Play SDK: retain
      13 types, 4 required/9 optional (required Email address, User IDs, Emails and App interactions),
      encrypted transit and no declared sharing under the documented transfer exceptions. The
      sample stays local and raw Play age signals are not sent to a developer server. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469).
- [x] Retain accurate account answers: the app connects existing external mailboxes, does not create
      a University identity and does not delete a provider account. Local removal differs from
      provider-side deletion. [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
- [x] Confirm the app-specific Console declarations: final read-only preflight reports all
      **11 actioned**, none needing attention, only the three 13+ groups, and matching saved
      listings. Preserve exact responses and submission state privately.

Selected device contacts become recipients only when used; the entire address book is not uploaded.
Recipients, mail/attachments, remote resources, server searches and explicit export/sharing need
accurate disclosure. A 13+ audience does not eliminate user-data or applicable regional obligations.
Real mailbox users must have their own provider authorization. Current sign-in uses IMAP/SMTP,
not Google OAuth; an OAuth client is not a Play-publishing credential for this implementation.

## Foreground service and battery settings

Version 8 retains optional direct IMAP IDLE for eligible real mailboxes. Push is off by default;
the user selects **Instantly (push)**. It has an ongoing generic notification and stops when the
user chooses periodic/manual checks or no eligible real account remains. Sample accounts never
run this service. An explicit platform hold and a transient access check must also gate network
work; the former universal adult-only/guardian rule is no longer the declared behavior.

The manifest uses `specialUse`, with an IMAP-listener subtype. The listener waits for future
changes rather than performing a finite transfer; scheduled polling remains available with
different notification timing. No FCM backend is implemented. The successful version-7 video is historical. The exact version-8 recording is complete;
its public deployment and matching Console description/video were verified and saved/reread.
[Service type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646).

- [x] Record the exact final release enabling push, ongoing status, actual background arrival,
      opening the message and stopping push. Use synthetic mail and keep credentials out of footage.
- [x] Publish only sanitized video/identity files; verify URL/hash and save/reload the updated
      functionality, interruption impact, use-case rationale and evidence link in Console.
- [x] Confirm the exact final manifest lacks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; help still
      uses general Android battery settings. Device-specific help behavior and power restrictions
      are broader coverage limits, not incomplete Console declarations. Timing is not guaranteed.

Broader Android 14–16 restart/reboot, notification-denial, long Doze and device-specific behavior
remain separate coverage unless actually exercised. Do not relabel the service merely to avoid a
policy decision. Google review, not a successful upload, decides the declaration's acceptance.

## Build, credentials and headless access

Keep the existing upload key and signing properties outside Git under `/home/gablo/.config/upm-mail/`.
Never print passwords, commit them or include them in an owner ZIP. Use `scripts/setup-signing.py
--existing /absolute/path/upload.jks --alias ALIAS` only to register an existing key; passwords are
entered at hidden terminal prompts. Do not generate a replacement for the key already in use.

```bash
export UPM_SIGNING_PROPERTIES_FILE=/home/gablo/.config/upm-mail/signing.properties
python3 scripts/build-release.py --version-code 8 --version-name 1.1.0
```

The helper runs release lint/tests/bundle generation, requires R8 mapping/renaming evidence and
reports uncompressed DEX bytes, then verifies signing and excludes the public debug key.
`--check-optimization /absolute/path/app.aab` checks an existing artifact without rebuilding or
verifying signing. `--unsigned` is validation-only and cannot produce a Play-ready release.

For interactive Console access, the owner signs in and completes passkeys/2FA in a browser they
can view and control. Keep remote display/debugging local and use an SSH tunnel; never expose a
logged-in browser or saved session publicly. A headless Playwright session alone is not a usable
login display. No Google password needs to be sent in chat.

An optional service account can support later uploads: enable the Android Publisher API, invite
its email in Play Console with app-specific release permissions, and store any JSON credential
outside the checkout with mode `0600`. No billing/account-wide admin grant is needed. Cloud-project
linking is no longer required; API access does not replace Console agreements or all declarations.
Avoid concurrent Console/API edits. [API setup](https://developers.google.com/android-publisher/getting_started),
[edits workflow](https://developers.google.com/android-publisher/edits).

## Testing and later publication

1. Preserve version 6 as the current available release while the submitted version 8 completes
   Google review under managed publishing.
   Do not turn the significant-change date into a claim that publication is automatically scheduled.
2. At or after the date hold, verify actual review and platform state, then deliberately publish
   the approved testing update. Keep signed artifacts and separate submission, approval and live-state records.
3. Eligible testers can join the existing [closed test](https://play.google.com/apps/testing/io.github.pportilla.upmmail).
   Internal participants must first leave internal testing and then join closed testing.
   [Track-switching rules](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en).
4. Maintain at least **12 continuously opted-in closed testers for 14 days**, collect feedback and
   apply for production access. Configured addresses do not start the clock. Production remains
   unpublished. [Testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465).
