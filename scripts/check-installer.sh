#!/usr/bin/env bash
# check-installer.sh — the installer must name the headset it wrote to, and refuse to guess.
#
# `H-30`. `install-on-quest.sh` took the first line of `adb devices`: with both headsets reachable
# it installed on an arbitrary one, printed `connected.`, started the activity and exited 0, and
# nothing in its output named which headset got the build. Two people then could not say what
# either of them was running — which is the other half of `H-31`, where every APK ever produced
# carried `versionCode = 1`.
#
# **This runs the real script against a fake `adb`.** The script already takes `ADB` from the
# environment, so no production code exists for testing only; what the fake decides is what a
# device would have answered. Three cases, and the third is the canary: without it, a script that
# exited non-zero for any reason at all would pass the first two.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A fake adb whose answers come from files, so each case writes its own device.
make_adb() {
  cat > "$WORK/adb" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  devices) cat "$FAKE_DEVICES"; exit 0 ;;
  connect) exit 0 ;;
esac
# everything else is `-s <serial> <verb> …`
shift 2
case "${1:-}" in
  shell)
    case "${2:-}" in
      getprop) echo "FAKE-SERIAL" ;;
      dumpsys) echo "    versionCode=$FAKE_INSTALLED minSdk=34 targetSdk=34" ;;
      am) echo "Starting: Intent" ;;
      *) : ;;
    esac
    ;;
  install) echo "Success" ;;
  logcat) : ;;
  *) : ;;
esac
exit 0
FAKE
  chmod +x "$WORK/adb"
}
make_adb

apk() {
  mkdir -p "$WORK/out"
  : > "$WORK/out/app-debug.apk"
  printf '{ "elements": [ { "versionCode": %s, "versionName": "x" } ] }\n' "$1" > "$WORK/out/output-metadata.json"
}

run_installer() {
  ( cd "$ROOT" && ADB="$WORK/adb" FAKE_DEVICES="$WORK/devices" FAKE_INSTALLED="$1" \
      DEVICE="${2:-}" APK="$WORK/out/app-debug.apk" TIMEOUT=5 \
      bash scripts/install-on-quest.sh ) > "$WORK/log" 2>&1
}

# --- case 1: two devices, no DEVICE set -> refuse, and name both -------------------------------
printf 'List of devices attached\n1111\tdevice\n2222\tdevice\n' > "$WORK/devices"
apk 110
if run_installer 110 ""; then
  say "ERR:" "the installer picked one of two connected devices instead of refusing"
  FAIL=1
elif ! grep -q 1111 "$WORK/log" || ! grep -q 2222 "$WORK/log"; then
  say "ERR:" "the installer refused but did not name both devices:"
  cat "$WORK/log"
  FAIL=1
else
  say "ok:" "two devices are refused, and both are named"
fi

# --- case 2: one device, and the install does not take -> non-zero -----------------------------
printf 'List of devices attached\n1111\tdevice\n' > "$WORK/devices"
apk 110
if run_installer 42 "1111"; then
  say "ERR:" "the installer reported success while the device carries a different versionCode"
  FAIL=1
else
  say "ok:" "a versionCode that did not take fails the install"
fi

# --- case 3, THE CANARY: one device, matching version -> success, and the device is named ------
# Without this, a script that exited non-zero unconditionally would pass both cases above.
if ! run_installer 110 "1111"; then
  say "ERR:" "the installer fails on a correct install — the two checks above prove nothing:"
  cat "$WORK/log"
  exit 2
fi
if ! grep -q "serial:" "$WORK/log" || ! grep -q "after:" "$WORK/log"; then
  say "ERR:" "a successful install does not name the device or the version it wrote:"
  cat "$WORK/log"
  FAIL=1
else
  say "ok:" "a correct install succeeds and names the device and the version (canary)"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: installer scan"
  exit 1
fi
echo "OK: installer scan"
exit 0
