@_exported import Foundation
#if canImport(FoundationNetworking)
@_exported import FoundationNetworking
#endif
import UIKit

@_exported import UIKit

public struct WKDataDetectorTypes: OptionSet, Sendable { public let rawValue: UInt; public init(rawValue: UInt) { self.rawValue = rawValue } }
public enum WKNavigationActionPolicy: Int, Sendable { case cancel, allow }
public enum WKNavigationType: Int, Sendable { case linkActivated, formSubmitted, backForward, reload, formResubmitted, other }
@MainActor open class WKFrameInfo { public var isMainFrame: Bool { true } }
@MainActor open class WKNavigationAction { public var request: URLRequest { fatalError() }; public var targetFrame: WKFrameInfo? { nil }; public var navigationType: WKNavigationType { .other } }
open class WKNavigation {}
@MainActor open class WKWindowFeatures {}
public final class WKContentRuleList: @unchecked Sendable {}
@MainActor open class WKContentRuleListStore {
    public class func `default`() -> WKContentRuleListStore! { WKContentRuleListStore() }
    public func compileContentRuleList(forIdentifier: String!, encodedContentRuleList: String!) async throws -> WKContentRuleList? { nil }
}
@MainActor open class WKUserContentController { public func add(_ list: WKContentRuleList) {} }
@MainActor open class WKWebsiteDataStore { public class func `default`() -> WKWebsiteDataStore { WKWebsiteDataStore() } }
@MainActor open class WKWebpagePreferences { public var allowsContentJavaScript = true }
@MainActor open class WKWebViewConfiguration {
    public init() {}
    public func setURLSchemeHandler(_ handler: (any WKURLSchemeHandler)?, forURLScheme: String) {}
    public var websiteDataStore = WKWebsiteDataStore()
    public var allowsInlineMediaPlayback = false
    public var defaultWebpagePreferences = WKWebpagePreferences()
    public var dataDetectorTypes = WKDataDetectorTypes()
    public var userContentController = WKUserContentController()
}
@MainActor public protocol WKURLSchemeTask: NSObjectProtocol {
    var request: URLRequest { get }
    func didReceive(_ response: URLResponse)
    func didReceive(_ data: Data)
    func didFinish()
    func didFailWithError(_ error: any Error)
}
@MainActor public protocol WKURLSchemeHandler: NSObjectProtocol {
    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask)
    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask)
}
@MainActor public protocol WKNavigationDelegate: NSObjectProtocol {}
@MainActor public protocol WKUIDelegate: NSObjectProtocol {}
@MainActor open class WKWebView: UIView {
    public init(frame: CGRect, configuration: WKWebViewConfiguration) { self.configuration = configuration }
    public let configuration: WKWebViewConfiguration
    public var allowsBackForwardNavigationGestures = false
    public var allowsLinkPreview = true
    public var isFindInteractionEnabled = false
    public var findInteraction: UIFindInteraction? { nil }
    public var scrollView = UIScrollView()
    public var isInspectable = false
    @discardableResult public func loadFileURL(_ url: URL, allowingReadAccessTo readAccessURL: URL) -> WKNavigation? { nil }
    public weak var navigationDelegate: (any WKNavigationDelegate)?
    public weak var uiDelegate: (any WKUIDelegate)?
    public var title: String? { nil }
    public var canGoBack: Bool { false }
    public var canGoForward: Bool { false }
    public var isLoading: Bool { false }
    public var estimatedProgress: Double { 0 }
    public var url: URL? { nil }
    @discardableResult public func load(_ request: URLRequest) -> WKNavigation? { nil }
    @discardableResult public func goBack() -> WKNavigation? { nil }
    @discardableResult public func goForward() -> WKNavigation? { nil }
    @discardableResult public func reload() -> WKNavigation? { nil }
    public func evaluateJavaScript(_ js: String, completionHandler: (@MainActor @Sendable (Any?, (any Error)?) -> Void)? = nil) {}
    public func observe<Value>(_ keyPath: KeyPath<WKWebView, Value>, options: NSKeyValueObservingOptions = [],
                               changeHandler: @escaping @Sendable (WKWebView, NSKeyValueObservedChange<Value>) -> Void) -> NSKeyValueObservation { NSKeyValueObservation() }
}
