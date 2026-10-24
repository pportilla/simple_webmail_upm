# UPM Mail release preparation status

Prepared **24 October 2026** for the first Google Play release by **zango**.
Support and privacy contact: **p.portilla89@gmail.com**.

The supervised-mail implementation and signed **version 2 (1.0.0)** bundle are prepared and
validated. **Version 2 is available to internal testers; production is not published.** The internal
release is marked **Not reviewed**. Code validation, test distribution and Google approval are
separate states; this report records each below. See [the release checklist](PLAY_STORE_RELEASE.md)
for policy sources and the remaining submission steps.

## Source and release identity

- Repository: `pportilla/simple_webmail_upm`; review branch: `codex/play-store-release`.
- Base branch: `claude/android-upm-webmail-app-3p882d`.
- The artifacts were built from the modified source tree containing the supervised-mail changes.
  Their checksums below identify the validated files; an earlier base commit does not contain them.
- Release package: `io.github.pportilla.upmmail`; version code **2**; version name **1.0.0**.
- Minimum Android API **26**; target/compile API **36**; release is not debuggable.
- Owner-confirmed distribution intent: **free**, **worldwide**, **no ads**, **all ages including
  children under 13**. The app is an unofficial client for existing UPM or compatible TLS mailboxes.
- The existing verified personal Play account requires **12 closed-test participants opted in
  continuously for 14 days** before an application for production access. Three tester addresses
  are configured in both the closed and internal tracks; that does not establish an active closed
  test or start its clock.

## Implemented safety and release changes

- Neutral birth-date entry before mailbox setup; only the derived age band is retained. Under-18
  users enter a separate supervised interface. Unconfigured profiles cannot access credentials
  or mail. Supervised setup requires an adult, acceptable-use acceptance, provider authorization acknowledgment,
  an adult birth-date declaration and a guardian PIN. These are local controls, not independent
  identity verification or a certificate of legal parental consent.
- Guardian-approved addresses and individual incoming-message approval bound to the reviewed
  content. Each outgoing message requires approval of its exact content and recipients. Account
  changes, contact blocking and revocation invalidate pending permissions. A sender address is
  not treated as proof of identity.
- Supervised reading and writing use plain text, with no child attachments, remote images,
  clickable links, sharing, address-book import or search. Supervised drafts stay local. The MIME
  sender can include a generated HTML alternative of the same text; children do not compose rich
  HTML content.
- Children can block a sender and save a local concern for guardian review. A separate adult
  provider-report composer previews deliberate report text and destination. Reports also contain
  the sending identity and normal mail headers; private original correspondence is not attached
  automatically. In supervised profiles, reports require a live guardian session when sent.
- Persisted PIN throttling and a salted PIN verifier; guardian sessions lock on pause/background,
  Back and process restart. Sensitive surfaces and dialogs protect screenshots; Recents capture
  is disabled on supported Android versions, with window protection on older versions.
- Authorization checks cover repositories, queued SMTP, live transport work, notifications,
  attachments and external Android actions. Revocation interrupts active SMTP/IMAP work where
  transmission can still be stopped. Mail already accepted by a server cannot be recalled, and
  ambiguous delivery remains a non-automatic-retry `SEND_UNCERTAIN` result.
- Verified TLS is required for configured IMAP/SMTP. Android cleartext traffic is disabled.
  Passwords use Android Keystore encryption; mailbox files are not separately encrypted by the
  app. Backup and device transfer are disabled.
- Instant push is **off by default** and available only in the adult profile. Supervised profiles
  use periodic/manual retrieval and no mail-content notifications. Push notifications contain
  generic status, and changing profiles cancels live IDLE connections. The direct battery-exemption
  permission was removed; battery help opens general Android settings.
- English/Spanish native and public privacy/acceptable-use text, store metadata, dedicated upload
  signing and release helpers are prepared. No ads, billing, developer mail relay, analytics or
  automatic crash-reporting SDK was found.

## Verified build results

The final machine-readable record is `release-output/family-release-2/validation.json`.

| Check | Result |
| --- | --- |
| Release unit tests | **643 passed; 0 failures, 0 errors, 0 skipped.** |
| Focused transport regressions | Four SMTP tests cover revocation while MAIL/RCPT waits, guardian report locking and uncertain delivery after the terminator. Three push tests cover denied startup and cancellation of live IDLE/keep-alive work. |
| Safety coverage | Age/terms gates, exact approvals, account changes, PIN persistence/throttling, restart locking, sender blocks, reports, queued retries and provider authorization. |
| Release lint | **0 errors, 40 warnings, 2 hints.** Remaining warnings concern dependency freshness, resources, style and maintenance. The new `UseKtx` suggestion concerns intentionally checked synchronous safety-policy persistence. There is no `BatteryLife` warning. No suppression was added to conceal these checks. |
| Bundle/signing | Final signed AAB passed bundletool validation and JAR signature verification. Release APK and exact-AAB universal APK passed signature verification. |
| 16 KB support | AAB declares `PAGE_ALIGNMENT_16K`; all four ABI copies of `libandroidx.graphics.path.so` have 16,384-byte ELF load alignment. Release/universal APK zip alignment passed. This is structural validation, not a test on a 16 KB runtime device. |
| Packaging | Version 2, expected package, API 26/36, backup disabled and non-debuggable confirmed. Language splitting is disabled so both languages remain available offline. |
| Text/assets | Native EN/ES privacy XML parsed; store text meets character limits; changed-file whitespace checks passed. |

The 213 main/test Kotlin files match the source-hash snapshot recorded before the final build
(`release-output/family-release-2/source-code-snapshot.json`). This covers those files only, not
XML, Gradle or dependencies, and is not a cryptographic source-to-binary attestation.
The full suite covers the final Kotlin/Java source. The final resource-only wording corrections
were then repackaged and lint checked. Real UPM sign-in and external-provider mail delivery
remain unverified; local fixture tests must not be presented as those checks.

The universal APK generated from the exact final AAB was installed on an Android 16/API 36
emulator. Actual under-13 onboarding reached adult DOB/PIN setup, provider acknowledgment and
terms acceptance, then the supervised home. Correct PIN unlock and immediate Home/reopen locking
passed. A guardian-screen capture was black and the safe child surface remained readable; no
app-scoped fatal exception or ANR appeared. This is a 4 KB emulator, not a 16 KB runtime test.

Broader fixture-based checks exercised individual incoming review, exact outgoing approval and
local queuing (not live delivery), wrong PIN rejection, child sender blocking, a concern visible to
the guardian, communication revocation and ignored external mail/share intents. Those checks used
an earlier debug build (SHA-256 beginning `3ae9e8ff`); the final debug build (beginning `23e7f8fd`)
also preserved the locked/paused profile after update. Recents retained a previous safe child snapshot; the current
guardian snapshot was protected. This does not claim that every Recents image is blank. Final
screenshot provenance is recorded per locale and the scoped QA summary is
`release-output/family-runtime/QA_SUMMARY.json`.

The exact final-AAB universal APK also authenticated against a disposable local TLS 1.3 IMAP/SMTP
fixture on a separate Android 16 emulator. A continuous **97.64-second** video shows adult opt-in,
the ongoing notification, actual synthetic arrival while backgrounded, opening the delivered
message and stopping push by selecting manual checking. Service and ongoing-notification absence
were then verified. A temporary CA was trusted only by that disposable emulator; application TLS
verification and the signed APK were unchanged. This is not a UPM login, external-provider test
or reusable reviewer account. The [public demonstration and guide](https://pportilla.github.io/simple_webmail_upm/foreground-service/)
returned HTTP 200 and matched the local export at **12:14 UTC on 24 October 2026**, deployed at
`gh-pages` commit `d1dc11515a26d98778b7d54695c2785632e068bb`. Sanitized video and identity evidence
are in `release-output/push-evidence-final/`; fixture credentials and private CA material are excluded.

Known non-blocking limitation: repeated external `mailto` requests into an existing adult activity
can retain screenshot protection until a fresh normal launch. This may prevent OS screenshots;
it does not expose mail or lose message content.

## Exact local artifacts

Generated release files are ignored by Git. Preserve the exact uploaded AAB rather than rebuilding
an already-uploaded version code.

| Artifact | Local path | SHA-256 |
| --- | --- | --- |
| Signed Play AAB | `/home/gablo/mail_upm/app/build/outputs/bundle/release/app-release.aab` | `ac81d703d4afb24e73743ec31f9845925bd97b5bfaad503b23f7618f830b8dfb` |
| Signed release APK | `/home/gablo/mail_upm/app/build/outputs/apk/release/app-release.apk` | `ddff439aa0d56a91f30e15e631f95957157b9217acaae50f4c14ef4f2cfe6628` |
| Universal APK generated from that AAB | `/home/gablo/mail_upm/release-output/family-release-2/universal.apk` | `25c6f3a6105905219686bd0a34a6ff9c6ef2f1c08719096bfa947649cb1bace6` |
| Debug APK for local QA | `/home/gablo/mail_upm/app/build/outputs/apk/debug/app-debug.apk` | `23e7f8fd7531b6ec45cc2df22043b40e077678d1d7da737b74fc169da6e118d8` |

Public upload-certificate SHA-256:

```text
B5:CE:F6:46:1E:E2:98:48:17:C9:7B:D8:69:21:BA:EC:10:4E:F9:44:B5:47:E4:A0:D1:DB:7D:D1:CA:01:95:48
```

The private key is `/home/gablo/.config/upm-mail/upload-key.jks`; signing configuration is
`/home/gablo/.config/upm-mail/signing.properties`. Both have mode `0600`, outside Git. Back them up
securely. They must not appear in public artifacts or the release kit. Google's distribution
certificate may differ from this upload certificate.

The final private owner kit is
`/home/gablo/mail_upm/release-output/family-release-2/upm-mail-1.0.0-v2-play-kit.zip`, with an adjacent
`.zip.sha256` file. It contains the exact AAB/universal APK, public certificate, validation and scoped
QA/video evidence, final policies and metadata, plus the owner's private terms/action and Console
state records. It excludes the signing key, passwords, browser sessions and raw test fixtures.
`SHA256SUMS` inside the ZIP verifies every other entry. Keep this handoff private because the
Console record includes the owner-supplied tester addresses.

The previous version-1 bundle, 611-test report, `runtime-smoke/` universal APK and older handoff
ZIP are superseded evidence. Do not use them to describe version 2 or upload them by mistake.

## Store assets and public policies

- English/Spanish listing text is under `fastlane/metadata/android/en-US/` and `es-ES/`.
- Each locale has a **512 × 512** icon and **1024 × 500** feature graphic derived from the existing
  launcher envelope vector. Editable sources are in [store-artwork](store-artwork/README.md).
- **16 final store screenshots** are prepared: eight per locale, at **1080 × 1920**, frozen at
  **12:03 UTC on 24 October 2026**. They show the adult inbox, message, compose, privacy and sign-in
  screens, plus supervised home, approved-message view and supervised compose. They are unmodified
  runtime captures with fictional `.invalid` identities and no real mailbox password. Each locale's
  `images/phoneScreenshots/CAPTURE.json` records the final debug APK, provenance and checksums.
  Guardian setup and approvals used the actual UI; only fictional mail cache entries were seeded.
- [Privacy policy](https://pportilla.github.io/simple_webmail_upm/privacy.html) and
  [acceptable-use rules](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) were
  redeployed at `gh-pages` commit `602bbe90804caa4af335b1f0ef6f7b5cd186bb45`. Both returned HTTP 200
  and matched final source bytes at **11:49 UTC on 24 October 2026**. Local verification is in
  `release-output/privacy-deployment.json`. The privacy URL is saved in Play Console.

## Confirmed Console state

- The app record exists. Category **Communication**, support email and repository support website
  are saved. Final English/Spanish listing descriptions, 16 screenshots, icon and localized feature
  graphics are saved as changes ready for review. The icon and two feature graphics are labeled
  as created/edited using AI; the unmodified runtime screenshots are not. Public policy links are saved.
- The IARC Communication questionnaire is saved. Blocking/reporting are declared; the app is
  **not** described as universally limited to invited friends. Current results are ESRB Everyone,
  PEGI Parental guidance, Brazil/Germany all ages, and IARC Generic/Russia/South Korea 12+.
  These regional ratings do not establish a target-audience declaration or Families approval.
- Three tester addresses are configured and selected in both the **closed and internal** tracks.
  No completed 12-person/14-day test or production-access approval is claimed.
- **App access remains unresolved.** The owner declined reviewer mailbox credentials and offered
  the public support email. Console explicitly requires reviewer access to all functionality and
  says reviewers cannot contact the owner for access. This prerequisite was freshly confirmed
  at **12:17 UTC on 24 October 2026**. A support address cannot truthfully satisfy
  that attestation. No review mailbox or full-function offline demo is configured; no external
  account was provisioned. The audience form is disabled until sign-in details are completed.
- Data safety is saved as a **draft**, with submission blocked by the unfinished audience form.
  It declares 13 collected data types for app functionality: required email addresses, user IDs and
  emails; optional names, selected contacts, files/documents, photos, videos, three audio categories,
  server search history and remote-content interactions. It declares non-ephemeral processing,
  encrypted transit, no app account creation, existing external accounts and no deletion-request
  mechanism. No sharing is declared under the disclosed user-directed transfer exceptions.
  Local-only age/PIN/approval records are not represented as off-device collection. The saved
  answer record is `release-output/data-safety-declaration.json`; this draft is not approval.
- **Internal version 2 was published at 11:56 UTC on 24 October 2026.** Console confirms
  **Available to internal testers / Not reviewed**, under temporary name
  `io.github.pportilla.upmmail (unreviewed)`. The three selected testers can use the
  [internal opt-in link](https://play.google.com/apps/internaltest/4701581542823930298). Availability
  requires an eligible tester account; this is not a public production listing or Google approval.
- The **specialUse foreground-service declaration is saved and verified after reload**, using the
  public exact-release video and the optional adult IMAP IDLE explanation. This is not Google
  approval of the service type. Final store listing changes show **Ready to send for review**.
- The **closed version-2 release is saved ready for review, but review submission is blocked**.
  It uses the same AAB, the three selected testers and all 178 available countries/regions.
  Release validation shows **zero errors** and two non-blocking mapping/native-symbol warnings.
  Publishing overview has **14 pending changes**, including the closed rollout and both listings.
  Its pre-review checks explicitly prevent sending because **Target audience and content** and
  **Data safety** are incomplete. No closed review submission, rollout or qualifying test has
  started; this state is `ready_for_review_blocked`.

## Remaining publication gates

1. Complete truthful full-function reviewer access if a supported route becomes available. The
   owner declined credentials; do not invent them, repeat the request or certify access based on
   the support email. Preserve the audience intent, rather than substituting 13+ to bypass forms.
2. Complete the target-audience/Families and Data safety declarations against the final controls,
   provider eligibility and applicable child-data obligations. Local DOB/PIN controls do not
   independently establish legal consent or Google acceptance.
3. Complete the publishing-overview steps that Console permits and monitor any review response.
   The `specialUse` declaration and video are saved; optional adult-only push still requires
   Google's assessment of the declared service type.
4. Activate the required closed test when Console permits it, recruit at least **12** participants,
   maintain their opt-in for **14 consecutive days**, gather feedback, then apply for production
   access. Three configured addresses and an internal release do not fulfill this gate.
5. Preserve the actual track state: internal testing is available, the saved closed release is blocked,
   and production access is not approved. The owner has requested publication; no additional
   approval is implied here for already-authorized steps. Update this record when Console
   confirms any further review or rollout.
