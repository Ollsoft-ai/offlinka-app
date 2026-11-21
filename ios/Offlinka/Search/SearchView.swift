import SwiftUI

/// Search while typing, grouped by book; recent searches when the field is empty.
@MainActor
struct SearchView: View {
    @Environment(AppModel.self) private var app
    @State private var model: SearchModel
    @FocusState private var focused: Bool

    init(source: ContentSource, scopeBookId: String?) {
        _model = State(initialValue: SearchModel(source: source, scopeBookId: scopeBookId))
    }

    var body: some View {
        VStack(spacing: 0) {
            searchField
                .padding(.horizontal, BrandSize.gutter)
                .padding(.vertical, BrandSpace.s)
            Divider().overlay(Color.brandLineSoft)
            results
        }
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(scopeTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { focused = true }
    }

    private var scopeTitle: String? {
        model.scopeBookId.flatMap { app.content?.book(id: $0) }.map(BookCatalog.title(of:))
    }

    // MARK: - Field

    private var searchField: some View {
        HStack(spacing: BrandSpace.s) {
            BrandIconView(icon: .search, size: BrandSize.iconSmall)
                .foregroundStyle(Color.brandInk2)
            TextField(scopeTitle ?? tr(.searchPlaceholder), text: $model.query)
                .brandText(.searchInput)
                .foregroundStyle(Color.brandInk)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit(openTopHit)
                .accessibilityIdentifier("search.field")
            if !model.query.isEmpty {
                Button {
                    model.query = ""
                    focused = true
                } label: {
                    BrandIconView(icon: .close, size: BrandSize.iconSmall)
                        .foregroundStyle(Color.brandInk2)
                        .frame(width: BrandSize.touch, height: BrandSize.touch)
                }
                .accessibilityLabel(tr(.searchClearQuery))
            }
        }
        .padding(.leading, BrandSpace.m)
        .frame(minHeight: BrandSize.searchField)
        .background(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).fill(Color.brandSurface))
        .overlay(RoundedRectangle(cornerRadius: BrandRadius.m, style: .continuous).strokeBorder(Color.brandOutline, lineWidth: BrandSize.outline))
    }

    // MARK: - Results

    @ViewBuilder
    private var results: some View {
        switch model.phase {
        case .idle:
            recent
        case .empty:
            EmptyStateView(icon: .search, title: tr(.searchNoResultsTitle), message: tr(.searchNoResultsBody))
            Spacer()
        case .searching where model.groups.isEmpty:
            ProgressView()
                .padding(BrandSpace.xxl)
            Spacer()
        case .searching, .results:
            List {
                ForEach(model.groups) { group in
                    Section {
                        let visible = group.isExpanded ? group.hits : Array(group.hits.prefix(SearchModel.collapsedCount))
                        ForEach(visible, id: \.self) { hit in
                            HitRow(hit: hit, query: model.trimmedQuery, onOpen: { open(hit) }, onFill: { model.query = hit.title })
                        }
                        if !group.isExpanded && group.hits.count >= SearchModel.collapsedCount {
                            Button(tr(.searchShowMore)) {
                                Task { await model.expand(group) }
                            }
                            .brandText(.label)
                            .foregroundStyle(Color.brandLink)
                            .frame(minHeight: BrandSize.touch)
                        }
                    } header: {
                        HStack(spacing: BrandSpace.m) {
                            BookIconBox(kind: BookCatalog.kind(of: group.book), box: 26)
                            Text(BookCatalog.title(of: group.book))
                                .brandText(.label)
                                .foregroundStyle(Color.brandInk2)
                        }
                        .textCase(nil)
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isHeader)
                    }
                    .listRowBackground(Color.brandBg)
                    .listRowSeparatorTint(Color.brandLine)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    @ViewBuilder
    private var recent: some View {
        List {
            if !app.store.recentSearches.isEmpty {
                Section {
                    ForEach(app.store.recentSearches, id: \.self) { query in
                        Button {
                            model.query = query
                        } label: {
                            HStack(spacing: BrandSpace.m) {
                                BrandIconView(icon: .clock, size: BrandSize.iconSmall)
                                    .foregroundStyle(Color.brandInk3)
                                Text(query)
                                    .brandText(.body)
                                    .foregroundStyle(Color.brandInk)
                            }
                            .frame(minHeight: BrandSize.touch - 8)
                        }
                    }
                } header: {
                    HStack {
                        Text(tr(.searchRecentTitle))
                        Spacer()
                        Button(tr(.actionClear)) {
                            app.store.clearRecentSearches()
                        }
                        .textCase(nil)
                    }
                }
                .listRowBackground(Color.brandSurface)
            }
            Section {
                Text(tr(.searchTip))
                    .brandText(.bodySmall)
                    .foregroundStyle(Color.brandInk2)
            }
            .listRowBackground(Color.clear)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Actions

    private func openTopHit() {
        guard let hit = model.topHit else { return }
        open(hit)
    }

    private func open(_ hit: SearchHit) {
        app.store.addRecentSearch(model.query)
        focused = false
        app.router.open(ReaderTarget(bookId: hit.bookId, path: hit.path))
    }
}

private struct HitRow: View {
    let hit: SearchHit
    let query: String
    let onOpen: () -> Void
    let onFill: () -> Void

    var body: some View {
        HStack(spacing: BrandSpace.s) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                    Self.highlighted(hit.title, query: query)
                        .lineLimit(2)
                    if let snippet = hit.snippet, !snippet.isEmpty {
                        HStack(spacing: BrandSpace.xs) {
                            BrandIconView(icon: .redirect, size: 16)
                            Text(snippet)
                        }
                        .brandText(.bodySmall)
                        .foregroundStyle(Color.brandInk2)
                    }
                }
                .padding(.leading, 26 + BrandSpace.m)
                .frame(maxWidth: .infinity, minHeight: BrandSize.touch, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("search.hit")

            Button(action: onFill) {
                BrandIconView(icon: .fillQuery, size: BrandSize.iconSmall)
                    .foregroundStyle(Color.brandInk2)
                    .frame(width: BrandSize.touch, height: BrandSize.touch)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr(.searchFill))
        }
    }

    /// The title with the part that matched the query in bold (diacritics-insensitive), the rest regular.
    static func highlighted(_ title: String, query: String) -> Text {
        let regular = Font.custom("Archivo-Regular", size: 17, relativeTo: .body)
        let bold = Font.custom("Archivo-Bold", size: 17, relativeTo: .body)
        guard !query.isEmpty,
              let match = title.range(of: query, options: [.caseInsensitive, .diacriticInsensitive])
        else {
            return Text(title).font(bold).foregroundColor(Color.brandInk)
        }
        let before = Text(String(title[..<match.lowerBound])).font(regular).foregroundColor(Color.brandInk2)
        let matched = Text(String(title[match])).font(bold).foregroundColor(Color.brandInk)
        let after = Text(String(title[match.upperBound...])).font(regular).foregroundColor(Color.brandInk2)
        return before + matched + after
    }
}

#Preview("Hledání") {
    NavigationStack { SearchView(source: FakeContentSource.demo(), scopeBookId: nil) }
        .environment(AppModel.preview())
}
