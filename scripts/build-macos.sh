#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
xcodebuild -project apps/macos/SoundLink.xcodeproj -scheme 'Sound Link' -configuration Debug \
  -derivedDataPath build/macos CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= build
printf '\nDevelopment app: build/macos/Build/Products/Debug/Sound Link.app\n'
