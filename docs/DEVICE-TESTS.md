# Android: device test checklist

**What only a real phone with the drive can prove.** Everything else is covered by the JVM, Robolectric, Paparazzi and host-core tests (`android/README.md`). Run this on every release candidate, on at least one cheap phone (Android 8–10, 3–4 GB RAM) and one current phone (Android 14–16), with the shipping drive.

Install: `adb install -r offlinka-<version>-universal.apk` (or from the drive's `Aplikace/android/`). Tick each box, note the phone model + Android version at the top of the run. Anything red goes to the issue list with a screen recording.

## Run header

- Phone / Android version / RAM:
- App version (Nastavení → O aplikaci):
- Drive edition:
- Date, tester:

## 1. First start and picking the drive

- [ ] Fresh install, drive **not** plugged: Connect screen in Czech (phone in Czech) or English (phone in English), drive illustration, no crash, no network prompt.
- [ ] Plug the drive in. The system may show a "USB disk" notification: tapping it does not break the app.
- [ ] Tap **Vybrat flashku**: the system picker opens **directly on the drive** (Android 10+); on Android 8–9 it opens on the last folder, navigate to the drive.
- [ ] Pick the drive root, "Použít tuto složku" → "Povolit": "Hledám knihy na flashce…" then Home with **all books** of the drive (14 tiles on the full edition).
- [ ] Time from "Povolit" to Home: ____ s (target under 3 s on a mid phone).
- [ ] Pick a wrong folder (e.g. `Aplikace` or the phone's `Download`): calm "Tady knihy nejsou" message, button to pick again, no crash.
- [ ] Pick the `Knihovna` folder itself: books open (minus the phone editions).
- [ ] Cancel the picker (back): returns to the Connect screen.

## 2. Remembering the drive

- [ ] Close the app (swipe away), reopen with the drive plugged: Home directly, no picker.
- [ ] Reboot the phone, plug the drive, open the app: Home directly (persisted permission survived).
- [ ] Reboot, open the app **before** plugging the drive: calm "Flashku nevidím" screen; plug it in: Home appears within ~3 s without tapping anything.
- [ ] Clear the app's permission (Settings → Apps → Offlinka → Storage → clear data, or uninstall/reinstall): Connect screen again, picking works.

## 3. Unplugging mid-read (must never crash)

- [ ] Open a long Czech Wikipedia article with images, scroll, pull the drive out: "Flashka je odpojená" screen within ~2 s, **no crash, no ANR**.
- [ ] Plug it back: the same article is back (same place in the navigation), back button history works.
- [ ] Pull the drive while a search is running (type quickly while pulling): no crash.
- [ ] Pull the drive while a map is panning/zooming and while a game runs: no crash.
- [ ] Pull the drive while the app is in the background, return to the app: unplugged screen (or Home if replugged meanwhile).
- [ ] Pull the drive during the first start "Hledám knihy…": no crash, ends on a calm screen.
- [ ] Repeat the unplug test 5× in a row: no crash, no growing memory (Settings → Developer options → Memory, optional).

## 4. Two drives

- [ ] With a second Offlinka drive (or a copy of `Knihovna` on another USB stick): Nastavení → Flashka → "Vybrat jinou flashku", pick it: books of both are listed, no duplicates.
- [ ] Unplug one: its books disappear, the other's stay; Settings shows it as "Není zapojená".

## 5. Reading

- [ ] Česká Wikipedie main page opens in **under 2 s** (SPEC quality bar): ____ s.
- [ ] Scroll a long article (e.g. "Praha", "Česko"): smooth, images load, no white flashes.
- [ ] Links between articles work; back/forward buttons and the system back gesture go through the article history, then leave the reader.
- [ ] Article tables (infobox) fit the screen or scroll sideways; no horizontal page scroll.
- [ ] Text size (Aa): 80–200 % changes the article text live; the value is kept after restart.
- [ ] Light/dark toggle in the reader: the article switches without reloading; dark mode is readable (no white boxes).
- [ ] "Hledat v článku": matches highlighted, "2 z 7" counter, up/down jump, close clears highlights.
- [ ] Save an article (bookmark): toast, it appears in Uložené; remove it again.
- [ ] An internet link (e.g. "Externí odkazy" at the end of an article): dialog "Odkaz vede na internet", copy link works, nothing opens a browser.
- [ ] A link to another Wikimedia project we have (e.g. a Wikislovník link) opens offline in that book.
- [ ] A phone number in the guides (155, 112): tapping opens the dialer with the number.
- [ ] A PDF link in the guides: opens in the phone's PDF viewer, or a calm "can't open" message if none is installed.
- [ ] Missing article (a red link): calm "Článek tu není" page.
- [ ] Rotate the phone in the reader: the article stays, no reload to the top.
- [ ] Switch the app language while an article is open (Nastavení → Jazyk): the app restarts the screen and the article is still there.

## 6. Maps and games (JavaScript + their own assets)

- [ ] Mapa Česka opens, the map renders, pinch-zoom down to street level works, panning is smooth, labels readable.
- [ ] Mapa Slovenska the same.
- [ ] Map search / place list inside the map viewer (if the viewer has one) works offline.
- [ ] Hry: each game starts, plays with touch, score is kept after leaving and reopening (local storage).
- [ ] Audio/video in an article (if the edition has any): plays, seeking works (range requests).

## 7. Search

- [ ] Typing "cesky krumlov" (no diacritics) shows "Český Krumlov" first, while typing, no visible lag per key (target ≤ 100 ms with the drive's search index).
- [ ] Results are grouped by book, Czech Wikipedia first; "Zobrazit další" limits to one book.
- [ ] Recent searches appear when the field is empty; removing one works; Nastavení → "Vymazat nedávno hledané" clears them.
- [ ] Drive with `.offidx` files: Knihovna → details of Česká Wikipedie says "Rychlé hledání: Ano"; diacritics-free search works. App memory after opening (Settings → Apps → Offlinka → Memory): ____ MB.
- [ ] Pull the drive while typing a search with the index attached: no crash.

## 8. Home, library, saved, settings, about

- [ ] Home quick links: První pomoc and Mějte doma předem open the guides; Náhodný článek opens a random Czech article each time.
- [ ] "Pokračovat ve čtení" shows the last article and opens it.
- [ ] Knihovna: every book with size, date, language; phone editions shown as replaced; details show the file name and "Rychlé hledání".
- [ ] Historie grouped Dnes / Včera / date; clearing asks first; "Ukládat historii" off stops recording.
- [ ] Language: Podle systému / Čeština / Slovenčina / English switch the whole UI; on Android 13+ the choice also shows in the system's per-app language setting.
- [ ] Theme: Podle systému / Světlý / Tmavý; status bar icons stay readable in both.
- [ ] O aplikaci: version, licences readable, no "Kiwix" anywhere else in the UI.

## 9. Platform and accessibility

- [ ] Airplane mode on the whole time: everything above works (the app has no INTERNET permission: Settings → Apps → Offlinka → Permissions shows none).
- [ ] System font size at maximum + display size large: no clipped text on Home, Search, Settings; buttons still tappable.
- [ ] TalkBack: Home tiles, search results, reader buttons (zpět, vpřed, uložit, velikost písma) are announced with Czech labels.
- [ ] Gesture navigation and 3-button navigation: no content hidden behind the bars; the keyboard does not cover the search results.
- [ ] Tablet / USB-C iPad-size Android (if available): layout uses 3 columns on Home, reader text width is limited.
- [ ] 16 KB page size device (Pixel 8+ with Android 15 16 KB mode, optional): app starts (native lib is page-aligned).
- [ ] Battery: 30 min of reading with the drive plugged in, note battery drop: ____ %.
