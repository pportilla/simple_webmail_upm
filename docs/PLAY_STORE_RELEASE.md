# Google Play release checklist

Updated on **2026-10-25 (Europe/Madrid)** for `io.github.pportilla.upmmail`. Version **3 (1.0.1)** is approved and available in closed Alpha, with policy declarations approved. The dashboard currently reports zero qualifying closed-test opt-ins. Optimized **version 5 (1.0.2)** passed automated and exact-APK sample, guardian and real-TLS checks and is **available to internal testers**. Its closed update was submitted; Console shows **Changes in review**, with automated pre-review checks still running at the last observation. Version 5 is not yet approved for closed distribution. Production requires the qualifying closed test and production-access approval. Exact evidence and limits are in [`RELEASE_STATUS.md`](RELEASE_STATUS.md).

## Confirmed identity and account setup

| Item | Required decision or evidence |
| --- | --- |
| Play release | The owner confirmed this is the **first Play release**. The app has been created in the existing developer account. Retain `io.github.pportilla.upmmail` as its package identity; the account-specific Console link is kept with the private release handoff. |
| Developer account | Confirmed: an existing, verified **personal** Play Console account. This app's Console dashboard explicitly requires **12 closed-test participants opted in continuously for 14 days** before applying for production access. Creating another account is unnecessary. |
| Public identity | Confirmed developer name: **zango**. Confirmed public support email: **p.portilla89@gmail.com**. Use the same contact in the privacy policy. Support website: `https://github.com/pportilla/simple_webmail_upm`. |
| Distribution | Confirmed: **free**, **worldwide**, **no ads**. Saved category: Communication. The current code has no identified ads, billing or analytics integration. |
| Intended audience | **All six age groups are saved:** 5 and under, 6–8, 9–12, 13–15, 16–17 and 18+, matching the owner's explicit intent. The version-3 release and declarations are approved. Users under 18 receive supervised mail with guardian assistance for younger children. Required owner-authorized declarations are recorded privately; local PIN/DOB controls do not independently verify legal parental consent. |
| Signing | Use a dedicated upload key for this first release and back it up securely. If a key has already been generated during release preparation, preserve it instead of generating another. Never use the repository's public `app/debug.keystore` for Play. |
| Review access | **Restricted access Yes and five instructions are saved/reloaded.** Public **Try sample mail** follows the normal age/terms gate; its exact signed APK passed the sample/guardian walkthrough without mailbox credentials. The [reviewer guide](https://pportilla.github.io/simple_webmail_upm/demo-review.html) is deployed and verified. Sample sends/reports are simulations. The owner declined external reviewer credentials; none were supplied or provisioned. |
| Privacy hosting | The [privacy URL](https://pportilla.github.io/simple_webmail_upm/privacy.html) and [acceptable-use rules](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) are hosted on GitHub Pages with HTTPS enforced. Revised policy/FGS evidence was verified at `80ae3b0dddcff055f97500bbbf7052ecb80a3688`, and the finalized reviewer guide at `de2e698d185ab69d142badba33a289b7bb013410`; URLs returned HTTP 200 with matching local hashes. The privacy URL is saved in Play Console. |
| Evidence | Version 3 (1.0.1) passed 673 release tests, lint with zero errors, bundle/signature/16 KB structural checks and 16 documented runtime checks. Its exact-AAB upgrade preserved the locked/paused profile; fresh sample/guardian checks and a separate exact-release local TLS FGS video passed. `RELEASE_STATUS.md` records scope and untested paths. Real UPM and external-provider testing remain unverified. |

The app is an unofficial native IMAP/SMTP client for existing UPM accounts, not the University's official app or a website wrapper. Keep the non-affiliation statement visible in both store descriptions. Current sign-in does not use Google OAuth; a Gmail OAuth client is not a publishing prerequisite.

## Version 5 optimization update

The code-5/1.0.2 release enables R8 optimization/obfuscation and resource shrinking,
using AGP 8.11.2/R8 8.11.26 and narrow mail-provider, MIME-constructor and hostname-alias keep
rules. The source recomputes the retry count from the immutable queued item after suspending
cleanup, avoiding an invalid optimized coroutine register assignment without changing retry
behavior. No coroutine optimization exclusion remains. Source changes are committed at
`dcfa34c8c300bb72aab420eac2780eafe7ee6f64`.

- [x] Verify the final signed code-5 bundle: **673 tests passed**, lint **0 errors / 41 warnings / 2 hints**,
      bundle/signatures and 16 KB structure passed; all 264 source-snapshot files unchanged.
- [x] Verify retained sample and real-TLS queued sending on the exact derived APK after upgrade.
      Independent IMAP inspection confirmed UTF-8 body and both attachments byte-for-byte; fresh
      TLS MIME forwarding passed and a same-CA certificate with the wrong hostname was rejected.
- [x] Complete nine exact-release local TLS checks, including IMAP MIME loading, SMTP forwarding,
      background IMAP IDLE arrival and stopping the service; preserve the continuous video.
- [x] Complete 15 fresh sample/guardian checks: offline reply, attachment export, drafts,
      search, under-13 setup, wrong PIN, exact outgoing approval and Home locking passed; new
      unreviewed replies remained hidden from the child. Sample arrival notifications were not retested.
- [x] Inspect the exact uploaded artifact: Google Play reports **High** DEX optimization and
      **98% optimization / 98% obfuscation / 98% shrinking**; new-install download **2.42 MB**.
      Release preview has zero errors and only the prebuilt native-debug-symbol advisory.
- [x] Publish version 5 internally: active/latest and available to internal testers, **25 October
      2026 at 11:56:26 Europe/Madrid**.
- [x] Verify the public version-5 reviewer guide and FGS evidence (HTTP 200 and matching hashes),
      save/reload the declaration and submit the closed update at **11:58:26 Europe/Madrid**.
- [ ] Await automated pre-review checks and Google review. Console shows **Changes in review**;
      managed publishing is off, so the accepted rollout is published after approval.

Embedded `r8.json`, mapping evidence, retained class-name measurements and total DEX byte
reduction answer different questions. Do not label a local byte reduction or class-name count
as Play's optimization percentage. Google documents the metadata/fallback rules in
[Android vitals](https://developer.android.com/google/play/vitals/code-optimization).
Final code 5 contains **4,404,944 uncompressed DEX bytes**, a **90.6096% reduction** from version 3.
The exact hashes, R8 8.11.26 metadata and independently observed Play scores are recorded in
`RELEASE_STATUS.md`. The internal release and public evidence are available; the closed update is
submitted with automated pre-review checks and review pending.

## Version 3 review access and completed release checks

The exact version-3 AAB is available internally and **approved/live in closed Alpha**, across
178 countries/regions with three configured tester addresses. Its policy declarations are approved.
The dashboard reports zero qualifying closed-test opt-ins; configuration alone does not start the
required test period. This is not production publication.

The normal setup screen now offers **Try sample mail** / **Probar correo de ejemplo**, labeled
**Sample · review@example.invalid**. The sample uses the normal repositories, mail engine and
age/guardian policy with a local backend. Adults can read, search, manage folders and drafts,
inspect a fictional attachment and simulate sending. The child path still requires guardian
contact approval, incoming-text review and exact outgoing-message approval. The reviewer
chooses a private guardian PIN during the test; no shared PIN or personal mailbox login is needed.

In the sample account's settings, **Receive sample mail in 5 seconds** exercises local arrival.
Adult instant mode refreshes it locally; Android, global and account notification settings still
apply. Manual mode requires inbox refresh. Sample mail creates no persistent server connection
or foreground service; supervised mail still hides unapproved text and suppresses content
notifications. Real accounts continue their own network activity. Removing and re-adding the
sample resets its local content. Full walkthrough source: [`demo-review.html`](demo-review.html).

- [x] Complete automated signed version-3 bundle checks: 673 tests, zero lint errors, signatures,
      packaging and 16 KB structural alignment passed. See `RELEASE_STATUS.md` for exact hashes.
- [x] Complete 16 documented runtime checks on the exact-AAB APK: sample entry, read/search,
      attachment export, local send/reply and eventual retry completion, restart persistence,
      arrival without PushService, reset, guardian incoming/exact outgoing approval and wrong PIN.
      This does not claim exhaustive mixed-account, locale or OS lifecycle testing.
- [x] Deploy and verify revised privacy/terms and exact-version-3 FGS video; save/reload the
      FGS declaration and EN/ES descriptions.
- [x] Finalize/deploy the reviewer guide and save/reload five English App access
      instructions identifying simulated operations and the independent live-mail path.
- [x] Complete App access, all six audience bands and Data safety. The version-3 closed release
      and policy declarations are approved; no declarations remain incomplete.
- [ ] Verify the closed test is actually running before counting qualification time. The confirmed
      production gate remains **12 continuously opted-in closed testers for 14 days**; internal
      testing and merely configuring tester addresses do not satisfy it.

## Access from a headless Ubuntu server

### Option A: the owner uses Play Console

1. Open [Play Console](https://play.google.com/console/) on the owner's computer. The owner completes Google sign-in, passkeys/2FA and any identity or agreement steps there.
2. Transfer the prepared signed `.aab` to that computer and use the Console to create/update the app, upload the bundle, supply declarations and manage test releases. No Google password needs to be sent to the server or chat.
3. If interactive remote assistance is needed, use a browser session the owner can actually view and control. A server-side headless Playwright session alone does not provide a usable login UI. Bind remote debugging/display services to localhost and reach them through an SSH tunnel; protect and later remove any saved browser session files. Do not expose a logged-in browser publicly.

### Option B: optional API access for later uploads

1. In a Google Cloud project, enable **Google Play Android Developer API** and create a dedicated service account.
2. In Play Console **Users and permissions**, invite that service account's email and grant access only to this app and the necessary release/listing functions. Start with testing permissions when only testing is intended. Billing, financial and account-wide administrator permissions are unnecessary for this mail app's upload workflow.
3. If a JSON key is needed, place it outside the checkout, for example `/home/gablo/.config/upm-mail/play-service-account.json`. Restrict its directory to the owner and its file to mode `0600`. Never commit it, paste it into chat, include it in an APK, or print it in build logs. Give the automation only the file path.
4. Cloud-project linking to the Play developer account is no longer required. Follow Google's current [API setup instructions](https://developers.google.com/android-publisher/getting_started).
5. For a new app, plan on Console app creation and the initial signed bundle upload. The publishing Edits API operates on an existing initialized app and does not replace Console agreements and other required setup. Google's [Edits documentation](https://developers.google.com/android-publisher/edits) describes this limitation using legacy APK terminology; new apps use AABs. Avoid simultaneous Console edits and API edits because they can invalidate one another.

GitHub access, Play API access, the Android upload key, and the UPM reviewer login are separate credentials. Store each only where it is needed. This repository's public source can be cloned without a GitHub token.

## Local release build

The release helpers keep signing material outside Git. Use `scripts/setup-signing.py --generate` only if a dedicated upload key has not already been created. To register an existing upload key, use `scripts/setup-signing.py --existing /absolute/path/upload.jks --alias ALIAS` and enter passwords at its hidden terminal prompts. The helper writes the signing configuration under `/home/gablo/.config/upm-mail/`; do not put secrets in a command line or paste them into chat.

Once the signing configuration exists:

```bash
export UPM_SIGNING_PROPERTIES_FILE=/home/gablo/.config/upm-mail/signing.properties
python3 scripts/build-release.py --version-code 5 --version-name 1.0.2
```

Version code `5` is the uploaded optimized update. Use a larger code for a new Play upload; preserve the frozen version-5 AAB. The helper runs release lint, release unit tests and the bundle build, checks embedded R8 mapping evidence and DEX size, then verifies the signature and rejects the public debug key. `--unsigned` is a separate validation option; its output is not ready for Play upload. `--check-optimization /absolute/path/app.aab` checks an existing bundle's R8 evidence without rebuilding or verifying signing. The release workflow can also produce an artifact when its signing secrets are configured, but does not publish the app.

## Bundle and compatibility

- [ ] Confirm `applicationId`, release version name, and a version code greater than every previously uploaded code. Do not infer the next Play code from a local build or GitHub run number.
- [ ] Build using the release signing procedure in the repository README. Check the release certificate against the intended upload certificate; reject debug-signed output.
- [ ] Verify `debuggable=false`, no `.debug` application-ID suffix, and the expected release metadata in the final AAB.
- [ ] Run the project's relevant unit tests and Android lint. Install an APK generated from the release bundle for device testing; preserve the AAB checksum, version and certificate fingerprint in the release record.
- [ ] API 36 is already configured. Since August 31, 2026, new phone apps and updates require Android 16/API 36 or newer. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878)
- [ ] Inspect the final bundle for native `.so` libraries. Google's current guide requires 16 KB support for API 35+ on 64-bit devices and gives February 1, 2027 as the update-blocking date. Java/Kotlin-only apps and dependencies already support these page sizes; native dependencies need inspection. [16 KB guide](https://developer.android.com/guide/practices/page-sizes)
- [ ] Enroll new apps in Play App Signing and upload a signed AAB. Preserve the upload key independently of Google's app-signing key. [Upload/signing requirements](https://developer.android.com/studio/publish/upload-bundle)
- [ ] Check migration behavior for any existing users. A Play install cannot update an installation signed with a different app-signing key. Do not recommend uninstalling without explaining loss of local drafts, queued mail and pending changes.

## Listing and Console declarations

The editable English and Spanish store text is in `fastlane/metadata/android/en-US/` and `fastlane/metadata/android/es-ES/`. It contains no placeholder developer/contact values. Review it against the final app. `changelogs/default.txt` describes this first release; replace it with version-specific notes for subsequent releases. The metadata folders alone do not publish anything or install/configure Fastlane.

Enter **zango**, **p.portilla89@gmail.com**, and the support website above in the relevant Console fields. Fastlane Supply's current [supported metadata fields](https://github.com/fastlane/fastlane/blob/master/supply/lib/supply.rb) are title, short description, full description and video; it does not consume a locale `contact_email.txt` or `contact_website.txt`. Contact fields are therefore recorded here for Console entry rather than in silently ignored files.

The current Data safety answers were saved and all 13 type dialogs were rechecked: required
**Email address, User IDs, Emails and App interactions**, with nine optional types. Read-state
synchronization makes App interactions required for ordinary real-mail reading; remote-image
loading is an additional optional path. The selected purpose is App functionality, with
non-ephemeral collection and no declared sharing under the documented user-initiated/disclosed
transfer exceptions. The local sample adds no off-device mail collection. This records verified
field answers, approved with the version-3 release. The optimization update changes build behavior,
not the app's declared data flows.

- [ ] Confirm the listing title, supported languages, short/full descriptions and release notes. Do not claim guaranteed instant delivery, end-to-end encryption, or encryption of every local file.
- [ ] Supply a store icon, feature graphic and actual phone screenshots meeting the current Console specifications. Use synthetic mailbox content and hide reviewer credentials. Do not use screenshots of private university correspondence.
- [ ] Complete the content-rating questionnaire, target audience, ads declaration, app access and all other forms shown by this app's Console dashboard. Do not claim a content rating before the questionnaire produces it.
- [ ] Check that the saved Console support email is `p.portilla89@gmail.com`, developer name is `zango`, and website is the repository URL above.
- [ ] Link the privacy policy in Console and in the app. It must identify the app/developer and contact, explain personal-data handling, security, retention and deletion, and be publicly accessible without geography restrictions or a PDF-only document. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [ ] Audit Data safety against the final build and the privacy policy. Account identifiers, sent mail and attachments, server searches, optional device contacts, remote image loading, local caches and export/sharing flows need consideration. Direct transmission to UPM is not automatically “no collection”; local-only handling and user-initiated sharing have distinct rules. Internal-only testing is exempt from the Data safety section, but closed/open testing and production are not. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
- [ ] Answer account-creation questions based on actual behavior. Adding an existing UPM mailbox currently configures a local client account; it does not create a university identity. Explain local removal/cache deletion separately from UPM's server-side account/data processes. Reassess if signup is added or linked later. [Account-deletion scope](https://support.google.com/googleplay/android-developer/answer/13327111)
- [ ] Complete **App access** using the implemented public sample route and accurate age/guardian/PIN instructions. The owner declined external mailbox credentials; do not invent them or mark live mailbox access as verified. Distinguish local simulations and notifications from server IMAP/SMTP/push behavior, and confirm the route on the submitted signed build. Google requires reusable, location-independent access to review functionality; acceptance of the supplied route remains subject to review. [Review-access requirements](https://support.google.com/googleplay/android-developer/answer/15748846)

## All-age audience: implemented controls and release validation

The owner's requested audience includes children under 13. Selecting all age groups is an assertion that the app was designed for, and is appropriate for, every selected group; it is not simply a distribution preference. Google's age guidance specifically cautions against typing/computing-heavy experiences for ages 5 and under, search for ages 6–8, and direct user interaction for ages 9–12. These are suitability considerations, not blanket bans on all email clients. The release provides a separate supervised interface with large controls, guardian review and assisted plain-text reading/writing, with runtime validation recorded in `RELEASE_STATUS.md`. The version-3 release and declarations are approved; this does not independently verify parental consent or a child's provider-account eligibility. An IARC rating is a separate result. [Target-audience guidance](https://support.google.com/googleplay/android-developer/answer/9867159)

**Policy requirements:** targeting any children invokes Families. Children must receive an online-safety reminder before exchanging freeform content, and adult action is required before exchanging personal information. Additional social features need adult management controls. Child-accessible content must be suitable, sensitive-data handling disclosed, and APIs/SDKs compatible with child use. Certain device identifiers must not be transmitted for children or unknown-age users. The policy also requires applicable children's-privacy-law compliance. A mixed audience does not waive these requirements. No ads are implemented. [Families full policy](https://support.google.com/googleplay/android-developer/answer/9893335)

**Implementation:** the release now asks for a neutral birth date before mailbox setup and retains only an age band. Unconfigured access is blocked. Under-18 users receive a separate supervised interface; an adult completes a separate date-of-birth check, provider-use acknowledgment, acceptable-use acceptance and local guardian PIN setup before credentials are used. This is local access control, not independently verified parental identity or legal consent.

The guardian manages exact approved/blocked addresses, reviews each incoming message before the child can see it, and approves the exact recipients and content of each outgoing message. Incoming approval is tied to message content; an allowed sender alone is insufficient because addresses can be spoofed. Supervised reading and writing use plain text with no attachments, external links, remote resources, address-book import, exports or search. Supervised drafts are local. Repository and network checks cover queued sends and profile changes. Guardian sessions lock on background/Back; supervised push and mail-content notifications are disabled. Periodic/manual sync can retrieve mail for guardian review while communication is enabled.

Children can block a sender and ask a guardian to review a concern. That request is stored locally. The adult dashboard separately offers a provider-report composer: the adult chooses the destination, types the concern, previews the exact payload and approves sending. Reports include the sending account identity and normal email headers. Original correspondence and attachments are not automatically included. Supervised reports need a live guardian session; closing it can pause queued delivery. Report submission is not proof that a provider has acted. Blocking or revoking approval cannot recall a message already accepted by the mail server; an interrupted send can have an uncertain outcome and must not be retried as if delivery were impossible.

The public acceptable-use source is `docs/acceptable-use.html`; the same substantive rules require acceptance in the app. The website and native privacy policy describe local age/PIN data, approval records, reporting and retention. Confirm that the deployed policy/terms match the final build before submitting.

### Saved Console answers for the current build

These facts support the completed declarations. Recheck them against actual behavior when updating the app; saved forms are not Google approval.

| Question or topic | Current supported answer |
| --- | --- |
| Intended audience | All six listed age groups are selected and submitted. The under-18 path is supervised, with adult assistance for younger children. |
| App category | **Communication**; a native email client. |
| Ads / in-app purchases | **No / No** in the inspected build. No advertising SDK or billing integration was found. |
| User interaction / exchange of user-created content | **Yes**: direct email, multiple recipients, freeform text and file attachments. No public feed, random matching or anonymous chat-discovery feature was found. |
| Sharing personal information | **Yes**: sender/recipient addresses, optional names, message text and attachments can be transmitted. No developer-operated mail relay does not mean no transmission. |
| Parental controls / adult action / safety reminder | **Yes, implemented:** separate adult setup and PIN, guardian contact/message controls and a safety reminder. Do not describe the PIN/date checks as independently verified legal parental consent. |
| User blocking / abuse reporting | **Yes:** local sender blocking, local concerns for guardian review, and an adult-controlled provider report with explicit destination/payload confirmation. Local concerns are not automatically delivered to a provider or developer. |
| Precise location / camera / microphone features | No corresponding runtime permission or capture feature was found. A user can still manually attach a file containing personal information; do not misdescribe all attachments as free of such data. |
| Account creation | **No new provider identity is created in the app**; real mail signs into an existing mailbox. Version 3 also provides a local sample without mailbox credentials. Age/guardian controls still apply; the privacy policy is accessible without login. |
| Children's data / Families compliance | Authentication and mail data are involved. Age bands/PIN verification/approval records stay local. **Do not answer “no children's personal data”** based only on the absence of ads, analytics or a developer backend; audit the exact final data flows. |

### Final validation before an all-age submission

1. Verify precredential blocking, local age derivation, separate adult setup and required acceptable-use/provider acknowledgments. Do not retain or transmit the entered birth dates. Review PIN throttling, process death, background locking and app-data reset behavior; reset must not expose previously stored mail through an unrestricted route.
2. Exercise both approved and rejected contacts/messages. Check spoofed/display-name sender cases, changed content after approval, reply/forward recipients, exact-message outgoing approval, queued retries, revocation and offline recovery. Confirm drafts never upload before supervised authorization. Check notifications, incoming share/mailto requests, search, attachments and external actions cannot bypass the child interface. Run the focused safety tests plus the existing release suite and inspect the actual app.
3. Verify local concerns can be reviewed/resolved by a guardian, sender blocking affects future access, and provider reports preview only deliberately supplied data. Google's UGC policy requires terms acceptance and proportionate moderation, including blocking for one-to-one interaction and reporting for identified communities. Do not claim that local guardian moderation controls mail on another device or that a queued report has been delivered. [UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en-GB), [moderation guidance](https://support.google.com/googleplay/android-developer/answer/12923286)
4. Match the policy to actual child data handling and provider eligibility. Determine the applicable legal obligations for the intended countries and operator/provider roles; the local PIN and adult declaration are not a certificate of legal parental consent. The FTC guidance links the current COPPA rule and distinguishes notice, consent, access and deletion responsibilities. [FTC guidance](https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions)
5. Check the assisted experience for the youngest requested age groups: clear safety wording, large accessible controls, adult review and joint reading/writing, with no unrestricted search or communication. Capture the final supervised UI and update reviewer instructions, listings, Data safety and IARC answers. Test results support the implementation; only Google's review establishes Play acceptance.

An unpublished/internal release is a testing step. It does not replace the required closed test, resolve unavailable reviewer mailbox access, or establish Google approval. The current code addresses the earlier missing controls; keep any failing tests or unavailable evidence explicit rather than repeating those controls as unimplemented work.

### Contacts and provider-account details

`READ_CONTACTS` is not categorically prohibited by the cited Families policy. In the adult interface it performs local email-address suggestions after Android permission; the entire address book is not uploaded. Selected recipients leave the device when mail is sent. The supervised interface uses approved addresses and does not import device contacts; verify that it never prompts for this permission. Google's newer contacts policy requires an access justification when targeting API 37+, with enforcement from **27 January 2027**; declarations began appearing in September 2026. This build targets API 36, so do not misstate that future API-specific deadline as an existing rejection. Plan a minimum-scope picker migration or supported core-function justification before targeting 37. [Contacts permission guidance](https://support.google.com/googleplay/android-developer/answer/16935362)

UPM's current account guidance ties mail to University affiliation/enrollment; it does not establish a general child account offering. Its personal-mailbox terms prohibit transfer to third parties. Confirm the intended children's own permitted access. If the owner later supplies reviewer/test access, it must be explicitly authorized. A dedicated custom TLS IMAP/SMTP test mailbox is technically supported, subject to full-function review checks; the owner has currently declined this credential route. Do not use an adult's shared personal inbox as a child-access or review workaround. A custom test mailbox does not establish UPM account eligibility or verify the UPM presets. [UPM account eligibility](https://soporte.upm.es/hc/es/articles/23480559826077-Correo-Electr%C3%B3nico-C%C3%B3mo-disponer-de-cuenta-de-correo-electr%C3%B3nico), [UPM mail terms](https://soporte.upm.es/hc/es/articles/37381588653853-Correo-Electr%C3%B3nico-T%C3%A9rminos-y-Condiciones-de-Uso)

The separate Child Safety Standards declaration currently applies to Social/Dating-category apps and anonymous/random chat apps. A normal Communication-category email client does not automatically fall into those categories; the Families communication duties above still apply. Reassess if category/functionality changes or Console requests it. [Child Safety Standards scope](https://support.google.com/googleplay/android-developer/answer/14747720)

## Foreground service: declaration and evidence

The exact-version-5 [demonstration video and guide](https://pportilla.github.io/simple_webmail_upm/foreground-service/) are public and HTTP/hash verified at `gh-pages` commit `6323e00`. The 109.731-second local TLS fixture demonstrates real IDLE/notification behavior, including opt-in, background arrival and stopping the service. The updated `specialUse` description/video were saved, reloaded and submitted with the version-5 closed update; approval is pending. This does not establish UPM compatibility. Built-in sample arrivals are a separate local simulation and never start this service. The approved [version-3 evidence](https://pportilla.github.io/simple_webmail_upm/foreground-service/v3/) and earlier [version-2 evidence](https://pportilla.github.io/simple_webmail_upm/foreground-service/v2/) are archived separately.

The manifest declares `FOREGROUND_SERVICE_SPECIAL_USE`, `PushService` uses `specialUse`, and its subtype describes an IMAP IDLE connection for incoming mail. Push is **off by default**, available only to the adult profile, and starts only after the user selects **Instantly (push)** with a real mailbox configured. Sample accounts are excluded. A foreground notification reports generic connection status without account identifiers. Users can stop push by changing **Settings > Notifications > Check for new mail** from **Instantly (push)** to a periodic/manual mode, or by removing all real accounts. Switching to a supervised profile also stops the service and its live connections; guardian unlock does not enable it. Exact-release opt-in and manual stopping were demonstrated; broader restart/device coverage remains separate.

Google reviews `specialUse` explanations and requires a valid use case, user benefit, perceptibility/control, necessity and limited duration. It is not an automatic exemption from background-work rules. [Service type documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646)

**Functionality explanation supporting the saved declaration:**

> UPM Mail receives mail for existing real mailboxes through direct IMAP connections. When an adult user enables Instant push, the foreground service maintains an IMAP IDLE connection to each connected real inbox and refreshes it when the server reports a change. An ongoing notification shows generic connection status. Users can stop this service by choosing periodic or manual checking, removing all real accounts, or switching to supervised mail. Sample accounts and supervised profiles cannot use this service. Periodic checks, queued sending and search indexing have separate scheduled jobs.

**Interruption impact supported by the recorded behavior:**

> Deferring or interrupting the IDLE connection delays notification of incoming mail until a reconnection, a scheduled check or the next foreground refresh. The user can choose those less frequent checking modes, but they do not provide the same notification timing as an active IDLE connection. The connection remains active while push is enabled and real accounts exist; it pauses network work offline and reconnects when connectivity returns.

**Saved type rationale:** the optional listener waits for future IMAP changes of unknown timing; it is not a finite transfer or device-to-device messaging-continuity service. Scheduled polling remains an alternative with different timing. The app implements direct IMAP without FCM. The updated explanation and version-5 public video are submitted for the current update; future implementation changes must still match the declaration.

- [x] Review the final manifest subtype against the verified local TLS listener behavior; the submitted explanation and exact video are recorded in `release-output/optimized-release-5/foreground-service-console.txt` and `foreground-service-saved.txt`.
- [x] Verify the ongoing notification explains the active connection and how to stop it in the exact-release video.
- [ ] Broader runtime coverage remains: Android 14–16 restart/reboot, denied notifications, last-account removal and network-loss combinations. Focused automated safety tests passed, but the final device walkthrough does not establish this full matrix.
- [x] Record the exact version-5 release using a configured local TLS mailbox: user opt-in, ongoing notification, background synthetic arrival, message opening and manual stopping. Service/ongoing-notification absence was verified afterward.
- [x] Include English captions, release identity and relevant settings; publish without credentials or private mail and verify the public URL/hash.
- [x] Save/reload the Console FGS declaration with functionality, delay/interruption impact, use-case choice and version-5 video link; the updated declaration is submitted with the closed update. [Declaration and video requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

**Acceptance is not guaranteed.** If the declaration is rejected, inspect Google's specific reason and revise the implementation or evidence. Do not relabel the service as a different type solely to bypass review. A release with periodic/manual checking is an implementation option if the persistent service cannot be justified, but its code, manifest, listing and privacy declarations would need to be updated together.

## Battery settings

The release no longer declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or launches the direct per-app exemption request. Its background-delivery help opens Android's general battery-optimization settings, with a general system-settings fallback. This removes the direct-request permission and its declaration rationale from the release. Android can still delay background mail when power restrictions apply. [Android background-power guidance](https://developer.android.com/training/monitoring-device-state/doze-standby)

- [x] Confirm the final merged release manifest lacks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`release-output/demo-release-3/manifest.xml`).
- [ ] Verify the help action opens general Android settings and gracefully handles devices without that screen.
- [ ] Test manual/periodic mail access with power restrictions enabled. Selecting push remains optional and does not imply guaranteed delivery timing.

## Testing, review and launch

1. Completed validation is recorded in `RELEASE_STATUS.md`: 673 automated tests, 15 exact-version-5 sample/guardian checks and nine local TLS checks, including the foreground-service demonstration. Live UPM login and external-provider delivery remain unverified. Broader device, locale and power-condition checks above are additional coverage, not incomplete Console declarations.
2. Version 3 (1.0.1) is approved/live in closed Alpha. The optimized version-5 replacement passed automated and emulator checks and is available internally. Its closed update was submitted; Console shows Changes in review while automated pre-review checks complete. Preserve the earlier release evidence.
3. Eligible testers can join the live [closed test](https://play.google.com/apps/testing/io.github.pportilla.upmmail). Existing internal participants must first leave the internal test, then opt into the closed test. Address any later review or pre-launch findings on the optimized update. [Google's track-switching guidance](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-en)
4. Production access remains locked until at least **12 genuine testers have opted into the closed test continuously for 14 days**. Gather feedback and apply for production access afterward. Three configured addresses and internal participation do not satisfy this requirement. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
5. Preserve the uploaded AAB, version, signing fingerprint, recorded declarations, evidence and actual track state. The private owner handoff contains those records. Production is not published; successful submission does not establish Google approval.

Recheck linked requirements before a future submission or production-access application.
