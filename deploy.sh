#!/usr/bin/env bash
# TV File Picker: build, install on the TV, grant the permission, verify.
# Usage:   ./deploy.sh <TV_SERIAL>
# Example: ./deploy.sh <TV_IP>:5555
set -euo pipefail

SERIAL="${1:-}"
PKG="com.mcanalagoz.tvpicker"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ -z "$SERIAL" ]]; then
    echo "Usage: $0 <TV_SERIAL>"
    echo "Connected devices:"
    adb devices
    exit 1
fi

cd "$(dirname "$0")"

# 0a. Locate the SDK and rewrite local.properties every time (works even after a fresh checkout)
SDK=""
for candidate in "${ANDROID_HOME:-}" /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
        SDK="$candidate"
        break
    fi
done
if [[ -z "$SDK" ]]; then
    echo "Android SDK not found. Install it first (brew install --cask android-commandlinetools)."
    exit 1
fi
echo "sdk.dir=$SDK" > local.properties
echo "==> SDK: $SDK"

# 0b. Generate the Gradle wrapper with a global Gradle if it is missing
if [[ ! -x ./gradlew ]]; then
    if ! command -v gradle >/dev/null 2>&1; then
        echo "Neither gradlew nor gradle found. Fix: brew install gradle"
        exit 1
    fi
    echo "==> Generating gradlew..."
    gradle wrapper --gradle-version 8.5
fi

# 1. Build
echo "==> Building..."
./gradlew assembleDebug

[[ -f "$APK" ]] || { echo "APK not found: $APK"; exit 1; }

# 2. Install
echo "==> Installing ($SERIAL)..."
adb -s "$SERIAL" install -r "$APK"

# 3. All-files access (needed on Android 11+, granted through adb)
echo "==> Granting permission..."
adb -s "$SERIAL" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow

# 4. Verify: does the system now see the picker?
echo "==> OPEN_DOCUMENT check:"
adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT -t "*/*" || true

echo "==> OPEN_DOCUMENT_TREE check:"
adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT_TREE || true

echo "Done. Now try picking a file or folder in Lemuroid or PPSSPP."
