import SwiftUI

/// Language, light/dark, text size, the drive, history and searches.
@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Form {
            Section(tr(.settingsSectionDisplay)) {
                Picker(tr(.settingsLanguage), selection: setting(\.language)) {
                    Text(tr(.settingsLanguageSystem)).tag(AppLanguage.system)
                    Text(tr(.settingsLanguageCs)).tag(AppLanguage.cs)
                    Text(tr(.settingsLanguageSk)).tag(AppLanguage.sk)
                    Text(tr(.settingsLanguageEn)).tag(AppLanguage.en)
                }
                .accessibilityIdentifier("settings.language")

                Picker(tr(.settingsTheme), selection: setting(\.theme)) {
                    Text(tr(.settingsThemeSystem)).tag(AppTheme.system)
                    Text(tr(.settingsThemeLight)).tag(AppTheme.light)
                    Text(tr(.settingsThemeDark)).tag(AppTheme.dark)
                }

                VStack(alignment: .leading, spacing: BrandSpace.s) {
                    HStack {
                        Text(tr(.settingsTextSize))
                        Spacer()
                        Text(tr(.settingsTextSizeValue, Int((app.store.settings.textScale * 100).rounded())))
                            .foregroundStyle(Color.brandInk2)
                            .monospacedDigit()
                    }
                    HStack(spacing: BrandSpace.m) {
                        Button {
                            app.updateSettings { $0.textScale = $0.steppedTextScale(-1) }
                        } label: {
                            BrandIconView(icon: .minus, size: BrandSize.iconSmall)
                                .frame(width: BrandSize.touch, height: BrandSize.touch)
                        }
                        .buttonStyle(.bordered)
                        .disabled(app.store.settings.textScale <= (AppSettings.textScales.first ?? 1))
                        .accessibilityLabel(tr(.readerTextSmaller))

                        Text(tr(.settingsTextSizePreview))
                            .font(.custom("Archivo-Regular", size: 17 * app.store.settings.textScale))
                            .foregroundStyle(Color.brandInk)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)

                        Button {
                            app.updateSettings { $0.textScale = $0.steppedTextScale(1) }
                        } label: {
                            BrandIconView(icon: .plus, size: BrandSize.iconSmall)
                                .frame(width: BrandSize.touch, height: BrandSize.touch)
                        }
                        .buttonStyle(.bordered)
                        .disabled(app.store.settings.textScale >= (AppSettings.textScales.last ?? 1))
                        .accessibilityLabel(tr(.readerTextLarger))
                    }
                }
                .padding(.vertical, BrandSpace.xs)
            }
            .listRowBackground(Color.brandSurface)

            Section(tr(.settingsSectionDrive)) {
                HStack(spacing: BrandSpace.m) {
                    BrandIconView(icon: app.phase == .ready ? .drive : .driveOff, size: BrandSize.icon)
                        .foregroundStyle(Color.brandInk2)
                    if app.phase == .ready, let name = app.driveName {
                        Text(tr(.settingsDriveConnected, name))
                    } else {
                        Text(tr(.settingsDriveDisconnected))
                    }
                }
                .accessibilityElement(children: .combine)

                if !app.isDemo {
                    Button(tr(.settingsDriveChange)) {
                        app.chooseAnotherDrive()
                    }
                    .foregroundStyle(Color.brandLink)
                }

                VStack(alignment: .leading, spacing: BrandSpace.xs) {
                    HStack {
                        Text(tr(.settingsKeepCopyTitle))
                            .foregroundStyle(Color.brandInk2)
                        Spacer()
                        Text(tr(.settingsSoon))
                            .brandText(.labelSmall)
                            .foregroundStyle(Color.brandWarnInk)
                            .padding(.horizontal, BrandSpace.s)
                            .padding(.vertical, BrandSpace.xxs)
                            .background(Capsule().fill(Color.brandWarnBg))
                    }
                    Text(tr(.settingsKeepCopyBody))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk3)
                }
                .accessibilityElement(children: .combine)
            }
            .listRowBackground(Color.brandSurface)

            Section {
                Toggle(tr(.settingsSaveHistory), isOn: setting(\.saveHistory))
                    .tint(Color.brandAccent)
                Button(tr(.settingsClearSearches)) {
                    app.store.clearRecentSearches()
                }
                .disabled(app.store.recentSearches.isEmpty)
                Button(tr(.historyClear), role: .destructive) {
                    app.store.clearHistory()
                }
                .disabled(app.store.history.isEmpty)
            } header: {
                Text(tr(.settingsSectionPrivacy))
            } footer: {
                Text(tr(.settingsPrivacyNote))
            }
            .listRowBackground(Color.brandSurface)

            Section {
                NavigationLink(value: Route.about) {
                    Text(tr(.settingsAbout))
                }
                .accessibilityIdentifier("settings.about")
            }
            .listRowBackground(Color.brandSurface)
        }
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.settingsTitle))
    }

    /// A binding to one setting that saves through `AppModel` (language changes re-localize the UI).
    private func setting<Value>(_ keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(
            get: { app.store.settings[keyPath: keyPath] },
            set: { newValue in app.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }
}

#Preview("Nastavení") {
    NavigationStack { SettingsView() }
        .environment(AppModel.preview())
}
