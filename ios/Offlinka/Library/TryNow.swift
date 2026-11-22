import Foundation

/// One link of Home's first-run card „Vyzkoušejte hned“; `label` is the article title for `.article`.
struct TryNowItem: Identifiable, Equatable, Sendable {
    enum Kind: Sendable {
        case article, map, firstAid, game
    }

    let kind: Kind
    let label: String
    let bookId: String
    let path: String

    var id: String { "\(bookId)/\(path)" }
}

/// „Vyzkoušejte hned“ on Home (first run, until closed): one article, the map, the first-aid cards, a game.
/// Every link is checked against the book actually on hand, so the card never offers a page that is
/// not there. Same pages as Android (`TryNow.kt`).
enum TryNow {
    /// Article per Wikipedia language: (path, title shown).
    static let articles: [String: (path: String, title: String)] = [
        "sk": ("Vysoké_Tatry", "Vysoké Tatry"),
        "cs": ("Sněžka", "Sněžka"),
    ]
    static let firstAidPath = BookCatalog.firstAidPath
    static let gamePath = "2048/index.html"

    /// Blocking (reads the books): call off the main thread.
    static func items(content: ContentSource, uiLanguage: String) -> [TryNowItem] {
        let books = content.books
        var items: [TryNowItem] = []
        if let wikipedia = BookCatalog.primaryWikipedia(in: books, languageCode: uiLanguage) {
            let language = BookCatalog.kind(of: wikipedia).wikipediaLanguage ?? "cs"
            let article = articles[language] ?? articles["cs"]!
            if exists(content, wikipedia.id, article.path) {
                items.append(TryNowItem(kind: .article, label: article.title, bookId: wikipedia.id, path: article.path))
            }
        }
        let region: BookKind = uiLanguage == "sk" ? .mapsSk : .mapsCz
        let maps = books.filter { [.mapsCz, .mapsSk, .maps].contains(BookCatalog.kind(of: $0)) }
        if let map = maps.first(where: { BookCatalog.kind(of: $0) == region }) ?? maps.first,
           let main = map.mainPath, exists(content, map.id, main) {
            items.append(TryNowItem(kind: .map, label: "", bookId: map.id, path: main))
        }
        if let guides = BookCatalog.guides(in: books), exists(content, guides.id, firstAidPath) {
            items.append(TryNowItem(kind: .firstAid, label: "", bookId: guides.id, path: firstAidPath))
        }
        if let games = books.first(where: { BookCatalog.kind(of: $0) == .games }), exists(content, games.id, gamePath) {
            items.append(TryNowItem(kind: .game, label: "", bookId: games.id, path: gamePath))
        }
        return items
    }

    private static func exists(_ content: ContentSource, _ bookId: String, _ path: String) -> Bool {
        ((try? content.contentLength(bookId: bookId, path: path)) ?? -1) >= 0
    }
}

/// The note about accent-free search under the search field (Android `SearchNote`).
enum SearchNote: Equatable, Sendable {
    /// „Hledání se připravuje (40 %)… Nechte aplikaci otevřenou.“
    case building(percent: Int)
    /// „Hledání je připravené. Háčky a čárky už psát nemusíte.“
    case ready
}
