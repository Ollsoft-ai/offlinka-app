import Foundation
import WebKit

/// How articles look: theme and text scale (Settings, reader menu).
struct ReaderAppearance: Equatable, Sendable {
    var theme: AppTheme
    var textScale: Double

    /// The theme name design/reader/inject.js understands.
    var themeName: String {
        switch theme {
        case .system: return "system"
        case .light: return "light"
        case .dark: return "dark"
        }
    }

    /// `offlinkaReader({...})`: applies theme and text size to the page on screen.
    var javaScript: String {
        "window.offlinkaReader && window.offlinkaReader({theme: \(SchemeResponder.javaScriptString(themeName)), scale: \(textScale)});"
    }
}

extension ReaderTexts {
    /// The error-page texts in the current UI language.
    static func current() -> ReaderTexts {
        ReaderTexts(
            languageCode: L10n.languageCode,
            missingTitle: tr(.readerNotFoundTitle),
            missingBody: tr(.readerNotFoundBody),
            driveTitle: tr(.unpluggedTitle),
            driveBody: tr(.unpluggedBody),
            noBookTitle: tr(.savedUnavailable),
            noBookBody: tr(.readerNotFoundBody),
            errorTitle: tr(.errorTitle),
            errorBody: tr(.errorBody),
            openingTitle: tr(.libraryOpening),
            fileTooBig: tr(.readerFileTooBig)
        )
    }
}

/// What all readers of one opened drive share: the `offlinka://` scheme handler (which also
/// adds the reader stylesheet to Wikimedia pages) and the content rules that keep WebKit offline.
@MainActor
final class ReaderEngine {
    let content: ContentSource
    let schemeHandler: OfflinkaSchemeHandler
    /// Books whose pages get the reader stylesheet (Wikimedia projects).
    private let styledBookIds: Set<String>

    init(content: ContentSource, appearance: ReaderAppearance, bundle: Bundle = .main) {
        self.content = content
        self.schemeHandler = OfflinkaSchemeHandler(source: content, assets: .bundle(bundle), texts: .current())
        self.styledBookIds = Set(content.books.filter { BookKind.of(name: $0.name).usesReaderStyle }.map(\.id))
        setAppearance(appearance)
        OfflineRules.prepare()
    }

    /// Call after the UI language changed.
    func refreshTexts() {
        schemeHandler.texts = .current()
    }

    /// Theme and text size for pages loaded from now on (pages on screen: `ReaderModel.apply`).
    func setAppearance(_ appearance: ReaderAppearance) {
        schemeHandler.readerHead = ReaderHead(bookIds: styledBookIds, theme: appearance.themeName, scale: appearance.textScale)
    }

    func makeConfiguration() -> WKWebViewConfiguration {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(schemeHandler, forURLScheme: OfflinkaURL.scheme)
        // Persistent store: games keep their scores in localStorage (each book is its own origin).
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.dataDetectorTypes = []
        if case .ready(let rules?) = OfflineRules.state {
            configuration.userContentController.add(rules)
        }
        return configuration
    }
}

/// The content rules that block every http(s), ws(s) and ftp load, set up once per app run: looked up in
/// WebKit's store first (compiled on an earlier run), compiled otherwise. Readers load their first page only
/// once this has settled. When it fails (a full phone cannot store the compiled list) pages still load: the
/// `Content-Security-Policy` of every `offlinka:` reply allows nothing remote either. Navigations to web
/// links are stopped earlier, in the navigation delegate, with a calm notice.
@MainActor
enum OfflineRules {
    enum State {
        case idle, loading
        /// Settled: the rules, or nil when they could not be set up this run.
        case ready(WKContentRuleList?)
    }

    static let identifier = "offlinka-offline-1"
    static let json = """
    [{"trigger":{"url-filter":"^https?:"},"action":{"type":"block"}},\
    {"trigger":{"url-filter":"^wss?:"},"action":{"type":"block"}},\
    {"trigger":{"url-filter":"^ftp:"},"action":{"type":"block"}}]
    """

    private(set) static var state = State.idle
    private static var waiters: [(WKContentRuleList?) -> Void] = []

    /// Starts the setup (at launch); later calls do nothing.
    static func prepare() {
        guard case .idle = state else { return }
        state = .loading
        Task {
            let rules = await load()
            state = .ready(rules)
            let waiting = waiters
            waiters = []
            for waiter in waiting {
                waiter(rules)
            }
        }
    }

    /// Runs `body` once the setup has settled (at once when it has).
    static func whenReady(_ body: @escaping (WKContentRuleList?) -> Void) {
        if case .ready(let rules) = state {
            body(rules)
            return
        }
        waiters.append(body)
        prepare()
    }

    private static func load() async -> WKContentRuleList? {
        guard let store = WKContentRuleListStore.default() else { return nil }
        let stored: WKContentRuleList? = await withCheckedContinuation { continuation in
            store.lookUpContentRuleList(forIdentifier: identifier) { list, _ in
                continuation.resume(returning: list)
            }
        }
        if let stored {
            return stored
        }
        return try? await store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json)
    }
}

/// JavaScript the reader evaluates in the page on demand.
enum ReaderScripts {
    #if DEBUG
    /// Moves the map of an openZIM maps book (`window.__openzim_map`, MapLibre) to `lat,lon,zoom` once its
    /// style has loaded. Store screenshots only.
    static func jumpMap(_ view: String) -> String {
        let parts = view.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 3 else { return "" }
        return """
        (function go(n) {
          var m = window.__openzim_map;
          if (m && m.isStyleLoaded && m.isStyleLoaded()) { m.jumpTo({center: [\(parts[1]), \(parts[0])], zoom: \(parts[2])}); }
          else if (n > 0) { setTimeout(function () { go(n - 1); }, 500); }
        })(120);
        """
    }
    #endif

    /// JSON list of the h2/h3 headings (ids added where missing) for the contents sheet.
    static let headings = """
    (function () {
      var out = [];
      var list = document.querySelectorAll('h2, h3');
      for (var i = 0; i < list.length; i++) {
        var h = list[i];
        var text = (h.textContent || '').replace(/\\s+/g, ' ').trim();
        if (!text) { continue; }
        if (!h.id) { h.id = 'offlinka-h-' + i; }
        out.push({ id: h.id, text: text, level: h.tagName === 'H2' ? 2 : 3 });
      }
      return JSON.stringify(out);
    })()
    """

    /// The article's address on the web, from `<link rel="canonical">` (empty when there is none).
    static let canonicalLink = "(function () { var l = document.querySelector('link[rel=canonical]'); return l ? l.href : ''; })()"

    /// Scrolls to a heading, opening collapsed sections (`<details>`) around it.
    static func scrollTo(_ id: String) -> String {
        """
        (function (id) {
          var el = document.getElementById(id);
          if (!el) { return; }
          var d = el.closest ? el.closest('details') : null;
          while (d) { d.open = true; d = d.parentElement ? d.parentElement.closest('details') : null; }
          el.scrollIntoView({ block: 'start' });
        })(\(SchemeResponder.javaScriptString(id)));
        """
    }
}
