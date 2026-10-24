# Google Play release checklist

Prepared on **2026-10-24** for `io.github.pportilla.upmmail`. This is a preparation checklist, not a record of a completed Play submission. Complete the unresolved items against the actual developer account and final signed bundle before submitting.

## Confirmed identity and remaining account setup

| Item | Required decision or evidence |
| --- | --- |
| Play release | The owner confirmed this is the **first Play release**. The app has been created in the existing developer account. Retain `io.github.pportilla.upmmail` as its package identity; the account-specific Console link is kept with the private release handoff. |
| Developer account | Confirmed: an existing, verified **personal** Play Console account. This app's Console dashboard explicitly requires **12 closed-test participants opted in continuously for 14 days** before applying for production access. Creating another account is unnecessary. |
| Public identity | Confirmed developer name: **zango**. Confirmed public support email: **p.portilla89@gmail.com**. Use the same contact in the privacy policy. Support website: `https://github.com/pportilla/simple_webmail_upm`. |
| Distribution | Confirmed: **free**, **worldwide**, **no ads**. Suggested category: Communication. The current code has no identified ads, billing or analytics integration. |
| Intended audience | The owner explicitly confirmed **all ages, including children under 13**. This intent includes the Console groups 5 and under, 6–8, 9–12, 13–15, 16–17 and 18+. The current app has not been designed or validated for every group and does **not** yet meet the identified Families requirements. Preserve that intent; do not silently substitute a 13+ audience or certify the current build as suitable for all ages. See the audience assessment below. |
| Signing | Use a dedicated upload key for this first release and back it up securely. If a key has already been generated during release preparation, preserve it instead of generating another. Never use the repository's public `app/debug.keystore` for Play. |
| Review account | Use an explicitly authorized UPM test/service mailbox **or a dedicated custom TLS IMAP/SMTP test mailbox** that allows reviewers to exercise all app functionality. Include synthetic mail/attachments and reproducible English login/server instructions. UPM's personal-account terms prohibit transfer to third parties; a spare personal account is not sufficient evidence of permission to give reviewers access. See the provider-account note below. |
| Privacy hosting | **Live and verified:** [public privacy policy](https://pportilla.github.io/simple_webmail_upm/privacy.html). GitHub Pages serves the reviewed `docs/privacy.html` content with HTTPS enforced; HTTP 200 and exact content were checked. The same policy is readable offline in the app. Enter the URL in Play Console. |
| Evidence | Ten English/Spanish screenshots were captured from the actual Android app with fictional local mailbox data; see the inventory in `RELEASE_STATUS.md`. Real UPM mail testing and the foreground-service demonstration video remain required. Do not represent synthetic screenshot fixtures as live mail testing. |

The app is an unofficial native IMAP/SMTP client for existing UPM accounts, not the University's official app or a website wrapper. Keep the non-affiliation statement visible in both store descriptions. Current sign-in does not use Google OAuth; a Gmail OAuth client is not a publishing prerequisite.

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
python3 scripts/build-release.py --version-code 1 --version-name 1.0.0
```

Use a different version code if `1` has already been uploaded by the time this is run. The helper runs release lint, release unit tests and the bundle build, then checks its signature and rejects the public debug key. `--unsigned` is a separate validation option; its output is not ready for Play upload. The release workflow can also produce an artifact when its signing secrets are configured, but does not publish the app.

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

- [ ] Confirm the listing title, supported languages, short/full descriptions and release notes. Do not claim guaranteed instant delivery, end-to-end encryption, or encryption of every local file.
- [ ] Supply a store icon, feature graphic and actual phone screenshots meeting the current Console specifications. Use synthetic mailbox content and hide reviewer credentials. Do not use screenshots of private university correspondence.
- [ ] Complete the content-rating questionnaire, target audience, ads declaration, app access and all other forms shown by this app's Console dashboard. Do not claim a content rating before the questionnaire produces it.
- [ ] Check that the saved Console support email is `p.portilla89@gmail.com`, developer name is `zango`, and website is the repository URL above.
- [ ] Link the privacy policy in Console and in the app. It must identify the app/developer and contact, explain personal-data handling, security, retention and deletion, and be publicly accessible without geography restrictions or a PDF-only document. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [ ] Audit Data safety against the final build and the privacy policy. Account identifiers, sent mail and attachments, server searches, optional device contacts, remote image loading, local caches and export/sharing flows need consideration. Direct transmission to UPM is not automatically “no collection”; local-only handling and user-initiated sharing have distinct rules. Internal-only testing is exempt from the Data safety section, but closed/open testing and production are not. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
- [ ] Answer account-creation questions based on actual behavior. Adding an existing UPM mailbox currently configures a local client account; it does not create a university identity. Explain local removal/cache deletion separately from UPM's server-side account/data processes. Reassess if signup is added or linked later. [Account-deletion scope](https://support.google.com/googleplay/android-developer/answer/13327111)
- [ ] Enter authorized review credentials under **App access** with English instructions. They must remain reusable, accessible regardless of location and able to reach all functionality without an unavailable OTP/2FA step. Use UPM-authorized test access or the app's custom TLS IMAP/SMTP setup with an authorized test mailbox; verify folders, send/receive, attachments and IDLE with that server. A custom server does not establish that the real UPM integration works. [Review-access requirements](https://support.google.com/googleplay/android-developer/answer/15748846)

## All-age audience: current release blocker

The owner's requested audience includes children under 13. Selecting all age groups is an assertion that the app was designed for, and is appropriate for, every selected group; it is not simply a distribution preference. Google's age guidance specifically cautions against typing/computing-heavy experiences for ages 5 and under, search for ages 6–8, and direct user interaction for ages 9–12. These are suitability considerations, not blanket bans on all email clients. The current text-heavy university mailbox experience has not demonstrated that fit. An IARC rating is a separate result and does not establish the intended audience. [Target-audience guidance](https://support.google.com/googleplay/android-developer/answer/9867159)

**Policy requirements:** targeting any children invokes Families. Children must receive an online-safety reminder before exchanging freeform content, and adult action is required before exchanging personal information. Additional social features need adult management controls. Child-accessible content must be suitable, sensitive-data handling disclosed, and APIs/SDKs compatible with child use. Certain device identifiers must not be transmitted for children or unknown-age users. The policy also requires applicable children's-privacy-law compliance. A mixed audience does not waive these requirements. No ads are implemented, so an advertising age screen or certified advertising SDK is not a solution to this app's actual gaps. [Families full policy](https://support.google.com/googleplay/android-developer/answer/9893335)

**Assessment of this code, not a Google approval decision:** email's core function is exchanging freeform content, so the Families social-app requirements appear relevant even with the Play category set to Communication. The source contains no age screening, adult verification, parental authorization, child-mode controls or online-safety reminder. `ComposeViewModel.send()` queues arbitrary recipient/message content; the repository and background outbox have no guardian authorization checks. `READ_CONTACTS` is requested when a recipient field gains focus. Remote resources default to blocked, but any user can enable them for a message, sender or all mail. Links and attachments can open external content. None of these existing controls establishes suitability for children.

### Truthful draft Console answers for the current build

Use these facts when the corresponding question appears; inspect the actual wording before saving. Do not invent a positive compliance attestation to make a form complete.

| Question or topic | Current supported answer |
| --- | --- |
| Intended audience | Owner intends every listed age group. **All-age suitability/Families declaration remains incomplete** until the gaps are resolved; no substitute audience is authorized. |
| App category | **Communication**; a native email client. |
| Ads / in-app purchases | **No / No** in the inspected build. No advertising SDK or billing integration was found. |
| User interaction / exchange of user-created content | **Yes**: direct email, multiple recipients, freeform text and file attachments. No public feed, random matching or anonymous chat-discovery feature was found. |
| Sharing personal information | **Yes**: sender/recipient addresses, optional names, message text and attachments can be transmitted. No developer-operated mail relay does not mean no transmission. |
| Parental controls / adult action / safety reminder | **No** in this build. Do not declare a child's own mailbox password or the Android contacts permission dialog to be parental consent. |
| User blocking / abuse reporting | No dedicated sender-blocking or abuse-reporting feature was found. Moving one message to Spam is not evidence that future messages are blocked or an abuse report reaches a responsible operator. |
| Precise location / camera / microphone features | No corresponding runtime permission or capture feature was found. A user can still manually attach a file containing personal information; do not misdescribe all attachments as free of such data. |
| Account creation | **No new provider identity is created in the app**; it signs into an existing mailbox. App access is restricted by mailbox login; the privacy policy is accessible without login. |
| Children's data / Families compliance | Authentication and mail data are involved. **Do not answer “no children's personal data” or certify compliance** based only on the absence of ads, analytics or a developer backend. |

### Work required before an all-age submission

The following is an implementation plan inferred from the observed gaps. It is not a claim that a PIN, warning or policy paragraph alone would secure approval.

1. Establish a supported child-use model and provider eligibility before collecting child mailbox credentials. Specify which adult configures the account, how adult status/authority is established, what the child can do, and how authorization can be withdrawn. If behavior differs by age, use an unbiased age flow and treat unknown age conservatively; alternatively apply the supervised restrictions to everyone. Store only the age information actually needed. A self-selected “I am an adult” checkbox or a PIN created by the child does not establish adult authorization.
2. Implement an age-appropriate safety reminder and an adult-controlled communication mode. Review inbound messages as well as compose/reply/forward, recipient selection, attachments, external links, remote resources, account changes and exports. A parent-approved contact list and restricted external content are possible design choices, not features currently present. Define behavior when approval is absent or revoked. Enforce it in the mail/account repositories and background jobs, not only visible buttons, including drafts, retries, notification actions and incoming share/mailto intents.
3. Resolve moderation and contact controls for this direct-provider architecture. Google's UGC policy specifies terms acceptance and proportionate moderation, with blocking for one-to-one interaction and reporting for identified communities. This code lacks those dedicated mechanisms. Confirm applicability and a workable provider-backed reporting/blocking path instead of claiming a generic email exemption. The incidental-sexual-content allowance cannot be used to justify exposing such content to children. [UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en-GB), [moderation guidance](https://support.google.com/googleplay/android-developer/answer/12923286)
4. Review each dependency and the configured provider's terms for child-directed use. Document actual child data flows, retention, deletion and parent access. Determine the applicable lawful basis/parental-consent requirements for intended countries before enabling collection; Play's adult-action mechanism is not automatically verifiable parental consent under applicable law. The FTC distinguishes notices, consent, access and deletion obligations, and flags the April 2025 COPPA amendment; use the current rule for the actual legal assessment. [FTC guidance and current-rule link](https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions)
5. Reassess suitability and usability for each requested age band, especially non-readers/early readers. Test the final supervised paths, bypass attempts, permission denial, offline/retry behavior and revocation. Update the public/in-app privacy policy, listing, Data safety, IARC answers, reviewer instructions and screenshots to the resulting behavior. Do not claim age suitability from the current adult-style demo screenshots.

This requires a product and data-handling design, not a release-metadata-only adjustment. Existing bundle/build checks and an unpublished internal draft can be prepared while these decisions remain open. They do not establish Families eligibility or authorize release to children.

### Contacts and provider-account details

`READ_CONTACTS` is not categorically prohibited by the cited Families policy. Here it performs local email-address suggestions after Android permission; the entire address book is not uploaded. Selected recipients leave the device when mail is sent. For a supervised design, gate any optional address-book use appropriately or use a selective picker. Google's newer contacts policy requires an access justification when targeting API 37+, with enforcement from **27 January 2027**; declarations began appearing in September 2026. This build targets API 36, so do not misstate that future API-specific deadline as an existing rejection. Plan a minimum-scope picker migration or supported core-function justification before targeting 37. [Contacts permission guidance](https://support.google.com/googleplay/android-developer/answer/16935362)

UPM's current account guidance ties mail to University affiliation/enrollment; it does not establish a general child account offering. Its personal-mailbox terms prohibit transfer to third parties. Confirm the intended children's own permitted access and arrange explicitly authorized reviewer/test access with the provider. Alternatively, reviewers can use the app's custom-server configuration with a dedicated, authorized TLS IMAP/SMTP test mailbox if all functionality is accessible; this remains subject to Google's review-access checks. Do not use an adult's shared personal inbox as a child-access or review workaround. A custom test mailbox does not establish UPM account eligibility or verify the UPM presets. [UPM account eligibility](https://soporte.upm.es/hc/es/articles/23480559826077-Correo-Electr%C3%B3nico-C%C3%B3mo-disponer-de-cuenta-de-correo-electr%C3%B3nico), [UPM mail terms](https://soporte.upm.es/hc/es/articles/37381588653853-Correo-Electr%C3%B3nico-T%C3%A9rminos-y-Condiciones-de-Uso)

The separate Child Safety Standards declaration currently applies to Social/Dating-category apps and anonymous/random chat apps. A normal Communication-category email client does not automatically fall into those categories; the Families communication duties above still apply. Reassess if category/functionality changes or Console requests it. [Child Safety Standards scope](https://support.google.com/googleplay/android-developer/answer/14747720)

## Foreground service: declaration and evidence

The manifest declares `FOREGROUND_SERVICE_SPECIAL_USE`, `PushService` uses `specialUse`, and its subtype describes an IMAP IDLE connection for incoming mail. Push is currently enabled by default. A foreground notification reports connection status. Users can stop push by changing **Settings > Notifications > Check for new mail** from **Instantly (push)** to a periodic/manual mode, or by removing all accounts. Confirm these paths on the final build rather than asserting an explicit opt-in flow that does not exist.

Google reviews `specialUse` explanations and requires a valid use case, user benefit, perceptibility/control, necessity and limited duration. It is not an automatic exemption from background-work rules. [Service type documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646)

**Draft functionality explanation — verify before submitting:**

> UPM Mail receives mail for existing university mailboxes through direct IMAP connections. With the Instant push setting active, the foreground service maintains an IMAP IDLE connection to each configured inbox and refreshes that inbox when the mail server reports a change. An ongoing notification shows connection status. Users can stop this service by choosing periodic or manual checking in Settings, or by removing all configured accounts. Periodic checks, queued sending and search indexing have separate scheduled jobs.

**Draft impact explanation — verify with recorded behavior:**

> Deferring or interrupting the IDLE connection delays notification of incoming mail until a reconnection, a scheduled check or the next foreground refresh. The user can choose those less frequent checking modes, but they do not provide the same notification timing as an active IDLE connection. The connection remains active while push is enabled and accounts exist; it pauses network work offline and reconnects when connectivity returns.

**Type justification to complete:** explain why this persistent IMAP listener fits `specialUse` better than the defined types and why a scheduled alternative does not meet the chosen feature's purpose. Do not claim that every email client qualifies, that continuous lifetime is already approved, or that FCM is technically impossible without evidence. The app currently implements direct IMAP and no FCM integration; confirm available UPM server integrations and the architectural constraints with the owner.

- [ ] Review the final manifest subtype against actual service behavior.
- [ ] Verify notification text explains the active work and a way to stop it. Do not base the justification on hiding ongoing work.
- [ ] Check push startup, stop/restart, last-account removal, denied notifications, reboot and network-loss behavior on Android 14–16.
- [ ] Record a video of the exact release build: open the test mailbox, show the checking setting, enable/select push, show its ongoing notification, leave the app, receive synthetic mail, open the new-mail notification, then switch to periodic/manual checking and show push stopping.
- [ ] Include English narration or captions, release version and relevant settings. Keep credentials and real mail out of the video. Make the link accessible to reviewers without requesting access.
- [ ] Complete the Console FGS declaration with functionality, delay/interruption impact, use-case choice and video link. [Declaration and video requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

**Acceptance is not guaranteed.** If the declaration is rejected, inspect Google's specific reason and revise the implementation or evidence. Do not relabel the service as a different type solely to bypass review. A release with periodic/manual checking is an implementation option if the persistent service cannot be justified, but its code, manifest, listing and privacy declarations would need to be updated together.

## Direct battery-optimization exemption

The current app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and opens the system's direct exemption request from its background-delivery setting, with a general settings fallback. Google permits direct requests only where power restrictions adversely affect core functionality; Android documents narrower acceptable messaging cases where FCM cannot meet the technical dependency. The presence of an email feature does not itself establish eligibility. [Doze exemption guidance](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases)

**Draft rationale, conditional on verification:** the app's direct IMAP listener loses timely incoming-mail notification when Doze suspends its network connection. The app does not operate a notification relay or FCM integration. Explain and document why the existing UPM service cannot supply equivalent notifications through an available FCM integration, rather than claiming that not having implemented FCM is sufficient.

- [ ] Capture device tests with exemption allowed and denied; document the observed notification delays and recovery.
- [ ] Keep the request optional, explain its effect before the system dialog, and show that manual/periodic mail access still works when declined.
- [ ] Verify the precise setting and prompt shown by the release build. Do not claim the request is automatic or mandatory if it is user-triggered.
- [ ] If eligibility cannot be supported, change the implementation to the general system settings path and remove the direct-request permission before submission. This is a code change, not a different answer on the form.

## Testing, review and launch

1. Verify real UPM sign-in, receive/send/reply/forward, attachments, drafts, outbox reconnect, folder changes, search, optional contacts permission, remote-image controls, both languages, sign-out/removal and notification behavior using the dedicated mailbox. Verify the privacy-policy link on the final build.
2. Upload the signed AAB to internal testing and inspect Console validation/pre-launch reports. Resolve crashes, permission failures and inaccessible screens before wider release.
3. This app's Console dashboard confirms production access is locked pending a closed test with at least **12 testers continuously opted in for 14 days**. Run that test, gather real feedback and then apply for production access. The unpublished internal draft does not satisfy this closed-test requirement or guarantee approval. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
4. Complete all unresolved identity, listing, app-access, privacy and service-evidence items, including the all-age/Families implementation and provider-eligibility gates above. Check the Console publishing overview and review the precise release/track changes before submitting them. An unpublished internal draft is not permission to certify unresolved policy answers or roll out to children.
5. Record the uploaded version, AAB SHA-256, signing fingerprint, policy-form version, test evidence, selected countries and review status. Do not mark the app published until Console confirms the intended release is available.

Recheck linked requirements at submission time, especially if this checklist is reused after the preparation date.
