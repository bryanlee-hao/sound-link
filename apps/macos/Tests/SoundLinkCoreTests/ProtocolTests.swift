import XCTest
import CryptoKit
@testable import SoundLinkCore

final class ProtocolTests: XCTestCase {
    var directory: URL!
    var now: TimeInterval = 100
    var engine: PairingEngine!
    let key = P256.Signing.PrivateKey()
    let client = UUID().uuidString.lowercased()
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        engine = try PairingEngine(directory: directory, clock: { self.now })
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }
    func envelope(_ object: [String: Any], key: P256.Signing.PrivateKey? = nil) throws -> [String: Any] {
        let data = try Wire.json(object)
        return ["payload": Wire.b64(data), "signature": Wire.b64(try (key ?? self.key).signature(for: Data("SoundLink/1\n".utf8) + data).derRepresentation)]
    }
    func request() throws -> [String: Any] {
        try envelope(["v": 1, "action": "pair", "client_id": client, "name": "Test phone", "public_key": Wire.b64(key.publicKey.derRepresentation), "request_id": UUID().uuidString.lowercased(), "invitation_id": engine.invitationID, "secret": engine.secret])
    }
    func pair() throws {
        let p = try request(); XCTAssertEqual(try engine.pair(p)["state"] as? String, "paired")
        XCTAssertTrue(engine.secret.isEmpty)
        XCTAssertEqual(try engine.pair(p)["state"] as? String, "paired")
    }
    func session() throws -> [String: Any] {
        let nonce = try engine.challenge(["client_id": client])["nonce"]!
        return try envelope(["v": 1, "action": "session", "client_id": client, "nonce": nonce])
    }
    func testDirectPairingPersistenceAndAuthenticatedHeartbeat() throws {
        try pair(); let auth = try engine.session(session()); let token = auth["token"] as! String
        XCTAssertEqual(try engine.ping(token, body: ["nonce": "round-trip"])["nonce"] as? String, "round-trip"); XCTAssertTrue(engine.online)
        now += 11; XCTAssertFalse(engine.online)
        let restarted = try PairingEngine(directory: directory); XCTAssertEqual(restarted.peer?.id, client)
        XCTAssertThrowsError(try restarted.authorize(token)); XCTAssertThrowsError(try engine.ping("", body: ["nonce":"x"]))
    }
    func testAuthenticatedPhoneRenamePersistsWithoutReplacingPairingOrSession() throws {
        try pair()
        let old = try XCTUnwrap(engine.peer)
        let auth = try engine.session(session()), token = auth["token"] as! String
        _ = try engine.ping(token, body: ["nonce": "rename", "device_name": "Xiaomi 17"])
        XCTAssertEqual(engine.peer?.name, "Xiaomi 17")
        XCTAssertEqual(engine.peer?.id, old.id)
        XCTAssertEqual(engine.peer?.publicKey, old.publicKey)
        XCTAssertEqual(engine.peer?.requestID, old.requestID)
        XCTAssertEqual(engine.peer?.invitationID, old.invitationID)
        XCTAssertEqual(engine.peer?.pairedAt, old.pairedAt)
        XCTAssertEqual(try engine.authorize(token), auth["epoch"] as? String)
        XCTAssertEqual(try PairingEngine(directory: directory).peer?.name, "Xiaomi 17")
        _ = try engine.ping(token, body: ["nonce": "renamed-again", "device_name": "我的手机"])
        XCTAssertEqual(engine.peer?.name, "我的手机")
    }
    func testInvalidOrOldClientPhoneNamesLeaveNameAndConnectionIntact() throws {
        try pair(); let token = try engine.session(session())["token"] as! String
        for value in ["", "   ", "bad\nname", String(repeating: "x", count: 65)] {
            _ = try engine.ping(token, body: ["nonce": "invalid-name", "device_name": value])
            XCTAssertEqual(engine.peer?.name, "Test phone")
        }
        _ = try engine.ping(token, body: ["nonce": "old-client"])
        XCTAssertEqual(engine.peer?.name, "Test phone")
        XCTAssertTrue(engine.online)
    }
    func testUnauthenticatedOrInvalidHeartbeatCannotRenamePhone() throws {
        try pair(); let token = try engine.session(session())["token"] as! String
        XCTAssertThrowsError(try engine.ping("invalid", body: ["nonce": "x", "device_name": "Spoof"]))
        XCTAssertThrowsError(try engine.ping(token, body: ["device_name": "Bad request"]))
        XCTAssertEqual(engine.peer?.name, "Test phone")
    }
    func testExpirationRefreshAndConsumedInvite() throws {
        let old = try request(); engine.refreshInvite(); XCTAssertThrowsError(try engine.pair(old))
        engine.refreshInvite(); let expired = try request(); now += 301; XCTAssertThrowsError(try engine.pair(expired))
        engine.refreshInvite(); try pair(); XCTAssertThrowsError(try engine.pair(request()))
    }
    func testForgedSignatureAndReplay() throws {
        var p = try request(); p["signature"] = Wire.b64(try P256.Signing.PrivateKey().signature(for: Data("wrong".utf8)).derRepresentation)
        XCTAssertThrowsError(try engine.pair(p)); try pair()
        let handshake = try session(); _ = try engine.session(handshake); XCTAssertThrowsError(try engine.session(handshake))
        let expired = try session(); now += 31; XCTAssertThrowsError(try engine.session(expired))
    }
    func testRevokeAndSleepInvalidateSessions() throws {
        try pair(); let token = try engine.session(session())["token"] as! String
        engine.suspend(); XCTAssertThrowsError(try engine.authorize(token))
        let next = try engine.session(session())["token"] as! String; try engine.unpair()
        XCTAssertThrowsError(try engine.authorize(next)); XCTAssertNil(try PairingEngine(directory: directory).peer)
    }
    func testResetWithoutPeerInvalidatesAbandonedInvitationAndCanRepeat() throws {
        let abandoned = try request()
        try engine.unpair()
        XCTAssertThrowsError(try engine.pair(abandoned))
        XCTAssertNil(engine.peer)
        try engine.unpair()
        try pair()
        let token = try engine.session(session())["token"] as! String
        try engine.unpair()
        XCTAssertThrowsError(try engine.authorize(token))
        XCTAssertNil(try PairingEngine(directory: directory).peer)
    }
    func testParserFragmentationLimitsAndSmuggling() throws {
        let request = "POST /v1/ping HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}"
        XCTAssertNil(try HTTPParser.parse(Data(request.dropLast().utf8)))
        XCTAssertEqual(try HTTPParser.parse(Data(request.utf8))?.path, "/v1/ping")
        for attack in [request + "x", request.replacingOccurrences(of: "Content-Length: 2", with: "Content-Length: 2\r\nContent-Length: 2"), request.replacingOccurrences(of: "Content-Length: 2", with: "Transfer-Encoding: chunked"), "GET / HTTP/1.1\r\n\r\n", String(repeating:"x",count:8193)] {
            XCTAssertThrowsError(try HTTPParser.parse(Data(attack.utf8)))
        }
    }
    func testRepeatedReconnectEvictsOldestSessionWithoutLockout() throws {
        try pair()
        let oldest = try engine.session(session())["token"] as! String
        for _ in 0..<12 { now += 1; let token = try engine.session(session())["token"] as! String; XCTAssertNoThrow(try engine.authorize(token)) }
        XCTAssertThrowsError(try engine.authorize(oldest))
    }
    func testPausedMacRejectsSessionsAndRequiresFreshAuthenticationOnResume() throws {
        try pair()
        let token = try engine.session(session())["token"] as! String
        engine.setAvailable(false)
        XCTAssertThrowsError(try engine.ping(token, body: ["nonce": "paused"]))
        XCTAssertThrowsError(try engine.session(session()))
        engine.setAvailable(true)
        XCTAssertThrowsError(try engine.authorize(token))
        let fresh = try engine.session(session())["token"] as! String
        XCTAssertNoThrow(try engine.ping(fresh, body: ["nonce": "resumed"]))
    }
    func testChallengeFloodBound() throws { try pair(); for _ in 0..<32 { _ = try engine.challenge(["client_id":client]) }; XCTAssertThrowsError(try engine.challenge(["client_id":client])); now += 31; XCTAssertNoThrow(try engine.challenge(["client_id":client])) }
    func testAutomaticInviteRotationPreservesUnexpiredAndRevokesExpiredCode() throws {
        let original = engine.invitationID
        let p = try request()
        now += 299; engine.refreshExpiredInvite()
        XCTAssertEqual(engine.invitationID, original)
        now += 2; engine.refreshExpiredInvite()
        XCTAssertNotEqual(engine.invitationID, original)
        XCTAssertThrowsError(try engine.pair(p))
        try pair(); let paired = engine.invitationID
        now += 301; engine.refreshExpiredInvite(); XCTAssertEqual(engine.invitationID, paired)
    }
    func testDirectPairingConsumesInvitationAndRetrySurvivesRestart() throws {
        let first = try request(), competing = try request()
        XCTAssertThrowsError(try engine.challenge(["client_id": client]))
        let response = try engine.pair(first)
        XCTAssertEqual(response["state"] as? String, "paired")
        let pairingID = engine.peer?.requestID
        XCTAssertThrowsError(try engine.pair(competing))
        now += 301
        let restarted = try PairingEngine(directory: directory)
        XCTAssertEqual(try restarted.pair(first)["pairing_id"] as? String, pairingID)
        XCTAssertEqual(restarted.peer?.requestID, pairingID)
    }
    func testInvalidSecretCannotPairEvenWithValidSignature() throws {
        var body = try engine.decoded(request())
        body["secret"] = Wire.random()
        XCTAssertThrowsError(try engine.pair(envelope(body)))
        XCTAssertNil(engine.peer)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("peer.json").path))
    }
    func testSignedUnpairBoundToExactPairingAndWorksWhilePaused() throws {
        try pair()
        let body: [String: Any] = ["v": 1, "action": "unpair", "client_id": client, "pairing_id": engine.peer!.requestID]
        let notice = try envelope(body)
        engine.setAvailable(false)
        XCTAssertNoThrow(try engine.validateUnpair(notice))
        XCTAssertThrowsError(try engine.validateUnpair(envelope(body, key: P256.Signing.PrivateKey())))
        try engine.unpair(); try pair()
        XCTAssertThrowsError(try engine.validateUnpair(notice))
        XCTAssertNotNil(engine.peer)
    }

}
