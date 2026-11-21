import XCTest

/// The real core on a real ZIM (`-fixture-drive`, debug builds): Connect → Home with the guides tile,
/// search „prvni pomoc“ → the article → Back to the search, query kept.
///
/// The drive folder comes from `OFFLINKA_FIXTURE_DRIVE` (CI: `TEST_RUNNER_OFFLINKA_FIXTURE_DRIVE=<folder>`,
/// a folder with `Knihovna/offlinka_cs_prirucky.zim`); without it the app makes one from its bundled guides.
/// Screenshots are kept in the result bundle (`ios/tools/export-shots.py` exports them).
final class FixtureDriveUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        let drive = ProcessInfo.processInfo.environment["OFFLINKA_FIXTURE_DRIVE"].flatMap { $0.isEmpty ? nil : $0 } ?? "bundled"
        app.launchArguments = ["-fixture-drive", drive, "-demo-language", "cs"]
        app.launch()
    }

    func testConnectSearchFirstAidAndBack() throws {
        let connect = app.buttons["connect.pick"]
        XCTAssertTrue(connect.waitForExistence(timeout: 30), "connect screen did not appear")
        keepScreenshot("fixture-1-connect")
        connect.tap()

        let guidesTile = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile.offlinka_cs_prirucky")).firstMatch
        XCTAssertTrue(guidesTile.waitForExistence(timeout: 30), "home with the guides tile did not appear")
        let search = app.buttons["home.search"]
        XCTAssertTrue(search.exists)
        keepScreenshot("fixture-2-home")

        search.tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.typeText("prvni pomoc")
        dismissKeyboardTip(app)
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 30), "no search result for „prvni pomoc“")
        keepScreenshot("fixture-3-search")
        hit.tap()

        // The first page of a launch starts WebKit's processes: slow on a cold CI simulator.
        let opened = Date()
        let heading = app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "První pomoc")).firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 90), "the article did not load from the ZIM")
        XCTContext.runActivity(named: String(format: "article shown after %.1f s", Date().timeIntervalSince(opened))) { _ in }
        keepScreenshot("fixture-4-article")

        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Back did not return to the search")
        XCTAssertEqual(field.value as? String, "prvni pomoc")
        keepScreenshot("fixture-5-back")
    }
}

extension XCTestCase {
    /// The simulator's one-time keyboard tip („Speed up your typing…“) covers half the screen.
    func dismissKeyboardTip(_ app: XCUIApplication) {
        let next = app.buttons["Continue"]
        if next.waitForExistence(timeout: 1) {
            next.tap()
        }
    }

    /// A full-resolution PNG screenshot kept in the result bundle under `name` (also when the test passes).
    func keepScreenshot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        let attachment = XCTAttachment(uniformTypeIdentifier: "public.png", name: name, payload: png, userInfo: nil)
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
