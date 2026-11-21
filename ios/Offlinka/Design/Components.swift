import SwiftUI
import UIKit

/// A brand icon from the asset catalog, or its SF Symbol fallback.
struct BrandIconView: View {
    let icon: BrandIcon
    var size: CGFloat = BrandSize.icon

    var body: some View {
        Group {
            if UIImage(named: icon.assetName) != nil {
                Image(icon.assetName)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: icon.systemName)
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.medium)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The coloured square with a book's icon (tone by book family).
struct BookIconBox: View {
    let kind: BookKind
    var box: CGFloat = BrandSize.bookIconBox

    var body: some View {
        let tone = (BookFamily(rawValue: kind.familyName) ?? .wiki).tone
        RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
            .fill(tone.background)
            .overlay(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline)
            )
            .overlay(BrandIconView(icon: kind.icon, size: box * 0.58).foregroundStyle(tone.foreground))
            .frame(width: box, height: box)
            .accessibilityHidden(true)
    }
}

/// Paper card with an ink outline and the brand's hard offset shadow.
struct CardModifier: ViewModifier {
    var shadow = true

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous)
                    .fill(Color.brandSurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous)
                    .strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline)
            )
            .hardShadow(shadow ? BrandElevation.card : BrandShadow(x: 0, y: 0, color: .clear))
    }
}

extension View {
    func brandCard(shadow: Bool = true) -> some View {
        modifier(CardModifier(shadow: shadow))
    }
}

/// Orange primary button with the hard bottom edge (pressed: the button sinks onto it).
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .brandText(.button)
            .foregroundStyle(Color.brandOnPrimary)
            .frame(maxWidth: .infinity, minHeight: BrandSize.button)
            .padding(.horizontal, BrandSpace.l)
            .background(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .fill(Color.brandPrimary)
            )
            .shadow(color: .brandPrimaryEdge, radius: 0, x: 0, y: configuration.isPressed ? 2 : 4)
            .offset(y: configuration.isPressed ? 2 : 0)
            .contentShape(Rectangle())
    }
}

/// Outlined secondary button on paper.
struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .brandText(.label)
            .foregroundStyle(Color.brandInk)
            .frame(maxWidth: .infinity, minHeight: BrandSize.buttonSmall + 4)
            .padding(.horizontal, BrandSpace.l)
            .background(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .fill(configuration.isPressed ? Color.brandSurfaceMuted : Color.brandSurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline)
            )
            .contentShape(Rectangle())
    }
}

/// Quick-link chip on the forest header: 40 high with a 48 hit area, `heroDeep` fill at 35 % and an
/// `onHero2` outline at 50 % (3.5:1, UX review).
struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .brandText(.label)
            .foregroundStyle(Color.brandOnHero)
            .padding(.horizontal, BrandSpace.m)
            .frame(minHeight: BrandSize.buttonSmall)
            .background(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .fill(Color.brandHeroDeep.opacity(configuration.isPressed ? 0.6 : 0.35))
            )
            .overlay(
                RoundedRectangle(cornerRadius: BrandRadius.s, style: .continuous)
                    .strokeBorder(Color.brandOnHero2.opacity(0.5), lineWidth: 1)
            )
            .padding(.vertical, (BrandSize.touch - BrandSize.buttonSmall) / 2)
            .contentShape(Rectangle())
    }
}

/// The ridge where the forest header meets the paper (as on the drive's start page).
struct RidgeShape: Shape {
    /// 0 = back ridge (lighter green), 1 = front ridge (paper).
    var layer: Int

    func path(in rect: CGRect) -> Path {
        // Points from the start page's SVG (viewBox 1440 x 120), scaled to the rect.
        let back: [(CGFloat, CGFloat)] = [(0, 70), (120, 30), (210, 60), (340, 8), (450, 52), (600, 22), (740, 68), (900, 10),
                                          (1030, 50), (1180, 14), (1300, 58), (1440, 28)]
        let front: [(CGFloat, CGFloat)] = [(0, 96), (150, 62), (260, 88), (430, 44), (580, 84), (710, 62), (880, 98), (1060, 48),
                                           (1200, 82), (1330, 58), (1440, 88)]
        let points = layer == 0 ? back : front
        var path = Path()
        let sx = rect.width / 1440
        let sy = rect.height / 120
        path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        for (x, y) in points {
            path.addLine(to: CGPoint(x: rect.minX + x * sx, y: rect.minY + y * sy))
        }
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// Forest header background with the ridge at the bottom.
struct HeroBackground: View {
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.brandHero
            RidgeShape(layer: 0).fill(Color.brandHeroRidge).frame(height: BrandSize.ridge * 1.6)
            RidgeShape(layer: 1).fill(Color.brandBg).frame(height: BrandSize.ridge * 1.6)
        }
    }
}

/// The „offlinka“ wordmark in type (until the logo is an asset): orange peak, paper or ink text.
struct Wordmark: View {
    var onDark = true
    var size: CGFloat = 20

    var body: some View {
        HStack(spacing: BrandSpace.xs) {
            Triangle()
                .fill(Color.brandPrimary)
                .frame(width: size * 0.6, height: size * 0.5)
            Text(verbatim: "offlinka")
                .font(.custom("ArchivoExpanded-ExtraBold", size: size, relativeTo: .headline))
                .foregroundStyle(onDark ? Color.brandOnHero : Color.brandInk)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr(.cdLogo))
    }
}

struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// Section title in the brand style: the orange peak, expanded ink title, optional count on the right.
struct SectionTitle: View {
    let text: String
    var trailing: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: BrandSpace.s) {
            Triangle()
                .fill(Color.brandPrimary)
                .frame(width: 16, height: 14)
                .accessibilityHidden(true)
            Text(text)
                .brandText(.headline)
                .foregroundStyle(Color.brandInk)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: BrandSpace.s)
            if let trailing {
                Text(trailing)
                    .brandText(.labelSmall)
                    .foregroundStyle(Color.brandInk2)
            }
        }
    }
}

/// Small outlined tag (`CZ`, `SK`, `EN` on the Wikipedia tiles).
struct TagView: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .brandText(.labelSmall)
            .foregroundStyle(Color.brandInk2)
            .padding(.horizontal, BrandSpace.s)
            .padding(.vertical, BrandSpace.xxs)
            .background(RoundedRectangle(cornerRadius: BrandRadius.xs).fill(Color.brandBg))
            .overlay(RoundedRectangle(cornerRadius: BrandRadius.xs).strokeBorder(Color.brandLine, lineWidth: 1))
    }
}

/// A calm empty state: icon, title, one line of help.
struct EmptyStateView: View {
    let icon: BrandIcon
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: BrandSpace.m) {
            BrandIconView(icon: icon, size: 36)
                .foregroundStyle(Color.brandInk3)
            Text(title)
                .brandText(.subhead)
                .foregroundStyle(Color.brandInk)
                .multilineTextAlignment(.center)
            Text(message)
                .brandText(.bodySmall)
                .foregroundStyle(Color.brandInk2)
                .multilineTextAlignment(.center)
        }
        .padding(BrandSpace.xxxl)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// A calm note in a box (search tip, index progress), with an optional trailing control.
struct InfoNote<Trailing: View>: View {
    let text: String
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .center, spacing: BrandSpace.s) {
            BrandIconView(icon: .info, size: BrandSize.iconSmall)
                .foregroundStyle(Color.brandInk2)
            Text(text)
                .brandText(.bodySmall)
                .foregroundStyle(Color.brandInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            trailing()
        }
        .padding(.leading, BrandSpace.m)
        .padding(.vertical, BrandSpace.s)
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandAccentSoft))
        .accessibilityElement(children: .contain)
    }
}

/// Short message at the bottom of the screen, with an optional action (design: toast spec).
struct ToastView: View {
    let toast: Toast
    let onAction: () -> Void

    var body: some View {
        HStack(spacing: BrandSpace.l) {
            Text(toast.message)
                .brandText(.label)
                .foregroundStyle(Color.brandOnHero)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle = toast.actionTitle {
                Button(actionTitle, action: onAction)
                    .brandText(.subhead)
                    .foregroundStyle(Color(hex: 0xFFB98C))
                    .frame(minHeight: BrandSize.touch)
            }
        }
        .padding(.horizontal, BrandSpace.l)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandHeroDeep))
        .hardShadow(BrandElevation.raised)
        .padding(.horizontal, BrandSize.gutter)
        .padding(.bottom, 104)
        .accessibilityElement(children: .contain)
    }
}

/// Sets the app-wide light/dark override on every window (also reaches sheets and web views).
/// More reliable than `.preferredColorScheme(nil)`, which does not always switch back to "system".
enum ThemeApplier {
    @MainActor
    static func apply(_ theme: AppTheme) {
        let style: UIUserInterfaceStyle
        switch theme {
        case .system: style = .unspecified
        case .light: style = .light
        case .dark: style = .dark
        }
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.overrideUserInterfaceStyle = style
            }
        }
    }
}
