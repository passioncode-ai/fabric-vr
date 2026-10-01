#!/usr/bin/env bash
# A change to the instrumented suite must come with a run of it, or with a ledger row saying it
# was not run and why.
#
# CI cannot run these tests: the APK is arm64-only, and they need Horizon OS, the Meta Spatial SDK
# runtime and a 190 MB model on the device. What CI can do is refuse to let a change to that suite
# pass unrecorded — which is exactly the failure F-03 recorded, where two instrumented tests were
# red for three commits while the handoff claimed thirteen green.
#
# EXIT CODE IS THE OUTPUT.
set -eu
LEDGER=docs/evidence/device-gate.md
BASE="${1:-origin/main}"

if ! git rev-parse --verify "$BASE" >/dev/null 2>&1; then
  echo "ok: $BASE is not available here, nothing to compare against"
  exit 0
fi

changed=$(git diff --name-only "$BASE"...HEAD -- '*/src/androidTest/**' 2>/dev/null || true)
if [ -z "$changed" ]; then
  echo "ok: no instrumented sources changed since $BASE"
  exit 0
fi

if [ ! -f "$LEDGER" ]; then
  echo "ERR: instrumented sources changed and $LEDGER does not exist:"
  echo "$changed" | sed 's/^/       /'
  exit 1
fi

# The newest row's commit. A row whose commit is an ancestor of HEAD describes a run of code that
# is in this history; one that is not describes a run of something else.
# Read the SECOND COLUMN OF A TABLE ROW, not "the last backticked hex in the file". The loose
# version took its answer from prose: adding a paragraph that mentions a commit silently changed
# what the gate certified. Measured 2026-09-20 while writing the paragraph three lines below the
# table that explains this.
# The NEWEST row by git history, not the last line in the file. Those are two different claims,
# and the difference bites: a row appended out of order failed this gate on 2026-09-21 while
# describing a run that had genuinely happened. The ledger is append-only, so in practice the rows
# are chronological — but "in practice" is what a check is for.
newest=""
newest_at=0
for sha in $(awk -F'|' '/^\| *[0-9]{4}-[0-9]{2}-[0-9]{2} *\|/ { gsub(/[ `]/, "", $3); if ($3 ~ /^[0-9a-f]{7,40}$/) print $3 }' "$LEDGER"); do
  at=$(git log -1 --format=%ct "$sha" 2>/dev/null || echo 0)
  if [ "$at" -gt "$newest_at" ]; then newest_at=$at; newest=$sha; fi
done
if [ -z "$newest" ]; then
  # **Two different findings, and this printed one sentence for both.** A ledger with no commit
  # in any row records nothing. A ledger whose rows all name commits this clone cannot find
  # records a past nobody here can follow — which is every row of this one since the repository
  # was re-published with a new root on 2026-09-30 (`DEC-0101`). The remedy is the same, a new
  # row for this change, but "records nothing" about thirty rows of history is false.
  named=$(awk -F'|' '/^\| *[0-9]{4}-[0-9]{2}-[0-9]{2} *\|/ { gsub(/[ `]/, "", $3); if ($3 ~ /^[0-9a-f]{7,40}$/) n++ } END { print n + 0 }' "$LEDGER")
  if [ "$named" -gt 0 ]; then
    echo "ERR: no commit named by the $named dated rows of $LEDGER resolves in this clone — they are the"
    echo "     pre-publication history (DEC-0101), or a history this checkout does not have. No row can"
    echo "     certify these instrumented changes:"
    echo "$changed" | sed 's/^/       /'
    echo "     Run the suite on a headset and append a row naming a commit in this history, or append"
    echo "     a row saying it was not run and why. $LEDGER is append-only."
  else
    echo "ERR: $LEDGER has no commit in any row, so it records nothing"
  fi
  exit 1
fi

# Self-test, before the verdict. `--is-ancestor` answering "yes" to a pair it must refuse would
# make every check below print ok while reading nothing — the same shape as a grep that matched
# no pattern. HEAD cannot be an ancestor of its own parent.
canary_parent=$(git rev-parse --verify "HEAD^" 2>/dev/null || echo "")
if [ -n "$canary_parent" ] && git merge-base --is-ancestor HEAD "$canary_parent" 2>/dev/null; then
  echo "ERR: git merge-base --is-ancestor answered yes to an impossible pair — this check proves nothing"
  exit 2
fi

# WHICH COMMIT THE ROW MUST CERTIFY. Being an ancestor of HEAD is not enough and was the whole
# defect: the ledger's seed row cites a commit that is permanently an ancestor, so from the day it
# was written this gate printed ok for every future change to the instrumented suite, for ever,
# with nobody running anything. Measured 2026-09-20 — verbatim the F-03 failure the ledger exists
# to prevent, running in CI on every push. A row certifies a run; a run certifies the code it ran
# against; so the row's commit must CONTAIN the change it is being offered as proof of.
changed_at=$(git log -1 --format=%H "$BASE..HEAD" -- '*/src/androidTest/**' 2>/dev/null || true)
if [ -z "$changed_at" ]; then
  changed_at=$(git log -1 --format=%H -- '*/src/androidTest/**' 2>/dev/null || true)
fi

# WHAT THE ROW SAYS HAPPENED. Being the right commit is not the same as being a run, and this
# gate printed one word for both: on 2026-09-21 it answered
# `ok: … newest device-gate row (ac4c29e) contains the change` over a row whose Result cell reads
# **not run**, and six of the last seven rows read that. An audit called it an acknowledgement
# gate wearing an execution gate's verdict, which is precisely right.
#
# **It still exits 0 for an acknowledgement, and that is `DEC-0066`, not laziness.** The ledger's
# own contract is "run the suite and append a row, OR append a row saying it was not run and
# why", and the operator has put the headset session last: a hard red here would stop every
# agent task on a device nobody is holding. What changes is the SENTENCE — an acknowledgement
# says so, in capitals, with the number of tests nobody has executed beside it, so no reader and
# no commit message can quote this line as evidence of a run. The strict form (only an executed
# row passes) belongs to the day the suite is green on a headset; it is a board row, not a
# silent intention.
#
# The Result cell is read with `tools/mdtable.awk` (`SI-10`): a cell here carries backticks and
# the occasional pipe, and a positional `-F'|'` read of the fifth column is the exact defect the
# library exists for. A row this cannot address is REFUSED rather than assumed green.
#
# `mdawk` appends the program to the library and runs the pair, because `awk -f lib 'prog'` reads
# `prog` as a FILENAME — the first version of this did exactly that, printed nothing, and the
# gate refused every row as unreadable. A parser that fails closed is the right failure, and it
# still has to work.
MDAWK_TMP=$(mktemp -t fabricvr-devgate-XXXXXX)
trap 'rm -f "$MDAWK_TMP"' EXIT
mdawk() {
  _prog=$1; shift
  cat tools/mdtable.awk > "$MDAWK_TMP"
  printf '%s\n' "$_prog" >> "$MDAWK_TMP"
  awk -f "$MDAWK_TMP" "$@"
}

result_of_row() {
  mdawk '
    /^[ \t]*\| *[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] *\|/ {
      n = split_row($0, c)
      if (n < 6) { print "UNREADABLE"; exit }
      sha = bare(c[2])
      if (sha != want) next
      # NOT `bare()` here: it strips spaces as well as backticks and asterisks, so **not run**
      # arrives as `notrun` and every pattern written the way a person reads the ledger misses
      # it. The cell is decoration-stripped and whitespace-squeezed instead, which keeps the
      # word boundary the Result column is written with.
      r = c[5]
      gsub(/[`*]/, "", r)
      gsub(/^[ \t]+|[ \t]+$/, "", r)
      gsub(/[ \t]+/, " ", r)
      print (r == "" ? "UNREADABLE" : r)
      exit
    }
  ' -v want="$1" "$LEDGER"
}

# The newest row that says something RAN — the number an acknowledgement is measured against.
# Prints the date, the commit and the result so the distance is readable without opening the file.
last_executed_row() {
  mdawk '
    /^[ \t]*\| *[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] *\|/ {
      n = split_row($0, c)
      if (n < 6) next
      r = c[5]; gsub(/[`*]/, "", r); gsub(/^[ \t]+|[ \t]+$/, "", r); gsub(/[ \t]+/, " ", r)
      # An EXECUTION is the shape the ledger itself writes — "<n> run, <m> red" — and nothing
      # else. No apostrophe in this comment on purpose: it sits inside a single-quoted awk
      # program, which is a self-test case in this repository for a reason. The first
      # version excluded /not run/ and called everything left an execution — which promoted
      # "correction, not a run" to the last executed row, in the very line that exists to say
      # how far away the last real one is.
      if (r !~ /[0-9]+ run/) next
      last = bare(c[1]) " " bare(c[2]) " — " r
    }
    END { print (last == "" ? "none in the ledger at all" : last) }
  ' "$LEDGER"
}

if git merge-base --is-ancestor "$newest" HEAD 2>/dev/null &&
   { [ -z "$changed_at" ] || git merge-base --is-ancestor "$changed_at" "$newest" 2>/dev/null; }; then
  result=$(result_of_row "$(git rev-parse --short "$newest")")
  [ -n "$result" ] || result=$(result_of_row "$newest")
  instrumented=$(grep -rhE '^[[:space:]]*@Test([^[:alnum:]_]|$)' -- */src/androidTest 2>/dev/null | wc -l | tr -d ' ')
  case "$result" in
    UNREADABLE|"")
      echo "ERR: the newest $LEDGER row ($newest) has no readable Result cell, so it certifies nothing."
      echo "     A row this gate cannot address is refused rather than assumed green (SI-10)."
      exit 1
      ;;
    # A digit immediately before "run" — the ledger writes an execution as "13 run, 0 red". A
    # bare `*" run"*` matches **not run** as well, which would have put this whole section back
    # where it started.
    *[0-9]" run"*)
      echo "ok: instrumented sources changed; newest device-gate row ($newest) contains the change ($(git rev-parse --short "${changed_at:-HEAD}")) and RAN: $result"
      exit 0
      ;;
    *)
      echo "ok: instrumented sources changed; the newest device-gate row ($newest) is an ACKNOWLEDGEMENT"
      echo "    — its Result reads \"$result\" — so this gate certifies that the absence was declared,"
      echo "    NOT that anything ran. $instrumented instrumented tests exist; the last EXECUTED row is"
      echo "    $(last_executed_row)."
      exit 0
      ;;
  esac
fi

if [ -n "$changed_at" ] && git merge-base --is-ancestor "$newest" HEAD 2>/dev/null; then
  echo "ERR: the newest device-gate row ($newest) PREDATES the instrumented change it is offered as proof of"
  echo "       change:  $(git rev-parse --short "$changed_at")"
  echo "       newest row: $(git rev-parse --short "$newest")"
  echo "     Run the suite on a headset against this commit and append a row, or append a row saying"
  echo "     it was not run and why. $LEDGER is append-only."
  exit 1
fi

echo "ERR: these instrumented sources changed since $BASE:"
echo "$changed" | sed 's/^/       /'
echo "       and the newest $LEDGER row names $newest, which is not an ancestor of HEAD."
echo "       Run the suite on a headset and append a row, or append a row saying it was not run"
echo "       and which environment that was — see the file's own Environment vocabulary."
exit 1
