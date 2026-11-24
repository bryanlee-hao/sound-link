# AGENTS.md

Guidance for coding agents working in this repository. Module-specific rules live in the nearest `AGENTS.md`:

- [apps/macos/AGENTS.md](apps/macos/AGENTS.md) — Mac app (Swift, AppKit, SwiftPM + Xcode)
- [apps/android/AGENTS.md](apps/android/AGENTS.md) — Android app (Java/Kotlin, Gradle)
- [packages/protocol/AGENTS.md](packages/protocol/AGENTS.md) — wire protocol specs and fixtures

## What this project is

Sound Link lets one pair of Bluetooth headphones follow playback between a Mac and an Android phone. The two apps pair via QR code, discover each other over BLE/GATT, and coordinate over an authenticated local-network TLS channel (periodic HTTPS POST heartbeats). Audio never goes through Sound Link; each device plays over its own system Bluetooth.

Read before non-trivial changes:

- `docs/architecture.md` — runtime shape, module ownership, platform capability limits
- `docs/coordination.md` — handoff rules and invariants (source of truth for behavior)
- `docs/discovery-and-pairing.md`, `docs/validation.md`, `packages/protocol/*-v1.md`

## Layout

```text
apps/macos/         Mac app + SoundLinkCore (pure logic, tested by swift test)
apps/android/       Android app (single Gradle module :app)
packages/protocol/  Language-neutral protocol specs + JSON fixtures (shared by both test suites)
scripts/            Build, icon/resource generation, Info.plist packaging
tests/              Python tests for build metadata and config isolation
docs/               Architecture, coordination, UI and validation notes (mostly Chinese)
assets/brand/       Icon source (icon.json) — generate icons via scripts/icons.swift
```

## Commands

Run everything from the repository root. Requires full Xcode, JDK 21, Android SDK platform 37 + build-tools 36.0.0, Python 3.

```bash
./scripts/build.sh                                                 # full: Android test+lint+APK, swift test, Mac app
./scripts/build-macos.sh                                           # Mac app only
swift test --package-path apps/macos                               # Swift core tests
apps/android/gradlew -p apps/android testDebugUnitTest lintDebug   # Android unit tests + lint
python3 -m unittest discover -s tests -v                           # build metadata / config isolation
```

Run the test command for every module you touch before reporting work as done. Passing tests does not validate real-device Bluetooth, audio routing, lock screen or battery behavior — say so explicitly instead of claiming it works.

## Cross-cutting rules

- **Behavior lives in docs first.** Handoff/coordination semantics are defined in `docs/coordination.md` and `packages/protocol/handoff-v1.md`. If code and docs disagree, flag it; don't silently change one side.
- **Both ends move together.** Any change to a message field, state, or timing budget (e.g. the 5 s handoff transaction, `ttlMs <= 5000`, 900 s / 840 s token lifetimes) must update the Mac side, the Android side, the protocol spec, and fixtures in the same change.
- **Pure state machines, injected platform.** Decision logic (Mac `HandoffEngine`, Android `OfflinePhonePolicy` etc.) must not call network, Bluetooth or UI directly. Write pure transitions with an injectable clock and test them; keep platform adapters thin.
- **Unknown is not disconnected.** Never treat unreadable/unknown state as a green light for an automatic action. A connect request returning success is not a successful handoff — route must be verified.
- **Source pause is best effort.** Never use play/pause toggles; send at most one explicit `pause`, and never let pause failure trigger a reverse connect or retry loop.
- **No private APIs or debug backdoors.** No ADB, Root, Shizuku, MediaRemote private frameworks, or accessibility/notification-content reading as a fallback.
- **Dev and release identities stay isolated.** Defaults are `app.soundlink.mac.dev` / `app.soundlink.android.dev` with online update disabled. `tests/test_build_metadata.py` enforces this — don't weaken it.

## Security and privacy

- Never commit keys, keystores, certificates, provisioning profiles, `.env`, logs or diagnostics. Local config and diagnostics go in the ignored `.local/`.
- Never log QR secrets, auth tokens, private keys, or audio content. Diagnostics must redact LAN addresses and device identifiers on export.
- No user IPs, headphone MAC addresses, test certificates or long-lived shared tokens in source or build outputs.
- Optional vendor IDs (e.g. Xiaomi App ID) come only from env vars / Gradle properties.

## Conventions

- Code, comments and identifiers in English. Docs under `docs/` and module READMEs are Chinese; root README has English + `README.zh-CN.md` — keep both in sync when editing user-facing README content.
- Don't add dependencies (SwiftPM, Gradle, Python) without asking. Sparkle and ZXing are pinned; keep them that way.
- Keep changes scoped to the task; don't refactor unrelated code.
- Don't commit or push unless explicitly asked.
