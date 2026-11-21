@_exported import Foundation
import UIKit
import Combine
import UniformTypeIdentifiers
import Observation

// MARK: core protocols
@MainActor @preconcurrency public protocol View { associatedtype Body: View; @ViewBuilder var body: Body { get } }
extension Never: View { public var body: Never { fatalError() } }
extension Optional: View where Wrapped: View { public var body: Never { fatalError() } }
public struct EmptyView: View { public init() {}; public var body: Never { fatalError() } }
public struct AnyView: View { public init<V: View>(_ v: V) {}; public var body: Never { fatalError() } }
public struct TupleView<T>: View { public init(_ t: T) {}; public var body: Never { fatalError() } }
public struct _ConditionalContent<T: View, F: View>: View { public var body: Never { fatalError() } }
public struct _Mod<C>: View { public var body: Never { fatalError() } }

@resultBuilder public struct ViewBuilder {
    public static func buildExpression<C: View>(_ c: C) -> C { c }
    public static func buildBlock() -> EmptyView { EmptyView() }
    public static func buildBlock<C: View>(_ c: C) -> C { c }
    public static func buildBlock<each C: View>(_ c: repeat each C) -> TupleView<(repeat each C)> { TupleView((repeat each c)) }
    public static func buildIf<C: View>(_ c: C?) -> C? { c }
    public static func buildEither<T: View, F: View>(first: T) -> _ConditionalContent<T, F> { fatalError() }
    public static func buildEither<T: View, F: View>(second: F) -> _ConditionalContent<T, F> { fatalError() }
    public static func buildLimitedAvailability<C: View>(_ c: C) -> AnyView { AnyView(c) }
}

// MARK: data flow
@propertyWrapper public struct State<Value>: DynamicProperty {
    public init(wrappedValue: Value) {}
    public init(initialValue: Value) {}
    public var wrappedValue: Value { get { fatalError() } nonmutating set {} }
    public var projectedValue: Binding<Value> { fatalError() }
}
extension State where Value: ExpressibleByNilLiteral { public init() {} }
@propertyWrapper @dynamicMemberLookup public struct Binding<Value> {
    public init(get: @escaping () -> Value, set: @escaping (Value) -> Void) {}
    public var wrappedValue: Value { get { fatalError() } nonmutating set {} }
    public var projectedValue: Binding<Value> { self }
    public subscript<S>(dynamicMember kp: WritableKeyPath<Value, S>) -> Binding<S> { fatalError() }
}
@propertyWrapper @dynamicMemberLookup public struct Bindable<Value: AnyObject & Observable> {
    public init(wrappedValue: Value) {}
    public init(_ v: Value) {}
    public var wrappedValue: Value { fatalError() }
    public var projectedValue: Bindable<Value> { self }
    public subscript<S>(dynamicMember kp: ReferenceWritableKeyPath<Value, S>) -> Binding<S> { fatalError() }
}
@propertyWrapper public struct FocusState<Value: Hashable>: DynamicProperty {
    public init() where Value == Bool {}
    public var wrappedValue: Value { get { fatalError() } nonmutating set {} }
    public var projectedValue: Binding { fatalError() }
    public struct Binding {}
}
public protocol DynamicProperty {}
public struct EnvironmentValues {
    public var colorScheme: ColorScheme { .light }
    public var horizontalSizeClass: UserInterfaceSizeClass? { nil }
    public var scenePhase: ScenePhase { .active }
    public var dismiss: DismissAction { DismissAction() }
    public var locale: Locale { get { .current } set {} }
    public var dynamicTypeSize: DynamicTypeSize { .large }
    public var verticalSizeClass: UserInterfaceSizeClass? { nil }
    public var openURL: OpenURLAction { OpenURLAction() }
}
public struct OpenURLAction { public func callAsFunction(_ url: URL) {} }
public struct _ToolbarMod<C: ToolbarContent>: ToolbarContent {}
extension ToolbarContent {
    @available(iOS 26.0, macOS 26.0, *)
    public func sharedBackgroundVisibility(_ v: Visibility) -> some ToolbarContent { _ToolbarMod<Self>() }
}
public enum DynamicTypeSize: Comparable, Sendable { case xSmall, small, medium, large, xLarge, xxLarge, xxxLarge, accessibility1 }
public enum VerticalEdge: Sendable { case top, bottom }
public enum ControlSize: Sendable { case mini, small, regular, large }
public struct PresentationBackgroundInteraction: Sendable {
    public static let automatic = PresentationBackgroundInteraction(), disabled = PresentationBackgroundInteraction()
    public static func enabled(upThrough detent: PresentationDetent) -> PresentationBackgroundInteraction { PresentationBackgroundInteraction() }
}
public struct DisclosureGroup<L: View, C: View>: View { public var body: Never { fatalError() } }
extension DisclosureGroup where L == Text { public init<S: StringProtocol>(_ t: S, @ViewBuilder content: @escaping () -> C) {} }
public func withAnimation<R>(_ animation: Animation? = nil, _ body: () throws -> R) rethrows -> R { try body() }
@propertyWrapper public struct Environment<Value>: DynamicProperty {
    public init(_ kp: KeyPath<EnvironmentValues, Value>) {}
    public init(_ type: Value.Type) where Value: AnyObject & Observable {}
    public var wrappedValue: Value { fatalError() }
}
public enum ColorScheme: Sendable, Hashable { case light, dark }
public enum UserInterfaceSizeClass: Sendable, Hashable { case compact, regular }
public enum ScenePhase: Sendable, Hashable { case background, inactive, active }
public struct DismissAction { public func callAsFunction() {} }

// MARK: geometry
public struct Alignment: Sendable { public static let center = Alignment(), top = Alignment(), bottom = Alignment(), leading = Alignment(), topLeading = Alignment(), trailing = Alignment() }
public struct HorizontalAlignment: Sendable { public static let leading = HorizontalAlignment(), center = HorizontalAlignment() }
public struct VerticalAlignment: Sendable { public static let center = VerticalAlignment(), firstTextBaseline = VerticalAlignment(), top = VerticalAlignment() }
public enum Edge: Sendable { public struct Set: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }
    public static let top = Set(rawValue: 1), bottom = Set(rawValue: 2), leading = Set(rawValue: 4), trailing = Set(rawValue: 8)
    public static let horizontal: Set = [.leading, .trailing], vertical: Set = [.top, .bottom], all: Set = [.top, .bottom, .leading, .trailing] } }
public struct SafeAreaRegions: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }; public static let container = SafeAreaRegions(rawValue: 1), all = SafeAreaRegions(rawValue: 3) }
public struct EdgeInsets { public init(top: CGFloat, leading: CGFloat, bottom: CGFloat, trailing: CGFloat) {} }
public struct Axis { public struct Set: OptionSet { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }; public static let horizontal = Set(rawValue: 1), vertical = Set(rawValue: 2) } }
public enum TextAlignment { case leading, center, trailing }
public enum Visibility { case automatic, visible, hidden }

// MARK: style
public protocol ShapeStyle {}
public struct Color: View, ShapeStyle, Hashable, Sendable {
    public enum RGBColorSpace { case sRGB }
    public init(uiColor: UIColor) {}
    public init(_ space: RGBColorSpace, red: Double, green: Double, blue: Double, opacity: Double = 1) {}
    public static let clear = Color(.sRGB, red: 0, green: 0, blue: 0)
    public func opacity(_ o: Double) -> Color { self }
    public var body: Never { fatalError() }
}
public struct Font: Hashable, Sendable {
    public enum TextStyle: Sendable { case largeTitle, title, title2, title3, headline, subheadline, body, callout, footnote, caption, caption2 }
    public struct Weight: Hashable, Sendable { public static let regular = Weight(), medium = Weight(), semibold = Weight(), bold = Weight() }
    public static func custom(_ name: String, size: CGFloat, relativeTo: TextStyle) -> Font { Font() }
    public static func custom(_ name: String, size: CGFloat) -> Font { Font() }
    public enum Design: Sendable { case `default`, monospaced, rounded, serif }
    public static func system(_ style: TextStyle, design: Design? = nil, weight: Weight? = nil) -> Font { Font() }
}
public struct Animation { public static func easeOut(duration: Double) -> Animation { Animation() } }
public struct AnyTransition { public static let opacity = AnyTransition() }
public enum ContentMode { case fit, fill }
public enum TextCase { case uppercase, lowercase }
public struct AccessibilityTraits: OptionSet, Sendable { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }
    public static let isHeader = AccessibilityTraits(rawValue: 1), updatesFrequently = AccessibilityTraits(rawValue: 2), isSelected = AccessibilityTraits(rawValue: 4) }
public struct AccessibilityChildBehavior { public static let combine = AccessibilityChildBehavior(), ignore = AccessibilityChildBehavior(), contain = AccessibilityChildBehavior() }
public struct SubmitLabel { public static let search = SubmitLabel() }
public struct TextInputAutocapitalization { public static let never = TextInputAutocapitalization() }
public struct ScrollDismissesKeyboardMode { public static let immediately = ScrollDismissesKeyboardMode() }
public struct TextSelectability { public static let enabled = TextSelectability() }
public struct PresentationDetent: Hashable { public static let medium = PresentationDetent(), large = PresentationDetent(); public static func height(_ h: CGFloat) -> PresentationDetent { PresentationDetent() } }
public struct ButtonRole { public static let cancel = ButtonRole(), destructive = ButtonRole() }

// MARK: shapes
public struct Path { public init() {}; public mutating func move(to p: CGPoint) {}; public mutating func addLine(to p: CGPoint) {}; public mutating func closeSubpath() {} }
public protocol Shape: View, Sendable { func path(in rect: CGRect) -> Path }
extension Shape { public var body: Never { fatalError() }
    public func fill<S: ShapeStyle>(_ s: S) -> some View { _Mod<Self>() } }
public protocol InsettableShape: Shape {}
extension InsettableShape { public func strokeBorder<S: ShapeStyle>(_ s: S, lineWidth: CGFloat = 1) -> some View { _Mod<Self>() } }
public enum RoundedCornerStyle: Sendable { case circular, continuous }
public struct RoundedRectangle: InsettableShape { public init(cornerRadius: CGFloat, style: RoundedCornerStyle = .continuous) {}; public func path(in rect: CGRect) -> Path { Path() } }
public struct Circle: InsettableShape { public init() {}; public func path(in rect: CGRect) -> Path { Path() } }
public struct Capsule: InsettableShape { public init() {}; public func path(in rect: CGRect) -> Path { Path() } }
public struct Rectangle: InsettableShape { public init() {}; public func path(in rect: CGRect) -> Path { Path() } }

// MARK: views
public struct Text: View, Equatable {
    public init<S: StringProtocol>(_ s: S) {}
    public init(verbatim: String) {}
    public func monospacedDigit() -> Text { self }
    public func font(_ f: Font?) -> Text { self }
    public func foregroundColor(_ c: Color?) -> Text { self }
    public static func + (lhs: Text, rhs: Text) -> Text { lhs }
    public var body: Never { fatalError() }
}
public struct Image: View {
    public init(_ name: String) {}
    public init(systemName: String) {}
    public enum TemplateRenderingMode { case template, original }
    public func renderingMode(_ m: TemplateRenderingMode?) -> Image { self }
    public func resizable() -> Image { self }
    public var body: Never { fatalError() }
}
public struct Label<T: View, I: View>: View {
    public init(@ViewBuilder title: () -> T, @ViewBuilder icon: () -> I) {}
    public var body: Never { fatalError() }
}
extension Label where T == Text, I == Image { public init<S: StringProtocol>(_ t: S, systemImage: String) {}; public init<S: StringProtocol>(_ t: S, image: String) {} }
public struct Slider<L: View, V: View>: View { public var body: Never { fatalError() } }
extension Slider where L == EmptyView, V == EmptyView { public init<F: BinaryFloatingPoint>(value: Binding<F>, in bounds: ClosedRange<F>, step: F.Stride = 1) where F.Stride: BinaryFloatingPoint {} }
public struct Button<L: View>: View {
    public init(action: @escaping @MainActor () -> Void, @ViewBuilder label: () -> L) {}
    public init(role: ButtonRole?, action: @escaping @MainActor () -> Void, @ViewBuilder label: () -> L) {}
    public var body: Never { fatalError() }
}
extension Button where L == Text {
    public init<S: StringProtocol>(_ t: S, action: @escaping @MainActor () -> Void) {}
    public init<S: StringProtocol>(_ t: S, role: ButtonRole?, action: @escaping @MainActor () -> Void) {}
}
public struct Link<L: View>: View { public init(destination: URL, @ViewBuilder label: () -> L) {}; public var body: Never { fatalError() } }
public struct TextField<L: View>: View { public var body: Never { fatalError() } }
extension TextField where L == Text { public init<S: StringProtocol>(_ t: S, text: Binding<String>) {} }
public struct Toggle<L: View>: View { public var body: Never { fatalError() } }
extension Toggle where L == Text { public init<S: StringProtocol>(_ t: S, isOn: Binding<Bool>) {} }
public struct Picker<L: View, Sel: Hashable, C: View>: View { public var body: Never { fatalError() } }
extension Picker where L == Text { public init<S: StringProtocol>(_ t: S, selection: Binding<Sel>, @ViewBuilder content: () -> C) {} }
public struct Menu<L: View, C: View>: View { public init(@ViewBuilder content: () -> C, @ViewBuilder label: () -> L) {}; public var body: Never { fatalError() } }
public struct ProgressView<L: View, C: View>: View { public var body: Never { fatalError() } }
extension ProgressView where L == EmptyView, C == EmptyView { public init() {}; public init<V: BinaryFloatingPoint>(value: V?) {} }
public struct LabeledContent<L: View, C: View>: View { public init(@ViewBuilder content: () -> C, @ViewBuilder label: () -> L) {}; public var body: Never { fatalError() } }
extension LabeledContent where L == Text, C == Text { public init<S1: StringProtocol, S2: StringProtocol>(_ t: S1, value: S2) {} }
public struct Spacer: View { public init(minLength: CGFloat? = nil) {}; public var body: Never { fatalError() } }
public struct Divider: View { public init() {}; public var body: Never { fatalError() } }
public struct VStack<C: View>: View { public init(alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct HStack<C: View>: View { public init(alignment: VerticalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct ZStack<C: View>: View { public init(alignment: Alignment = .center, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct Group<C: View>: View { public init(@ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct ScrollView<C: View>: View { public init(_ axes: Axis.Set = .vertical, showsIndicators: Bool = true, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct GridItem { public enum Size { case adaptive(minimum: CGFloat, maximum: CGFloat = .infinity), flexible(minimum: CGFloat = 10, maximum: CGFloat = .infinity) }
    public init(_ size: Size = .flexible(), spacing: CGFloat? = nil, alignment: Alignment? = nil) {} }
public struct LazyVGrid<C: View>: View { public init(columns: [GridItem], alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct List<Sel: Hashable, C: View>: View { public var body: Never { fatalError() } }
extension List where Sel == Never { public init(@ViewBuilder content: () -> C) {}
    public init<D: RandomAccessCollection, R: View>(_ data: D, @ViewBuilder rowContent: @escaping (D.Element) -> R) where C == ForEach<D, D.Element.ID, R>, D.Element: Identifiable {} }
public struct Form<C: View>: View { public init(@ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }
public struct Section<P: View, C: View, F: View>: View { public var body: Never { fatalError() } }
extension Section where P == EmptyView, F == EmptyView { public init(@ViewBuilder content: () -> C) {} }
extension Section where P == Text, F == EmptyView { public init<S: StringProtocol>(_ t: S, @ViewBuilder content: () -> C) {} }
extension Section where F == EmptyView { public init(@ViewBuilder content: () -> C, @ViewBuilder header: () -> P) {} }
extension Section { public init(@ViewBuilder content: () -> C, @ViewBuilder header: () -> P, @ViewBuilder footer: () -> F) {} }
extension Section where P == EmptyView { public init(@ViewBuilder content: () -> C, @ViewBuilder footer: () -> F) {} }
public struct ForEach<D: RandomAccessCollection, ID: Hashable, C: View>: View { public var body: Never { fatalError() } }
extension ForEach { public init(_ data: D, id: KeyPath<D.Element, ID>, @ViewBuilder content: @escaping (D.Element) -> C) {} }
extension ForEach where D.Element: Identifiable, ID == D.Element.ID { public init(_ data: D, @ViewBuilder content: @escaping (D.Element) -> C) {} }
public struct NavigationStack<Data, Root: View>: View { public var body: Never { fatalError() } }
extension NavigationStack { public init(path: Binding<Data>, @ViewBuilder root: () -> Root) where Data: MutableCollection & RandomAccessCollection & RangeReplaceableCollection, Data.Element: Hashable {} }
extension NavigationStack where Data == [Never] { public init(@ViewBuilder root: () -> Root) {} }
public struct NavigationLink<L: View, D: View>: View { public var body: Never { fatalError() } }
extension NavigationLink where D == Never { public init<P: Hashable>(value: P?, @ViewBuilder label: () -> L) {} }
extension NavigationLink { public init(@ViewBuilder destination: () -> D, @ViewBuilder label: () -> L) {} }
public struct TabView<Sel: Hashable, C: View>: View { public init(selection: Binding<Sel>?, @ViewBuilder content: () -> C) {}; public var body: Never { fatalError() } }

// MARK: styles
public struct ButtonStyleConfiguration { public struct Label: View { public var body: Never { fatalError() } }; public let label: Label; public let isPressed: Bool }
public protocol ButtonStyle { associatedtype Body: View; @ViewBuilder func makeBody(configuration: Configuration) -> Body; typealias Configuration = ButtonStyleConfiguration }
public protocol PrimitiveButtonStyle {}
public struct PlainButtonStyle: PrimitiveButtonStyle { public init() {} }
public struct BorderedButtonStyle: PrimitiveButtonStyle { public init() {} }
extension PrimitiveButtonStyle where Self == PlainButtonStyle { public static var plain: PlainButtonStyle { .init() } }
extension PrimitiveButtonStyle where Self == BorderedButtonStyle { public static var bordered: BorderedButtonStyle { .init() } }
public protocol ListStyle {}
public struct InsetGroupedListStyle: ListStyle { public init() {} }
public struct PlainListStyle: ListStyle { public init() {} }
extension ListStyle where Self == InsetGroupedListStyle { public static var insetGrouped: InsetGroupedListStyle { .init() } }
extension ListStyle where Self == PlainListStyle { public static var plain: PlainListStyle { .init() } }
public protocol PickerStyle {}
public struct SegmentedPickerStyle: PickerStyle { public init() {} }
extension PickerStyle where Self == SegmentedPickerStyle { public static var segmented: SegmentedPickerStyle { .init() } }
public protocol ProgressViewStyle {}
public struct LinearProgressViewStyle: ProgressViewStyle { public init() {} }
extension ProgressViewStyle where Self == LinearProgressViewStyle { public static var linear: LinearProgressViewStyle { .init() } }
public protocol ViewModifier { associatedtype Body: View; typealias Content = _ViewModifier_Content<Self>; @ViewBuilder @MainActor func body(content: Content) -> Body }
public struct _ViewModifier_Content<M>: View { public var body: Never { fatalError() } }

// MARK: toolbar
public protocol ToolbarContent {}
public struct ToolbarItemPlacement { public static let topBarLeading = ToolbarItemPlacement(), topBarTrailing = ToolbarItemPlacement(); public static let principal = ToolbarItemPlacement(), bottomBar = ToolbarItemPlacement(), confirmationAction = ToolbarItemPlacement(), primaryAction = ToolbarItemPlacement() }
public struct ToolbarPlacement { public static let navigationBar = ToolbarPlacement(), bottomBar = ToolbarPlacement(), tabBar = ToolbarPlacement() }
public struct ToolbarItem<C: View>: ToolbarContent { public init(placement: ToolbarItemPlacement = .principal, @ViewBuilder content: () -> C) {} }
public struct ToolbarItemGroup<C: View>: ToolbarContent { public init(placement: ToolbarItemPlacement, @ViewBuilder content: () -> C) {} }
public struct _TC: ToolbarContent {}
@resultBuilder public struct ToolbarContentBuilder {
    public static func buildExpression<C: ToolbarContent>(_ c: C) -> C { c }
    public static func buildBlock<each C: ToolbarContent>(_ c: repeat each C) -> _TC { _TC() }
    public static func buildIf<C: ToolbarContent>(_ c: C?) -> _TC { _TC() }
    public static func buildEither<C: ToolbarContent>(first: C) -> _TC { _TC() }
    public static func buildEither<C: ToolbarContent>(second: C) -> _TC { _TC() }
}

// MARK: modifiers
extension View {
    public func padding(_ edges: Edge.Set = .all, _ length: CGFloat? = nil) -> some View { _Mod<Self>() }
    public func padding(_ length: CGFloat) -> some View { _Mod<Self>() }
    public func padding(_ insets: EdgeInsets) -> some View { _Mod<Self>() }
    public func frame(width: CGFloat? = nil, height: CGFloat? = nil, alignment: Alignment = .center) -> some View { _Mod<Self>() }
    public func frame(minWidth: CGFloat? = nil, idealWidth: CGFloat? = nil, maxWidth: CGFloat? = nil, minHeight: CGFloat? = nil, idealHeight: CGFloat? = nil, maxHeight: CGFloat? = nil, alignment: Alignment = .center) -> some View { _Mod<Self>() }
    public func background<V: View>(_ v: V, alignment: Alignment = .center) -> some View { _Mod<Self>() }
    public func overlay<V: View>(_ v: V, alignment: Alignment = .center) -> some View { _Mod<Self>() }
    public func overlay<V: View>(alignment: Alignment = .center, @ViewBuilder content: () -> V) -> some View { _Mod<Self>() }
    public func foregroundStyle<S: ShapeStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func font(_ f: Font?) -> some View { _Mod<Self>() }
    public func fontWeight(_ w: Font.Weight?) -> some View { _Mod<Self>() }
    public func tracking(_ t: CGFloat) -> some View { _Mod<Self>() }
    public func lineSpacing(_ s: CGFloat) -> some View { _Mod<Self>() }
    public func lineLimit(_ n: Int?) -> some View { _Mod<Self>() }
    public func multilineTextAlignment(_ a: TextAlignment) -> some View { _Mod<Self>() }
    public func fixedSize(horizontal: Bool, vertical: Bool) -> some View { _Mod<Self>() }
    public func fixedSize() -> some View { _Mod<Self>() }
    public func textCase(_ c: TextCase?) -> some View { _Mod<Self>() }
    public func monospacedDigit() -> some View { _Mod<Self>() }
    public func scaledToFit() -> some View { _Mod<Self>() }
    public func opacity(_ o: Double) -> some View { _Mod<Self>() }
    public func offset(x: CGFloat = 0, y: CGFloat = 0) -> some View { _Mod<Self>() }
    public func shadow(color: Color = .clear, radius: CGFloat, x: CGFloat = 0, y: CGFloat = 0) -> some View { _Mod<Self>() }
    public func compositingGroup() -> some View { _Mod<Self>() }
    public func tint(_ c: Color?) -> some View { _Mod<Self>() }
    public func disabled(_ d: Bool) -> some View { _Mod<Self>() }
    public func contentShape<S: Shape>(_ s: S) -> some View { _Mod<Self>() }
    public func ignoresSafeArea(_ r: SafeAreaRegions = .all, edges: Edge.Set = .all) -> some View { _Mod<Self>() }
    public func id<ID: Hashable>(_ id: ID) -> some View { _Mod<Self>() }
    public func tag<V: Hashable>(_ v: V) -> some View { _Mod<Self>() }
    public func transition(_ t: AnyTransition) -> some View { _Mod<Self>() }
    public func animation<V: Equatable>(_ a: Animation?, value: V) -> some View { _Mod<Self>() }
    public func allowsHitTesting(_ e: Bool) -> some View { _Mod<Self>() }
    public func modifier<M: ViewModifier>(_ m: M) -> some View { _Mod<Self>() }
    public func buttonStyle<S: ButtonStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func buttonStyle<S: PrimitiveButtonStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func listStyle<S: ListStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func pickerStyle<S: PickerStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func progressViewStyle<S: ProgressViewStyle>(_ s: S) -> some View { _Mod<Self>() }
    public func listRowBackground<V: View>(_ v: V?) -> some View { _Mod<Self>() }
    public func listRowInsets(_ i: EdgeInsets?) -> some View { _Mod<Self>() }
    public func listRowSeparatorTint(_ c: Color?) -> some View { _Mod<Self>() }
    public func scrollContentBackground(_ v: Visibility) -> some View { _Mod<Self>() }
    public func scrollDismissesKeyboard(_ m: ScrollDismissesKeyboardMode) -> some View { _Mod<Self>() }
    public func navigationTitle<S: StringProtocol>(_ t: S) -> some View { _Mod<Self>() }
    public func navigationBarTitleDisplayMode(_ m: NavigationBarItem.TitleDisplayMode) -> some View { _Mod<Self>() }
    public func navigationDestination<D: Hashable, C: View>(for: D.Type, @ViewBuilder destination: @escaping (D) -> C) -> some View { _Mod<Self>() }
    public func toolbar<C: ToolbarContent>(@ToolbarContentBuilder content: () -> C) -> some View { _Mod<Self>() }
    public func toolbar(_ v: Visibility, for bars: ToolbarPlacement...) -> some View { _Mod<Self>() }
    public func toolbarBackground<S: ShapeStyle>(_ s: S, for bars: ToolbarPlacement...) -> some View { _Mod<Self>() }
    public func toolbarBackground(_ v: Visibility, for bars: ToolbarPlacement...) -> some View { _Mod<Self>() }
    public func toolbarColorScheme(_ c: ColorScheme?, for bars: ToolbarPlacement...) -> some View { _Mod<Self>() }
    public func tabItem<V: View>(@ViewBuilder _ label: () -> V) -> some View { _Mod<Self>() }
    public func sheet<C: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C) -> some View { _Mod<Self>() }
    public func presentationDetents(_ d: Set<PresentationDetent>) -> some View { _Mod<Self>() }
    public func presentationDragIndicator(_ v: Visibility) -> some View { _Mod<Self>() }
    public func presentationBackgroundInteraction(_ i: PresentationBackgroundInteraction) -> some View { _Mod<Self>() }
    public func controlSize(_ s: ControlSize) -> some View { _Mod<Self>() }
    public func sheet<I: Identifiable, C: View>(item: Binding<I?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (I) -> C) -> some View { _Mod<Self>() }
    public func navigationBarBackButtonHidden(_ h: Bool = true) -> some View { _Mod<Self>() }
    public func fullScreenCover<C: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C) -> some View { _Mod<Self>() }
    public func onReceive<P: Publisher>(_ publisher: P, perform: @escaping (P.Output) -> Void) -> some View where P.Failure == Never { _Mod<Self>() }
    public func safeAreaInset<V: View>(edge: VerticalEdge, alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> V) -> some View { _Mod<Self>() }
    public func minimumScaleFactor(_ f: CGFloat) -> some View { _Mod<Self>() }
    public func listRowSeparator(_ v: Visibility) -> some View { _Mod<Self>() }
    public func task(priority: TaskPriority = .userInitiated, _ action: @escaping @Sendable () async -> Void) -> some View { _Mod<Self>() }
    public func alert<S: StringProtocol, A: View, M: View, T>(_ t: S, isPresented: Binding<Bool>, presenting: T?, @ViewBuilder actions: (T) -> A, @ViewBuilder message: (T) -> M) -> some View { _Mod<Self>() }
    public func confirmationDialog<S: StringProtocol, A: View, M: View>(_ t: S, isPresented: Binding<Bool>, titleVisibility: Visibility = .automatic, @ViewBuilder actions: () -> A, @ViewBuilder message: () -> M) -> some View { _Mod<Self>() }
    public func confirmationDialog<S: StringProtocol, A: View>(_ t: S, isPresented: Binding<Bool>, titleVisibility: Visibility = .automatic, @ViewBuilder actions: () -> A) -> some View { _Mod<Self>() }
    public func fileImporter(isPresented: Binding<Bool>, allowedContentTypes: [UTType], onCompletion: @escaping (Result<URL, any Error>) -> Void) -> some View { _Mod<Self>() }
    public func contextMenu<M: View>(@ViewBuilder menuItems: () -> M) -> some View { _Mod<Self>() }
    public func swipeActions<C: View>(edge: HorizontalEdge = .trailing, allowsFullSwipe: Bool = true, @ViewBuilder content: () -> C) -> some View { _Mod<Self>() }
    public func textSelection(_ s: TextSelectability) -> some View { _Mod<Self>() }
    public func onAppear(perform: (() -> Void)? = nil) -> some View { _Mod<Self>() }
    public func onChange<V: Equatable>(of v: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> some View { _Mod<Self>() }
    public func onSubmit(of: SubmitTriggers = .text, _ action: @escaping () -> Void) -> some View { _Mod<Self>() }
    public func submitLabel(_ l: SubmitLabel) -> some View { _Mod<Self>() }
    public func autocorrectionDisabled(_ d: Bool = true) -> some View { _Mod<Self>() }
    public func textInputAutocapitalization(_ a: TextInputAutocapitalization?) -> some View { _Mod<Self>() }
    public func focused(_ b: FocusState<Bool>.Binding) -> some View { _Mod<Self>() }
    public func environment<T: AnyObject & Observable>(_ o: T?) -> some View { _Mod<Self>() }
    public func environment<V>(_ kp: WritableKeyPath<EnvironmentValues, V>, _ v: V) -> some View { _Mod<Self>() }
    public func accessibilityElement(children: AccessibilityChildBehavior = .ignore) -> some View { _Mod<Self>() }
    public func accessibilityLabel<S: StringProtocol>(_ s: S) -> some View { _Mod<Self>() }
    public func accessibilityHint<S: StringProtocol>(_ s: S) -> some View { _Mod<Self>() }
    public func accessibilityValue<S: StringProtocol>(_ s: S) -> some View { _Mod<Self>() }
    public func accessibilityIdentifier(_ s: String) -> some View { _Mod<Self>() }
    public func accessibilityAddTraits(_ t: AccessibilityTraits) -> some View { _Mod<Self>() }
    public func accessibilityHidden(_ h: Bool) -> some View { _Mod<Self>() }
}
public enum HorizontalEdge { case leading, trailing }
public struct SubmitTriggers: OptionSet { public let rawValue: Int; public init(rawValue: Int) { self.rawValue = rawValue }; public static let text = SubmitTriggers(rawValue: 1) }
public enum NavigationBarItem { public enum TitleDisplayMode { case automatic, inline, large } }

// MARK: app
public protocol UIViewRepresentable: View where Body == Never {
    associatedtype UIViewType: UIView
    @MainActor func makeUIView(context: Context) -> UIViewType
    @MainActor func updateUIView(_ v: UIViewType, context: Context)
    typealias Context = UIViewRepresentableContext<Self>
}
extension UIViewRepresentable { public var body: Never { fatalError() } }
public struct UIViewRepresentableContext<R> {}
public protocol Scene {}
public struct WindowGroup<C: View>: Scene { public init(@ViewBuilder content: () -> C) {} }
@resultBuilder public struct SceneBuilder { public static func buildBlock<S: Scene>(_ s: S) -> S { s } }
@MainActor public protocol App { associatedtype Body: Scene; @SceneBuilder var body: Body { get }; init() }
extension App { public static func main() {} }
