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

/// The article (design mockup `reader`): top bar with home, the book (the article title once its
/// heading scrolled away), save and more; web view; bottom bar with back, forward, find, text size
/// and the book's main page.
@MainActor
private struct ReaderContent: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    let model: ReaderModel
    let target: ReaderTarget
    @State private var showContents = false
    @State private var showTextSize = false

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
            .navigationBarBackButtonHidden(true)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .toolbar(.hidden, for: .tabBar)
            .toolbarBackground(Color.brandBg, for: .navigationBar)
            .toolbarBackground(Color.brandSurface, for: .bottomBar)
            .toolbarBackground(.visible, for: .navigationBar, .bottomBar)
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
            .sheet(isPresented: $showTextSize) {
                TextSizeSheet()
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
        ToolbarItem(placement: .topBarLeading) {
            HStack(spacing: BrandSpace.s) {
                Button { dismiss() } label: {
                    BrandIconView(icon: .home)
                        .frame(width: BrandSize.touch, height: BrandSize.touch)
                }
                .accessibilityLabel(tr(.navHome))
                barTitle
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button(action: toggleSaved) {
                BrandIconView(icon: model.isSaved ? .bookmarkFilled : .bookmark)
                    .foregroundStyle(Color.brandAccent)
            }
            .disabled(!model.canSave)
            .accessibilityLabel(model.isSaved ? tr(.readerUnsave) : tr(.readerSave))
            .accessibilityValue(model.isSaved ? tr(.cdSavedOn) : tr(.cdSavedOff))
            .accessibilityIdentifier("reader.save")
            moreMenu
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
            Button { model.showFind() } label: { BrandIconView(icon: .findInPage) }
                .accessibilityLabel(tr(.readerFind))
            Spacer()
            Button { showTextSize = true } label: { BrandIconView(icon: .textSize) }
                .accessibilityLabel(tr(.readerTextSize))
            Spacer()
            Button { model.openMainPage() } label: {
                BrandIconView(icon: model.book.map { BookCatalog.kind(of: $0).icon } ?? .home)
            }
            .disabled(model.book == nil)
            .accessibilityLabel(tr(.readerMainPage))
        }
    }

    /// The book (icon box + name), or the article title once its own heading scrolled away.
    @ViewBuilder
    private var barTitle: some View {
        if model.titleScrolledAway && !model.title.isEmpty {
            Text(model.displayTitle)
                .brandText(.subhead)
                .foregroundStyle(Color.brandInk)
                .lineLimit(1)
                .transition(.opacity)
                .accessibilityAddTraits(.isHeader)
        } else if let book = model.book {
            HStack(spacing: BrandSpace.s) {
                BookIconBox(kind: BookCatalog.kind(of: book), box: 26)
                Text(BookCatalog.title(of: book))
                    .brandText(.label)
                    .foregroundStyle(Color.brandInk2)
                    .lineLimit(1)
            }
            .transition(.opacity)
            .accessibilityElement(children: .combine)
        }
    }

    private var moreMenu: some View {
        Menu {
            Button {
                app.router.showHistory()
            } label: {
                Label(tr(.readerHistory), systemImage: BrandIcon.history.systemName)
            }
            Button {
                model.loadHeadings()
                showContents = true
            } label: {
                Label(tr(.readerContents), systemImage: BrandIcon.toc.systemName)
            }
            Button {
                app.updateSettings { $0.theme = colorScheme == .dark ? .light : .dark }
            } label: {
                Label(tr(.readerThemeToggle), systemImage: colorScheme == .dark ? BrandIcon.sun.systemName : BrandIcon.moon.systemName)
            }
            Button {
                copyLink()
            } label: {
                Label(tr(.readerCopyLink), systemImage: BrandIcon.copy.systemName)
            }
            Button {
                model.openRandomArticle()
            } label: {
                Label(tr(.homeQuickRandom), systemImage: BrandIcon.random.systemName)
            }
        } label: {
            BrandIconView(icon: .moreVert)
                .frame(width: BrandSize.touch, height: BrandSize.touch)
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
        let message = saved ? tr(.readerSavedToast) : tr(.readerRemovedToast)
        app.showToast(message)
        UIAccessibility.post(notification: .announcement, argument: message)
    }

    /// Copies the article's address on Wikipedia (useful later, with internet).
    private func copyLink() {
        model.canonicalLink { link in
            guard let link else { return }
            UIPasteboard.general.string = link
            app.showToast(tr(.readerLinkCopied))
        }
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

/// "Velikost písma" sheet: text size slider and light/dark (mockup `reader-text-size`).
@MainActor
private struct TextSizeSheet: View {
    var body: some View {
        VStack(alignment: .leading, spacing: BrandSpace.xl) {
            Text(tr(.readerTextSize))
                .brandText(.headline)
                .foregroundStyle(Color.brandInk)
                .accessibilityAddTraits(.isHeader)
            TextScaleControl()
            VStack(alignment: .leading, spacing: BrandSpace.s) {
                Text(tr(.settingsTheme))
                    .brandText(.label)
                    .foregroundStyle(Color.brandInk2)
                ThemePicker()
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, BrandSize.gutter)
        .padding(.top, BrandSpace.xxl)
        .background(Color.brandSurface.ignoresSafeArea())
        .presentationDetents([.height(300), .medium])
        .presentationDragIndicator(.visible)
    }
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
                    EmptyStateView(icon: .toc, title: tr(.readerContents), message: tr(.readerLoading))
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
