import XCTest

/// The real core on real ZIMs (`-fixture-drive`, debug builds).
///
/// The drive folder comes from `OFFLINKA_FIXTURE_DRIVE` (CI: `TEST_RUNNER_OFFLINKA_FIXTURE_DRIVE=<folder>`,
/// the review sample: `Telefon/` with the Czech Wikipedia mini + index and the games, `Knihovna/` with
/// the Slovak map). Without it the app makes a drive from its bundled guides. Screenshots are kept in
/// the result bundle (`ios/tools/export-shots.py` exports them).
final class FixtureDriveUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Connect → Home with the guides tile, search „prvni pomoc“ → the article → Back, query kept.
    func testConnectSearchFirstAidAndBack() throws {
        app = launchApp(language: "cs", theme: "light", connect: false)
        let connect = app.buttons["connect.pick"]
        XCTAssertTrue(connect.waitForExistence(timeout: 30), "connect screen did not appear")
        keepScreenshot("fixture-1-connect")
        connect.tap()

        let guidesTile = tile(in: app, "offlinka_cs_prirucky")
        XCTAssertTrue(guidesTile.waitForExistence(timeout: 30), "home with the guides tile did not appear")
        let search = app.buttons["home.search"]
        XCTAssertTrue(search.exists)
        keepScreenshot("fixture-2-home")

        search.tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        dismissKeyboardTip(app, wait: 3)
        field.typeText("prvni pomoc")
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 30), "no search result for „prvni pomoc“")
        keepScreenshot("fixture-3-search")
        hit.tap()

        // The first page of a launch starts WebKit's processes: slow on a cold CI simulator.
        let opened = Date()
        let heading = app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "První pomoc")).firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 90), "the article did not load from the ZIM")
        XCTContext.runActivity(named: String(format: "article shown after %.1f s", Date().timeIntervalSince(opened))) { _ in }
        Thread.sleep(forTimeInterval: 2)   // the text is in the accessibility tree before the first paint
        keepScreenshot("fixture-4-article")

        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Back did not return to the search")
        XCTAssertEqual(field.value as? String, "prvni pomoc")
        keepScreenshot("fixture-5-back")
    }

    /// The App Review demo walk, recorded from the simulator by the CI job `demo-video`: choose the drive,
    /// Home, search „cesky krumlov“, the article, Back, the map, the games, the first-aid guides. Calm
    /// pauses between the steps so the recording can be cut. Runs only with `OFFLINKA_DEMO_WALK=1`
    /// (UI language from `OFFLINKA_DEMO_LANG`, default cs) on the review sample drive.
    func testDemoWalkthrough() throws {
        let environment = ProcessInfo.processInfo.environment
        try XCTSkipUnless(environment["OFFLINKA_DEMO_WALK"] == "1", "the demo walk runs with OFFLINKA_DEMO_WALK=1 only")
        let language = environment["OFFLINKA_DEMO_LANG"].flatMap { $0.isEmpty ? nil : $0 } ?? "cs"
        app = launchApp(language: language, theme: "light", connect: false)

        // 1. Choose the drive.
        let connect = app.buttons["connect.pick"]
        XCTAssertTrue(connect.waitForExistence(timeout: 30), "connect screen did not appear")
        pause(2.5)
        connect.tap()

        // 2. Home.
        let search = app.buttons["home.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 30), "home did not appear")
        pause(3)

        // 3. Search „cesky krumlov“, typed at a readable pace.
        search.tap()
        let field = app.textFields["search.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        dismissKeyboardTip(app, wait: 3)
        pause(1)
        for character in "cesky krumlov" {
            field.typeText(String(character))
            Thread.sleep(forTimeInterval: 0.12)
        }
        dismissKeyboardTip(app)
        let hit = app.buttons["search.hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 20), "no search result")
        pause(2)

        // 4. The article.
        hit.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 90), "article did not load")
        pause(3)
        app.webViews.firstMatch.swipeUp()
        pause(2)

        // 5. Back: to the search with the query kept, then Home.
        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        pause()
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        pause()

        // 6. The map.
        let map = tile(in: app, "maps_")
        XCTAssertTrue(map.waitForExistence(timeout: 10), "no map tile")
        map.tap()
        XCTAssertTrue(app.buttons["reader.back"].firstMatch.waitForExistence(timeout: 60), "map did not open")
        pause(20)   // MapLibre draws its first tiles slowly on a CI simulator
        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        pause()

        // 7. The games.
        let games = tile(in: app, "offlinka_mul_hry")
        XCTAssertTrue(games.waitForExistence(timeout: 10), "no games tile")
        games.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 60), "games did not open")
        pause(4)
        app.buttons["reader.back"].firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        pause()

        // 8. The first-aid guides (the chip is in the header: Home is still scrolled down from the games tile).
        let firstAid = app.buttons["home.firstAid"]
        XCTAssertTrue(firstAid.waitForExistence(timeout: 10), "no first-aid link")
        // isHittable is true even under the bar: just scroll to the top (a swipe at the top only bounces).
        app.swipeDown()
        app.swipeDown()
        pause(1)
        firstAid.tap()
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.firstMatch.waitForExistence(timeout: 60), "guide did not load")
        pause(3)
        app.webViews.firstMatch.swipeUp()
        pause(3)
    }

    private func pause(_ seconds: TimeInterval = 1.5) {
        Thread.sleep(forTimeInterval: seconds)
    }
}

extension XCTestCase {
    /// Launches the app on the fixture drive when `OFFLINKA_FIXTURE_DRIVE` is set (or when `connect` is
    /// off: then the bundled guides make the drive), else on the demo drive. With `connect`, taps
    /// „Vybrat flashku“ on the fixture drive and waits for Home.
    func launchApp(language: String, theme: String, connect: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        let drive = ProcessInfo.processInfo.environment["OFFLINKA_FIXTURE_DRIVE"].flatMap { $0.isEmpty ? nil : $0 }
        let fixture = drive != nil || !connect
        app.launchArguments = fixture ? ["-fixture-drive", drive ?? "bundled"] : ["-demo-drive"]
        app.launchArguments += ["-demo-language", language, "-demo-theme", theme]
        app.launch()
        if connect {
            if fixture {
                let pick = app.buttons["connect.pick"]
                XCTAssertTrue(pick.waitForExistence(timeout: 30), "[\(language)] connect screen did not appear")
                pick.tap()
            }
            XCTAssertTrue(app.buttons["home.search"].waitForExistence(timeout: 30), "[\(language)] home did not appear")
        }
        return app
    }

    /// A Home tile by the start of its book key (`maps_`, `wikipedia_`, `offlinka_mul_hry`).
    func tile(in app: XCUIApplication, _ prefix: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile.\(prefix)")).firstMatch
    }

    /// The simulator's one-time keyboard tip („Speed up your typing…“) covers half the screen.
    func dismissKeyboardTip(_ app: XCUIApplication, wait: TimeInterval = 1) {
        let next = app.buttons["Continue"]
        if next.waitForExistence(timeout: wait) {
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
