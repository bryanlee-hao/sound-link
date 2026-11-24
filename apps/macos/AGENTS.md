# AGENTS.md — Mac app

See the root [AGENTS.md](../../AGENTS.md) for repo-wide rules. Module overview: [README.md](README.md).

## Stack

- Swift 5.9, AppKit, macOS 14+. SwiftPM (`Package.swift`) for tests; `SoundLink.xcodeproj` (scheme **Sound Link**) for the real app bundle.
- Single external dependency: Sparkle `2.10.1` (exact). Only starts when an HTTPS update feed **and** EdDSA public key are configured.
- Platform APIs: CoreBluetooth, IOBluetooth, CoreAudio, Network.framework, Keychain.

## Layout

| Path | Responsibility |
|---|---|
| `Sources/SoundLinkCore/` | Pure logic: identity, pairing, discovery protocol, TLS HTTP server, handoff engine, observation, output edges, setup steps. **No UI.** |
| `Sources/SoundLink/` | App target: UI (`ProductWindow`, `Language`), Bluetooth/discovery, media observation, handoff execution, speaker guard, diagnostics, updater. Files prefixed `Mac*` are platform adapters. |
| `Tests/SoundLinkCoreTests/` | XCTest for `SoundLinkCore` only (state transitions, protocol, security boundaries). |
| `version.json` | Dev version metadata consumed by `scripts/package.py`. |

## Commands (from repo root)

```bash
swift test --package-path apps/macos          # core tests — run after any SoundLinkCore change
./scripts/build-macos.sh                      # full Debug .app → build/macos/Build/Products/Debug/Sound Link.app
```

- `swift run` lacks entitlements, permission strings and resources — use the Xcode-built `.app` for runtime checks.
- The build is ad-hoc signed (`CODE_SIGN_IDENTITY=-`, empty `DEVELOPMENT_TEAM`). Don't add a personal Team or Developer ID to the project.
- First SwiftPM resolve needs network (Sparkle).

## Rules

- Put decision logic in `SoundLinkCore` and test it there; `Sources/SoundLink` adapters should only observe and execute already-authorized single actions.
- Use injectable clocks / monotonic time in core logic. Don't compute latency by subtracting wall clocks across devices.
- Bluetooth ACL connected ≠ audio profile ready ≠ default output switched. Confirm with CoreAudio's actual route before reporting success.
- Input protection (`AudioInputProtection`): only an aggregate device with an explicit AudioTap and no active input sub-devices is treated as system capture. Never whitelist by process name (e.g. `corespeechd`). Incomplete info → keep protecting.
- Manual "switch headphones to this Mac" bypasses input protection but still checks target revision, cancellation and concurrent transactions; it never sends a remote pause.
- No private frameworks (MediaRemote etc.), no helper scripts with absolute repo paths, no runtime dependency on Python/ADB/the source tree.
- Dev state lives in `~/Library/Application Support/Sound Link Dev/` with a dev-specific Keychain namespace — don't share storage with release builds.
- `SoundLink.xcodeproj` consumes the local Swift package and synchronized groups, so new files under `Sources/` are picked up without editing `project.pbxproj`.
- Protocol fixture tests read `../../packages/protocol/fixtures/*.json` via `#filePath`; keep that relative path working.
