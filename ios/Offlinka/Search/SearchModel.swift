import Foundation
import Observation

/// Search hits of one book.
struct SearchGroup: Identifiable, Equatable, Sendable {
    let book: BookRecord
    var hits: [SearchHit]
    var isExpanded = false

    var id: String { book.id }
}

/// Suggestions while typing, grouped by book. The core does the ranking (exact > prefix > word
/// prefix, diacritics-insensitive); this model debounces, runs it off the main thread, drops
/// stale answers and groups the hits.
@MainActor
@Observable
final class SearchModel {
    enum Phase: Equatable, Sendable {
        case idle, searching, results, empty
    }

    /// Design: max 4 rows per book, then „Zobrazit další“.
    static let collapsedCount = 4
    static let totalLimit = 60
    static let bookLimit = 50

    private(set) var groups: [SearchGroup] = []
    private(set) var phase: Phase = .idle
    /// Nothing matched the whole query, so the results are for its longest word („Výsledky pro …“).
    private(set) var retriedWord: String?
    /// Search only in this book (from the book detail screen).
    let scopeBookId: String?

    private var storedQuery = ""
    private let source: ContentSource
    private let debounce: Duration
    @ObservationIgnored private var work: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    private let uiLanguage: String

    init(source: ContentSource, scopeBookId: String? = nil, uiLanguage: String = L10n.languageCode,
         debounce: Duration = .milliseconds(50)) {
        self.source = source
        self.scopeBookId = scopeBookId
        self.uiLanguage = uiLanguage
        self.debounce = debounce
    }

    var query: String {
        get { storedQuery }
        set {
            guard newValue != storedQuery else { return }
            storedQuery = newValue
            schedule()
        }
    }

    var trimmedQuery: String {
        storedQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The first hit, opened when the user presses Search on the keyboard.
    var topHit: SearchHit? {
        groups.first?.hits.first
    }

    /// Waits for the running search (tests).
    func settle() async {
        await work?.value
    }

    func expand(_ group: SearchGroup) async {
        let query = trimmedQuery
        let source = self.source
        let bookId = group.book.id
        let limit = Self.bookLimit
        let generationAtStart = generation
        let hits = await Task.detached(priority: .userInitiated) { () -> [SearchHit] in
            (try? source.suggest(bookId: bookId, query: query, limit: limit)) ?? []
        }.value
        guard generationAtStart == generation, let index = groups.firstIndex(where: { $0.id == bookId }) else { return }
        if !hits.isEmpty {
            groups[index].hits = hits
        }
        groups[index].isExpanded = true
    }

    private func schedule() {
        work?.cancel()
        generation += 1
        let generationAtStart = generation
        let query = trimmedQuery
        guard !query.isEmpty else {
            groups = []
            retriedWord = nil
            phase = .idle
            return
        }
        phase = .searching
        let source = self.source
        let scope = scopeBookId
        let debounce = self.debounce
        let totalLimit = Self.totalLimit
        let bookLimit = Self.bookLimit
        work = Task { [weak self] in
            if debounce > .zero {
                try? await Task.sleep(for: debounce)
            }
            if Task.isCancelled { return }
            let found = await Task.detached(priority: .userInitiated) { () -> (hits: [SearchHit], word: String?) in
                func run(_ text: String) -> [SearchHit] {
                    do {
                        if let scope {
                            return try source.suggest(bookId: scope, query: text, limit: bookLimit)
                        }
                        return try source.suggestAll(query: text, limit: totalLimit)
                    } catch {
                        return []
                    }
                }
                let hits = run(query)
                if !hits.isEmpty { return (hits, nil) }
                // Titles only: „krvácení z nosu u dětí“ finds nothing, „krvácení“ does.
                if let word = SearchModel.longestWord(of: query) {
                    let retry = run(word)
                    if !retry.isEmpty { return (retry, word) }
                }
                return ([], nil)
            }.value
            guard let self, generationAtStart == self.generation else { return }
            self.groups = Self.group(found.hits, books: source.books, uiLanguage: self.uiLanguage)
            self.retriedWord = found.word
            self.phase = self.groups.isEmpty ? .empty : .results
        }
    }

    /// The longest word (3+ letters) of a query with several words, nil otherwise.
    nonisolated static func longestWord(of query: String) -> String? {
        let words = query.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard words.count > 1 else { return nil }
        return words.filter { $0.count >= 3 }.max { $0.count < $1.count }
    }

    /// Groups ranked hits by book: the UI-language Wikipedia first, then the books in the order
    /// they first appear in the ranking (design: "Order: UI-language Wikipedia first, then by best match").
    /// When the guides match they lead („prvni pomoc“ → První pomoc – rychlé karty, UX review r2).
    nonisolated static func group(_ hits: [SearchHit], books: [BookRecord], uiLanguage: String = "cs") -> [SearchGroup] {
        var grouped = groupInRankOrder(hits, books: books)
        if let wiki = grouped.firstIndex(where: { BookKind.of(name: $0.book.name).wikipediaLanguage == uiLanguage }), wiki > 0 {
            grouped.insert(grouped.remove(at: wiki), at: 0)
        }
        if let guides = grouped.firstIndex(where: { BookKind.of(name: $0.book.name) == .guides }), guides > 0 {
            grouped.insert(grouped.remove(at: guides), at: 0)
        }
        return grouped
    }

    nonisolated private static func groupInRankOrder(_ hits: [SearchHit], books: [BookRecord]) -> [SearchGroup] {
        var byId: [String: BookRecord] = [:]
        for book in books {
            byId[book.id] = book
        }
        var order: [String] = []
        var grouped: [String: [SearchHit]] = [:]
        for hit in hits {
            let key = hit.bookId.lowercased()
            guard byId[key] != nil else { continue }
            if grouped[key] == nil {
                order.append(key)
            }
            if grouped[key, default: []].contains(where: { $0.path == hit.path }) { continue }
            grouped[key, default: []].append(hit)
        }
        return order.compactMap { key in
            guard let book = byId[key], let bookHits = grouped[key] else { return nil }
            return SearchGroup(book: book, hits: bookHits)
        }
    }
}
