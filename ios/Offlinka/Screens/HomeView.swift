import SwiftUI

/// Start page: big search field, quick links, "continue reading" and a tile for every book.
@MainActor
struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass

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
                    SectionTitle(text: tr(.homeBooksTitle))
                    LazyVGrid(columns: columns, alignment: .leading, spacing: BrandSpace.m) {
                        ForEach(app.books) { book in
                            BookTile(book: book)
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
        .toolbar {
            ToolbarItem(placement: .principal) {
                Wordmark()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.brandHero, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: sizeClass == .regular ? 260 : 160), spacing: BrandSpace.m, alignment: .top)]
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: BrandSpace.l) {
            HStack(spacing: BrandSpace.xs) {
                Circle().fill(Color.brandOk).frame(width: 8, height: 8)
                Text(tr(.statusOffline))
                    .brandText(.labelSmall)
                    .textCase(.uppercase)
                    .foregroundStyle(Color.brandOnHero2)
            }
            .padding(.horizontal, BrandSpace.s)
            .padding(.vertical, BrandSpace.xs)
            .overlay(RoundedRectangle(cornerRadius: BrandRadius.xs).strokeBorder(Color.brandHeroLine, lineWidth: 1))
            .accessibilityElement(children: .combine)

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
                .brandShadow(BrandElevation.raised)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr(.homeSearchPlaceholder))
            .accessibilityIdentifier("home.search")

            quickLinks
        }
        .padding(.horizontal, BrandSize.gutter)
        .padding(.top, BrandSpace.xl)
        .padding(.bottom, BrandSize.ridge * 2)
        .frame(maxWidth: 900, alignment: .leading)
        .frame(maxWidth: .infinity)
        .background(HeroBackground().ignoresSafeArea(edges: .horizontal))
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

/// One book on the home screen.
@MainActor
struct BookTile: View {
    @Environment(AppModel.self) private var app
    let book: BookRecord

    var body: some View {
        let kind = BookCatalog.kind(of: book)
        Button {
            app.router.open(ReaderTarget(bookId: book.id, path: nil))
        } label: {
            VStack(alignment: .leading, spacing: BrandSpace.s) {
                BookIconBox(kind: kind)
                Text(BookCatalog.title(of: book))
                    .brandText(.titleSmall)
                    .foregroundStyle(Color.brandInk)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Text(BookCatalog.description(of: book))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(BrandSpace.m)
            .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
            .brandCard()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(tr(.cdOpenBook, BookCatalog.title(of: book)))
        .accessibilityHint(BookCatalog.description(of: book))
        .accessibilityIdentifier("tile.\(book.id)")
        .contextMenu {
            Button(tr(.libraryDetails)) {
                app.router.push(.book(book.id))
            }
            Button(tr(.libraryOpenMain)) {
                app.router.open(ReaderTarget(bookId: book.id, path: nil))
            }
        }
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
                        .brandText(.labelSmall)
                        .textCase(.uppercase)
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
            .brandCard()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Domů") {
    NavigationStack { HomeView() }
        .environment(AppModel.preview())
}
