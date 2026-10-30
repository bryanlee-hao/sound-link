import Foundation

/// Product copy follows the system unless the user explicitly chooses a language.
/// Diagnostic evidence and protocol fields deliberately retain their original values.
enum AppLanguage {
    static var preference: String { UserDefaults.standard.string(forKey: "interfaceLanguage") ?? "system" }
    static var chinese: Bool {
        let value = preference == "system" ? (Locale.preferredLanguages.first(where: { $0.hasPrefix("zh") || $0.hasPrefix("en") }) ?? "en") : preference
        return value.hasPrefix("zh")
    }
}
func L(_ chinese: String, _ english: String) -> String { AppLanguage.chinese ? chinese : english }
