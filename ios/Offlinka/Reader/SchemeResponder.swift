import Foundation

/// A request to `offlinka://`, reduced to what the responder needs (Sendable, testable without WebKit).
struct SchemeRequest: Sendable {
    let url: URL
    let headers: [String: String]

    init(url: URL, headers: [String: String] = [:]) {
        self.url = url
        self.headers = headers
    }

    func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}

/// What the scheme handler sends back to WebKit.
struct SchemeReply: Sendable, Equatable {
    var statusCode: Int
    var headers: [String: String]
    var body: Data
    /// Reading the drive failed: the app should check whether it was unplugged.
    var driveFailed = false
}

/// Texts of the small pages the reader shows instead of an article. Built on the main thread.
struct ReaderTexts: Sendable, Equatable {
    var languageCode = "cs"
    var missingTitle = ""
    var missingBody = ""
    var driveTitle = ""
    var driveBody = ""
    var noBookTitle = ""
    var noBookBody = ""
    var errorTitle = ""
    var errorBody = ""
}

/// The app's own reader assets served at `offlinka://app.offlinka/<name>` (stylesheet, fonts).
struct AppAssets: Sendable {
    private let lookup: @Sendable (String) -> Data?

    init(lookup: @escaping @Sendable (String) -> Data?) {
        self.lookup = lookup
    }

    static let allowedExtensions: Set<String> = ["css", "js", "ttf", "otf", "woff", "woff2", "svg", "png"]

    /// Served name → bundled file name (design/reader/offlinka-reader.css is served as reader.css).
    static let aliases = ["reader.css": "offlinka-reader.css"]

    func data(for path: String) -> Data? {
        let name = (path as NSString).lastPathComponent
        let ext = (name as NSString).pathExtension.lowercased()
        guard Self.allowedExtensions.contains(ext) else { return nil }
        return lookup(Self.aliases[name] ?? name)
    }

    /// Looks files up flat in the bundle (resources are copied to the bundle root).
    static func bundle(_ bundle: Bundle) -> AppAssets {
        AppAssets { name in
            let base = (name as NSString).deletingPathExtension
            let ext = (name as NSString).pathExtension
            guard let url = bundle.url(forResource: base, withExtension: ext) else { return nil }
            return try? Data(contentsOf: url)
        }
    }

    static let none = AppAssets { _ in nil }
}

/// What the reader adds to the `<head>` of Wikimedia pages (design/reader/inject.js contract):
/// the initial theme and text size, our stylesheet and the hook script. Inserting it into the
/// HTML (instead of a user script) styles the page before its first paint.
struct ReaderHead: Sendable, Equatable {
    /// Books whose HTML pages get it (Wikimedia projects).
    var bookIds: Set<String>
    /// `light`, `dark` or `system`.
    var theme: String
    var scale: Double

    var snippet: String {
        let config = "window.__offlinka={theme:\(SchemeResponder.javaScriptString(theme)),scale:\(scale)};"
        return "<script>\(config)</script>"
            + "<link id=\"offlinka-reader-css\" rel=\"stylesheet\" href=\"/\(OfflinkaURL.assetPathPrefix)reader.css\">"
            + "<script src=\"/\(OfflinkaURL.assetPathPrefix)inject.js\"></script>"
    }

    /// The page with the snippet inserted before `</head>`; unchanged when there is no head.
    func inject(into html: Data) -> Data {
        guard let close = html.range(of: Data("</head>".utf8)) ?? html.range(of: Data("</HEAD>".utf8)) else { return html }
        var result = Data(capacity: html.count + 400)
        result.append(html.subdata(in: html.startIndex..<close.lowerBound))
        result.append(Data(snippet.utf8))
        result.append(html.subdata(in: close.lowerBound..<html.endIndex))
        return result
    }
}

/// Answers `offlinka://<bookId>/<path>` requests from the content source.
///
/// Pure logic, no WebKit: the `WKURLSchemeHandler` runs it off the main thread and delivers the reply.
struct SchemeResponder: Sendable {
    let source: ContentSource
    let assets: AppAssets
    let texts: ReaderTexts
    /// Reader stylesheet hook for Wikimedia pages; nil leaves every page untouched.
    var readerHead: ReaderHead? = nil
    /// Largest body of one range reply. Media elements ask again for the rest.
    var maxRangeLength: Int64 = 8 * 1024 * 1024

    func reply(to request: SchemeRequest) -> SchemeReply {
        guard let parsed = OfflinkaURL.parse(request.url) else {
            return page(status: 400, title: texts.errorTitle, body: texts.errorBody)
        }
        if parsed.bookId == OfflinkaURL.appHost {
            return asset(parsed.path)
        }
        if parsed.path.hasPrefix(OfflinkaURL.assetPathPrefix) {
            // Same-origin copy of the app assets, so reader fonts load without CORS.
            return asset(String(parsed.path.dropFirst(OfflinkaURL.assetPathPrefix.count)))
        }
        guard let book = source.book(id: parsed.bookId) else {
            return page(status: 404, title: texts.noBookTitle, body: texts.noBookBody)
        }
        if parsed.path.isEmpty, let main = book.mainPath, !main.isEmpty {
            return redirect(bookId: book.id, path: main)
        }

        do {
            if let rangeHeader = request.header("Range") {
                let total = try source.contentLength(bookId: book.id, path: parsed.path)
                switch ByteRange.parse(rangeHeader, totalLength: total, maxLength: maxRangeLength) {
                case .satisfiable(let range):
                    let data = try source.range(bookId: book.id, path: parsed.path, offset: range.start, length: Int(range.length))
                    let end = range.start + Int64(data.count) - 1
                    var headers = baseHeaders(mime: MimeTypes.forPath(parsed.path), length: data.count)
                    headers["Content-Range"] = "bytes \(range.start)-\(max(end, range.start))/\(total)"
                    return SchemeReply(statusCode: 206, headers: headers, body: data)
                case .unsatisfiable:
                    var headers = baseHeaders(mime: MimeTypes.forPath(parsed.path), length: 0)
                    headers["Content-Range"] = "bytes */\(total)"
                    return SchemeReply(statusCode: 416, headers: headers, body: Data())
                case .none:
                    break
                }
            }

            let resource = try source.resource(bookId: book.id, path: parsed.path)
            let target = Self.normalized(resource.path)
            if target != Self.normalized(parsed.path), !target.isEmpty, MimeTypes.isHTML(resource.mimeType) {
                // A redirect inside the ZIM. Move the page to its real address so relative links,
                // history and "saved" all use the target path.
                return redirect(bookId: book.id, path: target)
            }
            let mime = MimeTypes.withCharset(resource.mimeType.isEmpty ? MimeTypes.forPath(resource.path) : resource.mimeType)
            var body = resource.data
            if let readerHead, readerHead.bookIds.contains(book.id), MimeTypes.isHTML(mime) {
                body = readerHead.inject(into: body)
            }
            return SchemeReply(statusCode: 200, headers: baseHeaders(mime: mime, length: body.count), body: body)
        } catch let error as ContentError {
            switch error {
            case .notFound, .unknownBook:
                return page(status: 404, title: texts.missingTitle, body: texts.missingBody)
            case .io:
                var reply = page(status: 503, title: texts.driveTitle, body: texts.driveBody)
                reply.driveFailed = true
                return reply
            case .invalidZim, .unsupported:
                return page(status: 500, title: texts.errorTitle, body: texts.errorBody)
            }
        } catch {
            return page(status: 500, title: texts.errorTitle, body: texts.errorBody)
        }
    }

    /// ZIM paths compared without a leading slash or the `C/` content namespace prefix.
    static func normalized(_ path: String) -> String {
        var result = Substring(path)
        while result.hasPrefix("/") {
            result = result.dropFirst()
        }
        if result.hasPrefix("C/") {
            result = result.dropFirst(2)
        }
        return String(result)
    }

    // MARK: - Replies

    private func baseHeaders(mime: String, length: Int) -> [String: String] {
        [
            "Content-Type": mime,
            "Content-Length": String(length),
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*",
            // Book ids are ZIM names, the same across editions: never let WebKit reuse a page of
            // the previous drive. Revalidating costs one more call into the core's cluster cache.
            "Cache-Control": "no-cache",
        ]
    }

    private func asset(_ path: String) -> SchemeReply {
        guard let data = assets.data(for: path) else {
            return SchemeReply(statusCode: 404, headers: baseHeaders(mime: "text/plain", length: 0), body: Data())
        }
        return SchemeReply(statusCode: 200, headers: baseHeaders(mime: MimeTypes.withCharset(MimeTypes.forPath(path)), length: data.count), body: data)
    }

    /// WKURLSchemeHandler cannot answer with an HTTP redirect, so this small page replaces itself
    /// with the target (`location.replace` keeps the back list clean).
    func redirect(bookId: String, path: String) -> SchemeReply {
        let target = OfflinkaURL.make(bookId: bookId, path: path)?.absoluteString ?? ""
        let literal = Self.javaScriptString(target)
        let html = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">\
        <script>location.replace(\(literal) + location.hash);</script></head><body></body></html>
        """
        let data = Data(html.utf8)
        return SchemeReply(statusCode: 200, headers: baseHeaders(mime: "text/html; charset=utf-8", length: data.count), body: data)
    }

    /// A calm, self-contained page in the brand colours (light and dark).
    func page(status: Int, title: String, body: String) -> SchemeReply {
        let html = """
        <!doctype html><html lang="\(Self.escapeHTML(texts.languageCode))"><head><meta charset="utf-8">\
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>\(Self.escapeHTML(title))</title>\
        <style>:root{color-scheme:light dark}body{margin:0;padding:56px 24px;background:#F5F0E3;color:#17231C;\
        font:400 18px/1.5 -apple-system,system-ui,sans-serif}h1{font-size:22px;line-height:1.25;margin:0 0 12px}\
        p{margin:0;color:#5F5B4B}@media (prefers-color-scheme:dark){body{background:#101B15;color:#F5F0E3}p{color:#C6D1BC}}</style>\
        </head><body><h1>\(Self.escapeHTML(title))</h1><p>\(Self.escapeHTML(body))</p></body></html>
        """
        let data = Data(html.utf8)
        return SchemeReply(statusCode: status, headers: baseHeaders(mime: "text/html; charset=utf-8", length: data.count), body: data)
    }

    static func escapeHTML(_ text: String) -> String {
        var result = ""
        result.reserveCapacity(text.count)
        for character in text {
            switch character {
            case "&": result += "&amp;"
            case "<": result += "&lt;"
            case ">": result += "&gt;"
            case "\"": result += "&quot;"
            case "'": result += "&#39;"
            default: result.append(character)
            }
        }
        return result
    }

    /// A JavaScript string literal (JSON string) that is also safe inside a `<script>` element.
    static func javaScriptString(_ text: String) -> String {
        var result = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": result += "\\\""
            case "\\": result += "\\\\"
            case "\n": result += "\\n"
            case "\r": result += "\\r"
            case "<": result += "\\u003C"
            case ">": result += "\\u003E"
            case "\u{2028}": result += "\\u2028"
            case "\u{2029}": result += "\\u2029"
            default:
                if scalar.value < 0x20 {
                    result += String(format: "\\u%04X", scalar.value)
                } else {
                    result.unicodeScalars.append(scalar)
                }
            }
        }
        return result + "\""
    }
}

/// MIME types for paths whose type the core does not report (range replies, app assets).
enum MimeTypes {
    private static let byExtension: [String: String] = [
        "html": "text/html", "htm": "text/html", "css": "text/css", "js": "application/javascript",
        "mjs": "application/javascript", "json": "application/json", "txt": "text/plain", "xml": "application/xml",
        "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif", "webp": "image/webp",
        "svg": "image/svg+xml", "ico": "image/x-icon", "avif": "image/avif",
        "mp4": "video/mp4", "m4v": "video/mp4", "webm": "video/webm", "ogv": "video/ogg",
        "mp3": "audio/mpeg", "m4a": "audio/mp4", "ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg",
        "wav": "audio/wav", "flac": "audio/flac",
        "pdf": "application/pdf", "pbf": "application/x-protobuf", "wasm": "application/wasm",
        "woff": "font/woff", "woff2": "font/woff2", "ttf": "font/ttf", "otf": "font/otf",
    ]

    static func forPath(_ path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        return byExtension[ext] ?? "application/octet-stream"
    }

    static func isHTML(_ mime: String) -> Bool {
        let lower = mime.lowercased()
        return lower.hasPrefix("text/html") || lower.hasPrefix("application/xhtml+xml")
    }

    /// Adds `charset=utf-8` to text types that do not name a charset (ZIMs store UTF-8).
    static func withCharset(_ mime: String) -> String {
        let lower = mime.lowercased()
        guard !lower.contains("charset=") else { return mime }
        let isText = lower.hasPrefix("text/") || lower.hasPrefix("application/javascript")
            || lower.hasPrefix("application/json") || lower.hasPrefix("application/xhtml+xml")
        return isText ? mime + "; charset=utf-8" : mime
    }
}
