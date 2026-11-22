import Foundation

/// What the reader does when its web content process dies (a big map or a long page on a phone short
/// of memory, or a crash): reload the page once, and if it dies again before any page finished
/// loading, stop and show a calm message with „Zkusit znovu“ instead of a reload loop. The app itself
/// never goes down with it (Android twin: `onRenderProcessGone`).
struct ContentProcessRecovery: Equatable, Sendable {
    enum Action: Equatable, Sendable {
        case reload
        case giveUp
    }

    /// Terminations since a page last finished loading.
    private(set) var terminationsSinceLoad = 0

    mutating func processTerminated() -> Action {
        terminationsSinceLoad += 1
        return terminationsSinceLoad <= 1 ? .reload : .giveUp
    }

    /// A page finished loading, or the user asked to try again.
    mutating func reset() {
        terminationsSinceLoad = 0
    }
}
