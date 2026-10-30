import Foundation

public struct HeadphoneTarget: Codable {
    public var revision: Int = 0
    public var macAddress = ""
    public var macName = ""
    public var phoneAddress = ""
    public var phoneName = ""
    // Optional fields keep persisted pre-0.6.5 targets readable.
    public var pendingSide: String?
    public var previousAddress: String?
    public var previousName: String?
    public init() {}
    public var ready: Bool { (pendingSide ?? "").isEmpty && !macAddress.isEmpty && !phoneAddress.isEmpty }
    public func canAutomaticallyMatch(side: String, address: String) -> Bool {
        guard pendingSide == side, let candidate = ObservationEngine.address(address) else { return false }
        let proposed = side == "mac" ? phoneAddress : macAddress
        return candidate == proposed && ((previousAddress ?? "").isEmpty || previousAddress == candidate)
    }
}

public struct AudioObservation: Codable {
    public var instance = ""
    public var event: Int = 0
    public var targetRevision: Int = 0
    public var playing = "unknown"
    public var playback = "尚无播放样本"
    public var connection = "unknown"
    public var connectionDetail = "尚无连接样本"
    public var route = "未知"
    public var protectedAudio = true
    // Separate an observed input from an unreadable sample. Older peers fail closed.
    public var audioReadable: Bool?
    public var isAudioReadable: Bool { playing != "unknown" && (audioReadable ?? !protectedAudio) }
    public var media = false
    public var stateSync: Bool?
    public var localControlGeneration: String?
    public var usageRecoveryToken: String?
    public var eventAgeMs: Double?
    public var ageMs: Double = 0
    public init() {}
    public static func decode(_ object: Any?) -> AudioObservation? {
        guard let object, let data = try? JSONSerialization.data(withJSONObject: object),
              data.count <= 8192, let value = try? JSONDecoder().decode(Self.self, from: data),
              ["unknown", "active", "idle"].contains(value.playing),
              ["unknown", "connected", "disconnected"].contains(value.connection),
              value.event >= 0, value.ageMs.isFinite, value.ageMs >= 0,
              value.eventAgeMs.map({ $0.isFinite && $0 >= 0 }) ?? true,
              (value.localControlGeneration?.count ?? 0) <= 100, (value.usageRecoveryToken?.count ?? 0) <= 100, value.instance.count <= 100, value.playback.count <= 1000,
              value.route.count <= 1000, value.connectionDetail.count <= 1000 else { return nil }
        return value
    }
    public var object: [String: Any] { (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(self))) as? [String: Any] ?? [:] }
    public var description: String {
        let state = connection == "connected" ? "已连接" : connection == "disconnected" ? "未连接" : "未知"
        return "耳机：\(state) · \(connectionDetail)\n播放：\(playback)\n输出：\(route)"
    }
}

public final class ObservationEngine {
    public private(set) var target = HeadphoneTarget()
    public private(set) var phone = AudioObservation()
    public var mac = AudioObservation()
    public private(set) var title = "暂不建议切换"
    public private(set) var reason = "请先选择目标耳机"
    public private(set) var history: [String] = []
    private let file: URL
    private let clock: () -> TimeInterval
    private var received: TimeInterval = -1
    private var epoch = ""
    private var baseline = ""
    private var macEvent = 0, phoneEvent = 0
    private var candidate = ""
    private var candidateUntil: TimeInterval = 0
    private var wasFresh = false
    private var syncBaseline = false
    private var phoneUseAt: TimeInterval?
    private var failedAt: TimeInterval?
    public private(set) var usageRecoveryToken = UUID().uuidString
    public private(set) var offlineFailure = ""
    private var lastPhoneProtected = true
    public private(set) var coordinationGeneration = 0
    public var allowOfflineMac = true
    public var phoneUseAge: Double? { phoneUseAt.map { max(0, clock() - $0) } }
    public var offlineUseValid: Bool { allowOfflineMac && target.ready && !lastPhoneProtected && phoneUseAt != nil && failedAt == nil }
    public var usageStatus: String {
        if failedAt != nil { return "失联接管已暂停：" + offlineFailure + "；等待新的耳机连接确认" }
        guard let age = phoneUseAge else { return "暂无耳机使用会话，失联时不接管" }
        return "使用会话保留 · 最近连接确认距今 \(Int(age)) 秒 · " + (offlineUseValid ? "允许失联接管，无固定到期时间" : "失联开关关闭或存在保护")
    }
    public func suspendOfflineUse(_ reason: String, generation: Int) {
        guard generation == coordinationGeneration else { return }
        failedAt = clock(); usageRecoveryToken = UUID().uuidString; offlineFailure = reason; candidate = ""
    }
    private func confirmUse(at time: TimeInterval, recoveryMatches: Bool = true) {
        phoneUseAt = max(phoneUseAt ?? time, time)
        // A delayed pre-failure snapshot must not re-arm the failed session.
        if let failedAt, time > failedAt, recoveryMatches { self.failedAt = nil; offlineFailure = "" }
    }
    public var offlineCandidate: Bool { candidate == "mac_offline" && clock() < candidateUntil }

    public init(directory: URL, clock: @escaping () -> TimeInterval = { MonotonicTime.now }) throws {
        self.file = directory.appendingPathComponent("observation-target.json"); self.clock = clock
        if FileManager.default.fileExists(atPath: file.path) { target = try JSONDecoder().decode(HeadphoneTarget.self, from: Data(contentsOf: file)) }
    }
    public static func address(_ value: String) -> String? {
        let s = value.uppercased().replacingOccurrences(of: "-", with: ":")
        return s.range(of: "^[0-9A-F]{2}(:[0-9A-F]{2}){5}$", options: .regularExpression) == nil ? nil : s
    }
    private func save(_ value: HeadphoneTarget) throws {
        try JSONEncoder().encode(value).write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        target = value; invalidate()
    }
    public func selectMac(address: String, name: String) throws {
        guard let address = Self.address(address), !name.isEmpty, name.count <= 150 else { throw LinkError("TARGET", "请选择有效耳机") }
        if target.macAddress == address && target.pendingSide != "mac" { return }
        var next = HeadphoneTarget(); next.revision = target.revision + 1
        next.macAddress = address; next.macName = name; next.pendingSide = "phone"
        next.previousAddress = target.pendingSide == "phone" ? target.previousAddress : target.phoneAddress
        next.previousName = target.pendingSide == "phone" ? target.previousName : target.phoneName
        try save(next)
    }
    public func confirmMac(revision: Int, address: String, name: String) throws {
        guard revision == target.revision, target.pendingSide == "mac" else { throw LinkError("TARGET_CHANGED", "目标已变化，请重新选择") }
        guard let address = Self.address(address), !name.isEmpty, name.count <= 150 else { throw LinkError("TARGET", "请选择有效耳机") }
        var next = target; next.revision += 1; next.macAddress = address; next.macName = name
        next.pendingSide = nil; next.previousAddress = nil; next.previousName = nil
        try save(next)
    }
    public func clear() throws { var next = HeadphoneTarget(); next.revision = target.revision + 1; try save(next) }
    public func selectPhone(_ body: [String: Any]) throws {
        guard let revision = body["revision"] as? Int, revision == target.revision else { throw LinkError("TARGET_CHANGED", "目标已变化，请等待状态刷新后重试") }
        guard let raw = body["address"] as? String, let address = Self.address(raw),
              let name = body["name"] as? String, !name.isEmpty, name.count <= 150 else { throw LinkError("TARGET", "请选择有效耳机") }
        let mode = body["mode"] as? String ?? "confirm"
        guard mode == "propose" || mode == "confirm" else { throw LinkError("TARGET", "不支持的耳机选择操作") }
        if mode == "propose" {
            if target.phoneAddress == address && target.pendingSide != "phone" { return }
            var next = HeadphoneTarget(); next.revision = target.revision + 1
            next.phoneAddress = address; next.phoneName = name; next.pendingSide = "mac"
            next.previousAddress = target.pendingSide == "mac" ? target.previousAddress : target.macAddress
            next.previousName = target.pendingSide == "mac" ? target.previousName : target.macName
            try save(next)
        } else {
            guard !target.macAddress.isEmpty, target.pendingSide != "mac" else { throw LinkError("TARGET_CHANGED", "目标已变化，请重新选择") }
            if target.phoneAddress == address && target.ready { return }
            var next = target; next.revision += 1; next.phoneAddress = address; next.phoneName = name
            next.pendingSide = nil; next.previousAddress = nil; next.previousName = nil
            try save(next)
        }
    }
    public func invalidate() { coordinationGeneration += 1; phoneUseAt = nil; failedAt = nil; offlineFailure = ""; lastPhoneProtected = true; syncBaseline = false; wasFresh = false; baseline = ""; candidate = ""; received = -1; epoch = ""; phone = AudioObservation() }
    public func receive(_ object: Any?, epoch: String) {
        guard let value = AudioObservation.decode(object) else { candidate = ""; wasFresh = false; received = -1; phoneUseAt = nil; return }
        let recovering = received < 0 || phoneAge >= 10 || self.epoch != epoch || phone.instance != value.instance || value.stateSync == true || phone.localControlGeneration != value.localControlGeneration
        self.epoch = epoch; phone = value; received = clock()
        if recovering { syncBaseline = true; candidate = "" }
        if value.targetRevision != target.revision { phoneUseAt = nil }
        if !epoch.isEmpty && target.ready && value.targetRevision == target.revision && value.ageMs < 10000 {
            lastPhoneProtected = value.protectedAudio || !value.isAudioReadable
            if value.connection == "connected" { confirmUse(at: clock() - value.ageMs / 1000, recoveryMatches: value.usageRecoveryToken == usageRecoveryToken) }
            else if value.connection == "disconnected" && mac.connection == "disconnected" && mac.ageMs < 10000 { phoneUseAt = nil }
        }
    }
    public var sessionEpoch: String { epoch }
    public var phoneAge: Double { received < 0 ? .infinity : max(0, clock() - received) + phone.ageMs / 1000 }
    public func evaluate(online: Bool, available: Bool = true) {
        let now = clock()
        let key = "\(target.revision)|\(mac.instance)|\(phone.instance)"
        let fresh = available && online && phoneAge < 10 && mac.ageMs < 10000 && !epoch.isEmpty
        if available && target.ready && mac.targetRevision == target.revision && mac.ageMs < 10000 {
            if mac.connection == "connected" { confirmUse(at: now - mac.ageMs / 1000) }
            else if mac.connection == "disconnected" && fresh && phone.targetRevision == target.revision && phone.connection == "disconnected" { phoneUseAt = nil }
        }
        let sameBaseline = !baseline.isEmpty && baseline == key
        let newMac = sameBaseline && mac.event > macEvent
        // A recovery snapshot establishes current state; it is never a new playback intent.
        let newPhone = sameBaseline && fresh && wasFresh && !syncBaseline && phone.event > phoneEvent
        macEvent = mac.event
        if fresh { phoneEvent = phone.event; baseline = key; syncBaseline = false; if candidate == "mac_offline" { candidate = "" } }
        if !sameBaseline || !available { candidate = "" }
        wasFresh = fresh
        var nextTitle = "暂不建议切换"
        var why = "等待新的播放事件；已有播放不会在重连后触发建议"
        if !target.ready { why = target.pendingSide == "mac" ? "等待 Mac 匹配或确认耳机" : target.pendingSide == "phone" ? "等待手机匹配或确认耳机" : "在任一设备选择目标耳机"; candidate = "" }
        else if !available { why = "Mac 已退出协调，等待重新建立基线"; candidate = ""; baseline = ""; phoneUseAt = nil }
        else if !fresh {
            let recentUse = sameBaseline && offlineUseValid && mac.targetRevision == target.revision &&
                mac.connection == "disconnected" && mac.playing == "active" && mac.ageMs < 10000 && mac.isAudioReadable
            if recentUse && newMac { candidate = "mac_offline"; candidateUntil = now + 8 }
            if recentUse && candidate == "mac_offline" && now < candidateUntil {
                nextTitle = "候选建议：手机 → Mac（手机暂离线）"
                why = "使用会话有效，最近连接确认距今 \(Int(phoneUseAge ?? 0)) 秒；Mac 单次连接并验证，不等待手机、不补发暂停。当前手机通话和耳机归属未知"
            } else {
                candidate = ""; why = usageStatus + "；等待合格的新起播，本轮不连接"
            }
        }
        else if mac.targetRevision != target.revision || phone.targetRevision != target.revision { why = "目标耳机版本尚未同步，等待两端确认"; baseline = ""; candidate = "" }
        else if !mac.isAudioReadable || !phone.isAudioReadable || mac.connection == "unknown" || phone.connection == "unknown" || mac.playing == "unknown" || phone.playing == "unknown" { why = "连接或播放状态未知，保守保护，无法可靠判断"; candidate = "" }
        else if mac.connection == "disconnected" && phone.connection == "disconnected" { why = "两端均未连接目标耳机，按规则不介入"; candidate = "" }
        else if mac.connection == "connected" && phone.connection == "connected" { why = "耳机同时连接两端，音频归属需进一步验证"; candidate = "" }
        else if (mac.connection == "connected" && mac.protectedAudio) || (phone.connection == "connected" && phone.protectedAudio) { why = "当前持有耳机的一端存在输入或通话保护，暂不自动抢占"; candidate = "" }
        else {
            if newMac && newPhone { candidate = ""; why = "两端同时出现新播放候选，暂不抢占，避免争抢" }
            else if newPhone && phone.playing == "active" {
                if mac.connection == "connected" && phone.media { candidate = "phone"; candidateUntil = now + 8 }
                else { candidate = ""; why = phone.connection == "connected" ? "新播放发生在耳机已连接的手机，无需切换" : "手机新音频不属于已识别的媒体/游戏，忽略切换" }
            } else if newMac && mac.playing == "active" {
                if phone.connection == "connected" { candidate = "mac"; candidateUntil = now + 8 }
                else { candidate = ""; why = "新播放发生在耳机已连接的 Mac，无需切换" }
            }
            if now >= candidateUntil || (candidate == "phone" && (phone.playing != "active" || !phone.media)) || (candidate == "mac" && mac.playing != "active") { candidate = "" }
            if candidate == "phone" && mac.connection == "connected" {
                nextTitle = "候选建议：Mac → 手机"; why = "手机新媒体优先，可接管 Mac 正在播放的音乐；接管成功后尽力暂停 Mac，失败不回滚。显式外放意图尚不可见；执行仍受授权和能力限制"
            } else if candidate == "mac" && phone.connection == "connected" {
                nextTitle = "候选建议：手机 → Mac"; why = "Mac 新起播优先，可接管手机正在播放的音乐；接管成功后尽力暂停手机，失败不回滚。执行时另行检查媒体/通知分类及路由，显式外放意图尚不可见"
            }
        }
        if nextTitle != title || why != reason {
            let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
            history.insert("\(stamp) · \(nextTitle) · \(why)", at: 0); history = Array(history.prefix(5))
        }
        title = nextTitle; reason = why
    }
    public var actionCandidate: String? {
        guard clock() < candidateUntil, candidate == "phone" || candidate == "mac" || candidate == "mac_offline" else { return nil }
        return candidate == "mac_offline" ? "mac" : candidate
    }
    public var object: [String: Any] {
        let targetObject = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(target))) as? [String: Any] ?? [:]
        return ["v": 1, "mode": "observe_only", "target": targetObject, "mac": mac.object,
                "phone": phone.object, "title": title, "reason": reason, "history": history,
                "usageRecoveryToken": usageRecoveryToken, "usageStatus": usageStatus, "offlineRetryBlocked": failedAt != nil, "usageSessionActive": phoneUseAt != nil, "phoneFresh": phoneAge < 10, "offlineMacAllowed": allowOfflineMac, "phoneUseAgeSeconds": phoneUseAge ?? -1, "offlineUseValid": offlineUseValid, "sampledAt": Date().timeIntervalSince1970]
    }
}
