# Google Play release checklist

Prepared on **2026-10-24** for `io.github.pportilla.upmmail`. This is a preparation checklist, not a record of a completed Play submission. Complete the unresolved items against the actual developer account and final signed bundle before submitting.

## Confirmed identity and remaining account setup

| Item | Required decision or evidence |
| --- | --- |
| Play release | The owner confirmed this is the **first Play release**. The app has been created in the existing developer account. Retain `io.github.pportilla.upmmail` as its package identity; the account-specific Console link is kept with the private release handoff. |
| Developer account | Confirmed: an existing, verified **personal** Play Console account. This app's Console dashboard explicitly requires **12 closed-test participants opted in continuously for 14 days** before applying for production access. Creating another account is unnecessary. |
| Public identity | Confirmed developer name: **zango**. Confirmed public support email: **p.portilla89@gmail.com**. Use the same contact in the privacy policy. Support website: `https://github.com/pportilla/simple_webmail_upm`. |
| Distribution | Confirmed: **free**, **worldwide**, **no ads**. Saved category: Communication. The current code has no identified ads, billing or analytics integration. |
| Intended audience | The owner explicitly confirmed **all ages, including children under 13**. This intent includes the Console groups 5 and under, 6–8, 9–12, 13–15, 16–17 and 18+. The release adds a dedicated supervised experience for users under 18, including guardian-assisted use for younger children. Validate the final build and all-age declarations against the controls below; this is not Google policy approval. Do not silently substitute a 13+ audience. |
| Signing | Use a dedicated upload key for this first release and back it up securely. If a key has already been generated during release preparation, preserve it instead of generating another. Never use the repository's public `app/debug.keystore` for Play. |
| Review access | The owner **declined to supply reviewer mailbox credentials** and will respond through **p.portilla89@gmail.com**. No reviewer mailbox is configured. That support contact is not verified interactive access to the app. Document any available offline demo honestly; it does not establish live IMAP/SMTP/push review access or guarantee acceptance. Do not provision an external mailbox or repeat the credential request without new instruction. |
| Privacy hosting | The [public privacy URL](https://pportilla.github.io/simple_webmail_upm/privacy.html) and [acceptable-use rules](https://pportilla.github.io/simple_webmail_upm/acceptable-use.html) are live on GitHub Pages with HTTPS enforced. Both final supervised-mail documents returned HTTP 200 and matched source bytes at 11:49 UTC on 24 October 2026. The privacy URL is saved in Play Console; the policy is also readable offline in the app. |
| Evidence | Sixteen final English/Spanish screenshots were captured from the actual Android app with fictional local mailbox data and actual guardian controls. The inventory and exact-AAB runtime validation are recorded in `RELEASE_STATUS.md`. The exact-release foreground-service video demonstrates real local TLS/IMAP IDLE behavior with a disposable synthetic mailbox. Real UPM and external-provider testing remain unverified. Do not represent local fixtures as university/provider validation. |

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
python3 scripts/build-release.py --version-code 2 --version-name 1.0.0
```

Version code `2` includes the supervised-mail changes; use a larger code if `2` has already been uploaded by the time this is run. The helper runs release lint, release unit tests and the bundle build, then checks its signature and rejects the public debug key. `--unsigned` is a separate validation option; its output is not ready for Play upload. The release workflow can also produce an artifact when its signing secrets are configured, but does not publish the app.

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
- [ ] Complete **App access** with truthful English instructions for the access that actually exists, including age/guardian/PIN setup and any clearly labeled offline sample. The owner declined mailbox credentials; do not invent them or mark live mailbox access as verified. Google requires reviewers to reach all functionality with reusable, location-independent access; merely listing the support email does not establish this. If an offline sample is implemented, distinguish simulated actions from IMAP/SMTP/push behavior and record that its acceptance is unverified. [Review-access requirements](https://support.google.com/googleplay/android-developer/answer/15748846)

## All-age audience: implemented controls and release validation

The owner's requested audience includes children under 13. Selecting all age groups is an assertion that the app was designed for, and is appropriate for, every selected group; it is not simply a distribution preference. Google's age guidance specifically cautions against typing/computing-heavy experiences for ages 5 and under, search for ages 6–8, and direct user interaction for ages 9–12. These are suitability considerations, not blanket bans on all email clients. The release now provides a separate supervised interface with large controls, guardian review and assisted plain-text reading/writing, with runtime validation recorded in `RELEASE_STATUS.md`. Google has not assessed or approved its suitability for every requested age group. An IARC rating is a separate result and does not establish the intended audience. [Target-audience guidance](https://support.google.com/googleplay/android-developer/answer/9867159)

**Policy requirements:** targeting any children invokes Families. Children must receive an online-safety reminder before exchanging freeform content, and adult action is required before exchanging personal information. Additional social features need adult management controls. Child-accessible content must be suitable, sensitive-data handling disclosed, and APIs/SDKs compatible with child use. Certain device identifiers must not be transmitted for children or unknown-age users. The policy also requires applicable children's-privacy-law compliance. A mixed audience does not waive these requirements. No ads are implemented, so an advertising age screen or certified advertising SDK is not a solution to this app's actual gaps. [Families full policy](https://support.google.com/googleplay/android-developer/answer/9893335)

**Implementation:** the release now asks for a neutral birth date before mailbox setup and retains only an age band. Unconfigured access is blocked. Under-18 users receive a separate supervised interface; an adult completes a separate date-of-birth check, provider-use acknowledgment, acceptable-use acceptance and local guardian PIN setup before credentials are used. This is local access control, not independently verified parental identity or legal consent.

The guardian manages exact approved/blocked addresses, reviews each incoming message before the child can see it, and approves the exact recipients and content of each outgoing message. Incoming approval is tied to message content; an allowed sender alone is insufficient because addresses can be spoofed. Supervised reading and writing use plain text with no attachments, external links, remote resources, address-book import, exports or search. Supervised drafts are local. Repository and network checks cover queued sends and profile changes. Guardian sessions lock on background/Back; supervised push and mail-content notifications are disabled. Periodic/manual sync can retrieve mail for guardian review while communication is enabled.

Children can block a sender and ask a guardian to review a concern. That request is stored locally. The adult dashboard separately offers a provider-report composer: the adult chooses the destination, types the concern, previews the exact payload and approves sending. Reports include the sending account identity and normal email headers. Original correspondence and attachments are not automatically included. Supervised reports need a live guardian session; closing it can pause queued delivery. Report submission is not proof that a provider has acted. Blocking or revoking approval cannot recall a message already accepted by the mail server; an interrupted send can have an uncertain outcome and must not be retried as if delivery were impossible.

The public acceptable-use source is `docs/acceptable-use.html`; the same substantive rules require acceptance in the app. The website and native privacy policy describe local age/PIN data, approval records, reporting and retention. Confirm that the deployed policy/terms match the final build before submitting.

### Truthful draft Console answers for the current build

Use these facts when the corresponding question appears; inspect the actual wording before saving. Do not invent a positive compliance attestation to make a form complete.

| Question or topic | Current supported answer |
| --- | --- |
| Intended audience | Owner intends every listed age group. The under-18 path is supervised, with adult assistance for younger children. Complete the declaration against final validation; no substitute audience is authorized. |
| App category | **Communication**; a native email client. |
| Ads / in-app purchases | **No / No** in the inspected build. No advertising SDK or billing integration was found. |
| User interaction / exchange of user-created content | **Yes**: direct email, multiple recipients, freeform text and file attachments. No public feed, random matching or anonymous chat-discovery feature was found. |
| Sharing personal information | **Yes**: sender/recipient addresses, optional names, message text and attachments can be transmitted. No developer-operated mail relay does not mean no transmission. |
| Parental controls / adult action / safety reminder | **Yes, implemented:** separate adult setup and PIN, guardian contact/message controls and a safety reminder. Do not describe the PIN/date checks as independently verified legal parental consent. |
| User blocking / abuse reporting | **Yes:** local sender blocking, local concerns for guardian review, and an adult-controlled provider report with explicit destination/payload confirmation. Local concerns are not automatically delivered to a provider or developer. |
| Precise location / camera / microphone features | No corresponding runtime permission or capture feature was found. A user can still manually attach a file containing personal information; do not misdescribe all attachments as free of such data. |
| Account creation | **No new provider identity is created in the app**; it signs into an existing mailbox. App access is restricted by mailbox login; the privacy policy is accessible without login. |
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

The exact-release [demonstration video and guide](https://pportilla.github.io/simple_webmail_upm/foreground-service/) are public and verified. The `specialUse` declaration is saved in Console. The local TLS fixture demonstrates real IDLE/notification behavior; it does not supply reusable reviewer access or establish UPM compatibility. These saved materials remain subject to Google review.

The manifest declares `FOREGROUND_SERVICE_SPECIAL_USE`, `PushService` uses `specialUse`, and its subtype describes an IMAP IDLE connection for incoming mail. Push is **off by default**, available only to the adult profile, and starts only after the user selects **Instantly (push)**. A foreground notification reports generic connection status without account identifiers. Users can stop push by changing **Settings > Notifications > Check for new mail** from **Instantly (push)** to a periodic/manual mode, or by removing all accounts. Switching to a supervised profile also stops the service and its live connections; guardian unlock does not enable it. Verify selection, stop and restart behavior on the final build.

Google reviews `specialUse` explanations and requires a valid use case, user benefit, perceptibility/control, necessity and limited duration. It is not an automatic exemption from background-work rules. [Service type documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646)

**Draft functionality explanation — verify before submitting:**

> UPM Mail receives mail for existing mailboxes through direct IMAP connections. When an adult user enables Instant push, the foreground service maintains an IMAP IDLE connection to each configured inbox and refreshes that inbox when the mail server reports a change. An ongoing notification shows generic connection status. Users can stop this service by choosing periodic or manual checking in Settings, removing all configured accounts, or switching to supervised mail. Supervised profiles cannot use this service. Periodic checks, queued sending and search indexing have separate scheduled jobs.

**Draft impact explanation — verify with recorded behavior:**

> Deferring or interrupting the IDLE connection delays notification of incoming mail until a reconnection, a scheduled check or the next foreground refresh. The user can choose those less frequent checking modes, but they do not provide the same notification timing as an active IDLE connection. The connection remains active while push is enabled and accounts exist; it pauses network work offline and reconnects when connectivity returns.

**Type justification to complete:** explain why this persistent IMAP listener fits `specialUse` better than the defined types and why a scheduled alternative does not meet the chosen feature's purpose. Do not claim that every email client qualifies, that continuous lifetime is already approved, or that FCM is technically impossible without evidence. The app currently implements direct IMAP and no FCM integration; confirm available UPM server integrations and the architectural constraints with the owner.

- [ ] Review the final manifest subtype against actual service behavior.
- [ ] Verify notification text explains the active work and a way to stop it. Do not base the justification on hiding ongoing work.
- [ ] Check push startup, stop/restart, last-account removal, denied notifications, reboot and network-loss behavior on Android 14–16. Confirm that changing to supervised mail cancels active IDLE/keep-alive work and cannot restart it through alarms, queued jobs or guardian unlock.
- [ ] Record a video of the exact release build: open the test mailbox, show the checking setting, enable/select push, show its ongoing notification, leave the app, receive synthetic mail, open the new-mail notification, then switch to periodic/manual checking and show push stopping.
- [ ] Include English narration or captions, release version and relevant settings. Keep credentials and real mail out of the video. Make the link accessible to reviewers without requesting access.
- [ ] Complete the Console FGS declaration with functionality, delay/interruption impact, use-case choice and video link. [Declaration and video requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

**Acceptance is not guaranteed.** If the declaration is rejected, inspect Google's specific reason and revise the implementation or evidence. Do not relabel the service as a different type solely to bypass review. A release with periodic/manual checking is an implementation option if the persistent service cannot be justified, but its code, manifest, listing and privacy declarations would need to be updated together.

## Battery settings

The release no longer declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or launches the direct per-app exemption request. Its background-delivery help opens Android's general battery-optimization settings, with a general system-settings fallback. This removes the direct-request permission and its declaration rationale from the release. Android can still delay background mail when power restrictions apply. [Android background-power guidance](https://developer.android.com/training/monitoring-device-state/doze-standby)

- [ ] Confirm the final merged release manifest lacks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [ ] Verify the help action opens general Android settings and gracefully handles devices without that screen.
- [ ] Test manual/periodic mail access with power restrictions enabled. Selecting push remains optional and does not imply guaranteed delivery timing.

## Testing, review and launch

1. Record what has actually been tested. Live UPM sign-in and external-provider receive/send/reply/forward, attachments, draft sync, outbox reconnect and push remain unverified; the owner declined to supply reviewer credentials. Exact-release local TLS authentication, IMAP IDLE, background arrival and push stopping were demonstrated with a disposable fixture; keep that scope explicit. Exercise offline guardian controls, local mail flows, permissions, both languages and the privacy-policy link on the final build without presenting them as live mail tests.
2. Upload the signed AAB to internal testing and inspect Console validation/pre-launch reports. Resolve crashes, permission failures and inaccessible screens before wider release.
3. This app's Console dashboard confirms production access is locked pending a closed test with at least **12 testers continuously opted in for 14 days**. Run that test, gather real feedback and then apply for production access. The available internal test does not satisfy this closed-test requirement or guarantee approval. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
4. Complete all unresolved identity, listing, app-access, privacy and service-evidence items, including the all-age/Families validation and provider-eligibility items above. Check the Console publishing overview and review the precise release/track changes before submitting them. An internal test is not evidence supporting unresolved policy attestations or wider distribution to children.
5. Record the uploaded version, AAB SHA-256, signing fingerprint, policy-form version, test evidence, selected countries and review status. Do not mark the app published until Console confirms the intended release is available.

Recheck linked requirements at submission time, especially if this checklist is reused after the preparation date.
