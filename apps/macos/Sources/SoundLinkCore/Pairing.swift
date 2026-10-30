import Foundation
import CryptoKit

public struct Peer: Codable {
    public let id: String
    public let name: String
    public let publicKey: String
    public let requestID: String
    public let invitationID: String
    public let pairedAt: Date
}
public final class PairingEngine {
    public private(set) var peer: Peer?
    public private(set) var invitationID = ""
    public private(set) var secret = ""
    public private(set) var expires: TimeInterval = 0
    public private(set) var lastSeen: TimeInterval = 0
    public private(set) var heartbeats = 0
    public private(set) var available = true
    public func setAvailable(_ value: Bool) { available = value; suspend() }
    public var changed: (() -> Void)?
    private let directory: URL
    private let clock: () -> TimeInterval
    private var challenges: [String: TimeInterval] = [:]
    private var sessions: [String: (String, TimeInterval)] = [:]
    public init(directory: URL, clock: @escaping () -> TimeInterval = { MonotonicTime.now }) throws {
        self.directory = directory; self.clock = clock
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let file = directory.appendingPathComponent("peer.json")
        if FileManager.default.fileExists(atPath: file.path) { peer = try JSONDecoder().decode(Peer.self, from: Data(contentsOf: file)) }
        refreshInvite()
    }
    public var online: Bool { lastSeen > 0 && clock() - lastSeen < 10 && peer != nil }
    public var secondsLeft: Int { max(0, Int(expires - clock())) }
    /// Keep an unexpired invitation stable; paired devices no longer need an invitation.
    public func refreshExpiredInvite() {
        if peer == nil && (clock() >= expires || secret.isEmpty) { refreshInvite() }
    }
    public func refreshInvite() {
        invitationID = UUID().uuidString.lowercased(); secret = Wire.random(); expires = clock() + 300
        changed?()
    }
    private func save(_ next: Peer?) throws {
        let file = directory.appendingPathComponent("peer.json")
        if let next {
            try JSONEncoder().encode(next).write(to: file, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        } else if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        peer = next
    }
    public func unpair() throws { try save(nil); sessions.removeAll(); challenges.removeAll(); lastSeen = 0; heartbeats = 0; refreshInvite() }
    public func validateUnpair(_ envelope: [String: Any]) throws {
        guard let peer else { throw LinkError("NOT_PAIRED", "配对已解除") }
        let p = try decoded(envelope, publicKey: peer.publicKey)
        guard p["action"] as? String == "unpair", p["client_id"] as? String == peer.id,
              p["pairing_id"] as? String == peer.requestID else { throw LinkError("BAD_REQUEST", "配对已变化") }
    }
    public func suspend() { sessions.removeAll(); challenges.removeAll(); lastSeen = 0; changed?() }
    public func decoded(_ envelope: [String: Any], publicKey: String? = nil) throws -> [String: Any] {
        guard let raw = envelope["payload"] as? String, let data = Wire.decode(raw), data.count <= 8192,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], object["v"] as? Int == 1,
              let encoded = publicKey ?? object["public_key"] as? String, let der = Wire.decode(encoded),
              let sigText = envelope["signature"] as? String, let sig = Wire.decode(sigText) else { throw LinkError("BAD_REQUEST", "无效的配对请求") }
        let key = try P256.Signing.PublicKey(derRepresentation: der)
        let signature = try P256.Signing.ECDSASignature(derRepresentation: sig)
        guard key.isValidSignature(signature, for: Data("SoundLink/1\n".utf8) + data) else { throw LinkError("BAD_SIGNATURE", "设备签名不匹配") }
        return object
    }
    public func pair(_ envelope: [String: Any]) throws -> [String: Any] {
        let p = try decoded(envelope)
        guard p["action"] as? String == "pair", let id = p["client_id"] as? String, UUID(uuidString: id) != nil,
              let name = p["name"] as? String, !name.isEmpty, name.count <= 64, !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              let key = p["public_key"] as? String, let request = p["request_id"] as? String, UUID(uuidString: request) != nil,
              let invite = p["invitation_id"] as? String, let token = p["secret"] as? String else { throw LinkError("BAD_REQUEST", "配对信息不完整") }
        if let peer, peer.id == id && peer.publicKey == key && peer.requestID == request && peer.invitationID == invite {
            return ["state": "paired", "client_id": id, "pairing_id": peer.requestID]
        }
        guard clock() < expires else { throw LinkError("INVITE_EXPIRED", "邀请已过期，请刷新 Mac 二维码。") }
        guard invite == invitationID, !secret.isEmpty, Wire.equal(token, secret) else { throw LinkError("INVITE_INVALID", "二维码已失效，请重新扫码。") }
        guard peer == nil else { throw LinkError("ALREADY_PAIRED", "Mac 已绑定手机，请先在 Mac 解除配对。") }
        // Possession of the signed, unexpired QR invitation authorizes this pairing.
        // Persist before reporting success; consume the invitation and invalidate sessions.
        let next = Peer(id: id, name: name, publicKey: key, requestID: request, invitationID: invite, pairedAt: Date())
        try save(next)
        secret = ""; sessions.removeAll(); challenges.removeAll(); lastSeen = 0
        changed?()
        return ["state": "paired", "client_id": id, "pairing_id": request]
    }
    public func challenge(_ body: [String: Any]) throws -> [String: Any] {
        guard let id = body["client_id"] as? String, id == peer?.id else { throw LinkError("NOT_PAIRED", "此手机尚未与 Mac 配对。") }
        challenges = challenges.filter { $0.value > clock() }
        guard challenges.count < 32 else { throw LinkError("RATE_LIMIT", "请求过多，请稍后重试。") }
        let nonce = Wire.random(); challenges[nonce] = clock() + 30
        return ["nonce": nonce]
    }
    public func session(_ envelope: [String: Any]) throws -> [String: Any] {
        guard available else { throw LinkError("MAC_UNAVAILABLE", "Mac 已暂停协调") }
        guard let peer else { throw LinkError("NOT_PAIRED", "配对已解除。") }
        let p = try decoded(envelope, publicKey: peer.publicKey)
        guard p["action"] as? String == "session", p["client_id"] as? String == peer.id,
              let nonce = p["nonce"] as? String, let until = challenges.removeValue(forKey: nonce), clock() < until else { throw LinkError("CHALLENGE_EXPIRED", "连接验证已过期，请重试。") }
        sessions = sessions.filter { $0.value.1 > clock() }
        if sessions.count >= 8, let oldest = sessions.min(by: { $0.value.1 < $1.value.1 })?.key { sessions.removeValue(forKey: oldest) }
        let token = Wire.random(); let epoch = UUID().uuidString.lowercased()
        sessions[token] = (epoch, clock() + 900)
        return ["token": token, "epoch": epoch, "expires_in": 900]
    }
    public func authorize(_ token: String) throws -> String {
        guard peer != nil, let s = sessions[token], s.1 > clock() else { throw LinkError("SESSION_EXPIRED", "连接认证已过期，请重新连接。") }
        return s.0
    }
    public func ping(_ token: String, body: [String: Any]) throws -> [String: Any] {
        guard available else { throw LinkError("MAC_UNAVAILABLE", "Mac 已暂停协调") }
        let epoch = try authorize(token)
        guard let nonce = body["nonce"] as? String, nonce.count <= 128, !nonce.isEmpty else { throw LinkError("BAD_REQUEST", "无效的心跳") }
        // An authenticated heartbeat refreshes display metadata for existing pairings.
        // The name never participates in identity, key validation, or session generation.
        if let name = body["device_name"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           name.count <= 64, !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
           let current = peer, current.name != name {
            try save(Peer(id: current.id, name: name, publicKey: current.publicKey,
                          requestID: current.requestID, invitationID: current.invitationID, pairedAt: current.pairedAt))
        }
        lastSeen = clock(); heartbeats += 1; changed?()
        return ["state": "connected", "epoch": epoch, "nonce": nonce, "count": heartbeats, "name": Host.current().localizedName ?? "Mac"]
    }
}
