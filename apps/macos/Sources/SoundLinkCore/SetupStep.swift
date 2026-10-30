import Foundation

public enum SetupStep: String {
    case reconnect, headphones, confirm, waiting, control, complete

    public static func next(reconnected: Bool, localSelected: Bool, remoteSelected: Bool,
                            pendingSide: String, localSide: String, controlReady: Bool) -> SetupStep {
        if !reconnected { return .reconnect }
        if pendingSide == localSide { return .confirm }
        if !pendingSide.isEmpty { return .waiting }
        if !localSelected { return .headphones }
        if !remoteSelected { return .waiting }
        return controlReady ? .complete : .control
    }
}
