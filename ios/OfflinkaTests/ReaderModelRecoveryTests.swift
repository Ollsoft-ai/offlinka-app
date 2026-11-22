#if os(iOS)
import XCTest
@testable import Offlinka

/// The reader survives its web content process dying: one reload, then „Zkusit znovu“, never a crash.
@MainActor
final class ReaderModelRecoveryTests: XCTestCase {
    func testContentProcessDeathReloadsOnceThenShowsTheMessage() {
        let engine = ReaderEngine(content: FakeContentSource.demo(), appearance: ReaderAppearance(theme: .light, textScale: 1))
        let model = ReaderModel(target: ReaderTarget(bookId: FakeContentSource.demoWikipediaId, path: "Praha"), engine: engine,
                                store: UserStore(fileURL: nil), appearance: ReaderAppearance(theme: .light, textScale: 1))
        model.contentProcessTerminated()
        XCTAssertFalse(model.contentFailed, "the first time the page reloads")
        model.contentProcessTerminated()
        XCTAssertTrue(model.contentFailed, "the second time it stops with a message")
        model.retryAfterContentFailure()
        XCTAssertFalse(model.contentFailed)
        model.contentProcessTerminated()
        XCTAssertFalse(model.contentFailed, "„Zkusit znovu“ starts over")
    }
}
#endif
