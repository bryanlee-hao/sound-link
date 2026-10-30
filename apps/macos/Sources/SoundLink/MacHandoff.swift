import AppKit
import CoreAudio
import IOBluetooth
import SoundLinkCore

/// Public Bluetooth + Core Audio only. Never disconnect the old endpoint first.
final class MacHandoff: NSObject {
    let speakerGuard = MacSpeakerGuard()
    private var id = ""
    private var timer: Timer?
    private var routeAttempted = false
    private var completion: ((Bool, String) -> Void)?
    func cancel() { speakerGuard.release(); mark("verification_closed"); timing = nil; id = ""; timer?.invalidate(); timer = nil; completion = nil }
    private var timing: (([String: Any]) -> Void)?
    private var started: TimeInterval = 0
    private var stages = Set<String>()
    private func mark(_ stage: String, fields: [String: Any] = [:]) {
        guard !id.isEmpty, stages.insert(stage).inserted else { return }
        timing?(["id": id, "stage": stage, "sinceStartMs": max(0, Int((MonotonicTime.now - started) * 1000)), "pollIntervalMs": 250].merging(fields) { _, new in new })
    }
    func connect(_ t: HandoffEngine.Transaction, valid: @escaping () -> Bool, mayMutate: @escaping () -> Bool, progress: @escaping ([String: Any]) -> Void, completion: @escaping (Bool, String) -> Void) {
        perform(id: t.id, address: t.target.macAddress, eventAgeMs: t.mac.eventAgeMs ?? -1, valid: valid, mayMutate: mayMutate, progress: progress, completion: completion)
    }
    func connectManually(address: String, valid: @escaping () -> Bool, progress: @escaping ([String: Any]) -> Void, completion: @escaping (Bool, String) -> Void) {
        perform(id: UUID().uuidString, address: address, eventAgeMs: -1, valid: valid, mayMutate: valid, progress: progress, completion: completion)
    }
    private func perform(id operationID: String, address: String, eventAgeMs: Double, valid: @escaping () -> Bool, mayMutate: @escaping () -> Bool, progress: @escaping ([String: Any]) -> Void, completion: @escaping (Bool, String) -> Void) {
        cancel(); id = operationID; self.completion = completion; routeAttempted = false
        started = MonotonicTime.now; stages = []; timing = progress
        mark("executor_started", fields: ["edgeToExecutorMs": eventAgeMs, "initialLinkConnected": IOBluetoothDevice(addressString: address)?.isConnected() ?? false])
        guard valid(), mayMutate() else { finish(false, "连接前状态已变化，未提交请求"); return }
        guard let device = IOBluetoothDevice(addressString: address), device.isPaired() else {
            finish(false, "目标耳机未在 Mac 配对"); return
        }
        speakerGuard.begin(operationID)
        if !device.isConnected() {
            mark("connect_requested")
            let result = device.openConnection(self, withPageTimeout: 8000, authenticationRequired: false)
            mark("connect_api_returned", fields: ["status": Int(result)])
            if result != kIOReturnSuccess { finish(false, "蓝牙连接请求失败（\(result)）"); return }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self, self.id == operationID else { return }
            guard MonotonicTime.now - self.started < 5 else { self.finish(false, "连接超时（5 秒）"); return }
            guard valid() else { self.mark("verification_stopped"); self.cancel(); return }
            let mutationsAllowed = mayMutate()
            if !mutationsAllowed { self.mark("read_only") }
            guard device.isConnected() else { return }
            self.mark("bluetooth_link_observed")
            let normalized = address.filter { $0.isHexDigit }.uppercased()
            let outputs = (MacObservation.words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDevices) ?? []).filter { deviceID in
                guard let uid = MacObservation.string(deviceID, kAudioDevicePropertyDeviceUID),
                      uid.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: ":", with: "").uppercased().contains(normalized),
                      let streams = MacObservation.words(deviceID, kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeOutput) else { return false }
                return !streams.isEmpty
            }
            guard outputs.count == 1, var output = outputs.first else { return }
            self.mark("audio_output_observed")
            if MacObservation.words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice)?.first == output {
                self.mark("route_verified", fields: ["routeWasSetByApp": self.routeAttempted])
                self.finish(true, "目标蓝牙链路与系统默认输出已确认"); return
            }
            guard mutationsAllowed else { return }
            if !self.routeAttempted {
                self.routeAttempted = true
                var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
                self.mark("route_set_requested")
                let result = AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, 4, &output)
                self.mark("route_set_returned", fields: ["status": Int(result)])
                if result != noErr { self.finish(false, "耳机已连接，但设置默认媒体输出失败（\(result)）"); return }
            }
            if MacObservation.words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice)?.first == output {
                self.mark("route_verified", fields: ["routeWasSetByApp": self.routeAttempted])
                self.finish(true, "目标蓝牙链路与系统默认输出已确认")
            }
        }
    }
    @objc func connectionComplete(_ device: IOBluetoothDevice, status: IOReturn) {
        // Polling verifies the current transaction; late callbacks from an old request cannot mutate it.
    }
    private func finish(_ success: Bool, _ message: String) {
        mark("finished", fields: ["success": success, "message": message])
        let done = completion; cancel(); done?(success, message)
    }
    func authorizePause() {
        for id in ["com.apple.Music", "com.spotify.client"] where NSRunningApplication.runningApplications(withBundleIdentifier: id).count > 0 {
            let descriptor = NSAppleEventDescriptor(bundleIdentifier: id)
            _ = AEDeterminePermissionToAutomateTarget(descriptor.aeDesc, typeWildCard, typeWildCard, true)
        }
    }
    /// Precise pause adapters. Unsupported sources are skipped; no play/pause toggle or private API.
    func pause(bundles: Set<String>) -> String {
        let supported = bundles.intersection(["com.apple.Music", "com.spotify.client"])
        guard bundles.count == 1, let bundle = supported.first else { return "旧 Mac 播放器无定向暂停适配，已跳过" }
        // Do not present an automation authorization dialog in the middle of a handoff.
        let target = NSAppleEventDescriptor(bundleIdentifier: bundle)
        let permission = AEDeterminePermissionToAutomateTarget(target.aeDesc, typeWildCard, typeWildCard, false)
        guard permission == noErr else { return "Mac 播放器自动化未授权，已跳过暂停" }
        var error: NSDictionary?
        NSAppleScript(source: "with timeout of 1 seconds\n tell application id \"\(bundle)\" to pause\nend timeout")?.executeAndReturnError(&error)
        return error == nil ? "已向旧 Mac 播放器发送 pause" : "Mac 暂停失败，已跳过"
    }
}
