import XCTest
@testable import SoundLinkCore

final class SetupStepTests: XCTestCase {
    func testInitialSetupAdvancesOnlyAfterRequiredStateArrives() {
        XCTAssertEqual(SetupStep.next(reconnected: false, localSelected: false, remoteSelected: false, pendingSide: "", localSide: "mac", controlReady: false), .reconnect)
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: false, remoteSelected: false, pendingSide: "", localSide: "mac", controlReady: false), .headphones)
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: true, remoteSelected: false, pendingSide: "phone", localSide: "mac", controlReady: false), .waiting)
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: true, remoteSelected: true, pendingSide: "", localSide: "mac", controlReady: false), .control)
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: true, remoteSelected: true, pendingSide: "", localSide: "mac", controlReady: true), .complete)
    }
    func testConflictsAndMissingPeerNeverFinishWithOldAuthorization() {
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: true, remoteSelected: true, pendingSide: "mac", localSide: "mac", controlReady: true), .confirm)
        XCTAssertEqual(SetupStep.next(reconnected: true, localSelected: true, remoteSelected: false, pendingSide: "", localSide: "mac", controlReady: true), .waiting)
        XCTAssertEqual(SetupStep.next(reconnected: false, localSelected: true, remoteSelected: true, pendingSide: "mac", localSide: "mac", controlReady: true), .reconnect)
    }
}
