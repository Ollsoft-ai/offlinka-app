# iOS UI smoke test plan

**Automated** (`OfflinkaUITests/SmokeUITests`, simulator, demo drive via `-demo-drive`): home → search „cesky krumlov“ → article → save → „Zpět“ back to Search with the query → Uložené → Knihovna → Nastavení; „První pomoc“ quick link; Slovak UI leads with the Slovak Wikipedia; external link shows the offline notice.

**By hand on a device** (iPhone 15+ or USB-C iPad, real drive, edition 2026-11.3). Tick each in cs, then spot-check sk and en.

## Connect and drive

- [ ] First launch: connect screen with illustration, steps, „Vybrat flashku“. Files sheet → OFFLINKA → Otevřít → home with all books.
- [ ] Pick the `Knihovna` folder itself: works (phone editions missing). Pick `3 Nouzové příručky`: „Tady knihy nejsou“.
- [ ] Cancel the Files sheet: still on the connect screen, no error.
- [ ] Kill and relaunch with the drive in: opens straight to home (bookmark).
- [ ] Relaunch without the drive: „Flashka je odpojená“; plug in → home within ~3 s, no tap needed.
- [ ] Unplug while an article is open: calm unplugged screen within ~3 s, no crash; plug back → same tab and page.
- [ ] Unplug while typing a search (risk 1 in UNVERIFIED.md): no crash.
- [ ] Settings → „Vybrat jinou flashku“ → connect screen; pick the drive again.

## Round 2 (0.2.0)

- [ ] First start on edition 2026-11.3 (no `.offidx`): Home appears as soon as the UI-language Wikipedia is open; other tiles show „Otevírám…“ and fill in.
- [ ] Search shows „Připravuji hledání…“ with one percentage (UI-language Wikipedia only); the X only hides the note; the tip appears once that Wikipedia is indexed; „cesky krumlov“ then finds Český Krumlov.
- [ ] Screen stays on while an index builds; lock the phone mid-build, unlock: the build continues or restarts cleanly; kill the app mid-build: the next start continues with what is missing.
- [ ] Settings → Knihovna → Anglická Wikipedie → „Připravit hledání“ (only on 3 GB+ phones); stop it from the same details; the next start does not resume it.
- [ ] Unplug during a build and during reading: unplugged cover, no crash, no hang; plug back: same page.
- [ ] A broken ZIM (rename a copy to garbage on a test drive): its tile shows the warning line, tap → „Zkusit znovu“ card.
- [ ] Connect and Unplugged: „Otevřít první pomoc“ opens the bundled first-aid card without a drive; closes back.
- [ ] Reader: „Zpět“ always works (article from search goes back to the results); book name opens its main page; „Uložit“ in the bar; maps show only a floating „Zpět“.
- [ ] Settings → „Vybrat jinou flashku“ with a wrong folder keeps the books open (short message); „Zapomenout flashku“ goes to Connect.
- [ ] Largest Dynamic Type on an iPhone SE-size screen: „Vybrat flashku“ visible without scrolling; one tile column; theme choice as a list.

## Home, search, reader

- [ ] Tiles in SPEC order with icon and one-line description; tap opens the book's main page.
- [ ] „První pomoc“ and „Mějte doma předem“ open the guides; „Náhodný článek“ opens a random Czech article.
- [ ] Search „cesky krumlov“ finds Český Krumlov; results grouped by book; „Zobrazit další“; the fill-arrow puts a title into the field; recent searches appear and clear.
- [ ] Keystrokes feel instant on cs maxi (<100 ms per letter).
- [ ] Article: our stylesheet (Archivo, paper colours), images, tables, infobox; links between articles; back/forward buttons and edge swipe.
- [ ] External link → „Odkaz vede na internet“ with copy; nothing loads (check the Network instrument or Airplane mode banner).
- [ ] Find in page, contents sheet jumps to a heading, text size −/+ changes only Wikipedia pages, theme toggle.
- [ ] Save → toast; Uložené shows it; remove by swipe. History grouped Dnes/Včera; clear history asks first.
- [ ] Maps: map renders, pans, zooms. Games: 2048 plays and keeps the best score after relaunch. Guides PDF opens.
- [ ] Links into a book that is not on the drive show the calm „Kniha není na zapojené flashce“ page.

## Library, settings, about

- [ ] Library groups, sizes, dates, languages; „Menší verze pro telefon“ lists the replaced minis; detail screen values; search in one book.
- [ ] Language switch cs/sk/en applies at once everywhere; „Podle systému“ follows the iOS per-app language.
- [ ] Theme light/dark/system, including the reader; text size preview.
- [ ] „Ukládat historii“ off: new articles are not recorded.
- [ ] About: version, edition 2026-11, licences, open-source list.

## Accessibility and devices

- [ ] VoiceOver: every button has a label (tiles „Otevřít: …“, save on/off), headers are headers.
- [ ] Largest Dynamic Type: no clipped text on home, search, settings.
- [ ] iPad portrait/landscape, split view; iPhone landscape.
- [ ] Airplane mode on during the whole session: everything above still works.
