import Foundation

/// The app's icons. `rawValue` is the icon's name in `design/icons/svg/` (and in the asset
/// catalog built from it). Until an asset exists, the matching SF Symbol is drawn instead.
enum BrandIcon: String, CaseIterable, Sendable {
    // Books
    case bookWikipedia = "book_wikipedia"
    case bookWikipediaEn = "book_wikipedia_en"
    case bookMedicine = "book_medicine"
    case bookMaps = "book_maps"
    case bookGuides = "book_guides"
    case bookGames = "book_games"
    case bookWikibooks = "book_wikibooks"
    case bookWikiquote = "book_wikiquote"
    case bookWikisource = "book_wikisource"
    case bookWikiversity = "book_wikiversity"
    case bookWiktionary = "book_wiktionary"
    case bookWikivoyage = "book_wikivoyage"
    case article

    // UI
    case arrowBack = "arrow_back"
    case arrowForward = "arrow_forward"
    case bookmark
    case bookmarkFilled = "bookmark_filled"
    case check
    case checklist
    case chevronDown = "chevron_down"
    case chevronLeft = "chevron_left"
    case chevronRight = "chevron_right"
    case chevronUp = "chevron_up"
    case clock
    case close
    case contrast
    case copy
    case drive
    case driveOff = "drive_off"
    case fillQuery = "fill_query"
    case findInPage = "find_in_page"
    case firstAid = "first_aid"
    case folder
    case globe
    case help
    case history
    case home
    case info
    case library
    case lock
    case mail
    case minus
    case moon
    case moreVert = "more_vert"
    case noInternet = "no_internet"
    case phoneCopy = "phone_copy"
    case phoneDrive = "phone_drive"
    case plus
    case random
    case redirect
    case retry
    case search
    case settings
    case sun
    case textSize = "text_size"
    case toc
    case trash
    case warning

    var assetName: String { rawValue }

    /// SF Symbol drawn while the custom icon is not in the asset catalog.
    var systemName: String {
        switch self {
        case .bookWikipedia: return "book"
        case .bookWikipediaEn: return "book"
        case .bookMedicine: return "stethoscope"
        case .bookMaps: return "map"
        case .bookGuides: return "cross.case"
        case .bookGames: return "gamecontroller"
        case .bookWikibooks: return "books.vertical"
        case .bookWikiquote: return "quote.bubble"
        case .bookWikisource: return "text.book.closed"
        case .bookWikiversity: return "graduationcap"
        case .bookWiktionary: return "character.book.closed"
        case .bookWikivoyage: return "globe.europe.africa"
        case .article: return "doc.text"
        case .arrowBack: return "chevron.backward"
        case .arrowForward: return "chevron.forward"
        case .bookmark: return "bookmark"
        case .bookmarkFilled: return "bookmark.fill"
        case .check: return "checkmark"
        case .checklist: return "checklist"
        case .chevronDown: return "chevron.down"
        case .chevronLeft: return "chevron.left"
        case .chevronRight: return "chevron.right"
        case .chevronUp: return "chevron.up"
        case .clock: return "clock"
        case .close: return "xmark"
        case .contrast: return "circle.lefthalf.filled"
        case .copy: return "doc.on.doc"
        case .drive: return "externaldrive"
        case .driveOff: return "externaldrive.badge.xmark"
        case .fillQuery: return "arrow.up.left"
        case .findInPage: return "doc.text.magnifyingglass"
        case .firstAid: return "cross.case"
        case .folder: return "folder"
        case .globe: return "globe"
        case .help: return "questionmark.circle"
        case .history: return "clock.arrow.circlepath"
        case .home: return "house"
        case .info: return "info.circle"
        case .library: return "books.vertical"
        case .lock: return "lock"
        case .mail: return "envelope"
        case .minus: return "minus"
        case .moon: return "moon"
        case .moreVert: return "ellipsis.circle"
        case .noInternet: return "wifi.slash"
        case .phoneCopy: return "iphone.and.arrow.forward"
        case .phoneDrive: return "iphone"
        case .plus: return "plus"
        case .random: return "shuffle"
        case .redirect: return "arrow.turn.down.right"
        case .retry: return "arrow.clockwise"
        case .search: return "magnifyingglass"
        case .settings: return "gearshape"
        case .sun: return "sun.max"
        case .textSize: return "textformat.size"
        case .toc: return "list.bullet.indent"
        case .trash: return "trash"
        case .warning: return "exclamationmark.triangle"
        }
    }
}
