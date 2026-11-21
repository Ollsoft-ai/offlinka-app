import XCTest
@testable import Offlinka

final class DriveScannerTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("offlinka-scan-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("OFFLINKA", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root.deletingLastPathComponent())
    }

    // MARK: - File names

    func testParsesFlavourAndDate() {
        let maxi = ZimFileName(fileName: "wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        XCTAssertEqual(maxi.baseKey, "wikipedia_cs_all")
        XCTAssertEqual(maxi.flavour, .maxi)
        XCTAssertEqual(maxi.date, "2026-09")
        XCTAssertEqual(maxi.stem, "wikipedia_cs_all_maxi-offlinka_2026-09")

        let mini = ZimFileName(fileName: "wikipedia_cs_all_mini-offlinka_2026-09.zim")
        XCTAssertEqual(mini.baseKey, "wikipedia_cs_all")
        XCTAssertEqual(mini.flavour, .mini)

        let nopic = ZimFileName(fileName: "wikipedia_en_top1m_nopic_2026-04.zim")
        XCTAssertEqual(nopic.baseKey, "wikipedia_en_top1m")
        XCTAssertEqual(nopic.flavour, .nopic)
    }

    func testNamesWithoutFlavour() {
        let maps = ZimFileName(fileName: "maps_en_czech-republic_2026-06.zim")
        XCTAssertEqual(maps.baseKey, "maps_en_czech-republic")
        XCTAssertEqual(maps.flavour, .none)
        XCTAssertEqual(maps.date, "2026-06")

        let guides = ZimFileName(fileName: "offlinka_cs_prirucky.zim")
        XCTAssertEqual(guides.baseKey, "offlinka_cs_prirucky")
        XCTAssertEqual(guides.flavour, .none)
        XCTAssertNil(guides.date)
    }

    func testFlavourRankOrder() {
        XCTAssertGreaterThan(ZimFileName(fileName: "a_maxi_2026-01.zim").flavourRank, ZimFileName(fileName: "a_nopic_2026-01.zim").flavourRank)
        XCTAssertGreaterThan(ZimFileName(fileName: "a_nopic_2026-01.zim").flavourRank, ZimFileName(fileName: "a_mini_2026-01.zim").flavourRank)
    }

    // MARK: - Scanning

    func testFindsBooksAndPrefersLibraryMaxi() throws {
        try makeFile("Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim", size: 300)
        try makeFile("Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.offidx")
        try makeFile("Knihovna/offlinka_cs_prirucky.zim")
        try makeFile("Knihovna/maps_en_czech-republic_2026-06.zim")
        try makeFile("Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim")
        try makeFile("Telefon/wikipedia_sk_all_mini-offlinka_2026-08.zim")
        try makeFile("Telefon/offlinka_cs_prirucky.zim")
        try makeFile("Telefon/CO JE TADY.txt")

        let scan = try DriveScanner().scan(root: root)

        XCTAssertEqual(scan.books.map(\.url.lastPathComponent), [
            "maps_en_czech-republic_2026-06.zim",
            "offlinka_cs_prirucky.zim",
            "wikipedia_cs_all_maxi-offlinka_2026-09.zim",
            "wikipedia_sk_all_mini-offlinka_2026-08.zim",
        ])
        let cs = try XCTUnwrap(scan.books.first { $0.name.baseKey == "wikipedia_cs_all" })
        XCTAssertEqual(cs.folder, .library)
        XCTAssertEqual(cs.sizeBytes, 300)
        XCTAssertEqual(cs.indexURL?.lastPathComponent, "wikipedia_cs_all_maxi-offlinka_2026-09.offidx")
        XCTAssertEqual(scan.books.first { $0.name.baseKey == "offlinka_cs_prirucky" }?.folder, .library)
        // The phone mini of SK is the only SK edition, so it is used.
        XCTAssertEqual(scan.books.first { $0.name.baseKey == "wikipedia_sk_all" }?.folder, .phone)
        XCTAssertEqual(Set(scan.shadowed.map(\.url.lastPathComponent)), [
            "wikipedia_cs_all_mini-offlinka_2026-09.zim",
            "offlinka_cs_prirucky.zim",
        ])
    }

    func testIndexNamedAfterFullFileName() throws {
        try makeFile("Knihovna/wikibooks_cs_all_maxi-offlinka_2026-10.zim")
        try makeFile("Knihovna/wikibooks_cs_all_maxi-offlinka_2026-10.zim.offidx")
        let scan = try DriveScanner().scan(root: root)
        XCTAssertEqual(scan.books.first?.indexURL?.lastPathComponent, "wikibooks_cs_all_maxi-offlinka_2026-10.zim.offidx")
    }

    func testIgnoresHiddenAndAppleDoubleFiles() throws {
        try makeFile("Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        try makeFile("Knihovna/._wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        try makeFile("Knihovna/.hidden.zim")
        let scan = try DriveScanner().scan(root: root)
        XCTAssertEqual(scan.books.count, 1)
        XCTAssertTrue(scan.shadowed.isEmpty)
    }

    func testFolderNamesAreCaseInsensitive() throws {
        try makeFile("KNIHOVNA/wikipedia_cs_all_maxi-offlinka_2026-09.ZIM")
        let scan = try DriveScanner().scan(root: root)
        XCTAssertEqual(scan.books.count, 1)
        XCTAssertEqual(scan.books.first?.folder, .library)
    }

    func testUserPickedTheLibraryFolderItself() throws {
        try makeFile("Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        let scan = try DriveScanner().scan(root: root.appendingPathComponent("Knihovna"))
        XCTAssertEqual(scan.books.count, 1)
        XCTAssertEqual(scan.books.first?.folder, .library)
    }

    func testUserPickedThePhoneFolderItself() throws {
        try makeFile("Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim")
        let scan = try DriveScanner().scan(root: root.appendingPathComponent("Telefon"))
        XCTAssertEqual(scan.books.first?.folder, .phone)
    }

    func testWrongFolderReportsNoBooks() throws {
        try makeFile("3 Nouzové příručky/prvni-pomoc.pdf")
        XCTAssertThrowsError(try DriveScanner().scan(root: root)) { error in
            XCTAssertEqual(error as? DriveScanProblem, .noBooks(sawDriveFolders: false))
        }
    }

    func testEmptyDriveFoldersReportNoBooks() throws {
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Knihovna"), withIntermediateDirectories: true)
        XCTAssertThrowsError(try DriveScanner().scan(root: root)) { error in
            XCTAssertEqual(error as? DriveScanProblem, .noBooks(sawDriveFolders: true))
        }
    }

    func testMissingFolderIsNotReachable() {
        let gone = root.appendingPathComponent("unplugged")
        XCTAssertThrowsError(try DriveScanner().scan(root: gone)) { error in
            XCTAssertEqual(error as? DriveScanProblem, .notReachable)
        }
    }

    func testScanNeverWrites() throws {
        try makeFile("Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim")
        let before = try listing()
        _ = try DriveScanner().scan(root: root)
        XCTAssertEqual(try listing(), before)
    }

    // MARK: - Helpers

    private func makeFile(_ relativePath: String, size: Int = 16) throws {
        let url = root.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 0x5A, count: size).write(to: url)
    }

    private func listing() throws -> [String] {
        let enumerator = FileManager.default.enumerator(atPath: root.path)
        return (enumerator?.allObjects as? [String] ?? []).sorted()
    }
}
