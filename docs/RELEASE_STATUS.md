# UPM Mail release preparation status

Prepared **24 October 2026** for the first Google Play release by **zango**.
Support and privacy contact: **p.portilla89@gmail.com**.

This records source preparation, validation and an unpublished internal-test draft. It does not
record a production submission, Google policy approval or a live Play release. See [the release checklist](PLAY_STORE_RELEASE.md)
for the remaining Console and device steps.

## Source and release identity

- Repository: `pportilla/simple_webmail_upm`.
- Review branch: `codex/play-store-release`.
- Base branch: `claude/android-upm-webmail-app-3p882d`.
- Base commit: `6fd15a425030d200b25893f9279c07fd5573b9df`.
- Base Git tree: `ffd666258c4627faa99664f5b797bdf0a31a16be`.
- The reviewed changes are recorded by the commit containing this report. The artifacts below
  were built from the modified working tree before the review commit was created; the base
  commit alone does not contain the release preparation changes.
- Release package: `io.github.pportilla.upmmail`; version code **1**; version name **1.0.0**.
- Minimum Android API **26**; target/compile API **36**; release is not debuggable.
- Confirmed distribution intent: **free**, **worldwide**, **no ads**, with an intended audience
  that **includes children under 13**. The owner has an existing, verified personal Play Console
  account and has created the app record. The intended child audience introduces Google Play
  Families and children's-data requirements; compliance has not yet been established, and this
  report does not establish readiness for a child-directed release. This app's Console requires
  **12 closed-test participants opted in continuously for 14 days** before applying for production
  access.

## Completed validation

- **611 release unit tests passed**: zero failures, errors or skipped tests. Coverage includes
  plaintext account rejection before credentials/network access, persisted IMAP/SMTP/push
  connections, account removal, mail protocol behavior, search, database migrations and UI logic.
- Release lint completed with **0 errors, 39 warnings and 2 hints**. Warnings remain for direct
  battery-optimization exemption, dependency freshness, plural/resource/style issues and other
  maintenance suggestions. The `BatteryLife` warning is material to review: retain the direct
  request only with evidence supporting the core IMAP notification use case. See the checklist's
  exemption and foreground-service sections; Google approval has not been established.
- The signed AAB passed **bundletool 1.18.3 validation** and JAR signature verification.
- Signing configuration checks rejected missing credentials, partial credentials and an external
  copy of the public debug certificate. Explicit unsigned validation succeeded without using
  signing credentials. Both the Gradle configuration and release helper reject the debug key.
- The bundle declares **16 KB page alignment**. All four ABI copies of the bundled
  `libandroidx.graphics.path.so` have ELF load-segment alignment `0x4000`.
- Release and debug APKs passed **16 KB zip alignment** checks and APK signature verification.
- Language splitting is disabled in the AAB so its English/Spanish language picker works even
  when the alternate language was not selected during installation.
- Native English/Spanish privacy policy, XML resources and changed-file whitespace were checked.
  The policy is reachable before login and from Settings.
- A signed universal APK generated from the exact final AAB was installed and cold-launched on
  Android 16/API 36. The release process remained alive with no app-scoped fatal exception, crash
  or ANR. The offline pre-login policy showed zango, the support email and the expected disclosures;
  both server-security dropdowns exposed only SSL/TLS and STARTTLS. The universal APK also passed
  signature and 16 KB zip-alignment checks. Runtime evidence is retained locally under
  `release-output/runtime-smoke/`. This emulator used 4 KB runtime pages: 16 KB support was checked
  structurally, not exercised on a 16 KB runtime device.
- Store screenshots exercise the running Android UI using fictional, offline local data. They
  are not evidence of a successful real UPM login, SMTP send or live push notification.

## Local artifacts

These generated files are ignored by Git. Preserve the exact AAB used for upload and its checksum.
Do not rebuild with the same version code after uploading it without checking Console state.

| Artifact | Local path | SHA-256 |
| --- | --- | --- |
| Signed Play AAB | `/home/gablo/mail_upm/app/build/outputs/bundle/release/app-release.aab` | `0c69de663ef38875da84faaddce57fc1a7dce4149ebaec43e117a130ad8e2c90` |
| Signed release APK for device testing | `/home/gablo/mail_upm/app/build/outputs/apk/release/app-release.apk` | `6e7045ea8981162c9def65fd2a43109b1c48407e5b3cc36d3d2b3417b64ca0f7` |
| Signed universal APK generated from the final AAB and smoke-tested | `/home/gablo/mail_upm/release-output/runtime-smoke/universal.apk` | `fa312c1917c7975ce868fc1e2810311beba942fafcd25539f142ab23938bf050` |
| Debug APK used for screenshot fixtures | `/home/gablo/mail_upm/app/build/outputs/apk/debug/app-debug.apk` | `aba0a48f7448955fdf0e4f2871e20e26e7c021f3341d531072a67cd3180e18e7` |

The release upload certificate SHA-256 is:

```text
B5:CE:F6:46:1E:E2:98:48:17:C9:7B:D8:69:21:BA:EC:10:4E:F9:44:B5:47:E4:A0:D1:DB:7D:D1:CA:01:95:48
```

The private upload key is at `/home/gablo/.config/upm-mail/upload-key.jks` and its signing
configuration at `/home/gablo/.config/upm-mail/signing.properties`, both mode `0600` and outside
the repository. Back up the key and its passwords securely. Neither file belongs in the PR,
release kit, browser session or public artifacts. The certificate fingerprint above is public
information, not the private key. Google Play App Signing's eventual distribution certificate
may differ from this upload certificate.

The handoff ZIP has been assembled at
`/home/gablo/mail_upm/release-output/upm-mail-1.0.0-play-kit.zip`. It contains release artifacts,
public signing information, store assets and documentation; it excludes private signing
material and browser credentials. Refresh its documentation after final handoff-status updates.

## Listing assets and privacy

- English and Spanish listing text: `fastlane/metadata/android/en-US/` and `es-ES/`.
- Per locale: a **512 × 512** icon, a **1024 × 500** feature graphic, and five **1080 × 1920**
  phone screenshots showing inbox, message, compose, privacy policy and sign-in.
- Screenshots are unmodified `adb exec-out screencap -p` output from the debug app on an
  Android **API 36** emulator. Each locale's `images/phoneScreenshots/CAPTURE.json` records
  dimensions, timestamps, fixture information and SHA-256 checksums.
- Screenshot messages and identities are fictional and use reserved `.invalid` domains.
  There is no real mailbox password in the fixture. The capture helper refuses physical
  devices, the release package and an existing non-demo account.
- Artwork derives from the existing launcher envelope vector. Editable SVGs and regeneration
  instructions are in [store-artwork](store-artwork/README.md).
- The public policy source is [privacy.html](privacy.html). It identifies zango and the confirmed
  contact, explains direct mail-server transmission, TLS, password encryption, private local
  storage, optional contacts/remote content, background work and local versus provider deletion.
- The owner authorized public policy hosting. The policy was deployed separately to the
  `gh-pages` branch at commit `e9bfe9167eff70eab1a8e0c822e13b2ae82b61ee`, with GitHub Pages configured
  to serve that branch with HTTPS enforced. The public policy at
  [pportilla.github.io/simple_webmail_upm/privacy.html](https://pportilla.github.io/simple_webmail_upm/privacy.html)
  was verified to return HTTP 200 and the reviewed content. Entry in Play Console remains to be
  confirmed. A manual Pages workflow is also prepared.
- The owner-authorized internal-test release draft was saved in Play Console with the signed
  version **1 (1.0.0)** AAB above and English/Spanish release notes. Console confirmed that the
  changes were saved. No rollout was started; the draft is unpublished.

## Remaining release gates

**Child-audience blocker:** the current app is a free-form email client. It does not yet implement
a safety reminder before communication, adult verification/authorization, parent-managed child
controls or content/contact safeguards for a supervised child experience. The owner's confirmed
under-13 audience cannot be treated as a completed Console checkbox. The existing signed build
may be evaluated in the authorized unpublished/internal test draft, but it is not ready for a Families
or child-directed submission. See the Families assessment in the release checklist before
planning the required product changes and provider/legal review.

1. Obtain an authorized reusable review/test mailbox: UPM test/service access explicitly permitted
   by its provider, or a dedicated custom TLS IMAP/SMTP mailbox with full-feature reviewer access.
   Include synthetic content and English instructions. Do not transfer a private personal inbox
   or place review passwords in Git. A custom review server does not verify the real UPM integration.
2. Test real UPM sign-in, send/receive/reply/forward, attachments, draft sync, outbox recovery,
   search, removal, contacts permission, image controls and notifications on supported devices.
   Test Android background restrictions, reboot and network loss with push enabled and disabled.
3. Record and host the foreground-service demonstration video. Complete the `specialUse`
   declaration and substantiate the direct battery-exemption rationale; the code and checklist
   alone do not establish policy acceptance.
4. Enter the verified live privacy-policy URL in Play Console and keep the hosted and native
   policies synchronized with future data-handling changes.
5. Assess and satisfy the Families and children's-data requirements for the confirmed audience,
   including any required product, consent, privacy and communication controls. Complete
   app-access, Data safety, content-rating, target-audience and other Console forms.
   Direct transmission to the configured mail provider must be considered in Data safety;
   absence of a developer backend is not a blanket declaration of no collection.
6. Inspect the saved unpublished internal-test draft and any available validation/pre-launch
   reports. Then arrange the Console-required closed test with 12 participants continuously opted
   in for 14 days, gather feedback and apply for production access when eligible.
7. Review the exact listing/track changes with the owner before a review submission or rollout.
   The draft PR, approved policy hosting and unpublished internal-test preparation do not authorize
   merging the source or releasing the app to testers or production.
