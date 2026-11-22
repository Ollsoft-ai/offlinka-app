import SwiftUI
import UniformTypeIdentifiers

/// Every book on the drive (design mockup `library`): drive card, groups, phone editions last.
/// Row tap opens the book's main page; the info button shows the details.
@MainActor
struct LibraryView: View {
    @Environment(AppModel.self) private var app
    @State private var details: BookRecord?
    @State private var failedTile: ShelfTile?
    @State private var showPicker = false

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: BrandSpace.l) {
                    Text(tr(.librarySubtitle))
                        .brandText(.body)
                        .foregroundStyle(Color.brandInk2)
                        // The row clips at its edge: room for the overhang of Archivo's V and E.
                        .padding(.leading, 2)
                    DriveCard()
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: BrandSpace.s, trailing: 0))
            }

            ForEach(LibraryGroup.allCases, id: \.self) { group in
                let tiles = app.shelf.tiles.filter { $0.kind.libraryGroup == group }
                if !tiles.isEmpty {
                    Section(tr(group.titleKey)) {
                        ForEach(tiles) { tile in
                            if let record = tile.record, tile.state == .open {
                                BookRow(book: record, onOpen: {
                                    app.router.open(ReaderTarget(bookId: record.id, path: nil))
                                }, onInfo: {
                                    details = record
                                })
                            } else {
                                PendingRow(tile: tile) { failedTile = tile }
                            }
                        }
                    }
                    .listRowBackground(Color.brandSurface)
                }
            }

            if !app.shelf.shadowed.isEmpty {
                Section {
                    ForEach(app.shelf.shadowed, id: \.self) { entry in
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
                        showPicker = true
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
        .retryAlert(tile: $failedTile)
        // Another drive while these books stay open; a wrong pick only shows a short message.
        .fileImporter(isPresented: $showPicker, allowedContentTypes: [.folder]) { result in
            app.didPick(result)
        }
    }
}

/// A book that is still opening, or failed to open (tap: „Zkusit znovu“).
private struct PendingRow: View {
    let tile: ShelfTile
    let onFailedTap: () -> Void

    var body: some View {
        Button(action: onFailedTap) {
            HStack(spacing: BrandSpace.m) {
                BookIconBox(kind: tile.kind, box: BrandSize.bookIconBoxSmall)
                VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                    Text(BookCatalog.title(of: tile))
                        .brandText(.titleSmall)
                        .foregroundStyle(Color.brandInk)
                    HStack(spacing: BrandSpace.s) {
                        if tile.state == .opening {
                            ProgressView()
                                .controlSize(.small)
                        }
                        if tile.state == .failed {
                            BrandIconView(icon: .warning, size: 16)
                                .foregroundStyle(Color.brandWarnInk)
                        }
                        Text(tile.state == .opening ? tr(.libraryOpening) : tr(.libraryOpenFailed))
                            .brandText(tile.state == .failed ? .label : .bodySmall)
                            .foregroundStyle(tile.state == .failed ? Color.brandWarnInk : Color.brandInk2)
                    }
                }
                Spacer(minLength: 0)
                if tile.state == .failed {
                    BrandIconView(icon: .retry, size: BrandSize.iconSmall)
                        .foregroundStyle(Color.brandInk2)
                }
            }
            .frame(minHeight: BrandSize.listRow)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(tile.state != .failed)
        .accessibilityElement(children: .combine)
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
                // Like Android: every book in the library (Home counts the same), all ZIM files of the drive.
                Text(tr(.libraryBooksCount, app.shelf.tiles.count) + ", " + BookCatalog.formatSize(app.shelf.driveBytes))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandOnHero2)
            }
            Spacer(minLength: 0)
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandHero))
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline))
        .compositingGroup()
        .hardShadow(BrandElevation.card)
        .accessibilityElement(children: .combine)
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

/// A phone edition that is not used because the big edition is on the drive (full contrast and a
/// tag instead of dimming, UX review).
private struct ShadowedRow: View {
    let entry: DriveEntry

    var body: some View {
        HStack(spacing: BrandSpace.m) {
            BookIconBox(kind: BookKind.of(name: entry.name.baseKey), box: BrandSize.bookIconBoxSmall)
            VStack(alignment: .leading, spacing: BrandSpace.xs) {
                Text(BookCatalog.title(ofEntry: entry))
                    .brandText(.titleSmall)
                    .foregroundStyle(Color.brandInk)
                Text(BookCatalog.formatSizeShort(entry.sizeBytes))
                    .brandText(.caption)
                    .foregroundStyle(Color.brandInk2)
                TagView(text: tr(.libraryReplaced))
            }
        }
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
                detail(.libraryDetailSize, BookCatalog.formatSize(book.sizeBytes))
                detail(.libraryDetailDate, ContentDate.monthYear(book.date))
                detail(.libraryDetailLanguage, BookCatalog.languageName(book.language))
            }
            .listRowBackground(Color.brandSurface)

            if let phase = app.shelf.indexPhase(of: book) {
                indexSection(book, phase: phase)
            }

            // Things only support needs, folded away (UX review).
            Section {
                DisclosureGroup(tr(.librarySupport)) {
                    detail(.libraryDetailSearch, book.hasIndex ? tr(.librarySearchIndexed) : tr(.librarySearchBasic))
                    if !book.publisher.isEmpty {
                        detail(.libraryDetailPublisher, book.publisher)
                    }
                    if book.folder != .app {
                        detail(.libraryDetailFolder, book.folder.rawValue)
                    }
                    detail(.libraryDetailFile, book.fileName)
                }
                .tint(Color.brandInk2)
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

    /// The book's own search index (Android `IndexBlock`): the heavy-book offer, queued, preparing with
    /// „Zastavit přípravu“, or low space.
    @ViewBuilder
    private func indexSection(_ book: BookRecord, phase: IndexPhase) -> some View {
        Section(tr(.libraryIndexOfferTitle)) {
            VStack(alignment: .leading, spacing: BrandSpace.m) {
                Text(indexBody(phase))
                    .brandText(.body)
                    .foregroundStyle(phase == .lowSpace ? Color.brandWarnInk : Color.brandInk2)
                    .fixedSize(horizontal: false, vertical: true)
                if case .building(let percent) = phase {
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.brandLine)
                            Capsule().fill(Color.brandPrimary)
                                .frame(width: geometry.size.width * CGFloat(min(max(percent, 0), 100)) / 100)
                        }
                    }
                    .frame(height: 4)
                    .accessibilityHidden(true)
                }
                switch phase {
                case .offer, .lowSpace:
                    Button(tr(.libraryIndexOfferButton)) {
                        app.shelf.buildIndexNow(book)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                case .queued, .building:
                    Button(tr(.indexStop)) {
                        app.shelf.cancelIndexing(book)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("library.indexStop")
                }
            }
            .padding(.vertical, BrandSpace.xs)
        }
        .listRowBackground(Color.brandSurface)
    }

    private func indexBody(_ phase: IndexPhase) -> String {
        switch phase {
        case .offer: return tr(.libraryIndexOfferBody)
        case .queued: return tr(.indexQueued) + ". " + tr(.indexQueuedBody)
        case .building(let percent): return tr(.settingsTextSizeValue, percent) + " · " + tr(.indexKeepOpen)
        case .lowSpace: return tr(.indexLowSpaceBody)
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

#if DEBUG
#Preview("Knihovna") {
    NavigationStack { LibraryView() }
        .environment(AppModel.preview())
}
#endif

#if DEBUG
#Preview("Detail knihy") {
    NavigationStack { BookDetailView(bookId: FakeContentSource.demoWikipediaId) }
        .environment(AppModel.preview())
}
#endif
