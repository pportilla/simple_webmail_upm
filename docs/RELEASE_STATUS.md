# UPM Mail release status

Updated **25 October 2026 (Europe/Madrid)** for **zango**. Support/privacy: **p.portilla89@gmail.com**.

**Version 6 (1.0.3) is available to internal testers**, after signed-artifact validation and 11
focused UI checks for a clearer distinction between read and unread mail. The closed update was
submitted; Console reload confirms **Changes in review**, awaiting Google approval. Version **5 (1.0.2)** remains the approved release in closed
Alpha; its completed artifact, optimization and runtime evidence is retained below. The last recorded Console dashboard
count was **zero qualifying closed-test opt-ins**; this is not a new enrollment check. Production
is not published and requires the qualifying closed test and production-access approval.
See [the release checklist](PLAY_STORE_RELEASE.md).

## Version 6 unread-mail visibility update

Unread messages now have a theme-aware tinted row background and a **4 dp accent at the logical
start edge** in the shared mailbox and search-result row. The existing bold sender/subject,
unread dot and accessibility state remain. Selection keeps its own background while retaining
the unread accent, and the accent follows right-to-left layout direction. Read messages retain
the normal surface background. This changes presentation, with no mail, guardian, service or
data-handling behavior change.

The source is committed at `a4090d492a88b3066215326d008bbdc0f5313373`. The exact signed bundle
passed **673 release tests** with zero failures/errors/skips, lint with **zero errors, 41 warnings
and 2 hints**, bundle/signature validation and 16 KB ELF/ZIP structural checks. All **264** frozen
source inputs remained unchanged; their tree SHA-256 is
`a367d52bf67e1b02785f2328102667245298e2e406eb2aa01d2e9c36d0c5beb4`.
The package remains `io.github.pportilla.upmmail`, minimum API 26 and target/compile API 36;
the release is non-debuggable, backup is disabled and both languages are retained.

| Frozen version-6 artifact | SHA-256 |
| --- | --- |
| `release-output/unread-release-6/upm-mail-1.0.3-v6.aab` | `c5d9e16340c61859eca4e441630ce97f6db68813297973d9f7e9136e525383c2` |
| `release-output/unread-release-6/universal.apk`, generated from that exact AAB | `ccefd630c1eb8ec50ca9f70fa615881e1b1a5dd01bc16c39e4deb59ad7deabb2` |

Validation is recorded in `release-output/unread-release-6/validation.json`. Embedded R8
**8.11.26** still enables optimization, obfuscation and shrinking; uncompressed DEX is **4,405,176
bytes**. These local artifact observations are separate from the Console scores below.

Google Play independently reports **High** DEX optimization for version 6: **98% optimization,
98% obfuscation and 98% shrinking**, a **2.42 MB** new-install estimate and **235 KB** update.
All **18,029** supported devices are retained. These values were observed at **12:39 Europe/Madrid
on 25 October 2026**, with evidence in `release-output/unread-release-6/play-optimization.json`
and `play-bundle-details.txt`. The internal release was published at **12:41:02 Europe/Madrid**
and its availability was verified after reload. The full-rollout closed update was submitted at
**12:42:52 Europe/Madrid on 25 October 2026**. Console reload confirms **Changes in review**,
with managed publishing off; Google approval remains pending. Evidence is in
`release-output/unread-release-6/publishing-after-submission.txt` and its screenshot.

The exact derived APK passed **11 focused visual/interaction checks** on Android 16/API 36:
upgrade preserved the sample account and read state; light/dark read-versus-unread appearance,
mixed selection, marking unread and opening to mark read, previews disabled, actual sample
search results with separate query highlighting, and dynamic colors in light/dark modes.
No fatal, verifier or missing-class app exception appeared. Evidence, screenshot hashes and scope
are in `release-output/unread-runtime-6/qa-summary.json`; this used one emulator and the fictional
adult sample through normal UI, without database injection. Dynamic colors used the current
wallpaper palette. TalkBack and a right-to-left locale were not exercised; semantics and logical
start positioning were reviewed in source. Mail/guardian/TLS regression scenarios were not
repeated for this visual change; the version-5 runtime evidence below remains historical.

## Version 5 optimized release — approved

Release builds now enable R8 code optimization/obfuscation and resource shrinking. The mail
rules retain only required provider interfaces, reflective constructors, MIME handlers and
the hostname-verifier alias; they do not keep entire mail or app packages. AGP **8.11.2**
includes R8 **8.11.26**. The outgoing retry count is recomputed from the immutable queued item
after suspending cleanup, shortening the integer's lifetime without changing retry decisions
and avoiding an invalid optimized coroutine register assignment. App coroutine optimization
remains enabled. The exact rebuilt APK completed retained sample and real-TLS outbox work,
fresh mail and guardian-control checks before publication was authorized.

The build helper rejects bundles without an embedded, nonempty R8 mapping identifying R8 and
at least one renamed class. It also prints the sum of uncompressed DEX bytes. For an existing
bundle, run `python3 scripts/build-release.py --check-optimization /absolute/path/app.aab`;
this checks optimization evidence only, without rebuilding or claiming a Play score.

| Item | Verified state |
| --- | --- |
| Version | `io.github.pportilla.upmmail`, code **5**, name **1.0.2**; minimum API 26, target/compile API 36. |
| Source inputs | Native/source changes are committed at `dcfa34c8c300bb72aab420eac2780eafe7ee6f64`. All 264 frozen files remained unchanged during validation. Tree SHA-256 `d7000a8bd3ec43dd789eb5d247e44454c34be84517c1e8361368ab42b12e58e3`, recorded in `release-output/optimized-release-5/source-input-snapshot.json`. |
| Release tests / lint | **673 tests passed; zero failures, errors or skipped. Lint: zero errors, 41 warnings, 2 hints.** Android CI runs [40185836726](https://github.com/pportilla/simple_webmail_upm/actions/runs/40185836726) and [40185833724](https://github.com/pportilla/simple_webmail_upm/actions/runs/40185833724) also succeeded for source commit `dcfa34c8`. |
| Signatures / packaging | Bundletool, AAB signature and release/exact-universal APK signature checks passed. Non-debuggable, backup disabled and both languages retained. |
| 16 KB structure | `PAGE_ALIGNMENT_16K`, four native ABI libraries with 16,384-byte ELF load alignment, and release/universal APK ZIP alignment passed. No 16 KB runtime-device test is claimed. |
| Runtime | The exact derived APK completed retained sample and real-TLS queued messages after upgrade. Independent IMAP inspection verified the real message's UTF-8 body and both attachments byte-for-byte. Fresh TLS MIME forwarding passed; a same-CA certificate with the wrong hostname was rejected. All nine local TLS/foreground-service checks and the fresh sample/guardian walkthrough passed. Sample arrival notifications were not retested in version 5. |
| Play delivery and DEX metrics | **Version 5 was available to internal testers and approved/live in closed Alpha** at the start of the version-6 update. Version 6 now supersedes it internally; version 5 remains the approved closed release. Google Play reports **High** DEX optimization: **98% optimization, 98% obfuscation and 98% shrinking**. Its release preview had zero errors and only the prebuilt native-debug-symbol advisory. |

| Frozen version-5 artifact | SHA-256 |
| --- | --- |
| `release-output/optimized-release-5/upm-mail-1.0.2-v5.aab` | `96e55a48c4565c9192f1dbffc195e3968a2d371f5ec1a0ec40e46fdd2d86da02` |
| `release-output/optimized-release-5/universal.apk`, generated from that exact AAB | `15f76833d63b84541a67b7e3f4706abb7f616a9d20569aa397550f575794744e` |

Evidence is in `release-output/optimized-release-5/validation.json` and
`optimization-validation.json`. Uncompressed DEX fell from **46,908,968 to 4,404,944 bytes**
relative to frozen version 3, a **90.6096% byte reduction**. Of 5,129 retained DEX classes,
4,994 have changed class names (**97.3679%**). Those local measurements are not Play scores.
The actual nested hostname-verifier class and its enclosing class retain their required names.

Embedded `r8.json` confirms R8 **8.11.26**, with optimization, shrinking and obfuscation enabled.
Its raw `noObfuscationPercentage`, `noOptimizationPercentage` and `noShrinkingPercentage`
values are **1.51**, **2.11** and **1.64** respectively. These are preserved raw metadata, not
the separately observed Console percentages.
Exact compiler reports are frozen under `release-output/optimized-release-5/r8-reports/`,
with hashes in `manifest.json`. The embedded mapping SHA-256 is
`1f0c2413d8e99b628bdbe90c0aed636f9560e51f173e9cbc58052d18cafcfbab`.

Google Play independently reports **98%** for optimization, obfuscation and shrinking, with
**High** DEX optimization. Its new-install download estimate is **2.42 MB** (version 3: 12.5 MB),
and update estimate **2.25 MB**. Supported-device count remains **18,029**. The release preview
has no mapping/obfuscation warning; its only advisory concerns prebuilt native debug symbols.
Evidence: `release-output/optimized-release-5/play-optimization.json` and adjacent text/screenshot,
verified **25 October 2026 at 11:43 Europe/Madrid**. These observed Play scores are separate from
local byte reduction, renamed-class counts and raw R8 metadata.

Nine exact-version-5 real-mail checks passed, recorded in
`release-output/minified-runtime-5/tls-qa-summary.json`. The configured local TLS fixture exercised
IMAP MIME loading, SMTP UTF-8 text and two byte-identical attachments, queued-send recovery,
wrong-hostname rejection, background IMAP IDLE arrival (**2.67 seconds**) and manual stopping.
The **109.731-second** continuous recording and its identity manifest are under
`release-output/minified-runtime-5/push-evidence/`; video SHA-256
`4e048fc114f1109e5a8b386a5c6a72e1cd39b98c1a6a9dae77876b06596038cc`.
The exact signed app and its TLS checks were unchanged. This used an emulator-only trusted CA
and synthetic loopback mailbox, not the built-in sample or a real UPM/external-provider account.

The exact version-5 APK also passed **15 sample/guardian checks** on Android 16/API 36 with
4 KB pages: retained outbox recovery, fresh offline reply, attachment export, draft persistence,
search, eventual retry completion, under-13 setup, guardian persistence after restart, exact
incoming approval, wrong-PIN rejection, separate exact-send approval and locking after Home.
Newly generated unreviewed replies stayed hidden from the child. This is not a repeat claim for
every version-3 scenario: sample arrival notifications were not retested in version 5.
Evidence is recorded in `release-output/minified-runtime-5/qa-summary.json`. The transient retry
failure itself was not captured; runtime proves eventual completion. Direct version-3 guardian
upgrade, every folder/report action, all locales and long Doze scenarios were not repeated on
this final artifact. No verifier, reflective-loader, uncaught-scope or fatal error appeared in
the app UID log.

Version 4 and the intermediate version-5 build are rejected candidates, preserved separately.
Device tests found a renamed hostname-verifier alias and an invalid optimized coroutine
register assignment. Neither candidate was published; the final version-5 artifact above is the validated replacement.
Their size reductions and R8 metadata must not be presented as final release validation.

## Version 3 source and artifact identity

- Repository: `pportilla/simple_webmail_upm`; branch: `codex/play-store-release`.
- Version-3 source/build changes are committed at
  `58504452f76c6d274ecd8a93eebe14d42f70f8a8` and pushed to [PR #1](https://github.com/pportilla/simple_webmail_upm/pull/1).
- Package: `io.github.pportilla.upmmail`; version code **3**, version name **1.0.1**.
- Minimum API **26**, target/compile API **36**; release is non-debuggable, with app/device-transfer
  backup disabled. Language splitting is disabled so English and Spanish remain available offline.
- Distribution intent remains **free, worldwide, no ads, all ages including children under 13**.
  UPM Mail is an independent, unofficial client for existing UPM or compatible TLS mailboxes.

The frozen files below are identified by checksum; preserve the uploaded AAB rather than
rebuilding an already-used version code. Paths are relative to the repository.

| Artifact | Path | SHA-256 |
| --- | --- | --- |
| Signed Play AAB | `release-output/demo-release-3/upm-mail-1.0.1-v3.aab` | `39df4200ef71c74f5ccc18fca82532d6fc7c9638c7bbe8c4b36aedbf36e75df3` |
| Universal APK generated from that AAB | `release-output/demo-release-3/universal.apk` | `42df1b413c7cc2d4bdae0fc86e62f21e73b4a04b0a606838e784f17fab206f8c` |

## Implemented behavior

Version 3 adds public **Try sample mail** / **Probar correo de ejemplo** after the normal age and
acceptable-use gate. **Sample · review@example.invalid** needs no mailbox credentials. Its
folders, drafts, attachments, searches, sends, reports and incoming replies are local simulations
through the shared repositories and mail engine. Sends never reach real recipients. The immutable
sample-account flag, schema-4 migration and per-account backend registry isolate it from real
mailboxes; removal invalidates retained clients and deletes that sample's files.

The sample-arrival action creates fictional mail after a local delay. Adult instant mode refreshes
it locally, using ordinary notification permissions/settings. Sample accounts never open a mail
server connection or run the persistent foreground service. Real accounts and explicit browser,
sharing/export actions retain their normal behavior. The public/native policy and both store
languages now explain this distinction. The verified [public reviewer guide](https://pportilla.github.io/simple_webmail_upm/demo-review.html)
describes the tested route and the limits of local simulation.

The supervised controls introduced in version 2 remain in force for sample and real mail:

- Only the derived age band is retained. Under-18 setup requires acceptable-use/provider
  acknowledgments, adult DOB declaration and a private guardian PIN. Unconfigured access is
  blocked; these local controls do not independently verify identity or legal parental consent.
- The guardian approves contacts and each incoming message's exact text, then each outgoing
  message's exact content/recipients. Child reading/writing is plain text, without attachments,
  remote resources, clickable links, contact import, export or unrestricted search.
- Children can block senders and save local concerns. An adult can deliberately compose a provider
  report with an exact preview; original mail is not attached automatically. Sample reports stay
  local. Real reports include normal sender identity/headers and use the selected provider.
- PIN throttling persists. Guardian sessions lock on pause, Back and process restart. Repository,
  transport and Android-action guards enforce revocation. Accepted mail cannot be recalled;
  uncertain SMTP delivery is not automatically retried as if nothing was sent.
- Real mail requires verified TLS. Passwords use Android Keystore; mailbox files are not separately
  encrypted by the app. Push is off by default, adult-only and real-account-only. Supervised mail
  has no content notifications. No direct battery-exemption request, ads, billing, developer mail
  relay, analytics or automatic crash-reporting SDK is implemented.

## Completed version 3 validation

Evidence: `release-output/demo-release-3/validation.json` and its adjacent validation outputs.

| Check | Verified result |
| --- | --- |
| Release unit tests | **673 passed; 0 failures, 0 errors, 0 skipped.** |
| Release lint | **0 errors, 41 warnings, 2 hints.** Warnings remain; a clean error count is not a claim of warning-free output. |
| Bundle/signatures | Bundletool validation, signed AAB verification and release/universal APK signatures passed. The universal APK was generated from the exact AAB above. |
| Packaging | Version/package/API levels, non-debuggable release, backup disabled and language-split configuration verified. |
| 16 KB structure | AAB declares `PAGE_ALIGNMENT_16K`; all four ABI copies of `libandroidx.graphics.path.so` have 16,384-byte ELF load alignment. Release and universal APK ZIP alignment passed. |
| Source inputs | All **264** files in the prebuild source-input snapshot remained unchanged during validation. |
| Upgrade smoke check | Installing the exact version-3 universal APK over version 2 preserved the existing locked/paused supervised profile. |

`source-input-snapshot.json` covers app source, resources, tests and repository Gradle/wrapper
configuration. Its tree SHA-256 is
`c471be05078d7d8de55c60a377ac392cfd49a8b279fd5e82d2c77db3974fa704`.
It excludes downloaded dependencies, the SDK/JDK and local signing configuration; it is not a
reproducible-build or cryptographic source-to-binary attestation.

The exact signed universal APK passed **16** runtime checks on Android 16/API 36 with 4 KB pages,
using normal public UI with network disabled for sample scenarios and no app-database injection.
Evidence is `release-output/demo-runtime/qa-summary.json`. Checks include credential-free sample
entry, attachment export, reply/Sent/fictional reply, search, eventual retry completion, draft
persistence after restart, local arrival notifications without PushService, removal/recreation,
under-13 setup, individual incoming approval, wrong-PIN rejection and exact outgoing approval.
The approved child simulation completed locally; its new reply stayed hidden from the child and
available to the guardian for review. No app-scoped fatal exception or ANR appeared.

The first transient retry failure was not captured at runtime; automated tests cover that
sequence. Mixed real/sample operation, every locale/search operator, provider-report simulation,
all folder actions and long-lived OS process killing were not exhaustively retested in this
walkthrough. Generic search “From server” and removal “UPM server” wording remains, surrounded by
sample-only notices. Structural 16 KB checks are not a 16 KB runtime-device test.

A separate **97.918-second** exact-version-3 [foreground-service demonstration](https://pportilla.github.io/simple_webmail_upm/foreground-service/v3/)
shows verified local TLS 1.3 authentication, user-selected IMAP IDLE, ongoing status, background
synthetic arrival, opening mail and manual stopping. Service/ongoing-notification absence after
stopping was verified. The signed app and TLS checks were unchanged; a temporary CA was trusted
only by the disposable test emulator. Public-safe evidence is in
`release-output/demo-release-3/push-evidence/`. This used a configured local TLS mailbox, not the
built-in sample. Real UPM login and external-provider delivery remain unverified.

## Current Play Console state

| Area | Last confirmed state |
| --- | --- |
| Internal track | **Version 6 (1.0.3) is available to internal testers**, published at **12:41:02 Europe/Madrid on 25 October 2026** and verified after reload. Three tester addresses remain selected. [Internal opt-in](https://play.google.com/apps/internaltest/4701581542823930298) requires an eligible tester account. |
| Closed track | **Alpha version 5 (1.0.2) remains approved and available**. The **version-6 full-rollout update was submitted at 12:42:52 Europe/Madrid on 25 October 2026** and Console reload confirms **Changes in review**. Managed publishing is off; approval is pending. The configured scope remains 178 countries/regions with three tester addresses. The dashboard last reported **zero qualifying opt-ins**; configured addresses are not enrollment. |
| Production | Not published; the verified personal account requires **12 closed testers continuously opted in for 14 days**, followed by a production-access application. |
| App access | Restricted-access answer **Yes** and five instruction entries saved/reloaded, using the tested public sample route and reviewer-created guardian PIN. The first entry now identifies version 6 (1.0.3), saved and verified after reload; the other four are retained. The public guide is deployed and verified. The owner declined external reviewer credentials; none were provisioned. |
| Audience/Families | All six age bands, including **5 and under**, are saved; the version-3 release and declarations are approved. Required owner-authorized declarations are recorded privately. Local DOB/PIN controls are not independent legal-consent verification. |
| Data safety | Approved with version 3. All **13** type dialogs were rechecked: **4 required / 9 optional**, App functionality, non-ephemeral. Required types are Email address, User IDs, Emails and App interactions; automatic read-state sync makes the last required. No sharing is declared under documented transfer exceptions; encrypted transit, existing external accounts, no app-account creation and no deletion-request mechanism remain declared. |
| Store/policies | Category Communication, developer/support details, existing assets and revised v3 EN/ES descriptions are saved. Privacy/terms remain public. The version-6 reviewer guide is deployed and HTTP/hash verified. FGS guide/video remain explicitly version-5 evidence, with version-3 FGS evidence archived separately. |
| Foreground service | The saved `specialUse` description and public exact-release video identify **version 5**, whose closed update is now approved. The version-6 change does not modify the service; the retained video remains version-5 evidence. Built-in sample arrivals are not real IMAP IDLE evidence. |

The version-6 reviewer guide was deployed at `gh-pages` commit
`67a6843fae391c09a2b05b4c0abba074055726a6` and verified at **12:41:48 Europe/Madrid on 25 October
2026**: HTTP 200 and SHA-256 `749cb2eb14923aec6a4c6b64884971e0b3b5d83b6521f3b89f0c7c1eb8cd9b88`
matched the local guide. It distinguishes version-6 visual checks from the historical version-5
mail/guardian walkthrough and service video. Privacy and acceptable-use terms were unchanged.

Version-5 public evidence was deployed at `gh-pages` commit `6323e00` and verified at
**11:57 Europe/Madrid on 25 October 2026**. All five checked URLs returned HTTP 200 with matching
local hashes, including the [reviewer guide](https://pportilla.github.io/simple_webmail_upm/demo-review.html),
[foreground-service guide](https://pportilla.github.io/simple_webmail_upm/foreground-service/),
video/identity and archived version-3 identity. Records are
`release-output/optimized-release-5/public-deployment.json`, `internal-published.txt`,
`publishing-after-submission.txt` and `foreground-service-saved.txt`.

The saved type-by-type Data safety evidence is
`release-output/demo-release-3/data-safety-all-answers.json`. It includes the revised required
App interactions answer. Local age/PIN/approval data and sample mail are not off-device collection.

Public policy/FGS deployment is recorded in `release-output/demo-release-3/public-deployment.json`,
at `gh-pages` commit `80ae3b0dddcff055f97500bbbf7052ecb80a3688`; checked URLs returned HTTP 200 and
matched the local exports. The version-3 MP4 SHA-256 is
`5acca5614813d5f03e7170976c3f357b7694871639659fa49daff813a7ebb83d`.
The finalized reviewer guide was deployed at `de2e698d185ab69d142badba33a289b7bb013410`, with
HTTP/hash evidence in `release-output/demo-release-3/reviewer-guide-deployment.json`.
Console evidence includes `app-content-actioned.txt`, `internal-published.txt` and
`review-submit-result.txt` in the same directory.

The existing IARC Communication questionnaire declares blocking/reporting and does not claim the
whole app is restricted to invited friends. Ratings are ESRB Everyone, PEGI Parental guidance,
Brazil/Germany all ages, and IARC Generic/Russia/South Korea 12+. Ratings do not establish target
audience or independent legal-consent verification. The version-3 release and policy declarations
have since been approved. Version 5 remains approved/live in closed Alpha while version 6 is
available internally and its closed update awaits approval.

Eligible testers can now use the
[closed-test opt-in](https://play.google.com/apps/testing/io.github.pportilla.upmmail). Anyone enrolled internally must first
leave the internal test, then join the closed test with the same eligible Google account.
Google documents this [track-switching requirement](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en).
Count the required 14 continuous days only from actual closed-test opt-in, with at least 12 testers.

## Historical evidence and private handoff

Version 2 (1.0.0) passed **643** tests and its exact-AAB Android 16 smoke test. Its separate
**97.64-second** local TLS fixture video showed opt-in, foreground status, background synthetic
arrival and stopping push. The [archived demonstration](https://pportilla.github.io/simple_webmail_upm/foreground-service/v2/)
was verified as version-2 evidence. It was not a UPM/external-provider test. The 16 raw store
screenshots and their `CAPTURE.json` records also belong to that earlier capture set, not a v3
screenshot run. Historical validation is in `release-output/family-release-2/` and
`release-output/family-runtime/QA_SUMMARY.json`.

Public upload-certificate SHA-256:

```text
B5:CE:F6:46:1E:E2:98:48:17:C9:7B:D8:69:21:BA:EC:10:4E:F9:44:B5:47:E4:A0:D1:DB:7D:D1:CA:01:95:48
```

The private upload key and signing configuration remain outside Git under
`/home/gablo/.config/upm-mail/`, mode `0600`. Back them up securely; never include them in a public
artifact or owner ZIP. Google's distribution certificate may differ from this upload certificate.

The existing private v2 kit is
`release-output/family-release-2/upm-mail-1.0.0-v2-play-kit.zip`, with adjacent SHA-256 file.
It remains historical. The v3 owner handoff path is
`release-output/demo-release-3/upm-mail-1.0.1-v3-play-kit.zip`, with an adjacent SHA-256 file.
The handoff includes
only frozen, allowlisted artifacts and sanitized evidence, plus private owner/Console records as
appropriate. Exclude signing secrets, passwords, browser sessions and raw test-fixture staging.
Keep owner kits private because Console records may include tester addresses. Do not package
`release-output/demo-release-3/push-staging/`.

## Remaining work

1. Monitor the submitted version-6 closed update and address any specific Google findings.
   Its internal release is available; preserve the version-5 validation and service evidence separately.
2. Recruit and maintain at least 12 closed-test participants for 14 continuous days, gather
   feedback and apply for production access. Internal participation and three configured addresses
   do not fulfill this gate; existing internal testers must switch tracks as described above.
