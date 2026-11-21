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
                Slider(value: index, in: 0...Double(scales.count - 1), step: 1)
                    .tint(Color.brandAccent)
                    .accessibilityLabel(tr(.settingsTextSize))
                    .accessibilityValue(value)
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

/// System / light / dark as a segmented control.
@MainActor
struct ThemePicker: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Picker(tr(.settingsTheme), selection: app.binding(for: \.theme)) {
            Text(tr(.settingsThemeSystem)).tag(AppTheme.system)
            Text(tr(.settingsThemeLight)).tag(AppTheme.light)
            Text(tr(.settingsThemeDark)).tag(AppTheme.dark)
        }
        .pickerStyle(.segmented)
    }
}
