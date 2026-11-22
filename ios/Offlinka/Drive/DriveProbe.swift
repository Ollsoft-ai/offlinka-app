import Foundation

/// Blocking file-system calls on the USB drive (does the folder exist, resolve the bookmark) run here: on a
/// background thread, with a time limit. A stalled drive (loose plug, slow mount) can then never freeze the
/// app or get it killed at launch; no answer in time counts as unplugged.
enum DriveProbe {
    enum Outcome<T> {
        case value(T)
        /// No answer within the limit: treat the drive as gone.
        case timedOut
        /// An earlier call on this gate is still stuck in the drive: none is started on top of it.
        case busy
    }

    /// A healthy drive answers in milliseconds; a waking one in well under a second.
    static let timeout: TimeInterval = 4

    /// Runs `work` off the main thread. After `timeout` it returns `.timedOut`; the late result then goes to
    /// `late`, which undoes it (a security scope it started). One call at a time per `gate`.
    static func run<T: Sendable>(gate: Gate, timeout: TimeInterval = timeout, _ work: @escaping @Sendable () -> T,
                                 late: @escaping @Sendable (T) -> Void = { _ in }) async -> Outcome<T> {
        guard gate.enter() else { return .busy }
        let value: T? = await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            let once = Once(continuation)
            DispatchQueue.global(qos: .userInitiated).async {
                let value = work()
                gate.leave()
                if !once.resume(value) {
                    late(value)
                }
            }
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + timeout) {
                _ = once.resume(nil)
            }
        }
        guard let value else { return .timedOut }
        return .value(value)
    }

    /// Lets one call into the drive run at a time; it stays closed until that call really returns.
    final class Gate: @unchecked Sendable {
        private let lock = NSLock()
        private var running = false

        init() {}

        var isRunning: Bool {
            lock.lock()
            defer { lock.unlock() }
            return running
        }

        fileprivate func enter() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if running { return false }
            running = true
            return true
        }

        fileprivate func leave() {
            lock.lock()
            running = false
            lock.unlock()
        }
    }

    /// Resumes the continuation once: with the value, or with nil when the time ran out first.
    private final class Once<T: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<T?, Never>?

        init(_ continuation: CheckedContinuation<T?, Never>) {
            self.continuation = continuation
        }

        func resume(_ value: T?) -> Bool {
            lock.lock()
            let pending = continuation
            continuation = nil
            lock.unlock()
            pending?.resume(returning: value)
            return pending != nil
        }
    }
}
