import Foundation

/// Player-independent output edges. A new output qualifies only after it has run
/// continuously for `sustainSeconds`, so short alerts and UI sounds never count.
public struct OutputEdges {
    public static let defaultSustainSeconds: TimeInterval = 1.5
    public private(set) var event = 0
    public private(set) var eligible = false
    private let sustainSeconds: TimeInterval
    private var initialized = false
    private var previous = Set<UInt32>()
    private var pendingSince: [UInt32: TimeInterval] = [:]
    private var qualifying = Set<UInt32>()
    public init(sustainSeconds: TimeInterval = OutputEdges.defaultSustainSeconds) {
        self.sustainSeconds = sustainSeconds
    }
    public mutating func resetBaseline() {
        initialized = false; previous = []; pendingSince = [:]; qualifying = []; eligible = false
    }
    public mutating func update(active: Set<UInt32>, readable: Bool = true, now: TimeInterval) {
        guard readable else { resetBaseline(); return }
        if !initialized { initialized = true; previous = active; return }
        for process in active.subtracting(previous) { pendingSince[process] = now }
        // A pending output that stops before the threshold was a short sound.
        pendingSince = pendingSince.filter { active.contains($0.key) }
        let sustained = Set(pendingSince.filter { now - $0.value >= sustainSeconds }.keys)
        if !sustained.isEmpty {
            event += 1; qualifying = sustained
            pendingSince = pendingSince.filter { !sustained.contains($0.key) }
        }
        qualifying.formIntersection(active)
        eligible = !qualifying.isEmpty
        previous = active
    }
}

/// System alert sources never represent a user starting playback.
public enum SystemSoundSources {
    private static let identifiers: Set<String> = [
        "systemsoundserverd", "com.apple.systemsoundserverd",
        "com.apple.notificationcenterui", "com.apple.usernotificationcenter",
        "com.apple.coreservices.uiagent",
    ]
    public static func isSystemSound(_ identifier: String) -> Bool {
        identifiers.contains(identifier.lowercased())
    }
}
