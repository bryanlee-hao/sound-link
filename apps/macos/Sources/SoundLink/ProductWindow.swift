import AppKit
import SoundLinkCore

/// Resolve card contrast while drawing so an open window follows appearance changes.
private final class Surface: NSStackView {
    override func draw(_ dirtyRect: NSRect) {
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        (dark ? NSColor(srgbRed: 0.17, green: 0.17, blue: 0.18, alpha: 1) : .white).setFill()
        let shape = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: 18, yRadius: 18)
        shape.fill()
        (dark ? NSColor.separatorColor.withAlphaComponent(0.18) : NSColor(srgbRed: 0.83, green: 0.85, blue: 0.88, alpha: 1)).setStroke()
        shape.lineWidth = 1; shape.stroke()
        super.draw(dirtyRect)
    }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance(); needsDisplay = true
    }
}
/// Reserve a consistent icon column and a 12-point gap in settings rows.
private final class SettingsButtonCell: NSButtonCell {
    override func drawTitle(_ title: NSAttributedString, withFrame frame: NSRect, in view: NSView) -> NSRect {
        let text = NSMutableAttributedString(attributedString: title)
        text.addAttribute(.foregroundColor, value: isEnabled ? NSColor.labelColor : NSColor.disabledControlTextColor,
                          range: NSRange(location: 0, length: text.length))
        return super.drawTitle(text, withFrame: frame, in: view)
    }
    override func imageRect(forBounds rect: NSRect) -> NSRect {
        NSRect(x: rect.minX, y: rect.midY - 9, width: 18, height: 18)
    }
    override func titleRect(forBounds rect: NSRect) -> NSRect {
        let native = super.titleRect(forBounds: rect)
        return NSRect(x: rect.minX + 30, y: native.minY, width: max(0, rect.width - 30), height: native.height)
    }
}
final class ProductWindow: NSObject, NSToolbarDelegate {
    unowned let app: Application
    let window: NSWindow
    let scroll = NSScrollView()
    let document = FlippedDocument()
    let stack = NSStackView()
    var pages: [String: NSStackView] = [:]
    var page = "home"
    private var downloadReturnPage = "home"
    private let homePairingQR = NSImageView()
    private let homePairingCaption = NSTextField(wrappingLabelWithString: "")
    private let toolbar = NSToolbar(identifier: "SoundLink.Navigation")
    private let backID = NSToolbarItem.Identifier("SoundLink.Back")
    private let settingsID = NSToolbarItem.Identifier("SoundLink.Settings")
    let name = NSTextField(wrappingLabelWithString: "")
    let state = NSTextField(wrappingLabelWithString: "")
    let macState = NSTextField(labelWithString: "")
    let phoneState = NSTextField(labelWithString: "")
    let peerName = NSTextField(wrappingLabelWithString: "")
    let peerState = NSTextField(labelWithString: "")
    let guidance = NSTextField(wrappingLabelWithString: "")
    let attention = NSTextField(wrappingLabelWithString: "")
    let logState = NSTextField(wrappingLabelWithString: "")
    let automatic = NSSwitch()
    let macIcon = NSImageView(), phoneIcon = NSImageView()
    var setup: ActionButton!
    var hero: NSStackView!, empty: NSStackView!, peerCard: NSStackView!
    private let setupReconnectTitle = NSTextField(wrappingLabelWithString: "")
    private let setupReconnectDetail = NSTextField(wrappingLabelWithString: "")
    private let setupHeadphoneTitle = NSTextField(wrappingLabelWithString: "")
    private let setupHeadphoneDetail = NSTextField(wrappingLabelWithString: "")
    private let setupControlTitle = NSTextField(wrappingLabelWithString: "")
    private let setupControlDetail = NSTextField(wrappingLabelWithString: "")
    private let setupPicker = NSPopUpButton()
    private var setupSelection: NSStackView!
    private var setupApply: ActionButton!
    private var setupChange: ActionButton!
    private var setupDone: ActionButton!
    private var setupEditingHeadphones = false
    private var setupReturnScheduled = false
    private var setupPickerRevision = -1
    private var setupPickerPairingID: String?
    let headphoneStatus = NSTextField(wrappingLabelWithString: "")
    private let selectedHeadphoneName = NSTextField(wrappingLabelWithString: "")
    private let selectedMacName = NSTextField(wrappingLabelWithString: "")
    private let selectedPhoneName = NSTextField(wrappingLabelWithString: "")
    private let controlStatus = NSTextField(wrappingLabelWithString: "")
    private var changeHeadphoneButton: ActionButton!
    private var clearHeadphoneButton: ActionButton!
    private var confirmHeadphoneButton: ActionButton!
    private var setupAlert: NSAlert?
    var bindings: [(String, String, (String) -> Void)] = []
    let width: CGFloat = 500
    init(_ app: Application) {
        self.app = app
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 660), styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        super.init()
        window.title = "Sound Link"; window.isReleasedWhenClosed = false
        applyAppearance()
        window.backgroundColor = NSColor(name: "SoundLink.WindowBackground") { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? NSColor(srgbRed: 0.11, green: 0.11, blue: 0.12, alpha: 1)
                : NSColor(srgbRed: 0.93, green: 0.94, blue: 0.96, alpha: 1)
        }
        scroll.frame = window.contentView!.bounds; scroll.autoresizingMask = [.width, .height]
        scroll.hasVerticalScroller = true; scroll.drawsBackground = false; scroll.documentView = document
        window.contentView!.addSubview(scroll)
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 20; stack.translatesAutoresizingMaskIntoConstraints = false
        document.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: document.leadingAnchor, constant: 30), stack.topAnchor.constraint(equalTo: document.topAnchor, constant: 24), stack.widthAnchor.constraint(equalToConstant: width)])
        // Standard toolbar items adopt Liquid Glass on macOS 26 and the native
        // toolbar appearance on older systems, without a custom button backing.
        toolbar.delegate = self
        toolbar.displayMode = .iconOnly
        toolbar.allowsUserCustomization = false
        toolbar.autosavesConfiguration = false
        window.toolbarStyle = .unified
        window.toolbar = toolbar
        attention.font = .systemFont(ofSize: 13); attention.textColor = .systemOrange; attention.isHidden = true; stack.addArrangedSubview(attention); attention.widthAnchor.constraint(equalToConstant: width).isActive = true
        buildHome(); buildSettings(); buildHeadphones(); buildDevice(); buildPairing(); buildOnboarding(); buildDiagnostics()
        for key in ["home", "settings", "headphones", "device", "pairing", "download", "diagnostics", "advanced", "onboarding"] {
            if let view = pages[key] { stack.addArrangedSubview(view); view.widthAnchor.constraint(equalToConstant: width).isActive = true }
        }
        navigate("home"); window.center()
    }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [backID, .flexibleSpace, settingsID]
    }
    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace, settingsID]
    }
    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier identifier: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard identifier == backID || identifier == settingsID else { return nil }
        let item = NSToolbarItem(itemIdentifier: identifier)
        let isBack = identifier == backID
        item.label = isBack ? L("返回", "Back") : L("设置", "Settings")
        item.toolTip = item.label
        item.image = NSImage(systemSymbolName: isBack ? "chevron.left" : "slider.horizontal.3", accessibilityDescription: item.label)
        item.target = self
        item.action = isBack ? #selector(goBack) : #selector(showSettings)
        item.isBordered = true
        item.autovalidates = false
        if isBack { item.isNavigational = true }
        return item
    }
    @objc private func goBack() {
        navigate(page == "download" ? downloadReturnPage : page == "settings" || page == "device" || page == "pairing" ? "home" : page == "advanced" ? "diagnostics" : "settings")
    }
    @objc private func showSettings() { navigate("settings") }
    private func updateToolbar() {
        let desired: [NSToolbarItem.Identifier] = page == "home" ? [.flexibleSpace, settingsID] : [backID, .flexibleSpace]
        if toolbar.items.map(\.itemIdentifier) != desired {
            while !toolbar.items.isEmpty { toolbar.removeItem(at: 0) }
            for (index, identifier) in desired.enumerated() { toolbar.insertItem(withItemIdentifier: identifier, at: index) }
        }
        for item in toolbar.items where item.itemIdentifier == backID || item.itemIdentifier == settingsID {
            item.label = item.itemIdentifier == backID ? L("返回", "Back") : L("设置", "Settings")
            item.toolTip = item.label
            item.image?.accessibilityDescription = item.label
        }
    }
    func label(_ zh: String, _ en: String, size: CGFloat = 13, secondary: Bool = false) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: L(zh, en)); field.font = .systemFont(ofSize: size)
        field.textColor = secondary ? .secondaryLabelColor : .labelColor
        bindings.append((zh, en, { [weak field] in field?.stringValue = $0 })); return field
    }
    func button(_ zh: String, _ en: String, action: @escaping () -> Void) -> ActionButton {
        let b = ActionButton(L(zh, en), action)
        bindings.append((zh, en, { [weak b] in b?.title = $0; b?.setAccessibilityLabel($0) })); return b
    }
    func link(_ zh: String, _ en: String, symbol: String, action: @escaping () -> Void) -> ActionButton {
        let b = button(zh, en, action: action)
        let cell = SettingsButtonCell(textCell: L(zh, en)); cell.target = b; cell.action = #selector(ActionButton.run)
        b.cell = cell; b.isBordered = false; b.alignment = .left
        b.setButtonType(.momentaryPushIn); b.isEnabled = true; b.contentTintColor = .labelColor
        b.font = .systemFont(ofSize: 13); b.imageHugsTitle = false
        b.setAccessibilityLabel(L(zh, en))
        b.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil); b.imagePosition = .imageLeading
        b.heightAnchor.constraint(greaterThanOrEqualToConstant: 32).isActive = true
        return b
    }
    func vertical(_ spacing: CGFloat = 14) -> NSStackView {
        let v = NSStackView(); v.orientation = .vertical; v.alignment = .leading; v.spacing = spacing; return v
    }
    func row(_ children: [NSView]) -> NSStackView {
        let v = NSStackView(views: children); v.orientation = .horizontal; v.alignment = .centerY; v.spacing = 12
        for child in children where type(of: child) == NSView.self { child.setContentHuggingPriority(.init(1), for: .horizontal) }
        return v
    }
    func card(_ parent: NSStackView) -> NSStackView {
        let v = Surface(); v.orientation = .vertical; v.alignment = .leading; v.spacing = 16
        v.edgeInsets = NSEdgeInsets(top: 22, left: 24, bottom: 22, right: 24)
        parent.addArrangedSubview(v); v.widthAnchor.constraint(equalToConstant: width).isActive = true; return v
    }
    func full(_ view: NSView, in parent: NSStackView) { parent.addArrangedSubview(view); view.widthAnchor.constraint(equalToConstant: width - 48).isActive = true }
    func makePage(_ key: String) -> NSStackView { let v = vertical(); pages[key] = v; return v }
    func icon(_ symbol: String, size: CGFloat) -> NSImageView {
        let image = NSImageView(); image.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(.init(pointSize: size, weight: .light))
        image.contentTintColor = .labelColor; image.widthAnchor.constraint(equalToConstant: size + 12).isActive = true; image.heightAnchor.constraint(equalToConstant: size + 12).isActive = true; return image
    }
    func separator(_ parent: NSStackView) { let rule = NSBox(); rule.boxType = .separator; full(rule, in: parent) }
    func buildHome() {
        let home = makePage("home")
        empty = vertical(16); home.addArrangedSubview(empty)
        empty.widthAnchor.constraint(equalToConstant: width).isActive = true
        let welcome = card(empty); welcome.alignment = .centerX
        let leftArrow = icon("arrow.left.arrow.right", size: 14), rightArrow = icon("arrow.left.arrow.right", size: 14)
        leftArrow.contentTintColor = .tertiaryLabelColor; rightArrow.contentTintColor = .tertiaryLabelColor
        let connection = row([icon("laptopcomputer", size: 26), leftArrow, icon("headphones", size: 42), rightArrow, icon("iphone", size: 26)])
        connection.spacing = 18; welcome.addArrangedSubview(connection)
        let headline = label("一副耳机，连接两端", "One pair. Both devices.", size: 25)
        headline.font = .systemFont(ofSize: 25, weight: .semibold); headline.alignment = .center
        full(headline, in: welcome)
        let subtitle = label("Mac 与 Android，随播放自动切换。", "Switch between Mac and Android as playback starts.", secondary: true)
        subtitle.alignment = .center; full(subtitle, in: welcome)
        buildHomePairing(in: welcome)
        let features = card(empty); features.spacing = 20
        for (symbol, zh, en, detailZH, detailEN) in [
            ("arrow.left.arrow.right", "没有 Multipoint，也能流转", "Switch even without Multipoint", "让不支持多点连接的蓝牙耳机，也能在 Mac 与 Android 之间流转。", "Let Bluetooth headphones without multipoint switch between Mac and Android."),
            ("headphones", "AirPods × Android × Mac", "AirPods × Android × Mac", "连接 Mac 与 Android，让 AirPods 跨系统使用更顺手。", "Bring your AirPods into a Mac and Android workflow."),
            ("play.circle", "播放在哪，耳机就切到哪", "Follow your playback", "后台自动切换，减少手动断开与重连。", "Switch in the background, with less manual disconnecting and reconnecting.")
        ] {
            let mark = icon(symbol, size: 21); mark.contentTintColor = .systemBlue
            let copy = vertical(5)
            let title = label(zh, en, size: 14); title.font = .systemFont(ofSize: 14, weight: .medium)
            let description = label(detailZH, detailEN, size: 12, secondary: true)
            copy.addArrangedSubview(title); copy.addArrangedSubview(description)
            copy.widthAnchor.constraint(equalToConstant: width - 48 - 45).isActive = true
            title.widthAnchor.constraint(equalTo: copy.widthAnchor).isActive = true
            description.widthAnchor.constraint(equalTo: copy.widthAnchor).isActive = true
            full(row([mark, copy]), in: features)
        }
        hero = card(home); hero.alignment = .centerX; hero.spacing = 18
        hero.addArrangedSubview(icon("headphones", size: 42))
        name.font = .systemFont(ofSize: 27, weight: .semibold); name.alignment = .center; full(name, in: hero)
        state.font = .systemFont(ofSize: 13); state.alignment = .center; state.textColor = .systemBlue; full(state, in: hero)
        let devices = row([]); devices.spacing = 35
        for (symbol, title, image, status) in [("laptopcomputer", "Mac", macIcon, macState), ("iphone", "Android", phoneIcon, phoneState)] {
            let col = vertical(7); col.alignment = .centerX
            image.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(.init(pointSize: 27, weight: .light))
            image.widthAnchor.constraint(equalToConstant: 48).isActive = true; image.heightAnchor.constraint(equalToConstant: 44).isActive = true
            col.addArrangedSubview(image); col.addArrangedSubview(label(title, title))
            status.font = .systemFont(ofSize: 12); col.addArrangedSubview(status); col.widthAnchor.constraint(equalToConstant: 130).isActive = true
            if !devices.arrangedSubviews.isEmpty { devices.addArrangedSubview(icon("arrow.left.arrow.right", size: 17)) }
            devices.addArrangedSubview(col)
        }
        hero.addArrangedSubview(devices); separator(hero)
        let copy = vertical(4); copy.addArrangedSubview(label("自动切换", "Automatic switching", size: 14)); copy.addArrangedSubview(label("播放时，耳机跟随这台设备", "Headphones follow playback on this Mac", size: 12, secondary: true))
        automatic.target = app; automatic.action = #selector(Application.toggleAutomatic); automatic.setAccessibilityLabel(L("自动切换", "Automatic switching"))
        full(row([copy, NSView(), automatic]), in: hero)
        guidance.font = .systemFont(ofSize: 12); guidance.textColor = .secondaryLabelColor; guidance.alignment = .center; full(guidance, in: hero)
        setup = button("继续设置", "Continue Setup") { [weak self] in self?.navigate("onboarding") }; hero.addArrangedSubview(setup)
        peerCard = card(home)
        let peerCopy = vertical(5); peerName.font = .systemFont(ofSize: 14, weight: .medium); peerState.font = .systemFont(ofSize: 12); peerState.textColor = .secondaryLabelColor
        peerCopy.addArrangedSubview(peerName); peerCopy.addArrangedSubview(peerState)
        let manage = button("管理", "Manage") { [weak self] in self?.navigate("device") }
        full(row([icon("iphone", size: 22), peerCopy, NSView(), manage]), in: peerCard)
        let foot = label("关闭窗口后继续运行", "Keeps running when you close this window", size: 11, secondary: true); foot.alignment = .center
        home.addArrangedSubview(foot); foot.widthAnchor.constraint(equalToConstant: width).isActive = true
    }
    func buildSettings() {
        let page = makePage("settings")
        page.spacing = 24
        func setting(_ view: NSView, in panel: NSStackView) {
            if !panel.arrangedSubviews.isEmpty { separator(panel) }
            let line = row([view])
            line.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            view.widthAnchor.constraint(equalTo: line.widthAnchor).isActive = true
            full(line, in: panel)
        }
        func group(_ zh: String, _ en: String) -> NSStackView {
            let heading = label(zh, en, size: 13, secondary: true)
            heading.font = .systemFont(ofSize: 13, weight: .semibold)
            let section = vertical(8)
            page.addArrangedSubview(section)
            section.addArrangedSubview(heading)
            let panel = card(section)
            panel.spacing = 0
            panel.edgeInsets = NSEdgeInsets(top: 8, left: 24, bottom: 8, right: 24)
            return panel
        }
        let devices = group("设备与连接", "Devices & Connection")
        setting(link("耳机…", "Headphones…", symbol: "headphones") { [weak self] in self?.navigate("headphones") }, in: devices)
        setting(link("已配对的手机…", "Paired Phone…", symbol: "iphone") { [weak self] in self?.navigate("device") }, in: devices)
        setting(link("完成连接设置…", "Complete Setup…", symbol: "checklist") { [weak self] in self?.navigate("onboarding") }, in: devices)
        setting(link("安装 Android App", "Install Android App", symbol: "arrow.down.app") { [weak self] in self?.navigate("download") }, in: devices)
        let options = group("通用", "General")
        let offline = NSButton(checkboxWithTitle: L("手机离线时允许 Mac 接管", "Allow switching when phone is offline"), target: app, action: #selector(Application.toggleOfflineMac(_:)))
        bindings.append(("手机离线时允许 Mac 接管", "Allow switching when phone is offline", { [weak offline] in offline?.title = $0 }))
        offline.state = (UserDefaults.standard.object(forKey: "allowOfflineMac") as? Bool ?? true) ? .on : .off
        setting(offline, in: options)
        let language = NSPopUpButton(); language.addItems(withTitles: [L("跟随系统", "System Default"), "简体中文", "English"])
        bindings.append(("跟随系统", "System Default", { [weak language] in language?.item(at: 0)?.title = $0 }))
        language.selectItem(at: ["system", "zh", "en"].firstIndex(of: AppLanguage.preference) ?? 0); language.target = self; language.action = #selector(changeLanguage(_:))
        setting(row([label("语言", "Language"), language, NSView()]), in: options)
        let appearance = NSPopUpButton()
        for (zh, en) in [("跟随系统", "System Default"), ("浅色", "Light"), ("深色", "Dark")] {
            let index = appearance.numberOfItems
            appearance.addItem(withTitle: L(zh, en))
            bindings.append((zh, en, { [weak appearance] in appearance?.item(at: index)?.title = $0 }))
        }
        appearance.selectItem(at: ["system", "light", "dark"].firstIndex(of: UserDefaults.standard.string(forKey: "interfaceAppearance") ?? "system") ?? 0)
        appearance.target = self; appearance.action = #selector(changeAppearance(_:))
        appearance.setAccessibilityLabel(L("外观", "Appearance"))
        bindings.append(("外观", "Appearance", { [weak appearance] in appearance?.setAccessibilityLabel($0) }))
        setting(row([label("外观", "Appearance"), appearance, NSView()]), in: options)
        let support = group("帮助与关于", "Help & About")
        setting(link("重置连接…", "Reset Connections…", symbol: "arrow.counterclockwise") { [weak self] in self?.app.resetConnections() }, in: support)
        setting(link("帮助与诊断…", "Help & Diagnostics…", symbol: "questionmark.circle") { [weak self] in self?.navigate("diagnostics") }, in: support)
        if Distribution.updatesEnabled {
        setting(link("检查更新…", "Check for Updates…", symbol: "arrow.down.circle") { [weak self] in self?.app.checkForUpdates() }, in: support)
        }
        setting(link("关于 Sound Link", "About Sound Link", symbol: "info.circle") { [weak self] in self?.app.about() }, in: support)
    }
    func buildHeadphones() {
        let page = makePage("headphones"), current = card(page)
        full(label("当前耳机", "Current headphones", size: 12, secondary: true), in: current)
        selectedHeadphoneName.font = .systemFont(ofSize: 22, weight: .semibold)
        full(row([icon("headphones", size: 28), selectedHeadphoneName]), in: current)
        changeHeadphoneButton = button("选择耳机…", "Choose Headphones…") { [weak self] in self?.presentHeadphonePicker() }
        // The observation updater retains this data source; selection is presented in a sheet.
        app.targetPicker = NSPopUpButton(); app.targetPicker.target = app; app.targetPicker.action = #selector(Application.selectTarget)
        let sync = card(page)
        full(label("跨设备同步", "Device sync", size: 16), in: sync)
        headphoneStatus.font = .systemFont(ofSize: 13); full(headphoneStatus, in: sync)
        for (title, value) in [("Mac", selectedMacName), ("Android", selectedPhoneName)] {
            let platform = label(title, title, secondary: true)
            platform.widthAnchor.constraint(equalToConstant: 72).isActive = true
            value.font = .systemFont(ofSize: 13)
            full(row([platform, value]), in: sync)
        }
        confirmHeadphoneButton = button("确认手机选择…", "Confirm Phone Selection…") { [weak self] in
            self?.app.promptedTargetRevision = -1; self?.app.reconcileHeadphoneTarget()
        }
        sync.addArrangedSubview(confirmHeadphoneButton)
        let control = card(page)
        full(label("手机耳机控制", "Phone headphone control", size: 16), in: control)
        controlStatus.font = .systemFont(ofSize: 13); controlStatus.textColor = .secondaryLabelColor
        full(controlStatus, in: control)
        clearHeadphoneButton = button("清除选择…", "Clear Selection…") { [weak self] in
            guard let self else { return }
            let alert = NSAlert(); alert.messageText = L("清除耳机选择？", "Clear headphone selection?")
            alert.informativeText = L("自动切换将暂停，直到重新选择耳机。", "Automatic switching will wait until headphones are selected again.")
            alert.addButton(withTitle: L("清除选择", "Clear Selection")); alert.addButton(withTitle: L("取消", "Cancel"))
            alert.beginSheetModal(for: self.window) { [weak self] response in
                guard let self, response == .alertFirstButtonReturn else { return }
                self.app.cancelManual(); self.app.handoff.cancel("清除目标"); self.app.hardware.cancel()
                do { try self.app.observation?.clear(); self.app.update() } catch { self.app.failure = error.localizedDescription }
            }
        }
        clearHeadphoneButton.isBordered = false; clearHeadphoneButton.contentTintColor = .systemRed
        full(row([changeHeadphoneButton, NSView(), clearHeadphoneButton]), in: current)
    }
    private static func openBluetoothSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings") { NSWorkspace.shared.open(url) }
    }
    @objc private func headphonePickerChanged(_ sender: NSPopUpButton) {
        setupAlert?.buttons.first?.isEnabled = sender.indexOfSelectedItem > 0
    }
    private func presentHeadphonePicker() {
        guard window.attachedSheet == nil, let target = app.observation?.target else { return }
        let devices = app.audioObserver.devices(), pairingID = app.engine?.peer?.requestID
        let alert = NSAlert(); setupAlert = alert
        alert.messageText = L("选择耳机", "Choose Headphones")
        alert.informativeText = devices.isEmpty
            ? L("未找到已配对的耳机。请先在 Mac 蓝牙设置中配对，再回来选择。", "No paired headphones found. Pair them in Mac Bluetooth settings, then choose them here.")
            : L("选择已在此 Mac 配对的耳机，手机会尝试自动匹配。", "Choose headphones paired with this Mac. Your phone will try to match them.")
        alert.addButton(withTitle: L("使用这副耳机", "Use These Headphones"))
        alert.addButton(withTitle: L("取消", "Cancel"))
        alert.addButton(withTitle: L("蓝牙设置…", "Bluetooth Settings…"))
        let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 320, height: 30))
        picker.addItems(withTitles: [L("请选择…", "Select…")] + devices.map { "\($0.name) · \($0.address.suffix(5))" })
        if let index = devices.firstIndex(where: { $0.address == target.macAddress }) { picker.selectItem(at: index + 1) }
        picker.target = self; picker.action = #selector(headphonePickerChanged(_:))
        alert.accessoryView = picker; alert.buttons[0].isEnabled = picker.indexOfSelectedItem > 0
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.setupAlert === alert else { return }
            self.setupAlert = nil
            if response == .alertThirdButtonReturn { Self.openBluetoothSettings(); return }
            guard response == .alertFirstButtonReturn, self.app.engine?.peer?.requestID == pairingID,
                  self.app.observation?.target.revision == target.revision,
                  devices.indices.contains(picker.indexOfSelectedItem - 1) else { return }
            self.app.selectHeadphone(address: devices[picker.indexOfSelectedItem - 1].address)
        }
    }
    func buildDevice() {
        let page = makePage("device"), panel = card(page)
        full(button("显示配对码", "Show Pairing Code") { [weak self] in self?.navigate("pairing") }, in: panel)
        full(button("安装 Android App", "Install Android App") { [weak self] in self?.navigate("download") }, in: panel)
        app.pauseButton = button("暂停设备连接", "Pause Device Connection") { [weak self] in self?.app.togglePause() }; full(app.pauseButton, in: panel)
        app.unpair = button("解除手机配对…", "Unpair Phone…") { [weak self] in self?.app.removePeer() }; full(app.unpair, in: panel)
    }
    private func buildHomePairing(in welcome: NSStackView) {
        let panel = vertical(12); panel.alignment = .centerX
        full(panel, in: welcome)
        // Mirror the pairing image; the separate pairing page retains its own view.
        homePairingQR.imageScaling = .scaleProportionallyUpOrDown
        homePairingQR.widthAnchor.constraint(equalToConstant: 230).isActive = true
        homePairingQR.heightAnchor.constraint(equalToConstant: 230).isActive = true
        panel.addArrangedSubview(homePairingQR)
        let hint = label("使用手机上的 Sound Link App 扫码配对。", "Scan with the Sound Link app on your Android phone.", secondary: true)
        hint.alignment = .center; full(hint, in: panel)
        let download = button("还未安装？下载 Android App", "Need the app? Download for Android") { [weak self] in self?.navigate("download") }
        download.isBordered = false; download.contentTintColor = .linkColor
        panel.addArrangedSubview(download)
        homePairingCaption.font = .systemFont(ofSize: 12); homePairingCaption.textColor = .secondaryLabelColor
        let refresh = button("刷新", "Refresh") { [weak self] in
            self?.app.engine?.refreshInvite(); self?.app.lastQR = ""; self?.app.update()
        }
        refresh.isBordered = false; refresh.contentTintColor = .secondaryLabelColor
        refresh.font = .systemFont(ofSize: 12)
        refresh.setAccessibilityLabel(L("刷新配对码", "Refresh Pairing Code"))
        bindings.append(("刷新配对码", "Refresh Pairing Code", { [weak refresh] in refresh?.setAccessibilityLabel($0) }))
        panel.addArrangedSubview(row([homePairingCaption, refresh]))
    }
    func buildPairing() {
        for key in ["pairing", "download"] {
            let page = makePage(key)
            if key == "download" && app.downloadURL.isEmpty {
                let panel = card(page)
                full(label("请在手机安装配套的 Android 开发包，再返回扫码配对。", "Install the companion Android development build on your phone, then return to pair.", secondary: true), in: panel)
                panel.addArrangedSubview(button("去配对", "Pair Phone") { [weak self] in self?.navigate("pairing") })
                continue
            }
            if key == "pairing" {
                let install = card(page)
                full(label("1 · 在手机安装 Sound Link", "1 · Install Sound Link on your phone", size: 16), in: install)
                full(label("在手机上安装配套的 Android App 后打开。", "Install the companion Android app on your phone, then open it.", secondary: true), in: install)
                if !app.downloadURL.isEmpty {
                    install.addArrangedSubview(button("下载 Android App", "Download Android App") { [weak self] in self?.navigate("download") })
                }
            }
            let panel = card(page); panel.alignment = .centerX
            let heading = label(key == "pairing" ? "2 · 用 Sound Link 扫码配对" : "下载 Android App", key == "pairing" ? "2 · Scan with Sound Link to pair" : "Download Android App", size: 16)
            heading.alignment = .center; full(heading, in: panel)
            let hint = label(key == "pairing" ? "打开手机上的 Sound Link，点击「扫描配对码」，扫描下方二维码。" : "用手机相机扫描下方二维码，下载 APK 并安装。", key == "pairing" ? "Open Sound Link on your phone, tap “Scan pairing code”, then scan the code below." : "Scan the code below with your phone’s camera to download and install the APK.", secondary: true)
            hint.alignment = .center; full(hint, in: panel)
            let image = key == "pairing" ? app.pairingQR : app.downloadQR
            image.imageScaling = .scaleProportionallyUpOrDown; image.widthAnchor.constraint(equalToConstant: 230).isActive = true; image.heightAnchor.constraint(equalToConstant: 230).isActive = true; panel.addArrangedSubview(image)
            let caption = key == "pairing" ? app.pairingCaption : app.downloadCaption; caption.font = .systemFont(ofSize: 12); caption.textColor = .secondaryLabelColor; caption.alignment = .center; full(caption, in: panel)
            if key == "pairing" {
                app.refresh = button("刷新配对码", "Refresh Pairing Code") { [weak self] in self?.app.engine?.refreshInvite(); self?.app.lastQR = ""; self?.app.update() }; panel.addArrangedSubview(app.refresh)
            } else {
                let address = button(app.downloadURL, app.downloadURL) { [weak self] in
                    guard let self else { return }
                    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(self.app.downloadURL, forType: .string)
                }
                address.toolTip = L("点击复制下载地址", "Click to copy download address")
                panel.addArrangedSubview(address)
                panel.addArrangedSubview(label("点击地址即可复制", "Click the address to copy", size: 12, secondary: true))
                panel.addArrangedSubview(button("已安装，去配对", "Installed — Pair Phone") { [weak self] in
                    guard let self else { return }
                    self.navigate(self.downloadReturnPage == "home" ? "home" : "pairing")
                })
            }
        }
    }
    private var currentSetupStep: SetupStep {
        let target = app.observation?.target
        return SetupStep.next(reconnected: app.phoneRecoveryReady,
            localSelected: target?.macAddress.isEmpty == false, remoteSelected: target?.phoneAddress.isEmpty == false,
            pendingSide: target?.pendingSide ?? "", localSide: "mac", controlReady: app.handoff.phoneReady)
    }
    func buildOnboarding() {
        let page = makePage("onboarding")
        for (title, detail) in [(setupReconnectTitle, setupReconnectDetail),
                                (setupHeadphoneTitle, setupHeadphoneDetail),
                                (setupControlTitle, setupControlDetail)] {
            let panel = card(page)
            title.font = .systemFont(ofSize: 17, weight: .semibold); full(title, in: panel)
            detail.font = .systemFont(ofSize: 13); detail.textColor = .secondaryLabelColor; full(detail, in: panel)
            if title === setupHeadphoneTitle {
                setupSelection = vertical(12); full(setupSelection, in: panel)
                setupPicker.target = self; setupPicker.action = #selector(setupPickerChanged(_:))
                setupPicker.setAccessibilityLabel(L("选择耳机", "Choose Headphones"))
                bindings.append(("选择耳机", "Choose Headphones", { [weak self] in self?.setupPicker.setAccessibilityLabel($0) }))
                full(setupPicker, in: setupSelection)
                setupApply = button("使用这副耳机", "Use These Headphones") { [weak self] in self?.applySetupHeadphone() }
                let bluetooth = button("蓝牙设置…", "Bluetooth Settings…") { Self.openBluetoothSettings() }
                bluetooth.isBordered = false
                full(row([setupApply, NSView(), bluetooth]), in: setupSelection)
                setupChange = button("更换耳机", "Change Headphones") { [weak self] in
                    self?.setupEditingHeadphones = true; self?.refresh()
                }
                panel.addArrangedSubview(setupChange)
            }
        }
        setupDone = button("稍后设置", "Set Up Later") { [weak self] in self?.navigate("home") }
        page.addArrangedSubview(setupDone)
    }
    @objc private func setupPickerChanged(_ sender: NSPopUpButton) {
        setupApply.isEnabled = sender.selectedItem?.representedObject is String
    }
    private func applySetupHeadphone() {
        guard let address = setupPicker.selectedItem?.representedObject as? String,
              let peer = app.engine?.peer, peer.requestID == setupPickerPairingID,
              let target = app.observation?.target, target.revision == setupPickerRevision,
              app.audioObserver.devices().contains(where: { $0.address == address }) else { refresh(); return }
        setupEditingHeadphones = false
        app.selectHeadphone(address: address)
        refresh()
    }
    private func refreshSetup() {
        guard page == "onboarding" else { return }
        let target = app.observation?.target, peer = app.engine?.peer
        let reconnected = app.engine?.online == true && app.phoneRecoveryReady
        let synced = target?.ready == true, controlled = app.handoff.phoneReady
        setupReconnectTitle.stringValue = (reconnected ? "✓  " : "1  ") + L("蓝牙自动重连", "Bluetooth reconnection")
        setupReconnectDetail.stringValue = reconnected ? L("已允许，可自动发现此 Mac。", "Allowed. Your phone can automatically find this Mac.")
            : L("在手机 Sound Link 中允许自动重连，并选择：", "Allow reconnection in Sound Link on your phone, then select:") + "\n" + (app.discovery?.name ?? "Sound Link")
        setupHeadphoneTitle.stringValue = (synced ? "✓  " : "2  ") + L("选择并同步耳机", "Select and sync headphones")
        let macName = target?.macName ?? "", phoneName = target?.phoneName ?? ""
        let names = [("Mac", macName), ("Android", phoneName)].filter { !$0.1.isEmpty }.map { "\($0.0) · \($0.1)" }.joined(separator: "\n")
        let hint: String
        if target?.pendingSide == "mac" {
            hint = L("请在下方确认手机选择的对应耳机。", "Confirm the corresponding headphones below.")
        } else if target?.pendingSide == "phone" {
            hint = L("等待手机匹配或确认。", "Waiting for your phone to match or confirm.")
        } else if synced {
            hint = L("两端已同步", "Synced on both devices")
        } else {
            hint = L("可在任一端选择已配对的耳机，另一端会尝试匹配。", "Choose paired headphones on either device. The other will try to match.")
        }
        setupHeadphoneDetail.stringValue = names.isEmpty ? hint : names + "\n\n" + hint
        let needsPicker = macName.isEmpty || target?.pendingSide == "mac" || setupEditingHeadphones
        setupSelection.isHidden = !needsPicker; setupChange.isHidden = needsPicker
        if needsPicker {
            let devices = app.audioObserver.devices()
            let sameTarget = setupPickerRevision == target?.revision && setupPickerPairingID == peer?.requestID
            let selected = sameTarget ? setupPicker.selectedItem?.representedObject as? String : nil
            let preferred = selected ?? (target?.pendingSide == "mac" ? target?.phoneAddress : target?.macAddress)
            let titles = [L("请选择…", "Select…")] + devices.map { "\($0.name) · \($0.address.suffix(5))" }
            let oldAddresses = setupPicker.itemArray.compactMap { $0.representedObject as? String }
            if setupPicker.itemTitles != titles || oldAddresses != devices.map({ $0.address }) || !sameTarget {
                setupPicker.removeAllItems(); setupPicker.addItems(withTitles: titles)
                for (index, device) in devices.enumerated() { setupPicker.item(at: index + 1)?.representedObject = device.address }
                if let index = devices.firstIndex(where: { $0.address == preferred }) { setupPicker.selectItem(at: index + 1) }
            }
            setupPickerRevision = target?.revision ?? -1; setupPickerPairingID = peer?.requestID
            setupPicker.isEnabled = peer != nil && !devices.isEmpty
            setupApply.isEnabled = peer != nil && setupPicker.selectedItem?.representedObject is String
            setupApply.title = target?.pendingSide == "mac" ? L("确认耳机", "Confirm Headphones") : L("使用这副耳机", "Use These Headphones")
            if devices.isEmpty { setupHeadphoneDetail.stringValue += "\n" + L("未找到耳机，请先在 Mac 蓝牙设置中配对。", "No headphones found. Pair them in Mac Bluetooth settings first.") }
        }
        setupControlTitle.stringValue = (controlled ? "✓  " : "3  ") + L("允许手机控制耳机", "Allow phone headphone control")
        setupControlDetail.stringValue = controlled ? L("已允许，可自动切换。", "Allowed. Ready for automatic switching.")
            : synced ? L("在手机 Sound Link 弹窗中允许耳机控制。", "Allow headphone control in the Sound Link dialog on your phone.")
            : L("同步耳机后，在手机上完成授权。", "After syncing headphones, complete authorization on your phone.")
        if currentSetupStep == .complete && reconnected && !setupReturnScheduled {
            setupReturnScheduled = true
            let pairingID = peer?.requestID, revision = target?.revision
            // Navigate after the current refresh/layout, then recheck the live pairing.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.setupReturnScheduled = false
                guard self.page == "onboarding", self.app.engine?.peer?.requestID == pairingID,
                      self.app.observation?.target.revision == revision,
                      self.app.engine?.online == true, self.currentSetupStep == .complete else { return }
                self.navigate("home")
            }
        }
    }
    func buildDiagnostics() {
        let page = makePage("diagnostics"), panel = card(page)
        panel.addArrangedSubview(label("诊断日志", "Diagnostic logs", size: 17))
        logState.font = .systemFont(ofSize: 12); logState.textColor = .secondaryLabelColor; full(logState, in: panel)
        full(label("日志保存在本机，自动轮转。遇到问题时，可导出分享。", "Logs stay on this Mac and rotate automatically. Export them to share when something goes wrong.", secondary: true), in: panel)
        full(button("导出日志…", "Export Logs…") { [weak self] in self?.app.exportLogs() }, in: panel)
        full(label("包含 Mac 记录及手机摘要。手机完整日志需在手机导出。日志可能含设备、网络与播放应用信息，不包含录音或配对密钥。", "Includes Mac events and phone summaries. Export full phone logs on Android. Logs may contain device, network and playback app information, but no audio recordings or pairing keys.", size: 12, secondary: true), in: panel)
        let support = card(page)
        full(button("切换后原设备仍在播放…", "Previous Device Keeps Playing…") { [weak self] in
            let alert = NSAlert(); alert.messageText = L("暂停上一台设备的播放", "Pause previous playback")
            alert.informativeText = L("耳机切换到手机后，可尝试暂停此 Mac 的 Music 或 Spotify。仅在你需要此功能时授权；不影响自动切换。请先打开对应播放器。", "After headphones switch to your phone, Sound Link can pause Music or Spotify on this Mac. This optional permission is separate from headphone switching. Open the player before continuing.")
            alert.addButton(withTitle: L("继续", "Continue")); alert.addButton(withTitle: L("取消", "Cancel"))
            if alert.runModal() == .alertFirstButtonReturn { self?.app.hardware.authorizePause() }
        }, in: support)
        full(link("恢复扬声器声音", "Restore Speaker Audio", symbol: "speaker.wave.2") { [weak self] in self?.app.hardware.speakerGuard.release() }, in: support)
        full(link("高级诊断…", "Advanced Diagnostics…", symbol: "waveform.path.ecg") { [weak self] in self?.navigate("advanced") }, in: support)
        let advanced = makePage("advanced"), evidence = card(advanced)
        full(label("原始诊断记录保留来源语言。", "Raw diagnostic evidence retains its source language.", size: 12, secondary: true), in: evidence)
        app.interfaces = NSPopUpButton(); app.interfaces.target = app; app.interfaces.action = #selector(Application.selectNetwork); full(app.interfaces, in: evidence)
        app.handoffButton = button("启用自动切换", "Enable Automatic Switching") { [weak self] in self?.app.toggleAutomatic() }
        full(app.handoffButton, in: evidence)
        for label in [app.status, app.details, app.diagnosticLabel, app.discoveryLabel, app.handoffLabel, app.targetLabel, app.macAudioLabel, app.phoneAudioLabel, app.decisionLabel, app.reasonLabel, app.historyLabel] {
            label.font = .monospacedSystemFont(ofSize: 11, weight: .regular); label.isSelectable = true; full(label, in: evidence)
        }
        full(button("打开日志文件夹", "Open Logs Folder") { [weak self] in if let url = self?.app.diagnostics?.directory { NSWorkspace.shared.open(url) } }, in: evidence)
    }
    private func applyAppearance() {
        switch UserDefaults.standard.string(forKey: "interfaceAppearance") {
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        default: NSApp.appearance = nil
        }
    }
    @objc private func changeAppearance(_ sender: NSPopUpButton) {
        UserDefaults.standard.set(["system", "light", "dark"][sender.indexOfSelectedItem], forKey: "interfaceAppearance")
        applyAppearance()
    }
    @objc func changeLanguage(_ sender: NSPopUpButton) {
        UserDefaults.standard.set(["system", "zh", "en"][sender.indexOfSelectedItem], forKey: "interfaceLanguage")
        for (zh, en, set) in bindings { set(L(zh, en)) }
        app.manualMessage = ""; app.buildMenu(); app.lastQR = ""; app.update(); navigate(page)
        automatic.setAccessibilityLabel(L("自动切换", "Automatic switching"))
    }
    func navigate(_ destination: String) {
        if destination == "onboarding", app.engine?.peer == nil { navigate("pairing"); return }
        if destination == "download", page != "download" { downloadReturnPage = page }
        if destination == "onboarding", page != destination { setupEditingHeadphones = false; setupPickerRevision = -1 }
        page = destination
        for (key, view) in pages { view.isHidden = key != page }
        updateToolbar()
        window.title = ["home": "Sound Link", "onboarding": L("连接设置", "Connection Setup"), "settings": L("设置", "Settings"), "headphones": L("耳机", "Headphones"), "device": L("手机", "Phone"), "pairing": L("配对手机", "Pair Phone"), "download": L("安装手机 App", "Install Phone App"), "diagnostics": L("帮助与诊断", "Help & Diagnostics"), "advanced": L("高级诊断", "Advanced Diagnostics")][page] ?? "Sound Link"
        refresh(); scroll.contentView.scroll(to: .zero)
        window.recalculateKeyViewLoop()
        // Mouse navigation should not assign keyboard focus to a navigation button.
        // Keep the system focus indication available for keyboard navigation.
        if let event = NSApp.currentEvent, [.leftMouseDown, .leftMouseUp].contains(event.type) {
            window.makeFirstResponder(nil)
        }
    }
    func refresh() {
        let o = app.observation, paired = app.engine?.peer != nil
        let target = o?.target
        let hasSelection = target?.macAddress.isEmpty == false
        selectedHeadphoneName.stringValue = hasSelection ? (target?.macName ?? "") : L("尚未选择", "Not selected")
        changeHeadphoneButton.title = hasSelection ? L("更换耳机…", "Change Headphones…") : L("选择耳机…", "Choose Headphones…")
        clearHeadphoneButton.isHidden = !hasSelection
        confirmHeadphoneButton.isHidden = target?.pendingSide != "mac"
        selectedMacName.stringValue = target?.macName.isEmpty == false ? target!.macName : L("尚未选择", "Not selected")
        selectedPhoneName.stringValue = target?.phoneName.isEmpty == false ? target!.phoneName : L("尚未选择", "Not selected")
        headphoneStatus.stringValue = target?.pendingSide == "mac" ? L("请确认手机选择的耳机。", "Confirm the headphones selected on your phone.")
            : target?.pendingSide == "phone" ? L("等待手机匹配或确认。", "Waiting for phone matching or confirmation.")
            : target?.ready == true ? L("两端已同步", "Synced on both devices")
            : L("在任一端选择后，另一端会自动匹配。", "Choose on either device. The other device will try to match.")
        headphoneStatus.textColor = target?.ready == true ? .systemBlue : .secondaryLabelColor
        controlStatus.stringValue = app.handoff.phoneReady ? L("已允许，可自动切换", "Allowed · Ready for automatic switching")
            : L("请在手机 Sound Link 中允许耳机控制。", "Allow headphone control in Sound Link on your phone.")
        attention.isHidden = app.failure.isEmpty; attention.stringValue = L("Sound Link 需要处理，请在设置中查看帮助与诊断。", "Sound Link needs attention. Open Help & Diagnostics in Settings.")
        empty.isHidden = paired; hero.isHidden = !paired; peerCard.isHidden = !paired
        homePairingQR.image = app.pairingQR.image
        homePairingCaption.stringValue = app.pairingCaption.stringValue
        name.stringValue = o?.target.macName.isEmpty == false ? o!.target.macName : o?.target.phoneName.isEmpty == false ? o!.target.phoneName : L("尚未选择耳机", "No headphones selected")
        let fresh = app.engine?.online == true && (o?.phoneAge ?? .infinity) < 10 && o?.phone.targetRevision == o?.target.revision
        let macConnected = o?.mac.connection == "connected" && o?.mac.targetRevision == o?.target.revision, phoneConnected = fresh && o?.phone.connection == "connected"
        let both = macConnected && phoneConnected
        macState.stringValue = macConnected ? L("已连接", "Connected") : o?.mac.connection == "disconnected" ? L("未连接", "Not connected") : L("状态未知", "Unknown")
        phoneState.stringValue = !fresh ? L("状态未知", "Unknown") : phoneConnected ? L("已连接", "Connected") : o?.phone.connection == "disconnected" ? L("未连接", "Not connected") : L("状态未知", "Unknown")
        macIcon.contentTintColor = macConnected ? .systemBlue : .secondaryLabelColor; phoneIcon.contentTintColor = phoneConnected ? .systemBlue : .secondaryLabelColor
        macState.textColor = macConnected ? .systemBlue : .secondaryLabelColor; phoneState.textColor = phoneConnected ? .systemBlue : .secondaryLabelColor
        state.stringValue = both ? L("两端均已连接", "Connected to both devices") : macConnected ? L("耳机已连接 Mac", "Headphones connected to Mac") : phoneConnected ? L("耳机已连接手机", "Headphones connected to phone") : !fresh ? L("手机状态未知", "Phone status unknown") : L("等待耳机连接", "Waiting for headphones")
        if !app.handoff.enabled { state.stringValue = L("自动切换已暂停", "Automatic switching paused") }
        if app.paused { state.stringValue = L("设备连接已暂停", "Device connection paused") }
        if app.handoff.transaction != nil { state.stringValue = app.handoff.transaction?.destination == "mac" ? L("正在切换到 Mac…", "Switching to Mac…") : L("正在切换到手机…", "Switching to phone…") }
        if app.handoff.enabled && app.handoff.transaction == nil && app.handoff.lastOutcome == "failed" { state.stringValue = L("切换未成功，请查看诊断", "Couldn’t switch. Check Diagnostics.") }
        if !app.manualMessage.isEmpty { state.stringValue = app.manualMessage }
        if !app.failure.isEmpty { state.stringValue = L("连接服务需要处理，请查看诊断", "Connection needs attention. Check diagnostics.") }
        state.textColor = app.paused || !app.handoff.enabled ? .secondaryLabelColor : .systemBlue
        automatic.state = app.handoff.enabled ? .on : .off
        automatic.isEnabled = paired
        let missingTarget = o?.target.ready != true
        guidance.stringValue = o?.target.pendingSide == "mac" ? L("请确认手机选择的耳机", "Confirm the headphones selected on your phone") : o?.target.macAddress.isEmpty != false ? L("先选择系统已配对的耳机", "Choose headphones paired in Bluetooth settings") : missingTarget ? L("等待另一端匹配或确认耳机", "Waiting for the other device to match or confirm") : !app.handoff.phoneReady ? L("请在手机上允许耳机控制", "Allow headphone control on your phone") : ""
        guidance.isHidden = guidance.stringValue.isEmpty; setup.isHidden = !missingTarget && app.handoff.phoneReady && app.phoneRecoveryReady
        peerName.stringValue = app.engine?.peer?.name ?? L("未配对手机", "No paired phone")
        peerState.stringValue = app.paused ? L("已暂停", "Paused") : app.engine?.online == true ? L("已连接", "Connected") : L("暂未连接", "Not connected")
        logState.stringValue = app.diagnostics == nil ? L("日志未就绪", "Logs unavailable") : app.diagnostics?.error.isEmpty == false ? L("日志写入失败，请检查磁盘空间", "Can’t write logs. Check disk space.") : L("正在本机记录 · 最多约 4 MB", "Recording locally · up to about 4 MB")
        refreshSetup()
        app.automaticMenu?.state = app.handoff.enabled ? .on : .off
        app.automaticMenu?.isEnabled = automatic.isEnabled
        app.connectionMenu?.title = app.paused ? L("恢复设备连接", "Resume Device Connection") : L("暂停设备连接", "Pause Device Connection")
        app.manualMenu?.isEnabled = o?.target.macAddress.isEmpty == false && app.manualOperation == nil && app.handoff.transaction == nil && !(fresh && app.handoff.phoneLocalActive)
        app.manualMenu?.title = app.manualOperation == nil ? L("耳机切换到此 Mac", "Switch Headphones to This Mac") : L("正在切换到 Mac…", "Switching to Mac…")
        stack.layoutSubtreeIfNeeded()
        document.setFrameSize(NSSize(width: 560, height: max(scroll.contentSize.height, stack.fittingSize.height + 48)))
    }
}
