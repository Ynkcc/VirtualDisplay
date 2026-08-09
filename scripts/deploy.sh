#!/usr/bin/env bash
# Build, push and start scrcpy daemon server on connected Android device.
#
# Usage:
#   scripts/deploy.sh                  # full build + deploy
#   scripts/deploy.sh --skip-build     # skip gradle build, only push and restart
#   scripts/deploy.sh --skip-restart   # build and push, but don't restart server
#   scripts/deploy.sh --port 27183    # specify daemon port (default: 27183)
#   scripts/deploy.sh --bind 127.0.0.1 # specify bind address (default: 127.0.0.1)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/scrcpy/server"
APK_PATH="$SERVER_DIR/build/outputs/apk/release/server-release-unsigned.apk"
REMOTE_APK="/data/local/tmp/scrcpy-server.apk"
PORT="27183"
BIND_ADDRESS="127.0.0.1"
SKIP_BUILD=false
SKIP_RESTART=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true; shift ;;
        --skip-restart) SKIP_RESTART=true; shift ;;
        --port) PORT="$2"; shift 2 ;;
        --bind) BIND_ADDRESS="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if ! command -v adb &>/dev/null; then
    echo "[ERROR] adb not found in PATH"
    exit 1
fi

if ! adb get-state &>/dev/null; then
    echo "[ERROR] No Android device connected"
    exit 1
fi

if [ "$SKIP_BUILD" = false ]; then
    echo "[1/3] Building server APK..."
    if command -v java &>/dev/null && java -version 2>&1 | grep -qE "version \"2[1-9]|version [3-9][0-9]"; then
        true
    elif [ -d /usr/lib/jvm/java-21-openjdk ]; then
        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "       Using JAVA_HOME=$JAVA_HOME"
    fi

    cd "$PROJECT_ROOT"
    ./gradlew -p "$SERVER_DIR" assembleRelease 2>&1 | tail -3
fi

if [ ! -f "$APK_PATH" ]; then
    echo "[ERROR] APK not found at $APK_PATH"
    exit 1
fi

if [ "$SKIP_RESTART" = false ]; then
    echo "[2/3] Killing existing server..."
    adb shell "su -c 'pkill -f com.genymobile.scrcpy.Server'" 2>/dev/null || true
    sleep 1

    echo "[3/3] Pushing and starting server..."
    adb push "$APK_PATH" "$REMOTE_APK" 2>&1 | tail -1

    adb shell "su -c 'export CLASSPATH=$REMOTE_APK; nohup app_process / com.genymobile.scrcpy.Server 4.1 tunnel_forward=true audio=false send_device_meta=false send_dummy_byte=false send_stream_meta=false send_frame_meta=true cleanup=false daemon=true daemon_port=$PORT daemon_bind_address=$BIND_ADDRESS >/data/local/tmp/scrcpy-server.log 2>&1 &'" 2>&1

    sleep 2
    adb forward "tcp:$PORT" "tcp:$PORT" 2>/dev/null || true

    if adb shell "su -c 'netstat -tlnp 2>/dev/null | grep $PORT || ss -tlnp | grep $PORT'" 2>&1 | grep -q "$PORT"; then
        echo "       Server is listening on port $PORT"
    else
        echo "       [WARN] Server may not have started. Check logs: adb shell su -c 'cat /data/local/tmp/scrcpy-server.log'"
    fi
else
    echo "[2/2] Pushing APK only..."
    adb push "$APK_PATH" "$REMOTE_APK" 2>&1 | tail -1
fi

echo "[DONE] Deploy complete."
