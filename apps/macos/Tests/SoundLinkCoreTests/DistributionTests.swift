import XCTest
import CryptoKit
@testable import SoundLinkCore

final class DistributionTests: XCTestCase {
    private let baseURL = URL(string: "https://updates.example.test/sound-link/downloads/")!
    private func parse(_ data: Data) throws -> MacRelease { try MacRelease.parse(data, baseURL: baseURL) }
    func testUnconfiguredBuildCannotUpdate() throws {
        XCTAssertThrowsError(try MacRelease.parse(manifest(), baseURL: nil))
        XCTAssertFalse(Distribution.updatesEnabled)
    }
    private let payload = Data("test archive".utf8)
    private func manifest(_ changes: [String: Any] = [:]) throws -> Data {
        var value: [String: Any] = ["versionCode": 501, "versionName": "0.5.1", "minimumSystemVersion": "14.0",
            "url": "https://updates.example.test/sound-link/downloads/Sound-Link-0.5.1-macOS.zip", "size": payload.count,
            "sha256": SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()]
        value.merge(changes) { _, new in new }
        return try JSONSerialization.data(withJSONObject: value)
    }
    func testVersionAndCompatibility() throws {
        let release = try parse(manifest())
        XCTAssertTrue(release.isNewer(than: 500))
        XCTAssertFalse(release.isNewer(than: 501))
        XCTAssertFalse(release.isNewer(than: 502))
        XCTAssertTrue(release.supports(.init(majorVersion: 14, minorVersion: 0, patchVersion: 0)))
        XCTAssertFalse(release.supports(.init(majorVersion: 13, minorVersion: 6, patchVersion: 0)))
    }
    func testPayloadIntegrity() throws {
        let release = try parse(manifest())
        XCTAssertTrue(release.verify(payload))
        XCTAssertFalse(release.verify(Data("bad! archive".utf8)))
        XCTAssertFalse(release.verify(payload + Data([0])))
    }
    func testRejectsUntrustedURLs() throws {
        for url in ["https://updates.example.test/downloads/sound-link/Sound-Link-1.zip",
                    "https://other.example.test/sound-link/downloads/Sound-Link-1.zip",
                    "http://updates.example.test/sound-link/downloads/Sound-Link-1.zip",
                    "https://example.com/sound-link/downloads/Sound-Link-1.zip",
                    "https://updates.example.test/private/Sound-Link-1.zip",
                    "https://updates.example.test/sound-link/downloads/Sound-Link-1.zip?redirect=1",
                    "https://user@updates.example.test/sound-link/downloads/Sound-Link-1.zip"] {
            XCTAssertThrowsError(try parse(manifest(["url": url])))
        }
    }
    func testRejectsMalformedMetadata() throws {
        for change: [String: Any] in [["size": 0], ["size": 100 * 1024 * 1024 + 1], ["sha256": "wrong"],
                                     ["versionCode": -1], ["minimumSystemVersion": "14..0"], ["minimumSystemVersion": "-1.0"]] {
            XCTAssertThrowsError(try parse(manifest(change)))
        }
        XCTAssertThrowsError(try parse(Data(repeating: 32, count: 65_537)))
    }
}
