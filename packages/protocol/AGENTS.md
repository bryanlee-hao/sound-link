# AGENTS.md — Protocol

See the root [AGENTS.md](../../AGENTS.md) for repo-wide rules. Overview: [README.md](README.md).

## What lives here

Language-neutral specs and JSON fixtures only. No SDK, no shared runtime — Swift and Kotlin/Java implementations stay in their own apps.

| File | Status |
|---|---|
| `pairing-v1.md`, `discovery-v1.md`, `observation-v1.md`, `handoff-v1.md` | **Implemented** wire behavior (current 0.4.x). |
| `v1.md` | Full product **draft**. Its lease/exit/release messages, `/v1/events` stream and 15 s budget are not implemented — don't code against them as if they were. |
| `fixtures/*.json` | Cross-language test vectors, loaded by `apps/macos/Tests` (via relative `#filePath`) and Android unit tests (Gradle test resources). |

## Rules

- A spec change is a protocol change: update the Mac implementation, the Android implementation, and the fixtures in the same change, and run both test suites:
  ```bash
  swift test --package-path apps/macos
  apps/android/gradlew -p apps/android testDebugUnitTest
  ```
- Prefer adding a fixture (positive and negative cases) over prose when pinning down encoding, limits or edge cases.
- Fields/states are not frozen until reviewed on both ends with conformance tests. Unknown messages or capabilities must never default to execution; incompatible major versions allow only update/diagnostics.
- Fixture and example values must be placeholders — never real keys, tokens, QR secrets, IPs or Bluetooth addresses.
- Don't rename or move fixture files without updating both test loaders.
- Specs are written in Chinese; keep field names, enum values and code identifiers in English and in backticks.
