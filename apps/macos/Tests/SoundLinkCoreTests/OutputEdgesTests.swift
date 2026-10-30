import XCTest
@testable import SoundLinkCore

final class OutputEdgesTests: XCTestCase {
    func testExistingPlaybackIsBaselineButShortNewOutputQualifiesImmediately() {
        var e = OutputEdges()
        e.update(active: [1]); e.update(active: [1])
        XCTAssertEqual(e.event, 0); XCTAssertFalse(e.eligible)
        e.update(active: [1, 2])
        XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        e.update(active: [1])
        XCTAssertEqual(e.event, 1); XCTAssertFalse(e.eligible)
    }
    func testAnyNewOutputQualifiesWithoutBundleOrPlayerMetadataAndDoesNotRepeat() {
        var e = OutputEdges()
        e.update(active: [])
        e.update(active: [777]); XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        e.update(active: [777]); XCTAssertEqual(e.event, 1)
        e.update(active: []); XCTAssertFalse(e.eligible)
        e.update(active: [777]); XCTAssertEqual(e.event, 2); XCTAssertTrue(e.eligible)
    }
    func testNewOutputOverOldPlaybackAndUnknownStateReset() {
        var e = OutputEdges()
        e.update(active: [1])
        e.update(active: [1,2]); XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        e.update(active: [1,2], readable: false); XCTAssertFalse(e.eligible)
        e.update(active: [1,2]); e.update(active: [1,2])
        XCTAssertEqual(e.event, 1); XCTAssertFalse(e.eligible)
        e.update(active: [1,2,3]); XCTAssertEqual(e.event, 2); XCTAssertTrue(e.eligible)
    }
}
