@_exported import Foundation
public enum UIUserInterfaceStyle: Int, Sendable { case unspecified, light, dark }
public final class UITraitCollection: @unchecked Sendable { public var userInterfaceStyle: UIUserInterfaceStyle = .light }
open class UIColor: @unchecked Sendable {
    public init(red: CGFloat, green: CGFloat, blue: CGFloat, alpha: CGFloat) {}
    public init(dynamicProvider: @escaping @Sendable (UITraitCollection) -> UIColor) {}
    public static var clear: UIColor { UIColor(red: 0, green: 0, blue: 0, alpha: 0) }
}
open class UIImage { public init?(named: String) { return nil } }
@MainActor open class UIView { public var backgroundColor: UIColor?; public var isOpaque: Bool = true; public init() {} }
public struct UIEdgeInsets: Sendable { public var top: CGFloat = 0; public var left: CGFloat = 0; public var bottom: CGFloat = 0; public var right: CGFloat = 0 }
public struct NSKeyValueObservingOptions: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }
    public static let new = NSKeyValueObservingOptions(rawValue: 1); public static let initial = NSKeyValueObservingOptions(rawValue: 4) }
public struct NSKeyValueObservedChange<Value> { public let newValue: Value? }
public final class NSKeyValueObservation { public init() {} }
@MainActor open class UIScrollView: UIView {
    public var contentOffset = CGPoint.zero
    public var adjustedContentInset = UIEdgeInsets()
    public func observe<Value>(_ keyPath: KeyPath<UIScrollView, Value>, options: NSKeyValueObservingOptions = [],
                               changeHandler: @escaping @Sendable (UIScrollView, NSKeyValueObservedChange<Value>) -> Void) -> NSKeyValueObservation { NSKeyValueObservation() }
    public enum ContentInsetAdjustmentBehavior { case automatic, never }
    public var contentInsetAdjustmentBehavior: ContentInsetAdjustmentBehavior = .automatic
}
@MainActor open class UIWindow: UIView { public var overrideUserInterfaceStyle: UIUserInterfaceStyle = .unspecified }
@MainActor open class UIScene {}
@MainActor open class UIWindowScene: UIScene { public var windows: [UIWindow] = [] }
public struct UIBackgroundTaskIdentifier: Hashable, Sendable { public static let invalid = UIBackgroundTaskIdentifier() }
@MainActor open class UIFont { public enum TextStyle: Sendable { case body, caption1 } }
@MainActor open class UIFontMetrics {
    public init(forTextStyle: UIFont.TextStyle) {}
    public func scaledValue(for value: CGFloat) -> CGFloat { value }
}
@MainActor open class UIApplication {
    public static let shared = UIApplication()
    nonisolated public static let didReceiveMemoryWarningNotification = Notification.Name("mw")
    public func beginBackgroundTask(withName: String?, expirationHandler: (@MainActor @Sendable () -> Void)?) -> UIBackgroundTaskIdentifier { .invalid }
    public func endBackgroundTask(_ id: UIBackgroundTaskIdentifier) {}
    public var connectedScenes: Set<UIScene> { [] }
    public var isIdleTimerDisabled = false
    public struct OpenExternalURLOptionsKey: Hashable { }
    public func open(_ url: URL, options: [OpenExternalURLOptionsKey: Any] = [:], completionHandler: (@MainActor @Sendable (Bool) -> Void)? = nil) {}
}
extension UIScene: Hashable {
    nonisolated public static func == (a: UIScene, b: UIScene) -> Bool { a === b }
    nonisolated public func hash(into h: inout Hasher) { h.combine(ObjectIdentifier(self)) }
}
@MainActor open class UIPasteboard { public static let general = UIPasteboard(); public var string: String? }
public enum UIAccessibility {
    public struct Notification: Sendable { public static let announcement = Notification() }
    @MainActor public static func post(notification: Notification, argument: Any?) {}
}
@MainActor open class UIFindInteraction { public func presentFindNavigator(showingReplace: Bool) {} }
extension URL {
    public func startAccessingSecurityScopedResource() -> Bool { true }
    public func stopAccessingSecurityScopedResource() {}
    public struct BookmarkCreationOptions: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue } }
    public struct BookmarkResolutionOptions: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue } }
    public func bookmarkData(options: BookmarkCreationOptions = [], includingResourceValuesForKeys: Set<URLResourceKey>? = nil, relativeTo: URL? = nil) throws -> Data { Data() }
    public init(resolvingBookmarkData data: Data, options: BookmarkResolutionOptions = [], relativeTo url: URL? = nil, bookmarkDataIsStale: inout Bool) throws { self.init(fileURLWithPath: "/") }
}
extension Data.WritingOptions { public static let completeFileProtectionUntilFirstUserAuthentication = Data.WritingOptions.atomic }
