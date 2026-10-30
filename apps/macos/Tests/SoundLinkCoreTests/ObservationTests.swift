import XCTest
@testable import SoundLinkCore

final class ObservationTests: XCTestCase {
    var directory: URL!
    var engine: ObservationEngine!
    var now: TimeInterval = 100
    var phone = AudioObservation()
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        engine = try ObservationEngine(directory: directory, clock: { self.now })
        try engine.selectMac(address: "00:11:22:33:44:55", name: "Headphones")
        try engine.selectPhone(["revision": engine.target.revision, "address": "AA:BB:CC:DD:EE:FF", "name": "Phone headphones"])
        var mac = AudioObservation(); mac.instance = "mac"; mac.targetRevision = engine.target.revision
        mac.connection = "connected"; mac.playing = "idle"; mac.protectedAudio = false
        engine.mac = mac
        phone.instance = "phone"; phone.targetRevision = engine.target.revision
        phone.connection = "disconnected"; phone.playing = "idle"; phone.protectedAudio = false
        receive()
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }
    func receive(epoch: String = "session") { engine.receive(phone.object, epoch: epoch); engine.evaluate(online: true) }
    func startPhone() { phone.event += 1; phone.playing = "active"; phone.media = true; receive() }
    func testNewMediaSuggestsButDoesNotRepeatAfterReconnect() {
        startPhone(); XCTAssertEqual(engine.title, "候选建议：Mac → 手机")
        now += 11; engine.evaluate(online: false); XCTAssertEqual(engine.title, "暂不建议切换")
        receive(epoch: "new-session"); XCTAssertEqual(engine.title, "暂不建议切换")
        XCTAssertTrue(engine.reason.contains("已有播放"))
    }
    func testNeverSuggestsWhenBothDisconnectedOrUnknown() {
        engine.mac.connection = "disconnected"; startPhone()
        XCTAssertTrue(engine.reason.contains("两端均未连接"))
        engine.mac.connection = "unknown"; startPhone(); XCTAssertTrue(engine.reason.contains("未知"))
    }
    func testNotificationAndProtectedAudioAreBlocked() {
        phone.event += 1; phone.playing = "active"; phone.media = false; receive()
        XCTAssertTrue(engine.reason.contains("不属于"))
        engine.mac.protectedAudio = true; startPhone(); XCTAssertTrue(engine.reason.contains("保护"))
        engine.mac.protectedAudio = false; receive(); XCTAssertEqual(engine.title, "暂不建议切换")
    }
    func testMacOutputIsOnlyCandidateWithClassificationWarning() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        engine.mac.playing = "active"; engine.mac.event += 1; receive()
        XCTAssertEqual(engine.title, "候选建议：手机 → Mac")
        XCTAssertTrue(engine.reason.contains("分类"))
    }
    func testCandidateExpiresAndStopsWhenMediaStops() {
        startPhone(); now += 9; receive(); XCTAssertEqual(engine.title, "暂不建议切换")
        startPhone(); phone.playing = "idle"; receive(); XCTAssertEqual(engine.title, "暂不建议切换")
    }
    func testSimultaneousAndDualConnectionDoNotChooseWinner() {
        engine.mac.event += 1; engine.mac.playing = "active"; startPhone()
        XCTAssertTrue(engine.reason.contains("同时出现"))
        phone.connection = "connected"; startPhone(); XCTAssertTrue(engine.reason.contains("同时连接"))
    }
    func testTargetChangePersistsButRequiresFreshConfirmation() throws {
        startPhone()
        let revision = engine.target.revision
        try engine.selectMac(address: "11:22:33:44:55:66", name: "Other headphones")
        XCTAssertFalse(engine.target.ready)
        XCTAssertThrowsError(try engine.selectPhone(["revision": revision, "address": "AA:BB:CC:DD:EE:FF", "name": "stale"]))
        let restored = try ObservationEngine(directory: directory)
        XCTAssertEqual(restored.target.macName, "Other headphones"); XCTAssertTrue(restored.target.phoneAddress.isEmpty)
        receive(); XCTAssertTrue(engine.reason.contains("确认"))
    }
    func testOldAndMalformedSamplesCannotAuthorizeSuggestion() {
        phone.ageMs = 11000; startPhone(); XCTAssertNil(engine.actionCandidate)
        engine.receive(["playing": "active"], epoch: "session"); engine.evaluate(online: true)
        XCTAssertEqual(engine.title, "暂不建议切换")
        phone.ageMs = -1; XCTAssertNil(AudioObservation.decode(phone.object))
    }
    func testNewMediaCanTakeOverOngoingMusicInEitherDirection() {
        engine.mac.playing = "active"; receive()
        startPhone()
        XCTAssertEqual(engine.title, "候选建议：Mac → 手机")
        XCTAssertTrue(engine.reason.contains("尽力暂停"))
        phone.playing = "idle"; phone.connection = "connected"
        engine.mac.playing = "idle"; engine.mac.connection = "disconnected"; receive()
        phone.playing = "active"; phone.media = true; receive()
        engine.mac.playing = "active"; engine.mac.event += 1; receive()
        XCTAssertEqual(engine.title, "候选建议：手机 → Mac")
        XCTAssertTrue(engine.reason.contains("失败不回滚"))
        now += 9; receive()
        XCTAssertEqual(engine.title, "暂不建议切换", "持续播放不能反向争抢或延长候选")
    }
    func testOfflineMacSessionSurvivesHours() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        now += 20; engine.evaluate(online: false)
        engine.mac.playing = "active"; engine.mac.event += 1; engine.evaluate(online: false)
        XCTAssertEqual(engine.title, "候选建议：手机 → Mac（手机暂离线）")
        XCTAssertEqual(engine.actionCandidate, "mac"); XCTAssertTrue(engine.offlineCandidate)
        now += 9; engine.evaluate(online: false)
        XCTAssertEqual(engine.title, "暂不建议切换")
        now += 8 * 3600; engine.mac.event += 1; engine.evaluate(online: false)
        XCTAssertEqual(engine.actionCandidate, "mac", "会话不能仅因时间流逝失效")
    }
    func testExplicitMacExitAndProtectionBlockOfflineCandidate() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; phone.protectedAudio = true; receive()
        now += 20; engine.mac.playing = "active"; engine.mac.event += 1; engine.evaluate(online: false)
        XCTAssertEqual(engine.title, "暂不建议切换")
        phone.protectedAudio = false; receive()
        now += 20; engine.mac.event += 1; engine.evaluate(online: false, available: false)
        XCTAssertEqual(engine.title, "暂不建议切换")
        engine.evaluate(online: false, available: true)
        XCTAssertEqual(engine.title, "暂不建议切换", "退出后重新加入需要新基线")
    }
    func testReconnectSnapshotsNeverReplayPlaybackEvenWithRecentEventAge() {
        now += 20; engine.evaluate(online: false)
        phone.event += 1; phone.playing = "active"; phone.media = true; phone.eventAgeMs = 1000
        receive(epoch: "renewed")
        XCTAssertEqual(engine.title, "暂不建议切换")
        now += 20; engine.evaluate(online: false); receive(epoch: "renewed-again")
        XCTAssertEqual(engine.title, "暂不建议切换", "已消费序号不能因重连重放")
        now += 20; engine.evaluate(online: false)
        phone.event += 1; phone.eventAgeMs = 20000; receive()
        XCTAssertEqual(engine.title, "暂不建议切换", "新序号但旧事件不补做")
        phone.eventAgeMs = -1; XCTAssertNil(AudioObservation.decode(phone.object))
    }
    func testMissingSampleRecoveryEstablishesBaselineBeforeNewIntent() {
        engine.receive(nil, epoch: "session"); engine.evaluate(online: false)
        phone.event += 1; phone.playing = "active"; phone.media = true; phone.eventAgeMs = 500
        receive(); XCTAssertNil(engine.actionCandidate)
        phone.event += 1; receive()
        XCTAssertEqual(engine.title, "候选建议：Mac → 手机")
    }

    func testRecoveryUpdatesUsageButDoesNotStartHandoff() {
        now += 25; engine.evaluate(online: false)
        engine.mac.connection = "disconnected"
        phone.connection = "connected"; phone.playing = "active"; phone.media = true
        phone.event += 3; phone.stateSync = true; receive(epoch: "renewed")
        XCTAssertNil(engine.actionCandidate); XCTAssertTrue(engine.offlineUseValid)
        XCTAssertEqual(engine.phoneUseAge, 0)
        phone.stateSync = false
        now += 20; engine.mac.playing = "active"; engine.mac.event += 1; engine.evaluate(online: false)
        XCTAssertEqual(engine.actionCandidate, "mac")
    }
    func testUnknownSamplesAndCachedAgeNeverRenewUsageButDisconnectClearsIt() {
        phone.connection = "connected"; engine.mac.connection = "disconnected"; receive()
        now += 5; phone.ageMs = 5000; receive()
        XCTAssertEqual(engine.phoneUseAge, 5)
        now += 5; phone.ageMs = 0; phone.connection = "unknown"; receive()
        XCTAssertEqual(engine.phoneUseAge, 10)
        phone.connection = "disconnected"; receive()
        XCTAssertNil(engine.phoneUseAge); XCTAssertFalse(engine.offlineUseValid)
    }
    func testProtectionDisableAndRestartBlockOfflineUse() throws {
        phone.connection = "connected"; receive()
        engine.allowOfflineMac = false; XCTAssertFalse(engine.offlineUseValid)
        engine.allowOfflineMac = true; XCTAssertTrue(engine.offlineUseValid)
        phone.protectedAudio = true; receive(); XCTAssertFalse(engine.offlineUseValid)
        phone.protectedAudio = false; receive(); now += 8 * 3600; XCTAssertTrue(engine.offlineUseValid)
        receive(); XCTAssertTrue(engine.offlineUseValid)
        let restarted = try ObservationEngine(directory: directory)
        XCTAssertFalse(restarted.offlineUseValid)
        engine.invalidate(); XCTAssertFalse(engine.offlineUseValid)
    }

    func testFailureRequiresPostFailureConnectionEvidenceNotHeartbeatOrCachedSnapshot() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        now += 20; engine.suspendOfflineUse("测试失败", generation: engine.coordinationGeneration)
        XCTAssertFalse(engine.offlineUseValid); XCTAssertNotNil(engine.phoneUseAge)
        now += 2; phone.ageMs = 5000; receive()
        XCTAssertFalse(engine.offlineUseValid, "失败前的缓存连接不能解除阻止")
        phone.ageMs = 0; phone.connection = "unknown"; receive()
        XCTAssertFalse(engine.offlineUseValid)
        phone.connection = "connected"; phone.stateSync = true; receive()
        XCTAssertFalse(engine.offlineUseValid, "未收到失败代次的旧在途快照不能解除阻止")
        phone.usageRecoveryToken = engine.usageRecoveryToken; receive()
        XCTAssertTrue(engine.offlineUseValid); XCTAssertNil(engine.actionCandidate)
    }
    func testLocalConnectionRearmsFailureAndExplicitBothDisconnectedEndsSession() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        now += 20; engine.suspendOfflineUse("连接失败", generation: engine.coordinationGeneration)
        now += 1; engine.mac.connection = "connected"; engine.evaluate(online: false)
        XCTAssertTrue(engine.offlineUseValid)
        phone.connection = "disconnected"; receive()
        XCTAssertNotNil(engine.phoneUseAge, "Mac 仍连接时不能结束使用会话")
        engine.mac.connection = "disconnected"; engine.evaluate(online: true)
        XCTAssertNil(engine.phoneUseAge)
    }

    func testDestinationInputDoesNotBlockMacTakeoverButOwnerInputDoes() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"
        engine.mac.audioReadable = true; engine.mac.protectedAudio = true; receive()
        engine.mac.playing = "active"; engine.mac.event += 1; receive()
        XCTAssertEqual(engine.actionCandidate, "mac")
        phone.audioReadable = true; phone.protectedAudio = true; receive()
        XCTAssertNil(engine.actionCandidate)
        phone.protectedAudio = false; receive()
        XCTAssertNil(engine.actionCandidate, "保护解除不重放已经消费的起播")
    }
    func testDestinationInputDoesNotBlockPhoneMediaButMacOwnerRemainsProtected() {
        phone.audioReadable = true; phone.protectedAudio = true
        startPhone(); XCTAssertEqual(engine.actionCandidate, "phone")
        engine.mac.audioReadable = true; engine.mac.protectedAudio = true
        receive(); XCTAssertNil(engine.actionCandidate)
    }
    func testUnreadableDestinationNeverAuthorizesEvenWhenProtectionFlagIsFalse() {
        phone.audioReadable = false
        startPhone(); XCTAssertNil(engine.actionCandidate)
        phone.audioReadable = true; receive(); XCTAssertNil(engine.actionCandidate)
        startPhone(); XCTAssertEqual(engine.actionCandidate, "phone")
    }
    func testInputChangeAloneNeverStartsTakeover() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        engine.mac.audioReadable = true; engine.mac.protectedAudio = true; receive()
        XCTAssertNil(engine.actionCandidate)
        engine.mac.protectedAudio = false; receive(); XCTAssertNil(engine.actionCandidate)
    }
    func testOfflineMacCanAcquireWithLocalInputButNotUnreadableOutput() {
        engine.mac.connection = "disconnected"; phone.connection = "connected"; receive()
        now += 20; engine.mac.playing = "active"; engine.mac.media = true
        engine.mac.audioReadable = true; engine.mac.protectedAudio = true
        engine.mac.event += 1; engine.evaluate(online: false)
        XCTAssertEqual(engine.actionCandidate, "mac")
        engine.mac.audioReadable = false; engine.evaluate(online: false)
        XCTAssertNil(engine.actionCandidate)
    }
    func testLegacyProtectedSampleRemainsConservativeUntilPeerProvidesReadability() {
        phone.protectedAudio = true; startPhone(); XCTAssertNil(engine.actionCandidate)
        phone.audioReadable = true; startPhone(); XCTAssertEqual(engine.actionCandidate, "phone")
    }

}
