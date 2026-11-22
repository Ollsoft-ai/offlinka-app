import Foundation

/// What kind of book a ZIM is, from its stable `name` (e.g. `wikipedia_cs_all`).
/// Decides the tile order, our own title and one-line description (`design/strings`, `book_*`),
/// the icon, the library group and whether the reader stylesheet applies.
/// The ZIMs' own descriptions are often poor, so the UI uses ours. Same rules as Android.
enum BookKind: String, CaseIterable, Sendable {
    // Order = order of the tiles on the home screen (SPEC "Screens" 2).
    case wikipediaCs, wikipediaSk, wikipediaEn, medicine, mapsCz, mapsSk, maps, guides, games
    case wikibooks, wikiquote, wikisource, wikiversity, wiktionary, wikivoyage, wikipediaOther, other

    static func of(name: String) -> BookKind {
        let name = name.lowercased()
        if name.hasPrefix("wikipedia_cs_") || name == "wikipedia_cs" { return .wikipediaCs }
        if name.hasPrefix("wikipedia_sk_") || name == "wikipedia_sk" { return .wikipediaSk }
        if name.hasPrefix("wikipedia_en_medicine") || name.hasPrefix("wikimed") { return .medicine }
        if name.hasPrefix("wikipedia_en_") || name == "wikipedia_en" { return .wikipediaEn }
        if name.hasPrefix("wikipedia_") { return .wikipediaOther }
        if name.hasPrefix("maps_") {
            if name.contains("czech") { return .mapsCz }
            if name.contains("slovak") { return .mapsSk }
            return .maps
        }
        if name.hasPrefix("offlinka_") && name.hasSuffix("_prirucky") { return .guides }
        if name.hasPrefix("offlinka_") && name.hasSuffix("_hry") { return .games }
        if name.hasPrefix("wikibooks_") { return .wikibooks }
        if name.hasPrefix("wikiquote_") { return .wikiquote }
        if name.hasPrefix("wikisource_") { return .wikisource }
        if name.hasPrefix("wikiversity_") { return .wikiversity }
        if name.hasPrefix("wiktionary_") { return .wiktionary }
        if name.hasPrefix("wikivoyage_") { return .wikivoyage }
        return .other
    }

    var order: Int { Self.allCases.firstIndex(of: self) ?? Self.allCases.count }

    /// `cs`, `sk`, `en` for the Wikipedias, nil otherwise.
    var wikipediaLanguage: String? {
        switch self {
        case .wikipediaCs: return "cs"
        case .wikipediaSk: return "sk"
        case .wikipediaEn: return "en"
        default: return nil
        }
    }

    var isWikipedia: Bool {
        switch self {
        case .wikipediaCs, .wikipediaSk, .wikipediaEn, .wikipediaOther: return true
        default: return false
        }
    }

    /// Home and library order (design README "Tile order"): the UI-language Wikipedia (wide tile),
    /// Mapa Česka, Nouzové příručky, the other Wikipedia, English Wikipedia, Medicína, Mapa Slovenska,
    /// Hry, Wikivoyage, Wikiknihy, Wikizdroje, Wikicitáty, Wikislovník, Wikiverzita, unknown books.
    /// Same numbers as Android's `BookSelector.homeOrder`.
    func homeOrder(uiLanguage: String) -> Int {
        if let language = wikipediaLanguage, language == uiLanguage { return 0 }
        // The map of the UI language's country comes right after its Wikipedia (UX review r2).
        if self == .mapsSk && uiLanguage == "sk" { return 10 }
        if self == .mapsCz && uiLanguage == "sk" { return 60 }
        switch self {
        case .mapsCz: return 10
        case .guides: return 20
        case .wikipediaCs: return 30
        case .wikipediaSk: return 31
        case .wikipediaEn: return 40
        case .wikipediaOther: return 45
        case .medicine: return 50
        case .mapsSk: return 60
        case .maps: return 65
        case .games: return 70
        case .wikivoyage: return 80
        case .wikibooks: return 90
        case .wikisource: return 100
        case .wikiquote: return 110
        case .wiktionary: return 120
        case .wikiversity: return 130
        case .other: return 200
        }
    }

    var icon: BrandIcon {
        switch self {
        case .wikipediaCs, .wikipediaSk, .wikipediaOther: return .bookWikipedia
        case .wikipediaEn: return .bookWikipediaEn
        case .medicine: return .bookMedicine
        case .mapsCz, .mapsSk, .maps: return .bookMaps
        case .guides: return .bookGuides
        case .games: return .bookGames
        case .wikibooks: return .bookWikibooks
        case .wikiquote: return .bookWikiquote
        case .wikisource: return .bookWikisource
        case .wikiversity: return .bookWikiversity
        case .wiktionary: return .bookWiktionary
        case .wikivoyage: return .bookWikivoyage
        case .other: return .article
        }
    }

    /// Colour family of the icon box (`BookFamily` in design's Brand.swift), as a raw value.
    var familyName: String {
        switch self {
        case .wikipediaCs, .wikipediaSk, .wikipediaEn, .wikipediaOther, .other: return "wiki"
        case .mapsCz, .mapsSk, .maps: return "maps"
        case .guides, .medicine: return "aid"
        case .wikibooks, .wikiquote, .wikisource, .wikiversity, .wiktionary: return "books"
        case .games: return "games"
        case .wikivoyage: return "travel"
        }
    }

    /// Group on the library screen.
    var libraryGroup: LibraryGroup {
        switch self {
        case .wikipediaCs, .wikipediaSk, .wikipediaEn, .wikipediaOther: return .wiki
        case .mapsCz, .mapsSk, .maps: return .maps
        case .guides, .medicine: return .aid
        case .wikibooks, .wikiquote, .wikisource, .wikiversity, .wiktionary, .other: return .books
        case .games, .wikivoyage: return .other
        }
    }

    /// Wikimedia pages get our reader stylesheet, text size and dark mode.
    /// Maps and games are web apps and stay exactly as they are; our guides have their own style.
    var usesReaderStyle: Bool {
        switch self {
        case .mapsCz, .mapsSk, .maps, .games, .guides, .other: return false
        default: return true
        }
    }

    var titleKey: Str? {
        switch self {
        case .wikipediaCs: return .bookWikipediaCs
        case .wikipediaSk: return .bookWikipediaSk
        case .wikipediaEn: return .bookWikipediaEn
        case .medicine: return .bookMedicine
        case .mapsCz: return .bookMapsCz
        case .mapsSk: return .bookMapsSk
        case .guides: return .bookGuides
        case .games: return .bookGames
        case .wikibooks: return .bookWikibooks
        case .wikiquote: return .bookWikiquote
        case .wikisource: return .bookWikisource
        case .wikiversity: return .bookWikiversity
        case .wiktionary: return .bookWiktionary
        case .wikivoyage: return .bookWikivoyage
        case .maps, .wikipediaOther, .other: return nil
        }
    }

    /// `isMini`: the phone edition (intros only, no pictures).
    func descriptionKey(isMini: Bool) -> Str {
        switch self {
        case .wikipediaCs, .wikipediaSk: return isMini ? .bookWikipediaMiniDesc : .bookWikipediaFullDesc
        case .wikipediaEn: return .bookWikipediaEnDesc
        case .medicine: return .bookMedicineDesc
        case .mapsCz, .mapsSk, .maps: return .bookMapsDesc
        case .guides: return .bookGuidesDesc
        case .games: return .bookGamesDesc
        case .wikibooks: return .bookWikibooksDesc
        case .wikiquote: return .bookWikiquoteDesc
        case .wikisource: return .bookWikisourceDesc
        case .wikiversity: return .bookWikiversityDesc
        case .wiktionary: return .bookWiktionaryDesc
        case .wikivoyage: return .bookWikivoyageDesc
        case .wikipediaOther, .other: return .bookOtherDesc
        }
    }
}

/// Sections of the library screen (`library_group_*`).
enum LibraryGroup: String, CaseIterable, Sendable {
    case wiki, maps, aid, books, other

    var titleKey: Str {
        switch self {
        case .wiki: return .libraryGroupWiki
        case .maps: return .libraryGroupMaps
        case .aid: return .libraryGroupAid
        case .books: return .libraryGroupBooks
        case .other: return .libraryGroupOther
        }
    }
}

/// Display helpers for books.
enum BookCatalog {
    /// Home and library order for the UI language, then title.
    static func sorted(_ books: [BookRecord], uiLanguage: String = "cs") -> [BookRecord] {
        books.sorted { lhs, rhs in
            let left = BookKind.of(name: lhs.name).homeOrder(uiLanguage: uiLanguage)
            let right = BookKind.of(name: rhs.name).homeOrder(uiLanguage: uiLanguage)
            if left != right { return left < right }
            return lhs.title.localizedStandardCompare(rhs.title) == .orderedAscending
        }
    }

    /// English top1m: too heavy to index unasked (about 10 minutes and 220 MB); only from its details.
    static func isOnDemandIndex(_ name: String) -> Bool {
        name.lowercased().contains("top1m")
    }

    /// Order in which missing search indexes are built (lower first; nil = not automatically):
    /// the guides (they take no time), the UI-language Wikipedia, the other Wikipedias, the other
    /// text books, maps and games last; English top1m only when asked. Same as Android.
    static func indexPriority(name: String, uiLanguage: String, requested: Bool) -> Int? {
        if isOnDemandIndex(name) { return requested ? 10 : nil }
        let kind = BookKind.of(name: name)
        if kind == .guides { return 0 }
        if kind.isWikipedia { return kind.wikipediaLanguage == uiLanguage ? 1 : 2 }
        switch kind {
        case .mapsCz, .mapsSk, .maps, .games, .other: return 4
        default: return 3
        }
    }

    /// File name of a search index built on the phone for one exact ZIM file (name + size, so a
    /// new edition never reuses an old index): `wikipedia_cs_all_maxi-offlinka_2026-09-9958167476.offidx`.
    /// On-device index format tag, as on Android. Bumped when indexes built by older versions must be
    /// rebuilt once: `v5b` = core index v5 built by the hardened core. Files with other names are
    /// removed by the folder clean-up on the next load.
    static let indexTag = "v5b"

    static func indexFileName(zimFileName: String, size: Int64) -> String {
        let stem = (zimFileName as NSString).deletingPathExtension
        let safe = String(stem.unicodeScalars.map { scalar -> Character in
            let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
            return allowed.contains(scalar) ? Character(scalar) : "_"
        })
        return "\(safe)-\(size)-\(indexTag).offidx"
    }

    /// The book (`baseKey`) an index file built by `indexFileName` belongs to (also of older, untagged names).
    static func bookKey(ofIndexFile fileName: String) -> String? {
        guard fileName.hasSuffix(".offidx") else { return nil }
        var parts = String(fileName.dropLast(".offidx".count)).components(separatedBy: "-")
        if parts.count > 2, let last = parts.last, last.hasPrefix("v"), Int64(last) == nil {
            parts.removeLast()   // the format tag
        }
        guard parts.count >= 2 else { return nil }
        parts.removeLast()       // the file size
        return ZimFileName(fileName: parts.joined(separator: "-") + ".zim").baseKey
    }

    static func kind(of book: BookRecord) -> BookKind {
        BookKind.of(name: book.name)
    }

    static func isMini(_ book: BookRecord) -> Bool {
        ZimFileName(fileName: book.fileName).flavour == .mini
    }

    static func title(of book: BookRecord) -> String {
        if let key = kind(of: book).titleKey { return tr(key) }
        return book.title.isEmpty ? book.name : book.title
    }

    static func description(of book: BookRecord) -> String {
        tr(kind(of: book).descriptionKey(isMini: isMini(book)))
    }

    /// Title for a drive file that was not opened (a phone edition replaced by the big one).
    static func title(ofEntry entry: DriveEntry) -> String {
        if let key = BookKind.of(name: entry.name.baseKey).titleKey { return tr(key) }
        return entry.name.stem
    }

    /// The book of the "Random article" quick link: the Wikipedia in the UI language, else Czech.
    static func primaryWikipedia(in books: [BookRecord], languageCode: String) -> BookRecord? {
        let preferred: BookKind
        switch languageCode {
        case "sk": preferred = .wikipediaSk
        case "en": preferred = .wikipediaEn
        default: preferred = .wikipediaCs
        }
        return books.first { kind(of: $0) == preferred }
            ?? books.first { kind(of: $0) == .wikipediaCs }
            ?? books.first { [.wikipediaSk, .wikipediaEn, .wikipediaOther].contains(kind(of: $0)) }
    }

    static func guides(in books: [BookRecord]) -> BookRecord? {
        books.first { kind(of: $0) == .guides }
    }

    /// Fixed pages of our guides ZIM (`offlinka_cs_prirucky`) for the home quick links. Same on Android.
    static let firstAidPath = "prvni-pomoc.html"
    static let prepareAtHomePath = "vypadek-proudu.html"

    /// Language names (`lang_*`) for an ISO 639-3 list such as `ces,eng`.
    static func languageName(_ iso639: String) -> String {
        let codes = iso639.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        if codes.count > 1 { return tr(.langMul) }
        switch codes.first ?? "" {
        case "ces", "cze", "cs": return tr(.langCs)
        case "slk", "slo", "sk": return tr(.langSk)
        case "eng", "en": return tr(.langEn)
        case "mul": return tr(.langMul)
        default:
            let code = codes.first ?? ""
            return L10n.locale.localizedString(forLanguageCode: code) ?? code
        }
    }

    /// The drive's edition, e.g. `2026-11`: the newest content month of its books.
    static func edition(of books: [BookRecord]) -> String? {
        books.map { String($0.date.prefix(7)) }.filter { $0.count == 7 }.max()
    }

    /// A size as on Android (`BookVisuals.formatSize`): GB from 1 GB (2 decimals under 10 GB, else 1),
    /// MB from 1 MB, else kB; the UI locale's decimal comma, a no-break space before the unit.
    /// „9,96 GB“ · „17,1 GB“ · „540 MB“ · „394 kB“. Drive total and book details.
    static func formatSize(_ bytes: Int64) -> String {
        if bytes >= 1_000_000_000 {
            let gigabytes = Double(bytes) / 1e9
            return number(gigabytes, fractionDigits: gigabytes < 10 ? 2 : 1) + "\u{00A0}GB"
        }
        if bytes >= 1_000_000 {
            return number(Double(bytes) / 1e6, fractionDigits: 0) + "\u{00A0}MB"
        }
        return number(max(1, Double(bytes) / 1e3), fractionDigits: 0) + "\u{00A0}kB"
    }

    /// Rounded for list lines (`BookVisuals.formatSizeShort`): „10 GB“, „3,5 GB“, „393 MB“.
    static func formatSizeShort(_ bytes: Int64) -> String {
        if bytes >= 9_500_000_000 {
            return number(Double(bytes) / 1e9, fractionDigits: 0) + "\u{00A0}GB"
        }
        if bytes >= 1_000_000_000 {
            return number(Double(bytes) / 1e9, fractionDigits: 1) + "\u{00A0}GB"
        }
        if bytes >= 1_000_000 {
            return number(Double(bytes) / 1e6, fractionDigits: 0) + "\u{00A0}MB"
        }
        return number(max(1, Double(bytes) / 1e3), fractionDigits: 0) + "\u{00A0}kB"
    }

    /// At most `fractionDigits` decimals, no trailing zeros, the UI locale's separators.
    private static func number(_ value: Double, fractionDigits: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = L10n.locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = fractionDigits
        formatter.roundingMode = .halfEven
        return formatter.string(from: NSNumber(value: value)) ?? String(format: "%.\(fractionDigits)f", value)
    }

    /// Language tag on the tiles (the Wikipedias, and the English-only Medicína and Wikivoyage).
    static func languageTag(of book: BookRecord) -> String? {
        languageTag(kind: kind(of: book))
    }

    static func languageTag(kind: BookKind) -> String? {
        switch kind {
        case .wikipediaCs: return "CZ"
        case .wikipediaSk: return "SK"
        case .wikipediaEn, .medicine, .wikivoyage: return "EN"
        default: return nil
        }
    }

    /// "září 2026, čeština, 9,96 GB": the meta line of a library row.
    static func metaLine(of book: BookRecord) -> String {
        [ContentDate.monthYear(book.date), languageName(book.language), formatSizeShort(book.sizeBytes)]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }

    static func formattedCount(_ count: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = L10n.locale
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: count)) ?? String(count)
    }
}

/// Month and year of a ZIM date (`2026-09-29` → „září 2026“) in the UI language.
enum ContentDate {
    static func monthYear(_ zimDate: String) -> String {
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = zimDate.count >= 10 ? "yyyy-MM-dd" : "yyyy-MM"
        guard let date = parser.date(from: String(zimDate.prefix(10))) else { return zimDate }
        let formatter = DateFormatter()
        formatter.locale = L10n.locale
        formatter.setLocalizedDateFormatFromTemplate("LLLLyyyy")
        return formatter.string(from: date)
    }
}
