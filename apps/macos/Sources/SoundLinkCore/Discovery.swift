import Foundation
import CryptoKit
import Security
import Darwin

public enum MonotonicTime {
    public static var now: TimeInterval {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1_000_000_000
    }
}

/// Compact, signed endpoint proof. Fits in a single GATT long value (< 200 bytes).
public enum DiscoveryWire {
    public static let challengeUUID = UUID(uuidString: "27491E7A-7F1C-4E92-A002-534C00000001")!
    public static let endpointUUID = UUID(uuidString: "27491E7A-7F1C-4E92-A002-534C00000002")!
    public static let domain = Data("SoundLink/discovery/v1\n".utf8)
    public static func serviceUUID(pin: String) -> UUID {
        var bytes = Array(SHA256.hash(data: domain + Data(pin.utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }
    public static func body(nonce: Data, publicKey: Data, revision: UInt64, port: UInt16, hosts: [String], available: Bool) throws -> Data {
        guard nonce.count == 16, publicKey.count == 65, publicKey.first == 4, hosts.count <= 4,
              hosts.allSatisfy(LocalAddress.isPrivate), !available || (port != 0 && !hosts.isEmpty) else {
            throw LinkError("DISCOVERY_FORMAT", "地址描述格式错误")
        }
        var body = Data([1, available ? 1 : 0])
        for shift in stride(from: 56, through: 0, by: -8) { body.append(UInt8(truncatingIfNeeded: revision >> shift)) }
        body += nonce; body += publicKey
        body += Data([UInt8(port >> 8), UInt8(port & 255), UInt8(hosts.count)])
        for host in hosts { body += Data(host.split(separator: ".").map { UInt8($0)! }) }
        return body
    }
    public static func packet(body: Data, signature: Data) throws -> Data {
        guard (64...72).contains(signature.count), body.count <= 110 else { throw LinkError("DISCOVERY_FORMAT", "签名描述过大") }
        return body + Data([UInt8(signature.count)]) + signature
    }
}

extension DeviceIdentity {
    public func endpointProof(nonce: Data, revision: UInt64, port: UInt16, hosts: [String], available: Bool) throws -> Data {
        let publicKey = try P256.Signing.PublicKey(derRepresentation: publicKeyDER).x963Representation
        let body = try DiscoveryWire.body(nonce: nonce, publicKey: publicKey, revision: revision, port: port, hosts: hosts, available: available)
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, (DiscoveryWire.domain + body) as CFData, &error) as Data? else {
            throw LinkError("DISCOVERY_SIGNATURE", "无法签名最新地址")
        }
        return try DiscoveryWire.packet(body: body, signature: signature)
    }
}
