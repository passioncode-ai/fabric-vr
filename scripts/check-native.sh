#!/usr/bin/env bash
# check-native.sh — the JNI bridge still compiles.
#
# **Seconds, not minutes, and that is the whole point.** `check-all.sh` excludes the native build
# on purpose: `externalNativeBuild` pulls the NDK and a full arm64 compile of whisper.cpp into
# whatever calls it, which is not a pre-commit cost. The consequence, measured 2026-09-21 while
# changing the bridge for `B-147`: **nothing compiles `fabricvr_whisper.cpp` before a push.** The
# CI `checks` job has no NDK by design, so a syntax error in the bridge reaches the remote and is
# caught minutes later by the heavy `release` job — the one whose own comment records that it had
# never once succeeded until it was fixed.
#
# This compiles ONE file with `-fsyntax-only`: no linking, no whisper.cpp, no ggml. It cannot say
# the library works — that is the release job's and the device's answer — only that the file a
# person just edited is still a C++ translation unit. Which is exactly the failure that was
# reaching the remote.
#
# **It reports and passes where there is no NDK**, in the same shape as the device-gate check
# against a missing `origin/main`: a check that cannot run says so and does not pretend. What it
# never does is print the word that means it checked.
set -eu

SRC=feature-stt/src/main/cpp/fabricvr_whisper.cpp
[ -f "$SRC" ] || { echo "OK: native bridge check — no $SRC in this tree"; exit 0; }

# **16 KB page alignment is asked for** (`B-104`). NDK r27 links at 4 KB unless told otherwise, and
# a 4 KB-aligned library does not load on a 16 KB-page kernel; `llvm-readelf -lW` read every LOAD
# segment of `libfabricvr_whisper.so` as 0x1000 until this flag existed. A configuration check,
# not a build: it runs before the NDK and submodule exits below, so it holds on every machine.
# Lint says the same thing, but only in CI's release job, which is not running (`B-195`).
if ! grep -q 'ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' feature-stt/build.gradle.kts; then
  echo "ERR: feature-stt/build.gradle.kts does not pass -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON,"
  echo "     so the bridge links at 4 KB pages and will not load on a 16 KB-page kernel (B-104)."
  exit 1
fi
echo "ok: the native bridge is linked for 16 KB pages"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
CLANG=$(ls "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/clang++ 2>/dev/null | sort | tail -1 || true)
if [ -z "$CLANG" ] || [ ! -x "$CLANG" ]; then
  echo "note: native bridge NOT compiled — no NDK under $SDK/ndk. The release job is the only"
  echo "      compiler of this file when that is true, and it runs after the push."
  exit 0
fi

WHISPER=third_party/whisper.cpp
if [ ! -f "$WHISPER/include/whisper.h" ]; then
  echo "note: native bridge NOT compiled — $WHISPER/include/whisper.h is absent."
  echo "      Run: git submodule update --init --recursive"
  exit 0
fi

# **No JDK needed, and looking for one was the first version's mistake.** `jni.h` ships in the
# NDK's own sysroot, which is the right copy anyway because this file targets Android rather than
# the host — and Android Studio's bundled JBR has no `include/` directory at all, so the JDK path
# failed on the very machine the check was written on. `--target=aarch64-none-linux-android34`
# makes the NDK clang pick its sysroot up by itself.
# **The error log goes to a temporary file, not beside the source.** It was written to
# `<src>.err` inside the tracked tree, and `*.err` is not ignored — an interrupted run left an
# untracked artefact next to the source that `git add -A` would commit and that `selftest.sh`'s
# template (which copies untracked-but-not-ignored files) would carry into every case.
ERRLOG=$(mktemp -t checknative.XXXXXX) || { echo "FAIL: native bridge check cannot create a temporary file"; exit 1; }
trap 'rm -f "$ERRLOG"' EXIT

# The NDK version, which is what the reader wants to know. Four `dirname`s from
# `<sdk>/ndk/<ver>/toolchains/llvm/prebuilt/<host>/bin/clang++` land on `llvm` — so the message
# whose whole purpose is naming which compiler ran printed the constant "llvm".
NDK_VER=$(printf '%s' "$CLANG" | sed -n 's|.*/ndk/\([^/]*\)/.*|\1|p')

if "$CLANG" -fsyntax-only -std=c++17 -fexceptions \
     --target=aarch64-none-linux-android34 \
     -I"$WHISPER/include" -I"$WHISPER/ggml/include" \
     "$SRC" 2>"$ERRLOG"; then
  echo "OK: native bridge compiles (NDK ${NDK_VER:-unknown})"
else
  echo "FAIL: native bridge does not compile:"
  sed 's/^/         /' "$ERRLOG" | head -30
  exit 1
fi

# ---------- the submodule is the version every document names ----------
# `B-167`. `DEC-0050` pins 1 210 checksums over every Gradle artefact and touches **nothing**
# here: the submodule is compiled by CMake, so no dependency verification reaches it. What made
# moving it look routine was one line — `branch = master` in `.gitmodules` — which turns
# `git submodule update --remote` into a supply-chain change with no review and no diff anybody
# reads. The line is gone; this is the half that keeps it gone.
#
# It compares the **tag the submodule describes as** against the version the README and the
# module document state, because those are what a person reads. A bump is then three edits that
# have to agree, rather than one command whose effect is invisible.
EXPECTED_TAG=$(grep -oE 'whisper\.cpp v[0-9]+\.[0-9]+\.[0-9]+' README.md | head -1 | grep -oE 'v[0-9.]+')
if [ -z "$EXPECTED_TAG" ]; then
  echo "ERR: README.md names no whisper.cpp version, so nothing can be compared against the pin"
  exit 1
fi
if [ -d third_party/whisper.cpp/.git ] || [ -f third_party/whisper.cpp/.git ]; then
  ACTUAL_TAG=$(git -C third_party/whisper.cpp describe --tags --exact-match 2>/dev/null || echo "")
  if [ -z "$ACTUAL_TAG" ]; then
    echo "ERR: the whisper.cpp submodule is not at a tagged commit — it is at"
    echo "     $(git -C third_party/whisper.cpp rev-parse --short HEAD 2>/dev/null), which no document names."
    echo "     A pin nobody can name is a pin nobody reviews (B-167)."
    exit 1
  fi
  if [ "$ACTUAL_TAG" != "$EXPECTED_TAG" ]; then
    echo "ERR: the whisper.cpp submodule is at $ACTUAL_TAG and the documents say $EXPECTED_TAG."
    echo "     Bumping it is three edits that must agree: the submodule, README.md and"
    echo "     docs/modules/feature-stt.md. This is the check that makes them agree."
    exit 1
  fi
  if grep -q 'branch *=' .gitmodules 2>/dev/null; then
    echo "ERR: .gitmodules declares a branch, which makes 'git submodule update --remote' a"
    echo "     one-word supply-chain change (B-167). Remove it; the pin is the contract."
    exit 1
  fi
  echo "ok: the whisper.cpp submodule is $ACTUAL_TAG, the version the documents name, and no branch invites it to move"
else
  echo "note: the whisper.cpp submodule is not checked out, so its pin cannot be read"
fi
