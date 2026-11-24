import XCTest
@testable import SoundLinkCore

final class HomeRouteTests: XCTestCase {
    func testHeadphonesRestBesideTheConnectedDevice() {
        XCTAssertEqual(HomeRoute.resolve(localConnected: true, peerConnected: false, switching: false), .local)
        XCTAssertEqual(HomeRoute.resolve(localConnected: false, peerConnected: true, switching: false), .peer)
        XCTAssertEqual(HomeRoute.resolve(localConnected: true, peerConnected: true, switching: false), .both)
    }
    func testUnknownOrDisconnectedEvidenceStaysInTheMiddle() {
        // Callers pass peerConnected = false when the peer sample is stale or unknown.
        XCTAssertEqual(HomeRoute.resolve(localConnected: false, peerConnected: false, switching: false), .idle)
    }
    func testSwitchingOverridesConnectionEvidence() {
        XCTAssertEqual(HomeRoute.resolve(localConnected: true, peerConnected: false, switching: true), .switching)
        XCTAssertEqual(HomeRoute.resolve(localConnected: false, peerConnected: false, switching: true), .switching)
    }
}
