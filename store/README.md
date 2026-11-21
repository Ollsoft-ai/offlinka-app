# Store: Google Play and App Store

Everything the Offlinka apps need for the stores, ready for the release session. Nothing here is submitted automatically.

- **`RELEASE.md`**: the session checklist (accounts, signing, tracks, review), rejection risks, open questions.
- **`forms.md`**: answers for Data safety, IARC, target audience, health, App Privacy, age rating, export compliance.
- **Play listing** (fastlane `supply` layout): `android/fastlane/metadata/android/{cs-CZ,sk,en-US}/`: title, short and full description, `changelogs/<versionCode>.txt`.
- **App Store listing** (fastlane `deliver` layout): `ios/fastlane/metadata/{cs,sk,en-US}/`, categories, copyright, `review_information/notes.txt`.
- **`privacy/{cs,sk,en}.html`**: the apps' privacy policy, standalone pages for offlinka.cz (URLs in `RELEASE.md` step 0).
- **`screenshots/`**: store frames, feature graphic and Play icon from the current UI, see below.

## Screenshots

- **Re-run after every UI change:** re-record Paparazzi (`:ui:recordPaparazziDebug`), then `store/screenshots/build.sh` (all targets) or `build.sh play ios-69` (some). `--fastlane` also copies the Play images into `android/fastlane/metadata/android/*/images/`.
- **Output** (git-ignored): `screenshots/out/play/<locale>/images/` (phone screenshots 1080×1920, `featureGraphic.png` 1024×500, `icon.png` 512×512 with alpha), `screenshots/out/appstore/<locale>/` (1320×2868, 1206×2622, 1284×2778, iPad 2064×2752), contact sheet `screenshots/out/index.html`.
- **Edit** headlines, frame order and screen sources in `screenshots/frames.json`; the look is `screenshots/frame.html`.
- **Sources:**
  - Paparazzi goldens in `android/ui/src/test/snapshots/images/` (Pixel 5, 461×1000).
  - Real pages from the drive (`/srv/offlinka/drive/release/current`), served by `offlinka-cli` with the reader stylesheet and drawn into the snapshot's WebView area: the article, the dark article, the first aid guide and the map.
- **Missing app states** (`readerMap` in sk/en, a reader showing the guides, the reader in sk dark) fall back to the page without the app bars. The build lists them; add them to `ScreensTest.kt` and the frames pick them up.
- Every Chromium step runs alone under `atd-render timeout 120`. A full run takes about 6 minutes.
