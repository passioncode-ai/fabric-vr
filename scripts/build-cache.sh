#!/usr/bin/env bash
# build-cache.sh — what a build leaves on this machine, measured against a cap, and pruned back.
#
# **Lifecycle contract LC-15** (adopted by the organization on 2026-10-03, after a build directory
# filled a disk): whoever builds leaves at most the current release and the one before it, and the
# build caches that are not releases have a cap the repository names, with the command that brings
# them back under it. `AGENTS.md` → *Lifecycle* names this script as that command.
#
# **What a build here leaves.** Every module's `build/` (Gradle's outputs and intermediates), the
# NDK's `app/.cxx/` (a whole arm64 compile of whisper.cpp per variant) and the project's
# `.kotlin/`. The APK and AAB inside `app/build/outputs/` are written under FIXED names per variant
# — `app-debug.apk`, `app-release.apk`, `app-release.aab` — because `app/build.gradle.kts` sets no
# `archivesName` and no `outputFileName`, so a second build overwrites the first and "at most two
# releases" holds by construction. `scripts/check-build-cache.sh` refuses a build file that starts
# versioning those names, because that is the day this script must start pruning them by age.
#
# Usage:
#   bash scripts/build-cache.sh            # report: each directory, the total, the cap; exit 0
#   bash scripts/build-cache.sh enforce    # over the cap → remove them all; under it → nothing
#   bash scripts/build-cache.sh clean      # remove them all, whatever the size
#
# FABRICVR_BUILD_CAP_MB sets the cap (default 3072). BUILD_CACHE_ROOT points it at another tree —
# only `check-build-cache.sh` does that, against a fixture.
#
# **It deletes only what git ignores.** Every candidate is asked `git check-ignore` first; a
# directory git does not ignore is reported and left alone, so a mistyped pattern here can never
# take a source tree with it. Gradle's user-home cache (`~/.gradle`) is shared by every project on
# the machine and is NOT touched here — `AGENTS.md` says how it is kept bounded.
#
# EXIT CODE IS THE OUTPUT: 0 on success, 2 on a usage error or a directory that is not ignored.
set -eu

MODE=${1:-report}
case "$MODE" in report|enforce|clean) ;; *) echo "usage: $0 [report|enforce|clean]" >&2; exit 2 ;; esac

ROOT=${BUILD_CACHE_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
CAP_MB=${FABRICVR_BUILD_CAP_MB:-3072}
case "$CAP_MB" in ''|*[!0-9]*) echo "build-cache: FABRICVR_BUILD_CAP_MB must be a whole number of MB" >&2; exit 2 ;; esac
cd "$ROOT"

# The candidates, relative to the root. A glob that matches nothing stays literal and is skipped.
candidates() {
  for d in build */build .kotlin */.cxx; do
    [ -d "$d" ] && printf '%s\n' "$d"
  done
  return 0
}

size_kb() { du -sk "$1" 2>/dev/null | awk '{print $1}'; }

total_kb=0
refused=0
list=""
while IFS= read -r d; do
  [ -n "$d" ] || continue
  if ! git check-ignore -q "$d" 2>/dev/null; then
    echo "build-cache: $d is not ignored by git — left alone" >&2
    refused=1
    continue
  fi
  kb=$(size_kb "$d")
  total_kb=$((total_kb + ${kb:-0}))
  list="$list$d
"
  printf '  %8d MB  %s\n' "$(( ${kb:-0} / 1024 ))" "$d"
done <<EOF
$(candidates)
EOF

total_mb=$((total_kb / 1024))
echo "build-cache: ${total_mb} MB of build output, cap ${CAP_MB} MB"

remove_all() {
  printf '%s' "$list" | while IFS= read -r d; do
    [ -n "$d" ] || continue
    rm -rf -- "$d"
  done
  echo "build-cache: removed ${total_mb} MB; the next build regenerates it"
}

case "$MODE" in
  report) : ;;
  clean) remove_all ;;
  enforce)
    if [ "$total_mb" -gt "$CAP_MB" ]; then
      remove_all
    else
      echo "build-cache: under the cap — nothing removed"
    fi
    ;;
esac

[ "$refused" -eq 0 ] || exit 2
exit 0
