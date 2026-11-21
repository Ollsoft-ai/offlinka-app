import Foundation
#if canImport(os)
import os
#endif

/// Timings for the simulator and CI (`log show --predicate 'subsystem == "cz.offlinka.app"'`).
/// Debug builds only: release builds log nothing (paths are what the user reads).
enum DebugLog {
    #if DEBUG && canImport(os)
    private static let logger = Logger(subsystem: "cz.offlinka.app", category: "debug")
    #endif

    static func note(_ message: @autoclosure () -> String) {
        #if DEBUG && canImport(os)
        let text = message()
        logger.notice("\(text, privacy: .public)")
        #endif
    }

    /// Milliseconds since `start` (from `DispatchTime.now().uptimeNanoseconds`).
    static func ms(since start: UInt64) -> Int {
        Int((DispatchTime.now().uptimeNanoseconds &- start) / 1_000_000)
    }
}
