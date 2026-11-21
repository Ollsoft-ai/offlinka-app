import SwiftUI

/// Version, drive edition, privacy, licences, open-source notices.
@MainActor
struct AboutView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.s) {
                    Wordmark(onDark: false)
                    Text(tr(.aboutTagline))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                    Text(tr(.aboutVersion, Self.appVersion))
                        .brandText(.caption)
                        .foregroundStyle(Color.brandInk2)
                    if let edition = app.edition {
                        Text(tr(.aboutEdition, edition))
                            .brandText(.caption)
                            .foregroundStyle(Color.brandInk2)
                    }
                }
                .padding(.vertical, BrandSpace.s)
                .listRowBackground(Color.clear)
            }

            Section(tr(.aboutPrivacyTitle)) {
                Text(tr(.aboutPrivacyBody))
                    .brandText(.body)
                    .foregroundStyle(Color.brandInk)
            }
            .listRowBackground(Color.brandSurface)

            Section(tr(.aboutLicencesTitle)) {
                ForEach([Str.aboutLicWikipedia, .aboutLicOsm, .aboutLicZim, .aboutLicGames, .aboutLicFonts, .aboutLicApp], id: \.self) { key in
                    Text(tr(key))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk)
                }
                Text(tr(.aboutTrademark))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)
            }
            .listRowBackground(Color.brandSurface)

            Section {
                NavigationLink {
                    NoticesView()
                } label: {
                    VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                        Text(tr(.aboutNotices))
                        Text(tr(.aboutNoticesBody))
                            .brandText(.caption)
                            .foregroundStyle(Color.brandInk2)
                    }
                }
            }
            .listRowBackground(Color.brandSurface)

            Section {
                if let mail = URL(string: "mailto:info@offlinka.cz") {
                    Link(destination: mail) {
                        Label(tr(.aboutContact), systemImage: BrandIcon.mail.systemName)
                    }
                }
                Text(tr(.aboutMadeBy))
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk2)
            }
            .listRowBackground(Color.brandSurface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.aboutTitle))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// `1.0.0 (12) · core 0.1.0`
    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

/// Third-party software in the app, with licences. Product names and licence names are not translated.
@MainActor
private struct NoticesView: View {
    @Environment(AppModel.self) private var app

    private struct Component: Identifiable {
        let name: String
        let licence: String
        var id: String { name }
    }

    /// Keep in sync with core/Cargo.toml (Rust dependencies) and the bundled fonts.
    private static let components: [Component] = [
        Component(name: "UniFFI", licence: "MPL-2.0"),
        Component(name: "ruzstd", licence: "MIT"),
        Component(name: "lzma-rs", licence: "MIT"),
        Component(name: "fst", licence: "MIT / Unlicense"),
        Component(name: "lru", licence: "MIT"),
        Component(name: "memmap2", licence: "MIT / Apache-2.0"),
        Component(name: "unicode-normalization", licence: "MIT / Apache-2.0"),
        Component(name: "thiserror", licence: "MIT / Apache-2.0"),
        Component(name: "Rust standard library", licence: "MIT / Apache-2.0"),
        Component(name: "Archivo (The Archivo Project Authors)", licence: "SIL OFL 1.1"),
    ]

    var body: some View {
        List {
            Section {
                ForEach(Self.components) { component in
                    LabeledContent(component.name, value: component.licence)
                }
            } footer: {
                if let core = app.content?.coreVersion {
                    Text(verbatim: "offlinka-core \(core)")
                }
            }
            .listRowBackground(Color.brandSurface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.aboutNotices))
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("O aplikaci") {
    NavigationStack { AboutView() }
        .environment(AppModel.preview())
}
