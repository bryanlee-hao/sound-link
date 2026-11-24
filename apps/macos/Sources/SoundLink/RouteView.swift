import AppKit
import QuartzCore
import SoundLinkCore

/// Home bubble route: this Mac on the left, the phone on the right, and the headphones
/// bubble resting beside whichever device currently holds them.
final class RouteView: NSView {
    struct Side: Equatable {
        var name: String
        var status: String
        var highlighted: Bool
        var dimmed = false
    }

    static let height: CGFloat = 150
    private static let deviceDiameter: CGFloat = 76
    private static let headphoneDiameter: CGFloat = 60
    private static let edgeInset: CGFloat = 22
    private static let bubbleGap: CGFloat = 6
    private static let moveDuration: TimeInterval = 0.45
    private static let pulseKey = "SoundLink.Pulse"

    private let width: CGFloat
    private let local = Bubble(symbol: "laptopcomputer", diameter: deviceDiameter, pointSize: 30)
    private let peer = Bubble(symbol: "iphone", diameter: deviceDiameter, pointSize: 30)
    private let headphones = Bubble(symbol: "headphones", diameter: headphoneDiameter, pointSize: 26)
    private let localName = RouteView.caption(size: 13), localStatus = RouteView.caption(size: 12)
    private let peerName = RouteView.caption(size: 13), peerStatus = RouteView.caption(size: 12)
    private var route: HomeRoute?
    private var localSide = Side(name: "", status: "", highlighted: false)
    private var peerSide = Side(name: "", status: "", highlighted: false)

    init(width: CGFloat) {
        self.width = width
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: Self.height))
        for view in [local, peer, headphones, localName, localStatus, peerName, peerStatus] as [NSView] { addSubview(view) }
        let deviceY: CGFloat = 8, centerY = deviceY + Self.deviceDiameter / 2
        local.frame.origin = NSPoint(x: Self.edgeInset, y: deviceY)
        peer.frame.origin = NSPoint(x: width - Self.edgeInset - Self.deviceDiameter, y: deviceY)
        headphones.frame.origin = NSPoint(x: headphoneX(for: .idle), y: centerY - Self.headphoneDiameter / 2)
        for (field, bubble, y) in [(localName, local, 92.0), (localStatus, local, 112.0), (peerName, peer, 92.0), (peerStatus, peer, 112.0)] {
            field.frame = NSRect(x: bubble.frame.midX - 70, y: y, width: 140, height: 18)
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
    }
    required init?(coder: NSCoder) { nil }

    override var isFlipped: Bool { true }
    override var intrinsicContentSize: NSSize { NSSize(width: width, height: Self.height) }

    func update(route newRoute: HomeRoute, local newLocal: Side, peer newPeer: Side, summary: String) {
        localSide = newLocal; peerSide = newPeer
        localName.stringValue = newLocal.name; localStatus.stringValue = newLocal.status
        peerName.stringValue = newPeer.name; peerStatus.stringValue = newPeer.status
        setAccessibilityLabel([summary, "\(newLocal.name) \(newLocal.status)", "\(newPeer.name) \(newPeer.status)"].joined(separator: ", "))
        if newRoute != route { move(to: newRoute, animated: route != nil && window?.isVisible == true) }
        route = newRoute
        applyColors()
    }

    private func headphoneX(for route: HomeRoute) -> CGFloat {
        let besideLocal = Self.edgeInset + Self.deviceDiameter + Self.bubbleGap
        switch route {
        case .local: return besideLocal
        case .peer: return width - besideLocal - Self.headphoneDiameter
        case .both, .switching, .idle: return (width - Self.headphoneDiameter) / 2
        }
    }

    private func move(to newRoute: HomeRoute, animated: Bool) {
        let origin = NSPoint(x: headphoneX(for: newRoute), y: headphones.frame.minY)
        if animated {
            NSAnimationContext.runAnimationGroup { context in
                context.duration = Self.moveDuration
                context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
                headphones.animator().setFrameOrigin(origin)
            }
        } else {
            headphones.setFrameOrigin(origin)
        }
        headphones.layer?.removeAnimation(forKey: Self.pulseKey)
        if newRoute == .switching {
            let pulse = CABasicAnimation(keyPath: "opacity")
            pulse.fromValue = 1; pulse.toValue = 0.45; pulse.duration = 0.7
            pulse.autoreverses = true; pulse.repeatCount = .infinity
            headphones.layer?.add(pulse, forKey: Self.pulseKey)
        }
    }

    private func applyColors() {
        let active = route != .idle
        effectiveAppearance.performAsCurrentDrawingAppearance {
            local.paint(highlighted: localSide.highlighted)
            peer.paint(highlighted: peerSide.highlighted)
            headphones.paint(filled: active)
        }
        peer.alphaValue = peerSide.dimmed ? 0.45 : 1
        localStatus.textColor = localSide.highlighted ? .systemBlue : .secondaryLabelColor
        peerStatus.textColor = peerSide.highlighted ? .systemBlue : .secondaryLabelColor
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance(); applyColors()
    }

    private static func caption(size: CGFloat) -> NSTextField {
        let field = NSTextField(labelWithString: "")
        field.font = .systemFont(ofSize: size); field.alignment = .center
        field.lineBreakMode = .byTruncatingTail
        return field
    }
}

/// A circular, layer-backed bubble holding one SF Symbol.
private final class Bubble: NSView {
    private let image = NSImageView()

    init(symbol: String, diameter: CGFloat, pointSize: CGFloat) {
        super.init(frame: NSRect(x: 0, y: 0, width: diameter, height: diameter))
        wantsLayer = true
        layer?.cornerRadius = diameter / 2
        image.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?
            .withSymbolConfiguration(.init(pointSize: pointSize, weight: .regular))
        image.frame = bounds
        image.imageAlignment = .alignCenter
        image.autoresizingMask = [.width, .height]
        addSubview(image)
    }
    required init?(coder: NSCoder) { nil }

    /// Device bubble: tinted when the headphones are on this device, neutral otherwise.
    func paint(highlighted: Bool) {
        layer?.backgroundColor = (highlighted ? NSColor.systemBlue.withAlphaComponent(0.16) : NSColor.quaternaryLabelColor.withAlphaComponent(0.12)).cgColor
        layer?.borderWidth = 0
        image.contentTintColor = highlighted ? .systemBlue : .secondaryLabelColor
    }

    /// Headphones bubble: solid accent while connected anywhere, outlined grey when idle.
    func paint(filled: Bool) {
        layer?.backgroundColor = (filled ? NSColor.systemBlue : NSColor.quaternaryLabelColor.withAlphaComponent(0.12)).cgColor
        layer?.borderColor = NSColor.tertiaryLabelColor.cgColor
        layer?.borderWidth = filled ? 0 : 1.5
        image.contentTintColor = filled ? .white : .tertiaryLabelColor
    }
}
