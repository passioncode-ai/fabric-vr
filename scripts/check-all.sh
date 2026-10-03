#!/usr/bin/env bash
# check-all.sh — every gate this project has, in one command.
#
# CI calls this file and nothing else, so "green locally" and "green in CI" cannot mean two
# different sets of checks. Adding a gate means adding it HERE, not in the workflow.
# EXIT CODE IS THE OUTPUT: non-zero on the first failure.
#
# ORDER IS DELIBERATE. The three cheap gates cost about eight seconds between them, so a
# documentation failure reports in eight seconds instead of after a Gradle run.
#
# `:app:lintDebug` is NOT here. Measured 2026-09-20: its task graph contains `externalNativeBuild`,
# so running it pulls the NDK and a full arm64 compile of whisper.cpp into whatever calls it. That
# is right for the release job, which already pays for the NDK, and wrong for a gate meant to answer
# in seconds. CI runs it in the release job; `scripts/check-all.sh --with-lint` runs it here when
# you want the whole thing before pushing.
set -eu

# ---------- a JDK that RUNS, not a name that resolves ----------
#
# macOS ships `/usr/bin/java` as a stub that exists, is executable, and answers every invocation
# with *"Unable to locate a Java Runtime."* — so `command -v java` finds it and Gradle then fails
# with a message about Java in the middle of a documentation run. This file is the project's one
# entry point for every gate, and it died that way on 2026-09-22 **after** the whole self-test had
# passed: 44 cases green, then eleven words about java.com and no verdict. `.githooks/pre-push`
# already knew the answer and kept it to itself; a guard that lives in the hook protects a push
# and leaves every other caller — CI, an agent, a person — to meet the stub alone.
#
# Prefer the JBR the README documents (Android Studio's, JDK 21), and only then ask whether some
# other `java` actually runs. Ask by RUNNING it: the stub's whole defect is that it exists.
if [ -z "${JAVA_HOME:-}" ]; then
  _JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -x "$_JBR/bin/java" ]; then
    export JAVA_HOME="$_JBR"
  elif ! java -version >/dev/null 2>&1; then
    echo "check-all: no working JDK. Set JAVA_HOME to a JDK 21 — on this machine that is" >&2
    echo "           Android Studio's JBR at $_JBR" >&2
    echo "           (/usr/bin/java exists on macOS and is a stub that runs nothing.)" >&2
    exit 1
  fi
fi

WITH_LINT=0
[ "${1:-}" = "--with-lint" ] && WITH_LINT=1

# ---------- a gate that silently loses a check prints the same OK (`B-164`) ----------
#
# **The incident, in one sentence:** `git checkout scripts/check-strings.sh`, used to undo a
# planted defect, deleted the whole library-module section — and `check-all.sh` stayed green with
# one fewer `ok:` line, while a decision record and two commit messages went on describing a check
# that was not in the tree. Nothing noticed, because a gate with one check fewer prints the same
# word as a gate with all of them.
#
# `selftest.sh` closes part of this: it plants a defect per section of `check-docs.sh`, so one of
# those vanishing is named rather than missed. It says nothing about the other nine scripts, and
# `check-strings.sh` — the script the incident happened to — is one of them.
#
# **A ratchet on verdict lines catches the class for every script at once.** Each entry below is
# the number of `ok:`/`OK` lines that script prints on a clean tree, and it may only RISE: adding a
# check raises it in the same change, and a number that falls means a check went away. It cannot
# say which check — that is what the self-test is for — but it says which script, immediately, and
# it is one line per gate rather than a counter threaded through eleven of them.
#
# A gate whose verdict count is legitimately variable is not listed; today none is.
verdict_floor() {
  case $1 in
    check-device-gate) echo 1 ;;
    check-secrets)     echo 5 ;;
    check-destructive) echo 3 ;;
    check-seams)       echo 5 ;;
    check-schemas)     echo 2 ;;
    check-docs)        echo 28 ;;
    check-strings)     echo 4 ;;
    check-installer)   echo 4 ;;
    check-build-cache) echo 6 ;;
    check-native)      echo 1 ;;
    check-shell)       echo 1 ;;
    *)                 echo 0 ;;
  esac
}

# Runs a gate, shows everything it printed, and refuses a run that printed fewer verdicts than
# that gate is recorded as having. The gate's own exit code still decides first — this only
# catches the case where it succeeded while doing less than it used to.
gate() {
  script=$1; shift
  name=$(basename "$script" .sh)
  out=$(bash "$script" "$@" 2>&1) && rc=0 || rc=$?
  printf '%s\n' "$out"
  [ "$rc" -eq 0 ] || exit "$rc"
  seen=$(printf '%s\n' "$out" | grep -cE '^(ok:|OK[:—]|OK )' || true)
  floor=$(verdict_floor "$name")
  if [ "$seen" -lt "$floor" ]; then
    echo "FAIL: $name printed $seen verdict lines and is recorded as printing $floor —"
    echo "      a check went away, or the floor is stale. Both are decisions, neither is silent."
    exit 2
  fi
}

# The device-gate check ran ONLY from the workflow until 2026-09-21, which made this script's own
# first sentence false: a local run was green while CI was red, for a commit that had added an
# instrumented test with no ledger row. `origin/main` may be absent in a shallow clone, and the
# script says so and passes rather than failing on a missing ref.
gate scripts/check-device-gate.sh "${BASE_REF:-origin/main}"
gate scripts/check-secrets.sh
gate scripts/check-destructive.sh
gate scripts/check-seams.sh
# R2, from `DEC-0031`. It also runs as `MainThreadPolicyTest`; it is here as well because a red
# should be readable without waiting for a Gradle run, and because the script self-tests and
# exits 2 when the scan proved nothing — a distinct failure worth seeing on its own line.
python3 tools/check_main_thread.py
# Every @Database version has an exported schema, committed. Run BEFORE the build so the
# uncommitted-change half compares what is on disk, and see `DEC-0036` for why the rule exists.
gate scripts/check-schemas.sh
gate scripts/check-docs.sh
# One key, one module — and every `tools:ignore="UnusedResources"` naming the board row that
# owns the string it keeps alive. The unreferenced half is Android lint's `UnusedResources`,
# promoted to an error in `app/build.gradle.kts`, because writing a script to detect what the
# toolchain already detects is how a project ends up with two answers (`DEC-0045`).
gate scripts/check-strings.sh
# The installer must name the headset it wrote to and refuse to guess between two (`H-30`), and
# it must ASSERT the version it just installed rather than report it (`H-31`). Runs the real
# script against a fake `adb`, with a canary that proves a correct install still succeeds.
gate scripts/check-installer.sh
# LC-15: the command `AGENTS.md` names for bringing build output back under its cap is run against
# a throwaway repository — it prunes over the cap, keeps sources, refuses a directory git does not
# ignore — and the APK/AAB names are asserted fixed, which is what keeps releases at one per variant.
gate scripts/check-build-cache.sh
# **One file, `-fsyntax-only`, ~1.4 s.** The full native build is deliberately not here — it pulls
# the NDK and a whole arm64 compile of whisper.cpp — and the consequence was that NOTHING compiled
# the JNI bridge before a push: CI's cheap job has no NDK by design, so a syntax error in it
# reached the remote and waited for the heavy release job. Measured 2026-09-21 while changing that
# file. This says the bridge is still a translation unit; the release job and the device still own
# whether it works. It reports and passes where there is no NDK rather than printing `OK`.
gate scripts/check-native.sh
# **Milliseconds, and it exists because one mistake was made three times.** An apostrophe inside
# a single-quoted awk program closes the string and the rest is parsed as shell — written three
# separate times in one session, each time found by running a three-minute gate and reading a
# syntax error from a line that looks like awk. `DEC-0062`.
gate scripts/check-shell.sh
# **The gates' own negative tests.** Five sections and one script were written with `SI-06` quoted
# in their headers and three decision records claiming each had been watched failing — and a blind
# verification found five of the six evadable. Prose is not evidence that a check can fail; this
# is. The script prints its own split — planted defects and controls — so this comment does not
# restate a number that moves. `DEC-0056`, `DEC-0062`.
bash scripts/selftest.sh
python3 docs/ux/lint.py
# The JVM suite, **and the instrumented sources compiled**. CI cannot RUN an instrumented test —
# arm64, Horizon OS, the Spatial SDK runtime, a 190 MB model — and nothing compiled them either,
# so `app/src/androidTest` went red at `REQ-048` (a new parameter on `PermissionRequester`) and
# stayed red through a whole group of work while every gate printed green. The suite that cannot
# compile is the one the headset session `DEC-0066` schedules; the break would have been found by
# the person wearing the headset, which is the most expensive place to find it.
#
# Compiling is cheap and is not a claim that anything ran: `check-device-gate.sh` owns that
# distinction and says so in capitals.
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin

if [ "$WITH_LINT" -eq 1 ]; then
  ./gradlew --no-daemon :app:lintDebug
  echo "OK: all gates, lint included"
else
  echo "OK: all gates (lint runs in CI's release job — it needs the NDK)"
fi

# **Read the exit code, not the output.** This script prints "OK:" lines per gate and the summary
# above; a caller that greps for one of them and chains `&& git push` pushes whatever the grep
# matched, including a tree whose test task failed three lines earlier. That happened on
# 2026-09-21 and the push went out on a run that had just printed three FAILED tests. The line
# below is what a caller should look for, and `set -eu` means it is only ever reached on success.
echo "ALL GATES GREEN"

# ---------- the receipt the pre-push hook reads ----------
#
# **Why a receipt and not the gates themselves inside the hook.** `git push` opens its connection
# to the remote BEFORE running `pre-push` — it needs the remote refs to build the hook's input —
# and then holds that connection open for however long the hook takes. This suite takes minutes,
# and GitHub closes an idle SSH session well inside that window, so git finished the gates, printed
# `ALL GATES GREEN`, and then died writing the pack: **`SIGPIPE`, exit 141, nothing transmitted**,
# four times in a row on 2026-09-22 while the remote sat three commits behind. Measured: the same
# push with this script stubbed to `exit 0` succeeds, and with the real script fails — the
# difference is the duration, not the verdict.
#
# So the gates run OUTSIDE the connection, here, and leave a receipt naming the exact tree they
# passed on. The hook compares it in milliseconds. `SI-03` is unchanged and better served: the
# receipt is written only on the line below, which `set -eu` makes reachable only on success, so a
# caller still cannot manufacture a green by grepping.
#
# The receipt names the INDEX tree rather than a commit, because the gates normally run before the
# commit and committing does not change what they read. It lives in `.git/`, which is per-clone and
# untracked — a receipt that could be committed would travel to a machine that never ran anything.
# The tree the gates read, as a value, computed WITHOUT writing anything.
#
# Not `git write-tree`: that reflects the INDEX and writes tree objects, so it would change the
# moment work is staged and — worse — would report the real repository's index when run inside
# `selftest.sh`'s copy, whose `.git` is a symlink to it. Hashing the CONTENT of the tracked files
# is invariant across `git add` and `git commit`, which is what makes a receipt written before the
# commit still true at the push, and it is read-only, which is what makes it safe to exercise
# inside a copied tree.
gates_tree_id() {
  git ls-files -z 2>/dev/null | xargs -0 shasum -a 256 2>/dev/null | shasum -a 256 | cut -d' ' -f1
}

# `GATES_RECEIPT` overrides the location, for `selftest.sh` alone: inside its copied tree `.git`
# is a symlink to the real repository, and the one thing that script's header forbids is writing
# through it.
_receipt=${GATES_RECEIPT:-$(git rev-parse --git-dir 2>/dev/null)/gates-receipt}
if _id=$(gates_tree_id) && [ -n "$_id" ]; then
  printf '%s\n' "$_id" > "$_receipt"
fi

exit 0
