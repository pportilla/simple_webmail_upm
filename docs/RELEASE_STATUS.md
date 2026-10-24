# UPM Mail release status

Updated **24 October 2026** for **zango**. Support/privacy: **p.portilla89@gmail.com**.

The signed **version 3 (1.0.1)** build has passed automated validation and **16 documented device
checks**. **Version 3 is available to internal testers, and the closed Alpha release is in Google
review.** All 11 app-content declarations are actioned, now in review, with none remaining. Production is not
published; Google review and the required closed test still precede production access.
See [the release checklist](PLAY_STORE_RELEASE.md).

## Source and artifact identity

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

## Completed validation

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

A separate **97.918-second** exact-version-3 [foreground-service demonstration](https://pportilla.github.io/simple_webmail_upm/foreground-service/)
shows verified local TLS 1.3 authentication, user-selected IMAP IDLE, ongoing status, background
synthetic arrival, opening mail and manual stopping. Service/ongoing-notification absence after
stopping was verified. The signed app and TLS checks were unchanged; a temporary CA was trusted
only by the disposable test emulator. Public-safe evidence is in
`release-output/demo-release-3/push-evidence/`. This used a configured local TLS mailbox, not the
built-in sample. Real UPM login and external-provider delivery remain unverified.

## Current Play Console state

| Area | Last confirmed state |
| --- | --- |
| Internal track | **Version 3 (1.0.1) available to internal testers / Not reviewed**, confirmed at 13:40 UTC (15:40 Madrid) on 24 October 2026. Three tester addresses are selected. [Internal opt-in](https://play.google.com/apps/internaltest/4701581542823930298) requires an eligible tester account. |
| Closed track | **Alpha version 3 is in review**, across 178 countries/regions with three configured testers. Publishing overview confirms 16 changes sent for review. Managed publishing is off, so approved changes publish automatically. No qualifying closed-test clock is confirmed. |
| Production | Not published; the verified personal account requires **12 closed testers continuously opted in for 14 days**, followed by a production-access application. |
| App access | Restricted-access answer **Yes** and five instruction entries saved/reloaded, using the tested public sample route and reviewer-created guardian PIN. The public guide is deployed and verified. The owner declined external reviewer credentials; none were provisioned. |
| Audience/Families | All six age bands, including **5 and under**, are saved. Required owner-authorized declarations were completed and recorded privately. Google assessment is in progress; local DOB/PIN controls are not independent legal-consent verification. |
| Data safety | Saved and submitted with the release changes. All **13** type dialogs were rechecked: **4 required / 9 optional**, App functionality, non-ephemeral. Required types are Email address, User IDs, Emails and App interactions; automatic read-state sync makes the last required. No sharing is declared under documented transfer exceptions; encrypted transit, existing external accounts, no app-account creation and no deletion-request mechanism remain declared. Google approval is not yet recorded. |
| Store/policies | Category Communication, developer/support details, existing assets and revised v3 EN/ES descriptions are saved. Privacy/terms, reviewer guide and version-3 FGS guide/video are public and HTTP/hash verified. |
| Foreground service | Version-3 `specialUse` declaration was saved and reloaded with its public exact-release video. This is not Google approval. Built-in sample arrivals are not real IMAP IDLE evidence. |

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
audience or Families approval. The submitted version-3 changes remain under Google review.

After closed-test approval and availability, eligible testers can use the
[closed-test opt-in](https://play.google.com/apps/testing/io.github.pportilla.upmmail). The link's
presence does not establish availability before approval. Anyone enrolled internally must first
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

1. Await Google's review and address any response. Approved closed-track changes are configured
   to publish automatically; verify availability before starting the qualifying test.
2. Recruit and maintain at least 12 closed-test participants for 14 continuous days, gather
   feedback and apply for production access. Internal participation and three configured addresses
   do not fulfill this gate; existing internal testers must switch tracks as described above.
