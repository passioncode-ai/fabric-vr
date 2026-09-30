#!/usr/bin/env bash
# push-model-for-tests.sh — put the speech model where the device suite reads it.
#
# The instrumented tests must not depend on a 190 MB download, so they read the model from
# /data/local/tmp, which survives an app reinstall and needs no permission.
#
#   bash scripts/push-model-for-tests.sh [path-to-ggml-small-q5_1.bin]
set -u
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
DEVICE=${DEVICE:-}
MODEL=${1:-$HOME/Downloads/ggml-small-q5_1.bin}
REMOTE_DIR=/data/local/tmp/fabricvr-test-models

# The same refusal as `install-on-quest.sh` (`H-30`): with two headsets reachable, `{print $1;
# exit}` picked an arbitrary one and said nothing about which. Pushing 190 MB to the wrong headset
# is cheaper than installing on it and just as untraceable.
if [ -z "$DEVICE" ]; then
  connected=$("$ADB" devices | awk '$2=="device"{print $1}')
  count=$(printf '%s' "$connected" | grep -c . || true)
  if [ "${count:-0}" -gt 1 ]; then
    echo "more than one device is connected — set DEVICE= explicitly:"
    printf '%s\n' "$connected"
    exit 1
  fi
  DEVICE=$(printf '%s' "$connected" | head -1)
fi
[ -n "$DEVICE" ] || { echo "no device — put the headset on and run: $ADB connect <ip>:5555"; exit 1; }

if [ ! -f "$MODEL" ]; then
  echo "model not found at $MODEL"
  echo "fetch it once:"
  echo "  curl -L -o \"$MODEL\" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
  exit 1
fi

SIZE=$(stat -f %z "$MODEL" 2>/dev/null || stat -c %s "$MODEL")
if [ "$SIZE" != "190085487" ]; then
  echo "refusing to push: $MODEL is $SIZE bytes, expected 190085487 (the published size)"
  exit 1
fi

"$ADB" -s "$DEVICE" shell mkdir -p "$REMOTE_DIR"
"$ADB" -s "$DEVICE" push "$MODEL" "$REMOTE_DIR/ggml-small-q5_1.bin"
"$ADB" -s "$DEVICE" shell chmod 644 "$REMOTE_DIR/ggml-small-q5_1.bin"
"$ADB" -s "$DEVICE" shell ls -la "$REMOTE_DIR"
