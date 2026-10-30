import Cocoa
import CoreImage
import SoundLinkCore
import Sparkle

final class ActionButton: NSButton {
    var invoke: () -> Void = {}
    convenience init(_ title: String, _ action: @escaping () -> Void) {
        self.init(title: title, target: nil, action: nil); invoke = action; target = self; self.action = #selector(run)
        bezelStyle = .rounded; controlSize = .large
    }
    @objc func run() { invoke() }
}
final class FlippedDocument: NSView { override var isFlipped: Bool { true } }
final class Application: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    var menuItem: NSStatusItem!
    var identity: DeviceIdentity!
    var engine: PairingEngine!
    lazy var updaterController: SPUStandardUpdaterController? = Distribution.updatesEnabled
        ? SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: nil, userDriverDelegate: nil) : nil
    var control: LocalServer!
    var timer: Timer?
    var audioTimer: Timer?
    var discovery: MacDiscovery?
    var diagnostics: MacDiagnostics?
    let diagnosticLabel = NSTextField(wrappingLabelWithString: "等待手机诊断摘要")
    var diagnosticOnline = false
    var phoneRecoveryReady = false
    var observation: ObservationEngine?
    let audioObserver = MacObservation()
    let handoff = HandoffEngine()
    let hardware = MacHandoff()
    var handoffButton: ActionButton!
    let handoffLabel = NSTextField(wrappingLabelWithString: "自动切换已关闭")
    var sourceBundles = Set<String>()
    var lastPhoneHandoffReport = ""
    var observedAt: TimeInterval = 0
    let targetLabel = NSTextField(wrappingLabelWithString: "尚未选择目标耳机")
    let macAudioLabel = NSTextField(wrappingLabelWithString: "等待 Mac 样本")
    let phoneAudioLabel = NSTextField(wrappingLabelWithString: "等待手机样本")
    let decisionLabel = NSTextField(wrappingLabelWithString: "暂不建议切换")
    let reasonLabel = NSTextField(wrappingLabelWithString: "请选择目标耳机")
    let historyLabel = NSTextField(wrappingLabelWithString: "")
    var targetPicker: NSPopUpButton!
    var targetAlert: NSAlert?
    var targetAlertRevision = -1
    var promptedTargetRevision = -1
    var publishedTargetRevision = -1
    var paused = false
    let discoveryLabel = NSTextField(wrappingLabelWithString: "蓝牙发现准备中")
    var pauseButton: ActionButton!
    var address = ""
    var displayName = Host.current().localizedName ?? "Mac"
    var lastQR = ""
    let status = NSTextField(wrappingLabelWithString: "正在启动本机连接…")
    let details = NSTextField(wrappingLabelWithString: "")
    let downloadQR = NSImageView()
    let pairingQR = NSImageView()
    let downloadCaption = NSTextField(wrappingLabelWithString: "")
    let pairingCaption = NSTextField(wrappingLabelWithString: "")
    let peerLabel = NSTextField(wrappingLabelWithString: "尚未配对手机")
    var refresh: ActionButton!
    var unpair: ActionButton!
    var interfaces: NSPopUpButton!
    var selectedHost: String?
    var failure = ""
    func applicationDidFinishLaunching(_ note: Notification) {
        handoff.enabled = UserDefaults.standard.bool(forKey: "automaticSwitchingEnabled")
        buildMenu(); buildWindow()
        _ = updaterController
        do {
            let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent(Bundle.main.object(forInfoDictionaryKey: "SoundLinkSupportDirectory") as? String ?? "Sound Link Dev", isDirectory: true)
            identity = try DeviceIdentity(tag: "\(Bundle.main.bundleIdentifier ?? "app.soundlink.mac.dev").identity.v1"); engine = try PairingEngine(directory: support)
            observation = try ObservationEngine(directory: support)
            observation?.allowOfflineMac = UserDefaults.standard.object(forKey: "allowOfflineMac") as? Bool ?? true
            diagnostics = try MacDiagnostics(support: support)
            diagnostics?.event("switching_preference_restored", data: ["enabled": handoff.enabled])
            hardware.speakerGuard.start { [weak self] data in self?.diagnostics?.event("mac_mute_guard", data: data) }
            handoff.changed = { [weak self] message, data in self?.diagnostics?.event("handoff", data: data.merging(["message": message]) { _, new in new }) }
            control = try LocalServer(identity: identity.identity, port: UInt16(UserDefaults.standard.integer(forKey: "controlPort"))) { [weak self] r in guard let self else { return .json([:], status: 503) }; return try self.api(r) }
            discovery = MacDiscovery(identity: identity)
            discovery?.changed = { [weak self] in self?.update() }
            engine.changed = { [weak self] in self?.update() }
            for server in [control!] {
                server.changed = { [weak self] in
                    if let port = self?.control?.port, port != 0 { UserDefaults.standard.set(Int(port), forKey: "controlPort") }; self?.update()
                }
                server.failed = { [weak self] error in self?.failure = error; self?.update() }
                server.start()
            }
            NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(powerChanged(_:)), name: NSWorkspace.willSleepNotification, object: nil)
            NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(powerChanged(_:)), name: NSWorkspace.didWakeNotification, object: nil)
            audioTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in self?.updateObservation() }
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.update() }
            update()
        } catch { failure = error.localizedDescription; status.stringValue = "无法启动 Sound Link"; details.stringValue = failure }
        show()
    }
    @objc func powerChanged(_ note: Notification) {
        let sleeping = note.name == NSWorkspace.willSleepNotification
        diagnostics?.event(sleeping ? "mac_sleep" : "mac_wake", data: [:])
        cancelManual(); handoff.cancel("系统休眠或唤醒，取消旧事务"); hardware.cancel()
        observation?.invalidate()
        engine?.setAvailable(!sleeping && !paused)
        discovery?.setAvailable(!sleeping && !paused)
        update()
    }
    func togglePause() {
        cancelManual(); handoff.cancel("协调已暂停或恢复"); hardware.cancel(); observation?.invalidate(); paused.toggle(); diagnostics?.event(paused ? "mac_pause" : "mac_resume", data: [:]); engine?.setAvailable(!paused); discovery?.setAvailable(!paused); update()
    }
    var productUI: ProductWindow!
    var automaticMenu: NSMenuItem!
    var connectionMenu: NSMenuItem!
    var manualMenu: NSMenuItem!
    var manualOperation: UUID?
    var manualMessage = ""
    func buildWindow() {
        productUI = ProductWindow(self)
        window = productUI.window
    }
    func buildMenu() {
        let bar = NSMenu()
        let appItem = NSMenuItem(); let appMenu = NSMenu(title: "Sound Link"); appItem.submenu = appMenu; bar.addItem(appItem)
        func add(_ menu: NSMenu, _ title: String, _ action: Selector, _ key: String = "") -> NSMenuItem {
            let item = menu.addItem(withTitle: title, action: action, keyEquivalent: key); item.target = self; return item
        }
        _ = add(appMenu, L("关于 Sound Link", "About Sound Link"), #selector(about))
        if Distribution.updatesEnabled { _ = add(appMenu, L("检查更新…", "Check for Updates…"), #selector(checkForUpdates)) }
        appMenu.addItem(.separator())
        _ = add(appMenu, L("设置…", "Settings…"), #selector(showSettings), ",")
        _ = add(appMenu, L("显示 Sound Link", "Show Sound Link"), #selector(show), "0")
        appMenu.addItem(.separator())
        _ = add(appMenu, L("隐藏 Sound Link", "Hide Sound Link"), #selector(NSApplication.hide(_:)), "h"); appMenu.items.last?.target = NSApp
        appMenu.addItem(.separator())
        _ = add(appMenu, L("退出 Sound Link", "Quit Sound Link"), #selector(quit), "q")
        let editItem = NSMenuItem(); let edit = NSMenu(title: L("编辑", "Edit")); editItem.submenu = edit; bar.addItem(editItem)
        for (title, action, key) in [(L("全选", "Select All"), #selector(NSText.selectAll(_:)), "a"), (L("复制", "Copy"), #selector(NSText.copy(_:)), "c"), (L("粘贴", "Paste"), #selector(NSText.paste(_:)), "v")] { edit.addItem(withTitle: title, action: action, keyEquivalent: key) }
        NSApp.mainMenu = bar
        if menuItem == nil { menuItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength) }
        let icon = Bundle.main.url(forResource: "MenuBarIcon", withExtension: "tiff").flatMap { NSImage(contentsOf: $0) } ?? NSImage(systemSymbolName: "headphones", accessibilityDescription: "Sound Link")
        icon?.size = NSSize(width: 18, height: 18); icon?.isTemplate = true
        menuItem.button?.image = icon; menuItem.button?.toolTip = "Sound Link"; menuItem.button?.setAccessibilityLabel("Sound Link")
        let tray = NSMenu(); tray.autoenablesItems = false
        _ = add(tray, L("显示 Sound Link", "Show Sound Link"), #selector(show))
        tray.addItem(.separator())
        manualMenu = add(tray, L("耳机切换到此 Mac", "Switch Headphones to This Mac"), #selector(switchToMac))
        automaticMenu = add(tray, L("自动切换", "Automatic Switching"), #selector(toggleAutomatic))
        connectionMenu = add(tray, L("暂停设备连接", "Pause Device Connection"), #selector(toggleConnection))
        tray.addItem(.separator())
        _ = add(tray, L("设置…", "Settings…"), #selector(showSettings))
        _ = add(tray, L("导出诊断日志…", "Export Diagnostic Logs…"), #selector(exportLogs))
        if Distribution.updatesEnabled { _ = add(tray, L("检查更新…", "Check for Updates…"), #selector(checkForUpdates)) }
        _ = add(tray, L("关于 Sound Link", "About Sound Link"), #selector(about))
        tray.addItem(.separator())
        _ = add(tray, L("退出 Sound Link", "Quit Sound Link"), #selector(quit))
        menuItem.menu = tray
    }
    @objc func about() {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.orderFrontStandardAboutPanel(options: [.applicationName: "Sound Link", .applicationVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "", .credits: NSAttributedString(string: L("让耳机跟随正在使用的设备。", "Your headphones follow the device you use."))])
    }
    @objc func showSettings() { show(); productUI.navigate("settings") }
    @objc func toggleConnection() { togglePause() }
    @objc func toggleAutomatic() {
        cancelManual(); handoff.enabled.toggle()
        UserDefaults.standard.set(handoff.enabled, forKey: "automaticSwitchingEnabled")
        diagnostics?.event("switching_preference_changed", data: ["enabled": handoff.enabled])
        handoff.cancel("自动切换设置改变"); hardware.cancel(); observation?.invalidate(); update()
    }
    func cancelManual() {
        guard manualOperation != nil else { return }
        manualOperation = nil; manualMessage = ""; hardware.cancel()
        diagnostics?.event("manual_switch_cancelled", data: [:])
    }
    @objc func switchToMac() {
        guard let o = observation, !o.target.macAddress.isEmpty, manualOperation == nil, handoff.transaction == nil, !(o.phoneAge < 10 && handoff.phoneLocalActive) else { return }
        handoff.cancel("用户手动切换"); hardware.cancel()
        let operation = UUID(), revision = o.target.revision
        manualOperation = operation; manualMessage = L("正在切换到 Mac…", "Switching to Mac…")
        diagnostics?.event("manual_switch_started", data: ["revision": revision])
        hardware.connectManually(address: o.target.macAddress, valid: { [weak self] in
            guard let self, let current = self.observation else { return false }
            let valid = self.manualOperation == operation && current.target.revision == revision && !(current.phoneAge < 10 && self.handoff.phoneLocalActive)
            if !valid { self.cancelManual() }
            return valid
        }, progress: { [weak self] data in self?.diagnostics?.event("manual_switch_timing", data: data) }) { [weak self] success, message in
            guard let self, self.manualOperation == operation else { return }
            self.manualOperation = nil
            self.manualMessage = success ? "" : L("切换未成功，请检查蓝牙后重试", "Couldn’t switch. Check Bluetooth and try again.")
            self.diagnostics?.event("manual_switch_result", data: ["success": success, "message": message])
            // Rebuild automatic playback baselines; a manual action must not replay an old edge.
            self.observation?.invalidate(); self.update()
        }
        productUI.refresh()
    }
    @objc func exportLogs() {
        guard let diagnostics else { return }
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSSavePanel(); panel.nameFieldStringValue = "Sound-Link-Mac-logs.zip"
        panel.title = L("导出诊断日志", "Export Diagnostic Logs")
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        do {
            try diagnostics.export(to: destination)
            NSWorkspace.shared.activateFileViewerSelecting([destination])
        } catch {
            let alert = NSAlert(); alert.messageText = L("日志导出失败", "Couldn’t export logs"); alert.informativeText = error.localizedDescription; alert.runModal()
        }
    }
    @objc func toggleOfflineMac(_ sender: NSButton) {
        let allowed = sender.state == .on
        UserDefaults.standard.set(allowed, forKey: "allowOfflineMac")
        handoff.cancel("失联接管设置变化"); hardware.cancel(); observation?.invalidate()
        observation?.allowOfflineMac = allowed; updateObservation()
    }
    @objc func selectTarget() {
        guard let address = targetPicker.selectedItem?.representedObject as? String else { return }
        selectHeadphone(address: address)
    }
    func selectHeadphone(address: String) {
        guard let device = audioObserver.devices().first(where: { $0.address == address }) else { return }
        do {
            cancelManual(); handoff.cancel("耳机选择变化"); hardware.cancel()
            if let o = observation, o.target.pendingSide == "mac" {
                try o.confirmMac(revision: o.target.revision, address: address, name: device.name)
            } else { try observation?.selectMac(address: address, name: device.name) }
            updateObservation()
        }
        catch { failure = error.localizedDescription; update() }
    }
    @objc func targetConfirmationChanged(_ sender: NSPopUpButton) {
        targetAlert?.buttons.first?.isEnabled = sender.indexOfSelectedItem > 0
    }
    func reconcileHeadphoneTarget() {
        guard let o = observation else { return }
        if let alert = targetAlert, o.target.revision != targetAlertRevision {
            if let sheet = alert.window.sheetParent { sheet.endSheet(alert.window, returnCode: .abort) }
            targetAlert = nil
        }
        guard o.target.pendingSide == "mac", engine?.peer != nil else { return }
        let target = o.target, devices = audioObserver.devices()
        if let match = devices.first(where: { target.canAutomaticallyMatch(side: "mac", address: $0.address) }) {
            do {
                cancelManual(); handoff.cancel("自动匹配手机选择的耳机"); hardware.cancel()
                try o.confirmMac(revision: target.revision, address: match.address, name: match.name)
                diagnostics?.event("headphone_target_matched", data: ["side": "mac", "revision": o.target.revision])
            } catch { failure = error.localizedDescription }
            return
        }
        // Initial setup presents this confirmation inline in the headphone card.
        guard productUI?.page != "onboarding", targetAlert == nil, promptedTargetRevision != target.revision, window.attachedSheet == nil else { return }
        promptedTargetRevision = target.revision; targetAlertRevision = target.revision
        let alert = NSAlert(); targetAlert = alert
        alert.messageText = L("同步耳机选择", "Sync headphones")
        let matching = devices.firstIndex { $0.address == target.phoneAddress }
        let previous = target.previousName ?? ""
        alert.informativeText = L("手机选择了 ", "Your phone selected ") + target.phoneName + "。\n" +
            (matching == nil ? L("未找到相同的已配对设备。请确认对应耳机，或先在系统蓝牙中配对。", "No identical paired device was found. Confirm the corresponding headphones, or pair them in Bluetooth settings first.") :
                L("Mac 之前选择了 ", "This Mac previously selected ") + previous + L("，是否更换？", ". Replace them?"))
        alert.addButton(withTitle: L("确认耳机", "Confirm Headphones"))
        alert.addButton(withTitle: L("稍后", "Not Now"))
        let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 320, height: 28))
        picker.addItems(withTitles: [L("选择对应的耳机", "Choose corresponding headphones")] + devices.map { "\($0.name) · \($0.address.suffix(5))" })
        picker.target = self; picker.action = #selector(targetConfirmationChanged(_:))
        if let matching { picker.selectItem(at: matching + 1) }
        alert.buttons[0].isEnabled = picker.indexOfSelectedItem > 0
        alert.accessoryView = picker
        show()
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self else { return }
            if self.targetAlert === alert { self.targetAlert = nil }
            guard response == .alertFirstButtonReturn, let current = self.observation,
                  current.target.revision == target.revision, current.target.pendingSide == "mac",
                  devices.indices.contains(picker.indexOfSelectedItem - 1) else { return }
            let chosen = devices[picker.indexOfSelectedItem - 1]
            guard self.audioObserver.devices().contains(where: { $0.address == chosen.address }) else { return }
            do {
                self.cancelManual(); self.handoff.cancel("确认手机选择的耳机"); self.hardware.cancel()
                try current.confirmMac(revision: target.revision, address: chosen.address, name: chosen.name)
                self.updateObservation()
            } catch { self.failure = error.localizedDescription; self.update() }
        }
    }
    func updateObservation() {
        guard let o = observation else { return }
        reconcileHeadphoneTarget()
        if publishedTargetRevision != o.target.revision {
            publishedTargetRevision = o.target.revision
            if let pairingID = engine?.peer?.requestID {
                control?.publishEvent(.json(["server_id": identity.id, "event": "target_changed", "pairing_id": pairingID, "observation": o.object]))
            }
        }
        let now = MonotonicTime.now
        if now - observedAt >= 0.18 || o.mac.targetRevision != o.target.revision {
            o.mac = audioObserver.sample(target: o.target); observedAt = now
        }
        diagnostics?.observeMac(o.mac, inputSources: audioObserver.inputSources)
        o.mac.ageMs = max(0, now - observedAt) * 1000
        o.evaluate(online: engine.online, available: engine.available)
        if manualOperation == nil { handoff.tick(o, available: engine.available) }
        if let t = handoff.takeMacAction() {
            hardware.connect(t, valid: { [weak self] in
                guard let self, let current = self.observation else { return false }
                return self.handoff.valid(t.id) && self.handoff.lifetimeValid(t.id, observation: current) && self.engine.available && self.handoff.phoneReady
            }, mayMutate: { [weak self] in
                guard let self, let current = self.observation else { return false }
                return self.handoff.macCanMutate(t.id, observation: current)
            }, progress: { [weak self] data in
                self?.diagnostics?.event("mac_handoff_timing", data: data)
            }) { [weak self] success, message in
                guard let self, let current = self.observation else { return }
                self.diagnostics?.event("mac_handoff_result", data: ["id": t.id, "success": success, "message": message])
                if success { self.handoff.macVerified(t.id, observation: current) } else { self.handoff.failed(t.id, message) }
            }
        }
        if o.mac.connection == "disconnected", let t = handoff.takeMacPause() {
            let safe = o.mac.instance == t.mac.instance && o.mac.event == t.mac.event && !o.mac.protectedAudio && o.mac.connection == "disconnected" && sourceBundles == audioObserver.activeBundles
            let message = !safe ? "旧 Mac 状态变化或仍连接，跳过暂停" : o.mac.playing != "active" ? "Mac 已停止播放，无需暂停" : hardware.pause(bundles: sourceBundles)
            handoff.pauseResult(t.id, message)
        }
        if handoff.transaction == nil { sourceBundles = audioObserver.activeBundles }
        handoffButton.title = handoff.enabled ? L("暂停自动切换", "Pause Automatic Switching") : L("启用自动切换", "Enable Automatic Switching")
        handoffButton.isEnabled = handoff.enabled || (o.target.ready && handoff.phoneReady)
        let useStatus = o.usageStatus
        handoffLabel.stringValue = handoff.status + "\n" + useStatus + "\n" + (handoff.phoneReady ? "手机控制就绪" : "请先在手机授权目标耳机控制") + " · 不按播放器名单判断；提示音也可能触发\n" + handoff.history.prefix(3).joined(separator: "\n")
        let devices = audioObserver.devices()
        let names = [L("选择已配对的耳机", "Choose paired headphones")] + devices.map { "\($0.name) · \($0.address.suffix(5))" }
        if targetPicker.itemTitles != names {
            targetPicker.removeAllItems(); targetPicker.addItems(withTitles: names)
            for (index, device) in devices.enumerated() { targetPicker.item(at: index + 1)?.representedObject = device.address }
        }
        targetPicker.selectItem(at: devices.firstIndex(where: { $0.address == o.target.macAddress }).map { $0 + 1 } ?? 0)
        targetLabel.stringValue = o.target.pendingSide == "mac"
            ? L("手机已选择：", "Phone selected: ") + o.target.phoneName + L(" · 等待 Mac 确认", " · Awaiting Mac confirmation")
            : o.target.pendingSide == "phone"
                ? o.target.macName + L(" · 等待手机匹配或确认", " · Awaiting phone matching or confirmation")
                : o.target.ready ? o.target.macName + L(" · 已同步", " · Synced")
                : L("在任一设备选择已配对的耳机", "Choose paired headphones on either device")
        macAudioLabel.stringValue = o.mac.description + "\n通用输出观察 · 新活动序号 \(o.mac.event)"
        let fresh = engine.online && o.phoneAge < 10
        phoneAudioLabel.stringValue = fresh ? o.phone.description + "\n\(Int(o.phoneAge)) 秒前采样 · 新活动序号 \(o.phone.event)" : "当前状态待刷新 · 心跳过期不等于耳机断开\n失联接管资格见上方使用记录；当前手机状态未知"
        decisionLabel.stringValue = o.title
        reasonLabel.stringValue = o.reason + (handoff.enabled ? "\n自动切换开启；执行结果以上方事务记录为准。" : "\n自动切换关闭，只显示建议。")
        historyLabel.stringValue = o.history.prefix(3).joined(separator: "\n")
        productUI?.refresh()
    }
    @objc func selectNetwork() { selectedHost = interfaces.selectedItem?.representedObject as? String; lastQR = ""; update() }
    var downloadURL: String { Distribution.websiteURL?.absoluteString ?? "" }
    func qr(_ content: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(content.utf8), forKey: "inputMessage"); filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale: CGFloat = 6
        // Composite a white quiet zone; never interpolate QR modules.
        let context = CIContext(); let rectangle = output.extent.insetBy(dx: -4, dy: -4)
        let white = CIImage(color: .white).cropped(to: rectangle)
        let result = output.composited(over: white).transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let image = context.createCGImage(result, from: result.extent) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
    func update() {
        guard engine != nil, identity != nil else { return }
        engine.refreshExpiredInvite()
        updateObservation()
        if diagnosticOnline != engine.online {
            diagnosticOnline = engine.online
            diagnostics?.event(diagnosticOnline ? "phone_online" : "phone_heartbeat_expired", data: ["count": engine.heartbeats, "mac_available": engine.available])
        }
        let stamp = diagnostics?.lastReceived.map { DateFormatter.localizedString(from: $0, dateStyle: .none, timeStyle: .medium) } ?? "尚未收到"
        diagnosticLabel.stringValue = "\(engine.online ? "最近摘要" : "历史摘要，非当前在线状态") · \(stamp)\n\(diagnostics?.text ?? "等待诊断")\n\(diagnostics?.error ?? "完整日志可从手机导出；此处只同步摘要，不额外保活。")"
        let candidates = LocalAddress.candidates()
        let old = interfaces.itemArray.compactMap { $0.representedObject as? String }
        if old != candidates.map({ $0.1 }) {
            interfaces.removeAllItems()
            for (name, host) in candidates { interfaces.addItem(withTitle: "\(name) · \(host)"); interfaces.lastItem?.representedObject = host }
        }
        address = candidates.first(where: { $0.1 == selectedHost })?.1 ?? candidates.first?.1 ?? ""
        if let index = candidates.firstIndex(where: { $0.1 == address }) { interfaces.selectItem(at: index) }
        let orderedHosts = ([address] + candidates.map { $0.1 }).filter { !$0.isEmpty }
        var uniqueHosts: [String] = []; for host in orderedHosts where !uniqueHosts.contains(host) { uniqueHosts.append(host) }
        discovery?.update(hosts: uniqueHosts, port: control?.port ?? 0)
        discoveryLabel.stringValue = "\(discovery?.status ?? "蓝牙未启动") · 已读取地址 \(discovery?.reads ?? 0) 次\n\(discovery?.lastRead.map { "最近读取 " + DateFormatter.localizedString(from: $0, dateStyle: .none, timeStyle: .medium) } ?? "手机可通过蓝牙获取变化后的 IP 和端口")"
        pauseButton.title = paused ? L("恢复设备连接", "Resume Device Connection") : L("暂停设备连接", "Pause Device Connection")
        let ready = !address.isEmpty && (control?.port ?? 0) != 0
        let newQR = "\(address)|\(control?.port ?? 0)|\(engine.invitationID)|\(engine.peer?.id ?? "")"
        if newQR != lastQR {
            lastQR = newQR
            downloadQR.image = downloadURL.isEmpty ? nil : qr(downloadURL)
            if ready {
                var data: [String: Any] = ["v": 1, "id": identity.id, "name": displayName, "host": address, "port": Int(control.port), "pin": identity.pin, "invite": engine.invitationID, "secret": engine.secret]
                if engine.peer != nil { data.removeValue(forKey: "invite"); data.removeValue(forKey: "secret"); data["mode"] = "route" }
                pairingQR.image = (try? Wire.json(data)).flatMap { qr("soundlink://pair/" + Wire.b64($0)) }
            } else { pairingQR.image = nil }
        }
        if engine.peer == nil && (engine.secondsLeft == 0 || engine.secret.isEmpty) { pairingQR.image = nil; lastQR = "" }
        downloadCaption.stringValue = L("Android 17 及以上 · 云端下载", "Android 17 or later · Online download")
        pairingCaption.stringValue = engine.peer != nil ? L("扫码更新连接地址", "Scan to update the connection address") : String(format: L("配对码剩余 %d 秒", "Pairing code expires in %d s"), engine.secondsLeft)
        status.stringValue = paused ? "已暂停 · 手机将停止本轮连接" : !failure.isEmpty ? "连接服务需要处理" : !ready ? "等待局域网" : engine.online ? "手机已连接" : engine.peer != nil ? "已配对 · 等待手机连接" : "准备好了，开始连接手机"
        peerLabel.stringValue = engine.peer.map { "\($0.name)  ·  双向确认 \(engine.heartbeats) 次" } ?? "尚未绑定手机 · 配对完成后显示实时连接"
        unpair.isEnabled = engine.peer != nil; refresh.isEnabled = engine.peer == nil
        productUI?.refresh()
        details.stringValue = failure.isEmpty ? "\(displayName) · 本机身份 \(identity.pin.prefix(12))\n\(ready ? "加密连接 :\(control.port) · 10 秒无心跳显示离线" : "检查 Wi-Fi 或本机网络权限")" : failure
    }
    func didPairPhone() {
        handoff.enabled = true
        UserDefaults.standard.set(true, forKey: "automaticSwitchingEnabled")
        productUI.navigate("onboarding")
        update()
    }
    func clearPeer(notify: Bool) throws {
        let pairingID = engine.peer?.requestID ?? ""
        // Send to an existing TLS event listener before revoking its session. Never wait for receipt.
        if notify { control?.publishEvent(.json(["server_id": identity.id, "event": "unpaired", "pairing_id": pairingID])) }
        cancelManual(); handoff.cancel("解除配对"); hardware.cancel(); observation?.invalidate()
        try observation?.clear(); try engine.unpair()
        control?.publishEvent(.json(["server_id": identity.id, "event": "closed"]))
        phoneRecoveryReady = false
        lastQR = ""; productUI.navigate("home"); update()
    }
    func resetConnections() {
        guard window.attachedSheet == nil else { return }
        let dialog = NSAlert()
        dialog.messageText = L("重置所有连接？", "Reset all connections?")
        dialog.informativeText = L("清除 Sound Link 的手机配对、耳机选择和自动切换状态，并尝试通知手机。再次连接需重新扫码。系统蓝牙配对、语言、外观与日志会保留。", "Clear Sound Link’s phone pairing, headphone selection and automatic switching state, and try to notify your phone. Scan a new code to reconnect. System Bluetooth pairings, language, appearance and logs are kept.")
        dialog.addButton(withTitle: L("重置连接", "Reset Connections"))
        dialog.addButton(withTitle: L("取消", "Cancel"))
        dialog.buttons[0].hasDestructiveAction = true
        dialog.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn else { return }
            do {
                // Also works without a peer, clearing an abandoned invitation/target.
                try self.clearPeer(notify: true)
                self.handoff.enabled = false
                UserDefaults.standard.removeObject(forKey: "automaticSwitchingEnabled")
                self.paused = false
                self.engine.setAvailable(true)
                self.discovery?.setAvailable(true)
                self.promptedTargetRevision = -1
                self.diagnostics?.event("connections_reset", data: [:])
                self.update()
            } catch { self.failure = error.localizedDescription; self.update() }
        }
    }
    func removePeer() {
        guard engine?.peer != nil else { return }
        let dialog = NSAlert(); dialog.messageText = L("解除手机配对？", "Unpair this phone?"); dialog.informativeText = L("再次连接需要重新扫码。", "You’ll need to scan a new code to reconnect."); dialog.addButton(withTitle: L("解除配对", "Unpair")); dialog.addButton(withTitle: L("取消", "Cancel"))
        if dialog.runModal() == .alertFirstButtonReturn { do { try clearPeer(notify: true) } catch { failure = error.localizedDescription; update() } }
    }
    func api(_ r: HTTPRequest) throws -> HTTPResponse {
        do {
            var result: [String: Any]
            switch (r.method, r.path) {
            case ("POST", "/v1/pair"):
                let wasUnpaired = engine.peer == nil
                result = try engine.pair(r.object())
                // Idempotent retries must not reset preferences or reopen onboarding.
                if wasUnpaired && engine.peer != nil { didPairPhone() }
            case ("POST", "/v1/unpair"):
                try engine.validateUnpair(r.object()); try clearPeer(notify: false); result = ["state": "unpaired"]
            case ("POST", "/v1/events"):
                _ = try engine.authorize(r.token)
                var response = HTTPResponse.json(["server_id": identity.id, "event": "idle", "pairing_id": engine.peer?.requestID ?? ""])
                response.waitForEvent = true; return response
            case ("POST", "/v1/challenge"): result = try engine.challenge(r.object())
            case ("POST", "/v1/session"): result = try engine.session(r.object())
            case ("POST", "/v1/ping"):
                let body = try r.object(); result = try engine.ping(r.token, body: body)
                diagnostics?.receive(body["diagnostics"])
                phoneRecoveryReady = body["recoveryReady"] as? Bool ?? false
                result["pairing_id"] = engine.peer?.requestID ?? ""
                result["supports_events"] = true
                diagnostics?.observePhone(body["observation"])
                observation?.receive(body["observation"], epoch: result["epoch"] as? String ?? "")
                if let report = (body["handoff"] as? [String: Any])?["result"] as? [String: Any], let id = report["id"] as? String {
                    let key = id + ":" + (report["stage"] as? String ?? "")
                    if key != lastPhoneHandoffReport { lastPhoneHandoffReport = key; diagnostics?.event("phone_handoff_report", data: report) }
                }
                if let o = observation { handoff.receive(body["handoff"], observation: o) }
                // Echo only after a fresh local sample: stale in-flight responses cannot unlock phone failure.
                let phoneToken = String(((body["handoff"] as? [String: Any])?["localRecoveryToken"] as? String ?? "").prefix(100))
                if !phoneToken.isEmpty { observedAt = 0 }
                updateObservation()
                var snapshot = observation?.object ?? [:]
                snapshot["phoneRecoveryToken"] = phoneToken
                result["observation"] = snapshot
                var handoffSnapshot = handoff.object
                handoffSnapshot["phoneLocalGeneration"] = (body["observation"] as? [String: Any])?["localControlGeneration"] as? String ?? ""
                result["handoff"] = handoffSnapshot
                result["diagnosticProbe"] = diagnostics?.probe
            case ("POST", "/v1/observe/target"):
                _ = try engine.authorize(r.token)
                let body = try r.object()
                // Apply before invalidating hardware work; rejected/stale requests have no effects.
                let before = observation?.target.revision
                try observation?.selectPhone(body)
                if observation?.target.revision != before { cancelManual(); handoff.cancel("手机选择耳机"); hardware.cancel() }
                updateObservation()
                result = ["observation": observation?.object ?? [:]]
            default: return .json(["error": "NOT_FOUND", "message": "接口不存在"], status: 404)
            }
            result["server_id"] = identity.id; return .json(result)
        } catch {
            let error = error as? LinkError ?? LinkError("INVALID_REQUEST", "请求校验失败")
            return .json(["error": error.code, "message": error.message], status: error.code == "SESSION_EXPIRED" || error.code == "NOT_PAIRED" ? 401 : 400)
        }
    }
    @objc func show() { window?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true) }
    @objc func quit() { NSApp.terminate(nil) }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { show(); return true }
    func applicationWillTerminate(_ note: Notification) { cancelManual(); handoff.cancel("Mac 退出"); hardware.cancel(); hardware.speakerGuard.stop(); diagnostics?.event("mac_exit", data: [:]); timer?.invalidate(); audioTimer?.invalidate(); control?.stop(); engine?.suspend(); discovery?.stop() }
}
if CommandLine.arguments.contains("--speaker-guard") { runSpeakerGuard(); exit(0) }
let app = NSApplication.shared
let delegate = Application(); app.delegate = delegate; app.setActivationPolicy(.regular); app.run()
