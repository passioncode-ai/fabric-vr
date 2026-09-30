#!/usr/bin/env bash
# check-secrets.sh — refuse a commit that carries a credential.
#
# SCOPE: greps the tracked tree for key-shaped strings and for files that must never be tracked.
#   It does NOT check history, the build outputs, or whether a value is live.
# EXIT CODE IS THE OUTPUT: non-zero on any hit.
set -u
FAIL=0

say() { printf '%-8s %s\n' "$1" "$2"; }

# 1. Key shapes, in tracked files only.
#
# Two paths are exempt and no more: this script, which spells the shapes out, and the audit reports,
# which quote them as findings. Exempting all of docs/ — as this did until 2026-09-19 — meant a key
# pasted into a runbook, a plan or a handoff passed the gate, and those are exactly the documents a
# key gets pasted into.
#
# `git grep` rather than `git ls-files | xargs grep`: on a machine where `grep` is ugrep, `-z` means
# *decompress* instead of *NUL-separated*, so the old pipeline handed xargs one mangled blob, every
# grep failed into `|| true`, and the gate printed "ok" having read nothing. Measured 2026-09-19 by
# planting ten credential shapes: all ten passed. git grep needs no flavour and no file list.
PATTERNS='sk-or-v1-[A-Za-z0-9]|sk-ant-[A-Za-z0-9]|AIza[0-9A-Za-z_-]{30}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
PATTERNS="$PATTERNS|hf_[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{60,}"
PATTERNS="$PATTERNS|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|glpat-[A-Za-z0-9_-]{20}|lin_api_[A-Za-z0-9]{20,}"
# Added 2026-09-20, after measuring: only two members of the `sk-` family were listed, so an
# OpenAI project key, a classic OpenAI key and a Stripe live key each passed this gate while
# tracked. The list was assembled from the credentials this project had HELD rather than from the
# ones it could be handed, which is the wrong axis — a key arrives in a paste, not in an inventory.
PATTERNS="$PATTERNS|sk-proj-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{32,}|sk_live_[A-Za-z0-9]{20,}"
PATTERNS="$PATTERNS|rk_live_[A-Za-z0-9]{20,}|xapp-[0-9]-[A-Za-z0-9-]{10,}|SG\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}"

# Self-test first. A gate that greps nothing prints the same "ok" as a gate that found nothing, and
# that is the whole failure above: the canary is what tells the two apart, every run.
CANARY="sk-""or-v1-0123456789abcdef0123456789abcdef"
if ! printf '%s\n' "$CANARY" | git grep -qE "$PATTERNS" --no-index --untracked -- - 2>/dev/null \
   && ! printf '%s\n' "$CANARY" | grep -qE "$PATTERNS"; then
  say "ERR:" "the pattern engine matches nothing — this scan proves nothing"
  exit 2
fi

# `--untracked`, and that word is the whole of rule 1's usefulness. This project's own commit
# procedure is `check-all.sh` -> `git add -A` -> `git commit`, so at the moment the gate runs, a
# file an agent has just written is NOT yet tracked — and a scan of tracked files only cannot see
# the one file that is about to be committed. Measured 2026-09-20: a known-covered shape in a new
# file passed, and was caught the instant the same file was staged. `--untracked` honours
# `.gitignore`, so `local.properties`, `keystore.properties`, `.env` and every build output stay
# out of it; those are rule 2's, and they are supposed to hold secrets.
HITS=$(git grep -nIE --untracked "$PATTERNS" -- \
  ':!scripts/check-secrets.sh' ':!docs/evidence/audits/' 2>/dev/null || true)
if [ -n "$HITS" ]; then
  say "ERR:" "a key-shaped string is in the working tree:"
  printf '%s\n' "$HITS"
  FAIL=1
else
  say "ok:" "no key-shaped strings, tracked or untracked (canary matched, so the scan ran)"
fi

# 2. Files that must never be tracked.
for f in local.properties keystore.properties .env; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    say "ERR:" "$f is tracked and must not be"
    FAIL=1
  fi
done
say "ok:" "local.properties, keystore.properties and .env are untracked"

# 2b. **Signing material, by pattern rather than by name** — `T-039`, `DEC-0049`.
#
# The Android signing key is the only artefact in this project with **no recovery path**: once a
# signed build is on a headset, losing the key means every future upgrade is an uninstall that
# takes `filesDir` with it, permanently, for everyone who has the app. And the shortest route from
# `keystore.properties.sample` to a working build used to put that file **inside the repository** —
# the sample said "paths are relative to the repository root", and neither `.gitignore` nor this
# gate mentioned a keystore at all.
#
# By pattern, because the file name is the one thing a person picks freely. `git ls-files` rather
# than a working-tree scan: an untracked keystore beside the worktree is the intended state and
# `.gitignore` covers it; what must never happen is one being TRACKED.
TRACKED_SIGNING=$(git ls-files -- '*.keystore' '*.jks' '*.p12' '*.pepk' 'release-lineage.bin' 2>/dev/null || true)

# The self-test runs the same enumeration over a temp repository, because `git ls-files` with a
# pathspec that matches nothing prints exactly what a clean tree prints. A canary for a PATTERN
# alone would not have caught the dead word-boundary that made the destructive-command gate inert,
# and this is the same shape.
SIGNING_CANARY=$(mktemp -d)
trap 'rm -rf "$SIGNING_CANARY"' EXIT
( cd "$SIGNING_CANARY" && git init -q . && : > planted.keystore && git add -f planted.keystore ) >/dev/null 2>&1
SIGNING_SEEN=$( (cd "$SIGNING_CANARY" && git ls-files -- '*.keystore' '*.jks' '*.p12' '*.pepk' 'release-lineage.bin') | wc -l | tr -d ' ')
if [ "$SIGNING_SEEN" != "1" ]; then
  say "ERR:" "the signing-material enumeration saw $SIGNING_SEEN of 1 planted file — this scan proves nothing"
  exit 2
fi

if [ -n "$TRACKED_SIGNING" ]; then
  say "ERR:" "signing material is tracked, and there is no recovery if it leaks or is lost:"
  printf '%s\n' "$TRACKED_SIGNING"
  FAIL=1
else
  say "ok:" "no signing material is tracked (canary matched, so the scan ran)"
fi

# 3. The app must not log the key: no logging call may take the settings value directly.
if git grep -nE 'Log[.2]*\.[iwe]\(.*(apiKey|API_KEY|openrouter_api_key)' -- '*.kt' >/dev/null 2>&1; then
  say "ERR:" "a logging call names the API key"
  FAIL=1
else
  say "ok:" "no logging call names the API key"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: secret scan"
  exit 1
fi
echo "OK: secret scan"
