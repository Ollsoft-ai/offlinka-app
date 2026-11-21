import SwiftUI

/// Language, light/dark, text size, the drive, history and searches (design mockup `settings`).
@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Form {
            Section(tr(.settingsSectionDisplay)) {
                Picker(tr(.settingsLanguage), selection: app.binding(for: \.language)) {
                    Text(tr(.settingsLanguageSystem)).tag(AppLanguage.system)
                    Text(tr(.settingsLanguageCs)).tag(AppLanguage.cs)
                    Text(tr(.settingsLanguageSk)).tag(AppLanguage.sk)
                    Text(tr(.settingsLanguageEn)).tag(AppLanguage.en)
                }
                .accessibilityIdentifier("settings.language")

                VStack(alignment: .leading, spacing: BrandSpace.s) {
                    Text(tr(.settingsTheme))
                    ThemePicker()
                }
                .padding(.vertical, BrandSpace.xs)

                VStack(alignment: .leading, spacing: BrandSpace.m) {
                    Text(tr(.settingsTextSize))
                    TextScaleControl()
                    // Preview in the reader's type: 17 pt body times the chosen scale.
                    Text(tr(.settingsTextSizePreview))
                        .font(.custom("Archivo-Regular", size: 17 * app.store.settings.textScale))
                        .lineSpacing(17 * app.store.settings.textScale * 0.5)
                        .foregroundStyle(Color.brandInk)
                        .padding(BrandSpace.m)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: BrandRadius.s).fill(Color.brandBg))
                }
                .padding(.vertical, BrandSpace.xs)
            }
            .listRowBackground(Color.brandSurface)

            Section(tr(.settingsSectionDrive)) {
                HStack(spacing: BrandSpace.m) {
                    Circle()
                        .fill(app.phase == .ready ? Color.brandOk : Color.brandInk3)
                        .frame(width: 8, height: 8)
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
                            .background(RoundedRectangle(cornerRadius: BrandRadius.xs).fill(Color.brandWarnBg))
                    }
                    Text(tr(.settingsKeepCopyBody))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk3)
                }
                .opacity(0.7)
                .accessibilityElement(children: .combine)
            }
            .listRowBackground(Color.brandSurface)

            Section {
                Toggle(tr(.settingsSaveHistory), isOn: app.binding(for: \.saveHistory))
                    .tint(Color.brandAccent)
                Button(tr(.settingsClearSearches)) {
                    app.store.clearRecentSearches()
                }
                .disabled(app.store.recentSearches.isEmpty)
                Button(tr(.historyClear), role: .destructive) {
                    app.store.clearHistory()
                }
                .foregroundStyle(Color.brandDanger)
                .disabled(app.store.history.isEmpty)
            } header: {
                Text(tr(.settingsSectionPrivacy))
            } footer: {
                HStack(alignment: .firstTextBaseline, spacing: BrandSpace.xs) {
                    BrandIconView(icon: .lock, size: 18)
                    Text(tr(.settingsPrivacyNote))
                }
                .brandText(.caption)
                .foregroundStyle(Color.brandInk2)
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
}

#Preview("Nastavení") {
    NavigationStack { SettingsView() }
        .environment(AppModel.preview())
}
