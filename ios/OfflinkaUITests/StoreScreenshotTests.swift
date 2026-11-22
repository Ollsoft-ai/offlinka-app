import XCTest

/// App Store screenshots from the simulator. Runs only with `OFFLINKA_STORE_SHOTS=1` (CI:
/// `TEST_RUNNER_OFFLINKA_STORE_SHOTS=1`); languages from `OFFLINKA_STORE_LANGS` (default `cs,sk,en`).
///
/// The drive: `OFFLINKA_FIXTURE_DRIVE` (CI: the store-shots fixture, trimmed copies of the real drive's
/// books with their real names, metadata and pictures, opened through `-fixture-drive` and the Connect
/// button; `OFFLINKA_FIXTURE_SIZES` gives the real file sizes, `OFFLINKA_MAP_VIEW` the map's view), else the
/// demo drive (`-demo-drive`, fake pages).
/// Every capture is a result-bundle attachment named like the Paparazzi golden it replaces
/// (`ScreensTest_home[cs-light]`), so `ios/tools/export-shots.py` + `store/screenshots/build.sh`
/// (`SNAPSHOTS=<folder>`) frame them. Status bar: `simctl status_bar` (CI, `ios/tools/sim-prepare.sh`).
final class StoreScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    private static func library(_ language: String) -> String {
        switch language {
        case "sk": return "Knižnica"
        case "en": return "Library"
        default: return "Knihovna"
        }
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(ProcessInfo.processInfo.environment["OFFLINKA_STORE_SHOTS"] == "1",
                          "store screenshots run with OFFLINKA_STORE_SHOTS=1 only")
    }

    private var languages: [String] {
        let list = ProcessInfo.processInfo.environment["OFFLINKA_STORE_LANGS"] ?? ""
        let parsed = list.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return parsed.isEmpty ? ["cs", "sk", "en"] : parsed
    }

    // One test per language: a flaky UI query in one language does not cost the others.
    func testStoreScreenshotsCs() throws { try capture(language: "cs") }
    func testStoreScreenshotsEn() throws { try capture(language: "en") }
    func testStoreScreenshotsSk() throws { try capture(language: "sk") }

    private func capture(language: String) throws {
        try XCTSkipUnless(languages.contains(language), "\(language) is not in OFFLINKA_STORE_LANGS")
        try captureLanguage(language)
    }

    private func captureLanguage(_ loc: String) throws {
        // Home, then search „cesky krumlov“ and the article.
        app = launchApp(language: loc, theme: "light")
        try capture("ScreensTest_home[\(loc)-light]")

        app.buttons["home.search"].tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "[\(loc)] search did not open")
        dismissKeyboardTip(app, wait: 3)
        field.typeText("cesky krumlov")   // not return: that opens the top hit
        dismissKeyboardTip(app, wait: 2)
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 20), "[\(loc)] no search result")
        dismissKeyboard()
        try capture("ScreensTest_search[\(loc)-light]")

        hit.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 60), "[\(loc)] article did not load")
        try capture("ScreensTest_reader[\(loc)-light]", settle: 3)

        // The map (MapLibre draws its tiles after the page loaded).
        app = launchApp(language: loc, theme: "light")
        let map = revealTile(in: app, "maps_en_czech")
        XCTAssertTrue(map.exists, "[\(loc)] no map tile")
        map.tap()
        // No UI query on the map: its accessibility tree can time out XCUITest while MapLibre loads.
        try capture("ScreenStatesTest_readerMap[\(loc)-light]", settle: 25)

        // First aid from the guides.
        app = launchApp(language: loc, theme: "light")
        let firstAid = app.buttons["home.firstAid"]
        XCTAssertTrue(firstAid.waitForExistence(timeout: 10), "[\(loc)] no first-aid link")
        firstAid.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 60), "[\(loc)] guide did not load")
        try capture("ScreenStatesTest_readerGuides[\(loc)-light]", settle: 3)

        // Library.
        app = launchApp(language: loc, theme: "light")
        let library = app.buttons[Self.library(loc)].firstMatch
        XCTAssertTrue(library.waitForExistence(timeout: 10), "[\(loc)] no Library tab")
        library.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Wiki")).firstMatch.waitForExistence(timeout: 10))
        try capture("ScreensTest_library[\(loc)-light]")

        // Dark: Home and an article with pictures (Karlštejn; in Slovak the mountains of the Vysoké Tatry).
        app = launchApp(language: loc, theme: "dark")
        try capture("ScreensTest_home[\(loc)-dark]")
        app.buttons["home.search"].tap()
        let darkField = app.textFields["search.field"]
        XCTAssertTrue(darkField.waitForExistence(timeout: 10), "[\(loc)] search did not open")
        dismissKeyboardTip(app, wait: 2)
        darkField.typeText(loc == "sk" ? "vysoke tatry" : "karlstejn")
        dismissKeyboardTip(app, wait: 2)
        let wanted = loc == "sk" ? "pohorie" : "Karlštejn"
        let article = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "search.hit", wanted)).firstMatch
        XCTAssertTrue(article.waitForExistence(timeout: 20), "[\(loc)] no hit for the dark article")
        article.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 60), "[\(loc)] dark article did not load")
        try capture("ScreensTest_reader[\(loc)-dark]", settle: 3)
    }

    /// The results list hides the keyboard as soon as it is dragged.
    private func dismissKeyboard() {
        guard app.keyboards.firstMatch.exists else { return }
        app.buttons["search.hit"].firstMatch.swipeDown()
        _ = app.keyboards.firstMatch.waitForNonExistence(timeout: 3)
    }

    /// Lets animations and web content settle, then keeps the screen as `name`.
    private func capture(_ name: String, settle: TimeInterval = 1.5) throws {
        Thread.sleep(forTimeInterval: settle)
        keepScreenshot(name, required: true)
    }
}
