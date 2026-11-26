import XCTest
@testable import SoundLinkCore

final class OutputEdgesTests: XCTestCase {
    var now: TimeInterval = 100
    func step(_ e: inout OutputEdges, _ active: Set<UInt32>, readable: Bool = true, by delta: TimeInterval = 0.2) {
        now += delta; e.update(active: active, readable: readable, now: now)
    }
    func sustain(_ e: inout OutputEdges, _ active: Set<UInt32>) {
        step(&e, active); step(&e, active, by: OutputEdges.defaultSustainSeconds)
    }
    func testExistingPlaybackIsBaselineAndSustainedNewOutputQualifies() {
        var e = OutputEdges()
        step(&e, [1]); step(&e, [1])
        XCTAssertEqual(e.event, 0); XCTAssertFalse(e.eligible)
        sustain(&e, [1, 2])
        XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        step(&e, [1])
        XCTAssertEqual(e.event, 1); XCTAssertFalse(e.eligible)
    }
    func testShortOutputBelowThresholdNeverQualifies() {
        var e = OutputEdges()
        step(&e, [1])
        step(&e, [1, 2]); step(&e, [1, 2], by: 0.8); step(&e, [1])
        step(&e, [1], by: 5)
        XCTAssertEqual(e.event, 0); XCTAssertFalse(e.eligible)
    }
    func testOutputQualifiesExactlyAtThresholdAndDoesNotRepeat() {
        var e = OutputEdges()
        step(&e, [])
        step(&e, [777]); step(&e, [777], by: OutputEdges.defaultSustainSeconds - 0.1)
        XCTAssertEqual(e.event, 0)
        step(&e, [777], by: 0.1); XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        step(&e, [777], by: 30); XCTAssertEqual(e.event, 1)
        step(&e, []); XCTAssertFalse(e.eligible)
        sustain(&e, [777]); XCTAssertEqual(e.event, 2); XCTAssertTrue(e.eligible)
    }
    func testNewOutputOverOldPlaybackAndUnknownStateReset() {
        var e = OutputEdges()
        step(&e, [1])
        sustain(&e, [1, 2]); XCTAssertEqual(e.event, 1); XCTAssertTrue(e.eligible)
        step(&e, [1, 2], readable: false); XCTAssertFalse(e.eligible)
        sustain(&e, [1, 2]); step(&e, [1, 2])
        XCTAssertEqual(e.event, 1); XCTAssertFalse(e.eligible)
        sustain(&e, [1, 2, 3]); XCTAssertEqual(e.event, 2); XCTAssertTrue(e.eligible)
    }
    func testPendingIsTrueOnlyWhileNewOutputIsProvingSustain() {
        var e = OutputEdges()
        step(&e, [1]); XCTAssertFalse(e.pending)
        step(&e, [1, 2]); XCTAssertTrue(e.pending); XCTAssertEqual(e.event, 0)
        step(&e, [1]); XCTAssertFalse(e.pending)
        step(&e, [1, 3]); step(&e, [1, 3], by: OutputEdges.defaultSustainSeconds)
        XCTAssertFalse(e.pending); XCTAssertEqual(e.event, 1)
        step(&e, [1, 3], readable: false); XCTAssertFalse(e.pending)
        step(&e, [1, 3, 4]); XCTAssertFalse(e.pending) // First readable sample is baseline.
    }
    func testSystemAlertSourcesAreRecognisedCaseInsensitively() {
        XCTAssertTrue(SystemSoundSources.isSystemSound("systemsoundserverd"))
        XCTAssertTrue(SystemSoundSources.isSystemSound("com.apple.UserNotificationCenter"))
        XCTAssertFalse(SystemSoundSources.isSystemSound("com.google.Chrome.helper"))
        XCTAssertFalse(SystemSoundSources.isSystemSound("com.apple.Music"))
    }
}
