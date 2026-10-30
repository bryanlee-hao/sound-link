import XCTest
import CryptoKit
@testable import SoundLinkCore

final class DiscoveryTests: XCTestCase {
    func fixture() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../packages/protocol/fixtures/discovery-v1.json").standardized
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }
    func hex(_ text: String) -> Data {
        var result = Data(); var i = text.startIndex
        while i < text.endIndex { let next = text.index(i, offsetBy: 2); result.append(UInt8(text[i..<next], radix: 16)!); i = next }
        return result
    }
    func testStableUUIDAndSignedPacketMatchesAndroidVector() throws {
        let f = try fixture()
        XCTAssertEqual(DiscoveryWire.serviceUUID(pin: f["pin"] as! String).uuidString.lowercased(), f["service_uuid"] as? String)
        let body = try DiscoveryWire.body(nonce: hex(f["nonce"] as! String), publicKey: hex(f["public_key"] as! String), revision: 7, port: 49777, hosts: ["10.0.0.8", "192.168.8.1"], available: true)
        XCTAssertEqual(body, hex(f["body"] as! String))
        let packet = hex(f["packet"] as! String)
        let signature = packet.suffix(Int(packet[body.count]))
        let key = try P256.Signing.PublicKey(x963Representation: hex(f["public_key"] as! String))
        XCTAssertTrue(key.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation: signature), for: DiscoveryWire.domain + body))
        XCTAssertEqual(try DiscoveryWire.packet(body: body, signature: signature), packet)
        XCTAssertLessThan(packet.count, 200)
    }
    func testEndpointBoundsAndUnavailableState() throws {
        let key = P256.Signing.PrivateKey().publicKey.x963Representation, nonce = Data(repeating: 0, count: 16)
        XCTAssertThrowsError(try DiscoveryWire.body(nonce: Data(), publicKey: key, revision: 1, port: 1, hosts: ["10.0.0.1"], available: true))
        XCTAssertThrowsError(try DiscoveryWire.body(nonce: nonce, publicKey: key, revision: 1, port: 1, hosts: ["8.8.8.8"], available: true))
        XCTAssertThrowsError(try DiscoveryWire.body(nonce: nonce, publicKey: key, revision: 1, port: 0, hosts: [], available: true))
        XCTAssertThrowsError(try DiscoveryWire.body(nonce: nonce, publicKey: key, revision: 1, port: 1, hosts: Array(repeating: "10.0.0.1", count: 5), available: true))
        XCTAssertEqual(try DiscoveryWire.body(nonce: nonce, publicKey: key, revision: 1, port: 0, hosts: [], available: false).count, 94)
    }
}
