#!/usr/bin/env bash
# check-destructive.sh — no script in this repository may destroy the person's data.
#
# `adb uninstall` takes `filesDir` with it: the notes database, the whole vault, the 190 MB model
# and every Keystore value, with no backup (`android:allowBackup="false"`) and, until `T-023`, no
# export of any kind. It is the single most expensive command available here, and the only reason
# anyone reaches for it is a signature change — which `T-037` shows is usually avoidable.
#
# WHY THIS SCRIPT RATHER THAN THE GREP T-037 SPECIFIES: that grep is `grep -c 'adb.*uninstall'` on
# one file. Measured 2026-09-20, it returns **0 with the violation present**, because the scripts
# here invoke adb through `"$ADB"` and the literal word never appears on those lines. It also
# checks one file, and `grep -c` exits 1 at zero — so wired into `check-all.sh` under `set -eu`,
# the PASSING case aborts the run.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

# `\b` is not a word boundary in POSIX ERE, which is what `git grep -E` speaks — so the second
# alternative matched nothing and a literal `adb uninstall` sailed through. Found by an
# independent verification pass, and it is the mirror image of the defect this script's own header
# criticises in the check it replaced.
PATTERN='(\$\{?ADB\}?|(^|[^A-Za-z0-9_./-])adb)[^|;&]*[[:space:]]uninstall'

# Self-test first. A pattern that matches nothing prints the same "ok" as a scan that found
# nothing — the failure `check-secrets.sh` records from 2026-09-19, when ten planted credentials
# all passed a gate that was reading a mangled blob.
# BOTH alternatives, and through `git grep` — the engine that does the scanning. The first
# version tested one alternative through system grep, a different engine, which is how a dead
# `\b` survived a canary that passed.
CANARY_DIR=$(mktemp -d)
TMP_UNLABELLED=$(mktemp)
trap 'rm -rf "$CANARY_DIR" "$TMP_UNLABELLED"' EXIT
printf '%s\n' '"$ADB" uninstall ai.passioncode.fabricvr' > "$CANARY_DIR/a.sh"
printf '%s\n' 'adb uninstall ai.passioncode.fabricvr' > "$CANARY_DIR/b.sh"
hits=$( (cd "$CANARY_DIR" && git grep -hIE --no-index "$PATTERN" -- . 2>/dev/null) | wc -l | tr -d ' ')
if [ "${hits:-0}" -lt 2 ]; then
  say "ERR:" "the pattern matched $hits of 2 planted spellings — this scan proves nothing"
  exit 2
fi

# **A second canary, for the enumeration rather than the pattern.** The one above runs
# `--no-index` over a temp directory, so it proves the regex matches and says nothing at all about
# whether the real scan can SEE a new file. It could not: `git grep` reads staged content, and an
# untracked script sailed through this gate on 2026-09-21. A canary that exercises a different
# code path from the check is the shape this project keeps rediscovering, so this one plants a
# real untracked file where the real pathspec looks.
UNTRACKED_CANARY="scripts/.canary-destructive-$$.sh"
trap 'rm -rf "$CANARY_DIR" "$TMP_UNLABELLED"; rm -f "$UNTRACKED_CANARY"' EXIT
printf '%s\n' 'adb uninstall ai.passioncode.fabricvr' > "$UNTRACKED_CANARY"
seen=$(git grep -lIE --untracked "$PATTERN" -- 'scripts/*.sh' 2>/dev/null | grep -c "$(basename "$UNTRACKED_CANARY")" || true)
rm -f "$UNTRACKED_CANARY"
if [ "${seen:-0}" -ne 1 ]; then
  say "ERR:" "the scan cannot see an untracked script — a new file's violation would reach CI unseen"
  exit 2
fi

# `--untracked`, and it is not a nicety. Without it `git grep` reads only what has been STAGED,
# so a brand-new script carrying a violation is invisible to a local run and only reds in CI,
# after the push — and a new file is exactly where a new violation arrives. Measured 2026-09-21 by
# planting `adb uninstall` in an untracked script and watching this gate print ok.
# `check-secrets.sh` had it from the start; the other three did not.
HITS=$(git grep -nIE --untracked "$PATTERN" -- 'scripts/*.sh' ':!scripts/check-destructive.sh' 2>/dev/null || true)
if [ -n "$HITS" ]; then
  say "ERR:" "a script can uninstall the app, which destroys filesDir (H-23, G-01):"
  printf '%s\n' "$HITS"
  FAIL=1
else
  say "ok:" "no script uninstalls the app (canary matched, so the scan ran)"
fi

# In a DOCUMENT the command is not forbidden — the documents that explain the danger have to print
# it. What is forbidden is printing it unlabelled: T-023's and T-037's Definitions of Done both
# say `README.md` must say what it destroys beside every occurrence, and nothing checked. Scoping
# this as "forbidden everywhere" was tried and reds on twenty lines of prose warning about it,
# which is how a gate teaches people to switch gates off.
cat > "$TMP_UNLABELLED" <<'EOF'
EOF
git grep -nIE --untracked "$PATTERN" -- '*.md' ':!docs/evidence/audits/' 2>/dev/null | while IFS=: read -r file line _; do
  # Six lines each way and a wide vocabulary, because the point is that a reader meets the danger
  # near the command — not that the sentence uses one chosen verb. A window too narrow reds on
  # prose that is doing exactly what is wanted, which is how a gate gets switched off.
  lo=$(( line > 6 ? line - 6 : 1 ))
  window=$(sed -n "${lo},$((line + 6))p" "$file" 2>/dev/null)
  case "$window" in
    *destroy*|*DESTROY*|*delet*|*Delet*|*remov*|*Remov*|*wipe*|*filesDir*|*lose*|*lost*) ;;
    *) echo "$file:$line prints the command with no warning within six lines" >> "$TMP_UNLABELLED" ;;
  esac
done
if [ -s "$TMP_UNLABELLED" ]; then
  say "ERR:" "a document prints the command that destroys filesDir without saying so:"
  cat "$TMP_UNLABELLED"
  FAIL=1
else
  say "ok:" "every document that prints the uninstall says what it destroys"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: destructive-command scan"
  exit 1
fi
echo "OK: destructive-command scan"
exit 0
