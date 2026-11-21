import SwiftUI
import UniformTypeIdentifiers

/// First run (and after a failed pick): plug in the drive and pick it in the Files sheet.
@MainActor
struct ConnectView: View {
    @Environment(AppModel.self) private var app
    let problem: AppModel.ConnectProblem?

    @State private var showPicker = false
    @State private var showHelp = false

    var body: some View {
        HeroScreen {
            DriveIllustration(pluggedIn: true)
        } content: {
            VStack(alignment: .leading, spacing: BrandSpace.xl) {
                Text(tr(.connectTitle))
                    .brandText(.headline)
                    .foregroundStyle(Color.brandInk)
                    .accessibilityAddTraits(.isHeader)

                Text(tr(.connectBody))
                    .brandText(.body)
                    .foregroundStyle(Color.brandInk2)

                if let problem {
                    // A problem note replaces the steps (design: connect-wrong-folder).
                    ProblemCard(problem: problem)
                } else {
                    VStack(alignment: .leading, spacing: BrandSpace.m) {
                        StepRow(number: 1, text: tr(.connectStepPlug))
                        StepRow(number: 2, text: tr(.connectStepPickIos))
                    }
                }

                Button {
                    showPicker = true
                } label: {
                    Label {
                        Text(problem == nil ? tr(.connectButton) : tr(.connectPickAgain))
                    } icon: {
                        BrandIconView(icon: .drive)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("connect.pick")

                Text(tr(.connectRemember))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)

                Button(tr(.connectHelpLink)) {
                    showHelp = true
                }
                .brandText(.label)
                .foregroundStyle(Color.brandLink)
                .frame(maxWidth: .infinity, minHeight: BrandSize.touch)
            }
        }
        .fileImporter(isPresented: $showPicker, allowedContentTypes: [.folder]) { result in
            app.didPick(result)
        }
        .sheet(isPresented: $showHelp) {
            HelpSheet()
        }
    }
}

private struct ProblemCard: View {
    let problem: AppModel.ConnectProblem

    var body: some View {
        VStack(alignment: .leading, spacing: BrandSpace.xs) {
            Text(title)
                .brandText(.subhead)
                .foregroundStyle(Color.brandWarnInk)
            Text(message)
                .brandText(.bodySmall)
                .foregroundStyle(Color.brandInk)
        }
        .padding(BrandSpace.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandWarnBg))
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandWarnLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        switch problem {
        case .notFound: return tr(.connectErrNotfoundTitle)
        case .wrongFolder: return tr(.connectErrWrongfolderTitle)
        case .noAccess: return tr(.connectErrNoaccessTitle)
        case .unreadable: return tr(.errorTitle)
        }
    }

    private var message: String {
        switch problem {
        case .notFound: return tr(.connectErrNotfoundBody)
        case .wrongFolder: return tr(.connectErrWrongfolderBody)
        case .noAccess: return tr(.connectErrNoaccessBody)
        case .unreadable: return tr(.errorBody)
        }
    }
}

private struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: BrandSpace.m) {
            Text(verbatim: "\(number)")
                .brandText(.label)
                .foregroundStyle(Color.brandOnAccent)
                .frame(width: 28, height: 28)
                .background(Circle().fill(Color.brandAccent))
            Text(text)
                .brandText(.body)
                .foregroundStyle(Color.brandInk)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// "Flashka se neukazuje?" help.
struct HelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: BrandSpace.l) {
                    ForEach([Str.help1, .help2, .help4Ios, .help5], id: \.self) { key in
                        HStack(alignment: .firstTextBaseline, spacing: BrandSpace.m) {
                            Circle().fill(Color.brandPrimary).frame(width: 8, height: 8)
                            Text(tr(key))
                                .brandText(.body)
                                .foregroundStyle(Color.brandInk)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(BrandSize.gutter)
            }
            .background(Color.brandBg.ignoresSafeArea())
            .navigationTitle(tr(.helpTitle))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr(.actionDone)) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A remembered drive is not plugged in. Waits calmly; reopens by itself when it is back.
@MainActor
struct UnpluggedView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        HeroScreen {
            DriveIllustration(pluggedIn: false)
        } content: {
            VStack(alignment: .leading, spacing: BrandSpace.xl) {
                Text(tr(.unpluggedTitle))
                    .brandText(.headline)
                    .foregroundStyle(Color.brandInk)
                    .accessibilityAddTraits(.isHeader)
                Text(tr(.unpluggedBody))
                    .brandText(.body)
                    .foregroundStyle(Color.brandInk2)
                HStack(spacing: BrandSpace.s) {
                    Circle()
                        .fill(Color.brandOk)
                        .frame(width: 8, height: 8)
                        .padding(5)
                        .background(Circle().fill(Color.brandOk.opacity(0.25)))
                    Text(tr(.unpluggedWaiting))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk2)
                }
                .accessibilityElement(children: .combine)
                // The app reopens by itself when the drive is back (checked every 2 s and on
                // return to the app); this button is only for another drive.
                Button(tr(.unpluggedPickOther)) { app.chooseAnotherDrive() }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }
}

/// Forest hero with the wordmark in the bar and the ridge into the paper below, then content
/// (Connect and Unplugged, design mockups `connect`, `unplugged`).
struct HeroScreen<Hero: View, Content: View>: View {
    @ViewBuilder let hero: () -> Hero
    @ViewBuilder let content: () -> Content

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    hero()
                        .frame(maxWidth: .infinity)
                        .padding(.top, BrandSpace.l)
                        .padding(.bottom, BrandSize.ridge * 2)
                        .background(HeroBackground().ignoresSafeArea(edges: .horizontal))
                    content()
                        .padding(.horizontal, BrandSize.gutter)
                        .padding(.top, BrandSpace.l)
                        .padding(.bottom, BrandSpace.huge)
                        .frame(maxWidth: 560, alignment: .leading)
                }
                .frame(maxWidth: .infinity)
            }
            .background(Color.brandBg.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Wordmark(size: 26)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Color.brandHero, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}

/// While the books are being found and opened.
struct OpeningView: View {
    var body: some View {
        VStack(spacing: BrandSpace.l) {
            Wordmark(onDark: false)
            ProgressView()
            Text(tr(.connectSearching))
                .brandText(.bodySmall)
                .foregroundStyle(Color.brandInk2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.brandBg.ignoresSafeArea())
        .accessibilityElement(children: .combine)
    }
}

/// A phone with the drive in its USB-C port (or pulled out), drawn for the forest hero.
struct DriveIllustration: View {
    var pluggedIn = true

    var body: some View {
        ZStack(alignment: .top) {
            // Phone
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Color.brandHeroDeep)
                .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Color.brandOnHero, lineWidth: 4))
                .frame(width: 128, height: 214)
                .overlay(alignment: .top) {
                    VStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 3).fill(Color.brandOnHero).frame(width: 84, height: 12).padding(.top, 26)
                        RoundedRectangle(cornerRadius: 4).fill(Color.brandOnHero).frame(width: 84, height: 20)
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 4).fill(Color.brandOnHero).frame(width: 39, height: 38)
                            RoundedRectangle(cornerRadius: 4).fill(Color.brandOnHero).frame(width: 39, height: 38)
                        }
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 4).fill(Color.brandOnHero).frame(width: 39, height: 38)
                            RoundedRectangle(cornerRadius: 4).fill(Color.brandOnHero).frame(width: 39, height: 38)
                        }
                    }
                    .opacity(pluggedIn ? 1 : 0.35)
                }
            // Drive: in the port, or pulled out a little
            VStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 2).fill(Color.brandOnHero2).frame(width: 22, height: 10)
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(Color.brandHeroDeep)
                    .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Color.brandOnHero, lineWidth: 4))
                    .frame(width: 46, height: 72)
                    .overlay(Triangle().fill(Color.brandPrimary).frame(width: 16, height: 14))
            }
            .offset(y: pluggedIn ? 214 : 244)
        }
        .frame(height: 214 + 112, alignment: .top)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr(.cdDriveIllustration))
    }
}

#Preview("Zapojte flashku") {
    ConnectView(problem: nil)
        .environment(AppModel.preview(phase: .connect(problem: nil)))
}

#Preview("Špatná složka") {
    ConnectView(problem: .wrongFolder)
        .environment(AppModel.preview(phase: .connect(problem: .wrongFolder)))
}

#Preview("Odpojeno") {
    UnpluggedView()
        .environment(AppModel.preview(phase: .unplugged))
}
