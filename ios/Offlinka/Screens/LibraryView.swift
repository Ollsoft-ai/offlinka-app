import SwiftUI

/// Every book on the drive (design mockup `library`): drive card, groups, phone editions last.
/// Row tap opens the book's main page; the info button shows the details.
@MainActor
struct LibraryView: View {
    @Environment(AppModel.self) private var app
    @State private var details: BookRecord?

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.l) {
                    Text(tr(.librarySubtitle))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                    DriveCard()
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: BrandSpace.s, trailing: 0))
            }

            ForEach(LibraryGroup.allCases, id: \.self) { group in
                let books = app.books.filter { BookCatalog.kind(of: $0).libraryGroup == group }
                if !books.isEmpty {
                    Section(tr(group.titleKey)) {
                        ForEach(books) { book in
                            BookRow(book: book, onOpen: {
                                app.router.open(ReaderTarget(bookId: book.id, path: nil))
                            }, onInfo: {
                                details = book
                            })
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
        .sheet(item: $details) { book in
            NavigationStack {
                BookDetailView(bookId: book.id, inSheet: true)
            }
            .presentationDetents([.medium, .large])
        }
    }
}

/// Forest card with the drive's name, edition and size.
@MainActor
private struct DriveCard: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        HStack(alignment: .top, spacing: BrandSpace.l) {
            BrandIconView(icon: .drive, size: BrandSize.icon)
                .foregroundStyle(Color.brandOnHero)
                .frame(width: BrandSize.bookIconBox, height: BrandSize.bookIconBox)
                .overlay(RoundedRectangle(cornerRadius: BrandRadius.s).strokeBorder(Color.brandHeroLine, lineWidth: 1))
            VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                Text(verbatim: app.driveName ?? "OFFLINKA")
                    .font(.custom("ArchivoExpanded-ExtraBold", size: 17, relativeTo: .headline))
                    .foregroundStyle(Color.brandOnHero)
                if let edition = app.edition {
                    Text(tr(.libraryDriveEdition, ContentDate.monthYear(edition)))
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandOnHero2)
                }
                Text(tr(.libraryBooksCount, app.books.count) + ", " + tr(.librarySize, BookCatalog.gigabytes(totalBytes, significantDigits: 2)))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandOnHero2)
            }
            Spacer(minLength: 0)
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandHero))
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline))
        .hardShadow(BrandElevation.card)
        .accessibilityElement(children: .combine)
    }

    private var totalBytes: Int64 {
        app.books.reduce(0) { $0 + $1.sizeBytes }
    }
}

/// Icon box, name, description and "month year, language, size"; info button for the details.
private struct BookRow: View {
    let book: BookRecord
    let onOpen: () -> Void
    let onInfo: () -> Void

    var body: some View {
        HStack(spacing: BrandSpace.m) {
            Button(action: onOpen) {
                HStack(spacing: BrandSpace.m) {
                    BookIconBox(kind: BookCatalog.kind(of: book), box: BrandSize.bookIconBoxSmall)
                    VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                        Text(BookCatalog.title(of: book))
                            .brandText(.titleSmall)
                            .foregroundStyle(Color.brandInk)
                        Text(BookCatalog.description(of: book))
                            .brandText(.bodySmall)
                            .foregroundStyle(Color.brandInk2)
                        Text(BookCatalog.metaLine(of: book))
                            .brandText(.caption)
                            .foregroundStyle(Color.brandInk2)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: BrandSize.listRow)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(tr(.libraryOpenMain))

            Button(action: onInfo) {
                BrandIconView(icon: .info, size: BrandSize.iconSmall)
                    .foregroundStyle(Color.brandInk2)
                    .frame(width: BrandSize.touch, height: BrandSize.touch)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr(.libraryDetails))
        }
    }
}

/// A phone edition that is not used because the big edition is on the drive.
private struct ShadowedRow: View {
    let entry: DriveEntry

    var body: some View {
        HStack(spacing: BrandSpace.m) {
            BookIconBox(kind: BookKind.of(name: entry.name.baseKey), box: BrandSize.bookIconBoxSmall)
            VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                Text(BookCatalog.title(ofEntry: entry))
                    .brandText(.titleSmall)
                    .foregroundStyle(Color.brandInk2)
                Text(tr(.libraryReplaced) + ", " + tr(.librarySize, BookCatalog.gigabytes(entry.sizeBytes)))
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk3)
            }
        }
        .opacity(0.6)
        .frame(minHeight: BrandSize.listRow - 8)
        .accessibilityElement(children: .combine)
    }
}

/// Details of one book: key/value list and „Otevřít úvodní stránku“. Shown as a sheet from the
/// library, or pushed (home tile menu).
@MainActor
struct BookDetailView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let bookId: String
    var inSheet = false

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
                HStack(alignment: .top, spacing: BrandSpace.m) {
                    BookIconBox(kind: BookCatalog.kind(of: book))
                    VStack(alignment: .leading, spacing: BrandSpace.xs) {
                        Text(BookCatalog.title(of: book))
                            .brandText(.title)
                            .foregroundStyle(Color.brandInk)
                        Text(BookCatalog.description(of: book))
                            .brandText(.bodySmall)
                            .foregroundStyle(Color.brandInk2)
                    }
                }
                .listRowBackground(Color.clear)
            }

            Section {
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

            Section {
                Button(tr(.libraryOpenMain)) {
                    open(ReaderTarget(bookId: book.id, path: nil))
                }
                .buttonStyle(PrimaryButtonStyle())
                Button(tr(.cdSearch)) {
                    if inSheet { dismiss() }
                    app.router.push(.search(scopeBookId: book.id))
                }
                .buttonStyle(SecondaryButtonStyle())
                Button(tr(.homeQuickRandom)) {
                    openRandom(book)
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: BrandSpace.xs, leading: 0, bottom: BrandSpace.xs, trailing: 0))
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.libraryDetails))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if inSheet {
                    Button(tr(.actionDone)) { dismiss() }
                }
            }
        }
    }

    private func detail(_ label: Str, _ value: String) -> some View {
        LabeledContent {
            Text(value)
                .brandText(.bodyStrong)
                .foregroundStyle(Color.brandInk)
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .textSelection(.enabled)
        } label: {
            Text(tr(label))
                .brandText(.body)
                .foregroundStyle(Color.brandInk2)
        }
    }

    private func open(_ target: ReaderTarget) {
        if inSheet { dismiss() }
        app.router.open(target)
    }

    private func openRandom(_ book: BookRecord) {
        guard let content = app.content else { return }
        let bookId = book.id
        Task { @MainActor in
            let path = await Task.detached(priority: .userInitiated) { try? content.randomArticle(bookId: bookId) }.value
            if let path = path ?? nil {
                open(ReaderTarget(bookId: bookId, path: path))
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
