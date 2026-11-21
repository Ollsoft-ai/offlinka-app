import SwiftUI

/// Saved articles and the history (design mockups `saved`, `history`).
@MainActor
struct SavedView: View {
    @Environment(AppModel.self) private var app
    @State private var confirmClear = false

    var body: some View {
        @Bindable var router = app.router
        VStack(spacing: 0) {
            Picker(tr(.savedTitle), selection: $router.savedShowsHistory) {
                Text(tr(.savedTabSaved)).tag(false)
                Text(tr(.savedTabHistory)).tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, BrandSize.gutter)
            .padding(.vertical, BrandSpace.s)

            if router.savedShowsHistory {
                history
            } else {
                saved
            }
        }
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.savedTitle))
        .confirmationDialog(tr(.historyClearConfirmTitle), isPresented: $confirmClear, titleVisibility: .visible) {
            Button(tr(.historyClear), role: .destructive) {
                app.store.clearHistory()
            }
            Button(tr(.actionCancel), role: .cancel) {}
        } message: {
            Text(tr(.historyClearConfirmBody))
        }
    }

    // MARK: - Saved

    @ViewBuilder
    private var saved: some View {
        if app.store.saved.isEmpty {
            EmptyStateView(icon: .bookmark, title: tr(.savedEmptyTitle), message: tr(.savedEmptyBody))
            Spacer()
        } else {
            List {
                ForEach(app.store.saved) { article in
                    ArticleRow(article: article, book: app.content?.book(named: article.bookName), open: { open(article) }) {
                        Button {
                            remove(article)
                        } label: {
                            BrandIconView(icon: .bookmarkFilled)
                                .foregroundStyle(Color.brandAccent)
                                .frame(width: BrandSize.touch, height: BrandSize.touch)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(tr(.readerUnsave))
                    }
                    .swipeActions {
                        Button(tr(.savedRemove), role: .destructive) {
                            remove(article)
                        }
                    }
                }
                .listRowBackground(Color.brandSurface)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }

    // MARK: - History

    @ViewBuilder
    private var history: some View {
        if app.store.history.isEmpty {
            EmptyStateView(icon: .history, title: tr(.historyEmptyTitle), message: tr(.historyEmptyBody))
            Spacer()
        } else {
            List {
                ForEach(Array(historyDays.enumerated()), id: \.element.id) { offset, day in
                    Section {
                        ForEach(day.articles) { article in
                            ArticleRow(article: article, book: app.content?.book(named: article.bookName), open: { open(article) }) {
                                Text(Self.time(article.date))
                                    .brandText(.labelSmall)
                                    .foregroundStyle(Color.brandInk3)
                                    .monospacedDigit()
                            }
                            .swipeActions {
                                Button(tr(.savedRemove), role: .destructive) {
                                    app.store.removeHistory(article)
                                }
                            }
                        }
                    } header: {
                        HStack {
                            Text(day.title)
                            Spacer()
                            if offset == 0 {
                                Button(tr(.historyClear)) {
                                    confirmClear = true
                                }
                                .foregroundStyle(Color.brandDanger)
                                .textCase(nil)
                            }
                        }
                    }
                    .listRowBackground(Color.brandSurface)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }

    /// History grouped by day: „Dnes“, „Včera“, then „středa 18. listopadu“.
    private var historyDays: [HistoryDay] {
        let calendar = Calendar.current
        let formatter = DateFormatter()
        formatter.locale = L10n.locale
        formatter.setLocalizedDateFormatFromTemplate("EEEEdMMMM")
        var days: [HistoryDay] = []
        for article in app.store.history {
            let title: String
            if calendar.isDateInToday(article.date) {
                title = tr(.historyToday)
            } else if calendar.isDateInYesterday(article.date) {
                title = tr(.historyYesterday)
            } else {
                title = formatter.string(from: article.date)
            }
            if let last = days.indices.last, days[last].title == title {
                days[last].articles.append(article)
            } else {
                days.append(HistoryDay(title: title, articles: [article]))
            }
        }
        return days
    }

    private static func time(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = L10n.locale
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    // MARK: - Actions

    private func open(_ article: ArticleRef) {
        guard let book = app.content?.book(named: article.bookName) else { return }
        app.router.open(ReaderTarget(bookId: book.id, path: article.path))
    }

    private func remove(_ article: ArticleRef) {
        let index = app.store.saved.firstIndex(where: { $0.id == article.id }) ?? 0
        app.store.removeSaved(article)
        let store = app.store
        app.showToast(tr(.savedRemoved), actionTitle: tr(.actionUndo)) {
            store.restoreSaved(article, at: index)
        }
    }
}

private struct HistoryDay: Identifiable {
    let title: String
    var articles: [ArticleRef]

    var id: String { title }
}

/// An article in Saved / History: title, then the book (icon + name); dimmed when its book is not
/// on the plugged-in drive. `trailing` is the bookmark (Saved) or the time (History).
private struct ArticleRow<Trailing: View>: View {
    let article: ArticleRef
    let book: BookRecord?
    let open: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: BrandSpace.s) {
            Button(action: open) {
                VStack(alignment: .leading, spacing: BrandSpace.xs) {
                    Text(article.title)
                        .brandText(.bodyStrong)
                        .foregroundStyle(book == nil ? Color.brandInk3 : Color.brandInk)
                        .lineLimit(2)
                    HStack(spacing: BrandSpace.s) {
                        BookIconBox(kind: BookKind.of(name: article.bookName), box: 26)
                        Text(book.map(BookCatalog.title(of:)) ?? tr(.savedUnavailable))
                            .brandText(.bodySmall)
                            .foregroundStyle(book == nil ? Color.brandInk3 : Color.brandInk2)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: BrandSize.listRow - 8, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(book == nil)
            .accessibilityElement(children: .combine)
            trailing()
        }
        .opacity(book == nil ? 0.75 : 1)
    }
}

#Preview("Uložené") {
    NavigationStack { SavedView() }
        .environment(AppModel.preview())
}
