import Foundation
import Observation

enum AppTab: Hashable, Sendable {
    case home, library, saved, settings
}

/// Screens pushed onto a tab's navigation stack.
enum Route: Hashable, Sendable {
    case reader(ReaderTarget)
    case search(scopeBookId: String?)
    case book(String)
    case about
}

/// Selected tab and every tab's navigation path. Lives in `AppModel`, so it survives the root view
/// being rebuilt (language change) and readers can be opened from anywhere.
@MainActor
@Observable
final class Router {
    var tab: AppTab = .home
    var homePath: [Route] = []
    var libraryPath: [Route] = []
    var savedPath: [Route] = []
    var settingsPath: [Route] = []
    /// Saved tab shows the history segment (reader menu "Historie" opens it).
    var savedShowsHistory = false

    func showHistory() {
        savedShowsHistory = true
        tab = .saved
    }

    /// Opens a page on the current tab.
    func open(_ target: ReaderTarget) {
        push(.reader(target))
    }

    func push(_ route: Route) {
        switch tab {
        case .home: homePath.append(route)
        case .library: libraryPath.append(route)
        case .saved: savedPath.append(route)
        case .settings: settingsPath.append(route)
        }
    }

    /// Tapping the selected tab again goes back to its root.
    func popToRoot(_ tab: AppTab) {
        switch tab {
        case .home: homePath.removeAll()
        case .library: libraryPath.removeAll()
        case .saved: savedPath.removeAll()
        case .settings: settingsPath.removeAll()
        }
    }

    func reset() {
        tab = .home
        homePath.removeAll()
        libraryPath.removeAll()
        savedPath.removeAll()
        settingsPath.removeAll()
    }
}
