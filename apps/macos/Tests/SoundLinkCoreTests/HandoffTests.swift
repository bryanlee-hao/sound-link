import XCTest
@testable import SoundLinkCore

final class HandoffTests: XCTestCase {
    var now: Double = 100
    var directory: URL!
    var o: ObservationEngine!
    var h: HandoffEngine!
    var phone = AudioObservation()
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        o = try ObservationEngine(directory: directory, clock: { self.now })
        h = HandoffEngine(clock: { self.now }); h.enabled = true
        try o.selectMac(address: "00:11:22:33:44:55", name: "Mac headset")
        try o.selectPhone(["revision": o.target.revision, "address": "AA:BB:CC:DD:EE:FF", "name": "Phone headset"])
        var mac = AudioObservation(); mac.instance = "mac"; mac.targetRevision = o.target.revision
        mac.connection = "connected"; mac.playing = "active"; mac.protectedAudio = false; mac.media = true; o.mac = mac
        phone.instance = "phone"; phone.targetRevision = o.target.revision; phone.connection = "disconnected"; phone.playing = "idle"; phone.protectedAudio = false; phone.media = true
        sync()
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }
    func sync() {
        o.receive(phone.object, epoch: "epoch"); o.evaluate(online: true)
        h.receive(["ready": true, "revision": o.target.revision], observation: o)
        h.tick(o, available: true)
    }
    func phoneStarts() { phone.event += 1; phone.playing = "active"; sync() }
    func testPhoneIndependentTransactionSuppressesDuplicateAndCompletionReplay() throws {
        phoneStarts(); XCTAssertNotNil(h.transaction)
        h.receive(["ready": true, "revision": o.target.revision, "localTakeover": ["active": true]], observation: o)
        h.tick(o, available: true)
        XCTAssertNil(h.transaction); XCTAssertNil(h.object["command"])
        phone.localControlGeneration = "completed-local-tx"; phone.event += 1
        sync()
        XCTAssertNil(h.transaction)
        phoneStarts(); XCTAssertNotNil(h.transaction)
    }
    func testLocalCompletionCancelsOlderRemoteCommandEvenWithoutActiveReport() {
        phoneStarts(); XCTAssertNotNil(h.transaction)
        phone.localControlGeneration = "finished-without-active-ping"
        sync(); XCTAssertNil(h.transaction); XCTAssertNil(h.object["command"])
        phoneStarts(); XCTAssertNotNil(h.transaction)
    }
    func testPhoneTakeoverOfPlayingMacRequiresRouteProofBeforePause() throws {
        phoneStarts(); let t = try XCTUnwrap(h.transaction)
        XCTAssertEqual(h.phase, "connecting_phone"); XCTAssertNil(h.takeMacPause())
        var proof: [String: Any] = ["id": t.id, "revision": t.target.revision, "stage": "connected", "routeVerified": false]
        h.receive(["ready": true, "revision": t.target.revision, "result": proof], observation: o)
        XCTAssertNil(h.takeMacPause())
        phone.connection = "connected"; o.receive(phone.object, epoch: "epoch"); proof["routeVerified"] = true
        h.receive(["ready": true, "revision": t.target.revision, "result": proof], observation: o)
        XCTAssertEqual(h.takeMacPause()?.id, t.id); XCTAssertNil(h.takeMacPause())
        h.pauseResult(t.id, "无适配，跳过"); XCTAssertNil(h.transaction); XCTAssertTrue(h.status.contains("已接管"))
        h.tick(o, available: true); XCTAssertNil(h.transaction)
    }
    func testMacTakeoverNeedsMediaClassifierAndOnlyOneHardwareRequest() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        o.mac.event += 1; o.mac.media = false; sync(); XCTAssertNil(h.takeMacAction())
        o.mac.event += 1; o.mac.media = true; sync()
        let t = try XCTUnwrap(h.takeMacAction()); XCTAssertNil(h.takeMacAction())
        h.connected(t.id)
        let command = try XCTUnwrap(h.object["command"] as? [String: Any]); XCTAssertEqual(command["action"] as? String, "pause")
        now += 6; h.tick(o, available: true)
        XCTAssertNil(h.transaction); XCTAssertTrue(h.status.contains("接管成功"))
    }
    func testTimeoutDedupAndFailureCooldownNeverRetryOldEdge() throws {
        phoneStarts(); let id = try XCTUnwrap(h.transaction?.id)
        now += 5; h.tick(o, available: true); XCTAssertNil(h.transaction)
        h.connected(id); XCTAssertNil(h.transaction)
        sync(); XCTAssertNil(h.transaction)
        phoneStarts(); XCTAssertNil(h.transaction); XCTAssertTrue(h.status.contains("冷却"))
        now += 11; sync(); XCTAssertNil(h.transaction)
        phoneStarts(); XCTAssertNotNil(h.transaction)
    }
    func testStaleProofWrongIDAndTargetCannotFinish() throws {
        phoneStarts(); let t = try XCTUnwrap(h.transaction)
        phone.connection = "connected"; o.receive(phone.object, epoch: "epoch")
        for proof in [["id": "other", "revision": t.target.revision, "stage": "connected", "routeVerified": true] as [String: Any], ["id": t.id, "revision": -1, "stage": "connected", "routeVerified": true]] {
            h.receive(["ready": true, "revision": t.target.revision, "result": proof], observation: o)
            XCTAssertEqual(h.phase, "connecting_phone")
        }
        try o.clear(); h.tick(o, available: true); XCTAssertFalse(h.valid(t.id)); XCTAssertNil(h.transaction)
    }
    func testDisableAndMacExitCancelInFlightCommands() throws {
        phoneStarts(); h.enabled = false; h.tick(o, available: true)
        XCTAssertNil(h.object["command"]); XCTAssertNil(h.transaction)
        h.enabled = true; phoneStarts(); XCTAssertNotNil(h.transaction)
        h.tick(o, available: false); XCTAssertNil(h.transaction)
    }
    func testNewSessionCancelsButPhoneVerificationSurvivesConfigChanges() throws {
        phoneStarts(); let old = try XCTUnwrap(h.transaction)
        o.receive(phone.object, epoch: "replacement"); h.tick(o, available: true)
        XCTAssertFalse(h.valid(old.id)); XCTAssertNil(h.object["command"])
        sync() // Restored session first establishes a baseline.
        phoneStarts(); let next = try XCTUnwrap(h.transaction)
        phone.event += 1; o.receive(phone.object, epoch: "epoch"); h.tick(o, available: true)
        XCTAssertTrue(h.valid(next.id))
        phone.connection = "connected"; o.receive(phone.object, epoch: "epoch")
        h.receive(["ready":true,"revision":o.target.revision,"result":["id":next.id,"revision":o.target.revision,"stage":"connected","routeVerified":true]], observation:o)
        XCTAssertNil(h.takeMacPause()); XCTAssertNil(h.transaction)
        XCTAssertTrue(h.status.contains("路由已确认")); XCTAssertTrue(h.status.contains("跳过"))
    }
    func testNoActionWithoutPhoneControlOrWithEndedSession() {
        phone.event += 1; phone.playing = "active"; o.receive(phone.object, epoch: "epoch"); o.evaluate(online: true)
        h.receive(["ready": false], observation: o); h.tick(o, available: true); XCTAssertNil(h.transaction)
        h.receive(["ready": true, "revision": o.target.revision], observation: o); h.tick(o, available: true); XCTAssertNil(h.transaction)
        phone.connection = "connected"; o.mac.connection = "disconnected"; sync()
        o.invalidate(); now += 301; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true); XCTAssertNil(h.transaction)
    }
    func testProtectedPhoneAfterRequestCanReportRouteWithoutOldEndpointPause() throws {
        phoneStarts(); let t = try XCTUnwrap(h.transaction)
        phone.protectedAudio = true; phone.connection = "connected"; o.receive(phone.object, epoch:"epoch")
        h.tick(o, available:true); XCTAssertNotNil(h.transaction)
        h.receive(["ready":true,"revision":o.target.revision,"result":["id":t.id,"revision":o.target.revision,"stage":"connected","routeVerified":true]], observation:o)
        XCTAssertNil(h.takeMacPause()); XCTAssertNil(h.transaction); XCTAssertTrue(h.status.contains("跳过"))
    }

    func testMacProtectionChangeKeepsVerificationButPermanentlyBlocksEffects() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        o.mac.event += 1; sync()
        let t = try XCTUnwrap(h.takeMacAction())
        XCTAssertTrue(h.macCanMutate(t.id, observation: o))
        phone.protectedAudio = true; sync()
        XCTAssertTrue(h.valid(t.id)); XCTAssertEqual(h.phase, "connecting_mac")
        XCTAssertFalse(h.macCanMutate(t.id, observation: o)); XCTAssertNil(h.takeMacAction())
        phone.protectedAudio = false; sync()
        XCTAssertFalse(h.macCanMutate(t.id, observation: o))
        h.macVerified(t.id, observation: o)
        XCTAssertNil(h.transaction); XCTAssertNil(h.object["command"])
        XCTAssertTrue(h.status.contains("路由已确认")); XCTAssertTrue(h.status.contains("跳过"))
    }
    func testMacNewIntentAndStalePhoneAlsoLatchReadOnlyUntilDeadline() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        o.mac.event += 1; sync(); let t = try XCTUnwrap(h.takeMacAction())
        o.mac.event += 1; sync()
        XCTAssertTrue(h.valid(t.id)); XCTAssertFalse(h.macCanMutate(t.id, observation: o))
        now += 5; h.tick(o, available: true)
        XCTAssertNil(h.transaction); h.macVerified(t.id, observation: o); XCTAssertNil(h.object["command"])
        now += 11; sync(); o.mac.event += 1; sync()
        let next = try XCTUnwrap(h.takeMacAction())
        phone.ageMs = 8000; sync()
        now += 2.1; h.tick(o, available: true)
        XCTAssertTrue(h.valid(next.id)); XCTAssertFalse(h.macCanMutate(next.id, observation: o))
    }
    func testMacReadOnlyDoesNotSurviveSessionChangeOrExplicitStop() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        o.mac.event += 1; sync(); let t = try XCTUnwrap(h.takeMacAction())
        o.mac.protectedAudio = true; sync(); XCTAssertTrue(h.valid(t.id))
        o.receive(phone.object, epoch: "new"); h.tick(o, available: true)
        XCTAssertFalse(h.valid(t.id)); XCTAssertNil(h.object["command"])
        o.mac.protectedAudio = false; sync(); o.mac.event += 1; sync()
        let next = try XCTUnwrap(h.takeMacAction())
        o.mac.protectedAudio = true; sync(); h.enabled = false; h.tick(o, available: true)
        XCTAssertFalse(h.valid(next.id)); XCTAssertNil(h.object["command"])
    }

    func testSuccessfulHandoffAllowsImmediateNewReverseIntentButConsumesTransitionEdges() throws {
        phoneStarts(); let first = try XCTUnwrap(h.transaction)
        // A route reconfiguration starts a Mac output while the phone is connecting.
        o.mac.event += 1; sync()
        phone.connection = "connected"; o.mac.connection = "disconnected"; sync()
        h.receive(["ready": true, "revision": o.target.revision, "result": ["id": first.id,
            "revision": o.target.revision, "stage": "connected", "routeVerified": true]], observation: o)
        h.pauseResult(first.id, "跳过")
        sync(); XCTAssertNil(h.takeMacAction()) // No replay of an edge generated during handoff.
        o.mac.event += 1; sync()
        let reverse = try XCTUnwrap(h.takeMacAction()) // No ten-second wait after success.
        XCTAssertEqual(reverse.destination, "mac")
        h.macVerified(reverse.id, observation: o)
        h.receive(["ready": true, "revision": o.target.revision, "result": ["id": reverse.id,
            "revision": o.target.revision, "stage": "pause_handled", "message": "已暂停"]], observation: o)
        XCTAssertNil(h.transaction)
    }

    func testNewReverseIntentDoesNotWaitForBestEffortPause() throws {
        phoneStarts(); let first = try XCTUnwrap(h.transaction)
        phone.connection = "connected"; o.mac.connection = "disconnected"; sync()
        h.receive(["ready": true, "revision": o.target.revision, "result": ["id": first.id,
            "revision": o.target.revision, "stage": "connected", "routeVerified": true]], observation: o)
        XCTAssertEqual(h.phase, "pausing_mac")
        o.mac.event += 1; sync()
        let reverse = try XCTUnwrap(h.takeMacAction())
        XCTAssertNotEqual(reverse.id, first.id); XCTAssertEqual(reverse.destination, "mac")
        h.pauseResult(first.id, "late pause callback")
        XCTAssertEqual(h.transaction?.id, reverse.id)
    }

    func testOfflineMacConnectsOnceWithoutHeartbeatAndNeverQueuesPhonePause() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        let t = try XCTUnwrap(h.takeMacAction()); XCTAssertTrue(t.offlineMac)
        XCTAssertNil(h.takeMacAction()); XCTAssertTrue(h.macCanMutate(t.id, observation: o))
        XCTAssertNil(h.object["command"])
        h.macVerified(t.id, observation: o)
        XCTAssertNil(h.transaction); XCTAssertNil(h.object["command"])
        XCTAssertTrue(h.status.contains("不补发"))
        o.evaluate(online: false); h.tick(o, available: true); XCTAssertNil(h.transaction)
    }
    func testOfflineReconnectWithProtectionLatchesReadOnlyAcrossSessionAndInstance() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        let t = try XCTUnwrap(h.takeMacAction())
        phone.instance = "restarted"; phone.stateSync = true; phone.protectedAudio = true
        o.receive(phone.object, epoch: "new-session"); o.evaluate(online: true); h.tick(o, available: true)
        XCTAssertTrue(h.lifetimeValid(t.id, observation: o)); XCTAssertTrue(h.valid(t.id))
        XCTAssertFalse(h.macCanMutate(t.id, observation: o)); XCTAssertNil(h.object["command"])
        phone.protectedAudio = false; o.receive(phone.object, epoch: "new-session"); o.evaluate(online: true)
        XCTAssertFalse(h.macCanMutate(t.id, observation: o))
        h.macVerified(t.id, observation: o); XCTAssertNil(h.object["command"])
    }
    func testOfflineMacPauseClearsTransactionAndUsage() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        let t = try XCTUnwrap(h.takeMacAction())
        o.invalidate(); h.tick(o, available: true)
        XCTAssertFalse(h.valid(t.id)); XCTAssertFalse(o.offlineUseValid)
        h.macVerified(t.id, observation: o); XCTAssertNil(h.object["command"])
    }

    func testOfflineFailureBlocksRepeatedNewPlaybackUntilFreshConnectedSnapshot() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        now += 8 * 3600; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        let t = try XCTUnwrap(h.takeMacAction())
        h.failed(t.id, "连接失败")
        XCTAssertFalse(o.offlineUseValid); XCTAssertNotNil(o.phoneUseAge)
        for _ in 0..<3 {
            now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
            XCTAssertNil(h.takeMacAction()); XCTAssertNil(h.transaction)
        }
        phone.stateSync = true; phone.usageRecoveryToken = o.usageRecoveryToken; sync()
        XCTAssertTrue(o.offlineUseValid); XCTAssertNil(h.transaction)
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        XCTAssertNotNil(h.takeMacAction())
    }
    func testOfflineTimeoutAlsoBlocksNewPlayback() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        XCTAssertNotNil(h.takeMacAction())
        let t = try XCTUnwrap(h.transaction)
        now = t.deadline - 0.001; h.tick(o, available: true)
        XCTAssertTrue(h.valid(t.id)); XCTAssertTrue(o.offlineUseValid)
        now = t.deadline; h.tick(o, available: true)
        h.macVerified(t.id, observation: o) // Late success cannot undo the timeout.
        XCTAssertFalse(o.offlineUseValid); XCTAssertTrue(o.usageStatus.contains("接管超时"))
        now += 20; o.mac.event += 1; o.evaluate(online: false); h.tick(o, available: true)
        XCTAssertNil(h.transaction)
    }

    func testPresentationOutcomeRequiresRouteProofAndResetsForNewAttempt() throws {
        phoneStarts()
        let first = try XCTUnwrap(h.transaction)
        XCTAssertEqual(h.object["phase"] as? String, "connecting_phone")
        h.failed(first.id, "connection failed")
        XCTAssertEqual(h.object["lastOutcome"] as? String, "failed")
        now += 11; sync() // Reconnection establishes a baseline before the next edge.
        phoneStarts()
        let second = try XCTUnwrap(h.transaction)
        XCTAssertEqual(h.object["lastOutcome"] as? String, "")
        // Optional old-player pause failure must not turn a verified connection into a UI error.
        h.connected(second.id)
        h.pauseResult(second.id, "pause unavailable")
        XCTAssertEqual(h.object["lastOutcome"] as? String, "succeeded")
        XCTAssertEqual(h.object["phase"] as? String, "finished")
    }

    func testMacDestinationInputCanContinueButUnreadabilityLatchesReadOnly() throws {
        o.mac.connection = "disconnected"; phone.connection = "connected"; sync()
        o.mac.audioReadable = true; o.mac.protectedAudio = true
        o.mac.event += 1; sync()
        let t = try XCTUnwrap(h.takeMacAction())
        XCTAssertTrue(h.macCanMutate(t.id, observation: o))
        o.mac.audioReadable = false; sync()
        XCTAssertTrue(h.valid(t.id)); XCTAssertFalse(h.macCanMutate(t.id, observation: o))
        o.mac.audioReadable = true; sync()
        XCTAssertFalse(h.macCanMutate(t.id, observation: o))
    }
    func testProtectedPhoneDestinationCanConfirmRouteAndPauseUnprotectedOldMac() throws {
        phone.audioReadable = true; phone.protectedAudio = true; phoneStarts()
        let t = try XCTUnwrap(h.transaction)
        phone.connection = "connected"; o.mac.connection = "disconnected"; sync()
        h.receive(["ready": true, "revision": o.target.revision,
                   "result": ["id": t.id, "revision": t.target.revision, "stage": "connected", "routeVerified": true]], observation: o)
        XCTAssertEqual(h.takeMacPause()?.id, t.id)
    }
    func testProtectedSourceAfterPhoneRequestStillPreventsOldMacPause() throws {
        phone.audioReadable = true; phoneStarts(); let t = try XCTUnwrap(h.transaction)
        o.mac.audioReadable = true; o.mac.protectedAudio = true
        phone.connection = "connected"; o.mac.connection = "disconnected"; sync()
        h.receive(["ready": true, "revision": o.target.revision,
                   "result": ["id": t.id, "revision": t.target.revision, "stage": "connected", "routeVerified": true]], observation: o)
        XCTAssertNil(h.takeMacPause()); XCTAssertNil(h.transaction)
    }

}
