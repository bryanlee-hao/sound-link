import Foundation

/// Detects main-queue stalls. The TLS listener, its connections and the BLE peripheral all run on
/// `.main`, so a blocked main queue makes the phone see CONNECT_TLS / GATT timeouts.
/// Uses uptime (excludes system sleep) so sleeping is never reported as a stall.
final class MainThreadWatchdog {
    static let probeInterval: DispatchTimeInterval = .seconds(1)
    static let stallThresholdMs: UInt64 = 500

    private let queue = DispatchQueue(label: "app.soundlink.mac.watchdog", qos: .utility)
    private var timer: DispatchSourceTimer?
    private var probeInFlight = false // Accessed on `queue` only.

    /// `report` is called on the main queue once the stalled main queue runs again.
    func start(report: @escaping (_ lagMs: UInt64) -> Void) {
        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(deadline: .now() + Self.probeInterval, repeating: Self.probeInterval, leeway: .milliseconds(100))
        source.setEventHandler { [weak self] in self?.probe(report: report) }
        timer = source
        source.resume()
    }

    func stop() { timer?.cancel(); timer = nil }

    private func probe(report: @escaping (UInt64) -> Void) {
        guard !probeInFlight else { return } // A still-pending probe already measures the stall.
        probeInFlight = true
        let sentAt = DispatchTime.now().uptimeNanoseconds
        DispatchQueue.main.async { [weak self] in
            let lagMs = (DispatchTime.now().uptimeNanoseconds - sentAt) / 1_000_000
            if lagMs >= Self.stallThresholdMs { report(lagMs) }
            self?.queue.async { self?.probeInFlight = false }
        }
    }
}
