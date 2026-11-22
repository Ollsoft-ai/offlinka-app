import SwiftUI

/// Start page: big search field, quick links, "continue reading" and a tile for every book.
@MainActor
struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var failedTile: ShelfTile?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                VStack(alignment: .leading, spacing: BrandSpace.xl) {
                    if let last = continueArticle {
                        ContinueCard(article: last.article, book: last.book) {
                            app.router.open(ReaderTarget(bookId: last.book.id, path: last.article.path))
                        }
                    }
                    if !app.tryNow.isEmpty {
                        TryNowCard(items: app.tryNow, books: app.books) { item in
                            app.router.open(ReaderTarget(bookId: item.bookId, path: item.path))
                        } onDismiss: {
                            app.dismissTryNow()
                        }
                    }
                    SectionTitle(text: tr(.homeBooksTitle), trailing: tr(.libraryBooksCount, app.shelf.tiles.count))
                    // The first book (the UI-language Wikipedia) gets a wide tile.
                    if let first = app.shelf.tiles.first {
                        BookTile(tile: first, wide: true) { failedTile = first }
                    }
                    LazyVGrid(columns: columns, alignment: .leading, spacing: BrandSpace.m) {
                        ForEach(app.shelf.tiles.dropFirst()) { tile in
                            BookTile(tile: tile) { failedTile = tile }
                        }
                    }
                }
                .padding(.horizontal, BrandSize.gutter)
                .padding(.bottom, BrandSpace.huge)
                .frame(maxWidth: 900)
            }
            .frame(maxWidth: .infinity)
        }
        .background(Color.brandBg.ignoresSafeArea())
        .heroToolbar(trailing: offlineBadge)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.brandHero, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .retryAlert(tile: $failedTile)
        // The card's links are checked against the books open now: again as more books open.
        .onChange(of: tryNowKey, initial: true) { _, _ in app.refreshTryNow() }
    }

    private var tryNowKey: String {
        "\(app.shelf.tiles.filter { $0.state == .open }.count)/\(app.store.settings.tryNowDismissed)/\(L10n.languageCode)"
    }

    /// One column at large text sizes, so expanded titles never break mid-word (UX review r2).
    private var columns: [GridItem] {
        if dynamicTypeSize >= .xxLarge && sizeClass != .regular {
            return [GridItem(.flexible(), spacing: BrandSpace.m, alignment: .top)]
        }
        return [GridItem(.adaptive(minimum: sizeClass == .regular ? 260 : 160), spacing: BrandSpace.m, alignment: .top)]
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: BrandSpace.l) {
            Text(tr(.homeTitle))
                .brandText(.display)
                .foregroundStyle(Color.brandOnHero)
                .accessibilityAddTraits(.isHeader)

            Button {
                app.router.push(.search(scopeBookId: nil))
            } label: {
                HStack(spacing: BrandSpace.m) {
                    BrandIconView(icon: .search, size: BrandSize.icon)
                        .foregroundStyle(Color.brandInk2)
                    Text(tr(.homeSearchPlaceholder))
                        .brandText(.searchInput)
                        .foregroundStyle(Color.brandInk3)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, BrandSpace.l)
                .frame(minHeight: BrandSize.searchField)
                .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandSurface))
                .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline))
                .compositingGroup()
                .hardShadow(BrandElevation.raised)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr(.homeSearchPlaceholder))
            .accessibilityIdentifier("home.search")

            if let note = app.searchNote {
                SearchNoteBox(note: note, onHero: true) { app.dismissSearchNote() }
            }

            quickLinks
        }
        .padding(.horizontal, BrandSize.gutter)
        .padding(.top, BrandSpace.s)
        .padding(.bottom, BrandSize.ridge * 2)
        .frame(maxWidth: 900, alignment: .leading)
        .frame(maxWidth: .infinity)
        .background(HeroBackground().ignoresSafeArea(edges: .horizontal))
    }

    private var offlineBadge: some View {
        HStack(spacing: BrandSpace.s) {
            Circle().fill(Color.brandOk).frame(width: 8, height: 8)
            Text(tr(.statusOffline))
                .brandText(.label)
                .foregroundStyle(Color.brandOnHero2)
        }
        .padding(.horizontal, BrandSpace.m)
        .padding(.vertical, BrandSpace.s)
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.s).strokeBorder(Color.brandHeroLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private var quickLinks: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BrandSpace.s) {
                if let guides = BookCatalog.guides(in: app.books) {
                    Button {
                        app.router.open(ReaderTarget(bookId: guides.id, path: BookCatalog.firstAidPath))
                    } label: {
                        Label { Text(tr(.homeQuickFirstAid)) } icon: { BrandIconView(icon: .firstAid, size: BrandSize.iconSmall) }
                    }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("home.firstAid")

                    Button {
                        app.router.open(ReaderTarget(bookId: guides.id, path: BookCatalog.prepareAtHomePath))
                    } label: {
                        Label { Text(tr(.homeQuickPrepare)) } icon: { BrandIconView(icon: .checklist, size: BrandSize.iconSmall) }
                    }
                    .buttonStyle(ChipButtonStyle())
                }
                if let wikipedia = BookCatalog.primaryWikipedia(in: app.books, languageCode: L10n.languageCode) {
                    Button {
                        openRandom(in: wikipedia)
                    } label: {
                        Label { Text(tr(.homeQuickRandom)) } icon: { BrandIconView(icon: .random, size: BrandSize.iconSmall) }
                    }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("home.random")
                }
            }
        }
    }

    private var continueArticle: (article: ArticleRef, book: BookRecord)? {
        guard let last = app.store.history.first, let book = app.content?.book(named: last.bookName) else { return nil }
        return (last, book)
    }

    private func openRandom(in book: BookRecord) {
        guard let content = app.content else { return }
        let bookId = book.id
        Task { @MainActor in
            let path = await Task.detached(priority: .userInitiated) { try? content.randomArticle(bookId: bookId) }.value
            if let path = path ?? nil {
                app.router.open(ReaderTarget(bookId: bookId, path: path))
            }
        }
    }
}

/// One book on the home screen; `wide` spans the whole row with the icon beside the text.
/// A book still opening is dimmed with „Otevírám…“; one that failed is dimmed with „Nejde otevřít“
/// and offers „Zkusit znovu“.
@MainActor
struct BookTile: View {
    @Environment(AppModel.self) private var app
    let tile: ShelfTile
    var wide = false
    var onFailedTap: () -> Void = {}

    var body: some View {
        Button(action: tap) {
            Group {
                if wide {
                    HStack(alignment: .top, spacing: BrandSpace.l) {
                        BookIconBox(kind: tile.kind)
                        texts
                        tag
                    }
                } else {
                    VStack(alignment: .leading, spacing: BrandSpace.s) {
                        HStack(alignment: .top) {
                            BookIconBox(kind: tile.kind)
                            Spacer(minLength: 0)
                            tag
                        }
                        texts
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(BrandSpace.l)
            .frame(maxWidth: .infinity, minHeight: wide ? 0 : 168, alignment: .topLeading)
            .brandCard(shadow: tile.state == .open)
        }
        .buttonStyle(.plain)
        .disabled(tile.state == .opening)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(tr(.cdOpenBook, BookCatalog.title(of: tile)))
        .accessibilityHint(subtitle)
        .accessibilityIdentifier("tile.\(tile.id)")
        .contextMenu {
            if let record = tile.record {
                Button(tr(.libraryDetails)) {
                    app.router.push(.book(record.id))
                }
                Button(tr(.libraryOpenMain)) {
                    app.router.open(ReaderTarget(bookId: record.id, path: nil))
                }
            }
        }
    }

    private func tap() {
        switch tile.state {
        case .open:
            if let record = tile.record {
                app.router.open(ReaderTarget(bookId: record.id, path: nil))
            }
        case .failed:
            onFailedTap()
        case .opening:
            break
        }
    }

    private var subtitle: String {
        switch tile.state {
        case .open: return BookCatalog.description(of: tile)
        case .opening: return tr(.libraryOpening)
        case .failed: return tr(.libraryOpenFailed)
        }
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: BrandSpace.xs) {
            Text(BookCatalog.title(of: tile))
                .brandText(wide ? .headline : .title)
                .foregroundStyle(Color.brandInk)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            switch tile.state {
            case .open:
                Text(subtitle)
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            case .opening:
                HStack(spacing: BrandSpace.s) {
                    ProgressView()
                        .controlSize(.small)
                    Text(subtitle)
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk2)
                }
            case .failed:
                // Full contrast, not dimmed like "opening" (UX review r2).
                HStack(alignment: .firstTextBaseline, spacing: BrandSpace.xs) {
                    BrandIconView(icon: .warning, size: 16)
                        .foregroundStyle(Color.brandWarnInk)
                        .padding(3)
                        .background(Circle().fill(Color.brandWarnBg))
                    Text(subtitle)
                        .brandText(.label)
                        .foregroundStyle(Color.brandWarnInk)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var tag: some View {
        if let tag = BookCatalog.languageTag(kind: tile.kind) {
            TagView(text: tag)
        }
    }
}

extension View {
    /// A book that could not be opened: the calm error and „Zkusit znovu“ (Home and Library), as a
    /// branded card instead of a stock alert (UX review r2).
    @MainActor
    func retryAlert(tile: Binding<ShelfTile?>) -> some View {
        sheet(item: tile) { failed in
            RetryCard(tile: failed)
                .presentationDetents([.height(280), .medium])
                .presentationDragIndicator(.visible)
        }
    }
}

@MainActor
private struct RetryCard: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let tile: ShelfTile

    var body: some View {
        VStack(alignment: .leading, spacing: BrandSpace.l) {
            HStack(spacing: BrandSpace.m) {
                BookIconBox(kind: tile.kind, box: BrandSize.bookIconBoxSmall)
                VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                    Text(tr(.errorTitle))
                        .brandText(.title)
                        .foregroundStyle(Color.brandInk)
                        .accessibilityAddTraits(.isHeader)
                    Text(BookCatalog.title(of: tile))
                        .brandText(.label)
                        .foregroundStyle(Color.brandInk2)
                }
            }
            Text(tr(.errorBody))
                .brandText(.body)
                .foregroundStyle(Color.brandInk)
                .fixedSize(horizontal: false, vertical: true)
            Button(tr(.actionRetry)) {
                app.retryBook(tile.id)
                dismiss()
            }
            .buttonStyle(PrimaryButtonStyle())
            Button(tr(.actionClose)) { dismiss() }
                .buttonStyle(SecondaryButtonStyle())
        }
        .padding(BrandSize.gutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.brandSurface.ignoresSafeArea())
    }
}

/// "Pokračovat ve čtení": the last article from the history.
struct ContinueCard: View {
    let article: ArticleRef
    let book: BookRecord
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: BrandSpace.m) {
                BookIconBox(kind: BookCatalog.kind(of: book), box: BrandSize.bookIconBoxSmall)
                VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                    Text(tr(.homeContinue))
                        .brandText(.label)
                        .foregroundStyle(Color.brandInk2)
                    Text(article.title)
                        .brandText(.subhead)
                        .foregroundStyle(Color.brandInk)
                        .lineLimit(2)
                    Text(BookCatalog.title(of: book))
                        .brandText(.caption)
                        .foregroundStyle(Color.brandInk2)
                }
                Spacer(minLength: 0)
                BrandIconView(icon: .chevronRight, size: BrandSize.iconSmall)
                    .foregroundStyle(Color.brandInk2)
            }
            .padding(BrandSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandSurface))
            .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandLine, lineWidth: BrandSize.outline))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// „Vyzkoušejte hned“: four links that work right away (first run, until closed; Android `TryNowCard`).
struct TryNowCard: View {
    let items: [TryNowItem]
    let books: [BookRecord]
    let open: (TryNowItem) -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(tr(.tryTitle))
                    .brandText(.title)
                    .foregroundStyle(Color.brandInk)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                Button(action: onDismiss) {
                    BrandIconView(icon: .close, size: BrandSize.iconSmall)
                        .foregroundStyle(Color.brandInk2)
                        .frame(width: BrandSize.touch, height: BrandSize.touch)
                }
                .accessibilityLabel(tr(.actionClose))
            }
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                if index > 0 {
                    Rectangle().fill(Color.brandLineSoft).frame(height: 1)
                        .padding(.leading, BrandSize.bookIconBoxSmall + BrandSpace.l)
                        .padding(.trailing, BrandSpace.m)
                }
                Button {
                    open(item)
                } label: {
                    HStack(spacing: BrandSpace.l) {
                        BookIconBox(kind: kind(of: item), box: BrandSize.bookIconBoxSmall)
                        Text(label(of: item))
                            .brandText(.subhead)
                            .foregroundStyle(Color.brandInk)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        BrandIconView(icon: .chevronRight, size: BrandSize.iconSmall)
                            .foregroundStyle(Color.brandInk2)
                    }
                    .padding(.trailing, BrandSpace.s)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.try.\(String(describing: item.kind))")
            }
        }
        .padding(.leading, BrandSpace.l)
        .padding(.trailing, BrandSpace.xs)
        .padding(.top, BrandSpace.xs)
        .padding(.bottom, BrandSpace.s)
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandSurface))
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandLine, lineWidth: BrandSize.outline))
    }

    private func label(of item: TryNowItem) -> String {
        switch item.kind {
        case .article: return item.label
        case .map: return tr(.tryMap)
        case .firstAid: return tr(.tryFirstAid)
        case .game: return tr(.tryGame)
        }
    }

    /// The badge of the book the link opens (Wikipedia, map, guides, games).
    private func kind(of item: TryNowItem) -> BookKind {
        if let book = books.first(where: { $0.id == item.bookId }) { return BookCatalog.kind(of: book) }
        switch item.kind {
        case .article: return .wikipediaCs
        case .map: return .mapsCz
        case .firstAid: return .guides
        case .game: return .games
        }
    }
}

#if DEBUG
#Preview("Domů") {
    NavigationStack { HomeView() }
        .environment(AppModel.preview())
}
#endif

