#!/usr/bin/env bash
# install-on-quest.sh — wait for the headset, then install and launch.
#
# The Quest drops its adb connection within seconds of going to sleep, and a 135 MB streamed
# install needs a live link for about a minute. So this waits for the device to be awake rather
# than failing once and leaving a half-written APK.
set -u
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}

# A hard-coded address belongs to one router. When DEVICE is unset, take whichever headset adb can
# already see — a USB cable and a different network both work then, without editing this file.
#
# **It refuses to guess between two** (`H-30`). The old `awk '…{print $1; exit}'` took the first
# line: with both headsets reachable it installed on an arbitrary one, printed `connected.`,
# started the activity and exited 0, and nothing in its output named which headset got the build.
# Two people then could not say what either of them was running.
if [ -z "${DEVICE:-}" ]; then
  connected=$("$ADB" devices | awk '$2=="device"{print $1}')
  count=$(printf '%s' "$connected" | grep -c . || true)
  if [ "${count:-0}" -gt 1 ]; then
    echo "more than one device is connected — set DEVICE= explicitly:"
    printf '%s\n' "$connected"
    exit 1
  fi
  DEVICE=$(printf '%s' "$connected" | head -1)
fi
if [ -z "${DEVICE:-}" ]; then
  DEVICE=192.168.0.253:5555
  # Said out loud, so "no headset is connected" is distinguishable from "the right one is".
  echo "no device is connected; falling back to the hard-coded address $DEVICE"
fi
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
META=${META:-$(dirname "$APK")/output-metadata.json}
TIMEOUT=${TIMEOUT:-300}

[ -f "$APK" ] || { echo "no APK at $APK — run ./gradlew :app:assembleDebug"; exit 1; }

# What this APK actually carries, from the file Gradle writes beside it — not from `aapt2`, which
# is not on every PATH, and not from `git`, which describes the tree rather than the artefact.
EXPECTED=$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' "$META" 2>/dev/null | head -1)
[ -n "$EXPECTED" ] || echo "no versionCode in $META — the post-install check will be skipped"

# What the device says it has. Empty when the app is not installed at all, which is a legitimate
# answer for a first install and must not be confused with a mismatch.
installed_version() {
  "$ADB" -s "$DEVICE" shell dumpsys package ai.passioncode.fabricvr 2>/dev/null |
    sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1
}

echo "waiting for $DEVICE (put the headset on; up to ${TIMEOUT}s)…"
deadline=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  "$ADB" connect "$DEVICE" >/dev/null 2>&1
  if [ "$("$ADB" devices | awk -v d="$DEVICE" '$1==d{print $2}')" = "device" ]; then
    echo "connected."
    # **Name the headset before touching it.** "Which build is he on" is unanswerable while the
    # installer declines to say which device it wrote to (`H-30`).
    echo "--- device ---"
    echo "serial: $("$ADB" -s "$DEVICE" shell getprop ro.serialno | tr -d '\r')"
    echo "model:  $("$ADB" -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
    echo "before: $(installed_version)"
    "$ADB" -s "$DEVICE" install -r "$APK" || { echo "install failed — keep the headset on and retry"; exit 1; }
    AFTER=$(installed_version)
    echo "after:  $AFTER  (apk: ${EXPECTED:-unknown})"
    # **Assert, do not report.** The retro's *verify against the build that is installed* rule
    # could not be followed while there was nothing to compare: every APK was versionCode 1.
    if [ -n "$EXPECTED" ] && [ "$AFTER" != "$EXPECTED" ]; then
      echo "the installed versionCode is $AFTER but this APK is $EXPECTED — the install did not take"
      exit 1
    fi
    "$ADB" -s "$DEVICE" shell am start -n ai.passioncode.fabricvr/.PanelActivity
    sleep 3
    echo "--- resumed activity ---"
    "$ADB" -s "$DEVICE" shell dumpsys activity activities | grep -i fabricvr | head -3
    echo "--- app log (last 40 lines) ---"
    "$ADB" -s "$DEVICE" logcat -d -t 40 | grep -iE "fabricvr|AndroidRuntime" | head -20
    exit 0
  fi
  sleep 3
done
echo "the headset never came online within ${TIMEOUT}s"
exit 1
