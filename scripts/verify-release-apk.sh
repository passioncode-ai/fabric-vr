#!/usr/bin/env bash
# verify-release-apk.sh — is this APK the release Fabric VR may publish? Exit 0 only if it is.
#
#   bash scripts/verify-release-apk.sh <apk> [<version>]
#
# The release workflow (`.github/workflows/release.yml`) runs this on the APK it built, before
# uploading it, and a non-zero exit stops the release. It checks what a headset and Meta's own
# packaging rules check, plus the one thing only this repository knows — which key is ours:
#
#   - `apksigner verify` succeeds, with exactly one signer, and the APK Signature Scheme v2 or v3
#     verifies. A v1-only (JAR) signature is refused: Meta does not support v1-only apps on Quest
#     (VRC.Quest.Packaging.2 requires v2), and Android refuses one for an app targeting API 30+.
#   - The signer's certificate SHA-256 is RELEASE_CERT_SHA256 below, the release certificate
#     `docs/deployment/signing.md` publishes. Anything else — the debug key, a throwaway key, a
#     second keystore — is a different app to Android, and an install of it can never be updated
#     by the real one. There is deliberately no override: a release signed by another key is
#     always wrong.
#   - The manifest is a release manifest: not debuggable, targetSdkVersion 34 (Meta: apps created
#     since 2026-03-01 must target 34), minSdkVersion within Meta's 29-34, 64-bit native code only
#     (VRC.Quest.Packaging.6), and the package is `ai.passioncode.fabricvr`.
#   - With <version> (the tag without its `v`, or `-rc.N`), the APK's versionName is that version
#     plus `+<sha>` — so a `v0.2.0` tag cannot ship an APK that calls itself 0.1.0.
#
# Passing this is NOT store readiness: no Horizon Store listing exists, and the store's own
# review (performance, input, assets) is not something an APK check can answer.
#
# Tools: `APKSIGNER` and `AAPT2` if set (`scripts/check-release-apk.sh` sets them to fakes);
# otherwise the newest build-tools under $ANDROID_HOME (or $ANDROID_SDK_ROOT, or `sdk.dir` in
# local.properties). Nothing secret is read or printed: an APK's signature and manifest are public.
#
# EXIT CODE IS THE OUTPUT.
set -eu

# The release certificate's SHA-256, as `apksigner` prints it. Its single published home is
# `docs/deployment/signing.md`; `scripts/check-release-apk.sh` fails if the two disagree.
RELEASE_CERT_SHA256=0669c0cf4907e41141ac5c4bf82a25ce75c91e33bfb0d7fca05540e394c6d541
PACKAGE=ai.passioncode.fabricvr
TARGET_SDK=34
MIN_SDK_FLOOR=29
MIN_SDK_CEIL=34

APK=${1:?usage: verify-release-apk.sh <apk> [<version>]}
VERSION=${2:-}
VERSION=${VERSION#v}
VERSION=${VERSION%%-rc.*}

[ -f "$APK" ] || { echo "FAIL: no APK at $APK"; exit 1; }

sdk_root() {
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { printf '%s\n' "$d"; return; }
  done
  root=$(cd "$(dirname "$0")/.." && pwd)
  if [ -f "$root/local.properties" ]; then
    d=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | head -n 1)
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { printf '%s\n' "$d"; return; }
  fi
  return 1
}

tool() {
  sdk=$(sdk_root) || { echo "FAIL: no Android SDK (set ANDROID_HOME) to find $1 in" >&2; return 1; }
  # Newest build-tools first: `sort -V` orders 36.1.0 after 36.0.0 and 37.0.0 after both.
  newest=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort -V | tail -n 1)
  [ -x "$sdk/build-tools/$newest/$1" ] || { echo "FAIL: $sdk/build-tools/$newest/$1 is missing" >&2; return 1; }
  printf '%s\n' "$sdk/build-tools/$newest/$1"
}

APKSIGNER=${APKSIGNER:-$(tool apksigner)}
AAPT2=${AAPT2:-$(tool aapt2)}

FAIL=0
bad() { echo "ERR:     $1"; FAIL=1; }

# ---------- the signature ----------
if ! signed=$("$APKSIGNER" verify --print-certs --verbose "$APK" 2>&1); then
  printf '%s\n' "$signed" | sed 's/^/         /'
  echo "FAIL: apksigner does not verify $APK (its output is above): unsigned, a broken signature, or"
  echo "      the tool could not run — apksigner needs a JDK on JAVA_HOME or PATH"
  exit 1
fi

scheme() { printf '%s\n' "$signed" | grep -q "^Verified using $1 scheme .*: true$"; }
schemes=""
for s in v1 v2 v3 v3.1 v4; do scheme "$s" && schemes="$schemes $s"; done
if scheme v2 || scheme v3; then
  echo "ok:      signature schemes verified:$schemes"
else
  bad "neither APK Signature Scheme v2 nor v3 verifies (verified:${schemes:- none}); Quest needs v2"
fi

signers=$(printf '%s\n' "$signed" | sed -n 's/^Number of signers: //p' | head -n 1)
if [ "$signers" = 1 ]; then
  echo "ok:      exactly one signer"
else
  bad "expected exactly one signer, apksigner reports '${signers:-nothing}'"
fi

# Every signer certificate digest apksigner prints, in either of its spellings: build-tools 35
# says `Signer #1 certificate SHA-256 digest:`, build-tools 37 says `V2 Signer: certificate SHA-256
# digest:` (and `V3 Signer: …` when v3 is on). Public-key digests are a different number and are
# skipped. All of them must be one certificate, and it must be ours. (When `T-037` adds a v3
# rotation lineage from the debug key, this is the line to revisit: the lineage's old certificate
# is expected there, and only there.)
digests=$(printf '%s\n' "$signed" \
  | grep -E '^(Signer #[0-9]+|V[0-9.]+ Signer[^:]*:) certificate SHA-256 digest: ' \
  | sed 's/.*certificate SHA-256 digest: //' | tr -d ': \t\r' | tr '[:upper:]' '[:lower:]' \
  | sort -u || true)
count=$(printf '%s' "$digests" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  bad "apksigner printed no certificate SHA-256 digest; refusing an output this script cannot read"
elif [ "$count" -gt 1 ]; then
  bad "the signature schemes name $count different certificates: $(printf '%s' "$digests" | tr '\n' ' ')"
elif [ "$digests" = "$RELEASE_CERT_SHA256" ]; then
  echo "ok:      certificate SHA-256 $digests is the published release certificate"
else
  bad "certificate SHA-256 $digests is NOT the release certificate $RELEASE_CERT_SHA256"
fi

# ---------- the manifest ----------
badging=$("$AAPT2" dump badging "$APK" 2>&1) || { printf '%s\n' "$badging"; echo "FAIL: aapt2 cannot read $APK"; exit 1; }
field() { printf '%s\n' "$badging" | sed -n "s/^$1:'\([^']*\)'.*/\1/p" | head -n 1; }

pkg=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
vname=$(printf '%s\n' "$badging" | sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" | head -n 1)
vcode=$(printf '%s\n' "$badging" | sed -n "s/^package: .* versionCode='\([^']*\)'.*/\1/p" | head -n 1)
# aapt2 from build-tools 35+ prints `minSdkVersion:`; older ones printed `sdkVersion:`.
min=$(field minSdkVersion)
[ -n "$min" ] || min=$(field sdkVersion)
target=$(field targetSdkVersion)
native=$(printf '%s\n' "$badging" | sed -n 's/^native-code: //p' | head -n 1)

if [ "$pkg" = "$PACKAGE" ]; then
  echo "ok:      package $pkg"
else
  bad "package is '${pkg:-unreadable}', expected $PACKAGE"
fi

if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  bad "the APK is debuggable; a release manifest must not be (Meta: android:debuggable false or unset)"
else
  echo "ok:      not debuggable"
fi

if [ "$target" = "$TARGET_SDK" ]; then
  echo "ok:      targetSdkVersion $target"
else
  bad "targetSdkVersion is '${target:-unreadable}', expected $TARGET_SDK"
fi

case $min in
  ''|*[!0-9]*) bad "minSdkVersion is unreadable ('$min')" ;;
  *) if [ "$min" -ge "$MIN_SDK_FLOOR" ] && [ "$min" -le "$MIN_SDK_CEIL" ]; then
       echo "ok:      minSdkVersion $min"
     else
       bad "minSdkVersion $min is outside Meta's $MIN_SDK_FLOOR-$MIN_SDK_CEIL"
     fi ;;
esac

if [ "$native" = "'arm64-v8a'" ]; then
  echo "ok:      native code arm64-v8a only"
else
  bad "native code is '${native:-none}', expected arm64-v8a only (Quest apps are 64-bit only)"
fi

if [ -n "$VERSION" ]; then
  case $vname in
    "$VERSION+"*) echo "ok:      versionName $vname matches the tag's $VERSION" ;;
    *) bad "versionName '${vname:-unreadable}' does not start with the tag's version $VERSION+" ;;
  esac
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: $APK is not a publishable Fabric VR release"
  exit 1
fi
echo "OK: $APK is release-signed (versionName $vname, versionCode $vcode)"
exit 0
