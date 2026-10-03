#!/usr/bin/env bash
# check-build-cache.sh — the build-output prune works, and touches nothing it should not.
#
# **Lifecycle contract LC-15.** `scripts/build-cache.sh` is the command `AGENTS.md` names for
# bringing this machine's build output back under its cap. A prune that is never run against a
# fixture is a prune nobody knows works — and a prune that deletes the wrong directory is worse
# than none. So this runs the REAL script against a throwaway git repository carrying this
# repository's own `.gitignore`, with planted build directories and a planted source file.
#
# Six cases. The fourth is the canary for the safety rule (a directory git does not ignore is
# refused, never deleted); the sixth is the "at most two releases" half of LC-15, which holds by
# construction only while the APK and AAB keep fixed names — so it reads the real build file.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A fixture shaped like this repository: a source file, three build outputs of known size.
fixture() {
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo"
  cp "$ROOT/.gitignore" "$WORK/repo/.gitignore"
  ( cd "$WORK/repo" && git init -q . )
  mkdir -p "$WORK/repo/app/src/main" "$WORK/repo/app/build/outputs/apk/debug" "$WORK/repo/app/.cxx" "$WORK/repo/core-notes/build"
  echo 'class Keep' > "$WORK/repo/app/src/main/Keep.kt"
  head -c 2097152 /dev/zero > "$WORK/repo/app/build/outputs/apk/debug/app-debug.apk"
  head -c 1048576 /dev/zero > "$WORK/repo/app/.cxx/libwhisper.so"
  head -c 1048576 /dev/zero > "$WORK/repo/core-notes/build/classes.jar"
}

run() {  # $1 = mode, $2 = cap in MB
  BUILD_CACHE_ROOT="$WORK/repo" FABRICVR_BUILD_CAP_MB="$2" bash "$ROOT/scripts/build-cache.sh" "$1" > "$WORK/log" 2>&1
}

outputs_present() { [ -d "$WORK/repo/app/build" ] && [ -d "$WORK/repo/app/.cxx" ] && [ -d "$WORK/repo/core-notes/build" ]; }
outputs_gone() { [ ! -e "$WORK/repo/app/build" ] && [ ! -e "$WORK/repo/app/.cxx" ] && [ ! -e "$WORK/repo/core-notes/build" ]; }

# --- case 1: report measures and removes nothing ------------------------------------------------
fixture
if run report 1 && outputs_present && grep -q 'MB of build output, cap 1 MB' "$WORK/log"; then
  say "ok:" "report measures the build output against the cap and removes nothing"
else
  say "ERR:" "report removed something, or did not state the total against the cap:"; cat "$WORK/log"; FAIL=1
fi

# --- case 2: enforce under the cap removes nothing ----------------------------------------------
fixture
if run enforce 100 && outputs_present; then
  say "ok:" "enforce under the cap leaves the build output alone"
else
  say "ERR:" "enforce under the cap removed build output:"; cat "$WORK/log"; FAIL=1
fi

# --- case 3: enforce over the cap removes every output and no source ----------------------------
fixture
if run enforce 1 && outputs_gone && [ -f "$WORK/repo/app/src/main/Keep.kt" ]; then
  say "ok:" "enforce over the cap removes every build output and keeps the sources"
else
  say "ERR:" "enforce over the cap left build output behind, or took a source file:"; cat "$WORK/log"; FAIL=1
fi

# --- case 4 (canary): a candidate git does not ignore is refused, never deleted ------------------
fixture
printf 'build/\n' > "$WORK/repo/.gitignore"          # app/.cxx and core-notes/build no longer ignored…
mkdir -p "$WORK/repo/.kotlin"; echo x > "$WORK/repo/.kotlin/state"   # …and neither is .kotlin
if run clean 1; then
  say "ERR:" "the prune exited 0 over directories git does not ignore"; cat "$WORK/log"; FAIL=1
elif [ -d "$WORK/repo/.kotlin" ] && [ -d "$WORK/repo/app/.cxx" ] && grep -q 'not ignored by git' "$WORK/log"; then
  say "ok:" "a directory git does not ignore is refused by name and left in place"
else
  say "ERR:" "a directory git does not ignore was deleted, or the refusal did not name it:"; cat "$WORK/log"; FAIL=1
fi

# --- case 5: clean removes regardless of the cap ------------------------------------------------
fixture
if run clean 100000 && outputs_gone; then
  say "ok:" "clean removes the build output whatever the cap"
else
  say "ERR:" "clean left build output behind:"; cat "$WORK/log"; FAIL=1
fi

# --- case 6: release artefacts keep fixed names, so a build overwrites the previous one ----------
if grep -nE 'archivesName|outputFileName' "$ROOT/app/build.gradle.kts" > "$WORK/names"; then
  say "ERR:" "app/build.gradle.kts versions its artefact names — builds now accumulate, and"
  say "" "scripts/build-cache.sh must prune them to the current and previous release (LC-15):"
  sed 's/^/         /' "$WORK/names"; FAIL=1
else
  say "ok:" "APK and AAB names are fixed per variant, so at most one per variant is ever on disk"
fi

exit "$FAIL"
