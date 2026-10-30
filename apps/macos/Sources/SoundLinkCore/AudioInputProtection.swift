/// A process name is never sufficient to exempt an input from protection.
public enum AudioInputProtection: String {
    case deviceInput, systemPlaybackCapture, unknown

    public var protectsOwner: Bool { self != .systemPlaybackCapture }

    /// Only a proven tap aggregate with no input-capable subdevices is exempt.
    /// Virtual microphones and mixed microphone/tap aggregates remain protected.
    public static func classify(isAggregate: Bool, tapCount: Int?, subdeviceInputCounts: [Int]?) -> Self {
        guard isAggregate else { return .deviceInput }
        guard let tapCount, let counts = subdeviceInputCounts, counts.allSatisfy({ $0 >= 0 }) else { return .unknown }
        if counts.contains(where: { $0 > 0 }) { return .deviceInput }
        return tapCount > 0 ? .systemPlaybackCapture : .unknown
    }
}
