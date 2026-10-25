# Google Play release checklist

Updated **25 October 2026 (Europe/Madrid)** for `io.github.pportilla.upmmail`.
**Version 6 (1.0.3) is approved/live in closed Alpha and available internally.** Version **7
(1.1.0)** is being prepared for a general **13+** audience. Its signed build passed **698 tests**,
lint with zero errors and artifact validation. Runtime and Console submission evidence remain
pending; earlier release results do not validate this changed experience.
Exact historical identities remain in [release status](RELEASE_STATUS.md).

**Hold publication of version 7 on every track, including internal testing, until at least
28 October 2026 at 00:00 UTC (01:00 Europe/Madrid).** Managed publishing is on for the update,
but does not automatically protect an internal release. No automatic publication is scheduled.
The English/Spanish significant-change notice is submitted successfully for that effective date.

## Identity and current scope

| Item | Confirmed scope or remaining evidence |
| --- | --- |
| App / publisher | Independent, unofficial **UPM Mail**, developer **zango**, support **p.portilla89@gmail.com**, [repository/support website](https://github.com/pportilla/simple_webmail_upm). Keep the non-affiliation statement visible. |
| Account / distribution | Existing verified personal Play developer account; free, Communication category, no ads or billing; current scope 178 countries/regions. No new developer account is needed. |
| Audience | Only **13–15, 16–17 and 18+** are saved for the proposed update, pending review. This supersedes the earlier all-age intent. Normal teens receive ordinary mail; known under-13 profiles remain unsupported. Target audience and IARC ratings are separate. |
| Version | Validated version code **7**, name **1.1.0**. Preserve the exact validated/uploaded AAB. For any later upload, use a code above every code already used in Console. |
| Signing | Preserve the existing dedicated upload key outside Git and back it up securely. Never use the repository's public debug key or replace the existing key casually. |
| Review access | Public **Try sample mail** needs no personal mailbox credentials. Version-7 instructions must describe the new 13+ acknowledgment, ordinary mail and any explicit Play requirement; remove old DOB/guardian-PIN instructions. Final signed walkthrough and saved Console update pending. |
| Public policies | Existing HTTPS [privacy](https://pportilla.github.io/simple_webmail_upm/privacy.html) and [acceptable-use](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) URLs remain. Version-7 privacy, acceptable-use and reviewer-guide pages, plus archived v6 pages, returned HTTP 200 with matching source hashes at `gh-pages` commit `1dbfec998a59028f02717ebb63291cfb49246586`. |
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
- [x] Validate the exact signed version-7 bundle; preserve its frozen hash and the date hold.
- [ ] Complete declarations and closed review submission under managed publishing.
- [ ] Keep any version-7 internal release **as an unpublished draft**. Do not use internal rollout
      to bypass the date hold or assume managed publishing automatically holds that track.
- [ ] At or after **28 October 2026, 00:00 UTC / 01:00 Europe/Madrid**, recheck the notice, review
      result, platform requirements and managed-publishing state before deliberate publication.

No publication job or automatic schedule has been created. Review approval alone does not waive
the date hold. Existing version-6 testing can continue while version 7 is held.

## Exact version-7 validation

Do not mark these complete using version-5/6 counts or screenshots.

- [x] Freeze all **271** native source inputs and record exact AAB/derived-APK hashes in
      `RELEASE_STATUS.md`. Package/version/API levels, non-debuggable release, disabled backup and
      offline EN/ES availability passed validation.
- [x] Pass **698 release tests**, with zero failures/errors/skips; lint reports **0 errors,
      42 warnings, 2 hints**. Bundle/signatures and embedded R8 evidence passed. R8 8.11.26 is
      enabled; DEX totals **4,368,636 bytes**, separate from any future observed Play score.
- [x] Verify `PAGE_ALIGNMENT_16K`, all four ABI ELF load alignments and exact-universal ZIP alignment.
      This is structural compatibility, not a 16 KB runtime-device test. Upload certificate retained.
- [ ] Exercise fresh 13+ setup, public sample mail, blocking/report preview, attachments, search,
      draft persistence and ordinary teen use without guardian screens.
- [ ] Exercise legacy teen migration with accounts/drafts/blocks intact, queued mail held for review,
      `SENDING` marked uncertain, and known under-13 access still unsupported.
- [ ] Test optional sharing decline, explicit restriction/clearance, pending/error/late-response
      handling, restart/persistence and live/queued transport stopping. Separate mocked decisions,
      sideload behavior and any real Play account/region observations in the evidence.
- [ ] Exercise exact-release TLS IMAP/SMTP, MIME attachments and wrong-hostname rejection; record
      the new real-mail foreground-service demonstration. A local fixture is not UPM authentication.
- [ ] Inspect actual EN/ES screens and captures; replace screenshots showing removed guardian UI.

API 36 is configured; inspect the final bundle against the current [target API policy](https://support.google.com/googleplay/android-developer/answer/11926878)
and [16 KB guidance](https://developer.android.com/guide/practices/page-sizes). The existing App
Signing enrollment and upload key are retained. A Play install cannot update an installation with
a different app-signing certificate; do not suggest uninstalling without accounting for local
messages, drafts and queued work.

## Listing and Console declarations

Use `fastlane/metadata/android/en-US/` and `es-ES/` for store text and genuine runtime screenshots.
Use the version-specific `changelogs/7.txt`. These files do not publish themselves. Contact fields
are entered in Console; do not rely on unsupported locale `contact_email.txt` metadata.

- [ ] Save/reload the general 13+ EN/ES descriptions, current screenshots and version-7 notes;
      remove blanket guardian claims and under-13 marketing from the active listing.
- [ ] Save/reload App access using the actual no-credentials sample route and any explicit Play
      eligibility screen. Do not provide a fictional password, suggest bypassing a platform hold,
      or claim the sample tests production mail transport. [Review access](https://support.google.com/googleplay/android-developer/answer/15748846).
- [x] Deploy and HTTP/hash-verify version-7 privacy, acceptable-use and reviewer-guide pages,
      alongside the three archived v6 pages. Verification passed at **13:37:30 Europe/Madrid**;
      retain matching SDK/privacy wording and update any runtime verification notice only after QA.
- [ ] Recheck IARC interaction, blocking/reporting and parental-controls answers against version 7.
      The earlier rating is historical until the changed declaration is verified; do not retain a
      claim that the app itself provides guardian PIN/message controls.
- [ ] Reconcile Data safety with live mail and the Play SDK. Earlier mail declarations were 13 types,
      4 required/9 optional: required Email address, User IDs, Emails and App interactions. The sample
      remains local, but SDK data handling needs a specific final review; the old answers are not
      automatically a completed version-7 declaration. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469).
- [ ] Retain accurate account answers: the app connects existing external mailboxes, does not create
      a University identity and does not delete a provider account. Local removal differs from
      provider-side deletion. [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
- [ ] Confirm publisher/support details, no-ads answer, Communication category and every app-specific
      Console task. Save exact responses and actual review state privately.

Selected device contacts become recipients only when used; the entire address book is not uploaded.
Recipients, mail/attachments, remote resources, server searches and explicit export/sharing need
accurate disclosure. A 13+ audience does not eliminate user-data or applicable regional obligations.
Real mailbox users must have their own provider authorization. Current sign-in uses IMAP/SMTP,
not Google OAuth; an OAuth client is not a Play-publishing credential for this implementation.

## Foreground service and battery settings

Version 7 retains optional direct IMAP IDLE for eligible real mailboxes. Push is off by default;
the user selects **Instantly (push)**. It has an ongoing generic notification and stops when the
user chooses periodic/manual checks or no eligible real account remains. Sample accounts never
run this service. An explicit platform hold and a transient access check must also gate network
work; the former universal adult-only/guardian rule is no longer the declared behavior.

The manifest uses `specialUse`, with an IMAP-listener subtype. The listener waits for future
changes rather than performing a finite transfer; scheduled polling remains available with
different notification timing. No FCM backend is implemented. The old version-5 video is historical;
prepare a version-7 recording and description matching the changed eligibility behavior.
[Service type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646).

- [ ] Record the exact final release enabling push, ongoing status, actual background arrival,
      opening the message and stopping push. Use synthetic mail and keep credentials out of footage.
- [ ] Publish only sanitized video/identity files; verify URL/hash and save/reload the updated
      functionality, interruption impact, use-case rationale and evidence link in Console.
- [ ] Confirm the final manifest still lacks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and that help
      uses general Android battery settings. Background timing is not guaranteed.

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
python3 scripts/build-release.py --version-code 7 --version-name 1.1.0
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

1. Preserve version 6 as the current available release while finishing and reviewing version 7.
   Do not turn the significant-change date into a claim that publication is automatically scheduled.
2. At or after the date hold, verify actual review and platform state, then deliberately publish
   the approved testing update. Keep signed artifacts and separate submission, approval and live-state records.
3. Eligible testers can join the existing [closed test](https://play.google.com/apps/testing/io.github.pportilla.upmmail).
   Internal participants must first leave internal testing and then join closed testing.
   [Track-switching rules](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en).
4. Maintain at least **12 continuously opted-in closed testers for 14 days**, collect feedback and
   apply for production access. Configured addresses do not start the clock. Production remains
   unpublished. [Testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465).
