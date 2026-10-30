import Foundation
import CryptoKit

public enum Distribution {
    public static let websiteURL = configuredURL("SoundLinkWebsiteURL")
    public static let baseURL = configuredURL("SoundLinkUpdateBaseURL")
    public static var updatesEnabled: Bool {
        configuredURL("SUFeedURL") != nil &&
        !(Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String ?? "").isEmpty
    }
    private static func configuredURL(_ key: String) -> URL? {
        guard let text = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              let url = URL(string: text), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil
        else { return nil }
        return url
    }
    public static func isPackageURL(_ url: URL, baseURL: URL? = Distribution.baseURL) -> Bool {
        guard let baseURL, baseURL.scheme == "https", baseURL.absoluteString.hasSuffix("/") else { return false }
        return url.scheme == "https" && url.host == baseURL.host && url.port == nil &&
        url.user == nil && url.password == nil && url.query == nil && url.fragment == nil &&
        url.deletingLastPathComponent().absoluteString == baseURL.absoluteString &&
        url.lastPathComponent.hasPrefix("Sound-Link-") && url.pathExtension == "zip"
    }
}

public struct MacRelease: Decodable {
    public let versionCode: Int
    public let versionName: String
    public let minimumSystemVersion: String
    public let url: URL
    public let size: Int
    public let sha256: String

    public static func parse(_ data: Data, baseURL: URL? = Distribution.baseURL) throws -> MacRelease {
        guard data.count <= 65_536 else { throw URLError(.dataLengthExceedsMaximum) }
        let release = try JSONDecoder().decode(Self.self, from: data)
        guard release.versionCode > 0, !release.versionName.isEmpty,
              release.versionName.count <= 64, release.size > 0, release.size <= 100 * 1024 * 1024,
              release.sha256.count == 64, release.sha256.allSatisfy({ "0123456789abcdef".contains($0) }),
              Distribution.isPackageURL(release.url, baseURL: baseURL),
              release.minimumSystemVersion.split(separator: ".", omittingEmptySubsequences: false).count == 2,
              release.minimumSystemVersion.split(separator: ".").allSatisfy({ Int($0).map { $0 >= 0 } ?? false })
        else { throw URLError(.cannotParseResponse) }
        return release
    }

    public func isNewer(than build: Int) -> Bool { versionCode > build }

    public func supports(_ system: OperatingSystemVersion) -> Bool {
        let parts = minimumSystemVersion.split(separator: ".").compactMap { Int($0) }
        return parts.count == 2 && (system.majorVersion > parts[0] ||
            (system.majorVersion == parts[0] && system.minorVersion >= parts[1]))
    }

    public func verify(_ data: Data) -> Bool {
        data.count == size && SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() == sha256
    }
}

public enum MacReleaseClient {
    private static func request(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        return request
    }

    // Stream with a hard bound; neither a bad manifest nor a bad server can fill disk/memory.
    private static func fetch(_ url: URL, limit: Int) async throws -> Data {
        let (bytes, response) = try await URLSession.shared.bytes(for: request(url))
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, http.url == url,
              http.expectedContentLength <= Int64(limit) else { throw URLError(.badServerResponse) }
        var data = Data()
        for try await byte in bytes {
            guard data.count < limit else { throw URLError(.dataLengthExceedsMaximum) }
            data.append(byte)
        }
        return data
    }

    public static func latest() async throws -> MacRelease {
        guard let base = Distribution.baseURL else { throw URLError(.unsupportedURL) }
        return try MacRelease.parse(await fetch(base.appendingPathComponent("macos.json"), limit: 65_536))
    }

    public static func download(_ release: MacRelease) async throws -> Data {
        let data = try await fetch(release.url, limit: release.size)
        guard release.verify(data) else { throw URLError(.cannotDecodeContentData) }
        return data
    }
}
