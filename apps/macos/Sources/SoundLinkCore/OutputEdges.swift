import Foundation

/// Player-independent output edges. New activity qualifies on its first observed sample.
public struct OutputEdges {
    public private(set) var event = 0
    public private(set) var eligible = false
    private var initialized = false
    private var previous = Set<UInt32>()
    private var qualifying = Set<UInt32>()
    public init() {}
    public mutating func resetBaseline() {
        initialized = false; previous = []; qualifying = []; eligible = false
    }
    public mutating func update(active: Set<UInt32>, readable: Bool = true) {
        guard readable else { resetBaseline(); return }
        if !initialized { initialized = true; previous = active; return }
        let started = active.subtracting(previous)
        if !started.isEmpty { event += 1; qualifying = started }
        qualifying.formIntersection(active)
        eligible = !qualifying.isEmpty
        previous = active
    }
}
