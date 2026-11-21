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

    static let collapsedCount = 5
    static let totalLimit = 60
    static let bookLimit = 50

    private(set) var groups: [SearchGroup] = []
    private(set) var phase: Phase = .idle
    /// Search only in this book (from the book detail screen).
    let scopeBookId: String?

    private var storedQuery = ""
    private let source: ContentSource
    private let debounce: Duration
    @ObservationIgnored private var work: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    init(source: ContentSource, scopeBookId: String? = nil, debounce: Duration = .milliseconds(50)) {
        self.source = source
        self.scopeBookId = scopeBookId
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
            let hits = await Task.detached(priority: .userInitiated) { () -> [SearchHit] in
                do {
                    if let scope {
                        return try source.suggest(bookId: scope, query: query, limit: bookLimit)
                    }
                    return try source.suggestAll(query: query, limit: totalLimit)
                } catch {
                    return []
                }
            }.value
            guard let self, generationAtStart == self.generation else { return }
            self.groups = Self.group(hits, books: source.books)
            self.phase = self.groups.isEmpty ? .empty : .results
        }
    }

    /// Groups ranked hits by book, in the order the books first appear in the ranking.
    nonisolated static func group(_ hits: [SearchHit], books: [BookRecord]) -> [SearchGroup] {
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
