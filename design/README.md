# Offlinka design system

**The look of both apps, ready to rebuild natively.** Match the drive's start page (`reference/`) and offlinka.cz: paper background, forest hero with contour lines and a ridge, ink-outlined cards with hard offset shadows, orange only where it matters, Archivo Expanded headlines.

- **Mockups (the reference to implement):** `mockups/png/<screen>-<light|dark>.png`, 393 × 852 pt at 2×. Open `mockups/index.html` for all of them; `mockups/*.html` are the sources (`?state=…`, `?lang=sk|en`, `?theme=dark`).
- Edit sources, then regenerate; never hand-edit `out/`.

| What | Source | Generated / where it goes |
|---|---|---|
| Tokens | `tokens.json` | `out/android/{Color,Type,Theme}.kt`, `out/ios/Brand.swift`, `out/web/tokens.css` |
| UI strings cs/sk/en | `strings/*.json` | `out/android/values*/strings.xml`, `out/ios/Localizable.xcstrings` |
| Line icons (58) | `icons/svg/*.svg` | `out/android/drawable/ic_*.xml`, `out/ios/icons/Icons.xcassets` |
| App icon | `tools/appicon.py` → `appicon/*.svg` | `out/android/{drawable,mipmap-*}`, `out/android/play-store-512.png`, `out/ios/AppIcon.appiconset` |
| Fonts | `brand/fonts/static/*.woff2` | `fonts/*.ttf` + `fonts/OFL.txt` |
| Reader stylesheet | `reader/offlinka-reader.css`, `reader/inject.js` | served by the app at `/_offlinka/…` |

Regenerate: `python3 design/tools/generate.py` (stdlib only). PNGs: `source /srv/offlinka/env.sh`, then one at a time `atd-render timeout 300 node design/tools/render-mockups.cjs [name]` and `atd-render timeout 120 node design/tools/render-icons.cjs`.

## Principles

- **Calm.** One accent per screen: the orange primary button, or the orange peak. Everything else is paper, ink and forest.
- **The peak** (the orange i-dot of the logo) marks "you are here" and "start here": above the active bottom-bar icon, before the Home section title and before article section headings.
- **Cards are paper with an ink outline and a hard shadow** (offset, no blur). Lists are quieter: line-coloured outline, no shadow.
- **Big readable text.** Body 17, never below 13. All sizes in sp / Dynamic Type; layouts must grow, never clip.
- **Left aligned.** Centre only the Connect illustration and short captions.
- No stock Material look: no FABs, no tonal elevation (`surfaceTint` is transparent), no ripples in orange.

## Colour roles

Light / dark are separate hand-picked steps (`tokens.json` → `color`). Use roles, not raw hex.

| Role | Light | Dark | Use |
|---|---|---|---|
| `bg` | #F5F0E3 | #0F1A14 | screen background (paper) |
| `surface` | #FFFDF7 | #16241C | cards, sheets, bars, fields |
| `surfaceMuted` | #E9DFC8 | #1E2F25 | notes, table heads, tags |
| `ink` / `ink2` / `ink3` | #17231C / #5F5B4B / #736C59 | #EEE8D8 / #BDB6A2 / #99937D | text: primary / secondary / meta + placeholder |
| `outline` | #17231C | #4B6B59 | 1.5 outline of cards, buttons, fields, icon boxes |
| `line` / `lineSoft` | #CDBF9F / #E2D6B8 | #2E4236 / #22342A | list group outline, bar borders / row dividers |
| `hero` / `heroDeep` / `heroRidge` | #1F3D2F / #14291F / #2F5642 | #173024 / #0F2219 / #1F3D2F | forest header, toast, ridge band |
| `onHero` / `onHero2` / `heroLine` | #F5F0E3 / #DDE4D2 / #4B6B59 | same / same / #3E5E4C | text and 1 px outlines on the hero |
| `primary` / `primaryEdge` / `onPrimary` | #F26A1B / #B94A0B / #1A1206 | #F26A1B / #9A3D08 / #1A1206 | the one main button per screen |
| `accent` / `onAccent` / `accentSoft` | #1F3D2F / #F5F0E3 / #DCE6D3 | #A8CB94 / #0F1A14 / #22392B | selected segment, switch on, step numbers, active icon |
| `link` + `linkUnderline` | #2F5642 + #7C9A68 | #A8CB94 + #5E7F4F | text links, always underlined 1.5 |
| `warnBg` / `warnInk` / `warnLine` | #FBDCC8 / #A33F05 / #E3A27A | #3A2416 / #FFB98C / #7A4424 | warning note |
| `danger` | #B4321F | #E5735D | destructive rows (Vymazat historii) |
| `ok` | #7C9A68 | #8FAF7A | "connected" dots |
| `shadow` / `shadowStrong` / `shadowSelected` | ink 16 % / #08140E 45 % / forest | black 55 % / black 65 % / black | hard shadows |
| `scrim` | #14291F 45 % | black 60 % | behind sheets and dialogs |

Contrast (WCAG): ink on bg 14.3, ink2 on surface 6.7, ink3 on surface 5.1 (dark 5.2), link on surface 8.2, onPrimary on primary 6.1, dark ink on dark bg 14.6, dark link 9.9. Never put white text on orange.

**Book tones** (icon box background / foreground, `bookTone(family, dark)` / `BookFamily.tone`): `wiki` Wikipedias, `maps`, `aid` guides + medicine, `books` Wikibooks/Wikisource/Wikiquote/Wiktionary/Wikiversity, `games`, `travel` Wikivoyage.

## Type

Archivo 400/600/700 for text, **Archivo Expanded 800** for display. Sizes sp = pt; line height fixed; tracking in em.

| Style | Font | Size / line | Use |
|---|---|---|---|
| `display` | Expanded 800, −0.015 | 30 / 34 | Home hero title |
| `headline` | Expanded 800, −0.01 | 24 / 29 | screen titles (Knihovna, Nastavení), Connect title |
| `title` | Expanded 800, −0.01 | 19 / 24 | wide tile, sheet titles, dialog titles |
| `titleSmall` | Expanded 800 | 17 / 22 | tile names; library rows use 15.5 / 20 |
| `button` | Expanded 800 | 17 / 22 | primary and secondary buttons |
| `subhead` | 700 | 17 / 23 | section headings, reader title |
| `body` / `bodyStrong` | 400 / 600 | 17 / 25 | text, list titles (16 / 21 in rows) |
| `bodySmall` | 400 | 15 / 21 | descriptions, notes |
| `label` | 600 | 15 / 20 | segments, chips, group headers |
| `labelSmall` | 600 | 13 / 17 | badges, counts, nav labels (12.5) |
| `caption` | 400 | 13 / 18 | meta lines (date, language, size) |
| `searchInput` | 600 | 18 / 24 | text in the search field |

## Spacing, shape, elevation

- Spacing 2 · 4 · 8 · 12 · 16 · 20 · 24 · 32 · 48 (`xxs … huge`). **Screen gutter 16.** Gap between tiles 12. Between sections 22–24.
- Radii: tags 3, icon boxes and quick chips 6, **cards, buttons, fields 8**, sheets and dialogs 12.
- Outline 1.5 (selected/focused field 2), hairlines 1.
- Hard shadows (offset copy of the shape, no blur; Compose `Modifier.hardShadow(Offlinka.elevation.card, shape)`, SwiftUI `.hardShadow(BrandElevation.card)`):
  - `card` x 4 y 5 `shadow`: tiles, secondary buttons, slider knob, drive card.
  - `raised` x 5 y 6 `shadowStrong`: search field on the hero, dialogs, toast.
  - `selected` x 4 y 5 `shadowSelected`: a chosen card.
  - `button`: primary button sits on a 4 high `primaryEdge` bottom edge; pressed: edge 2 and content moves down 2.
- Touch targets ≥ 48. Icons 24 (20 in dense rows, 26 in book boxes).
- Motion: 120 / 200 / 280 ms, easing (0.2, 0.7, 0.2, 1). Only in answer to the user (sheet in, toast in, press). Off with reduce motion.

## Components (sizes in dp = pt)

- **Status bar / edge to edge:** draw behind it. Home, Connect, About: status bar sits on `hero` with light icons. Other screens: `bg` with dark icons (inverse in dark mode). Mockups use 44 top and 24 bottom insets; use the real insets.
- **Hero** (Home, Connect, About): `hero` with the contour texture `brand/topo-d.svg` (900 × 900 tile, offset so lines start off-screen) and a **ridge**: two mountain bands at the bottom, 30 high, `heroRidge` behind, `bg` in front. Polygon points (in % of width / band height): back band `0 58, 8.3 25, 14.6 50, 23.6 6.7, 31.3 43.3, 41.7 18.3, 51.4 56.7, 62.5 8.3, 71.5 41.7, 81.9 11.7, 90.3 48.3, 100 23.3` + bottom corners; front band `0 80, 10.4 51.7, 18.1 73.3, 29.9 36.7, 40.3 70, 49.3 51.7, 61.1 81.7, 73.6 40, 83.3 68.3, 92.4 48.3, 100 73.3` + bottom corners. Padding 6 top, 16 sides, 46 bottom (ridge included).
- **Hero top row:** 48 high. Logo `brand/logo.svg` 22 high (paper wordmark, orange peak). Right: badge 28 high, 1 `heroLine` outline, radius 4, 10 side padding, 8 dot `ok` + `labelSmall` `onHero2` (`status_offline`).
- **Search field:** 56 high, `surface`, 1.5 `outline`, radius 8, 14 left padding, search icon 24 `ink3`, 10 gap, `searchInput` text, placeholder `ink3`. On the hero add the `raised` shadow. Focused (Search screen): 52 high, 2 outline, caret `primary`, clear button 40.
- **Quick chips** (hero): 40 high, radius 6, 1 `heroLine` outline, fill `heroDeep` 35 %, icon 20 `onHero2` + `label` `onHero`, gap 8; one row that scrolls sideways (the third chip is cut on purpose). Keys `home_quick_first_aid` → guides `prvni-pomoc.html`, `home_quick_prepare` → `vypadek-proudu.html`, `home_quick_random` → `random_article()` of the main Wikipedia.
- **Continue reading:** shown when history is not empty. Margin 20 top, 16 sides; padding 11/8/11/12; `surface`, 1.5 `line`, radius 8; book box 36 + `labelSmall` `ink3` caption + 17/700 title + chevron `ink3`.
- **Section header (Home):** padding 24 / 16 / 12; `title` (Expanded 19) with the orange peak 14 × 12 before it (gap 9), like "Vyzkoušejte hned" on the drive; count on the right in `labelSmall` `ink3` (`library_books_count`).
- **Book tile:** `surface`, 1.5 `outline`, radius 8, `card` shadow, padding 14/12/14/14, min height 146. Book box 44 (radius 6, 1.5 `outline`, tone colours, icon 26), 12 gap, name `titleSmall` (wraps, hyphenates), 4 gap, description `bodySmall` at 14 / 19 `ink2`. Language tag top right for non-Czech content: 11.5 / 600, 1 `line` outline, radius 3 (`CZ`, `SK`, `EN`). Rows stretch to the tallest tile.
- **Wide tile:** the Wikipedia of the UI language (cs → Czech, sk → Slovak, en → Czech). Full width, row layout, padding 14 (52 on the right, room for the language tag), name in `title`.
- **Tile order:** UI-language Wikipedia (wide), Mapa Česka, Nouzové příručky, the other Wikipedia (sk/cs), Anglická Wikipedie, Medicína, Mapa Slovenska, Hry, Wikivoyage, Wikiknihy, Wikizdroje, Wikicitáty, Wikislovník, Wikiverzita, then unknown ZIMs (their own title, `book_other_desc`). Hide books that are not on the drive.
- **Bottom bar:** 64 + inset, `surface`, top 1 `line`. Four equal items: icon 24 + label 12.5 / 600, gap 3. Inactive `ink2`. Active: label `ink`, icon `accent`, and the **peak**: orange triangle 12 × 9, 5 from the top edge, centred above the icon. Items: Domů `home`, Knihovna `library`, Uložené `bookmark`, Nastavení `settings`.
- **App bar (inner):** 56, `bg`, 1 `line` bottom border when content scrolls under it. Buttons 48. Large title variant: `headline`, padding 14 / 16 / 0, optional `bodySmall` subtitle `ink2`.
- **List group:** margin 0 16, `surface`, 1.5 `line`, radius 8, clip. Group header: padding 22 / 16 / 8, `label` `ink2` (+ optional `caption` note on the right). **Row:** min 56, padding 10/12/10/14, gap 12; leading icon 24 `ink2` or book box 36; title 16 / 600 (library rows: Expanded 15.5); second line 14 / 19 `ink2`; meta 13 / 18 `ink3`; trailing chevron `ink3`, info button 44, value text, switch or time. Dividers 1 `lineSoft`. Unavailable rows at 62 % opacity.
- **Search results:** flat rows on `bg`, min 52, left padding 52 (aligned with the group icon), divider `lineSoft`. Title 17: the matched part 700 `ink`, the rest 400 `ink2`. Redirects: second line 14 `ink3` with the `redirect` icon 18 and the target title. Trailing 44 button `fill_query` (`search_fill`). Group header: book box 26 + book name `label` 14 `ink2`, padding 18 / 16 / 6. Max 4 rows per book, then `search_show_more` link. Order: UI-language Wikipedia first, then by best match.
- **Recent searches:** header row (`search_recent_title` + `action_clear` link), rows with `clock` icon, the term, remove `close` 44. The tip `search_tip` in an info note below.
- **Primary button:** 52, radius 8, `primary`, `button` text `onPrimary`, optional 24 icon, 4 `primaryEdge` bottom edge, full width in forms. One per screen.
- **Secondary button:** 52, `surface`, 1.5 `outline`, `card` shadow, `button` text `ink`.
- **Text link:** 16 / 700 `link`, underline 1.5 `linkUnderline`, offset 3.
- **Segmented control:** 44, 1.5 `outline`, radius 8, `surface`; segments size to their label (padding 10), 1.5 dividers; selected `accent` fill + `onAccent` text. Used for Uložené / Historie and light / dark.
- **Switch:** 52 × 32, on `accent` with an `onAccent` knob 24, off `surfaceMuted` with an `ink3` knob.
- **Slider:** track 4 `line`, filled part `accent`, 5 tick marks 2 × 6 `ink3`, knob 26 `surface` + 1.5 `outline` + `card` shadow. Text size steps 85 · 100 · 115 · 130 · 150 %.
- **Note:** padding 12 / 14, radius 8, `surfaceMuted`, `bodySmall` `ink2`, icon 24, optional 16 / 700 title. **Warning:** `warnBg`, 1.5 `warnLine`, icon `warnInk`, text `ink`.
- **Steps** (Connect only, a real sequence): 32 circles `accent` with 16 / 700 numbers, gap 12, text 16 / 22.
- **Bottom sheet:** `surface`, 1.5 `outline` top edge, radius 12 top, padding 8 / 16 / 40, grab handle 36 × 4 `line`, title `title`. Scrim `scrim` (a soft 18 % forest scrim for the text-size sheet so the article stays visible).
- **Dialog:** 24 side margins, `surface`, 1.5 `outline`, radius 12, `raised` shadow, padding 22/20/18, icon 26, `title`, `body` `ink2`.
- **Toast:** 16 side margins, 104 above the bottom edge, min 52, radius 8, `heroDeep`, 15 / 600 `onHero`, action 700 in #FFB98C, `raised` shadow. Wording: same verb as the action (Uložit → `reader_saved_toast`).
- **Drive card** (Library): `hero` + contour texture, 1.5 `outline`, radius 8, `card` shadow, padding 14 / 14 / 14 / 16, drive icon in a 44 box with 1 `heroLine`, name in Expanded 17, two lines `onHero2`.

## Screens

Each mockup lists its states in a comment at the top of the HTML. String keys are the `data-s` attributes in the HTML.

1. **Connect** (`connect`, `connect-wrong-folder`, `connect-help`, `unplugged`): hero with the phone + drive illustration (236, centred, colours from the theme), then `headline` title, `connect_body`, two steps (Android `connect_step_pick_android`, iOS `connect_step_pick_ios`), primary `connect_button` with the `drive` icon, `connect_remember` caption, `connect_help_link` → help sheet (`help_1`, `help_2`, then `help_3_android` on Android or `help_4_ios` on iOS, `help_5`). Wrong folder or no ZIMs: warning note replaces the steps, button becomes `connect_pick_again`; other errors use `connect_err_notfound_*`, `connect_err_noaccess_*`. **Unplugged later:** same hero with the drive pulled out, `unplugged_title`, `unplugged_body`, a waiting row (8 dot `ok` with a 5 halo + `unplugged_waiting`), secondary `unplugged_pick_other`. Resume automatically when the same drive returns; never crash, never show a raw error.
2. **Home** (`home`, `home-full`, `home-en`, `home-sk`): hero (top row, `display` title `home_title` 18 below, search field, quick chips), continue-reading card, section header, tiles, bottom bar. Tapping the field opens Search with the keyboard up.
3. **Search** (`search`, `search-recent`): app bar replaced by back button + focused field (padding 6 / 16 / 10 / 4, bottom 1 `line`). Results update on every keystroke; diacritics-insensitive.
4. **Reader** (`reader`, `reader-find`, `reader-text-size`, `reader-offline-link`): top bar: `home` button (leave the reader), then the book (box 26 + name 15 / 600 `ink2`) while the article's own h1 is visible; once the h1 scrolls under the bar the article title replaces it (17 / 700, ellipsized, 200 ms fade), Save `bookmark` / `bookmark_filled` in `accent`, More (`reader_history`, light/dark, `reader_copy_link`). WebView between the bars. Bottom toolbar 56 + inset, `surface`, top 1 `line`, five 48 buttons spread evenly: back, forward (disabled 55 %), find, text size, the book's main page (the book's own icon). Find mode replaces the top bar: close, field 42 high with 2 outline and the count `reader_find_count` 14 `ink3` inside, previous / next. Native find highlights cannot be themed; fine. Links to the internet open the dialog (`reader_external_*`, copy + close), never a browser.
5. **Library** (`library`, `library-details`, `library-full`): large title + subtitle, drive card, groups `library_group_*` with rows (book box 36, Expanded name, description, meta line "month year, language, size" in `caption`, info button). Phone editions last, dimmed, `library_replaced`. Row tap = open the book's main page; info = details sheet with a key/value list (dt `ink2`, dd 600 right-aligned, tabular numbers, dividers `lineSoft`) and primary `library_open_main`. Sizes: GB with 2 significant decimals ≥ 1 GB, else MB.
6. **Saved / History** (`saved`, `history`): large title, segmented control (margin 16), list group. Saved rows: title + book line + filled bookmark in `accent` (tap removes; toast with Undo). History: day headers (`history_today`, `history_yesterday`, then "weekday d. month"), time 13 / 600 `ink3` tabular, `history_clear` link in the first header (confirm dialog `history_clear_confirm_*`). Empty states: `saved_empty_*`, `history_empty_*` as a note.
7. **Settings** (`settings`): Vzhled (language row → picker with endonyms, light/dark segmented, text size slider + value + a preview line in reader type 19.5 / 31 on `bg`), Flashka (connected drive with `ok` dot, choose another, keep-a-copy row dimmed with the `settings_soon` tag), Hledání a historie (switch, clear searches, clear history in `danger`), privacy line with `lock` 18 in `caption`, About row.
8. **About** (`about`): hero header with back, logo 30 high, `about_tagline`, two badges (version, edition), privacy note, licences group (Wikipedia CC BY-SA, OSM ODbL, ZIM by Kiwix/openZIM, games MIT, Archivo OFL, open-source notices → a list screen), contact row (`info@offlinka.cz`, copy only, no mail intent needed), footer `caption`: `about_lic_app` + `about_trademark`.

## Book mapping

Match by the ZIM `Name` metadata / file name prefix. Prefer `Knihovna/*maxi*` over `Telefon/*mini*` of the same language.

| ZIM | Name key | Description key | Icon | Tone |
|---|---|---|---|---|
| `wikipedia_cs_all_maxi` | `book_wikipedia_cs` | `book_wikipedia_full_desc` | `book_wikipedia` | wiki |
| `wikipedia_cs_all_mini` | `book_wikipedia_cs` | `book_wikipedia_mini_desc` | `book_wikipedia` | wiki |
| `wikipedia_sk_all_*` | `book_wikipedia_sk` | full / mini desc | `book_wikipedia` | wiki |
| `wikipedia_en_top1m_nopic` | `book_wikipedia_en` | `book_wikipedia_en_desc` | `book_wikipedia_en` | wiki |
| `wikipedia_en_medicine_nopic` | `book_medicine` | `book_medicine_desc` | `book_medicine` | aid |
| `maps_en_czech-republic` / `maps_en_slovakia` | `book_maps_cz` / `book_maps_sk` | `book_maps_desc` | `book_maps` | maps |
| `offlinka_cs_prirucky` | `book_guides` | `book_guides_desc` | `book_guides` | aid |
| `offlinka_mul_hry` | `book_games` | `book_games_desc` | `book_games` | games |
| `wikibooks_cs` | `book_wikibooks` | `book_wikibooks_desc` | `book_wikibooks` | books |
| `wikiquote_cs` | `book_wikiquote` | `book_wikiquote_desc` | `book_wikiquote` | books |
| `wikisource_cs` | `book_wikisource` | `book_wikisource_desc` | `book_wikisource` | books |
| `wikiversity_cs` | `book_wikiversity` | `book_wikiversity_desc` | `book_wikiversity` | books |
| `wiktionary_cs` | `book_wiktionary` | `book_wiktionary_desc` | `book_wiktionary` | books |
| `wikivoyage_en_europe` | `book_wikivoyage` | `book_wikivoyage_desc` | `book_wikivoyage` | travel |

## Icons

- 24 grid, 1.75 stroke, round caps and joins, 2 padding, `currentColor`. Sources `icons/svg/`, review sheet `icons/sheet.html`.
- Android: `R.drawable.ic_<name>` (tint with `LocalContentColor` / `Icon(tint=…)`). iOS: `Image("<name>")` from `Icons.xcassets` (template images, tint with `.foregroundStyle`).
- Book icons: `book_wikipedia`, `book_wikipedia_en`, `book_medicine`, `book_maps`, `book_guides`, `book_games`, `book_wikibooks`, `book_wikiquote`, `book_wikisource`, `book_wikiversity`, `book_wiktionary`, `book_wikivoyage`.
- UI: `search home library bookmark bookmark_filled settings history clock arrow_back arrow_forward chevron_* close find_in_page text_size contrast sun moon toc more_vert random first_aid checklist drive drive_off phone_drive phone_copy globe no_internet info help warning check plus minus trash fill_query copy folder retry mail lock article redirect`.

## App icon

- Open paper book with the logo's orange peak above its spine, hard shadow, faint contour lines on forest. Reads at 48 px (`appicon/preview.png`).
- **Android:** copy `out/android/drawable/ic_launcher_{background,foreground,monochrome}.xml`, `out/android/mipmap-anydpi-v26/ic_launcher{,_round}.xml` and `out/android/mipmap-*dpi/ic_launcher{,_round}.png` into `res/`; manifest `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"`. 432 px layer PNGs in `appicon/png/`. Play Console: `out/android/play-store-512.png`.
- **iOS:** copy `out/ios/AppIcon.appiconset` into the asset catalog (single 1024 size; light, dark and tinted appearances).

## Reader stylesheet

- `reader/offlinka-reader.css` restyles MediaWiki pages (Wikipedia, Wikibooks, Wiktionary, Wikivoyage … from mwoffliner 2.x, Vector 2022 markup). **Every rule is scoped to `body.mediawiki`**, so maps, games and our guides are untouched (checked: before/after identical).
- **Serve** on the article's own origin: `/_offlinka/reader.css`, `/_offlinka/inject.js`, `/_offlinka/fonts/<file>.ttf` (Android `https://offlinka.local/_offlinka/…`, iOS `offlinka://…/_offlinka/…`).
- **Inject** when serving a MediaWiki HTML page: insert before `</head>` `<link id="offlinka-reader-css" rel="stylesheet" href="/_offlinka/reader.css"><script src="/_offlinka/inject.js"></script>` (no flash). Optional first line of the script response: `window.__offlinka={theme:'dark',scale:1.15};`.
- **Theme and size:** `offlinkaReader({theme: 'light'|'dark'|'system', scale: 0.85…1.5})` via `evaluateJavascript` / `evaluateJavaScript`. It sets `html.offlinka-dark|light`, Vector's own `skin-theme-clientpref-day|night`, and `--ofl-scale`. Keep Android `WebSettings.textZoom = 100`; the scale does the work on both platforms.
- What it does: paper / forest colours from the tokens, Archivo body 17 / 1.6, max line 680, Expanded headings with the orange peak before h2, underlined forest links, quiet dotted links for the internet, full-width infobox card, figures with captions, two-column galleries, sideways-scrolling tables, calm notes, navboxes and Commons boxes hidden on phones, dark mode with its own steps, inverted formulas and a soft backing for transparent diagrams.
- Pictures missing from the ZIM (the mini editions put an alt-text SVG in their place) are hidden with their caption by `inject.js` + CSS, so the mini reads clean.
- Before/after at 393 px (Vector, Offlinka, Vector dark, Offlinka dark): `reader/screenshots/*.jpg` for Sněžka (maxi, mini), a sports article with tables, Hlavní strana, Pythagorova věta (formulas), Wikivoyage. Harness: `reader/test/` (`extract.py` needs libzim in a scratch venv, test only; `render.cjs` serves like the app and injects like the app).

## Fonts

- `fonts/archivo_{regular,semibold,bold}.ttf`, `fonts/archivo_expanded_extrabold.ttf`, licence `fonts/OFL.txt`. Android `res/font/` as is; iOS PostScript names `Archivo-Regular`, `Archivo-SemiBold`, `Archivo-Bold`, `ArchivoExpanded-ExtraBold` (UIAppFonts).
- Coverage: Basic Latin, Latin-1, Latin Extended-A (every cs/sk letter, also Polish, Hungarian, German), quotes, dashes, ellipsis, €. Check: `fonts/glyph-test.png`.
- Built by `tools/fonts.py` (fontTools, dev only): each file got its own name table; the woff2 instances all said `Archivo-SemiBold`, which iOS would have merged.

## Strings

- `strings/{cs,sk,en}.json`, cs is the default locale (Android `values/`). Slovak says „USB kľúč“, like offlinka.sk.
- Placeholders: `{name}` text, `{n}` `{count}` `{current}` `{total}` integers. Plurals (`library_books_count`, `library_articles_count`) have one / few / many / other.
- Content descriptions: `cd_*`; icon-only buttons reuse action strings (`action_back`, `reader_save` …).
- The generator rejects em dashes, emoji and "Kiwix" outside `about_*`. Never hard-code UI text.

## Accessibility

- 48 touch targets everywhere (icon buttons 48, rows ≥ 56, chips 40 inside a 48 hit area).
- Font scale up to 200 %: titles wrap (no ellipsis except the reader title), tiles grow, the hero grows, the bottom-bar labels may wrap to two lines.
- Light and dark contrast above 4.5 for all text roles (see Colour roles). Focus: 3 outline `focus` with 2 offset (keyboard / switch access).
- The peak is decoration; selection is also carried by colour and weight, and by `selected` semantics.

## Self-review (phone size, against the drive's start page)

- Unmistakably Offlinka: same hero (forest, contours, ridge, paper logo, Expanded question, card search with hard shadow, outlined chips) and the same paper tiles. Kept.
- Fixed after review: tile names 16 → 17 and the Home section title now carries the peak like "Vyzkoušejte hned"; wide tile reserves room for its language tag (Slovak title ran into it); `ink3` darkened to pass 4.5 on cards; reader top bar shows the book instead of repeating the article title; Connect illustration enlarged to fill the screen; settings rows no longer inherit block spans.
- Deliberately different from the drive: no orange "Hledat" button in the field (results come while typing, the keyboard key submits), no Kiwix hints, no "V počítači / V mobilu" cards.

## Open points for the founders

- App licence line (`about_lic_app` says only "© 2026 Ollcee s.r.o."; add the licence name when decided).
- iOS dark icon uses our own dark background (not transparent); switch if Apple review prefers the system background.
