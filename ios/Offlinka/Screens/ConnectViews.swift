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
        ScrollView {
            VStack(alignment: .leading, spacing: BrandSpace.xl) {
                DriveIllustration()
                    .frame(maxWidth: .infinity)
                    .padding(.top, BrandSpace.xxl)

                Text(tr(.connectTitle))
                    .brandText(.headline)
                    .foregroundStyle(Color.brandInk)
                    .accessibilityAddTraits(.isHeader)

                Text(tr(.connectBody))
                    .brandText(.body)
                    .foregroundStyle(Color.brandInk2)

                if let problem {
                    ProblemCard(problem: problem)
                }

                VStack(alignment: .leading, spacing: BrandSpace.m) {
                    StepRow(number: 1, text: tr(.connectStepPlug))
                    StepRow(number: 2, text: tr(.connectStepPickIos))
                }

                Button(problem == nil ? tr(.connectButton) : tr(.connectPickAgain)) {
                    showPicker = true
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
                .frame(minHeight: BrandSize.touch)
            }
            .padding(.horizontal, BrandSize.gutter)
            .padding(.bottom, BrandSpace.huge)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(Color.brandBg.ignoresSafeArea())
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
        VStack(spacing: BrandSpace.xl) {
            Spacer(minLength: BrandSpace.huge)
            BrandIconView(icon: .driveOff, size: 56)
                .foregroundStyle(Color.brandInk2)
            Text(tr(.unpluggedTitle))
                .brandText(.headline)
                .foregroundStyle(Color.brandInk)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(tr(.unpluggedBody))
                .brandText(.body)
                .foregroundStyle(Color.brandInk2)
                .multilineTextAlignment(.center)
            HStack(spacing: BrandSpace.s) {
                ProgressView()
                Text(tr(.unpluggedWaiting))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button(tr(.actionRetry)) { app.retry() }
                .buttonStyle(PrimaryButtonStyle())
            Button(tr(.unpluggedPickOther)) { app.chooseAnotherDrive() }
                .buttonStyle(SecondaryButtonStyle())
        }
        .padding(.horizontal, BrandSize.gutter)
        .padding(.bottom, BrandSpace.xxl)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.brandBg.ignoresSafeArea())
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

/// A phone with the drive in its USB-C port, drawn in the brand style.
struct DriveIllustration: View {
    var body: some View {
        ZStack(alignment: .top) {
            // Phone
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.brandSurface)
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: 3))
                .frame(width: 112, height: 196)
                .brandShadow(BrandElevation.card)
                .overlay(alignment: .top) {
                    VStack(spacing: 10) {
                        Capsule().fill(Color.brandOutline).frame(width: 30, height: 6).padding(.top, 12)
                        RoundedRectangle(cornerRadius: 6).fill(Color.brandHero).frame(width: 80, height: 54)
                            .overlay(Triangle().fill(Color.brandPrimary).frame(width: 22, height: 18).offset(y: 4))
                        ForEach(0..<3, id: \.self) { index in
                            Capsule().fill(Color.brandLine).frame(width: index == 2 ? 50 : 76, height: 6)
                        }
                    }
                }
            // Drive in the port
            VStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 2).fill(Color.brandOutline).frame(width: 18, height: 10)
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color.brandPrimary)
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: 3))
                    .frame(width: 44, height: 62)
                    .overlay(Text(verbatim: "O").font(.custom("ArchivoExpanded-ExtraBold", size: 18)).foregroundStyle(Color.brandOnPrimary))
            }
            .offset(y: 196)
        }
        .frame(height: 196 + 72)
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
