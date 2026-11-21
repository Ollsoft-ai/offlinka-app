import SwiftUI

extension AppModel {
    /// A binding to one setting that saves through `updateSettings` (a language change re-localizes the UI).
    func binding<Value>(for keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(
            get: { self.store.settings[keyPath: keyPath] },
            set: { newValue in self.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }
}

/// Text size slider over the reader's steps (`A ——o—— A`, value in %), as in the mockups.
@MainActor
struct TextScaleControl: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let scales = AppSettings.textScales
        let current = app.store.settings.steppedTextScale(0)
        let index = Binding<Double>(
            get: { Double(scales.firstIndex(of: current) ?? 2) },
            set: { newValue in
                let step = min(max(Int(newValue.rounded()), 0), scales.count - 1)
                app.updateSettings { $0.textScale = scales[step] }
            }
        )
        let value = tr(.settingsTextSizeValue, Int((current * 100).rounded()))
        VStack(spacing: BrandSpace.s) {
            HStack(spacing: BrandSpace.l) {
                Text(verbatim: "A")
                    .font(.custom("ArchivoExpanded-ExtraBold", size: 15))
                    .foregroundStyle(Color.brandInk)
                    .accessibilityHidden(true)
                VStack(spacing: 2) {
                    Slider(value: index, in: 0...Double(scales.count - 1), step: 1)
                        .tint(Color.brandAccent)
                        .accessibilityLabel(tr(.settingsTextSize))
                        .accessibilityValue(value)
                    // Tick marks in ink3: the steps are visible (UX review).
                    HStack {
                        ForEach(0..<scales.count, id: \.self) { step in
                            Capsule()
                                .fill(Color.brandInk3)
                                .frame(width: 2, height: 8)
                            if step < scales.count - 1 {
                                Spacer(minLength: 0)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .accessibilityHidden(true)
                }
                Text(verbatim: "A")
                    .font(.custom("ArchivoExpanded-ExtraBold", size: 28))
                    .foregroundStyle(Color.brandInk)
                    .accessibilityHidden(true)
            }
            Text(value)
                .brandText(.label)
                .foregroundStyle(Color.brandInk2)
                .monospacedDigit()
                .accessibilityHidden(true)
        }
    }
}

/// System / light / dark: a segmented control, or a vertical list at large text sizes, where the
/// segments would clip ("Podle sy…", UX review).
@MainActor
struct ThemePicker: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private let options: [(AppTheme, Str)] = [(.system, .settingsThemeSystem), (.light, .settingsThemeLight), (.dark, .settingsThemeDark)]

    var body: some View {
        if dynamicTypeSize > .xLarge {
            VStack(spacing: 0) {
                ForEach(options.indices, id: \.self) { index in
                    let option = options[index]
                    Button {
                        app.updateSettings { $0.theme = option.0 }
                    } label: {
                        HStack {
                            Text(tr(option.1))
                                .brandText(.body)
                                .foregroundStyle(Color.brandInk)
                            Spacer()
                            if app.store.settings.theme == option.0 {
                                BrandIconView(icon: .check, size: BrandSize.iconSmall)
                                    .foregroundStyle(Color.brandAccent)
                            }
                        }
                        .frame(minHeight: BrandSize.touch)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(app.store.settings.theme == option.0 ? .isSelected : [])
                }
            }
        } else {
            Picker(tr(.settingsTheme), selection: app.binding(for: \.theme)) {
                ForEach(options.indices, id: \.self) { index in
                    Text(tr(options[index].1)).tag(options[index].0)
                }
            }
            .pickerStyle(.segmented)
        }
    }
}
