import SwiftUI
import UIKit
import WebKit

/// The article screen. Creates its `ReaderModel` (and so its WKWebView) once, on first appear:
/// `State(initialValue:)` in `init` would build a new web view every time SwiftUI re-creates
/// this struct.
@MainActor
struct ReaderScreen: View {
    let target: ReaderTarget
    /// Where this reader had got to before it was rebuilt (drive back, language change).
    let resumeAt: ReaderTarget?
    let engine: ReaderEngine
    let store: UserStore
    @State private var model: ReaderModel?

    var body: some View {
        Group {
            if let model {
                ReaderContent(model: model, target: target)
            } else {
                Color.brandBg.ignoresSafeArea()
            }
        }
        .onAppear {
            guard model == nil else { return }
            let created = ReaderModel(
                target: resumeAt ?? target,
                engine: engine,
                store: store,
                appearance: ReaderAppearance(theme: store.settings.theme, textScale: store.settings.textScale)
            )
            model = created
            created.start()
        }
    }
}

/// The article: web view with title, back/forward, save, find, contents and appearance.
@MainActor
private struct ReaderContent: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    let model: ReaderModel
    let target: ReaderTarget
    @State private var showContents = false

    var body: some View {
        ReaderWebView(webView: model.webView)
            .ignoresSafeArea(.container, edges: .bottom)
            .overlay(alignment: .top) {
                if model.isLoading && model.progress < 1 {
                    ProgressView(value: max(model.progress, 0.05))
                        .progressViewStyle(.linear)
                        .tint(Color.brandPrimary)
                        .accessibilityLabel(tr(.readerLoading))
                }
            }
            .background(Color.brandBg.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .toolbar(.hidden, for: .tabBar)
            .toolbarBackground(Color.brandSurface, for: .navigationBar, .bottomBar)
            .alert(tr(.readerExternalTitle), isPresented: externalLinkShown, presenting: model.externalLink) { url in
                Button(tr(.readerCopyLink)) {
                    UIPasteboard.general.string = url.absoluteString
                    app.showToast(tr(.readerLinkCopied))
                }
                Button(tr(.actionClose), role: .cancel) {}
            } message: { url in
                Text(tr(.readerExternalBody) + "\n\n" + url.absoluteString)
            }
            .sheet(isPresented: $showContents) {
                ContentsSheet(headings: model.headings) { heading in
                    showContents = false
                    model.scrollTo(heading)
                }
            }
            .onAppear(perform: applyAppearance)
            .onChange(of: app.store.settings.theme) { _, _ in applyAppearance() }
            .onChange(of: app.store.settings.textScale) { _, _ in applyAppearance() }
            .onChange(of: model.location) { _, location in
                if let location {
                    app.resumeLocations[target] = ReaderTarget(bookId: location.bookId, path: location.path)
                }
            }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(spacing: 0) {
                Text(model.displayTitle)
                    .brandText(.subhead)
                    .foregroundStyle(Color.brandInk)
                    .lineLimit(1)
                if !model.bookTitle.isEmpty && model.bookTitle != model.displayTitle {
                    Text(model.bookTitle)
                        .brandText(.caption)
                        .foregroundStyle(Color.brandInk2)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        }
        ToolbarItemGroup(placement: .bottomBar) {
            Button { model.goBack() } label: { BrandIconView(icon: .arrowBack) }
                .disabled(!model.canGoBack)
                .accessibilityLabel(tr(.actionBack))
            Spacer()
            Button { model.goForward() } label: { BrandIconView(icon: .arrowForward) }
                .disabled(!model.canGoForward)
                .accessibilityLabel(tr(.actionForward))
            Spacer()
            Button(action: toggleSaved) {
                BrandIconView(icon: model.isSaved ? .bookmarkFilled : .bookmark)
                    .foregroundStyle(model.isSaved ? Color.brandPrimary : Color.brandInk)
            }
            .disabled(!model.canSave)
            .accessibilityLabel(model.isSaved ? tr(.readerUnsave) : tr(.readerSave))
            .accessibilityValue(model.isSaved ? tr(.cdSavedOn) : tr(.cdSavedOff))
            .accessibilityIdentifier("reader.save")
            Spacer()
            Button { model.showFind() } label: { BrandIconView(icon: .findInPage) }
                .accessibilityLabel(tr(.readerFind))
            Spacer()
            moreMenu
        }
    }

    private var moreMenu: some View {
        Menu {
            Button {
                model.loadHeadings()
                showContents = true
            } label: {
                Label(tr(.readerContents), systemImage: BrandIcon.toc.systemName)
            }
            Button {
                model.openMainPage()
            } label: {
                Label(tr(.readerMainPage), systemImage: BrandIcon.home.systemName)
            }
            Button {
                model.openRandomArticle()
            } label: {
                Label(tr(.homeQuickRandom), systemImage: BrandIcon.random.systemName)
            }
            Divider()
            if model.usesReaderStyle {
                Button {
                    stepTextSize(-1)
                } label: {
                    Label(tr(.readerTextSmaller), systemImage: BrandIcon.minus.systemName)
                }
                .disabled(app.store.settings.textScale <= (AppSettings.textScales.first ?? 1))
                Button {
                    stepTextSize(1)
                } label: {
                    Label(tr(.readerTextLarger), systemImage: BrandIcon.plus.systemName)
                }
                .disabled(app.store.settings.textScale >= (AppSettings.textScales.last ?? 1))
            }
            Button {
                toggleTheme()
            } label: {
                Label(tr(.readerThemeToggle), systemImage: colorScheme == .dark ? BrandIcon.sun.systemName : BrandIcon.moon.systemName)
            }
        } label: {
            BrandIconView(icon: .moreVert)
        }
        .accessibilityLabel(tr(.actionMore))
    }

    // MARK: - Actions

    private var externalLinkShown: Binding<Bool> {
        Binding(
            get: { model.externalLink != nil },
            set: { if !$0 { model.externalLink = nil } }
        )
    }

    private func toggleSaved() {
        guard let saved = model.toggleSaved() else { return }
        app.showToast(saved ? tr(.readerSavedToast) : tr(.readerRemovedToast))
        UIAccessibility.post(notification: .announcement, argument: saved ? tr(.readerSavedToast) : tr(.readerRemovedToast))
    }

    private func stepTextSize(_ step: Int) {
        app.updateSettings { $0.textScale = $0.steppedTextScale(step) }
    }

    private func toggleTheme() {
        app.updateSettings { $0.theme = colorScheme == .dark ? .light : .dark }
    }

    private func applyAppearance() {
        model.apply(ReaderAppearance(theme: app.store.settings.theme, textScale: app.store.settings.textScale))
    }
}

/// Hosts the reader's WKWebView (owned by `ReaderModel`).
struct ReaderWebView: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

/// "Obsah článku": headings of the page.
private struct ContentsSheet: View {
    let headings: [ReaderHeading]
    let onSelect: (ReaderHeading) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if headings.isEmpty {
                    EmptyStateView(icon: .article, title: tr(.readerContents), message: tr(.readerLoading))
                } else {
                    List(headings) { heading in
                        Button {
                            onSelect(heading)
                        } label: {
                            Text(heading.text)
                                .brandText(heading.level == 2 ? .bodyStrong : .body)
                                .foregroundStyle(Color.brandInk)
                                .padding(.leading, heading.level == 2 ? 0 : BrandSpace.l)
                                .frame(minHeight: BrandSize.touch - 8, alignment: .leading)
                        }
                        .listRowBackground(Color.brandSurface)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(Color.brandBg.ignoresSafeArea())
            .navigationTitle(tr(.readerContents))
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
