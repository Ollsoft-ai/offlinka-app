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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
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
            .toolbarBackground(.visible, for: .navigationBar)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !model.isMap {
                    ReaderBottomBar(model: model, back: back, showContents: openContents,
                                    showTextSize: { showTextSize = true }, toggleSaved: toggleSaved)
                }
            }
            .overlay(alignment: .topLeading) {
                // Maps: the whole screen is the map; a floating „Zpět“ pill instead of the bar.
                if model.isMap {
                    Button(action: back) {
                        Label {
                            Text(tr(.readerBarBack))
                        } icon: {
                            BrandIconView(icon: .arrowBack, size: BrandSize.iconSmall)
                        }
                        .brandText(.label)
                        .foregroundStyle(Color.brandInk)
                        .padding(.horizontal, BrandSpace.m)
                        .frame(minHeight: BrandSize.buttonSmall)
                        .background(Capsule().fill(Color.brandSurface))
                        .overlay(Capsule().strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline))
                        .hardShadow(BrandElevation.card)
                    }
                    .buttonStyle(.plain)
                    .padding(BrandSpace.m)
                    .accessibilityIdentifier("reader.back")
                }
            }
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
            .onChange(of: dynamicTypeSize) { _, _ in applyAppearance() }
            .onChange(of: app.shelf.tiles) { _, _ in model.reloadIfBookOpened() }
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
        ToolbarItem(placement: .topBarTrailing) {
            moreMenu
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
            // Tapping the book opens its main page (UX review r2).
            Button {
                model.openMainPage()
            } label: {
                HStack(spacing: BrandSpace.s) {
                    BookIconBox(kind: BookCatalog.kind(of: book), box: 26)
                    Text(BookCatalog.title(of: book))
                        .brandText(.label)
                        .foregroundStyle(Color.brandInk2)
                        .lineLimit(1)
                }
            }
            .buttonStyle(.plain)
            .transition(.opacity)
            .accessibilityElement(children: .combine)
            .accessibilityHint(tr(.readerMainPage))
        }
    }

    private var moreMenu: some View {
        Menu {
            if model.canGoForward {
                Button {
                    model.goForward()
                } label: {
                    Label(tr(.actionForward), systemImage: BrandIcon.arrowForward.systemName)
                }
            }
            if let book = model.book, !book.fileName.isEmpty, book.folder != .app {
                Button {
                    app.router.push(.book(book.id))
                } label: {
                    Label(tr(.libraryDetails), systemImage: BrandIcon.info.systemName)
                }
            }
            Button {
                app.router.showHistory()
            } label: {
                Label(tr(.readerHistory), systemImage: BrandIcon.history.systemName)
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

    /// „Zpět“ is never disabled: page history first, else leave the reader (Search keeps its query
    /// and scroll; the guides cover closes).
    private func back() {
        if model.canGoBack {
            model.goBack()
        } else {
            dismiss()
        }
    }

    private func openContents() {
        model.loadHeadings()
        showContents = true
    }

    /// The app's text size times the phone's text size (Dynamic Type), as Android multiplies the
    /// system font scale.
    private func applyAppearance() {
        let system = UIFontMetrics(forTextStyle: .body).scaledValue(for: 17) / 17
        model.apply(ReaderAppearance(theme: app.store.settings.theme, textScale: app.store.settings.textScale * Double(system)))
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
        // Non-modal: the article stays visible and live behind the panel (UX review r2).
        .presentationBackgroundInteraction(.enabled(upThrough: .height(300)))
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
                    EmptyStateView(icon: .toc, title: tr(.readerContents), message: tr(.readerContentsEmpty))
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

/// The reader's bottom bar (labelled buttons for older readers): back, contents, find, text size,
/// save. Games get no find and no text size; maps get no bar (a floating „Zpět“ instead).
@MainActor
private struct ReaderBottomBar: View {
    let model: ReaderModel
    let back: () -> Void
    let showContents: () -> Void
    let showTextSize: () -> Void
    let toggleSaved: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            item(.arrowBack, tr(.readerBarBack), enabled: true, action: back)
                .accessibilityIdentifier("reader.back")
            item(.toc, tr(.readerBarContents), enabled: model.book != nil, action: showContents)
            if !model.isWebApp {
                item(.findInPage, tr(.readerBarFind), enabled: true) { model.showFind() }
                item(.textSize, tr(.readerBarText), enabled: true, action: showTextSize)
            }
            item(model.isSaved ? .bookmarkFilled : .bookmark, tr(.readerSave), enabled: model.canSave, action: toggleSaved)
                .accessibilityValue(model.isSaved ? tr(.cdSavedOn) : tr(.cdSavedOff))
                .accessibilityIdentifier("reader.save")
        }
        .padding(.top, BrandSpace.xs)
        .background(Color.brandSurface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Color.brandLine).frame(height: 1) }
    }

    private func item(_ icon: BrandIcon, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                BrandIconView(icon: icon)
                Text(label)
                    .font(.custom("Archivo-SemiBold", size: 12.5, relativeTo: .caption2))
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(enabled ? Color.brandInk : Color.brandInk3)
            .frame(maxWidth: .infinity, minHeight: BrandSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.55)
        .accessibilityLabel(label)
    }
}
