import XCTest
@testable import Offlinka

/// The page's content process dying: one reload, then a calm stop; a loaded page starts over.
final class ContentProcessRecoveryTests: XCTestCase {
    func testReloadsOnceThenGivesUp() {
        var recovery = ContentProcessRecovery()
        XCTAssertEqual(recovery.processTerminated(), .reload)
        XCTAssertEqual(recovery.processTerminated(), .giveUp)
        XCTAssertEqual(recovery.processTerminated(), .giveUp)
    }

    func testALoadedPageStartsOver() {
        var recovery = ContentProcessRecovery()
        XCTAssertEqual(recovery.processTerminated(), .reload)
        recovery.reset()
        XCTAssertEqual(recovery.processTerminated(), .reload)
    }
}
