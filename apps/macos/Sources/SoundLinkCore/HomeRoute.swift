import Foundation

/// Where the home screen places the headphones bubble between the local and peer device.
/// Callers pass `peerConnected = false` for stale or unknown samples, so unknown evidence
/// never places the headphones beside a device.
public enum HomeRoute: String {
    case local, peer, both, switching, idle

    public static func resolve(localConnected: Bool, peerConnected: Bool, switching: Bool) -> HomeRoute {
        if switching { return .switching }
        switch (localConnected, peerConnected) {
        case (true, true): return .both
        case (true, false): return .local
        case (false, true): return .peer
        case (false, false): return .idle
        }
    }
}
