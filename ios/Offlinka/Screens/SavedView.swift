import SwiftUI

/// Saved articles and the history.
@MainActor
struct SavedView: View {
    @Environment(AppModel.self) private var app
    @State private var showsHistory = false
    @State private var confirmClear = false

    var body: some View {
        VStack(spacing: 0) {
            Picker(tr(.savedTitle), selection: $showsHistory) {
                Text(tr(.savedTabSaved)).tag(false)
                Text(tr(.savedTabHistory)).tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, BrandSize.gutter)
            .padding(.vertical, BrandSpace.s)

            if showsHistory {
                history
            } else {
                saved
            }
        }
        .background(Color.brandBg.ignoresSafeArea())
        .navigationTitle(tr(.savedTitle))
        .toolbar {
            if showsHistory && !app.store.history.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button(tr(.historyClear), role: .destructive) {
                        confirmClear = true
                    }
                }
            }
        }
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
                    ArticleRow(article: article, book: app.content?.book(named: article.bookName)) {
                        open(article)
                    }
                    .swipeActions {
                        Button(tr(.savedRemove), role: .destructive) {
                            app.store.removeSaved(article)
                            app.showToast(tr(.savedRemoved))
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
                ForEach(historyDays) { day in
                    Section(day.title) {
                        ForEach(day.articles) { article in
                            ArticleRow(article: article, book: app.content?.book(named: article.bookName)) {
                                open(article)
                            }
                            .swipeActions {
                                Button(tr(.savedRemove), role: .destructive) {
                                    app.store.removeHistory(article)
                                }
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

    private var historyDays: [HistoryDay] {
        let calendar = Calendar.current
        let formatter = DateFormatter()
        formatter.locale = L10n.locale
        formatter.dateStyle = .long
        formatter.timeStyle = .none
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

    private func open(_ article: ArticleRef) {
        guard let book = app.content?.book(named: article.bookName) else { return }
        app.router.open(ReaderTarget(bookId: book.id, path: article.path))
    }
}

private struct HistoryDay: Identifiable {
    let title: String
    var articles: [ArticleRef]

    var id: String { title }
}

/// An article in Saved / History; greyed out when its book is not on the plugged-in drive.
private struct ArticleRow: View {
    let article: ArticleRef
    let book: BookRecord?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: BrandSpace.m) {
                if let book {
                    BookIconBox(kind: BookCatalog.kind(of: book), box: BrandSize.bookIconBoxSmall)
                }
                VStack(alignment: .leading, spacing: BrandSpace.xxs) {
                    Text(article.title)
                        .brandText(.bodyStrong)
                        .foregroundStyle(book == nil ? Color.brandInk3 : Color.brandInk)
                        .lineLimit(2)
                    Text(book.map(BookCatalog.title(of:)) ?? tr(.savedUnavailable))
                        .brandText(.caption)
                        .foregroundStyle(Color.brandInk2)
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: BrandSize.listRow - 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(book == nil)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Uložené") {
    NavigationStack { SavedView() }
        .environment(AppModel.preview())
}
