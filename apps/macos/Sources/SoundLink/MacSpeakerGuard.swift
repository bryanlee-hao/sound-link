import Foundation
import CoreAudio

/// A warm child owns the mute and its deadline. Parent exit/pipe EOF restores it too.
final class MacSpeakerGuard {
    private var process: Process?
    private var input: FileHandle?
    func start(log: @escaping ([String: Any]) -> Void) {
        guard process == nil, let executable = Bundle.main.executableURL else { return }
        signal(SIGPIPE, SIG_IGN)
        let p = Process(), commands = Pipe(), events = Pipe()
        p.executableURL = executable; p.arguments = ["--speaker-guard"]
        p.standardInput = commands; p.standardOutput = events; p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { log(["stage": "unavailable", "error": error.localizedDescription]); return }
        process = p; input = commands.fileHandleForWriting
        DispatchQueue.global(qos: .utility).async {
            var buffer = Data()
            while true {
                let bytes = events.fileHandleForReading.availableData
                if bytes.isEmpty { break }; buffer.append(bytes)
                while let newline = buffer.firstIndex(of: 10) {
                    let line = buffer[..<newline]; buffer.removeSubrange(...newline)
                    if let value = try? JSONSerialization.jsonObject(with: line) as? [String: Any] {
                        DispatchQueue.main.async { log(value) }
                    }
                }
            }
        }
    }
    func begin(_ id: String) { send("protect " + id) }
    func release() { send("release") }
    private func send(_ line: String) {
        guard process?.isRunning == true else { return }
        try? input?.write(contentsOf: Data((line + "\n").utf8))
    }
    func stop() { try? input?.close(); input = nil; process = nil }
}

private final class SpeakerMuteWorker {
    private let lock = NSRecursiveLock()
    private let journal: URL
    private var saved: [String: UInt32] = [:]
    private var token = ""
    private var expiry: DispatchWorkItem?
    init() throws {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Sound Link", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        journal = root.appendingPathComponent("speaker-mute-recovery.json")
        if FileManager.default.fileExists(atPath: journal.path) {
            saved = try JSONDecoder().decode([String: UInt32].self, from: Data(contentsOf: journal))
            restore("startup")
        }
    }
    private func emit(_ stage: String, _ detail: String = "") {
        let data = try? JSONSerialization.data(withJSONObject: ["id": token, "stage": stage, "detail": detail])
        if var data { data.append(10); try? FileHandle.standardOutput.write(contentsOf: data) }
    }
    private func devices() -> [(String, AudioObjectID)] {
        (MacObservation.words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDevices) ?? []).compactMap { id in
            guard MacObservation.words(id, kAudioDevicePropertyTransportType)?.first == kAudioDeviceTransportTypeBuiltIn,
                  !(MacObservation.words(id, kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeOutput) ?? []).isEmpty,
                  let uid = MacObservation.string(id, kAudioDevicePropertyDeviceUID) else { return nil }
            return (uid, id)
        }
    }
    private func write(_ id: AudioObjectID, _ value: UInt32) throws {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyMute, mScope: kAudioObjectPropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
        var mutable = value
        let code = AudioObjectSetPropertyData(id, &address, 0, nil, 4, &mutable)
        guard code == noErr, MacObservation.words(id, kAudioDevicePropertyMute, scope: kAudioObjectPropertyScopeOutput)?.first == value else {
            throw NSError(domain: "SoundLink.Mute", code: Int(code), userInfo: [NSLocalizedDescriptionKey: "无法确认静音状态"])
        }
    }
    private func persist() throws {
        if saved.isEmpty { if FileManager.default.fileExists(atPath: journal.path) { try FileManager.default.removeItem(at: journal) }; return }
        try JSONEncoder().encode(saved).write(to: journal, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: journal.path)
    }
    func protect(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        guard saved.isEmpty else { return } // No deadline extensions.
        token = id
        let builtins = devices()
        let output = MacObservation.words(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice)?.first
        guard builtins.contains(where: { $0.1 == output }) else { emit("skipped", "当前不是内置扬声器"); return }
        for (uid, device) in builtins {
            if MacObservation.words(device, kAudioDevicePropertyMute, scope: kAudioObjectPropertyScopeOutput)?.first == 0 { saved[uid] = 0 }
        }
        guard !saved.isEmpty else { emit("skipped", "原本静音或不可控制"); return }
        do {
            try persist() // Recovery record must exist before any hardware change.
            for (uid, device) in builtins where saved[uid] != nil { try write(device, 1) }
            emit("muted", "内置扬声器 · 最长 5 秒")
            let work = DispatchWorkItem { [weak self] in self?.restore("timeout_5s", expected: id) }
            expiry = work; DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 5, execute: work)
        } catch { emit("error", error.localizedDescription); restore("failure") }
    }
    func restore(_ reason: String, expected: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        if let expected, expected != token { return }
        expiry?.cancel(); expiry = nil
        guard !saved.isEmpty else { return }
        for (uid, id) in devices() where saved[uid] != nil {
            guard let current = MacObservation.words(id, kAudioDevicePropertyMute, scope: kAudioObjectPropertyScopeOutput)?.first else { continue }
            do {
                if current == 1 { try write(id, 0) } // Never overwrite numeric volume or an explicit unmute.
                saved.removeValue(forKey: uid)
            } catch { emit("restore_error", error.localizedDescription) }
        }
        do { try persist() } catch { emit("journal_error", error.localizedDescription) }
        emit(saved.isEmpty ? "restored" : "restore_pending", reason)
    }
}

func runSpeakerGuard() {
    signal(SIGPIPE, SIG_IGN)
    do {
        let worker = try SpeakerMuteWorker()
        while let line = readLine() {
            let parts = line.split(separator: " ")
            if parts.count == 2, parts[0] == "protect", UUID(uuidString: String(parts[1])) != nil { worker.protect(String(parts[1])) }
            else if line == "release" { worker.restore("release") }
        }
        worker.restore("parent_exit")
    } catch { }
}
