# Store release: step by step

For the session with the founders. Work top to bottom and tick the boxes. Rules and fees checked on **2026-11-21**; the sources are linked at each step. Answers for the forms: `store/forms.md`.

## Decided: who owns the accounts

- [x] **Ollsoft s.r.o.** owns both store accounts (founders, 2026-11-21).
  - IČO 19336039, Příčná 1892/4, Nové Město, 110 00 Praha 1.
  - D-U-N-S **933697073**, verified through Apple's lookup on 2026-11-02.
- **One account per store for all Ollsoft apps:** Offlinka is the second app next to Olingo Sken (`org.ollsoft.sken`).
- **Developer page** (both apps): https://ollsoft.ai, info@ollsoft.ai.
- **Offlinka's own listing:** https://offlinka.cz, info@offlinka.cz.
- Marketing text and screenshots name no company. The stores show the developer name themselves.

## 0. Before the session (agents and Kryštof)

- [ ] **0.3 merged** on `main`, device checklist `docs/DEVICE-TESTS.md` passed on two phones.
- [ ] **Release notes** `android/fastlane/metadata/android/*/changelogs/5.txt` (0.3.2, versionCode 5) are a launch note for the first Play release. A later build needs `changelogs/<its versionCode>.txt`.
- [ ] **Fixes that reviewers would hit:** the first four items of "Rejection risks" below.
- [ ] **Privacy pages published** from `store/privacy/` at https://offlinka.cz/aplikace/soukromi, https://offlinka.sk/aplikacia/sukromie and https://offlinka.cz/en/app/privacy (Kryštof).
- [x] **Review sample** published; its README text is `store/review-sample-README.txt` (use it when the sample is rebuilt; the 2026-11 zip's README still says 32 GB and "2048, draughts") (https://github.com/Ollsoft-ai/offlinka-app/releases/download/review-sample-2026-11/offlinka-review-sample.zip, 1.5 GB): one folder `OFFLINKA/` with `Telefon/` (`wikipedia_cs_all_mini-…zim`, 870 MB, text only, and its `.offidx`; `offlinka_mul_hry.zim`) and `Knihovna/maps_en_slovakia…zim`. The guides are left out: the apps bundle them. Put its URL into `ios/fastlane/metadata/review_information/notes.txt` and the Play "App access" text.
- [x] **Demo video** (made by us; founders 2026-11-21): `/srv/offlinka/app-dist/review-video/offlinka-app-review-demo-en.mp4` (1:39, 1920×1080, English voice-over and captions). iOS Simulator recording with the review sample: choose the drive, search „cesky krumlov“, article, map, 2048, first aid; marked "Recorded in the iOS Simulator". Unplug/replug is not shown (the Simulator can't). Play needs no demo video.
- [ ] **Screenshots:** re-record Paparazzi (`:ui:recordPaparazziDebug`), then `store/screenshots/build.sh --fastlane`. Look at `store/screenshots/out/index.html`. The build lists missing app states; the iPhone and iPad sets must come from the iOS simulator before the App Store submission (step 3).
- [x] **Public phone number:** **+420 728 374 741**, the same as on offlinka.cz (founders, 2026-11-21). Used as the developer phone on both stores; it confirms by a one-time code.

## 1. Google Play account (once, for all Ollsoft apps)

- [x] (account 6707358614901730690, verified 2026-11-25) Create at https://play.google.com/console/signup: **organization** account, Ollsoft s.r.o., D-U-N-S 933697073.
  - **Fee: US$25, one time** (2026-11-21).
  - Legal name and address must match the Dun & Bradstreet record exactly.
  - Contact email and phone, developer email and phone: each confirmed by a one-time code.
  - Source: https://support.google.com/googleplay/android-developer/answer/13628312
- [ ] **Shown publicly** on Google Play: legal name, D-U-N-S, legal address, developer email (info@ollsoft.ai) and developer phone.
- [ ] **Developer page:** website https://ollsoft.ai, email info@ollsoft.ai.
- [ ] **EU trader declaration:** a new account declares on creation; every account must declare by **2027-02-01**.
  - Trader: yes.
  - From **2027-03-01** Play shows the contact details, the D-U-N-S and a **Terms of Service link per app**. Offlinka needs short app terms by then (open item).
  - In the EEA and UK, "Offered by" will show Ollsoft s.r.o. instead of Google.
  - Source: https://support.google.com/googleplay/android-developer/answer/17594752
- [ ] **12 testers for 14 days does not apply to us.** Google: "Developers with personal accounts created after November 13, 2023, must run a closed test … with a minimum of 12 testers … for at least 14 days." Organization accounts are not named.
  - No Google page says outright that organizations are exempt (the community guide does).
  - If the Dashboard still shows "Apply for production", follow it.
  - Source: https://support.google.com/googleplay/android-developer/answer/14151465
- **Target API:** new apps and updates must target **API 36** since **2026-08-31**. We target 36. Source: https://developer.android.com/google/play/requirements/target-sdk
- **16 KB pages:** checked on 0.2.0. The 64-bit `.so` files are aligned to 16 KB and `zipalign -c -P 16` passes. Play blocks updates without 16 KB support from 2027-02-01. Source: https://developer.android.com/guide/practices/page-sizes

## 2. Google Play: the Offlinka app

- [x] **Create app:** name `Offlinka`, default language Czech (cs-CZ), App, Free. Accept the declarations.
- **Which key signs which file** (both only from `signing.env`, never in the repo):
  - **Done 2026-11-25: Play has our app signing key and uses it as the upload key too** (no separate upload key was registered, so every AAB for Play is signed with the **app signing key**; Play's generated APKs verified 68:B2:3A…). Old plan, unused: **upload key** `_secrets/android/offlinka-upload.jks` (`OFFLINKA_UPLOAD_*`), CN=Offlinka upload, RSA 4096, valid to 2057-01-02. Certificate SHA-256 `A8:ED:90:80:EA:0E:41:D5:A1:01:46:60:C1:2B:18:50:2A:09:0D:03:52:65:B5:75:22:90:90:0D:91:9E:A5:4E`.
  - **APK → the drive: app signing key** `_secrets/android/offlinka-app-signing.jks` (`OFFLINKA_SIGNING_*`), CN=Offlinka. Certificate SHA-256 `68:B2:3A:D7:CD:94:A3:81:EC:9D:6E:23:A9:DB:11:02:5D:AD:E1:17:34:A2:0F:B4:B8:35:1A:97:0F:4C:30:CB`. The same key is uploaded to Play below, so Play updates install over the drive APK.
- [x] **App signing with our own key, BEFORE the first release.** Play's default is now Google-generated keys; once a release is rolled out it cannot be changed. Source: https://support.google.com/googleplay/android-developer/answer/9842756
  - Test and release → App integrity → Play app signing → **Change app signing key** → "Export and upload a key from Java keystore".
  - Download the PEPK tool and the encryption key from that page.
  - Run PEPK on `_secrets/android/offlinka-app-signing.jks` (alias and password from `signing.env`, read in the shell, never printed). Upload the encrypted zip.
  - **Upload key:** skipped (step 5 of the dialog). A separate upload key adds nothing here: the app signing key must live with us anyway for the drive APK. To add one later: App signing → request upload key reset with the `keytool -export -rfc` certificate of `offlinka-upload.jks`.
  - Google: "If you want to use the same signing key across multiple stores, make sure to provide your own signing key … instead of having Google generate one for you." Keep upload and app signing keys different.
- [ ] **Build** (shell, from `android/`, secrets loaded with `set -a; source "$OFFLINKA_SECRETS/android/signing.env"; set +a`):
  - **AAB for Play**, signed with the **app signing key** (the `OFFLINKA_SIGNING_*` values): `OFFLINKA_KEYSTORE="$OFFLINKA_SECRETS/android/$OFFLINKA_SIGNING_STORE" OFFLINKA_KEY_ALIAS="$OFFLINKA_SIGNING_ALIAS" OFFLINKA_KEYSTORE_PASSWORD="$OFFLINKA_SIGNING_PASSWORD" OFFLINKA_KEY_PASSWORD="$OFFLINKA_SIGNING_PASSWORD" … ./gradlew --no-daemon bundleRelease` (under the build lock, see `docs/SPEC.md`).
  - **APK for the drive**, signed with the **app signing key**: the same with the `OFFLINKA_SIGNING_*` values and `assembleRelease`.
- [x] **Store listing** (uploaded by API 2026-11-25, cs/sk/en texts + icon, feature graphic, 8 screenshots each; no listing phone; category set by hand): `fastlane supply --package_name cz.offlinka.app --metadata_path android/fastlane/metadata/android --json_key <service account json> --skip_upload_apk --skip_upload_aab`, or paste the texts by hand. Category, contact and the rest: `store/forms.md`.
- [ ] **App content** forms: privacy policy, ads, app access, content rating, target audience 13+, data safety, health, financial, news, advertising ID (`store/forms.md`).
- [ ] **Countries:** Czechia and Slovakia.
- [x] **Internal testing:** (0.3.2 vc5 live 2026-11-25; first review via a closed-testing release, sent 2026-11-25) upload the AAB, add the founders' Google accounts, install from the opt-in link and run the device checklist once with the drive. Read the pre-launch report: without a drive it only sees the Connect screen, which is fine.
- [ ] **Closed testing** (optional for us): the same build to a few friends with drives for a week.
- [ ] **Production:** promote the build, staged rollout 20 % → 100 % after two days without crashes in Android vitals.
- [ ] Check that the **app signing certificate** under App integrity is ours (SHA-256 matches `keytool -list -v` of `offlinka-app-signing.jks`).

## 3. Apple

- [x] **Apple Developer Program, organization:** (active 2026-11-24, Team D5B5L4G2C8) https://developer.apple.com/programs/enroll/
  - Legal entity Ollsoft s.r.o., D-U-N-S 933697073.
  - Enrolled by someone who can sign for the company (jednatel).
  - **Apple Account email on the company domain**, e.g. `…@ollsoft.ai`. Apple: "Your work email address needs to [be] associated with your organization's domain name".
  - Website https://ollsoft.ai must be public and belong to the company.
  - **Fee: 99 USD per membership year**, shown in local currency during enrollment (2026-11-21).
- [ ] **EU trader (DSA):** yes. Apple publishes the D-U-N-S address, a phone and an email on the EU product page; it needs a document proving the business name and address. Apps without trader status are removed from the EU App Store. Source: https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements
- [x] **Identifiers:** (bundle ID by API 2026-11-24) explicit App ID `cz.offlinka.app`. Note the Team ID (`OFFLINKA_TEAM_ID`). The App Store Connect API team key (.p8, App Manager) exists since 2026-11-24 and is shared by all Ollsoft apps, so it lives in Kryštof's private user-wide secrets (`users/krystof/_secrets/apple/asc-api.env`: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`, `FASTLANE_TEAM_ID`; see `ios/fastlane/Appfile`), not in the project `_secrets/`. CI gets it as GitHub Actions secrets.
- [x] **App record** (Apple ID 6838847374) in App Store Connect: iOS, name `Offlinka`, primary language Czech, bundle ID `cz.offlinka.app`, SKU `offlinka-ios`.
- [ ] **App Information and App Privacy:** categories, content rights, age rating, Data Not Collected, privacy URL (`store/forms.md`). Price free, availability Czechia and Slovakia. In Pricing and Availability **untick "Mac (Apple silicon)" and "Apple Vision Pro"**: they are on by default and untested (the app needs a USB drive and a phone).
- [ ] **First build on a Mac** (cloud Mac: skill `cloud-mac`): `ios/README.md` "Build on macOS", then `ios/UI-SMOKE-TESTS.md` on an iPhone 15 with the drive.
  - Since 2026-04-28 uploads must be built with **Xcode 26 or later**. Source: https://developer.apple.com/news/upcoming-requirements/
- [x] **TestFlight:** (CI job `testflight`; 0.3.2 build 60 VALID 2026-11-25, internal group "Ollsoft" gets every build) `cd ios && fastlane beta`, then add the founders as internal testers and test with the drive.
- [ ] **Screenshots from the iOS simulator:** capture the same moments (Home, search „cesky krumlov“, article, map, first aid, dark, Library) on an iPhone 17 Pro Max simulator and an iPad Pro 13″ simulator. Name them like the Paparazzi files, run `SNAPSHOTS=<that folder> store/screenshots/build.sh ios-69 ios-63 ipad-13`.
  - Apple requires at least one screenshot of the **"iPhone with Dynamic Island (medium display)"** class (1206×2622 or 1179×2556; our `ios-63`).
  - It also requires **iPad 13″** (2064×2752) because the app runs on iPad.
  - 1320×2868 (`ios-69`) and 1284×2778 (`ios-65`) cover the large classes.
  - Up to 10 per size, no alpha. Source: https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/
- [x] **Metadata:** (by API 2026-11-25: texts cs/sk/en, Reference + Education, age rating 12+, review notes + demo video, 63 screenshots, build 60 attached; manual release) `cd ios && fastlane release` (uploads the build and `fastlane/metadata`, no auto-submit). Upload the screenshots in App Store Connect, or `fastlane deliver --screenshots_path ../store/screenshots/out/appstore --skip_binary_upload --skip_metadata`.
  - **No "What's New" for the first version:** App Store Connect does not take it. Add `release_notes.txt` from the second version on.
- [ ] **App Review Information:** contact name, phone and email. Notes come from `review_information/notes.txt` (sample URL filled in). Attach the demo video. Sign-in: not required.
- [ ] **Submit for review**, release manually after approval.
- **Export compliance:** nothing to answer per build: `ITSAppUsesNonExemptEncryption` = NO is already in `ios/project.yml`.

## 4. The APK on the drive

- **Same key as Play:** the drive APK is signed with the **app signing key** uploaded to Play in step 2, never with the upload key. Android installs an update only when the certificates match.
  - Updates from Play therefore install over the drive APK, keeping saved articles and history. Source: https://developer.android.com/studio/publish/app-signing
- **versionCode on the drive ≤ the one on Play**, so a drive never blocks a Play update. Build the drive APK from the same commit as the Play release.
- **File name:** `Aplikace/android/Offlinka-<version>.apk`, with its SHA-256 in `Aplikace/SHA256SUMS.txt`.
- **Android developer verification:** Google is rolling this out for apps on certified phones.
  - Brazil, Indonesia, Singapore and Thailand from 2026-09-30; everywhere else in 2027.
  - Play App Signing apps are registered automatically. The drive APK carries the same package name and key, so it is covered.
  - Sideloaded installs are not checked yet.
  - Source: https://developer.android.com/developer-verification
- [ ] Device test once: install the drive APK, save an article, then update from Play (internal track). The saved article must still be there.

## 5. After release

- [ ] Add the store badges and links to offlinka.cz (an idea branch first, per the shop rules), and change the shop's mobile guide from Kiwix to the Offlinka app.
- [ ] Replies to store reviews: a founder approves, like customer e-mails.
- [ ] **Before April 2027: build iOS with Xcode 27 (iOS 27 SDK).** Apple warned on build 58 (ITMS-90725, built with the iOS 26.5 SDK); after that date older SDKs can't be uploaded. Change `DEVELOPER_DIR` in `.github/workflows/ios.yml`.
- [ ] Every new version: changelog in `android/fastlane/metadata/android/*/changelogs/<versionCode>.txt` and `ios/fastlane/metadata/*/release_notes.txt`, screenshots re-built.

## Rejection risks (fix before submitting)

- **Guides tell users to install Kiwix.** The bundled `offlinka_cs_prirucky.zim` (both apps) lists "Jak to otevřít v mobilu … aplikace Kiwix" on its main page; that page and "Jak používat tuto flashku" explain the drive and Kiwix. Reviewers without the drive open exactly these pages. Fix: an app edition of the guides without those two pages (drive-prep), copied into both apps.
- ~~**No privacy policy link inside the iOS app.**~~ Fixed: on iOS, About → Soukromí opens the policy in Safari.
- **About names Ollcee s.r.o.** (`about_lic_app`, `about_made_by`) while the stores will show Ollsoft s.r.o. Fix: "Aplikace Offlinka © 2026 Ollsoft s.r.o."; if the drive maker stays, say "Flashku vyrábí Ollcee s.r.o.".
- **"Najděte se na mapě" / "Find yourself on the map"** (0.3 `try_map`) promises location, but the map cannot show where you are (the shop FAQ says so). Fix: „Prohlédněte si mapu“ / "Explore the map".
- ~~**App Store screenshots are Android renders.**~~ Fixed: the App Store frames are iOS simulator captures on the real drive's content (`/srv/offlinka/app-dist/ios-shots/frames/`).
- **No way to review without the drive** (guideline 2.1): the sample folder and the demo video in step 0 remove most of it. Without them, expect "unable to review, provide a demo video or the hardware" and a lost week.
- Lower risks, no action now:
  - Maps and games run JavaScript from the drive (guideline 4.7, HTML5 mini games). Both are ours to describe, have no purchases or ads, and fit the 13+ rating.
  - "Wikipedia" appears in subtitles, keywords and screenshots (guideline 5.2.1, trademarks). It is descriptive and the name is just "Offlinka". If Apple objects, reply: "Wikipedia content is used under CC BY-SA 4.0 and named only to describe what the drive holds; Offlinka is not affiliated with the Wikimedia Foundation, as the description and About say." If it still objects, use the subtitle „Encyklopedie a mapy bez internetu“.

## Open questions for the founders

- ~~Public phone number~~: decided 2026-11-21: +420 728 374 741, the number on the website.
- ~~Review sample hosting and demo video~~: decided 2026-11-21: the sample is a public GitHub release asset (free content, CC BY-SA / ODbL); the demo video is made by us from the iOS simulator + real drive footage, no founder recording.
- **App terms:** Google shows a Terms of Service link per app from 2027-03-01. Shall we write short app terms (free, as is, licences)?
- **Countries:** Czechia and Slovakia only, or the whole EU from the start?
- ~~Auto Backup~~: decided 2026-11-21: off on Android (`allowBackup=false`); iOS excludes all app data from iCloud and computer backups (2026-11-22); privacy 3.4 says so.
