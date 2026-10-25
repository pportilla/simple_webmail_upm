# UPM Mail release status

Updated **25 October 2026 (Europe/Madrid)** for **zango**. Support/privacy: **p.portilla89@gmail.com**.

**Version 6 (1.0.3) is approved and available in closed Alpha and available internally.**
Version **7 (1.1.0)** is being prepared as a general **13+** mail client. Its final signed build
passed **698 tests**, lint with zero errors, and artifact validation. Exact-release runtime
checks and Console upload/submission remain in progress.
**Do not publish version 7 on any track before 28 October 2026 at 00:00 UTC
(01:00 Europe/Madrid). Internal testing is included in this hold.** Managed publishing is on,
but it must not be relied on to prevent an internal release. The significant-change notice has
been submitted successfully for that effective date. No automatic publication or scheduled
release job has been created.

The last recorded dashboard count was **zero qualifying closed-test opt-ins**, with three
configured tester addresses; this is not a fresh enrollment check. Production remains unpublished
and requires the qualifying closed test and production-access approval. See
[the release checklist](PLAY_STORE_RELEASE.md).

## Version 7: general 13+ mail

The owner changed the intended audience to **13–15, 16–17 and 18+** and requested removal of the
universal under-18 supervision. The new setup uses one local acknowledgment of 13+ eligibility,
authorized mailbox use and acceptable-use rules. It does not request a birth date or guardian PIN,
and there is no family dashboard, contact approval or per-message guardian approval. Eligible
13–17-year-olds receive the ordinary mail interface, including normal attachments, search,
contacts permission choices and optional real-mail push.

Sender blocking, deliberate provider-report composition and preview, verified TLS, permission
choices and the public offline sample remain. Sample sends/reports stay local; sample accounts
never run the persistent IMAP foreground service. The unread-row tint and leading accent from
version 6 remain. No ads, billing, developer mail relay or analytics integration is introduced.

Legacy teen migration preserves accounts, drafts and blocked senders. Previously queued mail is
held for user review before ordinary access is enabled; rows left in `SENDING` are treated as
uncertain delivery rather than automatically resent. Legacy guardian secrets/approval machinery
are removed. Known legacy under-13 profiles remain unsupported and blocked, rather than silently
becoming eligible. Migration and signed-release runtime proof remain pending below.

### Platform eligibility signals

The app integrates **Google Play Age Signals 0.0.4** using access request followed by signal
lookup when shared. Ordinary teen use is not a reason for a local restriction. An explicit
verification requirement, valid under-13 range or pending/declined significant-change approval
can restrict mail access. Optional sharing refusal, missing data and SDK errors create no new
restriction and do not erase a previously known one. A qualifying explicit response can clear
an existing restriction. This is not a claim that an SDK integration satisfies every regional law.

Only a coarse platform restriction is retained. Raw Play age ranges, installation identifiers,
age source and identity documents are not stored or sent to a developer server. A transient
check pauses network/send operations while an already eligible user can still view cached mail
and drafts. The transient state is not persisted. A request has a bounded wait, and a valid late
response can still apply; persistence failures cannot clear an existing restriction.

Production Play verification/approval screens require suitable Play accounts and regions;
local unit or sideload tests do not establish end-to-end regional behavior. The focused policy
review and its sources are recorded privately in `release-output/general-release-7/policy-review.md`.
Official references: [Age Signals request flow](https://developer.android.com/google/play/age-signals/request-age-signals),
[response definitions](https://developer.android.com/google/play/age-signals/understand-age-signals-responses),
[significant changes](https://developer.android.com/google/play/age-signals/notify-significant-changes).

## Version 7 readiness and publication hold

| Item | Last confirmed state |
| --- | --- |
| Identity | Verified `io.github.pportilla.upmmail`, code **7**, name **1.1.0**; target/compile API 36, minimum API 26. Non-debuggable, backup disabled and both languages retained. |
| Source/artifacts | All **271** frozen source inputs remained unchanged; source tree and exact signed artifacts are recorded below. Bundletool and AAB/exact-universal signature checks passed. |
| Automated validation | **698 tests passed, zero failures/errors/skips. Lint: zero errors, 42 warnings, 2 hints.** Bundle page-size metadata, all four native ABI ELF alignments and exact-universal ZIP 16 KB alignment passed. No 16 KB runtime-device test is claimed. |
| Runtime | Exact-release general setup, sample mail, legacy migration, eligibility behavior and real TLS/FGS evidence pending. Real UPM/external-provider testing is not established. |
| Audience | Only **13–15, 16–17 and 18+** have been saved in Console for the update; review is pending. Under-13 groups are removed from the proposed declaration. |
| Significant-change notice | English/Spanish notice submitted successfully at **13:29:26 Europe/Madrid on 25 October**. Console shows effective **28 October 2026**, editable until **26 October**. Effective time: **00:00 UTC / 01:00 Europe/Madrid**. |
| Internal track | Version 6 remains available. Version-7 upload/draft preparation is pending; **keep it unpublished before the hold expires**, even if review or validation completes earlier. |
| Closed track | Version 6 is approved/live. Prepare version 7 for review with managed publishing **on**; version-7 submission/review outcome is pending. |
| Publication scheduling | No automatic release is scheduled. After the hold expires, recheck the saved notice, review result, platform behavior and managed-publishing state before a deliberate publication action. |
| Listing / access / public evidence | Version-7 privacy, acceptable-use and reviewer-guide pages, plus archived v6 pages, are deployed and HTTP/hash verified. General 13+ listings/screenshots, saved no-PIN Console access instructions and new FGS evidence remain in progress. |
| Data safety / IARC | Existing live-mail declarations and content rating need reconciliation with the final Age Signals integration and removal of guardian controls. Final version-7 form verification pending; do not copy a previous all-age/parental-controls assertion. |

### Frozen version-7 artifact evidence

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `release-output/general-release-7/upm-mail-1.1.0-v7.aab` | 6,284,984 | `feaccc8004771ecac57c66b1a684e617b1e2bdb040e183726060f272b9cadf72` |
| `release-output/general-release-7/universal.apk`, generated from that exact AAB | 2,860,972 | `40ba30ec869b1b5d84e8ba2550793e3ecf39186b87d3a1d583f3ebd45efea4af` |

The 271-input source snapshot tree SHA-256 is
`68473e73ec3db4bad5e688b68e331ac4c16168cce257931d43ec13311c32d9e4`.
Validation is recorded in `release-output/general-release-7/validation.json`, with `SHA256SUMS`
and the adjacent source snapshot. R8 **8.11.26** has optimization, obfuscation and shrinking enabled;
uncompressed DEX totals **4,368,636 bytes**. Raw embedded `noObfuscationPercentage`,
`noOptimizationPercentage` and `noShrinkingPercentage` are **1.54 / 2.15 / 1.67**. These are compiler
metadata, not an observed version-7 Google Play score. The bundle declares `PAGE_ALIGNMENT_16K`,
and all four native ABI libraries have 16,384-byte ELF load alignment. The public upload
certificate below remains unchanged. Runtime acceptance remains a separate check.

The notice record is `release-output/general-release-7/significant-change.json`, with the saved
Console confirmation in `significant-change-submitted.txt`. Public pages were deployed at
`gh-pages` commit `1dbfec998a59028f02717ebb63291cfb49246586` and verified at **13:37:30
Europe/Madrid on 25 October 2026**. Privacy, acceptable use and reviewer guide, plus all three
archived `-v6.html` pages, returned HTTP 200 with matching local hashes. Evidence:
`release-output/general-release-7/public-policy-deployment.json`. This deployment does not
publish the version-7 app or waive its date hold.

The date hold applies to the changed experience, not just production distribution. Google's
significant-change guide states the effective time is 00:00 UTC; approval before that time can
refer to the prior experience. The Console date-picker observation is recorded separately from
that general rule. Review approval alone does not authorize early internal or closed publication.

## Current distribution and credentials

The app is free, unofficial, in the Communication category, and uses existing authorized UPM or
compatible TLS mailboxes. A local sample is available without mailbox credentials. No reviewer
mailbox was provisioned; the owner declined that route. Updated access instructions must describe
the sample honestly and distinguish simulated sending from real-provider access.

Eligible testers can use the existing [closed-test opt-in](https://play.google.com/apps/testing/io.github.pportilla.upmmail)
for the available release. Existing internal testers must first leave the
[internal test](https://play.google.com/apps/internaltest/4701581542823930298), then join closed testing
with an eligible account. Google requires **12 testers continuously opted into the closed test
for 14 days** before the production-access application. Configured addresses and internal testing
do not meet that requirement. [Track switching](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en),
[production testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465).

## Historical evidence scope

The following versions used the former all-age/supervised experience. Their guardian checks,
service eligibility, policy declarations and screenshots are historical evidence, not claims
about version 7. Preserve their frozen binaries and records; do not relabel them as new validation.

## Historical version 6 (1.0.3): current available release

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
with managed publishing off at submission. A later Console check on 25 October confirmed version 6 approved and live in closed Alpha. Managed publishing has since been enabled for the version-7 hold. Evidence is in
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

## Historical version 5 (1.0.2): optimization evidence

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
| Play delivery and DEX metrics | **Version 5 was available to internal testers and approved/live in closed Alpha** at the start of the version-6 update. Version 6 now supersedes it in both internal and closed testing. Google Play reports **High** DEX optimization: **98% optimization, 98% obfuscation and 98% shrinking**. Its release preview had zero errors and only the prebuilt native-debug-symbol advisory. |

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

## Earlier evidence and private handoff

Version 3 (1.0.1) added the shared-engine offline sample and passed 673 automated tests,
16 sample/guardian runtime checks and a separate 97.918-second local TLS service demonstration.
Its frozen AAB SHA-256 is `39df4200ef71c74f5ccc18fca82532d6fc7c9638c7bbe8c4b36aedbf36e75df3`;
its universal APK is `42df1b413c7cc2d4bdae0fc86e62f21e73b4a04b0a606838e784f17fab206f8c`.
Records remain under `release-output/demo-release-3/` and `release-output/demo-runtime/`.
Version 2 (1.0.0) passed 643 tests and its release smoke check; its earlier screenshots/service
recording remain under `release-output/family-release-2/` and `release-output/family-runtime/`.
None of these older checks establishes version-7 migration or Play Age Signals behavior.

Public upload-certificate SHA-256:

```text
B5:CE:F6:46:1E:E2:98:48:17:C9:7B:D8:69:21:BA:EC:10:4E:F9:44:B5:47:E4:A0:D1:DB:7D:D1:CA:01:95:48
```

The upload key and signing configuration remain outside Git under `/home/gablo/.config/upm-mail/`,
mode `0600`. Back them up securely; do not include them in public artifacts or owner ZIPs.
Google's distribution certificate may differ from the upload certificate. Keep historical owner
kits private: Console records can contain tester addresses. Package only frozen allowlisted
artifacts and sanitized evidence; exclude browser sessions, passwords, signing secrets, fixture
credentials and private keys. A version-7 kit is not yet recorded as assembled.

## Remaining work

1. Finish exact-version-7 migration/general-mail, eligibility and TLS runtime checks, then update
   the saved reviewer instructions, screenshots and service evidence. Signed artifact validation
   and public policy deployment are complete.
2. Finish declarations and upload/submit version 7 under the managed hold. The significant-change
   notice is submitted. Preserve an unpublished internal draft. Do not publish any version-7 track before
   **28 October 2026, 00:00 UTC / 01:00 Europe/Madrid**; no automatic release is scheduled.
3. After that time and the required checks/review, deliberately publish the approved testing update.
   Continue recruiting/retaining at least 12 closed testers for 14 days before applying for production.
