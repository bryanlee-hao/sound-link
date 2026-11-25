import Foundation

/// Per-connection server timing kept only to explain phone-side CONNECT_TLS stalls.
/// Never records peer addresses, headers, tokens or bodies.
public struct ConnectionTrace {
    /// Handshake or request arrival at or beyond this is reported even when the request was served.
    public static let slowSeconds: TimeInterval = 1
    public static let maxPathLength = 64

    public let acceptedAt: TimeInterval
    public let liveAtAccept: Int
    public private(set) var readyAt: TimeInterval?
    public private(set) var requestAt: TimeInterval?
    public private(set) var path: String?

    public init(acceptedAt: TimeInterval, liveAtAccept: Int) {
        self.acceptedAt = acceptedAt
        self.liveAtAccept = liveAtAccept
    }

    public func ready(at time: TimeInterval) -> ConnectionTrace {
        var next = self; next.readyAt = readyAt ?? time; return next
    }

    public func request(at time: TimeInterval, path rawPath: String) -> ConnectionTrace {
        var next = self
        next.requestAt = requestAt ?? time
        next.path = String((rawPath.split(separator: "?", maxSplits: 1).first ?? "").prefix(Self.maxPathLength))
        return next
    }

    /// Served connections stay silent unless the handshake or request arrival was slow.
    public func report(outcome: String, error: String? = nil, now: TimeInterval, live: Int) -> [String: Any]? {
        let handshake = readyAt.map { $0 - acceptedAt }
        let arrival = requestAt.map { $0 - acceptedAt }
        let slow = (handshake ?? 0) >= Self.slowSeconds || (arrival ?? 0) >= Self.slowSeconds
        guard outcome != "served" || slow else { return nil }
        var data: [String: Any] = [
            "outcome": outcome,
            "handshake_ms": Self.milliseconds(handshake),
            "request_ms": Self.milliseconds(arrival),
            "total_ms": Self.milliseconds(now - acceptedAt),
            "live_at_accept": liveAtAccept,
            "live": live,
        ]
        if let path { data["path"] = path }
        if let error { data["error"] = error }
        return data
    }

    private static func milliseconds(_ seconds: TimeInterval?) -> Int {
        guard let seconds, seconds.isFinite else { return -1 }
        return Int((max(0, seconds) * 1000).rounded())
    }
}
