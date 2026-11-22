import Foundation

/// App language: follow the phone, or a fixed one chosen in Settings.
enum AppLanguage: String, Codable, CaseIterable, Sendable {
    case system, cs, sk, en
}

/// Localized strings with an in-app language override.
///
/// Every UI string goes through `tr(_:)` with a typed key (`Str`), looked up in the `.lproj` of the
/// selected language that Xcode compiles from `Localizable.xcstrings`. Switching the language in
/// Settings takes effect at once (the root view is rebuilt), without restarting the app.
enum L10n {
    static let supported = ["cs", "sk", "en"]

    // Written only on the main thread (launch, Settings); read anywhere. Swapping a reference or
    // value is not torn, and a reader that sees the old language for one frame is harmless.
    nonisolated(unsafe) private static var bundle: Bundle = .main
    /// `cs`, `sk` or `en`.
    nonisolated(unsafe) private(set) static var languageCode = "cs"
    nonisolated(unsafe) private(set) static var locale = Locale(identifier: "cs_CZ")

    static func apply(_ language: AppLanguage, base: Bundle = .main) {
        let code = language == .system ? systemLanguageCode(base) : language.rawValue
        languageCode = code
        locale = Locale(identifier: code == "en" ? "en" : "\(code)_\(code.uppercased())")
        if let path = base.path(forResource: code, ofType: "lproj"), let localized = Bundle(path: path) {
            bundle = localized
        } else {
            bundle = base
        }
    }

    /// The phone's language if the app has it (this honours the per-app language in iOS Settings), else Czech.
    static func systemLanguageCode(_ base: Bundle = .main) -> String {
        let fromBundle = base.preferredLocalizations.first { supported.contains($0) }
        let fromPreferences = Bundle.preferredLocalizations(from: supported, forPreferences: Locale.preferredLanguages).first
        return fromBundle ?? fromPreferences ?? "cs"
    }

    static func string(_ key: Str) -> String {
        bundle.localizedString(forKey: key.rawValue, value: nil, table: "Localizable")
    }

    /// For tests: is there a real translation (not just the key echoed back)?
    static func hasString(_ key: Str, language: String, base: Bundle = .main) -> Bool {
        guard let path = base.path(forResource: language, ofType: "lproj"), let localized = Bundle(path: path) else { return false }
        let value = localized.localizedString(forKey: key.rawValue, value: "\u{1}", table: "Localizable")
        return value != "\u{1}" && value != key.rawValue && !value.isEmpty
    }
}

func tr(_ key: Str) -> String {
    L10n.string(key)
}

func tr(_ key: Str, _ arguments: CVarArg...) -> String {
    String(format: L10n.string(key), locale: L10n.locale, arguments: arguments)
}

/// Pages on the web the app links to (About's privacy policy, Connect's shop link); everything else stays inside
/// the app. Both open in Safari.
enum WebLinks {
    /// `utm_medium` of the shop link from this app (Android passes `android`).
    static let medium = "ios"

    /// The shop for people without a drive yet (`connect_buy_url`): offlinka.sk in Slovak, offlinka.cz otherwise.
    /// A physical product sold on the web (App Review 3.1.5(a)); no price in the app.
    static func shop() -> URL {
        if let url = URL(string: tr(.connectBuyUrl, medium)), url.scheme == "https", url.query?.contains("utm_medium=\(medium)") == true {
            return url
        }
        return shop(language: L10n.languageCode)
    }

    /// The same link without the string catalog (unit tests on Linux, a missing translation).
    static func shop(language: String) -> URL {
        let host = language == "sk" ? "offlinka.sk" : "offlinka.cz"
        return URL(string: "https://\(host)/?utm_source=app&utm_medium=\(medium)&utm_campaign=connect")!
    }

    /// The privacy policy in the UI language (`about_privacy_url`); the store listings link the same pages.
    static func privacyPolicy() -> URL {
        if let url = URL(string: tr(.aboutPrivacyUrl)), url.scheme == "https" {
            return url
        }
        return privacyPolicy(language: L10n.languageCode)
    }

    /// The same pages without the string catalog (unit tests on Linux, a missing translation).
    static func privacyPolicy(language: String) -> URL {
        switch language {
        case "sk": return URL(string: "https://offlinka.sk/aplikacia/sukromie")!
        case "en": return URL(string: "https://offlinka.cz/en/app/privacy")!
        default: return URL(string: "https://offlinka.cz/aplikace/soukromi")!
        }
    }
}
