import Foundation
import Observation
import UIKit
import WebKit

/// What a reader opens: a page of a book, or its main page when `path` is nil.
struct ReaderTarget: Hashable, Sendable {
    let bookId: String
    let path: String?
}

/// One heading of the article, for the contents sheet.
struct ReaderHeading: Decodable, Identifiable, Hashable, Sendable {
    let id: String
    let text: String
    let level: Int
}

/// State of one reader screen: owns its WKWebView and mirrors title, back/forward and location.
@MainActor
@Observable
final class ReaderModel {
    let webView: WKWebView

    private(set) var title = ""
    private(set) var canGoBack = false
    private(set) var canGoForward = false
    private(set) var isLoading = false
    private(set) var progress = 0.0
    /// Book and path of the page on screen.
    private(set) var location: OfflinkaURL.Parsed?
    private(set) var headings: [ReaderHeading] = []
    /// A tapped link that needs the internet; the view shows a notice.
    var externalLink: URL?

    private let engine: ReaderEngine
    private let store: UserStore
    private let coordinator = ReaderWebCoordinator()
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var appearance: ReaderAppearance
    @ObservationIgnored private var initialTarget: ReaderTarget?

    init(target: ReaderTarget, engine: ReaderEngine, store: UserStore, appearance: ReaderAppearance) {
        self.engine = engine
        self.store = store
        self.appearance = appearance
        self.initialTarget = target

        let webView = WKWebView(frame: .zero, configuration: engine.makeConfiguration())
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false
        webView.isFindInteractionEnabled = true
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.contentInsetAdjustmentBehavior = .automatic
        #if DEBUG
        webView.isInspectable = true
        #endif
        self.webView = webView

        coordinator.model = self
        webView.navigationDelegate = coordinator
        webView.uiDelegate = coordinator
        if engine.contentRules == nil {
            // Rules still compiling (first reader after launch): add them as soon as they exist.
            engine.whenContentRulesReady { [weak webView] rules in
                webView?.configuration.userContentController.add(rules)
            }
        }
        observe()
    }

    /// Loads the first page. Called when the view appears (not in init, so previews stay cheap).
    func start() {
        guard let target = initialTarget else { return }
        initialTarget = nil
        open(target)
    }

    // MARK: - Derived

    var book: BookRecord? {
        location.flatMap { engine.content.book(id: $0.bookId) }
    }

    var bookTitle: String {
        book.map(BookCatalog.title(of:)) ?? ""
    }

    var isSaved: Bool {
        guard let book, let location else { return false }
        return store.isSaved(bookName: book.name, path: location.path)
    }

    var canSave: Bool {
        location != nil && book != nil && !(location?.path.isEmpty ?? true)
    }

    var usesReaderStyle: Bool {
        book.map { BookKind.of(name: $0.name).usesReaderStyle } ?? false
    }

    // MARK: - Actions

    func open(_ target: ReaderTarget) {
        guard let url = OfflinkaURL.make(bookId: target.bookId, path: target.path ?? "") else { return }
        webView.load(URLRequest(url: url))
    }

    func goBack() {
        webView.goBack()
    }

    func goForward() {
        webView.goForward()
    }

    func openMainPage() {
        guard let book else { return }
        open(ReaderTarget(bookId: book.id, path: nil))
    }

    func openRandomArticle() {
        guard let book else { return }
        let content = engine.content
        let bookId = book.id
        Task { [weak self] in
            let path = await Task.detached(priority: .userInitiated) { try? content.randomArticle(bookId: bookId) }.value
            guard let self, let path = path ?? nil else { return }
            self.open(ReaderTarget(bookId: bookId, path: path))
        }
    }

    /// Saves or un-saves the page. Returns the new state, nil when the page cannot be saved.
    @discardableResult
    func toggleSaved() -> Bool? {
        guard let book, let location, !location.path.isEmpty else { return nil }
        let article = ArticleRef(bookName: book.name, path: location.path, title: displayTitle, date: Date())
        return store.toggleSaved(article)
    }

    func showFind() {
        webView.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    /// Theme and text size: for the page on screen now, and for the pages loaded next.
    func apply(_ newAppearance: ReaderAppearance) {
        engine.setAppearance(newAppearance)
        guard newAppearance != appearance else { return }
        appearance = newAppearance
        webView.evaluateJavaScript(newAppearance.javaScript, completionHandler: nil)
    }

    func loadHeadings() {
        webView.evaluateJavaScript(ReaderScripts.headings) { [weak self] result, _ in
            let list = (result as? String)
                .flatMap { $0.data(using: .utf8) }
                .flatMap { try? JSONDecoder().decode([ReaderHeading].self, from: $0) } ?? []
            MainActor.assumeIsolated {
                self?.headings = list
            }
        }
    }

    func scrollTo(_ heading: ReaderHeading) {
        webView.evaluateJavaScript(ReaderScripts.scrollTo(heading.id), completionHandler: nil)
    }

    /// Title shown in the bar: the page title, else the book title.
    var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? bookTitle : trimmed
    }

    // MARK: - Navigation (from the coordinator)

    func policy(for action: WKNavigationAction) -> WKNavigationActionPolicy {
        guard let url = action.request.url else { return .cancel }
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "tel" {
            // Calling (e.g. 155 from the first-aid guide) needs no internet.
            UIApplication.shared.open(url)
            return .cancel
        }
        if OfflinkaURL.isExternal(url) {
            let isMainFrame = action.targetFrame?.isMainFrame ?? true
            if isMainFrame && action.navigationType == .linkActivated {
                externalLink = url
            }
            return .cancel
        }
        return .allow
    }

    func didFinishNavigation() {
        headings = []
        recordVisit()
    }

    func openInThisView(_ request: URLRequest) {
        webView.load(request)
    }

    func contentProcessTerminated() {
        webView.reload()
    }

    // MARK: - Private

    private func recordVisit() {
        guard let book, let location, !location.path.isEmpty else { return }
        let pageTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pageTitle.isEmpty else { return }
        store.recordVisit(ArticleRef(bookName: book.name, path: location.path, title: pageTitle, date: Date()))
    }

    private func observe() {
        observations = [
            webView.observe(\.title, options: [.initial, .new]) { [weak self] webView, _ in
                MainActor.assumeIsolated { self?.title = webView.title ?? "" }
            },
            webView.observe(\.canGoBack, options: [.initial, .new]) { [weak self] webView, _ in
                MainActor.assumeIsolated { self?.canGoBack = webView.canGoBack }
            },
            webView.observe(\.canGoForward, options: [.initial, .new]) { [weak self] webView, _ in
                MainActor.assumeIsolated { self?.canGoForward = webView.canGoForward }
            },
            webView.observe(\.isLoading, options: [.initial, .new]) { [weak self] webView, _ in
                MainActor.assumeIsolated { self?.isLoading = webView.isLoading }
            },
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
                MainActor.assumeIsolated { self?.progress = webView.estimatedProgress }
            },
            webView.observe(\.url, options: [.initial, .new]) { [weak self] webView, _ in
                MainActor.assumeIsolated {
                    self?.location = webView.url.flatMap(OfflinkaURL.parse)
                }
            },
        ]
    }
}

/// WebKit delegates of a reader, forwarded to its model on the main thread.
final class ReaderWebCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
    weak var model: ReaderModel?

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        MainActor.assumeIsolated {
            decisionHandler(model?.policy(for: navigationAction) ?? .cancel)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        MainActor.assumeIsolated {
            model?.didFinishNavigation()
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        MainActor.assumeIsolated {
            model?.contentProcessTerminated()
        }
    }

    /// Links with `target="_blank"` and `window.open`: open in the same reader.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        MainActor.assumeIsolated {
            if navigationAction.targetFrame == nil {
                model?.openInThisView(navigationAction.request)
            }
        }
        return nil
    }
}
