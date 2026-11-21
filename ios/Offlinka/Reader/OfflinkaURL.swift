import Foundation

/// URLs of the reader: `offlinka://<bookId>/<path inside the ZIM>`.
///
/// Every book is its own host, so relative links inside a ZIM resolve naturally, links between
/// books are plain URLs, and every book gets its own web storage (games keep their scores).
/// The reserved host `app.offlinka` serves the app's own reader assets (stylesheet, fonts).
enum OfflinkaURL {
    static let scheme = "offlinka"
    static let appHost = "app.offlinka"
    /// App assets are also served under this path on every book host (same origin as the page,
    /// so `@font-face` needs no CORS): `offlinka://<bookId>/_offlinka/reader.css`,
    /// `/_offlinka/inject.js`, `/_offlinka/fonts/archivo_regular.ttf` (design/reader contract).
    static let assetPathPrefix = "_offlinka/"

    struct Parsed: Equatable, Sendable {
        /// Lowercased book id (host).
        let bookId: String
        /// Percent-decoded path inside the book, without a leading slash. Empty for the book root.
        let path: String
        let fragment: String?
    }

    static func make(bookId: String, path: String, fragment: String? = nil) -> URL? {
        var string = "\(scheme)://\(bookId.lowercased())/\(encodePath(path))"
        if let fragment, !fragment.isEmpty {
            string += "#" + (fragment.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? fragment)
        }
        return URL(string: string)
    }

    static func appAsset(_ name: String) -> URL? {
        URL(string: "\(scheme)://\(appHost)/\(encodePath(name))")
    }

    /// Percent-encodes a ZIM path for use in a URL path. Keeps `/` so relative links keep working.
    static func encodePath(_ path: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "?#%")
        let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return trimmed.addingPercentEncoding(withAllowedCharacters: allowed) ?? trimmed
    }

    static func parse(_ url: URL) -> Parsed? {
        guard url.scheme?.lowercased() == scheme,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.host, !host.isEmpty
        else { return nil }
        var encodedPath = components.percentEncodedPath
        if encodedPath.hasPrefix("/") {
            encodedPath.removeFirst()
        }
        let path = encodedPath.removingPercentEncoding ?? encodedPath
        return Parsed(bookId: host.lowercased(), path: path, fragment: components.fragment)
    }

    /// `true` for links that would need the internet.
    static func isExternal(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return false }
        return !["offlinka", "about", "data", "blob", "javascript"].contains(scheme)
    }
}
