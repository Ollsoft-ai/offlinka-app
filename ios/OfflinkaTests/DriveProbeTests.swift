import XCTest
@testable import Offlinka

/// Calls into the USB drive run off the main thread with a time limit: a stalled drive counts as gone and
/// never blocks the caller, and no second call piles up behind a stuck one.
final class DriveProbeTests: XCTestCase {
    func testAnAnswerInTimeIsReturned() async {
        let outcome = await DriveProbe.run(gate: DriveProbe.Gate(), timeout: 2) { 42 }
        guard case .value(42) = outcome else { return XCTFail("\(outcome)") }
    }

    func testAStalledDriveTimesOutAndALateAnswerIsUndone() async {
        let gate = DriveProbe.Gate()
        let release = DispatchSemaphore(value: 0)
        let undone = expectation(description: "late answer undone")
        let start = Date()
        let outcome = await DriveProbe.run(gate: gate, timeout: 0.2, {
            release.wait()   // stuck in the drive
            return true
        }, late: { _ in undone.fulfill() })
        guard case .timedOut = outcome else { return XCTFail("\(outcome)") }
        XCTAssertLessThan(Date().timeIntervalSince(start), 2, "the caller is not blocked by the drive")

        // While the stuck call runs, no other call starts.
        let second = await DriveProbe.run(gate: gate, timeout: 0.2) { true }
        guard case .busy = second else { return XCTFail("\(second)") }
        XCTAssertTrue(gate.isRunning)

        release.signal()
        await fulfillment(of: [undone], timeout: 5)
        XCTAssertFalse(gate.isRunning)
        let third = await DriveProbe.run(gate: gate, timeout: 2) { true }
        guard case .value(true) = third else { return XCTFail("\(third)") }
    }
}
