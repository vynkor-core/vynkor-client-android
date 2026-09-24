#!/usr/bin/env bash
# Full local verification: Rust core + Android unit tests + debug APK.
# Usage: scripts/check.sh   (ANDROID_HOME defaults to ~/.android-sdk, then ~/Android/Sdk)
set -euo pipefail
cd "$(dirname "$0")/.."

# Pick the first SDK that actually contains platforms/ (env may point at a
# stub like /opt/android-sdk without installed packages).
for candidate in "${ANDROID_HOME:-}" "$HOME/.android-sdk" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
  if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
    export ANDROID_HOME="$candidate"
    break
  fi
done
unset ANDROID_SDK_ROOT

echo "==> cargo test"
(cd rust && cargo test)

echo "==> cargo clippy (-D warnings)"
(cd rust && cargo clippy --all-targets -- -D warnings)

echo "==> gradlew :app:testDebugUnitTest :app:assembleDebug"
./gradlew :app:testDebugUnitTest :app:assembleDebug

echo "==> all green"
