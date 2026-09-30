#!/usr/bin/env bash
# check-seams.sh — a view model reaches its dependencies through its constructor, never from a
# method body.
#
# `Graph` is an `object` with a `lateinit var appContext`. It cannot be constructed twice and
# cannot be reset between tests — not even under Robolectric — so a dependency taken from inside a
# method body is a dependency no test can replace, and the method is untestable in the exact place
# where its failure would cost the most. That is why `NotesViewModel.retranscribe`, the one method
# that can overwrite a person's edited text, had no test for any of its branches until `T-017`.
#
# THE RULE, STATED SO THE CHECK CAN BE MECHANICAL: in `app/.../ui/*.kt`, `Graph.` may appear only
# ABOVE the line that closes the constructor. A default argument is fine and is the pattern; a
# reach from a body is not.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

# The body begins at the first line that closes a constructor OR opens a class with none. The
# first version armed only on `^) : ViewModel()` on its own line, so a view model written
# `class X : ViewModel() {` — or with a one-line constructor — was never scanned at all. The five
# that exist happen to share the multi-line shape; the next one written differently would not.
scan() {  # $1 = file. Prints offending "line:text", returns 0 either way.
  awk '
    /^\) *: *ViewModel\(\)/        { inbody = 1; next }
    /^class .*: *ViewModel\(\) *\{/ { inbody = 1; next }
    /^class .*\) *: *ViewModel\(\)/ { inbody = 1; next }
    # A comment that NAMES the singleton is not a reach for it — and the rule is worth explaining
    # in a KDoc next to the field it constrains, which is where the first widening reded.
    inbody && /^[ \t]*(\*|\/\/|\/\*)/ { next }
    inbody && /Graph\./            { printf "%d: %s\n", FNR, $0 }
  ' "$1"
}

# Self-test: a scanner that matches nothing prints the same silence as a clean tree. Measured
# 2026-09-19 in check-secrets.sh, where ten planted credentials passed a gate reading a mangled
# blob; and 2026-09-20 in check-device-gate.sh, which could not fail at all.
CANARY_FILE=$(mktemp)
TMP_D02=$(mktemp)
TMP_RB=$(mktemp)
trap 'rm -f "$CANARY_FILE" "$TMP_D02" "$TMP_RB"' EXIT
cat > "$CANARY_FILE" <<'CANARY'
class Fake(
    private val ok: String = Graph.notes,
) : ViewModel() {
    fun bad() = Graph.settings.get("x")
}
CANARY
canary_hits=$(scan "$CANARY_FILE" | wc -l | tr -d ' ')
if [ "$canary_hits" != "1" ]; then
  say "ERR:" "the scanner found $canary_hits reaches in a file planted with exactly one — it proves nothing"
  exit 2
fi

HITS=""
for f in app/src/main/kotlin/ai/passioncode/fabricvr/ui/*.kt; do
  [ -e "$f" ] || continue
  found=$(scan "$f")
  [ -n "$found" ] && HITS="$HITS$f:$found
"
done

if [ -n "$HITS" ]; then
  say "ERR:" "a view model reaches Graph from a method body, which no test can replace:"
  printf '%s' "$HITS"
  FAIL=1
else
  say "ok:" "every Graph dependency is a constructor parameter (canary matched, so the scan ran)"
fi

# D-02: `OPEN_APP_SETTINGS` and `OPEN_SETTINGS` are two different places — the system's page,
# which can grant a permission, and ours, which cannot. They shared a `when` arm at both call
# sites, so a person who had refused the microphone twice was sent somewhere that could not help
# them and voice notes, which are the product, were gone for good.
# Asserted as REACHABILITY, not as spelling. The first version matched one arrangement — the two
# ids sharing a comma on one line — and was green against the real defect written as two separate
# arms with the same body, against the idiomatic multi-line arm, and against bare enum entries.
# A gate that catches one spelling of a defect catches no defect.
: > "$TMP_D02"
# `--untracked`: `git grep` reads only staged content, so a new screen with a conflated arm is
# invisible until somebody adds it. See the note in `check-destructive.sh`.
for f in $(git grep -lE --untracked 'OPEN_APP_SETTINGS' -- 'app/src/main/**/*.kt' 2>/dev/null || true); do
  awk '
    /OPEN_APP_SETTINGS/ && /->/ {
      arm = $0
      # the arm and the two lines after it, since a body can wrap
      getline nxt1; getline nxt2
      body = arm nxt1 nxt2
      # Case-insensitive, and BOTH names the system page is reached by: openAppSettings, the
      # method on PermissionRequester and the lambda that forwards to it, and startAppSettings,
      # the Context extension it delegates to, which is the only route a screen holding no
      # requester has. The vocabulary was one name because only one existed when this was
      # written, so the gate reded on a correct arm in SettingsScreen — which is how a gate
      # teaches people to switch it off rather than obey it.
      # (No apostrophes in this block: the awk program is single-quoted, and one ends it.)
      if (tolower(body) !~ /openappsettings/ && tolower(body) !~ /startappsettings/)
        printf "%s:%d: %s\n", FILENAME, FNR-2, arm
      next
    }
  ' "$f" >> "$TMP_D02"
done
# Self-test: a planted arm that routes the system page to the app's own screen must be seen.
CAN=$(mktemp -d); trap 'rm -rf "$CAN"' EXIT
printf '%s\n' '                        UiAction.OPEN_APP_SETTINGS -> onSettings()' > "$CAN/p.kt"
printf '%s\n' '                        else -> Unit' >> "$CAN/p.kt"
printf '%s\n' '                    }' >> "$CAN/p.kt"
# And both correct spellings, because a gate that reds on correct code gets switched off — which
# is the failure mode a canary for the DEFECT alone cannot see.
printf '%s\n' '                        UiAction.OPEN_APP_SETTINGS -> onOpenAppSettings()' > "$CAN/ok1.kt"
printf '%s\n' '                    }' >> "$CAN/ok1.kt"
printf '%s\n' '                    }' >> "$CAN/ok1.kt"
printf '%s\n' '                        UiAction.OPEN_APP_SETTINGS -> {' > "$CAN/ok2.kt"
printf '%s\n' '                            if (!appContext.startAppSettings()) unavailable = true' >> "$CAN/ok2.kt"
printf '%s\n' '                        }' >> "$CAN/ok2.kt"
D02_SCAN='/OPEN_APP_SETTINGS/ && /->/ { arm=$0; getline n1; getline n2; b=tolower(arm n1 n2); if (b !~ /openappsettings/ && b !~ /startappsettings/) print }'
if [ "$(awk "$D02_SCAN" "$CAN/p.kt" | wc -l | tr -d ' ')" != "1" ]; then
  say "ERR:" "the conflation scanner did not see a planted arm — this scan proves nothing"
  exit 2
fi
if [ "$(cat "$CAN/ok1.kt" "$CAN/ok2.kt" | awk "$D02_SCAN" | wc -l | tr -d ' ')" != "0" ]; then
  say "ERR:" "the conflation scanner reds on a correct arm — it would be switched off, not obeyed"
  exit 2
fi
if [ -s "$TMP_D02" ]; then
  say "ERR:" "an OPEN_APP_SETTINGS arm does not reach openAppSettings — it cannot grant anything (D-02):"
  cat "$TMP_D02"
  FAIL=1
else
  say "ok:" "every OPEN_APP_SETTINGS arm reaches the system page (canary matched)"
fi

# I-04: `runBlocking` in production code parks the CALLING thread until the work finishes, and
# every caller of the one that existed was on `Dispatchers.Main.immediate`. `WhisperEngine.close()`
# used it to free the native context on the whisper thread — correct place, wrong wait — so
# changing the model while a transcription ran froze the whole app for the rest of it, past
# Android's five-second ANR limit.
#
# This is a gate rather than a review note because "every caller is off Main" is not checkable and
# "this string does not appear" is. `T-018` removed the only occurrence; the next one would come
# back silently.
#
# NO `\b`. POSIX ERE has no word-boundary escape, and `git grep -E '\brunBlocking\b'` therefore
# matches NOTHING while looking exactly like a working check — measured here on 2026-09-21 against
# a planted call, and it is the third time this project has been bitten by a scan whose silence
# meant "the pattern is broken" rather than "the tree is clean". The bare substring is also the
# right rule: a symbol whose name merely contains `runBlocking` is not something to allow either.
#
# Comment lines are skipped, and that is not a loophole but a requirement: the KDoc on
# `WhisperEngine.close()` and on `LocalWhisperOwner` explains at length what `runBlocking` did and
# why it is gone, and a gate that reds on the explanation of its own rule gets deleted by the next
# person in a hurry. `check-destructive.sh` learned the same thing on twenty lines of prose.
rb_scan() {  # $@ = roots. Prints "file:line:text" for every hit outside a comment.
  grep -rnE 'runBlocking' --include='*.kt' "$@" 2>/dev/null |
    grep -vE '^[^:]+:[0-9]+: *(\*|//|/\*)' || true
}
: > "$TMP_RB"
for root in app/src/main core-common/src/main core-notes/src/main feature-stt/src/main feature-vault/src/main; do
  [ -d "$root" ] || continue
  rb_scan "$root" >> "$TMP_RB"
done
# Self-test, with THE SAME FUNCTION and THE SAME PATTERN. The first version of this canary grepped
# a different string in a different file: it proved that `git grep` can find text, which was never
# in doubt, and said nothing about the pattern that mattered. A canary that does not exercise the
# check's own pattern is decoration.
CANARY_RB=$(mktemp -d); trap 'rm -rf "$CANARY_RB"' EXIT
printf 'fun planted() = runBlocking { 1 }\n' > "$CANARY_RB/Planted.kt"
printf ' * and this line only TALKS about runBlocking\n' >> "$CANARY_RB/Planted.kt"
printf '// so does this one: runBlocking\n' >> "$CANARY_RB/Planted.kt"
# Exactly one: the call, not the two comments. A canary that only proves the pattern matches would
# pass just as happily with the comment filter matching everything.
if [ "$(rb_scan "$CANARY_RB" | wc -l | tr -d ' ')" != "1" ]; then
  say "ERR:" "the runBlocking scan saw $(rb_scan "$CANARY_RB" | wc -l | tr -d ' ') hits in a file planted with exactly one call — it proves nothing"
  exit 2
fi
if [ -s "$TMP_RB" ]; then
  say "ERR:" "runBlocking in production code holds the calling thread, and every caller here is on Main (I-04):"
  cat "$TMP_RB"
  FAIL=1
else
  say "ok:" "no runBlocking in any main source set (canary matched, so the scan ran)"
fi

# B-159: a `viewModelScope.launch` whose body can throw needs a handler, or the exception has no
# owner.
#
# `viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate` and carries NO
# `CoroutineExceptionHandler`, so every coroutine launched into it is its own root: a throw that
# leaves the body fails no parent, reaches no `fold`, and reaches no person. On the headset that
# is a crash with nothing attached. In the suite it is worse than a red, because it is a red in
# the WRONG PLACE: `runTest` collects an uncaught coroutine exception against whichever test is
# running, so `SettingsViewModel.reload()` — four seams in one coroutine, called from `init` —
# failed four different cases across five runs and none of them was the test that built it.
#
# THE RULE: in `app/.../ui/*.kt`, `viewModelScope.launch` may appear only in `Guarded.kt`, which
# is where the catch, the log and the hand-off to the screen live. Everything else calls
# `launchGuarded` (or a view model's own named binding of it).
#
# **`launchIn(viewModelScope)` is deliberately NOT in this scan.** A flow's owner is its `.catch`
# operator rather than a surrounding try, so the same question has a different shape there and a
# rule written for `launch` would answer it wrongly. There is one such call in the tree
# (`NotesViewModel.kt`) and it carries a `.catch`; a second one is a finding, not a gate.
#
# Comment lines are skipped, for the reason `runBlocking`'s scan records: three KDocs here and in
# `Guarded.kt` explain at length what `viewModelScope.launch` did and why it is gone, and a gate
# that reds on the explanation of its own rule gets deleted by the next person in a hurry.
vms_scan() {  # $@ = files. Prints "file:line:text" for every bare launch outside a comment.
  grep -nE 'viewModelScope\.launch' "$@" 2>/dev/null |
    grep -vE '^([^:]+:)?[0-9]+: *(\*|//|/\*)' || true
}
TMP_VMS=$(mktemp)
CANARY_VMS=$(mktemp -d)
trap 'rm -f "$CANARY_FILE" "$TMP_D02" "$TMP_RB" "$TMP_VMS"; rm -rf "$CAN" "$CANARY_RB" "$CANARY_VMS"' EXIT
: > "$TMP_VMS"
for f in app/src/main/kotlin/ai/passioncode/fabricvr/ui/*.kt; do
  [ -e "$f" ] || continue
  case "$f" in
    */Guarded.kt) continue ;;   # the one file the rule exists to concentrate into
  esac
  vms_scan "$f" >> "$TMP_VMS"
done
# Self-test, with THE SAME FUNCTION: one bare launch, one wrapper call that must be left alone,
# and one comment that merely names the thing. Exactly one hit, or this scan proves nothing —
# a canary that only proved the pattern matches would pass just as happily with the comment
# filter matching everything, which is the failure `runBlocking`'s canary records above.
printf 'fun planted() = viewModelScope.launch { throw Exception() }\n' > "$CANARY_VMS/Planted.kt"
printf 'fun fine() = launchGuarded { throw Exception() }\n' >> "$CANARY_VMS/Planted.kt"
printf ' * and this line only TALKS about viewModelScope.launch\n' >> "$CANARY_VMS/Planted.kt"
printf '// so does this one: viewModelScope.launch\n' >> "$CANARY_VMS/Planted.kt"
vms_hits=$(vms_scan "$CANARY_VMS/Planted.kt" | wc -l | tr -d ' ')
if [ "$vms_hits" != "1" ]; then
  say "ERR:" "the launch scan saw $vms_hits hits in a file planted with exactly one bare launch — it proves nothing"
  exit 2
fi
if [ -s "$TMP_VMS" ]; then
  say "ERR:" "a viewModelScope.launch has no owner for what escapes it — use launchGuarded (B-159):"
  cat "$TMP_VMS"
  FAIL=1
else
  say "ok:" "every view-model coroutine goes through launchGuarded (canary matched, so the scan ran)"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: seam scan"
  exit 1
fi
echo "OK: seam scan"
exit 0
