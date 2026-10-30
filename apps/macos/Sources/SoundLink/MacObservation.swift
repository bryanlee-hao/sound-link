import Foundation
import AppKit
import CoreAudio
import CoreBluetooth
import IOBluetooth
import SoundLinkCore

/// Read-only system queries. No tap, capture, connect, disconnect or route mutation.
final class MacObservation {
    let instance = UUID().uuidString
    private var edges = OutputEdges()
    private var edgeAt: TimeInterval?
    private var previousRoute: String?
    private(set) var activeBundles = Set<String>()
    private(set) var inputSources: [String] = []
    struct Device { let address: String; let name: String; let connected: Bool }
    func devices() -> [Device] {
        guard CBManager.authorization == .allowedAlways else { return [] }
        return (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []).compactMap { d in
            guard let raw = d.addressString, let address = ObservationEngine.address(raw),
                  (d.classOfDevice & 0x1F00) == 0x0400 else { return nil }
            return Device(address: address, name: d.name ?? "蓝牙音频设备", connected: d.isConnected())
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    static func words(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> [UInt32]? {
        var a = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &a, 0, nil, &size) == noErr, size <= 65536, size % 4 == 0 else { return nil }
        if size == 0 { return [] }
        var values = [UInt32](repeating: 0, count: Int(size / 4))
        let result = values.withUnsafeMutableBytes { AudioObjectGetPropertyData(id, &a, 0, nil, &size, $0.baseAddress!) }
        return result == noErr ? Array(values.prefix(Int(size / 4))) : nil
    }
    static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var a = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var ref: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &ref) == noErr, let ref else { return nil }
        return ref.takeRetainedValue() as String
    }
    private func words(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> [UInt32]? { Self.words(id, selector, scope: scope) }
    private func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? { Self.string(id, selector) }
    private func inputProtection(_ device: AudioObjectID) -> AudioInputProtection {
        guard let kind = words(device, kAudioObjectPropertyClass)?.first else { return .unknown }
        guard kind == kAudioAggregateDeviceClassID else { return .deviceInput }
        let taps = words(device, kAudioAggregateDevicePropertySubTapList)
        let subdevices = words(device, kAudioAggregateDevicePropertyActiveSubDeviceList)
        let counts = subdevices?.map { words($0, kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeInput)?.count ?? -1 }
        return .classify(isAggregate: true, tapCount: taps?.count, subdeviceInputCounts: counts)
    }
    func sample(target: HeadphoneTarget) -> AudioObservation {
        var s = AudioObservation(); s.instance = instance; s.targetRevision = target.revision
        s.audioReadable = false; inputSources = []
        if target.macAddress.isEmpty { s.connectionDetail = "尚未选择 Mac 目标" }
        else if CBManager.authorization != .allowedAlways { s.connectionDetail = "需要 Mac 蓝牙权限" }
        else if IOBluetoothHostController.default()?.powerState != kBluetoothHCIPowerStateON {
            s.connectionDetail = "Mac 蓝牙不可用"
        } else if let d = devices().first(where: { $0.address == target.macAddress }) {
            s.connection = d.connected ? "connected" : "disconnected"
            s.connectionDetail = "\(d.name) · 蓝牙链路（不等同音频路由）"
        } else { s.connectionDetail = "目标不在已配对音频设备列表" }
        let output = words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice)?.first
        let defaultName = output.flatMap { string($0, kAudioObjectPropertyName) } ?? "未知"
        s.route = "默认输出：\(defaultName)"
        guard #available(macOS 14.2, *) else { s.playback = "当前 macOS 不支持进程输出观察（需要 14.2+）"; return s }
        guard let processes = words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyProcessObjectList) else {
            edges.resetBaseline(); edgeAt = nil; activeBundles = []; s.playback = "无法读取 Core Audio 输出活动"; return s
        }
        var active = Set<UInt32>(), names: [String] = [], routes = Set<String>(), input = false, unknown = false
        var bundles = Set<String>(), classifications: [AudioObjectID: AudioInputProtection] = [:]
        for process in processes {
            guard let out = words(process, kAudioProcessPropertyIsRunningOutput)?.first,
                  let mic = words(process, kAudioProcessPropertyIsRunningInput)?.first else {
                unknown = true; inputSources.append("process=\(process): 音频状态不可读"); continue
            }
            if mic != 0 {
                let pid = words(process, kAudioProcessPropertyPID)?.first
                let bundle = string(process, kAudioProcessPropertyBundleID) ?? "未知进程"
                if let devices = words(process, kAudioProcessPropertyDevices, scope: kAudioObjectPropertyScopeInput), !devices.isEmpty {
                    for device in devices {
                        let classification = classifications[device] ?? inputProtection(device)
                        classifications[device] = classification
                        input = input || classification.protectsOwner
                        inputSources.append("\(bundle) pid=\(pid ?? 0) · \(string(device, kAudioObjectPropertyName) ?? "未知输入") [\(device)] · \(classification.rawValue)")
                    }
                } else {
                    input = true
                    inputSources.append("\(bundle) pid=\(pid ?? 0) · 输入设备不可读，保守保护")
                }
            }
            if out != 0 {
                active.insert(process)
                let pid = words(process, kAudioProcessPropertyPID)?.first
                let appName = pid.flatMap { NSRunningApplication(processIdentifier: pid_t(bitPattern: $0))?.localizedName }
                let bundle = string(process, kAudioProcessPropertyBundleID) ?? pid.flatMap { NSRunningApplication(processIdentifier: pid_t(bitPattern: $0))?.bundleIdentifier } ?? ""
                bundles.insert(bundle)
                names.append(appName ?? string(process, kAudioProcessPropertyBundleID) ?? "未知进程")
                if let devices = words(process, kAudioProcessPropertyDevices, scope: kAudioObjectPropertyScopeOutput) {
                    for device in devices { routes.insert(string(device, kAudioObjectPropertyName) ?? "未知输出") }
                } else { routes.insert("部分进程输出未知") }
            }
        }
        let routeKey = "\(output ?? 0)|\(s.connection)"
        if let previousRoute, previousRoute != routeKey { edges.resetBaseline(); edgeAt = nil }
        previousRoute = routeKey
        let previousEvent = edges.event
        edges.update(active: active, readable: !unknown)
        if unknown { edgeAt = nil }
        else if edges.event != previousEvent { edgeAt = MonotonicTime.now }
        s.eventAgeMs = edgeAt.map { max(0, MonotonicTime.now - $0) * 1000 }
        if unknown { s.playback = "部分音频进程不可读" }
        else {
            s.playing = active.isEmpty ? "idle" : "active"
            s.playback = active.isEmpty ? "未检测到输出活动" : "输出活动：\(Array(Set(names)).sorted().prefix(6).joined(separator: "、"))"
        }
        activeBundles = bundles
        inputSources = Array(Set(inputSources)).sorted().prefix(12).map { String($0.prefix(400)) }
        s.audioReadable = !unknown
        s.event = edges.event; s.protectedAudio = input || unknown; s.media = edges.eligible
        if input { s.playback += "；检测到设备或未确认来源的输入，仅保护当前耳机占用" }
        else if !inputSources.isEmpty { s.playback += "；系统播放采集不阻止切换" }
        if !routes.isEmpty { s.route += "；活动进程：\(routes.sorted().joined(separator: "、"))" }
        s.route += "（音频流活动不保证实际有声）"
        return s
    }
}
