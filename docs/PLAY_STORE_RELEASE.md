# Google Play release checklist

Updated **5 November 2026 (UTC)** for `io.github.pportilla.upmmail`.
**Version 9 (1.1.1) is available to internal testers** from **11:52 UTC on 5 November**.
Closed Alpha still serves version 8 while the version-9 update is **In review**. Final signed
validation passed **729 tests**, zero failures/errors/skips, lint, artifact checks and **10 scoped
runtime checks**. Managed publishing is **off**; approved changes can publish automatically.
Automated checks completed; **In review** was confirmed at **11:58:50 UTC**. See [release status](RELEASE_STATUS.md).

The **28 October 2026** significant-change date has elapsed. It is not a current publication
hold, and this update does not change the 13+ audience or age policy. The owner has authorized
publishing the update. Recheck current review/managed-publishing state as part of actual delivery;
do not infer approval or availability from an upload.

## Current identity and release scope

| Item | Confirmed state or pending evidence |
| --- | --- |
| Publisher | **zango**, **p.portilla89@gmail.com**, [repository/support website](https://github.com/pportilla/simple_webmail_upm). Independent, unofficial UPM Mail. |
| Distribution | Existing personal developer account; free, Communication category, no ads/billing. Existing 178-region scope and 13–15 / 16–17 / 18+ audience retained. |
| Candidate | Code **9**, name **1.1.1**, minimum API 26, target/compile API 36 configured. Exact signed artifact and scoped runtime validated; internal release available and closed update **In review** with zero validation errors. |
| Dependency warning | Resolved release graph replaces transitive Fragment **1.1.0** with **1.9.1**. The exact uploaded release preview has no obsolete-SDK warning. |
| Refresh | Manual refresh executes neither queued mutations nor SMTP; independent mutation flush continues. Five-second optional warming budget, 60-second manual deadline including shared-connection lock waits, cancellation/selection cleanup. Original phone ANR is not independently proven fixed. |
| Check intervals | Instantly, **5 / 10 / 15 minutes**, with manual mode retained. Old 30/60 values normalize to 15. Short periods use inexact alarms/network jobs; they are approximate. |
| Signing | Reuse the existing upload key outside Git; never use the public debug key or replace the signing identity casually. |
| Review access | Existing public **Try sample mail** needs no mailbox credentials. Five version-9 reviewer instructions were saved and reread. Sample sending does not test UPM delivery. |
| Production | Dashboard shows **one opted-in closed tester** and disabled production access. Need at least **12 continuously opted-in testers for 14 days**, then an application. |

## Version-9 validation and delivery

- [x] Identify the warning's dependency path: Age Signals → Play services basement → Fragment 1.1.0.
      Confirm the release graph now selects only Fragment 1.9.1 and passes dependency/AAR/compile checks.
- [x] Pass focused refresh checks: four real-socket, four ViewModel, 24 SyncEngine and 16 repository
      tests. These are focused subsets of the final combined release suite.
- [x] Pass all **ten planner tests** covering short-interval job coalescing, manual/periodic
      transitions, authorization/account changes, process restart and active-work preservation.
- [x] Pass four virtual-time lifecycle regressions: interval changes reset a waiting timer,
      preserve active checks/draft flushing and retain the latest choice. Corrected source is
      now frozen with the draft-safety correction at `0dc4914f74e5f214f8e45e1f66de1d9148de6ee4`.
- [x] Pass the focused seven-test draft-safety suite, including three new regressions ensuring
      manual refresh does not execute pending draft mutations. The final combined suite and artifact checks also passed.
- [x] Record the corrected **278-input** snapshot: `2f0b653e6eea77f66b23b71d53fde0992ac3ca6ec1c963d929e01fe014044db9`.
      It remained unchanged through final artifact validation.
- [x] Pass **729 release tests**, zero failures/errors/skips; lint **0 errors / 45 warnings / 2 hints**.
      AAB metadata/signatures, R8 evidence and exact-derived APK checks passed. Embedded dependency
      metadata confirms Fragment 1.9.1 with 1.1.0 absent; final hashes are in `RELEASE_STATUS.md`.
- [x] Verify native ELF/ZIP 16 KB structure and bundle metadata. This is structural support,
      not a 16 KB runtime-device test.
- [x] Exercise final-release pull during a stalled optional body: cancellation at 4.850 seconds,
      indicator completion and another successful pull. Hold a server-accepted draft acknowledgment
      while changing interval and pulling; verify no cancellation or duplicate. Manual refresh
      executes no queued mutations/SMTP, but can wait for the shared mailbox connection.
- [x] Verify final-release 5/10-minute alarm, 15-minute job and manual-cleanup registrations, plus
      real-account instant service/fallback/background arrival and manual stop. Exact interval
      delivery timing and broader OEM/power behavior are outside this runtime coverage.
- [x] Verify restored fetch after connection loss, wrong-hostname TLS rejection, clean app logs,
      raw EN/ES settings captures and fixture cleanup. The final summary has **10 scoped checks**.
      Earlier-candidate SMTP/MIME/send recovery/attachment export are explicitly separate;
      sample and real regional Play eligibility were not newly exercised on the corrected APK.
- [x] Inspect uploaded v9 artifact **4860248166760547627**: no obsolete-SDK/DEX warning; DEX High,
      optimization/obfuscation/shrinking **98% / 98% / 98%**, **16 KB support**, **18,232 devices**,
      **2.39 MB** new install / **1.42 MB** update. Only the prebuilt-native debug-symbol advisory remains.
- [x] Deploy and HTTP/hash-verify all seven public policy/guide/archive HTML URLs; save and reread
      all five version-9 reviewer instructions. Keep the existing service video labeled as v8.
- [x] Finalize and reload both EN/ES listings with five ordered phone screenshots each; verify new
      exact-version-9 settings capture 04.
- [x] Verify app content: **all caught up**, **11 actioned declarations**, no new action required.
      Existing 13+ audience, FGS and Data safety remain unchanged; updated sign-in instructions
      are saved and recorded in review.
- [x] Publish version 9 to internal testing and verify **Available to internal testers**.
- [x] Confirm automated checks completed and the closed version-9 update is **In review** at
      **11:58:50 UTC**.
- [ ] Await Google approval and verify automatic closed publication. Managed publishing is off.
      The old October hold does not block this update; Google approval and production qualification
      remain external requirements.

The existing target/compile API 36 already meets the identified target-API requirement; the
Fragment constraint does not require a new platform level. The resolved graph also changes
Lifecycle/Tracing versions, so lifecycle/runtime checks remain relevant. Primary references:
[Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment),
[target API policy](https://support.google.com/googleplay/android-developer/answer/11926878),
[16 KB support](https://developer.android.com/guide/practices/page-sizes).

## Scheduling and service wording

Five- and ten-minute preferences enqueue coalesced, network-constrained JobScheduler work through
**inexact repeating alarms**. Fifteen-minute checks use periodic jobs. Manual mode removes the
repeating check timer; sending and pending operations retain their separate jobs. Existing push
uses direct IMAP IDLE and keeps its fallback check. Mode changes must not interrupt active
sending merely to reschedule checks. Manual refresh must not flush queued draft mutations under
its deadline; the ordinary background mutation path retains its existing behavior. These implementation details support review, while product
text should simply explain that background checks are approximate and Android may delay them.

No exact-alarm permission or new foreground-service type is introduced. The existing optional
`specialUse` IMAP listener applies only to eligible real accounts, with ongoing generic status
and user controls to stop it. Sample accounts never start it. Periodic polling is not represented
as a new persistent service or guaranteed instant delivery. Keep the FGS declaration accurate if
any lifecycle behavior changes. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

The existing battery-help flow opens general Android settings and does not request the direct
battery exemption. Recheck the final merged manifest. Broader OEM/Doze/reboot behavior remains
untested unless the final runtime evidence explicitly covers it.

## Listing, privacy and access

Use `fastlane/metadata/android/en-US/` and `es-ES/`, including `changelogs/9.txt`. Preserve the
unofficial-client disclosure, ordinary 13+ audience and truthful sample notices. Do not claim
all five-minute checks fire exactly, that the original phone ANR is conclusively solved, or that
local TLS/sample tests establish real UPM access.

Update only actual changed data flows. The retained mail declaration covers 13 collected types,
4 required/9 optional: Email address, User IDs, Emails and App interactions are required for
ordinary mail use. Blocking/reporting, account creation/deletion answers and Play Age Signals
handling stay as documented unless final inspection shows otherwise. Reconcile the newer
transitive AndroidX graph and final manifest rather than assuming the old forms are sufficient.
[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469).

Public [privacy](https://pportilla.github.io/simple_webmail_upm/privacy.html),
[acceptable use](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) and
[reviewer guide](https://pportilla.github.io/simple_webmail_upm/demo-review.html) must match the final
version labels and check-frequency behavior. Existing images/video retain their original artifact
provenance; new version-specific evidence requires an actual new capture. Public sample access
is independent of personal credentials. Never invent a reviewer password or suggest bypassing
an explicit Play restriction. [Review access](https://support.google.com/googleplay/android-developer/answer/15748846).

## Retained audience and data behavior

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

## Build, credentials and headless access

Keep the existing upload key and signing properties outside Git under `/home/gablo/.config/upm-mail/`.
Never print passwords, commit them or include them in an owner ZIP. Use `scripts/setup-signing.py
--existing /absolute/path/upload.jks --alias ALIAS` only to register an existing key; passwords are
entered at hidden terminal prompts. Do not generate a replacement for the key already in use.

```bash
export UPM_SIGNING_PROPERTIES_FILE=/home/gablo/.config/upm-mail/signing.properties
python3 scripts/build-release.py --version-code 9 --version-name 1.1.1
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

## Publication and production access

1. Signed validation and upload are complete. Version 9 is live for internal testers. The closed
   update and EN/ES listing changes were submitted at **11:53:16 UTC** and confirmed **In review**
   at **11:58:50 UTC**, with automated checks complete. Await approval and verify automatic
   publication; preserve the frozen artifact and signing identity.
2. Managed publishing is **off**, allowing approved changes to publish automatically. The October 28
   significant-change hold has expired; no new age-policy change or date hold was introduced.
3. Eligible testers can join the existing [closed test](https://play.google.com/apps/testing/io.github.pportilla.upmmail).
   Internal participants must leave internal testing before joining closed testing.
   [Track-switching rules](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en).
4. Current participation is **one opted-in tester**. Recruit and maintain at least **12 continuously
   opted-in testers for 14 days**, gather feedback and apply for production access. Merely listing
   tester addresses or using internal testing does not qualify. Production remains disabled.
   [Testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465).

Prior release hashes, scoped runtime results and the historical October hold are preserved in
[the version-8 archive](archive/RELEASE_STATUS_2026-10-25_V8.md).
