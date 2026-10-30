import Cocoa
import Sparkle

/// Sparkle owns download verification, installation, authorization and relaunch.
/// Manual checks are shared by the menu bar, app menu and Settings button.
extension Application {
    @objc func checkForUpdates() {
        NSApp.activate(ignoringOtherApps: true)
        updaterController?.checkForUpdates(nil)
    }
}
