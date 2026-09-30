#!/usr/bin/env bash
# check-strings.sh — the two things about `strings.xml` that the toolchain will not tell you.
#
# **What this script is NOT.** It does not look for unreferenced strings: Android lint's
# `UnusedResources` already does, it resolves `@string/` from the manifest and `R.plurals.` from
# Kotlin — where a naive `grep R.string.NAME` got three of twenty-seven answers wrong when this
# was measured on 2026-09-20 — and `app/build.gradle.kts` promotes it to an **error**. Writing a
# script to detect what the toolchain already detects is how a project ends up with two answers.
#
# What lint does not find, and this does:
#
#   1. **A key declared in two modules.** `action_allow` was in `:app` and in `:core-common`, and
#      `UiStateMapper` resolves the core one — so the app copy was dead and a human reviewer
#      would never have seen it. Lint caught that one only because the app copy happened to be
#      unreferenced; a duplicate where BOTH are referenced is invisible to it, and the two
#      spellings then drift, which is the exact thing `strings.xml`'s header exists to prevent.
#
#   2. **A `tools:ignore="UnusedResources"` with no receipt.** `T-032` kept three strings alive
#      on purpose — they are the evidence of `B-21` and `B-33`, two unfixed defects, and deleting
#      them would delete the evidence. That exemption must name a board row within six lines, or
#      it is a suppression list growing one line at a time, which is how the twenty-six
#      accumulated in the first place.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

FILES=$(git ls-files '*/src/main/res/values/strings.xml')
if [ -z "$FILES" ]; then
  say "ERR:" "no strings.xml is tracked — this scan would pass by finding nothing"
  exit 2
fi

# --- 1. one key, one module -------------------------------------------------------------------
keys_of() { sed -n 's/.*<string name="\([A-Za-z0-9_]*\)".*/\1/p' "$1"; }

DUPES=$(
  for f in $FILES; do
    m=$(printf '%s' "$f" | sed 's|/src/main/res/values/strings.xml||')
    keys_of "$f" | while read -r k; do [ -n "$k" ] && echo "$k $m"; done
  done | sort | awk '{ if ($1 == last) { print $1 " in " lastm " and " $2 } ; last = $1; lastm = $2 }'
)

# The canary runs the same function over a planted pair, because a pipeline that silently
# produces nothing prints the same "ok" as a clean tree — the failure `check-secrets.sh` records.
CANARY=$(mktemp -d)
trap 'rm -rf "$CANARY"' EXIT
mkdir -p "$CANARY/a" "$CANARY/b"
printf '<resources><string name="probe_key">x</string></resources>\n' > "$CANARY/a/strings.xml"
printf '<resources><string name="probe_key">y</string></resources>\n' > "$CANARY/b/strings.xml"
CANARY_HITS=$( (keys_of "$CANARY/a/strings.xml"; keys_of "$CANARY/b/strings.xml") | sort | uniq -d | wc -l | tr -d ' ')
if [ "${CANARY_HITS:-0}" -ne 1 ]; then
  say "ERR:" "the key reader found $CANARY_HITS of 1 planted duplicate — this scan proves nothing"
  exit 2
fi

if [ -n "$DUPES" ]; then
  say "ERR:" "a string key is declared in two modules; one of them is dead and they will drift:"
  printf '%s\n' "$DUPES"
  FAIL=1
else
  say "ok:" "no string key is declared in two modules (canary matched, so the scan ran)"
fi

# --- 2. every lint exemption names the finding that owns it -----------------------------------
UNJUSTIFIED=$(mktemp)
trap 'rm -rf "$CANARY"; rm -f "$UNJUSTIFIED"' EXIT
: > "$UNJUSTIFIED"
for f in $FILES; do
  grep -n 'tools:ignore="UnusedResources"' "$f" 2>/dev/null | cut -d: -f1 | while read -r line; do
    lo=$(( line > 6 ? line - 6 : 1 ))
    window=$(sed -n "${lo},${line}p" "$f")
    case "$window" in
      *B-[0-9]*) ;;
      *) echo "$f:$line exempts a string from UnusedResources without naming a board row" >> "$UNJUSTIFIED" ;;
    esac
  done
done
if [ -s "$UNJUSTIFIED" ]; then
  say "ERR:" "a string is kept alive by a lint exemption that cites nothing:"
  cat "$UNJUSTIFIED"
  FAIL=1
else
  say "ok:" "every UnusedResources exemption names the finding that owns its string"
fi

# --- 3. the library modules, which lint will not do -------------------------------------------
# `UnusedResources` is an ERROR on `:app` (`DEC-0045`) and that is the accurate half: lint
# resolves `@string/` from the manifest and `R.plurals.` from Kotlin.
#
# **It does not fire for a library module at all**, and adding `lint { error += … }` to one is
# inert — measured 2026-09-21 by planting `zz_probe_core` in `core-common` and watching both
# `:core-common:lintDebug --rerun-tasks` and `:app:lintDebug` print BUILD SUCCESSFUL. That is
# deliberate upstream: a library's resources are part of its API and may be used by a consumer
# lint cannot see. In THIS repository there is no such consumer — the modules ship inside one app
# — so a repository-wide reference scan is accurate here in a way it is not for a published
# library, and it is what covers the module that owns the error vocabulary.
#
# Both reference forms, because each is the one the other misses: `R.string.` / `R.plurals.` from
# Kotlin, `@string/` / `@plurals/` from XML and the manifest.
LIB_UNUSED=$(mktemp)
trap 'rm -rf "$CANARY"; rm -f "$UNJUSTIFIED" "$LIB_UNUSED"' EXIT
: > "$LIB_UNUSED"

# Both declaration forms, as two expressions rather than one alternation: BSD `sed` speaks BRE
# and has no `\|`, so the combined pattern matched **nothing** and this whole section printed
# `ok` on its first run — the exact failure this repository already has a name for, committed by
# the script written to prevent it. The canary below is why it did not survive that run.
names_of() {
  sed -n 's/.*<string name="\([A-Za-z0-9_]*\)".*/\1/p' "$1"
  sed -n 's/.*<plurals name="\([A-Za-z0-9_]*\)".*/\1/p' "$1"
}
printf '%s\n' '<resources><string name="probe_s">x</string><plurals name="probe_p"><item quantity="one">y</item></plurals></resources>' > "$CANARY/names.xml"
NAME_HITS=$(names_of "$CANARY/names.xml" | wc -l | tr -d ' ')
if [ "${NAME_HITS:-0}" -ne 2 ]; then
  say "ERR:" "the declaration reader found $NAME_HITS of 2 planted names — this scan proves nothing"
  exit 2
fi

for f in $FILES; do
  case "$f" in
    app/*) continue ;;  # lint owns the application module, and does it better
  esac
  m=$(printf '%s' "$f" | sed 's|/src/main/res/values/strings.xml||')
  for k in $(names_of "$f"); do
    hits=$(git grep -lI --untracked -e "R.string.$k" -e "R.plurals.$k" -e "@string/$k" -e "@plurals/$k" \
             -- '*.kt' '*.xml' 2>/dev/null | grep -cv "res/values/strings.xml" || true)
    [ "${hits:-0}" -eq 0 ] && echo "$m: $k is declared and referenced nowhere" >> "$LIB_UNUSED"
  done
done
if [ -s "$LIB_UNUSED" ]; then
  say "ERR:" "a library module declares a string nothing references (lint cannot see these):"
  cat "$LIB_UNUSED"
  FAIL=1
else
  say "ok:" "every library-module string has a reference somewhere in the repository"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: string scan"
  exit 1
fi
echo "OK: string scan"
exit 0
