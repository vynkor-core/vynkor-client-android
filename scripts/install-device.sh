#!/usr/bin/env bash
# Build (if needed) and install the debug APK on a device connected via adb
# (USB or wireless debugging). Fails loudly when no device is attached.
#
#   scripts/install-device.sh
#
# See docs/D14_AI_CHAT_AND_SETTINGS.md for the host-side setup the app
# expects (vyn kernel + ai plugin v0.3 with model discovery/agents).
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "No device attached. Connect the phone (USB debugging, or wireless" >&2
  echo "debugging via: adb pair <ip>:<pair-port> && adb connect <ip>:<port>)." >&2
  exit 1
fi

# R-26: prefer the device-matched split APK (smaller/faster install); fall
# back to the universal one.
pick_apk() {
  local abi dir="app/build/outputs/apk/debug"
  abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  for candidate in "$dir/app-$abi-debug.apk" "$dir/app-universal-debug.apk"; do
    [ -f "$candidate" ] && { echo "$candidate"; return; }
  done
  echo ""
}

APK="$(pick_apk)"

if [ -z "$APK" ]; then
  echo "APK not built — building…"
  env -u ANDROID_SDK_ROOT ANDROID_HOME="${ANDROID_HOME:-$HOME/.android-sdk}" \
    ./gradlew :app:assembleDebug
  APK="$(pick_apk)"
fi

if [ -z "$APK" ]; then
  echo "No debug APK found after build (expected in app/build/outputs/apk/debug)" >&2
  exit 1
fi

echo "Installing $APK…"
adb install -r "$APK"
echo "Installed. Launching…"
adb shell am start -n dev.vynkor.agent/.ChatActivity
