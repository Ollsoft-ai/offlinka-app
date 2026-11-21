import SwiftUI

/// Every book on the drive, grouped; the phone editions the big ones replace are listed last.
@MainActor
struct LibraryView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.xs) {
                    Text(tr(.librarySubtitle))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                    HStack(spacing: BrandSpace.s) {
                        Text(tr(.libraryBooksCount, app.books.count))
                        if let edition = app.edition {
                            Text(verbatim: "·")
                            Text(tr(.libraryDriveEdition, edition))
                        }
                    }
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk2)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: BrandSpace.s, leading: BrandSize.gutter, bottom: BrandSpace.s, trailing: BrandSize.gutter))
            }

            ForEach(LibraryGroup.allCases, id: \.self) { group in
                let books = app.books.filter { BookCatalog.kind(of: $0).libraryGroup == group }
                if !books.isEmpty {
                    Section(tr(group.titleKey)) {
                        ForEach(books) { book in
                            NavigationLink(value: Route.book(book.id)) {
                                BookRow(book: book)
                            }
                        }
                    }
                    .listRowBackground(Color.brandSurface)
                }
            }

            if !app.shadowed.isEmpty {
                Section {
                    ForEach(app.shadowed, id: \.self) { entry in
                        ShadowedRow(entry: entry)
                    }
                } header: {
                    Text(tr(.libraryGroupPhone))
                } footer: {
                    Text(tr(.libraryGroupPhoneNote))
                }
                .listRowBackground(Color.brandSurface)
            }

            if !app.isDemo {
                Section {
                    Button(tr(.libraryChangeDrive)) {
                        app.chooseAnotherDrive()
                    }
                    .foregroundStyle(Color.brandLink)
                }
                .listRowBackground(Color.brandSurface)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.libraryTitle))
    }
}

/// Title, size, language and content date of a book.
struct BookRow: View {
    let book: BookRecord

    var body: some View {
        HStack(spacing: BrandSpace.m) {
            BookIconBox(kind: BookCatalog.kind(of: book), box: BrandSize.bookIconBoxSmall)
            VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                Text(BookCatalog.title(of: book))
                    .brandText(.bodyStrong)
                    .foregroundStyle(Color.brandInk)
                Text(meta)
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk2)
            }
        }
        .frame(minHeight: BrandSize.listRow - 16)
        .accessibilityElement(children: .combine)
    }

    private var meta: String {
        [
            tr(.librarySize, BookCatalog.gigabytes(book.sizeBytes)),
            BookCatalog.languageName(book.language),
            ContentDate.monthYear(book.date),
        ]
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
    }
}

/// A phone edition that is not used because the big edition is on the drive.
private struct ShadowedRow: View {
    let entry: DriveEntry

    var body: some View {
        HStack(spacing: BrandSpace.m) {
            BookIconBox(kind: BookKind.of(name: entry.name.baseKey), box: BrandSize.bookIconBoxSmall)
                .opacity(0.5)
            VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                Text(BookCatalog.title(ofEntry: entry))
                    .brandText(.bodyStrong)
                    .foregroundStyle(Color.brandInk2)
                Text(tr(.librarySize, BookCatalog.gigabytes(entry.sizeBytes)) + " · " + tr(.libraryReplaced))
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk3)
            }
        }
        .frame(minHeight: BrandSize.listRow - 16)
        .accessibilityElement(children: .combine)
    }
}

/// Details of one book, with open / random / search.
@MainActor
struct BookDetailView: View {
    @Environment(AppModel.self) private var app
    let bookId: String

    var body: some View {
        if let book = app.content?.book(id: bookId) {
            content(book)
        } else {
            EmptyStateView(icon: .driveOff, title: tr(.savedUnavailable), message: tr(.unpluggedBody))
        }
    }

    private func content(_ book: BookRecord) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.m) {
                    BookIconBox(kind: BookCatalog.kind(of: book), box: 56)
                    Text(BookCatalog.title(of: book))
                        .brandText(.headline)
                        .foregroundStyle(Color.brandInk)
                    Text(BookCatalog.description(of: book))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                    Button(tr(.libraryOpenMain)) {
                        app.router.open(ReaderTarget(bookId: book.id, path: nil))
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, BrandSpace.s)
                    HStack(spacing: BrandSpace.s) {
                        Button(tr(.cdSearch)) {
                            app.router.push(.search(scopeBookId: book.id))
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        Button(tr(.homeQuickRandom)) {
                            openRandom(book)
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                }
                .padding(.vertical, BrandSpace.s)
                .listRowBackground(Color.clear)
            }

            Section(tr(.libraryDetails)) {
                detail(.libraryDetailArticles, BookCatalog.formattedCount(book.articleCount))
                if book.mediaCount > 0 {
                    detail(.libraryDetailMedia, BookCatalog.formattedCount(book.mediaCount))
                }
                detail(.libraryDetailSize, tr(.librarySize, BookCatalog.gigabytes(book.sizeBytes)))
                detail(.libraryDetailDate, ContentDate.monthYear(book.date))
                detail(.libraryDetailLanguage, BookCatalog.languageName(book.language))
                detail(.libraryDetailSearch, book.hasIndex ? tr(.librarySearchIndexed) : tr(.librarySearchBasic))
                if !book.publisher.isEmpty {
                    detail(.libraryDetailPublisher, book.publisher)
                }
                detail(.libraryDetailFolder, book.folder.rawValue)
                detail(.libraryDetailFile, book.fileName)
            }
            .listRowBackground(Color.brandSurface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(BookCatalog.title(of: book))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detail(_ label: Str, _ value: String) -> some View {
        LabeledContent {
            Text(value)
                .brandText(.body)
                .foregroundStyle(Color.brandInk)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        } label: {
            Text(tr(label))
                .brandText(.body)
                .foregroundStyle(Color.brandInk2)
        }
    }

    private func openRandom(_ book: BookRecord) {
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

#Preview("Knihovna") {
    NavigationStack { LibraryView() }
        .environment(AppModel.preview())
}

#Preview("Detail knihy") {
    NavigationStack { BookDetailView(bookId: FakeContentSource.demoWikipediaId) }
        .environment(AppModel.preview())
}
