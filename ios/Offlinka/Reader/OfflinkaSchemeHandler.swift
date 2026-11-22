import Foundation
import WebKit

/// Serves `offlinka://<bookId>/<path>` to WKWebView from the content source.
///
/// WebKit calls `start`/`stop` on the main thread. The core read runs on `reads` (blocking reads from
/// a slow drive must not hold Swift's cooperative threads; at most 6 at once, so a map or a page with many
/// pictures on a slow drive does not tie up dozens of threads) and the reply is delivered back on the
/// main thread, unless WebKit stopped the task meanwhile (calling a stopped task raises an
/// exception, so every task is tracked by a token).
final class OfflinkaSchemeHandler: NSObject, WKURLSchemeHandler {
    private static let reads: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "cz.offlinka.reader-reads"
        queue.qualityOfService = .userInitiated
        queue.maxConcurrentOperationCount = 6
        return queue
    }()

    private let source: ContentSource
    private let assets: AppAssets
    /// Texts of the small error pages. Main thread only; refreshed when the language changes.
    var texts: ReaderTexts
    /// Stylesheet hook for Wikimedia pages with the current theme and text size. Main thread only.
    var readerHead: ReaderHead?
    /// Called on the main thread when a read failed with an I/O error (drive unplugged?).
    var onDriveFailure: (@MainActor () -> Void)?

    private var tasks: [Int: WKURLSchemeTask] = [:]
    private var tokens: [ObjectIdentifier: Int] = [:]
    private var nextToken = 0

    init(source: ContentSource, assets: AppAssets, texts: ReaderTexts) {
        self.source = source
        self.assets = assets
        self.texts = texts
        super.init()
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        MainActor.assumeIsolated {
            guard let url = urlSchemeTask.request.url else {
                urlSchemeTask.didFailWithError(URLError(.badURL))
                return
            }
            nextToken += 1
            let token = nextToken
            tasks[token] = urlSchemeTask
            tokens[ObjectIdentifier(urlSchemeTask as AnyObject)] = token

            let request = SchemeRequest(url: url, headers: urlSchemeTask.request.allHTTPHeaderFields ?? [:])
            let responder = SchemeResponder(source: source, assets: assets, texts: texts, readerHead: readerHead)
            let started = DispatchTime.now().uptimeNanoseconds
            DebugLog.note("scheme #\(token) start \(url.absoluteString)")
            Self.reads.addOperation { [weak self] in
                let reply = responder.reply(to: request)
                DebugLog.note("scheme #\(token) read \(reply.statusCode) \(reply.body.count) B in \(DebugLog.ms(since: started)) ms")
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        self?.deliver(reply, url: url, token: token)
                    }
                }
            }
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        MainActor.assumeIsolated {
            if let token = tokens.removeValue(forKey: ObjectIdentifier(urlSchemeTask as AnyObject)) {
                tasks.removeValue(forKey: token)
                DebugLog.note("scheme #\(token) stopped by WebKit")
            }
        }
    }

    @MainActor
    private func deliver(_ reply: SchemeReply, url: URL, token: Int) {
        guard let task = tasks.removeValue(forKey: token) else { return }
        tokens.removeValue(forKey: ObjectIdentifier(task as AnyObject))
        guard let response = HTTPURLResponse(url: url, statusCode: reply.statusCode, httpVersion: "HTTP/1.1", headerFields: reply.headers) else {
            task.didFailWithError(URLError(.cannotParseResponse))
            return
        }
        task.didReceive(response)
        if !reply.body.isEmpty {
            task.didReceive(reply.body)
        }
        task.didFinish()
        if reply.driveFailed {
            onDriveFailure?()
        }
    }
}
