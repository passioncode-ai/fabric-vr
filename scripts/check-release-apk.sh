#!/usr/bin/env bash
# check-release-apk.sh — the release verifier must refuse every APK it exists to refuse.
#
# `scripts/verify-release-apk.sh` is the last step before a release APK is uploaded for
# publishing (`.github/workflows/release.yml`). It is the only place the signed artefact is
# compared with the certificate this repository publishes, so a verifier that passes everything
# would let a debug-signed, unsigned or wrongly-keyed APK reach a GitHub release — and an APK
# signed by another key can never be updated by the real one, on any headset that installs it.
#
# **This runs the real script against a fake `apksigner` and a fake `aapt2`.** The script already
# takes both from the environment (`APKSIGNER`, `AAPT2`), so no production code exists for testing
# only; what the fakes print is what the build tools would have printed for such an APK, in their
# own format. The first case is the canary: without it, a verifier that exited non-zero for any
# reason at all would pass every refusal below.
#
# It also checks the two places the verifier meets the rest of the repository: the fingerprint it
# enforces is the one `docs/deployment/signing.md` publishes, and the release workflow hands the
# keystore secrets to the build only as environment values, never inside a command line.
#
# WHAT THIS CANNOT CATCH: whether the real `apksigner` still prints these lines. The release job
# runs the real tool on the real APK, and the verifier refuses an output it cannot read (no digest
# line, no scheme line) rather than passing it.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The published release certificate, in the form `apksigner` prints it (lowercase, no colons).
GOOD=0669c0cf4907e41141ac5c4bf82a25ce75c91e33bfb0d7fca05540e394c6d541
# The debug certificate both headsets carry (`docs/deployment/signing.md`): a real APK can be
# signed with it, and it must be refused.
DEBUG=44f3efe6c08c71542cefafa687c48b9e75dd1f80a97f1e22710b6841159083fd

cat > "$WORK/apksigner" <<'FAKE'
#!/usr/bin/env bash
cat "$FAKE_SIGNER_OUT"
exit "${FAKE_SIGNER_RC:-0}"
FAKE
cat > "$WORK/aapt2" <<'FAKE'
#!/usr/bin/env bash
cat "$FAKE_BADGING"
FAKE
chmod +x "$WORK/apksigner" "$WORK/aapt2"
: > "$WORK/app-release.apk"

# signer <v1> <v2> <v3> <signers> <digest> [<v3-digest>] — in build-tools 37's spelling
# (`V2 Signer: certificate …`, a block per verified scheme); OLD=1 uses build-tools 35's
# (`Signer #1 certificate …`). Both are what the real tool prints, read from a real APK.
signer() {
  {
    echo "Verifies"
    echo "Verified using v1 scheme (JAR signing): $1"
    echo "Verified using v2 scheme (APK Signature Scheme v2): $2"
    echo "Verified using v3 scheme (APK Signature Scheme v3): $3"
    echo "Verified using v3.1 scheme (APK Signature Scheme v3.1): false"
    echo "Verified using v3.2 scheme (APK Signature Scheme v3.2): false"
    echo "Verified using v4 scheme (APK Signature Scheme v4): false"
    echo "Verified for SourceStamp: false"
    echo "Number of signers: $4"
    i=1
    while [ "$i" -le "$4" ]; do
      if [ "${OLD:-0}" = 1 ]; then
        echo "Signer #$i certificate DN: CN=fixture"
        echo "Signer #$i certificate SHA-256 digest: $5"
        echo "Signer #$i public key SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111"
      else
        for v in V2 V3; do
          { [ "$v" = V2 ] && [ "$2" = true ]; } || { [ "$v" = V3 ] && [ "$3" = true ]; } || continue
          d=$5; [ "$v" = V3 ] && [ -n "${6:-}" ] && d=$6
          echo "$v Signer: certificate DN: CN=fixture"
          echo "$v Signer: certificate SHA-256 digest: $d"
          echo "$v Signer: public key SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111"
        done
      fi
      i=$((i + 1))
    done
  } > "$WORK/signer.out"
}

# badging <targetSdk> <native-code> [debuggable]
badging() {
  {
    echo "package: name='ai.passioncode.fabricvr' versionCode='24000000' versionName='0.1.0+abc1234' platformBuildVersionName='15' compileSdkVersion='35'"
    echo "minSdkVersion:'34'"
    echo "targetSdkVersion:'$1'"
    echo "application-label:'Fabric VR'"
    [ "${3:-}" = debuggable ] && echo "application-debuggable"
    echo "native-code: $2"
  } > "$WORK/badging.out"
}

verify() {
  ( cd "$ROOT" && APKSIGNER="$WORK/apksigner" AAPT2="$WORK/aapt2" \
      FAKE_SIGNER_OUT="$WORK/signer.out" FAKE_BADGING="$WORK/badging.out" \
      FAKE_SIGNER_RC="${RC:-0}" \
      bash scripts/verify-release-apk.sh "$WORK/app-release.apk" "$@" ) > "$WORK/log" 2>&1
}

# refuses <what> <reason-pattern> [version] — the current fixtures must be refused, and for the
# reason the case is about: a refusal for some other reason would hide the check under test.
refuses() {
  what=$1; why=$2; shift 2
  if verify "$@"; then
    say "ERR:" "the verifier accepted $what:"
    sed 's/^/         /' "$WORK/log"
    FAIL=1
  elif ! grep -qE "$why" "$WORK/log"; then
    say "ERR:" "the verifier refused $what, but not for that reason (expected /$why/):"
    sed 's/^/         /' "$WORK/log"
    FAIL=1
  else
    say "ok:" "$what is refused"
  fi
}

# --- case 1, THE CANARY: the published key, v2 and v3, one signer, a release manifest ---------
signer false true true 1 "$GOOD"
badging 34 "'arm64-v8a'"
if ! verify 0.1.0; then
  say "ERR:" "the verifier refuses a correct release APK — every refusal below proves nothing:"
  sed 's/^/         /' "$WORK/log"
  exit 2
fi
if ! grep -q "$GOOD" "$WORK/log"; then
  say "ERR:" "a verified APK does not print the certificate digest it was checked against"
  FAIL=1
else
  say "ok:" "a correct release APK passes and names its certificate (canary)"
fi

# --- build-tools 35's spelling, v2 only (what AGP produces by default), passes too ----------
OLD=1 signer false true false 1 "$GOOD"; badging 34 "'arm64-v8a'"
if verify 0.1.0; then
  say "ok:" "the older apksigner spelling, v2 only, passes"
else
  say "ERR:" "the verifier refuses build-tools 35's apksigner output for a correct APK:"
  sed 's/^/         /' "$WORK/log"
  FAIL=1
fi

# --- the refusals ---------------------------------------------------------------------------
signer false true true 1 "$DEBUG";  badging 34 "'arm64-v8a'"
refuses "an APK signed with the debug key" "is NOT the release certificate" 0.1.0

signer true false false 1 "$GOOD";  badging 34 "'arm64-v8a'"
refuses "a v1-only (JAR) signature" "neither APK Signature Scheme v2 nor v3" 0.1.0

signer false false false 1 "$GOOD"; badging 34 "'arm64-v8a'"
RC=1 refuses "an APK apksigner says DOES NOT VERIFY" "apksigner does not verify" 0.1.0

signer false true true 1 "$GOOD" "$DEBUG"; badging 34 "'arm64-v8a'"
refuses "a v3 block naming another certificate than the v2 block" "name 2 different certificates" 0.1.0

OLD=1 signer false true false 1 "$DEBUG"; badging 34 "'arm64-v8a'"
refuses "the debug key in the older apksigner spelling" "is NOT the release certificate" 0.1.0

signer false true true 2 "$GOOD";   badging 34 "'arm64-v8a'"
refuses "an APK with two signers" "expected exactly one signer" 0.1.0

signer false true true 1 "$GOOD";   badging 34 "'arm64-v8a'" debuggable
refuses "a debuggable APK" "is debuggable" 0.1.0

signer false true true 1 "$GOOD";   badging 33 "'arm64-v8a'"
refuses "a targetSdk other than 34" "targetSdkVersion is .33." 0.1.0

signer false true true 1 "$GOOD";   badging 34 "'arm64-v8a' 'armeabi-v7a'"
refuses "32-bit native code" "expected arm64-v8a only" 0.1.0

signer false true true 1 "$GOOD";   badging 34 "'arm64-v8a'"
refuses "a tag whose version is not the APK's" "does not start with the tag" 0.2.0

: > "$WORK/signer.out";             badging 34 "'arm64-v8a'"
refuses "an apksigner output with no digest line" "printed no certificate SHA-256" 0.1.0

# --- the verifier and the record agree -------------------------------------------------------
colon=$(printf '%s' "$GOOD" | tr '[:lower:]' '[:upper:]' | sed 's/../&:/g; s/:$//')
if grep -q "$GOOD" "$ROOT/scripts/verify-release-apk.sh" && grep -q "$colon" "$ROOT/docs/deployment/signing.md"; then
  say "ok:" "the verifier enforces the fingerprint docs/deployment/signing.md publishes"
else
  say "ERR:" "the verifier's fingerprint and docs/deployment/signing.md disagree — one home moved"
  FAIL=1
fi

# --- the workflow: secrets reach the build only as environment values ------------------------
WF="$ROOT/.github/workflows/release.yml"
if [ ! -f "$WF" ]; then
  say "ERR:" ".github/workflows/release.yml is missing"
  FAIL=1
else
  bad=$(grep -n 'secrets\.ANDROID_' "$WF" | grep -vE '^[0-9]+:[[:space:]]+[A-Z0-9_]+:[[:space:]]*\$\{\{[[:space:]]*secrets\.ANDROID_[A-Z0-9_]+[[:space:]]*\}\}[[:space:]]*$' || true)
  if [ -n "$bad" ]; then
    say "ERR:" "a keystore secret is used outside a plain env mapping in release.yml:"
    printf '%s\n' "$bad" | sed 's/^/         /'
    FAIL=1
  elif ! grep -q 'scripts/verify-release-apk.sh' "$WF"; then
    say "ERR:" "release.yml does not run scripts/verify-release-apk.sh on what it uploads"
    FAIL=1
  else
    say "ok:" "release.yml maps the keystore secrets to env only, and verifies the APK it uploads"
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: release APK verifier"
  exit 1
fi
echo "OK: release APK verifier"
exit 0
