#!/usr/bin/env bash
# exposure.sh — how stale is what this project believes it has verified.
#
# `docs/evidence/verification.md` answers *"what do we actually know is true?"*, and every row in
# it is true **about the tree it observed**. It spends a whole section defining four staleness
# states and delegates computing them to this file. This file did not exist (`F-15`), so the four
# states had never been computed once and no row had ever been marked `behind` despite every row
# being behind. A citation to a script that does not exist survived a green gate for the life of
# that document.
#
# **THIS IS A DISCLOSURE, NOT A GATE.** No floor, no direction, never a target — for the same
# reason the `Human` column has none. It exits 0 whatever it finds. The moment `behind` becomes a
# number to avoid printing, rows stop being re-observed and start being re-worded.
#
# It exits NON-ZERO for exactly one reason: it could not read the ledger. A staleness tool that
# reports `behind 0` because it parsed nothing is the defect it exists to close, wearing the
# answer it exists to give.
#
#   scripts/exposure.sh            the four states and a total
#   scripts/exposure.sh --detail   one line per row
#   LEDGER=<path> scripts/exposure.sh   against another file — which is how `scripts/selftest.sh`
#                                       runs the planted cases
set -eu

LEDGER=${LEDGER:-docs/evidence/verification.md}
MDTABLE=${MDTABLE:-tools/mdtable.awk}
DETAIL=0
# **An unknown argument is refused, not ignored.** `--deatil` used to print the summary and exit
# 0, so a CI step with a typo'd flag reported success having printed half of what was asked for —
# the same silent-half-answer this script exists to prevent one level down.
case "${1:-}" in
  '')        ;;
  --detail)  DETAIL=1 ;;
  *)         echo "exposure: unknown argument '$1' — expected --detail or nothing"; exit 2 ;;
esac

[ -f "$LEDGER" ] || { echo "exposure: no ledger at $LEDGER"; exit 2; }

# **Parsed by `tools/mdtable.awk`, and the header this replaces was half right.**
#
# It argued — correctly — that splitting a row and counting cells from the RIGHT drops any row
# whose trailing note contains a `|`, which notes here do. It then concluded that counting from
# the LEFT was therefore safe. It is not: a pipe in `What`, `Run` or `Shipped in` shifts every
# cell to its right, and `$6` lands on `Shipped in` instead of `Observed at`. Measured: a row so
# shaped reported `current` while it was **20 commits behind**. That is worse than a dropped row,
# because the summary line still reads as a measurement.
#
# The splitter understands an escaped `\|` and a pipe inside a code span, so neither end of the
# row is a hazard. Columns: 1 REQ · 2 What · 3 Run · 4 Shipped in · 5 Observed at · 6 Environment ·
# 7 Auto · 8 Human · 9 Note.
#
# It also **skips fenced blocks**, which the old parser did not: this file's own staleness section
# pastes a sample `| REQ-999 | … |` row to show the output format, and counting it would have made
# an example into a verified requirement.
[ -f "$MDTABLE" ] || { echo "exposure: $MDTABLE is missing and this script cannot parse without it"; exit 2; }

# The program goes to a file first. **Not into `$(awk … <<EOF)`**: a fence pattern contains three
# backticks, and bash parses those as a command substitution while it is still looking for the
# end of the outer `$( … )` — the heredoc quoting has not applied yet. The failure is a syntax
# error at EOF, which at least is loud.
PROG=$(mktemp -t exposure.XXXXXX) || { echo "exposure: cannot create a temporary file"; exit 2; }
trap 'rm -f "$PROG"' EXIT
{
  printf '%s\n' '/^[ \t]*(```|~~~)/ { infence = !infence; next }'
  printf '%s\n' 'infence { next }'
  printf '%s\n' '/^[ \t]*\|[ \t]*REQ-[0-9]/ {'
  printf '%s\n' '  n = split_row($0, c)'
  printf '%s\n' '  if (n != 9) { printf "!SHAPE\t%s\t%d\n", bare(c[1]), n; next }'
  printf '%s\n' '  printf "%s\t%s\n", bare(c[1]), bare(c[5])'
  printf '%s\n' '}'
} > "$PROG"

rows=$(awk -f "$MDTABLE" -f "$PROG" "$LEDGER")

# A row this parser cannot address is REFUSED, not skipped. The script's whole contract is that it
# never reports a number it did not measure, and a silently dropped row is exactly that.
if printf '%s\n' "$rows" | grep -q '^!SHAPE'; then
  echo "exposure: $LEDGER has rows this parser cannot address:"
  printf '%s\n' "$rows" | awk -F'\t' '$1 == "!SHAPE" { printf "  %s has %s cells, expected 9\n", $2, $3 }'
  exit 2
fi

if [ -z "$rows" ]; then
  echo "exposure: $LEDGER has no REQ rows this script can read — refusing to report zero"
  exit 2
fi

head=$(git rev-parse HEAD 2>/dev/null || echo "")
[ -n "$head" ] || { echo "exposure: not in a git work tree"; exit 2; }

current=0; behind=0; unresolvable=0; unanchored=0
# Real characters, not the two-character sequences `\t` and `\n` waiting for `printf %b` to
# expand them. That expansion is what let ledger content garble the listing; building the string
# with the characters themselves means `%s` can print it verbatim.
TAB=$(printf '\t')
NL=$(printf '\nx'); NL=${NL%x}
detail=""

# A here-string rather than a pipe: a `while` in a pipeline runs in a subshell and every count
# below would be discarded at the closing `done`. That is the same class of silent zero.
while IFS="$(printf '\t')" read -r req obs; do
  [ -n "$req" ] || continue
  # `bare()` in the splitter already stripped backticks and asterisks, so `**—**` and `—` are the
  # same answer here. They were not before: a bolded em-dash fell through to the commit lookup and
  # was reported `unresolvable` — "the commit does not resolve here" — about a row that names no
  # commit at all. Two materially different statements, one of them false.
  case "$obs" in
    ''|'—'|'-'|'pending'|'n/a')
      unanchored=$((unanchored + 1))
      detail="$detail$req${TAB}unanchored${TAB}(no commit)$NL"
      continue
      ;;
  esac
  if ! git cat-file -e "${obs}^{commit}" 2>/dev/null; then
    unresolvable=$((unresolvable + 1))
    detail="$detail$req${TAB}unresolvable${TAB}$obs$NL"
    continue
  fi
  n=$(git rev-list --count "${obs}..${head}" 2>/dev/null || echo "")
  if [ -z "$n" ]; then
    unresolvable=$((unresolvable + 1))
    detail="$detail$req${TAB}unresolvable${TAB}$obs$NL"
  elif [ "$n" -eq 0 ]; then
    current=$((current + 1))
    detail="$detail$req${TAB}current${TAB}$obs$NL"
  else
    behind=$((behind + 1))
    detail="$detail$req${TAB}behind $n${TAB}$obs$NL"
  fi
done <<EOF
$rows
EOF

# **Zero prints out loud.** `current 12 · behind 0 · …` is a measurement; printing nothing when
# everything is fresh is what makes freshness indistinguishable from a check that never looked.
echo "current $current · behind $behind · unresolvable $unresolvable · unanchored $unanchored  (of $((current + behind + unresolvable + unanchored)) rows, against $(git rev-parse --short HEAD))"

if [ "$DETAIL" -eq 1 ]; then
  # `printf '%s'`, never `%b`. `%b` expands backslash escapes that came out of the LEDGER, so an
  # `Observed at` cell containing a Windows path printed a literal tab and split one row across
  # two lines — a detail listing garbled by its own input, in the mode CI runs.
  printf '%s' "$detail" | awk -F'\t' 'NF{printf "  %-10s %-14s %s\n", $1, $2, $3}'
fi
exit 0
