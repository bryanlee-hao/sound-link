# AGENTS.md — Android app

See the root [AGENTS.md](../../AGENTS.md) for repo-wide rules. Module overview: [README.md](README.md).

## Stack

- `minSdk = targetSdk = compileSdk = 37` (Android 17). JDK 21 for Gradle; Java/Kotlin source and target 17.
- Mostly Java; Kotlin + Jetpack Compose for UI (`MainActivity.kt`, `ScanActivity.kt`, `SetupStep.kt`, `PreviewGeometry.kt`).
- QR scanning: Camera2 + ZXing `core 3.5.4`. Use the Gradle Wrapper (`apps/android/gradlew`); don't install or upgrade Gradle/AGP casually.

## Layout

All code is in package `app.soundlink.android` under `app/src/main/java/app/soundlink/android/`.

| Area | Key files |
|---|---|
| Connection / transport | `ConnectionService` (connectedDevice foreground service), `Link`, `PairingLifecycle`, `MacCompanionService` |
| Discovery | `BleDiscovery`, `DiscoveryProtocol`, `DiscoveryReceiver` |
| Observation / handoff | `PhoneObservation`, `PlaybackEdges`, `HandoffCommand`, `HeadphoneControl`, `HeadphoneMatch`, `HeadphoneSelection`, `PhonePause` |
| Offline policy / recovery | `OfflinePhonePolicy`, `OfflineReconnectFlow`, `NetworkRetry`, `Recovery*`, `MuteLease`, `PhoneMuteGuard`, `MuteRecoveryReceiver` |
| Diagnostics | `Diagnostic*`, `DiagnosticsProvider` |
| Resources | `res/values/strings.xml` (EN) + `res/values-zh/strings.xml` (ZH) |

Tests: `app/src/test` = local JVM unit tests (JUnit 4 + `org.json`); `app/src/androidTest` = instrumented/live-device tests. Protocol fixtures from `packages/protocol/fixtures/` are on the unit-test resource path.

## Commands (from repo root)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apps/android/gradlew -p apps/android testDebugUnitTest lintDebug   # run after any change
apps/android/gradlew -p apps/android assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk
```

- Do **not** run `connectedAndroidTest` or other instrumented tests without asking: they drive UI, notifications and app state, and may reinstall the app on a real device.

## Rules

- Keep decisions (e.g. `OfflinePhonePolicy`, `HandoffCommand` validation, `PlaybackEdges`) pure and unit-testable; Bluetooth/audio/notification APIs belong in thin adapters.
- The foreground service owns observation and communication; background logic must not depend on Activity fields.
- `BluetoothDevice.connect()` returning success is not a handoff — keep observing the profile result and verify the route.
- Handoff commands: accept only `0 < ttlMs <= 5000`, subtract RTT/queue time, re-check local auth/target/instance/event/audio protection, and **persist the consumed `id:action` before touching hardware**.
- Only media/game playback usages generate start events. Unknown categories never trigger an automatic takeover. Pause is a single explicit `pause` on a precisely identified session; never play/pause toggle.
- Don't request extra permissions (microphone, accessibility, notification content, root/debug). Each permission maps to a specific feature — see `docs/architecture.md`.
- Persistent state uses private SharedPreferences + Keystore. Failure locks are persisted before connecting.
- Build identity/channel config comes only from env vars (`SOUNDLINK_APPLICATION_ID`, `SOUNDLINK_APP_NAME`, `SOUNDLINK_WEBSITE_URL`, `SOUNDLINK_UPDATE_BASE_URL`, `SOUNDLINK_XIAOMI_APP_ID`) or Gradle properties. URLs must be HTTPS. Never hardcode vendor credentials or read a release keystore.
- Every user-facing string needs both `values/strings.xml` and `values-zh/strings.xml` entries.
