import XCTest
@testable import SoundLinkCore

final class HeadphoneSelectionTests: XCTestCase {
    var directory: URL!
    var engine: ObservationEngine!
    let a = "00:11:22:33:44:55", b = "AA:BB:CC:DD:EE:FF"
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        engine = try ObservationEngine(directory: directory)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }
    func phone(_ address: String, mode: String = "propose") throws {
        try engine.selectPhone(["revision": engine.target.revision, "address": address, "name": "Headphones", "mode": mode])
    }
    func testPhoneCanInitiateWithoutMacSelectionAndExactIdentityMatches() throws {
        try phone(a)
        XCTAssertEqual(engine.target.pendingSide, "mac")
        XCTAssertFalse(engine.target.ready)
        XCTAssertTrue(engine.target.canAutomaticallyMatch(side: "mac", address: a.replacingOccurrences(of: ":", with: "-")))
        try engine.confirmMac(revision: engine.target.revision, address: a, name: "Renamed locally")
        XCTAssertTrue(engine.target.ready)
        XCTAssertNil(engine.target.pendingSide)
        XCTAssertEqual(engine.target.macName, "Renamed locally")
    }
    func testMacInitiatedConflictRetainsPriorIdentityForConfirmation() throws {
        try engine.selectMac(address: a, name: "First")
        try phone(a, mode: "confirm")
        try engine.selectMac(address: b, name: "Second")
        XCTAssertEqual(engine.target.previousAddress, a)
        XCTAssertTrue(engine.target.phoneAddress.isEmpty)
        XCTAssertFalse(engine.target.canAutomaticallyMatch(side: "phone", address: b))
        XCTAssertFalse(engine.target.ready)
        try phone(b, mode: "confirm")
        XCTAssertTrue(engine.target.ready)
        XCTAssertNil(engine.target.previousAddress)
    }
    func testPhoneInitiatedConflictDoesNotSilentlyReplaceMac() throws {
        try engine.selectMac(address: a, name: "First")
        try phone(a, mode: "confirm")
        try phone(b)
        XCTAssertEqual(engine.target.previousAddress, a)
        XCTAssertTrue(engine.target.macAddress.isEmpty)
        XCTAssertFalse(engine.target.canAutomaticallyMatch(side: "mac", address: b))
        XCTAssertFalse(engine.target.ready)
    }
    func testNamesNeverAuthorizeAnAutomaticMatchButUserMayConfirmDifferentAddresses() throws {
        try phone(a)
        XCTAssertFalse(engine.target.canAutomaticallyMatch(side: "mac", address: b))
        try engine.confirmMac(revision: engine.target.revision, address: b, name: "Headphones")
        XCTAssertTrue(engine.target.ready)
    }
    func testConcurrentChoicesAndStaleDialogsCannotOverwriteNewTarget() throws {
        try phone(a)
        let stale = engine.target.revision
        try engine.selectMac(address: b, name: "New selection")
        XCTAssertThrowsError(try engine.confirmMac(revision: stale, address: a, name: "Stale"))
        XCTAssertThrowsError(try engine.selectPhone(["revision": stale, "address": a, "name": "Stale", "mode": "propose"]))
        XCTAssertEqual(engine.target.macAddress, b)
        XCTAssertEqual(engine.target.pendingSide, "phone")
    }
    func testRestartRetainsPendingConflictAndClearInvalidatesIt() throws {
        try engine.selectMac(address: a, name: "First")
        try phone(a, mode: "confirm")
        try phone(b)
        let restored = try ObservationEngine(directory: directory)
        XCTAssertEqual(restored.target.previousAddress, a)
        XCTAssertEqual(restored.target.pendingSide, "mac")
        let stale = restored.target.revision
        try restored.clear()
        XCTAssertThrowsError(try restored.confirmMac(revision: stale, address: b, name: "Stale"))
        XCTAssertNil(restored.target.pendingSide)
        XCTAssertFalse(restored.target.ready)
    }
    func testOldPersistedTargetStillLoadsAndSameSelectionIsIdempotent() throws {
        let legacy = "{\"revision\":5,\"macAddress\":\"\(a)\",\"macName\":\"Mac name\",\"phoneAddress\":\"\(a)\",\"phoneName\":\"Phone name\"}"
        try Data(legacy.utf8).write(to: directory.appendingPathComponent("observation-target.json"))
        let restored = try ObservationEngine(directory: directory)
        XCTAssertTrue(restored.target.ready)
        try restored.selectPhone(["revision": 5, "address": a, "name": "Same", "mode": "propose"])
        XCTAssertEqual(restored.target.revision, 5)
    }
    func testInvalidRequestsAndOppositeSideConfirmationAreRejected() throws {
        try phone(a)
        let revision = engine.target.revision
        XCTAssertThrowsError(try phone(b, mode: "confirm"))
        XCTAssertThrowsError(try phone(b, mode: "unsupported"))
        XCTAssertThrowsError(try phone("not-an-address"))
        XCTAssertEqual(engine.target.revision, revision)
        XCTAssertFalse(engine.target.ready)
    }
    func testRepeatedProposalsKeepOriginalConflictInsteadOfBecomingAutomatic() throws {
        try engine.selectMac(address: a, name: "First")
        try phone(a, mode: "confirm")
        try engine.selectMac(address: b, name: "Second")
        try engine.selectMac(address: "11:22:33:44:55:66", name: "Third")
        XCTAssertEqual(engine.target.previousAddress, a)
        XCTAssertFalse(engine.target.canAutomaticallyMatch(side: "phone", address: "11:22:33:44:55:66"))
    }
}
