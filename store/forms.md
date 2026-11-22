# Store forms: answers to click through

Answers for Google Play Console and App Store Connect, checked against the app on 2026-11-21 (Android 0.2.0 release manifest, iOS `project.yml` and `PrivacyInfo.xcprivacy` on `main`). **Re-check the starred (*) lines against the 0.3 build before submitting.** Sources are at the end of each section.

## Google Play Console

### Store settings

- **App category:** Books & Reference (Knihy a reference).
- **Store listing contact:** email **info@offlinka.cz**, website **https://offlinka.cz**, phone **+420 728 374 741** (the offlinka.cz number).
- **Developer page** (account level, shared with Olingo Sken): website https://ollsoft.ai, email info@ollsoft.ai.
- **Listing languages:** cs-CZ (default), sk, en-US. Texts: `android/fastlane/metadata/android/<locale>/`. Images: `store/screenshots/build.sh --fastlane`.
- **Countries:** Czechia and Slovakia (the drive ships only there). Adding the rest of the EU later is one click.
- **Price:** free. No in-app products.

### App content

- **Privacy policy:** https://offlinka.cz/aplikace/soukromi
- **Ads:** No, the app contains no ads.
- **App access:** "All or some functionality is restricted". Instruction name "Without the Offlinka drive", text:
  - `The app reads the Offlinka USB-C flash drive. Without it: 1) the emergency guides are built in: on the first screen tap "Otevřít první pomoc" (English: "Open first aid"). 2) Sample library: download https://github.com/Ollsoft-ai/offlinka-app/releases/download/review-sample-2026-11/offlinka-review-sample.zip and unzip it into Download (this creates the folder OFFLINKA). Tap "Vybrat flashku" ("Choose drive"), then "Vybrat znovu" ("Choose again") if the drive is not found, open Download, pick the folder OFFLINKA that contains Knihovna and Telefon, tap "Use this folder" and "Allow". 3) Video with the real drive: [VIDEO URL]. No login is needed.`
- **Content rating:** see IARC below.
- **Target audience:** **13–15, 16–17, 18 and over.** Not under 13 (reasons below).
  - "Could your store listing unintentionally appeal to children?" **No.** The screenshots show Wikipedia, maps and first aid, no characters or kid imagery.
- **News app:** No. It is a reference app, the content is a snapshot and the listing never says "news".
- **Data safety:** see below.
- **Government app:** No.
- **Financial features:** "My app doesn't provide any financial features".
- **Health apps:** the form is required for every app. Tick **Medical → Emergency and First Aid** (the built-in first aid guides) and **Medical → Medical Reference and Education** (English medical Wikipedia on the drive). Nothing else; it is not a medical device.
- **Advertising ID:** No. (*) The release manifest has no `com.google.android.gms.permission.AD_ID`.
- **Permissions declarations:** none needed. (*) The 0.2.0 release manifest has no dangerous or special permission at all (no INTERNET, no storage, no foreground service, no photo/video); the drive is read through the system folder picker.
- **Foreground service:** none used, so no declaration. (*) No `FOREGROUND_SERVICE*` permission and no service with `foregroundServiceType`; search indexes are built in-process while the app is open.
- **EU trader status** (account level): **Trader: yes** (a company). Google shows the legal name, D-U-N-S, address, developer email and phone; from 2027-03-01 also a Terms of Service link per app.

### Data safety

- "Does your app collect or share any of the required user data types?" **No.**
- "Is all of the user data collected by your app encrypted in transit?" Not asked once you answer No.
- "Do you provide a way for users to request that their data is deleted?" Not asked once you answer No.
- Result on the listing: **No data collected, No data shared with third parties.**
- Why this is correct:
  - Google: "'Collect' means transmitting data from your app off a user's device"; data "only processed locally on the user's device and not sent off device does not need to be disclosed".
  - The app cannot transmit anything: no INTERNET permission. Settings, history, saved articles and search indexes stay in app storage.
  - WebView: maps and games run JavaScript from the drive, but without network access nothing leaves the device.
  - The shop link on Connect („Nemáte ještě Offlinku? Kupte ji zde“) and the privacy link only hand a fixed URL to the browser after a tap (`ACTION_VIEW`). The app sends nothing itself; the URL holds no user or device data, only `utm_source=app`, `utm_medium=android|ios` and `utm_campaign=connect`. The visit is the browser's and the shop's, under the shop's own policy, so the answer stays No.
  - Android backup is off (`allowBackup=false`, decided 2026-11-21): nothing from the app goes into a Google backup. On iOS the settings, saved articles and history may be in the user's own iCloud backup; the app never sends or reads it; the privacy policy discloses it (point 3.4).
- Sources: https://support.google.com/googleplay/android-developer/answer/10787469

### Content rating (IARC questionnaire)

- **Email:** info@offlinka.cz. **Category:** "Reference, News, or Educational".
- Google does not publish the question list (it is visible only in the Console), so these are the rules for answering:
  - Answer **Yes** where a question covers **factual or educational references** that Wikipedia contains: violence (wars, crimes), sexuality (sex education, anatomy, nude art), drugs, alcohol and tobacco, crude language (articles about words), and historical extremist symbols (Nazi flags in history articles). Say "in an encyclopedic context" wherever there is a choice.
  - Answer **No** to gratuitous or realistic depictions meant to entertain, to user interaction or user-generated content, to sharing location, to digital purchases, to gambling and to unrestricted internet access.
  - Games (10, checked in the drive's `offlinka_mul_hry.zim`): chess, draughts, five in a row and battleships against the computer; sudoku, minesweeper, solitaire (Klondike), memory, 2048 and Hextris alone. Abstract boards, grids and cards: battleships and minesweeper show no explosions or weapons, solitaire has no stakes, money or betting. No chat, no multiplayer, no network, no purchases. So the answers above stay: no violence, no gambling or simulated gambling, no user interaction.
- Expect a teen-level rating (for example PEGI 12) because of the encyclopedic answers. That is fine and matches the 13+ target audience.
- Sources: https://support.google.com/googleplay/android-developer/answer/188189

### Target audience: 13+, not "all ages"

- **Recommendation: 13+.** Selecting any group under 13 puts the app under the Families policy: everything a child can reach must be appropriate for children, and the app may need Families-specific reviews.
- Wikipedia on the drive is unfiltered (sexuality, violence, drugs, war). We cannot make it appropriate for children, and we have no way to filter it.
- 13+ costs nothing real: younger children can still use a parent's phone, the shop can keep "zvídavé děti" as a use case for the drive, and the app collects no data, so there is no extra privacy duty either way.
- Keep the listing free of child-oriented imagery, or Google may treat it as appealing to children and reject it.
- Sources: https://support.google.com/googleplay/android-developer/answer/9867159, https://support.google.com/googleplay/android-developer/answer/9893335

## App Store Connect

### App Privacy

- "Do you or your third-party partners collect data from this app?" **No, we do not collect data from this app.** Label: **Data Not Collected**.
- Consistent with `ios/Offlinka/Resources/PrivacyInfo.xcprivacy`: `NSPrivacyTracking` false, no tracking domains, no collected data types. Required-reason APIs declared: file timestamp (3B52.1) and disk space (E174.1).
- Apple: "Data that is processed only on device is not 'collected'."
- The shop link on Connect opens Safari with a fixed URL (`utm_medium=ios`) after a tap: no identifier, nothing sent by the app, so neither collection nor tracking.
- **Privacy policy URL:** https://offlinka.cz/aplikace/soukromi (sk: https://offlinka.sk/aplikacia/sukromie, en: https://offlinka.cz/en/app/privacy). Already in `ios/fastlane/metadata/<locale>/privacy_url.txt`.
- Source: https://developer.apple.com/app-store/app-privacy-details/

### Age rating (questionnaire valid since 2025-07-24, new questions 2026-09 and 2026-11-16)

- **In-App Controls:** Parental Controls **No**. Age Assurance **No**.
- **Capabilities:**
  - Unrestricted Web Access **No**: the web view shows only the drive's files; internet links are not opened.
  - User-Generated Content **No**. Social Media **No**. Messaging and Chat **No**. Shared Location **No**. Advertising **No**.
- **Mature Themes:**
  - Profanity or Crude Humor **None**. Horror/Fear Themes **None**.
  - Alcohol, Tobacco, or Drug Use or References **None**. Dating and Relationships **None**.
- **Medical or Wellness:** Medical or Treatment Information **Infrequent** (first aid guides and the medical Wikipedia are a small part of the library). Health or Wellness Topics **None**.
- **Sexuality or Nudity:** Mature or Suggestive Themes **Infrequent**. Sexual Content or Nudity **None**. Graphic Sexual Content and Nudity **None**.
- **Violence:** Cartoon or Fantasy Violence **None**. Realistic Violence **None**. Prolonged Graphic or Sadistic Realistic Violence **None**. Guns or Other Weapons **Infrequent**.
- **Chance-Based Activities:** Gambling **No**. Simulated Gambling **None**. Contests **None**. Loot Boxes **No**.
- **Made for Kids:** No.
- **Expected rating: 13+** (Infrequent Medical or Treatment Information). Benchmark: the official Wikipedia app is 9+ with Infrequent Mature or Suggestive Themes and Infrequent Guns or Other Weapons; we answer the encyclopedic content the same way and add the medical line.
- If you answer Medical "Frequent", the rating becomes 16+. Do so only if the reviewer asks.
- Sources: https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/, https://developer.apple.com/news/?id=ks775ehf, https://developer.apple.com/news/?id=yxkh08us

### Export compliance

- **`ITSAppUsesNonExemptEncryption` = NO is already set** in `ios/project.yml` (target Offlinka, `info.properties`, value `false`), so App Store Connect asks no encryption question per build.
- Correct because the app makes no network connections and links no cryptography: the Rust core only decompresses (zstd, xz) and reads files.
- Apple: "Set the value to NO if your app … doesn't use encryption, or if it only uses forms of encryption that are exempt".
- Source: https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations

### Other App Store Connect answers

- **Content Rights:** "Does your app contain, show, or access third-party content?" **Yes.** "Do you have all necessary rights?" **Yes**: Wikipedia CC BY-SA 4.0, OpenStreetMap ODbL, fonts SIL OFL 1.1, games MIT (Hextris GPL-3.0), all credited in Settings → About.
- **Advertising Identifier (IDFA):** No.
- **Categories:** primary Reference, secondary Education (`primary_category.txt`, `secondary_category.txt`).
- **Copyright:** `2026 Ollsoft s.r.o.` (`copyright.txt`).
- **EU Digital Services Act:** **trader**. Apple publishes the D-U-N-S address of Ollsoft s.r.o., a phone number and an email on the EU product page; both are verified by two-factor codes.
- **Review information:** notes in `ios/fastlane/metadata/review_information/notes.txt` (replace `https://github.com/Ollsoft-ai/offlinka-app/releases/download/review-sample-2026-11/offlinka-review-sample.zip`), attach the demo video, fill in the contact person's name, phone and email in App Store Connect.
- Source: https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements
