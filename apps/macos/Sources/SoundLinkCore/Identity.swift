import Foundation
import Security
import CryptoKit

public struct LinkError: Error, LocalizedError {
    public let code: String
    public let message: String
    public var errorDescription: String? { message }
    public init(_ code: String, _ message: String) { self.code = code; self.message = message }
}
public enum Wire {
    public static func b64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    public static func decode(_ text: String) -> Data? {
        guard text.count <= 24000, text.range(of: "^[A-Za-z0-9_-]*$", options: .regularExpression) != nil else { return nil }
        let s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: s + String(repeating: "=", count: (4 - s.count % 4) % 4))
    }
    public static func random(_ count: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        precondition(SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess)
        return b64(Data(bytes))
    }
    public static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    public static func equal(_ a: String, _ b: String) -> Bool {
        let x = Array(SHA256.hash(data: Data(a.utf8))), y = Array(SHA256.hash(data: Data(b.utf8)))
        return zip(x, y).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
    public static func json(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]) }
}

// Minimal DER encoder for a self-signed P-256 TLS leaf. Cryptography remains in Security.framework.
enum DER {
    static func node(_ tag: UInt8, _ data: Data) -> Data {
        var length = data.count
        var bytes: [UInt8] = []
        if length < 128 { bytes = [UInt8(length)] }
        else { while length > 0 { bytes.insert(UInt8(length & 255), at: 0); length >>= 8 }; bytes.insert(0x80 | UInt8(bytes.count), at: 0) }
        return Data([tag] + bytes) + data
    }
    static func seq(_ values: Data...) -> Data { node(0x30, values.reduce(Data(), +)) }
    static func oid(_ bytes: [UInt8]) -> Data { node(0x06, Data(bytes)) }
    static let ec = seq(oid([0x2a,0x86,0x48,0xce,0x3d,0x02,0x01]), oid([0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07]))
    static let ecdsa = seq(oid([0x2a,0x86,0x48,0xce,0x3d,0x04,0x03,0x02]))
}

public final class DeviceIdentity {
    public let key: SecKey
    public let certificate: SecCertificate
    public let identity: SecIdentity
    public let publicKeyDER: Data
    public let id: String
    public let pin: String
    public let hostname: String
    public init(tag: String = "app.soundlink.mac.identity.v1") throws {
        let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: Data(tag.utf8), kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true]
        var found: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary, &found)
        if result == errSecSuccess { key = (found as! SecKey) }
        else if result == errSecItemNotFound {
            var error: Unmanaged<CFError>?
            guard let generated = SecKeyCreateRandomKey([
                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits as String: 256,
                kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: Data(tag.utf8), kSecAttrLabel as String: "Sound Link device identity"]
            ] as CFDictionary, &error) else { throw error!.takeRetainedValue() }
            key = generated
        } else { throw LinkError("KEYCHAIN", "无法读取设备身份（\(result)），请解锁钥匙串后重试。") }
        var error: Unmanaged<CFError>?
        guard let pub = SecKeyCopyPublicKey(key), let raw = SecKeyCopyExternalRepresentation(pub, &error) as Data? else { throw LinkError("KEYCHAIN", "无法读取公钥") }
        publicKeyDER = DER.seq(DER.ec, DER.node(0x03, Data([0]) + raw))
        pin = Wire.digest(publicKeyDER); id = String(pin.prefix(32)); hostname = "\(id).soundlink.local"
        let label = tag + ".certificate"
        var saved: CFTypeRef?
        let certQuery: [String: Any] = [kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: label, kSecReturnRef as String: true]
        if SecItemCopyMatching(certQuery as CFDictionary, &saved) == errSecSuccess {
            certificate = (saved as! SecCertificate)
        } else {
            let name = DER.seq(DER.node(0x31, DER.seq(DER.oid([0x55,0x04,0x03]), DER.node(0x0c, Data(hostname.utf8)))))
            let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX"); format.timeZone = TimeZone(secondsFromGMT: 0); format.dateFormat = "yyyyMMddHHmmss'Z'"
            let validity = DER.seq(DER.node(0x18, Data(format.string(from: Date().addingTimeInterval(-86400)).utf8)), DER.node(0x18, Data(format.string(from: Date().addingTimeInterval(3650 * 86400)).utf8)))
            let san = DER.seq(DER.oid([0x55,0x1d,0x11]), DER.node(0x04, DER.seq(DER.node(0x82, Data(hostname.utf8)))))
            let constraints = DER.seq(DER.oid([0x55,0x1d,0x13]), DER.node(0x01, Data([0xff])), DER.node(0x04, DER.seq()))
            let usage = DER.seq(DER.oid([0x55,0x1d,0x0f]), DER.node(0x01, Data([0xff])), DER.node(0x04, DER.node(0x03, Data([7,0x80]))))
            let extended = DER.seq(DER.oid([0x55,0x1d,0x25]), DER.node(0x04, DER.seq(DER.oid([0x2b,0x06,0x01,0x05,0x05,0x07,0x03,0x01]))))
            let serial = Data([0]) + Data(SHA256.hash(data: publicKeyDER).prefix(16))
            let tbs = DER.seq(DER.node(0xa0, DER.node(0x02, Data([2]))), DER.node(0x02, serial), DER.ecdsa, name, validity, name, publicKeyDER, DER.node(0xa3, DER.seq(san, constraints, usage, extended)))
            guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) as Data?,
                  let cert = SecCertificateCreateWithData(nil, DER.seq(tbs, DER.ecdsa, DER.node(0x03, Data([0]) + signature)) as CFData) else { throw LinkError("CERTIFICATE", "无法生成本机 TLS 身份") }
            let status = SecItemAdd([kSecClass as String: kSecClassCertificate, kSecValueRef as String: cert, kSecAttrLabel as String: label] as CFDictionary, nil)
            guard status == errSecSuccess || status == errSecDuplicateItem else { throw LinkError("KEYCHAIN", "无法保存证书（\(status)）") }
            certificate = cert
        }
        var local: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &local)
        guard status == errSecSuccess, let local else { throw LinkError("KEYCHAIN", "无法建立 TLS 身份（\(status)）") }
        identity = local
    }
}
