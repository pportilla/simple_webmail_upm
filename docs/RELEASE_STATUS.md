# UPM Mail release status

Updated **5 November 2026 (UTC)** for **zango**.
Support/privacy: **p.portilla89@gmail.com**. Times in this current record use UTC unless
explicitly marked otherwise.

**Version 9 (1.1.1) is available to internal testers**, released on **5 November at 11:52 UTC**.
It improves refresh completion, adds shorter mail-check choices and replaces the outdated
transitive AndroidX Fragment dependency. The exact signed build passed **729 tests**, lint,
artifact checks and the scoped runtime walkthrough. Closed Alpha still serves version 8 while
the version-9 update and EN/ES listing changes are **In review**, confirmed at **11:58:50 UTC**
after automated checks completed. Managed publishing is **off**, so the update will publish
automatically after Google approval.

The earlier significant-change hold expired on **28 October 2026 at 00:00 UTC**.
It is not a current release blocker. Version 9 does not change the existing 13+ audience or age
policy. Production is still disabled: the dashboard currently shows **one closed tester opted in**,
against the requirement for at least **12 continuously opted-in testers for 14 days** before
applying for production access. See [the release checklist](PLAY_STORE_RELEASE.md).

## Version 9 changes

Manual refresh checks mailbox headers without executing queued draft mutations or SMTP work.
Pending mutations keep their independent background flush, so the manual deadline cannot cause
an already accepted draft upload to be retried. Optional cache warming has a **five-second budget**
and cancellation closes its sockets. Manual refresh has a **60-second deadline**, including any
wait for the mailbox connection shared with other work; it does not promise a lock-free check. The refresh
indicator resets in cleanup after success, failure, cancellation or a folder/account change;
cleanup from an older refresh cannot clear a newer refresh's indicator. Outgoing mail still runs
through its separate queue. These changes address reproduced refresh stalls; the original
phone's reported ANR has not been established as the same failure or independently proven fixed.

Mail-check choices are **Instantly (push), 5, 10 and 15 minutes**, plus retained manual mode
(value 0). Legacy 30/60-minute settings normalize to 15 minutes. Five- and ten-minute choices use
inexact alarms to enqueue coalesced jobs that require network access; 15-minute checks use
periodic JobScheduler work. Existing push keeps its own periodic fallback. These are approximate
background intervals: Android batching, power restrictions and network availability can delay
mail checks. Changing the interval resets a waiting timer but lets an already active check
finish; rapid setting updates retain the latest choice. This introduces no exact-alarm permission
or new foreground-service type.

The Play warning named transitive **`androidx.fragment:fragment:1.1.0`**, reached through Play
Age Signals and Play services basement. A catalog-backed dependency constraint selects stable
**Fragment 1.9.1** without adding a direct fragment-based UI. Its resolved graph also selects
Lifecycle 2.10.0 and Tracing 2.0.0 components, so final runtime validation must cover the app's
normal lifecycle. Minimum API 26, target/compile API 36, Java 17 and AGP 8.11.2 remain configured;
no Android target change was required. Focused dependency/compile checks passed, including
AAR metadata compatibility and confirming that Fragment 1.1.0 is no longer selected.

The dependency evidence is `release-output/refresh-release-9/dependency-fix-summary.json` and
`resolved-release-dependencies.json`. Official sources:
[Fragment release notes](https://developer.android.com/jetpack/androidx/releases/fragment),
[Google Maven metadata](https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/maven-metadata.xml).
The exact signed AAB's embedded dependency metadata also identifies Fragment 1.9.1 and excludes
1.1.0. The uploaded Play release preview now has **no obsolete-SDK or DEX-optimization warning**.
Its single advisory concerns missing symbols for a prebuilt native library.

## Readiness and Console state

| Item | Last confirmed result |
| --- | --- |
| Identity | Verified `io.github.pportilla.upmmail`, code **9**, name **1.1.1**, minimum API 26, target/compile API 36; non-debuggable, backup disabled and both languages retained. |
| Source and artifacts | Native source is frozen and pushed at `0dc4914f74e5f214f8e45e1f66de1d9148de6ee4`. All **278 inputs** remained unchanged against snapshot tree `2f0b653e6eea77f66b23b71d53fde0992ac3ca6ec1c963d929e01fe014044db9`; exact signed AAB/derived APK validation passed. |
| Focused checks | Four real-socket refresh tests, four ViewModel tests, 24 SyncEngine tests and 16 repository tests passed. Dependency/compile checks passed. All ten planner tests and four additional virtual-time polling lifecycle tests passed. These are focused subsets of the final release suite. |
| CI | Both corrected-source Android CI runs, `42018787298` and `42018778273`, completed successfully for `0dc4914f74e5f214f8e45e1f66de1d9148de6ee4`, verified at **11:38:03 UTC**. |
| Tests/lint | **729 tests passed; zero failures/errors/skips. Lint: 0 errors / 45 warnings / 2 hints.** The three additional warnings versus v8 are style suggestions. Bundle/signatures, R8/dependency metadata and 16 KB structure passed. |
| Runtime | **10 scoped checks passed** on the exact final APK, covering stalled-body pull recovery, draft safety, settings/scheduler registrations, instant service/arrival/stop, network recovery, wrong-host TLS rejection, clean logs, both settings captures and fixture cleanup. See the scope below; earlier candidate SMTP/export results are separate. |
| Play warnings/metrics | Uploaded release preview: **0 errors**, one prebuilt-native debug-symbol advisory, **no obsolete-SDK or DEX-optimization warning**. Play DEX status **High**, optimization/obfuscation/shrinking **98% / 98% / 98%**; **16 KB supported**, **18,232 supported devices**. New-install estimate **2.39 MB** (14.5 KB smaller), update **1.42 MB**; device coverage unchanged across listed form factors. |
| Closed test | Version **8** remains active. Final version **9**, closed Alpha release **7**, and EN/ES listing changes are **In review**, confirmed at **11:58:50 UTC** after automated checks completed. Submitted at **11:53:16 UTC**; approval is pending. |
| Internal test | Version **9 (1.1.1)** is **Available to internal testers**, released at **11:52 UTC** as internal release **8**. The old unpublished version-8 draft was discarded. |
| Publishing controls | Managed publishing is now **off**. Only the intended five update/listing changes were pending before submission; approved changes can publish automatically. |
| Audience/declarations | Existing **13–15, 16–17 and 18+** scope retained. App content reports **all caught up**, with **11 actioned declarations** and no new action required. Audience/FGS/Data safety are unchanged; updated sign-in instructions are recorded in review. Listings are finalized and verified. |
| Public evidence | Seven policy/guide/archive HTML URLs are deployed and HTTP/hash verified. Five version-9 reviewer instructions are saved/reread. EN/ES listings are finalized and reloaded: seven total images, including five ordered phone screenshots per locale; new exact-v9 settings capture 04 verified. Existing FGS video remains explicitly v8 evidence. |
| Production | Disabled; **one** tester currently opted in. At least **12** continuous closed-test participants for **14 days**, then the production-access application, remain required. |

Initial Console evidence is in `release-output/refresh-release-9/console-initial-dashboard.txt`
and `console-initial-bundles.txt`. Review submission, approval and availability are separate
states; none is inferred from a successful local build or upload. The owner has authorized
publishing the update. Internal availability is verified in `internal-published.txt`;
`play-final-observation.json` and `publishing-after-submission.txt` confirm closed review.
Review and closed availability are separate states. `app-content-final.txt` and
`app-content-actioned-final.txt` confirm no outstanding declaration action.

## Frozen version-9 build and public evidence

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `release-output/refresh-release-9/upm-mail-1.1.1-v9.aab` | 6,334,813 | `d0d3c857637e7373b29fa697a7d143bb2f4c8507c94672db592b6997bc35ee1d` |
| `release-output/refresh-release-9/universal.apk`, generated from that exact AAB | 2,882,139 | `f87fed9175ca2d99ef4fa7e59cb9f247c93e312401df6e04a0a27b894ebed2d7` |

`release-output/refresh-release-9/validation.json` records the final 729-test results, signed
artifact identity and unchanged source snapshot. Bundletool and AAB/exact-universal signatures
passed. The AAB declares `PAGE_ALIGNMENT_16K`; all four ABI copies of the native AndroidX library
have 16,384-byte ELF load alignment and APK ZIP alignment passed. This is structural validation,
not a test on a 16 KB runtime device.

R8 **8.11.26** enables optimization, obfuscation and shrinking. Uncompressed DEX totals
**4,396,368 bytes**. Raw embedded `noObfuscationPercentage` / `noOptimizationPercentage` /
`noShrinkingPercentage` values are **1.56 / 1.96 / 1.46**; these are not observed Play scores.
The AAB dependency protobuf contains Fragment **1.9.1**, with obsolete **1.1.0 absent**.
Separately, Play artifact **4860248166760547627** reports DEX **High** and
optimization/obfuscation/shrinking **98% / 98% / 98%**, 16 KB support and **18,232 devices**.
These are observed Console values, not calculated from the local R8 percentages. Evidence:
`release-output/refresh-release-9/play-artifact-9-details.txt` and `closed-preview-expanded.txt`.

Public policy, acceptable-use and reviewer-guide labels were deployed at `gh-pages` commit
`217f69a4c8e952a1cea26846b17abd55e2b03fdf`. All **seven** checked HTML URLs, including the archived
v8 pages, returned HTTP 200 with matching hashes at **11:26:17 UTC on 5 November 2026**.
Evidence: `release-output/refresh-release-9/public-pages-deployment.json` and
`reviewer-access-verified.json`. The five saved/reread reviewer entries describe version 9 and
approximate checks. The existing FGS video stays labeled as version 8; these updates do not
claim a new service recording or publish the app.

## Exact-release runtime scope

`release-output/refresh-runtime-9/qa-summary.json` records **10 passed checks** on final universal
APK `f87fed9175ca2d99ef4fa7e59cb9f247c93e312401df6e04a0a27b894ebed2d7`, using an API 36
headless emulator and a disposable loopback TLS mail fixture:

- Stalled optional-body warming aborted at **4.850 seconds**; the pull indicator cleared and a
  subsequent pull succeeded. A server-accepted draft with its acknowledgment held for **26 seconds**
  survived an interval change and pull without cancellation or a duplicate server draft.
- Five-/ten-minute modes registered **300,000 / 600,000 ms inexact alarms**; 15-minute mode used its
  periodic job; manual mode removed repeating checks. Real-account instant mode started its service
  and fallback job, delivered a background arrival, and stopped both when switched to manual.
- After connection loss, explicit refresh fetched restored mail, verified against server and local
  database identifiers. This is not a precise row-visibility timing measurement. A trusted certificate
  for the wrong hostname was rejected, and valid TLS access recovered.
- App-scoped logs showed no fatal/verifier/missing-class/ANR marker. Both raw EN/ES settings captures
  show all five choices. Fixture passwords, keys, app data, test CA and port mappings were removed.

The ten-check count includes captures and cleanup. Five-/ten-minute evidence is actual scheduler
registration, not proof of exact background delivery timing. SMTP/MIME, interrupted-send recovery
and slow attachment-export checks belong to the separately archived earlier candidate; they were
not rerun on the corrected APK and are not presented as final-version runtime coverage. The other
eight store phone screenshots retain their original version-8 provenance. The allowlist identifies
**25 sanitized runtime files**; no fixture staging or secrets belong in a handoff.

## Retained behavior and evidence limits

The app remains an unofficial native client for authorized UPM or compatible TLS IMAP/SMTP
mailboxes. Eligible 13+ users get ordinary mail. Existing explicit Play eligibility restrictions,
local sender blocking, deliberate provider-report preview, permission choices and verified TLS
remain. Raw Play age ranges/identifiers are not forwarded to a developer server. Optional
sample mail uses the shared engine locally; simulated sends never reach a provider, and sample
accounts do not run persistent IMAP push. No ads, billing, developer mail relay or analytics
integration is introduced by this update.

Shorter polling must not be described as guaranteed delivery every five or ten minutes. Exact
release UI/scheduling observations, local TLS fixtures and live-provider tests must be identified
separately. Earlier tests did not establish real UPM authentication, external-provider delivery,
actual regional Play age verification or 16 KB runtime-device coverage. New evidence must say
which of those limits, if any, it changes.

## Historical records and private handoff

The complete prior status, artifact hashes, test counts and scoped runtime evidence are preserved
in [the 25 October version-8 archive](archive/RELEASE_STATUS_2026-10-25_V8.md). Its review state and
date hold are historical. The new Console observation supersedes its old current-state claims.
Keep frozen artifacts under their original version directories; never relabel v8 evidence as v9.
The superseded, unuploaded initial v9 candidate is preserved separately under
`release-output/refresh-release-9/candidate-before-draft-flush-fix/`.

Public upload-certificate SHA-256 remains:

```text
B5:CE:F6:46:1E:E2:98:48:17:C9:7B:D8:69:21:BA:EC:10:4E:F9:44:B5:47:E4:A0:D1:DB:7D:D1:CA:01:95:48
```

Upload signing material stays outside Git under `/home/gablo/.config/upm-mail/`, mode `0600`.
Back it up separately and exclude it from all owner/public bundles. Private handoffs may contain
owner Console/tester records; include only allowlisted frozen artifacts and sanitized evidence.
Exclude passwords, browser sessions, test keys/CA material and raw fixture staging.

## Remaining work

1. Await Google approval of the closed update and verify its automatic publication. Internal
   version 9 is already available; closed approval and availability remain separate states.
2. Recruit and retain enough closed testers to meet 12 participants continuously for 14 days,
   collect feedback and apply for production access. Production is not yet published.
