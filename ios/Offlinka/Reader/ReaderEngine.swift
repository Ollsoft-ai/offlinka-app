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
            errorBody: tr(.errorBody)
        )
    }
}

/// What all readers of one opened drive share: the `offlinka://` scheme handler (which also
/// adds the reader stylesheet to Wikimedia pages) and the content rules that keep WebKit offline.
@MainActor
final class ReaderEngine {
    let content: ContentSource
    let schemeHandler: OfflinkaSchemeHandler
    private(set) var contentRules: WKContentRuleList?
    private var rulesListeners: [(WKContentRuleList) -> Void] = []
    /// Books whose pages get the reader stylesheet (Wikimedia projects).
    private let styledBookIds: Set<String>

    init(content: ContentSource, appearance: ReaderAppearance, bundle: Bundle = .main) {
        self.content = content
        self.schemeHandler = OfflinkaSchemeHandler(source: content, assets: .bundle(bundle), texts: .current())
        self.styledBookIds = Set(content.books.filter { BookKind.of(name: $0.name).usesReaderStyle }.map(\.id))
        setAppearance(appearance)
        compileContentRules()
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
        if let contentRules {
            configuration.userContentController.add(contentRules)
        }
        return configuration
    }

    /// Runs `body` with the compiled content rules as soon as they exist.
    func whenContentRulesReady(_ body: @escaping (WKContentRuleList) -> Void) {
        if let contentRules {
            body(contentRules)
        } else {
            rulesListeners.append(body)
        }
    }

    // MARK: - Private

    /// Nothing may reach the network: block every http(s), ws(s) and ftp load. Navigations to
    /// such links are also stopped earlier, in the navigation delegate, with a calm notice.
    static let offlineRules = """
    [{"trigger":{"url-filter":"^https?:"},"action":{"type":"block"}},\
    {"trigger":{"url-filter":"^wss?:"},"action":{"type":"block"}},\
    {"trigger":{"url-filter":"^ftp:"},"action":{"type":"block"}}]
    """

    private func compileContentRules() {
        Task { [weak self] in
            let compiled: WKContentRuleList? = try? await WKContentRuleListStore.default()
                .compileContentRuleList(forIdentifier: "offlinka-offline-1", encodedContentRuleList: Self.offlineRules)
            guard let self, let compiled else { return }
            self.contentRules = compiled
            let listeners = self.rulesListeners
            self.rulesListeners = []
            for listener in listeners {
                listener(compiled)
            }
        }
    }
}

/// JavaScript the reader evaluates in the page on demand.
enum ReaderScripts {
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
