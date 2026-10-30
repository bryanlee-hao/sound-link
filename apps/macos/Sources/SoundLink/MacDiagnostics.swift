import Foundation
import SoundLinkCore

/// Persist the authenticated phone's compact evidence, never its pairing secrets or request bodies.
final class MacDiagnostics {
    let directory: URL
    let instance = UUID().uuidString
    private var macKey = "", phoneKey = "", receiptID = ""
    private var probeStarted: TimeInterval = 0
    private(set) var probe: [String: Any] = [:]
    private var previous = ""
    private var lastSaved: TimeInterval = 0
    private(set) var lastReceived: Date?
    private(set) var text = "等待手机诊断摘要 · 需要 Android 0.3.1+"
    private(set) var error = ""
    init(support: URL) throws {
        directory = support.appendingPathComponent("Diagnostics", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        event("mac_launch", data: [:])
    }
    func event(_ type: String, data: [String: Any]) {
        do {
            let url = directory.appendingPathComponent("phone-summary.jsonl")
            let previousURL = directory.appendingPathComponent("phone-summary.previous.jsonl")
            let row: [String: Any] = ["mac_wall_ms": Int64(Date().timeIntervalSince1970 * 1000), "mac_instance": instance, "type": type, "data": data]
            var bytes = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]); bytes.append(10)
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
            if size + bytes.count > 2 * 1024 * 1024 {
                if FileManager.default.fileExists(atPath: previousURL.path) { try FileManager.default.removeItem(at: previousURL) }
                if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.moveItem(at: url, to: previousURL) }
            }
            if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
            let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }
            try handle.seekToEnd(); try handle.write(contentsOf: bytes)
        } catch { self.error = "诊断文件写入失败：\(error.localizedDescription)" }
    }
    /// Export an allowlisted snapshot, never the pairing store, Keychain, or preferences.
    func export(to destination: URL) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let snapshot = root.appendingPathComponent("Sound-Link-Mac-logs", isDirectory: true)
        try FileManager.default.createDirectory(at: snapshot, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        for name in ["phone-summary.jsonl", "phone-summary.previous.jsonl"] {
            let source = directory.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: source.path) {
                try FileManager.default.copyItem(at: source, to: snapshot.appendingPathComponent(name))
            }
        }
        let metadata: [String: Any] = ["app": "Sound Link", "version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "", "os": ProcessInfo.processInfo.operatingSystemVersionString, "exportedAt": ISO8601DateFormatter().string(from: Date()), "contents": "Mac events and received phone summaries. Export full phone logs on Android."]
        try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys]).write(to: snapshot.appendingPathComponent("manifest.json"))
        let archive = root.appendingPathComponent("logs.zip")
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = ["-c", "-k", "--norsrc", "--noextattr", "--keepParent", snapshot.path, archive.path]
        try process.run(); process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw CocoaError(.fileWriteUnknown) }
        try Data(contentsOf: archive).write(to: destination, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
    }
    func observeMac(_ sample: AudioObservation, inputSources: [String]) {
        let key = "\(sample.instance):\(sample.event):\(sample.playing):\(sample.protectedAudio):\(sample.isAudioReadable):\(inputSources.joined(separator: "|"))"
        guard key != macKey else { return }; macKey = key
        probeStarted = MonotonicTime.now
        probe = ["id": UUID().uuidString, "playing": sample.playing, "event": sample.event]
        event("mac_playback_changed", data: ["probe": probe, "instance": sample.instance, "protectedAudio": sample.protectedAudio, "audioReadable": sample.isAudioReadable, "inputSources": inputSources, "playback": sample.playback, "transport": "https_poll_response", "active_wake_sent": false])
    }
    func observePhone(_ raw: Any?) {
        guard let sample = AudioObservation.decode(raw) else { return }
        let key = "\(sample.instance):\(sample.event):\(sample.playing):\(sample.protectedAudio):\(sample.targetRevision):\(sample.connection):\(sample.route)"
        guard key != phoneKey || sample.stateSync == true else { return }; phoneKey = key
        event("phone_observation_received", data: ["instance": sample.instance, "event": sample.event, "playing": sample.playing, "media": sample.media, "protectedAudio": sample.protectedAudio, "sample_age_ms": sample.ageMs, "targetRevision": sample.targetRevision, "connection": sample.connection, "route": sample.route, "stateSync": sample.stateSync == true])
    }
    func receive(_ raw: Any?) {
        guard let value = raw as? [String: Any], value["v"] as? Int == 1,
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), data.count <= 12000 else { return }
        if let receipt = value["latestMacReceipt"] as? [String: Any], let id = receipt["id"] as? String,
           id == probe["id"] as? String, id != receiptID {
            receiptID = id
            event("mac_state_receipt_confirmed", data: ["id": id, "roundtrip_upper_bound_ms": max(0, MonotonicTime.now - probeStarted) * 1000, "receipt": receipt, "active_wake_sent": false])
        }
        lastReceived = Date()
        func field(_ key: String) -> String { String((value[key] as? String ?? "未知").prefix(800)) }
        text = "手机进程：\(value["pid"] ?? "?") · \(field("process").prefix(8))\n\(field("processEvidence"))\n最近执行间隔：\(field("gapEvidence"))\n最近请求失败：\(field("networkEvidence"))\n恢复入口：\(field("wakeEvidence")) · 丢弃 \(value["dropped"] ?? 0) · 写入错误 \(value["writeErrors"] ?? 0)"
        let encoded = String(data: data, encoding: .utf8) ?? ""
        if previous != encoded || Date().timeIntervalSince1970 - lastSaved >= 30 {
            previous = encoded; lastSaved = Date().timeIntervalSince1970
            event("phone_summary", data: value)
        }
    }
}
