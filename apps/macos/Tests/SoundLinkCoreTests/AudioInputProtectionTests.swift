import XCTest
@testable import SoundLinkCore

final class AudioInputProtectionTests: XCTestCase {
    func testTapOnlyAggregateDoesNotProtectPlaybackOwner() {
        XCTAssertFalse(AudioInputProtection.classify(isAggregate: true, tapCount: 1, subdeviceInputCounts: []).protectsOwner)
        XCTAssertFalse(AudioInputProtection.classify(isAggregate: true, tapCount: 2, subdeviceInputCounts: [0, 0]).protectsOwner)
    }
    func testPhysicalVirtualAndMixedMicrophoneInputsStayProtected() {
        XCTAssertTrue(AudioInputProtection.classify(isAggregate: false, tapCount: nil, subdeviceInputCounts: nil).protectsOwner)
        XCTAssertTrue(AudioInputProtection.classify(isAggregate: true, tapCount: 1, subdeviceInputCounts: [0, 1]).protectsOwner)
        XCTAssertTrue(AudioInputProtection.classify(isAggregate: true, tapCount: 0, subdeviceInputCounts: [1]).protectsOwner)
    }
    func testIncompleteDeviceEvidenceNeverExemptsInput() {
        for counts: [Int]? in [nil, [-1], [0, -1]] {
            XCTAssertEqual(AudioInputProtection.classify(isAggregate: true, tapCount: 1, subdeviceInputCounts: counts), .unknown)
        }
        XCTAssertEqual(AudioInputProtection.classify(isAggregate: true, tapCount: nil, subdeviceInputCounts: []), .unknown)
        XCTAssertEqual(AudioInputProtection.classify(isAggregate: true, tapCount: 0, subdeviceInputCounts: []), .unknown)
    }
}
