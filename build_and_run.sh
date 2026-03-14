#!/bin/bash
# Build and deploy StylusRemapper to MovinkPad via ADB.
#
# Prerequisites:
#   - Android SDK (ANDROID_HOME or standard install path)
#   - ADB connected to MovinkPad
#   - d8 (from build-tools) available
#
# Usage: bash build_and_run.sh [--run]
#   --run   Also start the remapper after deploying

set -euo pipefail

# --- Config ---
ANDROID_SDK="${ANDROID_HOME:-$USERPROFILE/AppData/Local/Android/Sdk}"
# Find latest build-tools
BUILD_TOOLS_DIR=$(ls -d "$ANDROID_SDK/build-tools/"* 2>/dev/null | sort -V | tail -1)
if [ -z "$BUILD_TOOLS_DIR" ]; then
    echo "ERROR: No Android build-tools found in $ANDROID_SDK/build-tools/"
    exit 1
fi
D8="$BUILD_TOOLS_DIR/d8.bat"

# Find android.jar (any API level works, prefer latest)
PLATFORM_DIR=$(ls -d "$ANDROID_SDK/platforms/android-"* 2>/dev/null | sort -V | tail -1)
if [ -z "$PLATFORM_DIR" ]; then
    echo "ERROR: No Android platform found in $ANDROID_SDK/platforms/"
    exit 1
fi
ANDROID_JAR="$PLATFORM_DIR/android.jar"

ADB="$ANDROID_SDK/platform-tools/adb"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/build"
# MSYS_NO_PATHCONV prevents Git Bash from converting /data/... to C:\... paths
DEVICE_PATH="/data/local/tmp/stylus-remapper"

echo "=== StylusRemapper Build ==="
echo "Build tools: $BUILD_TOOLS_DIR"
echo "Android JAR: $ANDROID_JAR"
echo ""

# --- Clean & compile ---
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

echo "[1/3] Compiling Java..."
javac --release 11 \
    -cp "$ANDROID_JAR" \
    -d "$OUT_DIR/classes" \
    "$SRC_DIR/com/example/stylusremapper/StylusRemapper.java"

echo "[2/3] Converting to DEX..."
"$D8" \
    --output "$OUT_DIR" \
    --lib "$ANDROID_JAR" \
    "$OUT_DIR/classes/com/example/stylusremapper/StylusRemapper.class"

echo "[3/3] Pushing to device..."
MSYS_NO_PATHCONV=1 "$ADB" shell "mkdir -p $DEVICE_PATH"
# Use Windows-style path for local file to avoid MSYS path conversion issues
WIN_DEX_PATH=$(cygpath -w "$OUT_DIR/classes.dex" 2>/dev/null || echo "$OUT_DIR/classes.dex")
MSYS_NO_PATHCONV=1 "$ADB" push "$WIN_DEX_PATH" "$DEVICE_PATH/classes.dex"

echo ""
echo "=== Deploy complete ==="
echo ""
echo "To run:"
echo "  adb shell CLASSPATH=$DEVICE_PATH/classes.dex app_process / com.example.stylusremapper.StylusRemapper"
echo ""

if [ "${1:-}" = "--run" ]; then
    echo "Starting StylusRemapper..."
    MSYS_NO_PATHCONV=1 "$ADB" shell "CLASSPATH=$DEVICE_PATH/classes.dex app_process / com.example.stylusremapper.StylusRemapper"
fi
