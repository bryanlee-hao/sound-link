import Foundation

/// One bounded transaction per new edge. No Bluetooth or player calls in the policy layer.
public final class HandoffEngine {
    public struct Transaction {
        public let id: String, destination: String, epoch: String, target: HeadphoneTarget
        public let mac: AudioObservation, phone: AudioObservation
        public let deadline: TimeInterval
        public let offlineMac: Bool
        public let generation: Int
    }
    public var enabled = false
    public private(set) var transaction: Transaction?
    public private(set) var phase = "idle"
    public private(set) var lastOutcome = ""
    public private(set) var status = "自动切换已关闭"
    public private(set) var history: [String] = []
    public private(set) var phoneReady = false
    public private(set) var macAction: Transaction?
    public private(set) var macPause: Transaction?
    private weak var observation: ObservationEngine?
    public private(set) var phoneLocalActive = false
    private var phoneLocalGeneration: String?
    private var seen = Set<String>()
    private var cooldown: TimeInterval = 0
    private var routeProven = false
    private var latest: [(String, Int)] = []
    private var consumedThrough: [String: Int] = [:]
    private func remember(_ o: ObservationEngine) {
        observation = o
        latest = [("\(o.target.revision)|mac|\(o.mac.instance)", o.mac.event), ("\(o.target.revision)|phone|\(o.phone.instance)", o.phone.event)]
    }
    private func consumeObserved() {
        for (key, event) in latest { consumedThrough[key] = max(consumedThrough[key] ?? -1, event) }
        if consumedThrough.count > 64 { consumedThrough = Dictionary(uniqueKeysWithValues: latest) }
    }
    private var pauseUntil: TimeInterval = 0
    public private(set) var macReadOnly = false
    private let clock: () -> TimeInterval
    public var changed: ((String, [String: Any]) -> Void)?
    public init(clock: @escaping () -> TimeInterval = { MonotonicTime.now }) { self.clock = clock }
    private func report(_ message: String) {
        status = message
        history.insert(DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium) + " · " + message, at: 0)
        history = Array(history.prefix(6))
        changed?(message, ["id": transaction?.id ?? "", "phase": phase, "offlineMac": transaction?.offlineMac ?? false, "usageStatus": observation?.usageStatus ?? "", "offlineRetryBlocked": !(observation?.offlineFailure.isEmpty ?? true)])
    }
    public func cancel(_ reason: String) {
        lastOutcome = ""
        if transaction != nil { consumeObserved(); phase = "cancelled"; report(reason) }
        transaction = nil; macAction = nil; macPause = nil
    }
    public func receive(_ value: Any?, observation: ObservationEngine) {
        remember(observation)
        guard let body = value as? [String: Any] else { phoneReady = false; phoneLocalActive = false; return }
        phoneReady = body["ready"] as? Bool == true && body["revision"] as? Int == observation.target.revision
        let localChanged = phoneLocalGeneration != observation.phone.localControlGeneration
        phoneLocalGeneration = observation.phone.localControlGeneration
        phoneLocalActive = observation.phoneAge < 10 && observation.phone.targetRevision == observation.target.revision && phoneReady && (body["localTakeover"] as? [String: Any])?["active"] as? Bool == true
        if phoneLocalActive || localChanged { cancel("手机独立接管状态变化 · 取消重复协调"); consumeObserved(); return }
        guard let t = transaction, t.epoch == observation.sessionEpoch, clock() < t.deadline,
              let result = body["result"] as? [String: Any], result["id"] as? String == t.id,
              result["revision"] as? Int == t.target.revision else { return }
        if phase == "connecting_phone", result["stage"] as? String == "connected",
           result["routeVerified"] as? Bool == true {
            // The authenticated result is also corroborated by the current phone observation.
            guard observation.phoneAge < 10, observation.phone.connection == "connected",
                  observation.phone.instance == t.phone.instance else { return }
            routeProven = true
            if observation.phone.event != t.phone.event || !observation.phone.media || observation.phone.playing != "active" || !observation.phone.isAudioReadable || observation.mac.protectedAudio || !observation.mac.isAudioReadable {
                finish("手机路由已确认 · 起播或保护状态已变化，跳过旧端暂停")
            } else { connected(t.id) }
        } else if phase == "connecting_phone", result["stage"] as? String == "failed" {
            finish("手机接管未完成：" + String((result["message"] as? String ?? "连接失败").prefix(180)))
        } else if phase == "pausing_phone", result["stage"] as? String == "pause_handled" {
            finish("Mac 已接管 · " + String((result["message"] as? String ?? "旧端暂停已处理").prefix(180)))
        }
    }
    public func tick(_ o: ObservationEngine, available: Bool) {
        remember(o)
        let now = clock()
        if !enabled || !available || !o.target.ready {
            cancel("协调已停止，取消未完成动作")
            if !enabled { status = "自动切换已关闭" }
            return
        }
        if phoneLocalActive && o.phoneAge < 10 {
            consumeObserved(); status = "手机正在独立接管 · 等待状态同步"; return
        }
        if status == "自动切换已关闭" { status = "自动切换已开启 · 等待新起播" }
        // Best-effort pause must not block a genuinely new intent after route proof.
        if let t = transaction, phase.hasPrefix("pausing"), let direction = o.actionCandidate, direction != t.destination {
            let sample = direction == "mac" ? o.mac : o.phone
            let sourceKey = "\(o.target.revision)|\(direction)|\(sample.instance)"
            if sample.event > (consumedThrough[sourceKey] ?? -1) {
                finish("接管已完成 · 新起播优先，跳过剩余暂停", consume: false)
            }
        }
        if let t = transaction {
            if !phoneReady { cancel("手机耳机控制已停止，取消旧动作"); return }
            if !lifetimeValid(t.id, observation: o) {
                cancel("目标或观察实例已变化，取消旧动作"); return
            }
            if now >= t.deadline || (phase.hasPrefix("pausing") && now >= pauseUntil) {
                finish(phase.hasPrefix("pausing") ? "接管成功 · 旧端暂停未确认，已跳过" : "接管超时（5 秒）· 不重试、不回滚"); return
            }
            if phase == "connecting_mac" { _ = macCanMutate(t.id, observation: o) }
            return
        }
        guard let direction = o.actionCandidate else { return }
        let event = direction == "phone" ? o.phone : o.mac
        let key = "\(o.target.revision)|\(direction)|\(event.instance)|\(event.event)"
        let sourceKey = "\(o.target.revision)|\(direction)|\(event.instance)"
        guard event.event > (consumedThrough[sourceKey] ?? -1) else { return }
        guard !seen.contains(key) else { return }
        seen.insert(key)
        if seen.count > 512 { seen = [key] }
        guard now >= cooldown else { report("切换冷却中 · 忽略本轮新起播"); return }
        let offline = direction == "mac" && o.offlineCandidate && o.offlineUseValid
        guard phoneReady, offline || o.phoneAge < 10 else { report("等待手机控制授权及新鲜状态 · 本轮不连接"); return }
        guard direction != "mac" || o.mac.media else { report("Mac 当前没有新的输出候选 · 不接管"); return }
        let t = Transaction(id: UUID().uuidString, destination: direction, epoch: o.sessionEpoch, target: o.target, mac: o.mac, phone: o.phone, deadline: now + 5, offlineMac: offline, generation: o.coordinationGeneration)
        macReadOnly = false; routeProven = false
        lastOutcome = ""; transaction = t; phase = direction == "phone" ? "connecting_phone" : "connecting_mac"
        report(direction == "phone" ? "手机正在接管 · 等待耳机及媒体路由" : offline ? "Mac 离线接管 · 单次连接，不补发手机暂停" : "Mac 正在接管 · 等待耳机及输出路由")
        if direction == "mac" { macAction = t }
    }
    /// Once intent/protection changes, only bounded reads remain, even if it changes back.
    public func macCanMutate(_ id: String, observation o: ObservationEngine) -> Bool {
        guard valid(id), let t = transaction, t.destination == "mac" else { return false }
        if !macReadOnly && (o.mac.event != t.mac.event || o.mac.playing != "active" ||
            !o.mac.media || !o.mac.isAudioReadable ||
            (t.offlineMac ? (!o.allowOfflineMac || (o.phoneAge < 10 && (o.phone.protectedAudio || !o.phone.isAudioReadable || o.phone.event != t.phone.event || o.phone.connection != "connected")))
                          : (o.phoneAge >= 10 || o.phone.protectedAudio || !o.phone.isAudioReadable || o.phone.event != t.phone.event))) {
            macReadOnly = true
            report("起播或保护状态变化 · 仅验证已提交连接，不再改路由或暂停旧端")
        }
        return !macReadOnly
    }
    public func macVerified(_ id: String, observation o: ObservationEngine) {
        guard valid(id), phase == "connecting_mac" else { return }
        remember(o); routeProven = true
        if transaction?.offlineMac == true { finish("Mac 路由已确认 · 失联接管完成，不补发手机暂停") }
        else if macCanMutate(id, observation: o) { connected(id) }
        else { finish("Mac 路由已确认 · 状态曾变化，跳过旧端暂停") }
    }
    public func lifetimeValid(_ id: String, observation o: ObservationEngine) -> Bool {
        guard enabled, let t = transaction, t.id == id, t.generation == o.coordinationGeneration,
              t.target.revision == o.target.revision, t.mac.instance == o.mac.instance else { return false }
        return t.offlineMac || (t.epoch == o.sessionEpoch && t.phone.instance == o.phone.instance)
    }
    public func takeMacAction() -> Transaction? { defer { macAction = nil }; return macAction }
    public func takeMacPause() -> Transaction? { defer { macPause = nil }; return macPause }
    public func valid(_ id: String) -> Bool { enabled && transaction?.id == id && clock() < (transaction?.deadline ?? 0) }
    public func connected(_ id: String) {
        guard valid(id), let t = transaction, phase.hasPrefix("connecting") else { return }
        routeProven = true; consumeObserved()
        pauseUntil = min(t.deadline, clock() + 5)
        phase = t.destination == "phone" ? "pausing_mac" : "pausing_phone"
        report(t.destination == "phone" ? "手机已接管 · 尽力暂停 Mac" : "Mac 已接管 · 尽力暂停手机")
        if t.destination == "phone" { macPause = t }
    }
    public func failed(_ id: String, _ reason: String) { if valid(id) { finish(reason) } }
    public func pauseResult(_ id: String, _ reason: String) { if valid(id) { finish("手机已接管 · " + reason) } }
    private func finish(_ message: String, consume: Bool = true) {
        if let t = transaction, t.offlineMac, !routeProven {
            observation?.suspendOfflineUse(message, generation: t.generation)
        }
        lastOutcome = routeProven ? "succeeded" : "failed"
        if consume { consumeObserved() }; phase = "finished"; report(message); transaction = nil; macAction = nil; macPause = nil; cooldown = routeProven ? 0 : clock() + 10
    }
    public var object: [String: Any] {
        var result: [String: Any] = ["enabled": enabled, "status": status, "history": history, "phoneReady": phoneReady, "phase": phase, "lastOutcome": lastOutcome]
        if let t = transaction, valid(t.id), phase == "connecting_phone" || phase == "pausing_phone" {
            result["command"] = ["id": t.id, "epoch": t.epoch, "action": phase == "connecting_phone" ? "connect" : "pause", "revision": t.target.revision,
                "address": t.target.phoneAddress, "instance": t.phone.instance, "event": t.phone.event,
                "ttlMs": max(0, Int((min(t.deadline, phase == "pausing_phone" ? pauseUntil : t.deadline) - clock()) * 1000))]
        }
        return result
    }
}
