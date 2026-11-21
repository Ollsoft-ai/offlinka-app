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
