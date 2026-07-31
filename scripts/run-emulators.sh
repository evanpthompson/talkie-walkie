#!/usr/bin/env bash
#
# Boot N Android emulators headlessly, build & install talkie-walkie on all
# of them, launch the app, and tail logcat — all via SDK command-line tools
# (emulator/adb), no Android Studio GUI required.
#
# The build itself (assembleDebug) resolves entirely from Google/Maven
# Central plus the vendored concentus/ module — no jitpack dependency to go
# flaky on. See docs/spec.md and concentus/NOTICE.md for background.
#
# Caveat: Bluetooth Classic RFCOMM (what this app uses for PTT) between two
# *emulators* is not reliably supported — emulator-to-emulator virtual
# Bluetooth varies by emulator version and mostly targets BLE, not Classic.
# This script is useful for build/install/launch/crash smoke-testing on N
# instances; for an actual two-way PTT test, pair a real device with either
# a second real device or one emulator, or use two real devices.
#
# Written for macOS's stock /bin/bash (3.2) — no `set -u`, no associative
# arrays, no array values through env vars (bash can't export arrays).
#
# Usage:
#   ./scripts/run-emulators.sh                          # boots DEFAULT_AVD_IMAGES below
#   ./scripts/run-emulators.sh Pixel_7_API_35            # boot just one AVD
#   ./scripts/run-emulators.sh Pixel_7_API_35 Pixel_8_API_36 Pixel_9_API_36
#   ./scripts/run-emulators.sh --list-avds               # list installed AVDs and exit
#   ./scripts/run-emulators.sh --no-kill                 # leave emulators running on exit

set -eo pipefail

# ── Config — edit these, or pass AVD names as positional args ──────────────

DEFAULT_AVD_IMAGES=(Pixel_7_API_35 Pixel_8_API_36)

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BOOT_TIMEOUT_SECS="${BOOT_TIMEOUT_SECS:-180}"
BASE_PORT="${BASE_PORT:-5554}"     # emulator ports increment by 2: 5554, 5556, 5558...
KILL_ON_EXIT=1                     # overridden by --no-kill below

APP_ID="com.talkiewalkie"
MAIN_ACTIVITY="$APP_ID/.MainActivity"

# Runtime permissions to pre-grant so the app doesn't block on a permission
# dialog when driven headlessly. BLUETOOTH_SCAN/CONNECT only exist on API
# 31+; `pm grant` on an older image just fails harmlessly (caught below).
GRANT_PERMISSIONS=(
  android.permission.RECORD_AUDIO
  android.permission.BLUETOOTH_CONNECT
  android.permission.BLUETOOTH_SCAN
)

# ── Arg parsing (positional args override DEFAULT_AVD_IMAGES) ──────────────

AVD_IMAGES=()
for arg in "$@"; do
  case "$arg" in
    --list-avds)
      exec "$ANDROID_HOME/emulator/emulator" -list-avds
      ;;
    --no-kill)
      KILL_ON_EXIT=0
      ;;
    -*)
      echo "Unknown argument: $arg" >&2
      exit 1
      ;;
    *)
      AVD_IMAGES+=("$arg")
      ;;
  esac
done
if [ "${#AVD_IMAGES[@]}" -eq 0 ]; then
  AVD_IMAGES=("${DEFAULT_AVD_IMAGES[@]}")
fi

# ── Setup ────────────────────────────────────────────────────────────────

EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$PROJECT_DIR/scripts/.emulator-logs"
mkdir -p "$LOG_DIR"

for bin in "$EMULATOR" "$ADB"; do
  [ -x "$bin" ] || { echo "Not found or not executable: $bin (check ANDROID_HOME)" >&2; exit 1; }
done

if [ ! -f "$PROJECT_DIR/local.properties" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
fi

SERIALS=()

cleanup() {
  if [ "$KILL_ON_EXIT" != "1" ]; then
    echo "Leaving emulators running (--no-kill). Serials: ${SERIALS[*]}"
    return
  fi
  echo "Shutting down emulators..."
  local serial
  for serial in "${SERIALS[@]}"; do
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

# ── Boot one AVD, wait for full boot, append its serial to SERIALS[] ───────

boot_avd() {
  local avd_name="$1"
  local port="$2"
  local serial="emulator-$port"

  echo "Booting '$avd_name' on $serial..."
  "$EMULATOR" -avd "$avd_name" -port "$port" \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    > "$LOG_DIR/${serial}.emulator.log" 2>&1 &
  SERIALS+=("$serial")

  "$ADB" -s "$serial" wait-for-device

  echo "Waiting for $serial to finish boot (timeout ${BOOT_TIMEOUT_SECS}s)..."
  local waited=0
  while [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 2
    waited=$((waited + 2))
    if [ "$waited" -ge "$BOOT_TIMEOUT_SECS" ]; then
      echo "Timed out waiting for $serial to boot — see $LOG_DIR/${serial}.emulator.log" >&2
      exit 1
    fi
  done
  echo "$serial booted."
}

# ── Boot all requested AVDs ──────────────────────────────────────────────────

port="$BASE_PORT"
for avd in "${AVD_IMAGES[@]}"; do
  boot_avd "$avd" "$port"
  port=$((port + 2))
done

# ── Build once, install on every booted device ───────────────────────────────

echo "Building debug APK..."
cd "$PROJECT_DIR"
./gradlew assembleDebug

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "Build succeeded but APK not found at $APK" >&2; exit 1; }

for serial in "${SERIALS[@]}"; do
  echo "Installing on $serial..."
  "$ADB" -s "$serial" install -r "$APK"

  for perm in "${GRANT_PERMISSIONS[@]}"; do
    "$ADB" -s "$serial" shell pm grant "$APP_ID" "$perm" >/dev/null 2>&1 || true
  done

  echo "Launching on $serial..."
  "$ADB" -s "$serial" shell am start -n "$MAIN_ACTIVITY" >/dev/null

  echo "Streaming logcat for $serial to $LOG_DIR/${serial}.logcat.log"
  "$ADB" -s "$serial" logcat > "$LOG_DIR/${serial}.logcat.log" 2>&1 &
done

echo
echo "Running: ${SERIALS[*]}"
echo "Logs:    $LOG_DIR"
echo "Press Ctrl+C to shut down (or re-run with --no-kill to leave them up)."
wait
