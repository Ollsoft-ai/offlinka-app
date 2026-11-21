import XCTest
@testable import Offlinka

final class OfflinkaURLTests: XCTestCase {
    func testRoundTripWithDiacriticsAndSpecialCharacters() throws {
        for path in ["Český_Krumlov", "Praha", "A/B/c.html", "Co_je_to?", "100%_čisté", "Rock_#1", "I/vlajka.svg", ""] {
            let url = try XCTUnwrap(OfflinkaURL.make(bookId: "wikipedia_cs_all", path: path), path)
            let parsed = try XCTUnwrap(OfflinkaURL.parse(url), path)
            XCTAssertEqual(parsed.bookId, "wikipedia_cs_all")
            XCTAssertEqual(parsed.path, path)
            XCTAssertNil(parsed.fragment)
        }
    }

    func testHostIsLowercasedAndFragmentKept() throws {
        let url = try XCTUnwrap(URL(string: "offlinka://Wikipedia_CS_all/Praha#Historie"))
        let parsed = try XCTUnwrap(OfflinkaURL.parse(url))
        XCTAssertEqual(parsed.bookId, "wikipedia_cs_all")
        XCTAssertEqual(parsed.path, "Praha")
        XCTAssertEqual(parsed.fragment, "Historie")
    }

    func testRelativeLinksResolveInsideTheBook() throws {
        let base = try XCTUnwrap(OfflinkaURL.make(bookId: "offlinka_mul_hry", path: "2048/index.html"))
        let style = try XCTUnwrap(URL(string: "style/main.css", relativeTo: base)?.absoluteURL)
        XCTAssertEqual(OfflinkaURL.parse(style)?.path, "2048/style/main.css")
        let up = try XCTUnwrap(URL(string: "../index.html", relativeTo: base)?.absoluteURL)
        XCTAssertEqual(OfflinkaURL.parse(up)?.path, "index.html")
    }

    func testQueryIsNotPartOfThePath() throws {
        let url = try XCTUnwrap(URL(string: "offlinka://maps_en_czech-republic/tiles/7/69/43.pbf?v=2"))
        XCTAssertEqual(OfflinkaURL.parse(url)?.path, "tiles/7/69/43.pbf")
    }

    func testOtherSchemesAreNotParsed() throws {
        XCTAssertNil(OfflinkaURL.parse(try XCTUnwrap(URL(string: "https://cs.wikipedia.org/wiki/Praha"))))
    }

    func testExternalDetection() throws {
        XCTAssertTrue(OfflinkaURL.isExternal(try XCTUnwrap(URL(string: "https://www.praha.eu/"))))
        XCTAssertTrue(OfflinkaURL.isExternal(try XCTUnwrap(URL(string: "mailto:info@example.org"))))
        XCTAssertFalse(OfflinkaURL.isExternal(try XCTUnwrap(URL(string: "offlinka://wikipedia_cs_all/Praha"))))
        XCTAssertFalse(OfflinkaURL.isExternal(try XCTUnwrap(URL(string: "about:blank"))))
    }
}

final class ByteRangeTests: XCTestCase {
    func testRanges() {
        XCTAssertEqual(ByteRange.parse(nil, totalLength: 100, maxLength: 0), .none)
        XCTAssertEqual(ByteRange.parse("bytes=0-1", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 0, end: 1)))
        XCTAssertEqual(ByteRange.parse("bytes=10-", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 10, end: 99)))
        XCTAssertEqual(ByteRange.parse("bytes=90-200", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 90, end: 99)))
        XCTAssertEqual(ByteRange.parse("bytes=-30", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 70, end: 99)))
        XCTAssertEqual(ByteRange.parse("bytes=-300", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 0, end: 99)))
        XCTAssertEqual(ByteRange.parse("Bytes=5-9, 20-30", totalLength: 100, maxLength: 0), .satisfiable(ByteRange(start: 5, end: 9)))
    }

    func testCapsLongRanges() {
        XCTAssertEqual(ByteRange.parse("bytes=0-", totalLength: 1000, maxLength: 100), .satisfiable(ByteRange(start: 0, end: 99)))
    }

    func testUnsatisfiableAndInvalid() {
        XCTAssertEqual(ByteRange.parse("bytes=100-", totalLength: 100, maxLength: 0), .unsatisfiable)
        XCTAssertEqual(ByteRange.parse("bytes=-0", totalLength: 100, maxLength: 0), .unsatisfiable)
        XCTAssertEqual(ByteRange.parse("bytes=0-", totalLength: 0, maxLength: 0), .unsatisfiable)
        XCTAssertEqual(ByteRange.parse("bytes=9-5", totalLength: 100, maxLength: 0), .none)
        XCTAssertEqual(ByteRange.parse("items=0-5", totalLength: 100, maxLength: 0), .none)
        XCTAssertEqual(ByteRange.parse("bytes=abc", totalLength: 100, maxLength: 0), .none)
    }
}

final class SchemeResponderTests: XCTestCase {
    private var source: FakeContentSource!
    private var responder: SchemeResponder!

    override func setUp() {
        source = FakeContentSource.demo()
        let texts = ReaderTexts(languageCode: "cs", missingTitle: "missing", missingBody: "missing body",
                                driveTitle: "drive", driveBody: "drive body", noBookTitle: "nobook", noBookBody: "nobook body",
                                errorTitle: "error", errorBody: "error body")
        let assets = AppAssets { name in
            ["offlinka-reader.css": "body{}", "inject.js": "x()", "archivo_regular.ttf": "ttf"][name].map { Data($0.utf8) }
        }
        responder = SchemeResponder(source: source, assets: assets, texts: texts, maxRangeLength: 1000)
    }

    private func reply(_ string: String, headers: [String: String] = [:]) throws -> SchemeReply {
        responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: string)), headers: headers))
    }

    func testServesArticleWithMimeAndCharset() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/Praha")
        XCTAssertEqual(reply.statusCode, 200)
        XCTAssertEqual(reply.headers["Content-Type"], "text/html; charset=utf-8")
        XCTAssertEqual(reply.headers["Content-Length"], String(reply.body.count))
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("hlavní a největší město"))
        XCTAssertFalse(reply.driveFailed)
    }

    func testPercentEncodedPathsAreDecoded() throws {
        let url = try XCTUnwrap(OfflinkaURL.make(bookId: FakeContentSource.demoWikipediaId, path: "Český_Krumlov"))
        let reply = responder.reply(to: SchemeRequest(url: url))
        XCTAssertEqual(reply.statusCode, 200)
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("Vltavě"))
    }

    func testBookRootRedirectsToMainPage() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/")
        XCTAssertEqual(reply.statusCode, 200)
        let html = String(decoding: reply.body, as: UTF8.self)
        XCTAssertTrue(html.contains("location.replace(\"offlinka://wikipedia_cs_all/Hlavn%C3%AD_strana\""), html)
    }

    func testZimRedirectMovesThePageToItsTarget() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/Krumlov")
        let html = String(decoding: reply.body, as: UTF8.self)
        XCTAssertTrue(html.contains("location.replace(\"offlinka://wikipedia_cs_all/%C4%8Cesk%C3%BD_Krumlov\""), html)
    }

    func testMissingArticleGivesCalmPage() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/Neexistující_článek")
        XCTAssertEqual(reply.statusCode, 404)
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("<h1>missing</h1>"))
    }

    func testUnknownBook() throws {
        let reply = try reply("offlinka://wikipedia_de_all/Berlin")
        XCTAssertEqual(reply.statusCode, 404)
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("nobook"))
    }

    func testRangeRequest() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/media/ukazka.bin", headers: ["range": "bytes=10-19"])
        XCTAssertEqual(reply.statusCode, 206)
        XCTAssertEqual(reply.body, Data((10..<20).map { UInt8($0 % 251) }))
        XCTAssertEqual(reply.headers["Content-Range"], "bytes 10-19/4096")
        XCTAssertEqual(reply.headers["Content-Length"], "10")
        XCTAssertEqual(reply.headers["Accept-Ranges"], "bytes")
    }

    func testOpenEndedRangeIsCapped() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/media/ukazka.bin", headers: ["Range": "bytes=0-"])
        XCTAssertEqual(reply.statusCode, 206)
        XCTAssertEqual(reply.body.count, 1000)
        XCTAssertEqual(reply.headers["Content-Range"], "bytes 0-999/4096")
    }

    func testUnsatisfiableRange() throws {
        let reply = try reply("offlinka://wikipedia_cs_all/media/ukazka.bin", headers: ["Range": "bytes=5000-"])
        XCTAssertEqual(reply.statusCode, 416)
        XCTAssertEqual(reply.headers["Content-Range"], "bytes */4096")
    }

    func testAppAssetsOnTheAppHostAndOnEveryBookHost() throws {
        XCTAssertEqual(try reply("offlinka://app.offlinka/reader.css").statusCode, 200)
        let sameOrigin = try reply("offlinka://wikipedia_cs_all/_offlinka/reader.css")
        XCTAssertEqual(sameOrigin.statusCode, 200)
        XCTAssertEqual(sameOrigin.headers["Content-Type"], "text/css; charset=utf-8")
        XCTAssertEqual(String(decoding: sameOrigin.body, as: UTF8.self), "body{}")
        XCTAssertEqual(try reply("offlinka://offlinka_mul_hry/_offlinka/inject.js").headers["Content-Type"], "application/javascript; charset=utf-8")
        XCTAssertEqual(try reply("offlinka://wikipedia_cs_all/_offlinka/fonts/archivo_regular.ttf").headers["Content-Type"], "font/ttf")
        XCTAssertEqual(try reply("offlinka://app.offlinka/secret.json").statusCode, 404)
    }

    func testReaderHeadIsInsertedIntoWikimediaPagesOnly() throws {
        responder.readerHead = ReaderHead(bookIds: [FakeContentSource.demoWikipediaId], theme: "dark", scale: 1.15)
        let article = String(decoding: try reply("offlinka://wikipedia_cs_all/Praha").body, as: UTF8.self)
        XCTAssertTrue(article.contains(#"<script>window.__offlinka={theme:"dark",scale:1.15};</script><link id="offlinka-reader-css" rel="stylesheet" href="/_offlinka/reader.css"><script src="/_offlinka/inject.js"></script></head>"#), article)
        let game = String(decoding: try reply("offlinka://offlinka_mul_hry/index.html").body, as: UTF8.self)
        XCTAssertFalse(game.contains("offlinka-reader-css"))
        // Not into non-HTML files of a styled book.
        let svg = String(decoding: try reply("offlinka://wikipedia_cs_all/I/vlajka.svg").body, as: UTF8.self)
        XCTAssertFalse(svg.contains("offlinka-reader-css"))
    }

    func testReaderHeadLeavesPagesWithoutHeadAlone() {
        let head = ReaderHead(bookIds: [], theme: "system", scale: 1)
        let html = Data("<p>no head</p>".utf8)
        XCTAssertEqual(head.inject(into: html), html)
    }

    /// Code review r2 #2: a book that is still opening answers 503 "Otevírám…" (the reader reloads), not 404.
    func testBookStillOpeningGetsOpeningPage() throws {
        let content = ShelfContent(library: FakeContentLibrary(), coreVersion: "fake", uiLanguage: "cs")
        content.setPending(["wikivoyage_en_europe"])
        let texts = ReaderTexts(noBookTitle: "nobook", openingTitle: "Otevírám…")
        let responder = SchemeResponder(source: content, assets: .none, texts: texts)
        let reply = responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: "offlinka://wikivoyage_en_europe/Prague"))))
        XCTAssertEqual(reply.statusCode, 503)
        XCTAssertFalse(reply.driveFailed)
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("Otevírám…"))
        let unknown = responder.reply(to: SchemeRequest(url: try XCTUnwrap(URL(string: "offlinka://wikiquote_cs_all/X"))))
        XCTAssertEqual(unknown.statusCode, 404)
    }

    func testDriveFailureIsFlagged() throws {
        source.simulatesUnpluggedDrive = true
        let reply = try reply("offlinka://wikipedia_cs_all/Praha")
        XCTAssertEqual(reply.statusCode, 503)
        XCTAssertTrue(reply.driveFailed)
        XCTAssertTrue(String(decoding: reply.body, as: UTF8.self).contains("drive"))
    }

    func testErrorPagesEscapeHTML() {
        XCTAssertEqual(SchemeResponder.escapeHTML("<a href=\"x\">&'"), "&lt;a href=&quot;x&quot;&gt;&amp;&#39;")
        XCTAssertEqual(SchemeResponder.javaScriptString("a\"b</script>\n"), "\"a\\\"b\\u003C/script\\u003E\\n\"")
    }

    func testNormalizedPaths() {
        XCTAssertEqual(SchemeResponder.normalized("/C/Praha"), "Praha")
        XCTAssertEqual(SchemeResponder.normalized("Praha"), "Praha")
        XCTAssertEqual(SchemeResponder.normalized("Cesky/x"), "Cesky/x")
    }

    func testMimeTypes() {
        XCTAssertEqual(MimeTypes.forPath("video/a.MP4"), "video/mp4")
        XCTAssertEqual(MimeTypes.forPath("tiles/1/2/3.pbf"), "application/x-protobuf")
        XCTAssertEqual(MimeTypes.forPath("noext"), "application/octet-stream")
        XCTAssertEqual(MimeTypes.withCharset("text/html; charset=iso-8859-2"), "text/html; charset=iso-8859-2")
        XCTAssertEqual(MimeTypes.withCharset("image/png"), "image/png")
        XCTAssertEqual(MimeTypes.withCharset("application/javascript"), "application/javascript; charset=utf-8")
    }
}
