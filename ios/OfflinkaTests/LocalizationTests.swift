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
        // design keeps short words and numbers with the next word (non-breaking spaces)
        func plain(_ text: String) -> String { text.replacingOccurrences(of: "\u{00A0}", with: " ") }

        L10n.apply(.cs)
        XCTAssertEqual(plain(tr(.navLibrary)), "Knihovna")
        XCTAssertEqual(plain(tr(.libraryBooksCount, 1)), "1 kniha")
        XCTAssertEqual(plain(tr(.libraryBooksCount, 3)), "3 knihy")
        XCTAssertEqual(plain(tr(.libraryBooksCount, 14)), "14 knih")
        XCTAssertEqual(plain(tr(.settingsDriveConnected, "OFFLINKA")), "Zapojená: OFFLINKA")

        L10n.apply(.sk)
        XCTAssertEqual(plain(tr(.connectTitle)), "Zapojte USB kľúč Offlinka")

        L10n.apply(.en)
        XCTAssertEqual(plain(tr(.libraryBooksCount, 1)), "1 book")
        XCTAssertEqual(plain(tr(.readerFindCount, 2, 5)), "2 of 5")
    }

    func testPrivacyPolicyLinkFollowsTheUILanguage() {
        XCTAssertEqual(WebLinks.privacyPolicy(language: "cs").absoluteString, "https://offlinka.cz/aplikace/soukromi")
        XCTAssertEqual(WebLinks.privacyPolicy(language: "sk").absoluteString, "https://offlinka.sk/aplikacia/sukromie")
        XCTAssertEqual(WebLinks.privacyPolicy(language: "en").absoluteString, "https://offlinka.cz/en/app/privacy")
    }

    /// design/strings (`about_privacy_url`) and the built-in fallback point at the same pages.
    func testPrivacyPolicyLinkFromDesignMatchesTheFallback() throws {
        try XCTSkipIf(Bundle.main.path(forResource: "cs", ofType: "lproj") == nil, "strings are not compiled into this bundle")
        defer { L10n.apply(.system) }
        for language in [AppLanguage.cs, .sk, .en] {
            L10n.apply(language)
            XCTAssertEqual(WebLinks.privacyPolicy(), WebLinks.privacyPolicy(language: language.rawValue))
        }
    }
}
