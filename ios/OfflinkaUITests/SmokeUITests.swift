import XCTest

/// Smoke test on the demo drive (`-demo-drive`, debug builds): home → search → reader → save →
/// saved list → library → settings. The full manual plan (real drive, unplugging, iPad) is in
/// ios/UI-SMOKE-TESTS.md.
final class SmokeUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-demo-drive", "-demo-language", "cs"]
        app.launch()
    }

    func testSearchOpenSaveAndBrowse() throws {
        let search = app.buttons["home.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 30), "home screen did not appear")
        XCTAssertTrue(app.buttons["tile.wikipedia_cs_all"].exists)
        keepScreenshot("demo-home")

        search.tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("cesky krumlov")
        dismissKeyboardTip(app)
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 5), "no search result")
        hit.tap()

        let article = app.webViews.staticTexts["Město na Vltavě se zámkem."]
        XCTAssertTrue(article.waitForExistence(timeout: 60), "article did not load from the scheme handler")

        app.buttons["reader.save"].tap()
        // „Zpět“ is never disabled: with no page history it returns to Search, query kept.
        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertEqual(app.textFields["search.field"].value as? String, "cesky krumlov")
        app.navigationBars.buttons.firstMatch.tap()

        app.tabBars.buttons["Uložené"].tap()
        XCTAssertTrue(element(containing: "Český Krumlov").waitForExistence(timeout: 5))

        app.tabBars.buttons["Knihovna"].tap()
        XCTAssertTrue(element(containing: "Česká Wikipedie").waitForExistence(timeout: 5))

        app.tabBars.buttons["Nastavení"].tap()
        // „O aplikaci“ is the last row: lists create rows only when they scroll into view.
        let about = app.descendants(matching: .any)["settings.about"].firstMatch
        for _ in 0..<4 where !about.waitForExistence(timeout: 2) {
            app.swipeUp()
        }
        XCTAssertTrue(about.exists, "no About row in Settings")
    }

    func testFirstAidQuickLink() throws {
        let firstAid = app.buttons["home.firstAid"]
        XCTAssertTrue(firstAid.waitForExistence(timeout: 30))
        firstAid.tap()
        XCTAssertTrue(app.webViews.staticTexts["Volejte 155 nebo 112."].waitForExistence(timeout: 60))
    }

    func testSlovakUILeadsWithTheSlovakWikipedia() throws {
        app.terminate()
        app.launchArguments = ["-demo-drive", "-demo-language", "sk"]
        app.launch()
        let tile = app.buttons["tile.wikipedia_sk_all"]
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        let czech = app.buttons["tile.wikipedia_cs_all"]
        XCTAssertTrue(czech.waitForExistence(timeout: 10))
        XCTAssertLessThan(tile.frame.minY, czech.frame.minY, "the UI-language Wikipedia is the first, wide tile")
    }

    func testExternalLinkShowsOfflineNotice() throws {
        XCTAssertTrue(app.buttons["tile.wikipedia_cs_all"].waitForExistence(timeout: 30))
        app.buttons["tile.wikipedia_cs_all"].tap()
        let praha = app.webViews.links["Praha"]
        XCTAssertTrue(praha.waitForExistence(timeout: 60), "the main page did not load")
        praha.tap()
        let external = app.webViews.links["Oficiální web"]
        XCTAssertTrue(external.waitForExistence(timeout: 10))
        external.tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5), "external link did not show the offline notice")
    }

    /// Rows combine their texts into one accessibility element, so match by "label contains".
    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }
}
