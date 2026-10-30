#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apps/android/gradlew -p apps/android assembleDebug testDebugUnitTest lintDebug --console=plain
swift test --package-path apps/macos
./scripts/build-macos.sh
