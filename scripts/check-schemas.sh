#!/usr/bin/env bash
# Every @Database version has an exported schema, committed in the change that created it.
#
# Room validates a database at open time against the schema it was compiled from. A version bump
# with no exported JSON means no migration can ever be TESTED from that version — and this project
# learned that the expensive way: `exportSchema` was `false` when v1 shipped, so `1.json` did not
# exist, so no test could start from a v1 device, so `MIGRATION_1_2` was never once executed. It
# deletes rows. `T-027` reconstructed the file out of git history; there is no second chance to
# reconstruct one whose entities have since changed.
#
# A CI step nobody can explain gets deleted the first time it is inconvenient, so the reason is
# here and the rule is a DEC.
#
# EXIT CODE IS THE OUTPUT.
set -eu
FAIL=0
say() { printf '%-8s %s\n' "$1" "$2"; }

DB=core-notes/src/main/kotlin/ai/passioncode/fabricvr/notes/db/NotesDatabase.kt
DIR=core-notes/schemas/ai.passioncode.fabricvr.notes.db.NotesDatabase

if [ ! -f "$DB" ]; then
  say "ERR:" "$DB is missing — this check cannot read the version it is about"
  exit 2
fi

V=$(grep -oE 'version = [0-9]+' "$DB" | grep -oE '[0-9]+' | head -1)
if [ -z "${V:-}" ]; then
  say "ERR:" "no 'version = N' found in $DB — the pattern this check reads has moved"
  exit 2
fi

# Self-test before the verdict: a version the tree does not have must be reported missing, or the
# loop below proves nothing. `check-destructive.sh` explains why a canary that walks a different
# path is worthless; this one walks the same `test -f`.
if [ -f "$DIR/999.json" ]; then
  say "ERR:" "a schema for version 999 exists — this check's canary cannot run"
  exit 2
fi

for n in $(seq 1 "$V"); do
  if [ ! -f "$DIR/$n.json" ]; then
    say "ERR:" "schema $n.json is missing — export it in the change that created version $n"
    FAIL=1
  fi
done

# The second half: KSP may have just regenerated a schema that differs from the committed one.
# Run this AFTER the build, so what it compares is what the build produced.
if ! git diff --quiet -- "$DIR" 2>/dev/null; then
  say "ERR:" "the exported schemas changed and were not committed:"
  git diff --name-only -- "$DIR" | sed 's/^/         /'
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: exported-schema check"
  exit 1
fi
say "ok:" "every schema version 1..$V is exported and committed (canary: 999.json is absent)"
echo "OK: exported-schema check"
exit 0
