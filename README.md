# Sound Link

**English** | [简体中文](README.zh-CN.md)

**Let your Bluetooth headphones follow playback between Mac and Android.**

Sound Link helps you share one pair of Bluetooth headphones between your computer and phone. When playback starts on the other device, the apps coordinate the connection based on headphone state and system permissions, reducing trips to Bluetooth settings.

Pair your devices with a QR code. Coordination happens over your local network; audio continues to play directly through each device's Bluetooth connection.

**[Website & Downloads](https://gkagent.cn/sound-link/) · [Report an Issue](https://github.com/bryanlee-hao/sound-link/issues) · [MIT License](LICENSE)**

> Sound Link is under active development and real-device testing. Testing has primarily focused on AirPods, Mac, and Android phones. See the known limitations below.

## Features

- **Automatic switching in both directions**: Switch the headphone connection in response to new playback events.
- **QR code pairing**: Scan the Mac's pairing code with the Android app to establish persistent device trust.
- **Bluetooth-assisted reconnection**: Discover a paired Mac over Bluetooth to help restore the local network connection.
- **Playback handoff**: After a successful switch, attempt to pause playback on the previous device when permissions and player capabilities allow it.
- **Manual controls**: Pause automatic switching, or use the Mac menu bar to switch headphones to your computer.
- **Status and diagnostics**: Inspect connection state and export diagnostic logs for troubleshooting.
- **English and Chinese interfaces**: Choose English or Simplified Chinese, with light, dark, and system appearance options.

Normal use does not require Root, ADB, or Shizuku. Audio is not sent through the cloud.

## Requirements

| Component | Requirement |
|---|---|
| Mac | macOS 14 or later |
| Android | Android 17 or later, API 37 |
| Headphones | Paired with both devices in their system Bluetooth settings |
| Connectivity | Bluetooth enabled and devices able to reach each other over the local network |

AirPods are the primary test target. Other Bluetooth headphones need model-specific validation. Windows and iPhone are not currently supported.

Using the same Wi-Fi network is usually convenient, but guest networks, client isolation, or firewalls can still prevent communication.

## Getting started

### 1. Install both apps

Visit the **[Sound Link website](https://gkagent.cn/sound-link/)** for an introduction and currently available Mac and Android downloads.

- **Mac**: Open the downloaded DMG and drag Sound Link into Applications.
- **Android**: Install the APK and allow installation from that source when prompted.

You can also build the apps yourself using the instructions below. The website's download section describes the Mac package's notarization status and first-launch instructions.

### 2. Pair your computer and phone

1. Open Sound Link on both devices.
2. In the Android app, select **Scan pairing code** and scan the code shown on your Mac.
3. Follow the setup prompts for the relevant Bluetooth, local network, camera, notification, and companion-device permissions.

The pairing code establishes device trust. Do not share it publicly.

### 3. Select your headphones

Pair the same headphones with both devices in their system Bluetooth settings. Then select and confirm the target headphones in Sound Link and complete the headphone-control authorization on Android.

The initial headphone connection must be established by the system or the user. If neither device is connected to the headphones, playback alone will not trigger a connection.

### 4. Start playback

Check that both apps report a working connection and that automatic switching is enabled, then start playback on your computer or phone.

Closing the Mac window leaves Sound Link running in the menu bar. You can pause automatic switching at any time.

## Building from source

### Prerequisites

To build the complete project on a Mac, install:

- Full Xcode, selected as the active developer tools.
- JDK 21.
- Android SDK Platform 37 and Build Tools 36.0.0, as required by the current project.
- Python 3.
- An internet connection for the initial Gradle, SwiftPM, and other dependency downloads.

The repository includes the Gradle Wrapper, so a separate Gradle installation is unnecessary. Set `JAVA_HOME` and `ANDROID_HOME` if your tools are installed outside the default locations.

### Clone and build

```bash
git clone https://github.com/bryanlee-hao/sound-link.git
cd sound-link

./scripts/build.sh
```

The script runs Android unit tests and lint, builds the development APK, runs Swift tests, and builds the complete Mac app.

Build outputs:

```text
Mac
build/macos/Build/Products/Debug/Sound Link.app

Android
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Install both development builds, then pair them using the steps above.

Development builds have separate application identifiers and storage. They can coexist with the website downloads, but do not automatically inherit their pairing or settings. Online downloads and updates are unconfigured by default.

### Build one platform

Mac only:

```bash
./scripts/build-macos.sh
```

Alternatively, open `apps/macos/SoundLink.xcodeproj` in Xcode and select the **Sound Link** scheme and **My Mac** destination.

Android only:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

./apps/android/gradlew -p apps/android assembleDebug
```

### Run tests

```bash
# Mac core logic
swift test --package-path apps/macos

# Android unit tests and static checks
./apps/android/gradlew -p apps/android testDebugUnitTest lintDebug

# Build metadata and configuration isolation
python3 -m unittest discover -s tests -v
```

See the [development and testing guide](scripts/README.md) for more details.

## Known limitations

- **Compatibility is still expanding.** Existing test results do not cover every headphone model, player, or Android vendor's system.
- **Switching takes time.** Bluetooth connections and audio routing introduce delays. Seamless switching and zero brief speaker playback are not guaranteed.
- **Some alerts may trigger switching.** Mac playback detection can treat notifications or system sounds as new playback.
- **Pausing the previous device is best effort.** It depends on system permissions and player capabilities; not every app will respond.
- **Background recovery depends on the operating system.** Screen locking, deep sleep, battery restrictions, revoked permissions, and process termination can affect responsiveness. Sustained background operation and power use need further device testing.
- **Exiting the Mac app does not actively return headphones to the phone.** Sleeping, pausing, or quitting stops Mac-side coordination, but a complete return-handoff flow is not yet implemented.

Passing automated tests does not mean every real-device scenario has been validated. See the [validation scope](docs/validation.md).

## Privacy and connectivity

- Audio plays directly over system Bluetooth; Sound Link does not relay it through the cloud.
- Devices exchange coordination state over the local network with identity verification and encrypted communication.
- Device identity keys are stored in the system Keychain or Android Keystore.
- Before sharing diagnostics, review and redact personal device names, network addresses, and other personal information.

## Project layout

```text
apps/
  macos/       Mac app and core logic
  android/     Android app
packages/
  protocol/    Protocol specifications and test fixtures
assets/        Icons and brand resources
scripts/       Local builds and resource generation
tests/         Cross-module tests
docs/          Architecture, interaction, and validation notes
```

This repository contains the application source and everything needed for independent development builds. Website operations, official signing, notarization, and deployment configuration are maintained separately and are not prerequisites for building this project.

## Feedback and contributions

Bug reports, suggestions, and pull requests are welcome. Please use [Issues](https://github.com/bryanlee-hao/sound-link/issues) to report problems.

For compatibility reports, include:

- Mac model and macOS version.
- Phone model, Android version, and vendor OS version.
- Headphone model.
- App version, reproduction steps, and expected versus actual behavior.
- Whether the device was locked, in a power-saving mode, or changing networks.

Do not submit pairing QR codes, private keys, signing files, or unreviewed raw diagnostics.

## License

Sound Link is licensed under the [MIT License](LICENSE).

See [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) for third-party licenses and notices.
