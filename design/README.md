# Offlinka design system

**Source of truth for the look of both apps.** Edit the sources, run the generator, never hand-edit `out/`.

- `tokens.json`: colours (light + dark), type scale, spacing, radii, sizes, hard shadows, motion.
- `strings/{cs,sk,en}.json`: every UI label. cs is the default locale.
- `fonts/`: Archivo TTFs for the apps + `OFL.txt` + `glyph-test.png`.
- Generate: `python3 design/tools/generate.py` (stdlib only) → `out/android/`, `out/ios/`, `out/web/tokens.css`.

## Android

- `out/android/{Color,Type,Theme}.kt` → `app/src/main/java/cz/offlinka/app/ui/theme/` (package `cz.offlinka.app.ui.theme`; `Type.kt` imports `cz.offlinka.app.R`).
- `fonts/*.ttf` → `app/src/main/res/font/` (names are already res-safe).
- `out/android/values*/strings.xml` → `app/src/main/res/values*/` (`values` = cs, `values-sk`, `values-en`).
- Use `OfflinkaTheme { … }` and read roles via `Offlinka.colors.ink`, `Offlinka.type.body`, `Offlinka.space.l`, `Offlinka.elevation.card` + `Modifier.hardShadow(shadow, shape)`.

## iOS

- `out/ios/Brand.swift` → app target. Colours `Color.brandInk` …, fonts `Font.brandBody` …, `.brandText(.body)` for line height + tracking, `.hardShadow(BrandElevation.card)`.
- `fonts/*.ttf` → app target + `UIAppFonts` in Info.plist. PostScript names: `Archivo-Regular`, `Archivo-SemiBold`, `Archivo-Bold`, `ArchivoExpanded-ExtraBold`.
- `out/ios/Localizable.xcstrings` → app target (source language cs; plurals one/few/many/other).

## Fonts

- Converted from the drive's woff2 subsets by `tools/fonts.py` (needs fontTools + brotli in a scratch venv, dev only).
- Coverage: Basic Latin, Latin-1, Latin Extended-A (all cs/sk letters, Polish, Hungarian, German …), typographic quotes, dashes, ellipsis, €.
- Each file got its own name table (the woff2 instances all said `Archivo-SemiBold`, which iOS would have collapsed into one font).

## Strings

- `{name}` = text placeholder (`%1$s` / `%1$@`), `{n}` `{count}` `{current}` `{total}` = integer (`%1$d` / `%1$lld`).
- Plural keys are objects (`library_books_count`, `library_articles_count`).
- The generator refuses em dashes, emoji and "Kiwix" outside `about_*`.
- Book names come from strings (`book_*`), matched by ZIM name prefix; unknown ZIMs show their own metadata title with `book_other_desc`.
