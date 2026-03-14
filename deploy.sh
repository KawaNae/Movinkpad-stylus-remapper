#!/bin/bash
# Clean deploy: kill old processes, remove old files, build and install APK.
#
# Usage: bash deploy.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/debug"
PACKAGE="com.example.stylusremapper"

echo "=== Clean Deploy ==="

# 1. Force stop app and kill old app_process remnants
echo "[1/5] Killing old processes..."
MSYS_NO_PATHCONV=1 adb shell am force-stop "$PACKAGE" || true
MSYS_NO_PATHCONV=1 adb shell "for pid in \$(pidof stylusremapper 2>/dev/null); do kill \$pid; done" || true
MSYS_NO_PATHCONV=1 adb shell "ps -A 2>/dev/null | grep 'stylusremapper' | awk '{print \$2}' | xargs -r kill" || true

# 2. Remove old Phase 1 DEX files
echo "[2/5] Removing old DEX files..."
MSYS_NO_PATHCONV=1 adb shell rm -rf /data/local/tmp/stylus-remapper || true

# 3. Uninstall existing app
echo "[3/5] Uninstalling old app..."
MSYS_NO_PATHCONV=1 adb uninstall "$PACKAGE" || true

# 4. Build
echo "[4/5] Building..."
cd "$SCRIPT_DIR"
./gradlew assembleDebug --quiet

# 5. Install
APK=$(ls "$APK_DIR"/*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
    echo "ERROR: No APK found in $APK_DIR"
    exit 1
fi
echo "[5/5] Installing $(basename "$APK")..."
WIN_APK=$(cygpath -w "$APK" 2>/dev/null || echo "$APK")
adb install "$WIN_APK"

echo ""
echo "=== Done! ==="
