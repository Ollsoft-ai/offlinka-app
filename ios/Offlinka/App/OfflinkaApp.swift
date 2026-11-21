import SwiftUI

@main
struct OfflinkaApp: App {
    @State private var app = AppModel.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
        }
    }
}

/// Switches between the connect / unplugged / opening states and the app itself.
@MainActor
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack(alignment: .bottom) {
            content
                // Rebuilt when the language changes, so every `tr()` is evaluated again.
                .id(app.store.settings.language)
                .environment(\.locale, L10n.locale)

            if let toast = app.toast {
                ToastView(message: toast)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.2), value: app.toast)
        .background(Color.brandBg.ignoresSafeArea())
        .tint(Color.brandAccent)
        .onAppear {
            ThemeApplier.apply(app.store.settings.theme)
            app.start()
        }
        .onChange(of: app.store.settings.theme) { _, theme in
            ThemeApplier.apply(theme)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: app.sceneDidBecomeActive()
            case .background: app.sceneDidEnterBackground()
            default: break
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch app.phase {
        case .starting, .opening:
            OpeningView()
        case .connect(let problem):
            ConnectView(problem: problem)
        case .unplugged:
            UnpluggedView()
        case .ready:
            MainTabsView()
        }
    }
}

/// The four tabs, each with its own navigation stack.
@MainActor
struct MainTabsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var router = app.router
        TabView(selection: $router.tab) {
            NavigationStack(path: $router.homePath) {
                HomeView().withRoutes()
            }
            .tabItem { Label(tr(.navHome), systemImage: BrandIcon.home.systemName) }
            .tag(AppTab.home)

            NavigationStack(path: $router.libraryPath) {
                LibraryView().withRoutes()
            }
            .tabItem { Label(tr(.navLibrary), systemImage: BrandIcon.library.systemName) }
            .tag(AppTab.library)

            NavigationStack(path: $router.savedPath) {
                SavedView().withRoutes()
            }
            .tabItem { Label(tr(.navSaved), systemImage: BrandIcon.bookmark.systemName) }
            .tag(AppTab.saved)

            NavigationStack(path: $router.settingsPath) {
                SettingsView().withRoutes()
            }
            .tabItem { Label(tr(.navSettings), systemImage: BrandIcon.settings.systemName) }
            .tag(AppTab.settings)
        }
    }
}

extension View {
    /// Destinations every tab can push.
    @MainActor
    func withRoutes() -> some View {
        navigationDestination(for: Route.self) { route in
            RouteView(route: route)
        }
    }
}

@MainActor
struct RouteView: View {
    @Environment(AppModel.self) private var app
    let route: Route

    var body: some View {
        switch route {
        case .reader(let target):
            if let engine = app.engine {
                ReaderScreen(target: target, resumeAt: app.resumeLocations[target], engine: engine, store: app.store)
                    // A reopened drive brings a new engine: rebuild the reader on the same page.
                    .id(ObjectIdentifier(engine))
            }
        case .search(let scopeBookId):
            if let content = app.content {
                SearchView(source: content, scopeBookId: scopeBookId)
            }
        case .book(let id):
            BookDetailView(bookId: id)
        case .about:
            AboutView()
        }
    }
}

#Preview("Aplikace (EN)") {
    RootView()
        .environment(AppModel.preview(language: .en))
}
