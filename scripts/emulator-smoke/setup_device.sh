#!/usr/bin/env bash
# Prepare one emulator for the smoke test (smoke.py).
#
# Usage: setup_device.sh <adb-serial> <host-port>
#
# Installs the gms debug APK, the test app and any optional helper APKs, enables the accessibility
# service, configures and starts the MCP server over adb, forwards <host-port> to it and starts Shizuku.
#
# Environment:
#   MCP_TOKEN          bearer token to configure (default: smoke-test-token)
#   APP_APK            app APK (default: app/build/outputs/apk/gms/debug/app-gms-debug.apk, from `make build`)
#   TEST_APP_APK       test app APK (default: built by test-app/build.sh into build/emulator-smoke/test-app)
#   SHIZUKU_APK        optional Shizuku APK; needed on API 30-32 for the guarded-field typing check
#   TERMUX_APK         optional Termux APK
#   WEBVIEW_SHELL_APK  optional WebView shell APK (org.chromium.webview_shell), used by the touch probe
# Nothing is downloaded. See README.md for where to get the optional APKs.
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <adb-serial> <host-port>" >&2; exit 2; }
SERIAL=$1
HOST_PORT=$2
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TOKEN="${MCP_TOKEN:-smoke-test-token}"
DEVICE_PORT=8090
NS=com.danielealbano.androidremotecontrolmcp
PKG=$NS.gms.debug

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB_BIN="$SDK/platform-tools/adb"; else ADB_BIN=adb; fi
adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }

APP_APK="${APP_APK:-$REPO/app/build/outputs/apk/gms/debug/app-gms-debug.apk}"
TEST_APP_APK="${TEST_APP_APK:-$REPO/build/emulator-smoke/test-app/nosettext.apk}"
[ -f "$APP_APK" ] || { echo "App APK not found: $APP_APK (run 'make build')" >&2; exit 1; }
[ -f "$TEST_APP_APK" ] || "$HERE/test-app/build.sh" >/dev/null

install() {
  local result
  result=$(adb install -r -g "$1" 2>&1 | grep -oE 'Success|Failure \[[^]]*\]' | tail -1)
  echo "install $(basename "$1"): ${result:-no result}"
  [ "$result" = Success ]
}

adb wait-for-device
install "$APP_APK"
# The test app is signed with a throwaway key; replace a copy signed with another key.
if ! install "$TEST_APP_APK"; then
  adb uninstall dev.test.nosettext >/dev/null
  install "$TEST_APP_APK"
fi
for apk in "${SHIZUKU_APK:-}" "${TERMUX_APK:-}" "${WEBVIEW_SHELL_APK:-}"; do
  [ -z "$apk" ] || install "$apk"
done

adb shell settings put secure enabled_accessibility_services \
  "$PKG/$NS.services.accessibility.McpAccessibilityService"
adb shell settings put secure accessibility_enabled 1
adb shell am broadcast -a "$NS.ADB_CONFIGURE" -n "$PKG/$NS.services.mcp.AdbConfigReceiver" \
  --es bearer_token "$TOKEN" --es binding_address 0.0.0.0 --ei port "$DEVICE_PORT" \
  --ez https_enabled false --ez tunnel_enabled false --ez shell_elevation_enabled true | tail -1
# Configuration is read when the server starts, so stop a running server before starting it again.
adb shell am start -n "$PKG/$NS.services.mcp.AdbServiceTrampolineActivity" --es action stop >/dev/null
sleep 2
adb shell am start -n "$PKG/$NS.services.mcp.AdbServiceTrampolineActivity" --es action start >/dev/null
adb forward "tcp:$HOST_PORT" "tcp:$DEVICE_PORT" >/dev/null

# Start the Shizuku server through adb (the "Start via ADB" path of the Shizuku app).
if adb shell pm path moe.shizuku.privileged.api 2>/dev/null | grep -q package:; then
  # shellcheck disable=SC2016  # expanded by the device shell
  lib=$(adb shell 'ls $(dirname $(pm path moe.shizuku.privileged.api | sed s/package://))/lib/*/libshizuku.so' | tr -d '\r' | head -1)
  echo "shizuku: $(adb shell "$lib" 2>&1 | tail -1)"
fi

# Wait for the server and report the number of tools.
for _ in $(seq 1 20); do
  if count=$(curl -s -m 5 -X POST "http://localhost:$HOST_PORT/mcp" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' 2>/dev/null \
      | python3 -c "import json,sys;r=sys.stdin.read();r=''.join(l[5:] for l in r.splitlines() if l.startswith('data:')) or r;print(len(json.loads(r)['result']['tools']))" 2>/dev/null); then
    echo "MCP server on localhost:$HOST_PORT: $count tools"
    exit 0
  fi
  sleep 1
done
echo "MCP server did not answer on localhost:$HOST_PORT" >&2
adb logcat -d | grep -E "FATAL|AndroidRuntime: " | head -5 >&2 || true
exit 1
