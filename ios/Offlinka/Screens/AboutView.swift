import SwiftUI
import UIKit

/// Version, drive edition, privacy, licences, open-source notices, contact (design mockup `about`).
@MainActor
struct AboutView: View {
    @Environment(AppModel.self) private var app
    static let contactEmail = "info@offlinka.cz"

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.m) {
                    Wordmark(onDark: false)
                    Text(tr(.aboutTagline))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                    HStack(spacing: BrandSpace.s) {
                        TagView(text: tr(.aboutVersion, Self.appVersion))
                        if let edition = app.edition {
                            TagView(text: tr(.aboutEdition, ContentDate.monthYear(edition)))
                        }
                    }
                }
                .padding(.vertical, BrandSpace.s)
                .listRowBackground(Color.clear)
            }

            Section(tr(.aboutPrivacyTitle)) {
                Label {
                    Text(tr(.aboutPrivacyBody))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk)
                } icon: {
                    BrandIconView(icon: .lock, size: BrandSize.iconSmall)
                        .foregroundStyle(Color.brandInk2)
                }
            }
            .listRowBackground(Color.brandSurface)

            Section(tr(.aboutLicencesTitle)) {
                ForEach([Str.aboutLicWikipedia, .aboutLicOsm, .aboutLicZim, .aboutLicGames, .aboutLicFonts], id: \.self) { key in
                    Text(tr(key))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk)
                }
                NavigationLink {
                    NoticesView()
                } label: {
                    VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                        Text(tr(.aboutNotices))
                            .brandText(.bodyStrong)
                        Text(tr(.aboutNoticesBody))
                            .brandText(.caption)
                            .foregroundStyle(Color.brandInk2)
                    }
                }
            }
            .listRowBackground(Color.brandSurface)

            Section {
                // Copy only: the app opens nothing outside itself.
                Button {
                    UIPasteboard.general.string = Self.contactEmail
                    app.showToast(tr(.readerLinkCopied))
                } label: {
                    HStack {
                        BrandIconView(icon: .mail, size: BrandSize.iconSmall)
                            .foregroundStyle(Color.brandInk2)
                        VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                            Text(tr(.aboutContact))
                                .brandText(.bodyStrong)
                                .foregroundStyle(Color.brandInk)
                            Text(verbatim: Self.contactEmail)
                                .brandText(.bodySmall)
                                .foregroundStyle(Color.brandLink)
                        }
                        Spacer()
                        BrandIconView(icon: .copy, size: BrandSize.iconSmall)
                            .foregroundStyle(Color.brandInk2)
                    }
                    .frame(minHeight: BrandSize.touch)
                }
                .accessibilityHint(tr(.actionCopy))
            } footer: {
                VStack(alignment: .leading, spacing: BrandSpace.xs) {
                    Text(tr(.aboutLicApp))
                    Text(tr(.aboutTrademark))
                    Text(tr(.aboutMadeBy))
                }
                .brandText(.caption)
                .foregroundStyle(Color.brandInk2)
                .padding(.top, BrandSpace.s)
            }
            .listRowBackground(Color.brandSurface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.aboutTitle))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// `1.0.0 (12)`
    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

/// Third-party software in the app, with licences (from core/LICENSES-THIRD-PARTY.md and the
/// bundled fonts). Product and licence names are not translated.
@MainActor
private struct NoticesView: View {
    @Environment(AppModel.self) private var app

    private struct Component: Identifiable {
        let name: String
        let licence: String
        let source: String
        var id: String { name }
    }

    /// Keep in sync with core/LICENSES-THIRD-PARTY.md ("Compiled into the app library").
    private static let components: [Component] = [
        Component(name: "UniFFI", licence: "MPL-2.0", source: "github.com/mozilla/uniffi-rs"),
        Component(name: "fst", licence: "Unlicense / MIT", source: "github.com/BurntSushi/fst"),
        Component(name: "ruzstd", licence: "MIT", source: "github.com/KillingSpark/zstd-rs"),
        Component(name: "twox-hash", licence: "MIT", source: "github.com/shepmaster/twox-hash"),
        Component(name: "lzma-rs", licence: "MIT", source: "github.com/gendx/lzma-rs"),
        Component(name: "crc", licence: "MIT / Apache-2.0", source: "github.com/mrhooray/crc-rs"),
        Component(name: "byteorder", licence: "Unlicense / MIT", source: "github.com/BurntSushi/byteorder"),
        Component(name: "lru", licence: "MIT", source: "github.com/jeromefroe/lru-rs"),
        Component(name: "libc", licence: "MIT / Apache-2.0", source: "github.com/rust-lang/libc"),
        Component(name: "unicode-normalization", licence: "MIT / Apache-2.0, Unicode-3.0", source: "github.com/unicode-rs/unicode-normalization"),
        Component(name: "tinyvec", licence: "Zlib / Apache-2.0 / MIT", source: "github.com/Lokathor/tinyvec"),
        Component(name: "thiserror", licence: "MIT / Apache-2.0", source: "github.com/dtolnay/thiserror"),
        Component(name: "anyhow, bytes, once_cell, hashbrown …", licence: "MIT / Apache-2.0", source: "crates.io"),
        Component(name: "Rust standard library", licence: "MIT / Apache-2.0", source: "github.com/rust-lang/rust"),
        Component(name: "Archivo (The Archivo Project Authors)", licence: "SIL OFL 1.1", source: "github.com/Omnibus-Type/Archivo"),
    ]

    var body: some View {
        List {
            Section {
                ForEach(Self.components) { component in
                    VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                        HStack {
                            Text(verbatim: component.name)
                                .brandText(.bodyStrong)
                                .foregroundStyle(Color.brandInk)
                            Spacer()
                            Text(verbatim: component.licence)
                                .brandText(.caption)
                                .foregroundStyle(Color.brandInk2)
                        }
                        Text(verbatim: component.source)
                            .brandText(.caption)
                            .foregroundStyle(Color.brandInk3)
                            .textSelection(.enabled)
                    }
                    .accessibilityElement(children: .combine)
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
