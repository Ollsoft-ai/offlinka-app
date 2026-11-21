import XCTest
@testable import Offlinka

/// Every typed key must exist in cs, sk and en of the compiled `Localizable.xcstrings` (design/).
/// Runs in the app-hosted test bundle, where `Bundle.main` is the app.
final class LocalizationTests: XCTestCase {
    func testEveryKeyIsTranslated() throws {
        try XCTSkipIf(Bundle.main.path(forResource: "cs", ofType: "lproj") == nil, "strings are not compiled into this bundle")
        var missing: [String] = []
        for language in L10n.supported {
            for key in Str.allCases where !L10n.hasString(key, language: language) {
                missing.append("\(language): \(key.rawValue)")
            }
        }
        XCTAssertEqual(missing, [])
    }

    func testLanguageSwitchAndPlurals() throws {
        try XCTSkipIf(Bundle.main.path(forResource: "cs", ofType: "lproj") == nil, "strings are not compiled into this bundle")
        defer { L10n.apply(.system) }

        L10n.apply(.cs)
        XCTAssertEqual(tr(.navLibrary), "Knihovna")
        XCTAssertEqual(tr(.libraryBooksCount, 1), "1 kniha")
        XCTAssertEqual(tr(.libraryBooksCount, 3), "3 knihy")
        XCTAssertEqual(tr(.libraryBooksCount, 14), "14 knih")
        XCTAssertEqual(tr(.settingsDriveConnected, "OFFLINKA"), "Zapojená: OFFLINKA")

        L10n.apply(.sk)
        XCTAssertEqual(tr(.connectTitle), "Zapojte USB kľúč Offlinka")

        L10n.apply(.en)
        XCTAssertEqual(tr(.libraryBooksCount, 1), "1 book")
        XCTAssertEqual(tr(.readerFindCount, 2, 5), "2 of 5")
    }

    /// Keys still waiting for design/strings: a text in every language, from design or the fallback.
    func testPendingKeysHaveTextInEveryLanguage() {
        defer { L10n.apply(.system) }
        for language in [AppLanguage.cs, .sk, .en] {
            L10n.apply(language)
            for key in PendingStr.allCases {
                let text = tr(key)
                XCTAssertFalse(text.isEmpty || text == key.rawValue, "\(language): \(key.rawValue)")
            }
        }
    }

    func testPrivacyPolicyLinkFollowsTheUILanguage() {
        XCTAssertEqual(WebLinks.privacyPolicy(language: "cs").absoluteString, "https://offlinka.cz/aplikace/soukromi")
        XCTAssertEqual(WebLinks.privacyPolicy(language: "sk").absoluteString, "https://offlinka.sk/aplikacia/sukromie")
        XCTAssertEqual(WebLinks.privacyPolicy(language: "en").absoluteString, "https://offlinka.cz/en/app/privacy")
    }
}
