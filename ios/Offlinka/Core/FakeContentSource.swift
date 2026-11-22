import Foundation

/// In-memory content for SwiftUI previews, the UI-test demo drive and unit tests.
/// Behaves like the core: redirects are followed, search is diacritics-insensitive and ranked
/// exact > prefix > word prefix, shorter titles first.
final class FakeContentSource: ContentSource, @unchecked Sendable {
    struct Entry {
        var title: String
        var mimeType: String
        var data: Data
        var redirectTo: String?

        static func html(_ title: String, _ body: String) -> Entry {
            let page = """
            <!doctype html><html lang="cs"><head><meta charset="utf-8">\
            <meta name="viewport" content="width=device-width, initial-scale=1"><title>\(title)</title></head>\
            <body><h1>\(title)</h1>\(body)</body></html>
            """
            return Entry(title: title, mimeType: "text/html", data: Data(page.utf8))
        }

        static func redirect(_ title: String, to target: String) -> Entry {
            Entry(title: title, mimeType: "", data: Data(), redirectTo: target)
        }

        static func file(_ mimeType: String, _ data: Data) -> Entry {
            Entry(title: "", mimeType: mimeType, data: data)
        }
    }

    struct FakeBook {
        var record: BookRecord
        var entries: [String: Entry]
        var illustration: Data?

        /// Follows redirects like the core (max 10).
        func resolve(_ path: String) throws -> (String, Entry) {
            var current = path
            for _ in 0..<10 {
                guard let entry = entries[current] else { throw ContentError.notFound(current) }
                guard let target = entry.redirectTo else { return (current, entry) }
                current = target
            }
            throw ContentError.invalidZim("redirect loop at \(path)")
        }

        /// Ranked like the core: exact > prefix > word prefix, then shorter titles.
        func scored(_ query: String) -> [(Int, SearchHit)] {
            let needle = FakeContentSource.normalize(query)
            guard !needle.isEmpty else { return [] }
            var result: [(Int, SearchHit)] = []
            for (path, entry) in entries where !entry.title.isEmpty && (entry.redirectTo != nil || MimeTypes.isHTML(entry.mimeType)) {
                let title = FakeContentSource.normalize(entry.title)
                let score: Int
                if title == needle {
                    score = 3
                } else if title.hasPrefix(needle) {
                    score = 2
                } else if title.split(separator: " ").contains(where: { $0.hasPrefix(needle) }) {
                    score = 1
                } else {
                    continue
                }
                // Redirects are shown under the searched title but open the target.
                let target = entry.redirectTo ?? path
                result.append((score, SearchHit(bookId: record.id, title: entry.title, path: target, snippet: nil)))
            }
            result.sort { lhs, rhs in
                if lhs.0 != rhs.0 { return lhs.0 > rhs.0 }
                if lhs.1.title.count != rhs.1.title.count { return lhs.1.title.count < rhs.1.title.count }
                return lhs.1.title < rhs.1.title
            }
            return result
        }

        func randomArticle() -> String? {
            entries
                .filter { $0.value.redirectTo == nil && MimeTypes.isHTML($0.value.mimeType) && $0.key != record.mainPath }
                .map(\.key)
                .sorted()
                .first
        }
    }

    let coreVersion = "fake"

    private let lock = NSLock()
    private var storage: [String: FakeBook]
    private var orderedIds: [String]
    private var ioFailure = false
    private var calls = 0

    init(books: [FakeBook]) {
        var storage: [String: FakeBook] = [:]
        for book in books {
            storage[book.record.id] = book
        }
        self.storage = storage
        self.orderedIds = BookCatalog.sorted(books.map(\.record)).map(\.id)
    }

    var books: [BookRecord] {
        lock.lock()
        defer { lock.unlock() }
        return orderedIds.compactMap { storage[$0]?.record }
    }

    /// Makes every read fail like an unplugged drive.
    var simulatesUnpluggedDrive: Bool {
        get { lock.lock(); defer { lock.unlock() }; return ioFailure }
        set { lock.lock(); ioFailure = newValue; lock.unlock() }
    }

    /// Number of content and search calls so far (tests).
    var callCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return calls
    }

    // MARK: - ContentSource

    func resource(bookId: String, path: String) throws -> ContentResource {
        let (finalPath, entry) = try resolve(bookId: bookId, path: path)
        return ContentResource(path: finalPath, mimeType: entry.mimeType, data: entry.data)
    }

    func contentLength(bookId: String, path: String) throws -> Int64 {
        Int64(try resolve(bookId: bookId, path: path).1.data.count)
    }

    func range(bookId: String, path: String, offset: Int64, length: Int) throws -> Data {
        let data = try resolve(bookId: bookId, path: path).1.data
        guard offset >= 0, offset <= Int64(data.count), length >= 0 else { throw ContentError.unsupported("range") }
        let start = Int(offset)
        let end = min(data.count, start + length)
        return data.subdata(in: start..<end)
    }

    func suggestAll(query: String, limit: Int) throws -> [SearchHit] {
        let ids = books.map(\.id)
        var scored: [(score: Int, bookOrder: Int, suggestion: SearchHit)] = []
        for (order, id) in ids.enumerated() {
            for (score, suggestion) in try scoredSuggestions(bookId: id, query: query) {
                scored.append((score, order, suggestion))
            }
        }
        scored.sort { lhs, rhs in
            if lhs.score != rhs.score { return lhs.score > rhs.score }
            if lhs.bookOrder != rhs.bookOrder { return lhs.bookOrder < rhs.bookOrder }
            return lhs.suggestion.title.count < rhs.suggestion.title.count
        }
        return scored.prefix(limit).map { $0.suggestion }
    }

    func suggest(bookId: String, query: String, limit: Int) throws -> [SearchHit] {
        let scored = try scoredSuggestions(bookId: bookId, query: query)
        return scored.prefix(limit).map { $0.1 }
    }

    func randomArticle(bookId: String) throws -> String? {
        try lookup(bookId).randomArticle()
    }

    func illustration(bookId: String, size: Int) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return storage[bookId.lowercased()]?.illustration
    }

    // MARK: - Internals

    private func lookup(_ bookId: String) throws -> FakeBook {
        lock.lock()
        defer { lock.unlock() }
        calls += 1
        if ioFailure { throw ContentError.io("simulated unplugged drive") }
        guard let book = storage[bookId.lowercased()] else { throw ContentError.unknownBook(bookId) }
        return book
    }

    private func resolve(bookId: String, path: String) throws -> (String, Entry) {
        try lookup(bookId).resolve(path)
    }

    private func scoredSuggestions(bookId: String, query: String) throws -> [(Int, SearchHit)] {
        try lookup(bookId).scored(query)
    }

    static func normalize(_ text: String) -> String {
        text.replacingOccurrences(of: "_", with: " ")
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "cs_CZ"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Demo library

extension FakeContentSource {
    // Book ids are the ZIM names, as in the core.
    static let demoWikipediaId = "wikipedia_cs_all"
    static let demoWikipediaSkId = "wikipedia_sk_all"
    static let demoGuidesId = "offlinka_cs_prirucky"
    static let demoGamesId = "offlinka_mul_hry"
    static let demoMapsId = "maps_en_czech-republic"

    /// A small library that looks like the real drive. Used by previews and `-demo-drive` UI tests.
    static func demo() -> FakeContentSource {
        FakeContentSource(books: demoBooks())
    }

    /// The demo books (Czech and Slovak Wikipedia, guides, games, map of Czechia).
    static func demoBooks() -> [FakeBook] {
        let wikipedia = FakeBook(
            record: demoRecord(id: demoWikipediaId, name: "wikipedia_cs_all", title: "Wikipedie", language: "ces",
                               mainPath: "Hlavní_strana", articles: 963_744, size: 9_958_167_476, file: "wikipedia_cs_all_maxi-offlinka_2026-09.zim"),
            entries: [
                "Hlavní_strana": .html("Wikipedie", """
                <p>Vítejte v offline Wikipedii.</p>
                <ul><li><a href="Praha">Praha</a></li><li><a href="Český_Krumlov">Český Krumlov</a></li>\
                <li><a href="Sněžka">Sněžka</a></li><li><a href="Neexistující_článek">Chybějící článek</a></li></ul>
                """),
                "Praha": .html("Praha", """
                <p><b>Praha</b> je hlavní a největší město Česka.</p>
                <p><a href="Český_Krumlov">Český Krumlov</a> leží na jihu. \
                <a class="external" href="https://www.praha.eu/">Oficiální web</a></p>
                <p><img src="I/vlajka.svg" alt="Vlajka" width="120"></p>
                """),
                "Český_Krumlov": .html("Český Krumlov", "<p>Město na Vltavě se zámkem.</p><p><a href=\"Praha\">Praha</a></p>"),
                "Sněžka": .html("Sněžka", "<p>Nejvyšší hora Česka, 1603 metrů.</p>"),
                "Krumlov": .redirect("Krumlov", to: "Český_Krumlov"),
                "Pražský_orloj": .html("Pražský orloj", "<p>Středověké hodiny na Staroměstské radnici.</p>"),
                "Komenský": .redirect("Komenský", to: "Jan_Amos_Komenský"),
                "Jan_Amos_Komenský": .html("Jan Amos Komenský", "<p>Učitel národů.</p>"),
                "I/vlajka.svg": .file("image/svg+xml", Data("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 3 2"><rect width="3" height="1" fill="#fff"/>\
                <rect y="1" width="3" height="1" fill="#d7141a"/><path d="M0 0l1.5 1L0 2z" fill="#11457e"/></svg>
                """.utf8)),
                "media/ukazka.bin": .file("application/octet-stream", Data((0..<4096).map { UInt8($0 % 251) })),
            ]
        )
        let wikipediaSk = FakeBook(
            record: demoRecord(id: demoWikipediaSkId, name: "wikipedia_sk_all", title: "Wikipédia", language: "slk",
                               mainPath: "Hlavná_stránka", articles: 251_000, size: 3_501_486_592, file: "wikipedia_sk_all_maxi-offlinka_2026-08.zim"),
            entries: [
                "Hlavná_stránka": .html("Wikipédia", "<p>Vitajte v offline Wikipédii.</p><p><a href=\"Bratislava\">Bratislava</a></p>"),
                "Bratislava": .html("Bratislava", "<p>Hlavné mesto Slovenska.</p>"),
                "Praha": .html("Praha", "<p>Hlavné mesto Česka.</p>"),
                "Vysoké_Tatry": .html("Vysoké Tatry", "<p>Najvyššie pohorie Slovenska.</p>"),
            ]
        )
        let guides = FakeBook(
            record: demoRecord(id: demoGuidesId, name: "offlinka_cs_prirucky", title: "Nouzové příručky", language: "ces",
                               mainPath: "index.html", articles: 6, size: 393_612, file: "offlinka_cs_prirucky.zim", publisher: "Ollcee s.r.o."),
            entries: [
                "index.html": .html("Nouzové příručky", "<p><a href=\"prvni-pomoc.html\">První pomoc</a></p><p><a href=\"vypadek-proudu.html\">Výpadek proudu</a></p>"),
                "prvni-pomoc.html": .html("První pomoc – rychlé karty", "<h3>1 · Nejdřív bezpečí, pak volejte</h3><p>Volejte 155 nebo 112.</p>"),
                "vypadek-proudu.html": .html("Výpadek proudu – co připravit", "<h3>Mějte doma předem</h3><p>Baterku, vodu, trvanlivé jídlo.</p>"),
                "pdf/prvni-pomoc.pdf": .file("application/pdf", Data("%PDF-1.4\n%%EOF\n".utf8)),
            ]
        )
        let games = FakeBook(
            record: demoRecord(id: demoGamesId, name: "offlinka_mul_hry", title: "Hry v prohlížeči", language: "ces,eng",
                               mainPath: "index.html", articles: 12, size: 1_025_390, file: "offlinka_mul_hry.zim", publisher: "Ollcee s.r.o."),
            entries: [
                "index.html": .html("Hry v prohlížeči", """
                <p id="n">0</p><button onclick="var n=+(localStorage.n||0)+1;localStorage.n=n;\
                document.getElementById('n').textContent=n">+1</button>
                """),
                "2048/index.html": .html("2048", "<p>Spojujte dlaždice se stejným číslem.</p>"),
            ]
        )
        let maps = FakeBook(
            record: demoRecord(id: demoMapsId, name: "maps_en_czech-republic", title: "Czech Republic", language: "eng",
                               mainPath: "index.html", articles: 1, size: 1_065_265_251, file: "maps_en_czech-republic_2026-06.zim", folder: .library),
            entries: [
                "index.html": .html("Czech Republic", "<div id=\"map\" style=\"height:300px;background:#E9DFC8\"></div>"),
            ]
        )
        return [games, guides, maps, wikipediaSk, wikipedia]
    }

    private static func demoRecord(id: String, name: String, title: String, language: String, mainPath: String?,
                                   articles: Int, size: Int64, file: String, publisher: String = "openZIM",
                                   folder: DriveFolder = .library) -> BookRecord {
        BookRecord(id: id, name: name, title: title, summary: "", language: language, creator: "Wikipedia",
                   publisher: publisher, date: "2026-09-29", articleCount: articles, mediaCount: 0, mainPath: mainPath,
                   sizeBytes: size, hasIndex: true, fileName: file, folder: folder)
    }
}

