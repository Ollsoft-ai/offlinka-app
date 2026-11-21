import XCTest

/// App Store screenshots from the simulator, on the demo drive (`-demo-drive`, debug builds). Runs only
/// with `OFFLINKA_STORE_SHOTS=1` (CI: `TEST_RUNNER_OFFLINKA_STORE_SHOTS=1`); languages from
/// `OFFLINKA_STORE_LANGS` (default `cs,sk,en`).
///
/// Every capture is a result-bundle attachment named like the Paparazzi golden it replaces
/// (`ScreensTest_home[cs-light]`), so `ios/tools/export-shots.py` + `store/screenshots/build.sh`
/// (`SNAPSHOTS=<folder>`) frame them. The reader captures show the demo page; the store frame draws
/// the real page from the drive into the area between the bars. Status bar: `simctl status_bar` (CI).
final class StoreScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    private struct Texts {
        let library: String

        static func of(_ language: String) -> Texts {
            switch language {
            case "sk": return Texts(library: "Knižnica")
            case "en": return Texts(library: "Library")
            default: return Texts(library: "Knihovna")
            }
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

    func testStoreScreenshots() throws {
        for language in languages {
            try captureLanguage(language)
        }
    }

    private func captureLanguage(_ loc: String) throws {
        // Home, then search „cesky krumlov“ and the article.
        launch(loc, theme: "light")
        try capture("ScreensTest_home[\(loc)-light]")

        app.buttons["home.search"].tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "[\(loc)] search did not open")
        field.typeText("cesky krumlov")   // not return: that opens the top hit
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 10), "[\(loc)] no search result")
        dismissKeyboard()
        try capture("ScreensTest_search[\(loc)-light]")

        hit.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 15), "[\(loc)] article did not load")
        try capture("ScreensTest_reader[\(loc)-light]")

        // The map.
        launch(loc, theme: "light")
        let map = tile("maps_")
        XCTAssertTrue(map.waitForExistence(timeout: 10), "[\(loc)] no map tile")
        map.tap()
        XCTAssertTrue(app.buttons["reader.back"].firstMatch.waitForExistence(timeout: 15), "[\(loc)] map did not open")
        try capture("ScreenStatesTest_readerMap[\(loc)-light]")

        // First aid from the guides.
        launch(loc, theme: "light")
        let firstAid = app.buttons["home.firstAid"]
        XCTAssertTrue(firstAid.waitForExistence(timeout: 10), "[\(loc)] no first-aid link")
        firstAid.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 15), "[\(loc)] guide did not load")
        try capture("ScreenStatesTest_readerGuides[\(loc)-light]")

        // Library.
        launch(loc, theme: "light")
        let library = app.buttons[Texts.of(loc).library].firstMatch
        XCTAssertTrue(library.waitForExistence(timeout: 10), "[\(loc)] no Library tab")
        library.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Wiki")).firstMatch.waitForExistence(timeout: 10))
        try capture("ScreensTest_library[\(loc)-light]")

        // Dark: Home and an article.
        launch(loc, theme: "dark")
        try capture("ScreensTest_home[\(loc)-dark]")
        let wikipedia = tile("wikipedia_\(loc == "sk" ? "sk" : "cs")_all")
        XCTAssertTrue(wikipedia.waitForExistence(timeout: 10), "[\(loc)] no Wikipedia tile")
        wikipedia.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 15), "[\(loc)] dark article did not load")
        try capture("ScreensTest_reader[\(loc)-dark]")
    }

    private func launch(_ language: String, theme: String) {
        app?.terminate()
        app = XCUIApplication()
        app.launchArguments = ["-demo-drive", "-demo-language", language, "-demo-theme", theme]
        app.launch()
        XCTAssertTrue(app.buttons["home.search"].waitForExistence(timeout: 20), "[\(language)] home did not appear")
    }

    private func tile(_ prefix: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile.\(prefix)")).firstMatch
    }

    /// The results list hides the keyboard as soon as it is dragged.
    private func dismissKeyboard() {
        guard app.keyboards.firstMatch.exists else { return }
        app.buttons["search.hit"].firstMatch.swipeDown()
        _ = app.keyboards.firstMatch.waitForNonExistence(timeout: 3)
    }

    /// Lets animations and web content settle, then keeps the screen as `name`.
    private func capture(_ name: String) throws {
        Thread.sleep(forTimeInterval: 1.5)
        keepScreenshot(name)
    }
}
