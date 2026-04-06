#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG="com.spacecraftartrack/.ui.MainActivity"

# Find the wireless device (non-emulator)
DEVICE=$("$ADB" devices | grep -v "emulator-" | grep -E "^\S+\s+device$" | head -1 | awk '{print $1}')
if [[ -z "$DEVICE" ]]; then
  echo "✘ No phone connected. Run ./scripts/connect-phone.sh first."
  exit 1
fi

echo "▸ Target device: $DEVICE"

# Build + install + launch
cd "$(dirname "$0")/../app"
echo "▸ Building & installing…"
./gradlew installDebug
echo "▸ Launching…"
"$ADB" -s "$DEVICE" shell am start -n "$PKG"
echo "✔ SpacecraftARTrack running on $DEVICE"
