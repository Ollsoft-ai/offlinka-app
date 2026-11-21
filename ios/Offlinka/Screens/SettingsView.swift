import SwiftUI
import UniformTypeIdentifiers

/// Language, light/dark, text size, the drive, history and searches (design mockup `settings`).
@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var showPicker = false
    @State private var confirmForget = false

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
                        showPicker = true
                    }
                    .foregroundStyle(Color.brandLink)
                    if app.hasRememberedDrive {
                        Button(tr(.settingsDriveForget), role: .destructive) {
                            confirmForget = true
                        }
                        .foregroundStyle(Color.brandDanger)
                    }
                }

                // „Česká Wikipedie i bez flashky“ stays hidden until it ships (UX review r2).
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
        // Another drive while these books stay open; a wrong pick only shows a short message.
        .fileImporter(isPresented: $showPicker, allowedContentTypes: [.folder]) { result in
            app.didPick(result)
        }
        .confirmationDialog(tr(.settingsDriveForget), isPresented: $confirmForget, titleVisibility: .visible) {
            Button(tr(.settingsDriveForget), role: .destructive) {
                app.forgetDrive()
            }
            Button(tr(.actionCancel), role: .cancel) {}
        }
    }
}

#if DEBUG
#Preview("Nastavení") {
    NavigationStack { SettingsView() }
        .environment(AppModel.preview())
}
#endif
