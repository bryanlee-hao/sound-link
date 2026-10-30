#!/bin/bash
# Xcode build phase: prepare resources before Xcode processes Info.plist/signs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

APP="$TARGET_BUILD_DIR/$WRAPPER_NAME"
RESOURCES="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
ICONS="$DERIVED_FILE_DIR/SoundLinkIcons"
mkdir -p "$RESOURCES" "$ICONS"

# Never run the cross-platform release/publish workflow from an Xcode build.
xcrun swift scripts/icons.swift --mac-output "$ICONS"
iconutil -c icns "$ICONS/SoundLink.iconset" -o "$RESOURCES/SoundLink.icns"
cp "$ICONS/MenuBarIcon.tiff" "$RESOURCES/MenuBarIcon.tiff"
cp THIRD_PARTY_NOTICES.txt "$RESOURCES/THIRD_PARTY_NOTICES.txt"
xcrun python3 scripts/package.py "$APP" --xcode-info "$DERIVED_FILE_DIR/SoundLink-Info.plist"
