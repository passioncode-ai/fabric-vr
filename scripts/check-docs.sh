#!/usr/bin/env bash
# check-docs.sh — the documentation gate for <project>.
#
# Seeded by task-pipeline (references/gates.md). IT IS YOURS NOW: extend it here,
# section by section. Each section is independent and removable.
#
# SCOPE: walks the markdown under docs/ (and this repository's own root .md files).
#   It does NOT check: prose meaning, whether a citation is the RIGHT one, code,
#   another repository's documents, or anything inside a fenced code block.
#   Read this header before quoting a green from here as evidence.
#
# EXIT CODE IS THE OUTPUT: non-zero on any failure. Nothing may run after the
#   VERDICT block at the bottom — a gate that appended a check after its verdict
#   printed FAIL and returned 0, and CI was green over it for an unknown period.
#
# PORTABLE to macOS bash 3.2: no grep -P, no sed -i, no readarray, no mapfile.
#
# PROGRESSIVE ARMING: a section whose input does not exist yet prints
#   "dormant: … — no <artefact> yet" and does NOT fail. Dormant is visible so it is
#   not forgotten, and green so a freshly seeded project does not start red.
#
# TWO REGISTER SHAPES, ONE CONTRACT: the project's decision home is either
#   docs/DECISIONS.md (ids DEC-####) or docs/adr/NNNN-slug.md (ids ADR-NNNN). Every
#   section below reads a NORMALISED INDEX built from whichever exists, so neither
#   shape is a second-class citizen. Having both is itself an error: one home per
#   project. Reading only one shape is how a fully populated ADR register sat behind
#   eight green "dormant" lines while a planted violation went uncaught.

set -u

FAIL=0
DOCS_DIR=${DOCS_DIR:-docs}
DEC_FILE=${DEC_FILE:-$DOCS_DIR/DECISIONS.md}
ADR_DIR=${ADR_DIR:-$DOCS_DIR/adr}
OQ_FILE=${OQ_FILE:-$DOCS_DIR/OPEN_QUESTIONS.md}
MAP_FILE=${MAP_FILE:-$DOCS_DIR/DOCMAP.md}
# The artifact root is RESOLVED, not assumed. It was renamed `superpowers` → `evidence`
# on 2026-08-13 (v1.53.0 made it resolvable, v0.46.0 moved the family), and this default
# still named the old one — so in every migrated project the SHA and propagation sections
# below found no corpus and went **dormant**, which reads exactly like having nothing to
# check. Measured 2026-08-16 on this skill's own repository. Prefer the new name, fall
# back to the old, and a project that has neither gets the dormant message it deserves.
if [ -z "${RETRO_GLOB:-}" ]; then
  if [ -d "$DOCS_DIR/evidence" ]; then RETRO_GLOB="$DOCS_DIR/evidence"
  elif [ -d "$DOCS_DIR/superpowers" ]; then RETRO_GLOB="$DOCS_DIR/superpowers"
  else RETRO_GLOB="$DOCS_DIR/evidence"
  fi
fi

# ---------- ratchets: a floor may only fall. Raising one is a decision. ----------
# THE TWO FLOORS ARE DIFFERENT KINDS. Mixing them up is why this is spelled out.
#
# PROP_FLOOR is an ID THRESHOLD, not a count. An entry whose number is >= the floor
#   must have propagated; everything older is a counted backlog that may only shrink.
#   ADOPTING THIS GATE IN AN EXISTING REPOSITORY MEANS SETTING IT TO THE NEXT FREE
#   ID: from today the rule binds, and the history becomes one printed number instead
#   of a thousand failures nobody will fix. Lower it as tranches are cleared.
PROP_FLOOR=${PROP_FLOOR:-0}        # id threshold — entries >= this must propagate
#
# RESIDUE_FLOOR is a COUNT: how many unmarked citations of retired decisions are
#   tolerated. On adoption set it to what the repository measurably has today, then
#   only ever lower it.
RESIDUE_FLOOR=${RESIDUE_FLOOR:-0}  # count — tolerated unmarked citations, today's number

TMP=$(mktemp -d 2>/dev/null || mktemp -d -t docgate)
trap 'rm -rf "$TMP"' EXIT

err()     { echo "ERR:     $*"; FAIL=1; }
ok()      { echo "ok:      $*"; }
skipmsg() { echo "skip:    $*"; }
dormant() { echo "dormant: $*"; }

# Strip what a reader never sees: fenced code blocks AND html comments. Sample
# content is not a claim about this repository. The comment half is not fussiness —
# a status line carrying `<!-- or: Superseded by ADR-0012 -->` made an entry read as
# retired and invented an undefined id, from one aside nobody renders.
# awk, because sed -i is not portable and this must run identically everywhere.
strip_asides() {
  awk '
    /^[ \t]*(```|~~~)/ { infence = !infence; print ""; next }
    infence { print ""; next }
    # A line describing a PLANTED defect quotes ids that were never meant to exist —
    # that is the payload of a negative self-test, written down so the incident can be
    # read later. It is sample content by the same argument as a fenced block, and the
    # checker cannot otherwise tell an id being USED from an id being DISCUSSED. Found
    # 2026-08-16: a retro entry recording `planted DEC-0009 while the highest defined id
    # was DEC-0001` was reported as citing an undefined decision.
    /planted/ { print ""; next }
    {
      line = $0
      # A comment carried over from an earlier line.
      if (incomment) {
        if (match(line, /-->/)) { line = substr(line, RSTART + RLENGTH); incomment = 0 }
        else { print ""; next }
      }
      # Comments that open and close on this line.
      while (match(line, /<!--.*-->/)) sub(/<!--.*-->/, "", line)
      # An opener with no closer: KEEP THE PREFIX. Dropping the whole line threw
      # away the "- **Status:** Accepted" that preceded the comment, and the entry
      # then had no status at all — the id vanished from the index and every
      # document citing it was reported as citing something undefined.
      if (match(line, /<!--/)) { line = substr(line, 1, RSTART - 1); incomment = 1 }
      print line
    }
  ' "$1"
}

# Every markdown file in scope, one per line.
find "$DOCS_DIR" -type f -name '*.md' 2>/dev/null | sort > "$TMP/files" || true
find . -maxdepth 1 -type f -name '*.md' 2>/dev/null | sort >> "$TMP/files" || true
FILE_COUNT=$(wc -l < "$TMP/files" | tr -d ' ')

if [ "$FILE_COUNT" = "0" ]; then
  echo "FAIL: documentation gate — no markdown found in $DOCS_DIR/ or the repository root."
  echo "      Seed the doc map and the registers first (task-pipeline stage 0, phase 1b),"
  echo "      or point DOCS_DIR at wherever this project keeps its documentation."
  exit 1
fi

# Fence-stripped copies, addressed by a flattened path.
while IFS= read -r f; do
  [ -f "$f" ] || continue
  flat=$(echo "$f" | tr '/' '_')
  strip_asides "$f" > "$TMP/s_$flat"
done < "$TMP/files"

flat_of() { echo "$TMP/s_$(echo "$1" | tr '/' '_')"; }



# **One markdown-table splitter, used by every section that reads a register.** `tools/mdtable.awk`
# carries the reasoning; the short version is that `awk -F'|'` splits on a pipe inside a code span
# or an escaped `\|`, shifting every cell to its right, and five parsers written in one day were
# each wrong that way — silently, because a shifted row fails the state match and is skipped
# rather than reported.
#
# `mdawk '<program>' <file>` concatenates the splitter and the program into one script. **Two
# `-f` flags is not the alternative**: `awk -f lib.awk '<program>' file` treats the program TEXT
# as a FILENAME, which is how the first attempt at this helper silently read zero rows and
# printed a clean answer — the same failure, one level up.
# `nofence <file>` — the same file with fenced code blocks blanked, and **nothing else removed.**
#
# `strip_asides` is the right tool for a check that reads IDS, and the wrong one for a check that
# reads PROSE: among other things it blanks any line containing the word "planted", deliberately,
# so that a retro entry describing a planted `DEC-0009` is not reported as citing an undefined
# decision. That rule is line-granular, and this project writes about planted defects constantly —
# `docs/modules/core-notes.md:59` states its test count in the same sentence as "A planted defect
# in the FTS sync was watched", so the count check reading `flat_of` saw no claim at all and
# reported the claim as missing. A gate that erases the sentence it is checking is a gate that
# will be read as broken and switched off.
#
# Fence-stripping alone is what a count check needs: a number in a sample block is an example, a
# number in prose is a claim.
nofence() {
  awk '
    /^[ \t]*(```|~~~)/ { infence = !infence; print ""; next }
    infence { print ""; next }
    { print }
  ' "$1"
}

# **The current handoff has exactly one definition in this script**: the file `README.md` links
# in the handoff directory. Not a name convention and not a date sort — the README is where a
# reader actually starts, so it is the definition rather than a hint. Computed here, above every
# section that needs it, because two sections computing it two ways is how §12 and §20 came to
# disagree while both looked right.
#
# Read from the fence-stripped copy: a handoff path inside a README code sample is an example,
# and counting it would red the gate for a correct README.
# `nofence`, not `flat_of`: the discovered file list holds `./README.md` and `flat_of README.md`
# names a different temporary file, so the first version of this read an empty copy and reported
# that the README names **zero** handoffs — a gate failing for a reason unrelated to what it
# checks, which is the shape that gets a gate switched off.
CURRENT_HANDOFF=$(nofence README.md 2>/dev/null | grep -oE 'docs/handoff/[A-Za-z0-9._-]+\.md' | sort -u || true)
CURRENT_HANDOFF_N=$(printf '%s\n' "$CURRENT_HANDOFF" | grep -c . || true)
# **Computed here and not twenty lines earlier**, which is where it was: `nofence` is defined
# below that point, and a shell function used before its definition is not an error — it is an
# empty result. The gate reported that the README names **zero** handoffs, for a reason that had
# nothing to do with handoffs.

MDTABLE="tools/mdtable.awk"
mdawk() {
  _prog=$1; shift
  cat "$MDTABLE" > "$TMP/mdawk.awk"
  printf '%s\n' "$_prog" >> "$TMP/mdawk.awk"
  awk -f "$TMP/mdawk.awk" "$@"
}

# ---------- 0. the decision home — exactly one, and which shape ----------
# entries: ID <TAB> FILE <TAB> STATUS-LINE      edges: SRC <TAB> MARKER <TAB> TARGET
# conseq:  ID <TAB> DOC
: > "$TMP/entries"; : > "$TMP/edges"; : > "$TMP/conseq"
SHAPE="none"; ID_PREFIX=""

have_reg=0; [ -f "$DEC_FILE" ] && have_reg=1
have_adr=0
if [ -d "$ADR_DIR" ]; then
  find "$ADR_DIR" -type f -name '[0-9][0-9][0-9][0-9]-*.md' 2>/dev/null | sort > "$TMP/adrfiles"
  [ -s "$TMP/adrfiles" ] && have_adr=1
fi

if [ "$have_reg" = "1" ] && [ "$have_adr" = "1" ]; then
  err "two decision homes: $DEC_FILE and $ADR_DIR both hold entries — one project, one register (references/documentation.md)"
fi

# Pull the six owed fields out of one entry body on stdin, for id $1 in file $2.
harvest_entry() {  # id file  (body on stdin)
  # The `Consequences` arm below reads AHEAD to gather a wrapped field, and the line that stops
  # it is the next field's own opener. Consuming it would silently drop a `Supersedes:` that
  # happens to follow — so the outer loop reads through `_replay`, and the lookahead hands back
  # whatever it did not use.
  _id=$1; _file=$2
  _replay=""
  while :; do
    if [ -n "$_replay" ]; then line=$_replay; _replay=""
    else IFS= read -r line || break
    fi
    case "$line" in
      *'Status:'*)
        grep -q "^$_id	" "$TMP/entries" 2>/dev/null || \
          printf '%s\t%s\t%s\n' "$_id" "$_file" "$line" >> "$TMP/entries" ;;
      *'Consequences / affects:'*)
        # **The field is read WHOLE, and the list ends where the prose begins** (`B-227`).
        #
        # Two bugs lived here and both printed `backlog: 0` over what they had not read. The loop
        # is line-by-line and this field wraps, so 63 of 263 targets sat on a continuation line
        # and were never harvested; and `case "$doc" in *.md)` dropped every non-markdown target
        # — 83 of them — so a decision naming `app/build.gradle.kts` could never be checked
        # against it at all. Twenty-seven documents named as needing to change did not cite their
        # decision, `docs/modules/app.md` among them, named by two decisions that both missed it.
        # That is this project's characteristic failure, in its own gate: a verdict printed over
        # rows nobody read.
        #
        # **Where the list stops matters as much as where it starts.** Entries are written as a
        # comma-separated list and then, often, a sentence of prose — and that prose names files
        # too (`DEC-0029` discusses "`T-013.md`'s correction"). Harvesting the whole continuation
        # made a prose mention into an obligation and reported a missing file that nothing is
        # obliged to have. The list therefore ends at the first sentence terminator, which is how
        # every entry in the register is actually punctuated.
        #
        # A non-`.md` target is recorded separately and checked for EXISTENCE only: a `.kt` file
        # cannot cite a decision id the way a document can, and demanding a comment in every
        # source file a decision touches is a rule nobody would keep. What a path must do is
        # resolve — a decision naming a file that is not there is the same defect as a document
        # that does not cite it.
        _c_field=$line
        while IFS= read -r _c_next; do
          case "$_c_next" in
            '') break ;;
            '- **'*) _replay=$_c_next; break ;;
            *) _c_field="$_c_field $_c_next" ;;
          esac
        done
        _c_list=${_c_field#*'Consequences / affects:'}
        _c_list=$(printf '%s' "$_c_list" | sed 's/\. .*//; s/\.$//')
        printf '%s' "$_c_list" | grep -o '`[^`]*`' | tr -d '`' | while IFS= read -r doc; do
          case "$doc" in
            *.md) printf '%s\t%s\n' "$_id" "$doc" >> "$TMP/conseq" ;;
            */*) printf '%s\t%s\n' "$_id" "$doc" >> "$TMP/conseqpath" ;;
          esac
        done ;;
      *'Supersedes:'*|*'Contradicts:'*|*'Refines:'*)
        _mk=$(echo "$line" | grep -o 'Supersedes\|Contradicts\|Refines' | head -1)
        echo "$line" | grep -o '\(DEC\|ADR\)-[0-9][0-9]*' | while IFS= read -r tgt; do
          [ "$tgt" = "$_id" ] || printf '%s\t%s\t%s\n' "$_id" "$_mk" "$tgt" >> "$TMP/edges"
        done ;;
    esac
  done
}

if [ "$have_reg" = "1" ]; then
  SHAPE="register"; ID_PREFIX="DEC"
  # Split the fence-stripped register into one body per "### DEC-####" heading.
  awk -v out="$TMP" '
    /^### DEC-[0-9]+/ { id=$2; sub(/[^A-Za-z0-9-].*/,"",id); n++; f=out "/e_" n; ids[n]=id }
    n { print > f }
    END { for (i=1;i<=n;i++) print ids[i] > (out "/e_ids") }
  ' "$(flat_of "$DEC_FILE")"
  if [ -f "$TMP/e_ids" ]; then
    _n=0
    while IFS= read -r _id; do
      _n=$((_n + 1))
      harvest_entry "$_id" "$DEC_FILE" < "$TMP/e_$_n"
    done < "$TMP/e_ids"
  fi
elif [ "$have_adr" = "1" ]; then
  SHAPE="adr"; ID_PREFIX="ADR"
  while IFS= read -r af; do
    _num=$(basename "$af" | sed 's/^\([0-9][0-9]*\)-.*/\1/')
    harvest_entry "ADR-$_num" "$af" < "$(flat_of "$af")"
  done < "$TMP/adrfiles"
fi

DECS=$(wc -l < "$TMP/entries" | tr -d ' ')
case "$SHAPE" in
  none) dormant "decision home — neither $DEC_FILE nor $ADR_DIR/NNNN-*.md yet" ;;
  *)    ok "decision home: $SHAPE ($DECS entr$([ "$DECS" = "1" ] && echo y || echo ies), ids $ID_PREFIX-####)" ;;
esac

# ---------- 1. relative links resolve ----------
while IFS= read -r f; do
  [ -f "$f" ] || continue
  dir=$(dirname "$f")
  grep -n -o '](\([^) ]*\))' "$(flat_of "$f")" 2>/dev/null |
  sed 's/](\(.*\))/\1/' |
  while IFS=: read -r ln target; do
    case "$target" in
      http://*|https://*|mailto:*|'#'*|'') continue ;;
    esac
    base=${target%%#*}
    [ -n "$base" ] || continue
    [ -e "$dir/$base" ] || echo "$f:$ln: dangling link -> $target" >> "$TMP/badlinks"
  done
done < "$TMP/files"
if [ -s "$TMP/badlinks" ] 2>/dev/null; then
  err "$(wc -l < "$TMP/badlinks" | tr -d ' ') dangling relative link(s):"
  sed 's/^/         /' "$TMP/badlinks"
else
  ok "relative links resolve ($FILE_COUNT files)"
fi

# ---------- 2. every id referenced is defined ----------
if [ "$SHAPE" = "none" ]; then
  dormant "id integrity — no decision home yet"
  OQS=0
else
  cut -f1 "$TMP/entries" | sort -u > "$TMP/dec_def"
  : > "$TMP/oq_def"
  [ -f "$OQ_FILE" ] && grep -o '^| *OQ-[0-9][0-9]*' "$(flat_of "$OQ_FILE")" | sed 's/^| *//' | sort -u > "$TMP/oq_def"
  OQS=$(wc -l < "$TMP/oq_def" | tr -d ' ')
  cat "$TMP/dec_def" "$TMP/oq_def" | sort -u > "$TMP/defined"

  : > "$TMP/refs"
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    grep -v 'Next free ID' "$(flat_of "$f")" 2>/dev/null |
      grep -o "\($ID_PREFIX\|OQ\)-[0-9][0-9]*" | sed "s|^|$f |" >> "$TMP/refs"
  done < "$TMP/files"

  : > "$TMP/undef"
  sort -u "$TMP/refs" | while read -r f id; do
    grep -qx "$id" "$TMP/defined" || echo "$f: $id referenced, never defined" >> "$TMP/undef"
  done
  if [ -s "$TMP/undef" ] 2>/dev/null; then
    err "undefined id(s):"; sed 's/^/         /' "$TMP/undef"
  else
    ok "every referenced id is defined ($DECS decisions, $OQS open questions)"
  fi
fi

# ---------- 3. the id allocator is sound ----------
# Register shape: a stated "Next free ID" must equal max defined + 1.
# ADR shape: there is no such line — the filename IS the allocator, so the sound
# check is that no two files claim one number.
check_next_free() {
  _file=$1; _prefix=$2; _deffile=$3
  [ -f "$_file" ] || { dormant "next-free-$_prefix — no $_file yet"; return; }
  _claim=$(grep -o "Next free ID:\** *\`\?$_prefix-[0-9][0-9]*" "$_file" | head -1 |
           grep -o '[0-9][0-9]*$')
  if [ -z "${_claim:-}" ]; then
    err "$_file: no parsable 'Next free ID: \`$_prefix-NNNN\`' line"
    return
  fi
  # Strip leading zeros BEFORE any arithmetic: bash reads 0009 as octal, 9 is not
  # an octal digit, the expansion errors, the `if` takes its else branch and the
  # check prints ok. It passed for every id ending 0-7 and was silent for 8 and 9.
  # Found by the probe; the check was wrong, not the probe.
  _claim_raw=$_claim
  _claim=$(echo "$_claim" | sed 's/^0*//'); [ -n "$_claim" ] || _claim=0
  if [ ! -s "$_deffile" ]; then _max=0; else
    _max=$(sed "s/^$_prefix-//" "$_deffile" | sed 's/^0*//' | sort -n | tail -1)
    [ -n "${_max:-}" ] || _max=0
  fi
  _want=$((_max + 1))
  if [ "$_claim" -ne "$_want" ]; then
    err "$_file: 'Next free ID' claims $_prefix-$_claim, highest defined is $_max (expected $_want)"
  else
    ok "next free $_prefix id is correct ($_prefix-$_claim_raw)"
  fi
}
case "$SHAPE" in
  register) check_next_free "$DEC_FILE" DEC "$TMP/dec_def" ;;
  adr)
    # Count from the FILENAMES, not from the entry index. The index keeps one row
    # per id on purpose (an entry has one status line), and that dedupe silently
    # swallowed the very thing this check looks for: a second file claiming a
    # number already taken. The filename is the allocator, so the filename is what
    # gets counted. The check was wrong, not the probe.
    _dupes=$(sed 's|.*/||; s|^\([0-9][0-9]*\)-.*|\1|' "$TMP/adrfiles" | sort | uniq -d | tr '\n' ' ')
    _adr_n=$(wc -l < "$TMP/adrfiles" | tr -d ' ')
    if [ -n "$(echo "$_dupes" | tr -d ' ')" ]; then
      err "duplicate ADR number(s) — two files claim one id: $_dupes"
    else
      ok "ADR numbers are unique ($_adr_n files)"
    fi ;;
  *) dormant "id allocator — no decision home yet" ;;
esac
check_next_free "$OQ_FILE" OQ "$TMP/oq_def"

# ---------- 4. a stated register size equals the computed one ----------
# Compute, never restate: a number written in prose is a number that goes stale.
_size_src=""
[ "$SHAPE" = "register" ] && _size_src=$DEC_FILE
if [ -n "$_size_src" ] && grep -q 'Register size:' "$_size_src" 2>/dev/null; then
  _stated=$(grep -o 'Register size:\** *\**[0-9][0-9]*' "$_size_src" | head -1 | grep -o '[0-9][0-9]*')
  if [ "${_stated:-x}" != "$DECS" ]; then
    err "$_size_src: states 'Register size: $_stated', computed $DECS"
  else
    ok "stated register size matches the computed one ($DECS)"
  fi
else
  dormant "register-size cross-check — no 'Register size:' line stated"
fi

# ---------- 5. consequences propagation (RATCHETED) ----------
# A document named in an entry's "Consequences / affects:" line must cite that
# entry. Writing down where a decision must propagate and then not propagating is
# the exact failure the loop exists to prevent.
PROP_MISSING=0
if [ "$SHAPE" = "none" ]; then
  dormant "propagation — no decision home yet"
else
  : > "$TMP/prop"
  while IFS="$(printf '\t')" read -r id doc; do
    [ -n "${doc:-}" ] || continue
    if [ ! -f "$doc" ]; then echo "$id -> $doc (MISSINGFILE)" >> "$TMP/prop"
    elif grep -q "$id" "$doc"; then :
    else echo "$id -> $doc (NOCITE)" >> "$TMP/prop"; fi
  done < "$TMP/conseq"
  # **A non-document target is checked for existence, and only that.** A `.kt` file cannot cite a
  # decision id the way a document can, and demanding a comment in every source file a decision
  # touches is a rule nobody would keep. What a path must do is RESOLVE: a decision naming a file
  # that is not there points the next reader at nothing, which is the same defect as a document
  # that does not cite it — and 83 such targets were dropped on the floor before `B-227`.
  #
  # **The register abbreviates, deliberately, and the check is written for that.** Entries name
  # `core-common/.../AppError.kt` and `app/res/values/strings.xml` — a module plus a file, elided
  # in the middle, because the full Kotlin source path is eight segments of noise in a sentence a
  # person reads. So a literal `[ -e ]` would refuse 22 correct entries. The rule instead: the
  # BASENAME must be a tracked file, and where the first segment names a real top-level directory
  # the tracked path must sit under it. That decides the thing worth deciding — the file is there
  # — and says nothing about the elided middle, which no reader is misled by.
  if [ -f "$TMP/conseqpath" ]; then
    git ls-files > "$TMP/tracked" 2>/dev/null || : > "$TMP/tracked"
    sort -u "$TMP/conseqpath" | while IFS="$(printf '\t')" read -r id path; do
      [ -n "${path:-}" ] || continue
      [ -e "$path" ] && continue
      _base=${path##*/}
      _top=${path%%/*}
      if [ -d "$_top" ]; then
        grep -q "^$_top/.*/$_base\$" "$TMP/tracked" && continue
        grep -q "^$_top/$_base\$" "$TMP/tracked" && continue
      else
        grep -q "/$_base\$" "$TMP/tracked" && continue
      fi
      echo "$id -> $path (MISSINGFILE)" >> "$TMP/prop"
    done
  fi
  [ -f "$TMP/prop" ] && PROP_MISSING=$(wc -l < "$TMP/prop" | tr -d ' ')
  : > "$TMP/prop_new"
  if [ "$PROP_MISSING" -gt 0 ]; then
    while IFS= read -r row; do
      _n=$(echo "$row" | grep -o '[0-9][0-9]*' | head -1 | sed 's/^0*//'); [ -n "$_n" ] || _n=0
      [ "$_n" -ge "$PROP_FLOOR" ] && echo "$row" >> "$TMP/prop_new"
    done < "$TMP/prop"
  fi
  if [ -s "$TMP/prop_new" ] 2>/dev/null; then
    err "entr(y|ies) naming a document that does not cite them (floor $ID_PREFIX-$PROP_FLOOR):"
    sed 's/^/         /' "$TMP/prop_new"
  else
    ok "consequences propagate (backlog below the floor: $PROP_MISSING)"
  fi
fi

# ---------- 6. supersede / contradict annotates the target ----------
# One word for "adds to" and "replaces a clause of" is unenforceable, so the
# markers are distinct and only two of them oblige the target to say so.
if [ "$SHAPE" = "none" ]; then
  dormant "supersede annotations — no decision home yet"
else
  : > "$TMP/ann"
  while IFS="$(printf '\t')" read -r src marker target; do
    [ -n "${target:-}" ] || continue
    [ "$marker" = "Refines" ] && continue          # additive: no annotation owed
    _status=$(grep "^$target	" "$TMP/entries" | head -1 | cut -f3)
    if [ -z "$_status" ]; then
      echo "$src $marker $target: target is not a defined entry" >> "$TMP/ann"
    else
      case "$_status" in
        *"$src"*) ;;
        *) echo "$target: status line does not record that $src ${marker}s it" >> "$TMP/ann" ;;
      esac
    fi
  done < "$TMP/edges"
  if [ -s "$TMP/ann" ] 2>/dev/null; then
    err "unannotated supersede/contradict target(s):"; sed 's/^/         /' "$TMP/ann"
  else
    ok "supersede and contradict targets are annotated"
  fi
fi

# ---------- 7. retired-decision residue (RATCHETED) ----------
# A document citing a WHOLLY RETIRED id must say so in the same breath. The unit
# is a line: one marker on it exempts every id on it. That is a real blind spot,
# measured and accepted — a tighter window produced mostly noise, and a gate that
# is mostly noise is a gate people switch off.
RESIDUE=0
if [ "$SHAPE" = "none" ]; then
  dormant "retired residue — no decision home yet"
else
  : > "$TMP/retired"
  while IFS="$(printf '\t')" read -r id file status; do
    case "$status" in
      *Superseded\ by*|*Reversed*) echo "$id" >> "$TMP/retired" ;;
    esac
  done < "$TMP/entries"
  if [ -s "$TMP/retired" ] 2>/dev/null; then
    : > "$TMP/res"
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      [ "$f" = "$DEC_FILE" ] && continue
      case "$f" in "$ADR_DIR"/*) continue ;; esac
      while IFS= read -r rid; do
        grep -n "$rid" "$(flat_of "$f")" 2>/dev/null | while IFS=: read -r ln text; do
          case "$text" in
            *supersed*|*Supersed*|*retired*|*Retired*|*reversed*|*Reversed*) ;;
            *) echo "$f:$ln cites $rid (retired) without saying so" >> "$TMP/res" ;;
          esac
        done
      done < "$TMP/retired"
    done < "$TMP/files"
    [ -f "$TMP/res" ] && RESIDUE=$(wc -l < "$TMP/res" | tr -d ' ')
    if [ "$RESIDUE" -gt "$RESIDUE_FLOOR" ]; then
      err "$RESIDUE unmarked citation(s) of retired decisions (floor $RESIDUE_FLOOR):"
      sed 's/^/         /' "$TMP/res"
    else
      ok "no unmarked citation of a retired decision (residue $RESIDUE, floor $RESIDUE_FLOOR)"
    fi
  else
    ok "no retired decisions yet"
  fi
fi

# ---------- 8. status vocabularies are closed ----------
# An unrecognised status is worse than a missing one: it looks answered, and every
# check on that row skips in silence.
if [ "$SHAPE" = "none" ]; then
  dormant "decision status vocabulary — no decision home yet"
else
  : > "$TMP/vocab"
  while IFS="$(printf '\t')" read -r id file status; do
    case "$status" in
      *Accepted*|*Superseded\ by*|*Reversed*) ;;
      *) echo "$file: $id has an unknown status ->${status#*Status:}" >> "$TMP/vocab" ;;
    esac
  done < "$TMP/entries"
  if [ -s "$TMP/vocab" ] 2>/dev/null; then
    err "decision status vocabulary:"; sed 's/^/         /' "$TMP/vocab"
  else
    ok "decision statuses are inside the closed vocabulary"
  fi
fi

if [ -f "$OQ_FILE" ]; then
  : > "$TMP/oqvocab"
  grep -n '^| *OQ-[0-9]' "$(flat_of "$OQ_FILE")" 2>/dev/null | while IFS= read -r row; do
    case "$row" in
      *'| Open '*|*'| Open|'*|*Open\ \|*|*Resolved→*|*Dropped*) ;;
      *) echo "$OQ_FILE:${row%%:*}: unknown question status" >> "$TMP/oqvocab" ;;
    esac
  done
  if [ -s "$TMP/oqvocab" ] 2>/dev/null; then
    err "open-question status vocabulary:"; sed 's/^/         /' "$TMP/oqvocab"
  else
    ok "open-question statuses are inside the closed vocabulary"
  fi
else
  dormant "open-question status vocabulary — no $OQ_FILE yet"
fi

# ---------- 9. every commit SHA named in the retro resolves ----------
# A file:line rots at the next edit; a SHA carries the diff, the message and the
# parent forever. A document may not send a reader to something absent.
# `[ -d .git ]` is the wrong question and it silently disabled this whole section for
# every submodule and every linked worktree — where `.git` is a FILE holding a `gitdir:`
# pointer. Measured 2026-08-16 on this skill's own repository, checked out as a submodule:
# the section printed `skip` while five SHAs in the archive did not resolve at all. Ask git
# whether it is inside a work tree; it knows about all three shapes and this does not.
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  skipmsg "commit-SHA resolution — not a git working tree"
elif [ ! -d "$RETRO_GLOB" ]; then
  dormant "commit-SHA resolution — no $RETRO_GLOB yet"
else
  : > "$TMP/sha"
  # An enumerated exception, never a floor. A commit whose history was rewritten before any
  # of this was gated cannot be repaired without inventing a mapping, and a frozen record of
  # a past run is not rewritten. Such SHAs are listed by name in a `docgate:known-dead`
  # marker inside the retro corpus itself — one home, with the reason in prose beside it —
  # so this passes over exactly those and still fails on the next one.
  DEAD=$(grep -rho 'docgate:known-dead[^>]*' "$RETRO_GLOB" 2>/dev/null | sed 's/docgate:known-dead//' | tr -s ' \n' ' ')
  # **Two git processes for the whole corpus, not two per SHA.** This ran `rev-parse --verify`
  # and `merge-base --is-ancestor` per candidate: 410 SHAs here, 820 spawns, **15 of the gate's
  # 42 seconds** — measured 2026-09-21, and the single largest cost in a check that runs before
  # every commit and 23 times inside `selftest.sh`. A gate that slow is a gate people route
  # around, which costs more than anything it catches.
  #
  # `cat-file --batch-check` answers "does this resolve to a commit" for every candidate in one
  # pass, and `rev-list HEAD` gives the reachable set once. Abbreviated SHAs are matched by
  # prefix against that set, which is what `merge-base --is-ancestor` was being asked per SHA.
  : > "$TMP/shacand"
  find "$RETRO_GLOB" -type f -name '*.md' 2>/dev/null | sort | while IFS= read -r f; do
    grep -n -o '`[0-9a-f][0-9a-f]*`' "$(flat_of "$f")" 2>/dev/null |
    while IFS=: read -r ln tok; do
      s=$(echo "$tok" | tr -d '`')
      case ${#s} in 7|8|9|10|11|12|40) ;; *) continue ;; esac
      case " $DEAD " in *" $s "*) continue ;; esac
      printf '%s\t%s\t%s\n' "$f" "$ln" "$s" >> "$TMP/shacand"
    done
  done
  if [ -s "$TMP/shacand" ] 2>/dev/null; then
    cut -f3 "$TMP/shacand" | sort -u | sed 's/$/^{commit}/' |
      git cat-file --batch-check 2>/dev/null > "$TMP/sharesolved" || true
    git rev-list HEAD 2>/dev/null > "$TMP/shareach" || : > "$TMP/shareach"
    awk -F'\t' '
      # Which candidates resolved to a commit object at all.
      # `<input> missing` for an unknown one; `<resolved-sha> commit <size>` otherwise — and the
      # resolved id is NOT always the token. An annotated tag peels to a commit whose id differs
      # from the id of the tag object, so prefix-matching the TOKEN against `rev-list HEAD` — which holds
      # commit ids only — reported a reachable tag as unreachable. No document cites a tag today;
      # this is the false positive that was waiting for the first one. The batch output already
      # carries the answer, so the resolved id is what gets matched.
      FILENAME == ARGV[1] {
        if ($0 ~ / missing$/) {
          tok = $0; sub(/\^\{commit\}.*/, "", tok); dead[tok] = 1
        } else if (NF >= 2 && $2 == "commit") {
          resolved[++nres] = $1
        }
        next
      }
      # `++nreach`, not `NR`: the record number in awk is GLOBAL and continues across input
      # files, so indexing by it left `reach[1..nreach]` mostly empty and every SHA read as
      # unreachable. The gate went red on five true commits, which is how it was caught in one
      # run. (No apostrophes in here: this program is single-quoted, and one closes the string.)
      FILENAME == ARGV[2] { reach[++nreach] = $0; next }
      {
        sha = $3
        if (dead[sha]) {
          printf "%s:%s: commit `%s` does not resolve\n", $1, $2, sha
          next
        }
        # The token may abbreviate a commit id, or name a tag that peels to one. Match the token
        # against the reachable set first; failing that, match every resolved id the batch
        # produced whose own prefix is this token, which is what covers the peeling case.
        found = 0
        for (i = 1; i <= nreach; i++) if (index(reach[i], sha) == 1) { found = 1; break }
        if (!found) {
          for (j = 1; j <= nres; j++) {
            if (index(resolved[j], sha) != 1 && length(sha) != 40) continue
            for (i = 1; i <= nreach; i++) if (reach[i] == resolved[j]) { found = 1; break }
            if (found) break
          }
        }
        if (!found) {
          # Resolving is the weaker half. A commit that was AMENDED AWAY still resolves on the
          # machine that amended it and reaches no clone — measured 2026-08-16, twice in one
          # close-out. Ask the question a reader actually has: is it in this history at all.
          printf "%s:%s: commit `%s` resolves but is NOT reachable from HEAD — amended away, or on a branch this checkout does not have\n", $1, $2, sha
        }
      }
    ' "$TMP/sharesolved" "$TMP/shareach" "$TMP/shacand" | sort -u >> "$TMP/sha"
  fi
  if [ -s "$TMP/sha" ] 2>/dev/null; then
    err "commit reference(s) a clone could not follow:"; sed 's/^/         /' "$TMP/sha"
  else
    ok "every commit reference in $RETRO_GLOB resolves AND is reachable from HEAD"
  fi
fi

# ---------- 10. the doc map and the registers agree, BOTH directions ----------
# The direction that feels redundant is the one that finds things: a register the
# map never names is a register nobody is told about.
if [ ! -f "$MAP_FILE" ]; then
  dormant "doc-map coverage — no $MAP_FILE yet"
else
  # Forward direction is scoped to the "## Registers" table: that table is a CLAIM
  # about what exists. The SSOT table below it legitimately names documents a young
  # project has not written yet, and failing on those would make the gate seed red.
  # TABLE ROWS ONLY. Reading every backtick in the section swept up the prose note
  # under the table ("an existing docs/adr/ IS the register") and reported it as a
  # missing file — a claim the note never made. A row is a claim; a sentence is not.
  : > "$TMP/map"
  awk '/^## Registers/ { on = 1; next } on && /^## / { exit } on && /^\|/ { print }' \
    "$(flat_of "$MAP_FILE")" | grep -o '`[^`]*`' | tr -d '`' | sort -u > "$TMP/map"
  : > "$TMP/mapmiss"
  while IFS= read -r doc; do
    case "$doc" in *'<'*|*'>'*) continue ;; esac
    case "$doc" in *.md|*/) ;; *) continue ;; esac
    [ -e "$doc" ] || [ -e "${doc%/}" ] ||
      echo "$MAP_FILE names $doc, which does not exist" >> "$TMP/mapmiss"
  done < "$TMP/map"
  for reg in "$DEC_FILE" "$OQ_FILE"; do
    [ -f "$reg" ] || continue
    grep -q "$(basename "$reg")" "$TMP/map" ||
      echo "$reg exists but $MAP_FILE never names it" >> "$TMP/mapmiss"
  done
  if [ "$SHAPE" = "adr" ]; then
    grep -q "adr" "$TMP/map" ||
      echo "$ADR_DIR is this project's decision home but $MAP_FILE never names it" >> "$TMP/mapmiss"
  fi
  if [ -s "$TMP/mapmiss" ] 2>/dev/null; then
    err "doc map / register disagreement:"; sed 's/^/         /' "$TMP/mapmiss"
  else
    ok "doc map and registers agree in both directions"
  fi
fi

# ---------- 11. a backticked path a DESCRIPTIVE document names must exist ----------
# THIS IS THE SECTION THAT WOULD HAVE CAUGHT `VoiceCaptureSheet.kt` SURVIVING AS THE DECLARED
# COVERAGE OF TWO SCREENS AFTER THE FILE WAS DELETED. Every other section here checks the
# documentation against itself; this one checks it against the tree.
#
# **IT IS SCOPED, AND THE SCOPE IS THE WHOLE DESIGN.** Run over every document, it fails on the
# audit reports — and it fails on them for doing their job. An axis that reports "there is no
# .github/", "gradle/verification-metadata.xml is absent", "scripts/exposure.sh is cited and does
# not exist" is *correct*, and a gate that reds on a true finding teaches people to disable gates.
# Measured 2026-09-20: 40 reports over the whole corpus, 37 of them from audits and specs naming
# absences deliberately.
#
# So: a DESCRIPTIVE document — one that says how the tree IS — is checked. An ANALYTIC document —
# an audit, a spec, a plan, a retro — is not, because naming a thing that is missing is precisely
# what it is for. The list is literal and short; adding to it is a decision, not a convenience.
DESC_DOCS="$DOCS_DIR/DOCMAP.md $DOCS_DIR/modules README.md"
ALLOW_FILE=${ALLOW_FILE:-$DOCS_DIR/.docpaths-allow}
: > "$TMP/allow"
if [ -f "$ALLOW_FILE" ]; then
  grep -v '^[[:space:]]*#' "$ALLOW_FILE" 2>/dev/null | grep -v '^[[:space:]]*$' |
    awk '{print $1}' | sort -u > "$TMP/allow"
fi

: > "$TMP/descfiles"
for d in $DESC_DOCS; do
  if [ -d "$d" ]; then find "$d" -type f -name '*.md' 2>/dev/null >> "$TMP/descfiles"
  elif [ -f "$d" ]; then echo "$d" >> "$TMP/descfiles"
  fi
done
sort -u "$TMP/descfiles" -o "$TMP/descfiles" 2>/dev/null || true

: > "$TMP/pathmiss"
: > "$TMP/pathstale"
while IFS= read -r f; do
  [ -f "$f" ] || continue
  grep -o '`[^`]*`' "$(flat_of "$f")" 2>/dev/null | tr -d '`' | while IFS= read -r tok; do
    case "$tok" in
      # not a claim about a file in this tree: a shape, a placeholder, an abbreviation,
      # a URL, a home-relative path, a resource reference, a brace expansion, a runtime path.
      ''|*' '*|*'<'*|*'>'*|*'*'*|*'?'*|*'{'*|*'…'*|*'...'*|http*|'~'*|'@'*|*'path/to/'*) continue ;;
      */*) ;;
      *) continue ;;
    esac
    case "$tok" in
      *.kt|*.kts|*.cpp|*.h|*.xml|*.md|*.sh|*.py|*.json|*.toml|*.pro|*.properties|*/) ;;
      *) continue ;;
    esac
    clean=${tok%%:*}
    case "$clean" in *[.,\;]) clean=${clean%?} ;; esac
    [ -n "$clean" ] || continue
    if grep -qx -- "$clean" "$TMP/allow" 2>/dev/null; then
      [ -e "$clean" ] && echo "$clean resolves now — drop it from $ALLOW_FILE" >> "$TMP/pathstale"
      continue
    fi
    # A document may name a path relative to itself — `../DOCMAP.md` from docs/modules/README.md
    # is a correct citation and resolving it only from the repository root reports a false miss.
    rel="$(dirname "$f")/$clean"
    [ -e "$clean" ] || [ -e "${clean%/}" ] || [ -e "$rel" ] || [ -e "${rel%/}" ] ||
      echo "$f: names $clean, which does not exist" >> "$TMP/pathmiss"
  done
done < "$TMP/descfiles"
sort -u "$TMP/pathmiss" -o "$TMP/pathmiss" 2>/dev/null || true
sort -u "$TMP/pathstale" -o "$TMP/pathstale" 2>/dev/null || true
if [ -s "$TMP/pathmiss" ] 2>/dev/null; then
  err "path(s) a descriptive document names that do not exist:"; sed 's/^/         /' "$TMP/pathmiss"
elif [ -s "$TMP/pathstale" ] 2>/dev/null; then
  err "allow-list entr(y|ies) that now resolve:"; sed 's/^/         /' "$TMP/pathstale"
else
  ok "every path a descriptive document names resolves ($(wc -l < "$TMP/descfiles" | tr -d ' ') files checked)"
fi

# ---------- 12. a command a document tells you to run must exist ----------
# SAME SCOPE AS SECTION 11, AND FOR THE SAME REASON — plus the ledgers, which are descriptive.
# Run over everything it fails on the plan: a spec naming `scripts/check-all.sh` is *proposing* a
# script, and a proposal is not a dead instruction. What is a dead instruction is a ledger telling
# a reader to run `scripts/exposure.sh` to measure its own staleness when that script is not there
# (F-15) — and that is inside this scope. `./gradlew <task>` was considered and left out:
# resolving a task name costs a configuration run, turning a one-second gate into a thirty-second
# one for a class of error the build reports immediately.
: > "$TMP/cmdfiles"
cp "$TMP/descfiles" "$TMP/cmdfiles" 2>/dev/null || true
for extra in "$DOCS_DIR/evidence/verification.md" "$DOCS_DIR/evidence/device-gate.md"; do
  [ -f "$extra" ] && echo "$extra" >> "$TMP/cmdfiles"
done
# **The current handoff, by the one definition this script has** — see §20, which owns it.
# This line used to glob `*entry*.md`, and the two agreed only because the current file happens
# to be named `…-v2-entry.md`: name the next one anything without `entry` in it and §20 would
# track it while §12 silently stopped checking the commands it tells you to run.
[ -n "${CURRENT_HANDOFF:-}" ] && [ -f "$CURRENT_HANDOFF" ] && echo "$CURRENT_HANDOFF" >> "$TMP/cmdfiles"
sort -u "$TMP/cmdfiles" -o "$TMP/cmdfiles" 2>/dev/null || true
: > "$TMP/cmdmiss"
while IFS= read -r f; do
  [ -f "$f" ] || continue
  grep -o '`\(bash \|python3 \)\?scripts/[A-Za-z0-9_./-]*\.\(sh\|py\)[^`]*`' "$(flat_of "$f")" 2>/dev/null |
    tr -d '`' | sed -e 's/^bash //' -e 's/^python3 //' | awk '{print $1}' |
    while IFS= read -r cmd; do
      cmd=${cmd%%:*}                      # a `script.sh:13,15` citation names a line, not a file
      [ -n "$cmd" ] || continue
      [ -f "$cmd" ] || echo "$f: tells you to run $cmd, which does not exist" >> "$TMP/cmdmiss"
    done
done < "$TMP/cmdfiles"
sort -u "$TMP/cmdmiss" -o "$TMP/cmdmiss" 2>/dev/null || true
if [ -s "$TMP/cmdmiss" ] 2>/dev/null; then
  err "command(s) a document tells you to run that do not exist:"; sed 's/^/         /' "$TMP/cmdmiss"
else
  ok "every script a document tells you to run exists"
fi

# ---------- 13. a symbol a module document names must be declared ----------
# Only `docs/modules/*.md`, and only CamelCase identifiers: a module document's job is to name the
# types its module owns, so a name it gives that nothing declares is either a rename nobody
# followed or a file that was deleted. The token must contain a lower-case letter, so `REQ`,
# `FTS4` and `OK` are prose, not symbols.
if [ -d "$DOCS_DIR/modules" ] && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  : > "$TMP/symmiss"
  for f in "$DOCS_DIR"/modules/*.md; do
    [ -f "$f" ] || continue
    grep -o '`[A-Z][A-Za-z0-9_]*`' "$(flat_of "$f")" 2>/dev/null | tr -d '`' | sort -u |
    while IFS= read -r sym; do
      case "$sym" in *[a-z]*) ;; *) continue ;; esac
      # MENTIONED, not declared. A module document legitimately names a platform or SDK type it
      # USES — `PendingIntent`, `VRFeature`, `PanelRegistration` — and an enum entry, a provider
      # name and a header name are all symbols nothing in this tree "declares". Requiring a
      # declaration reported eight of those and zero real defects. The class worth catching is
      # narrower and sharper: a name the documentation carries that the source does not mention
      # anywhere at all, which is what a deleted file leaves behind.
      # `--untracked`: without it a module document that names a class added in the same change
      # reds until the file is staged. That happened on 2026-09-21 with `LocalWhisperOwnerTest`
      # and reads as a stale document rather than as an unstaged file.
      git grep -q -w --untracked -- "$sym" -- '*.kt' '*.cpp' '*.h' 2>/dev/null && continue
      echo "$f: names \`$sym\`, which nothing declares" >> "$TMP/symmiss"
    done
  done
  sort -u "$TMP/symmiss" -o "$TMP/symmiss" 2>/dev/null || true
  if [ -s "$TMP/symmiss" ] 2>/dev/null; then
    err "symbol(s) a module document names that nothing declares:"; sed 's/^/         /' "$TMP/symmiss"
  else
    ok "every symbol a module document names is declared"
  fi
else
  dormant "module symbols — no $DOCS_DIR/modules or not a git work tree"
fi

# ---------- 14. the UX linter can actually SEE the screens ----------
#
# `docs/ux/lint.py` is vendored from the super-ux pack, so a `/ux` run overwrites it — and it
# shipped a matcher demanding `### SCR-NN` while `screens.md` has written `## SCR-01` since it
# existed. Every screen-level check was therefore dead, and two screens carried `Coverage:` paths
# naming files deleted months of commits earlier while the linter printed a clean green
# (measured 2026-09-20). The one-line fix lives in a file this repository does not own, so this
# check is what stops a re-vendor from silently killing the gate again: it compares what the
# linter FINDS against what the file plainly contains, rather than inspecting the regex.
if [ -f "$DOCS_DIR/ux/lint.py" ] && [ -f "$DOCS_DIR/ux/screens.md" ]; then
  cat > "$TMP/uxreach.py" <<'UXREACH'
import importlib.util, pathlib, sys
docs = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("uxlint", docs / "ux" / "lint.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
print(len(mod.ids((docs / "ux" / "screens.md").read_text(), "SCR")))
UXREACH
  seen=$(python3 "$TMP/uxreach.py" "$DOCS_DIR" 2>/dev/null || echo -1)
  present=$(grep -cE '^#+[ \t]+SCR-[0-9]+' "$DOCS_DIR/ux/screens.md" || echo 0)
  if [ "$seen" != "$present" ]; then
    err "docs/ux/lint.py sees $seen screen entries; screens.md contains $present — every screen-level check is dead"
  else
    ok "the UX linter sees every screen entry ($present)"
  fi
else
  dormant "UX linter reach — no $DOCS_DIR/ux/lint.py or screens.md"
fi

# ---------- 15. a deleted file may not be a live instruction ----------
#
# A spec is an instruction to be EXECUTED, so unlike sections 11-13 a dated artefact is in scope
# here. `DEC-0020` cut the assistant and deleted `ChatViewModel.kt` and `ChatScreen.kt`; the plan's
# own closing table said in writing that "T-017 and T-019 lose ChatViewModel", and the propagation
# was never applied. Measured 2026-09-20: 51 references across seven specs, T-019's Step 4 and four
# of T-017's twelve named tests targeting a class that does not exist. Three independent readings
# found it separately, which is what a missing gate looks like.
#
# Two scopes make this precise rather than noisy:
#   - only specs whose board row is still `open` — a closed task's spec records work done against
#     the tree as it then stood, and rewriting it would be rewriting history;
#   - only the sections an executor ACTS on. "What is wrong" and "Where it is" are the record of
#     the defect and must keep naming the file that carried it.
if [ -d "$DOCS_DIR/evidence/plans" ] && [ -f "$DOCS_DIR/evidence/backlog.md" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  git log --diff-filter=D --name-only --pretty=format: HEAD -- '*.kt' 2>/dev/null \
    | sort -u | grep -v '^$' > "$TMP/gonepaths" || true
  cat > "$TMP/deadinstr.py" <<'DEADINSTR'
import pathlib, re, sys
docs, gonelist = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
gone = sorted({pathlib.Path(l).name for l in gonelist.read_text().split() if l and not pathlib.Path(l).exists()})
if not gone:
    raise SystemExit(0)
board = (docs / "evidence" / "backlog.md").read_text()
allow = set()
af = docs / ".dead-instruction-allow"
if af.exists():
    for line in af.read_text().splitlines():
        line = line.split("#")[0].strip()
        if line:
            allow.add(line)
# the sections an executor acts on
ACTS_ON = ("decomposition", "tests", "definition of done", "blast radius")
for spec in sorted((docs / "evidence" / "plans").glob("*/T-*.md")):
    tid = spec.stem
    if not re.search(r"^\| B-\d+ \|[^|]*\b" + re.escape(tid) + r"\b.*\| open \|", board, re.M):
        continue
    text = spec.read_text().splitlines()
    live, acting = [], False
    for line in text:
        if line.startswith("## "):
            acting = any(line[3:].strip().lower().startswith(h) for h in ACTS_ON)
            continue
        if not acting or line.startswith(">") or "VOID" in line or "~~" in line:
            continue
        live.append(line)
    blob = "\n".join(live)
    for base in gone:
        if f"{tid}:{base}" in allow:
            continue
        n = blob.count(base)
        if n:
            print(f"{spec}: {base} appears {n}x in an executable section, and was deleted")
DEADINSTR
  python3 "$TMP/deadinstr.py" "$DOCS_DIR" "$TMP/gonepaths" > "$TMP/deadinstr" 2>/dev/null || true
  if [ -s "$TMP/deadinstr" ]; then
    err "open specs instructing work on deleted files:"; sed 's/^/         /' "$TMP/deadinstr"
  else
    ok "no open spec instructs work on a file that was deleted"
  fi
else
  dormant "dead-instruction check — no plans directory, no board, or not a git work tree"
fi

# ---------- 17. a stated test count equals the counted one ----------
# **Homeless until now, and that is the finding.** `T-003` built sections 11-13 and deferred this
# one, explicitly, because "building a count check before the counts are corrected would seed a
# red gate" — correct, and it named `T-044` as the owner. `T-044`'s spec never mentioned it, so
# `T-044` closed without it and the gap surfaced at `T-045`, which is the task that corrects the
# counts. That is the right moment: the check and the correction land together, so it goes green
# for a reason rather than by being written after the tree already agreed with it.
#
# Four documents stated a count that was wrong when this was written — `README.md` (365 vs 361),
# `app.md` (164 vs 161) and `core-common.md` twice. None of them was wrong when written; each
# drifted when a test was added or removed, which is precisely the class a human reader cannot
# catch and a machine catches for free.
#
# **It counts `@Test` OCCURRENCES, the same way the prose claims to.** That is not the same as
# cases executed — a parameterised test is one occurrence and several cases, and the JVM suite
# reports 362 where this counts 361. The documents say "@Test in the tree", so the check measures
# what they claim; a check that measured something else would be right about the wrong number.
: > "$TMP/counts"
# **An annotation, not a mention.** This counted every occurrence of the text `@Test`, so a KDoc
# line in `ImmersiveLaunchTest.kt` ("the `@Test` body is not the main thread") was a test, and the
# entry document's "34 instrumented" passed §23 over a suite of 33 (audit 2026-09-23). A test is an
# annotation at the start of a line, optionally indented; prose that names one is not.
TEST_ANNOTATION='^[[:space:]]*@Test([^[:alnum:]_]|$)'
count_tests() {   # $1 = module dir, or the literal ALL for every module; $2 = test|androidTest
  if [ "$1" = "ALL" ]; then
    # `*/src/$2` and not `find . -name` on purpose: `third_party/whisper.cpp` is a vendored
    # submodule with its own tests, and counting them would make every document wrong at once.
    grep -rhE "$TEST_ANNOTATION" */src/"$2" 2>/dev/null | wc -l | tr -d ' '
  elif [ -d "$1/src/$2" ]; then
    grep -rhE "$TEST_ANNOTATION" "$1/src/$2" 2>/dev/null | wc -l | tr -d ' '
  else
    echo 0
  fi
}
# Each row: the document, the regex that finds its claim, the module, the source set.
# A document with no claim is not an error — it is a document that does not state a count.
while IFS='|' read -r doc pattern module srcset; do
  [ -n "$doc" ] || continue
  [ -f "$doc" ] || { echo "$doc: named by the count check but absent" >> "$TMP/counts"; continue; }
  # **Read the fence-stripped copy, and compare EVERY match.** This shipped reading the raw file
  # and taking `head -1`, so a stale number inside a fenced code block above the real claim
  # satisfied the check and the real claim went unread — watched passing with a drifted 999 in
  # the guarded sentence. Every other section of this script reads `flat_of`; this one did not.
  nofence "$doc" > "$TMP/nofence.md"
  actual=$(count_tests "$module" "$srcset")
  matches=$(grep -oE "$pattern" "$TMP/nofence.md" 2>/dev/null | grep -oE '[0-9]+' || true)
  if [ -z "$matches" ]; then
    echo "$doc: states no count matching /$pattern/ — the claim this check guards has moved or gone" >> "$TMP/counts"
    continue
  fi
  printf '%s\n' "$matches" | while IFS= read -r stated; do
    [ -n "$stated" ] || continue
    [ "$stated" = "$actual" ] || \
      echo "$doc: states $stated, the tree has $actual ($module, src/$srcset)" >> "$TMP/counts"
  done
done <<'ROWS'
README.md|\*\*[0-9]+ JVM tests\*\*|ALL|test
docs/modules/app.md|\*\*[0-9]+ JVM tests in `:app`\*\*|app|test
docs/modules/core-common.md|\*\*[0-9]+ JVM tests\*\*|core-common|test
docs/modules/core-common.md|\*\*[0-9]+ instrumented tests\*\*|core-common|androidTest
docs/modules/core-notes.md|\*\*[0-9]+ JVM tests\.\*\*|core-notes|test
docs/modules/feature-stt.md|\*\*[0-9]+ JVM tests in `:feature-stt`\*\*|feature-stt|test
docs/modules/feature-assistant.md|\*\*[0-9]+ JVM tests\*\*|feature-assistant|test
docs/modules/feature-vault.md|\*\*[0-9]+ JVM tests\*\*|feature-vault|test
ROWS
if [ -s "$TMP/counts" ] 2>/dev/null; then
  err "stated test counts:"; sed 's/^/         /' "$TMP/counts"
else
  ok "every stated test count equals the counted one ($(count_tests ALL test) JVM, $(count_tests ALL androidTest) instrumented)"
fi

# ---------- 18. a closed board row names what closed it ----------
# The board's header promised that a closed row carries "the commit that closed it", and offered
# a *Closed* list for rows to move into. **82 rows were closed and none moved**, and nine carried
# no reference of any kind (`F-32`). A file that states a rule and breaks it 82 times teaches the
# next reader to skim its header, which is worse than having no rule — the next agent picks one
# of the two states at random.
#
# `T-045` decided the rule rather than patching it: closed rows STAY in the table, because a
# closed row's `Home` cell is often a paragraph naming what was done and what it cost, and a
# one-line entry in a second list destroys exactly that. What survives from the old rule is the
# part that had value — **which commit closed it** — and this is what makes that part true.
#
# A "resolving reference" is deliberately broad: a 7+ hex commit, a `DEC-####`, a `T-###`, or a
# path ending `.md`. Narrower would red on rows closed by a decision rather than by a diff, and
# those are real; broader would accept prose, which is what this replaced.
BOARD="${DOCS_DIR:-docs}/evidence/backlog.md"
if [ ! -f "$MDTABLE" ]; then
  err "closed board rows: $MDTABLE is missing"
elif [ -f "$BOARD" ]; then
  # Columns: 1 id · 2 What · 3 Source · 4 Size · 5 Sev · 6 Blast · 7 Age · 8 Prio · 9 State ·
  # 10 Home. **This shipped reading `$10`/`$11` under `-F'|'`**, and two rows carry a pipe inside
  # a code span in their `What` cell — so `$10` landed on Prio, the state match failed, and both
  # rows were skipped WITHOUT being counted. A closed row with no reference could have lived
  # there permanently. The shape is asserted now: a row this parser cannot address is reported,
  # never `next`ed.
  mdawk '
    /^[ \t]*\|[ \t]*B-[0-9]/ {
      n = split_row($0, c)
      if (n != 10) { printf "%s is a board row with %d cells, expected 10 — this parser cannot address it\n", bare(c[1]), n; next }
      state = bare(c[9]); home = c[10]
      if (state !~ /closed/) next
      closed++
      if (looks_like_sha(home) || home ~ /DEC-[0-9][0-9][0-9][0-9]/ || home ~ /T-[0-9][0-9][0-9]/ || home ~ /\.md/) next
      print bare(c[1]) " is closed and its Home names no commit, decision, task or spec"
    }
    END { printf "#counted %d\n", closed }
  ' "$BOARD" > "$TMP/board"
  BOARD_CLOSED=$(sed -n 's/^#counted //p' "$TMP/board")
  grep -v '^#counted' "$TMP/board" > "$TMP/board.bad" || true
  if [ -s "$TMP/board.bad" ] 2>/dev/null; then
    err "closed board rows without a resolving reference:"; sed 's/^/         /' "$TMP/board.bad"
  else
    ok "every closed board row names what closed it (${BOARD_CLOSED:-0} closed)"
  fi
else
  dormant "closed board rows — no $BOARD yet"
fi

# ---------- 19. the retro obeys its own two rules ----------
# `docs/evidence/retro.md` states two rules about itself, in its own header: **max ten** standing
# instructions in force, and **no row without a retire-when, written at birth**. Neither was
# checked. Section 9 already resolves the SHAs — the third rule the file states — so this closes
# the set rather than opening a new one.
#
# The cap is the load-bearing half. A standing list is read IN FULL at stage 0 of every run, and
# its value comes from being short enough that reading it is cheap; an unbounded list of rules
# nobody can hold is the failure mode the cap exists to prevent, and it arrives one useful row at
# a time. `T-046` hit it immediately: six proposed instructions against five already in force
# would have opened the list at eleven.
#
# The retire-when is the other half. A rule with no exit is a rule that outlives its incident and
# is obeyed out of deference, which is how doctrine rots into ritual.
RETRO="${DOCS_DIR:-docs}/evidence/retro.md"
if [ ! -f "$MDTABLE" ]; then
  err "the retro's own rules: $MDTABLE is missing"
elif [ -f "$RETRO" ]; then
  # **The cap is READ from the document, not restated here.** It shipped as `RETRO_CAP=10`
  # beside a heading that also says ten — two copies of one number, which is what `SI-01` (a
  # standing instruction in this very file) forbids. Change the heading and the old check went
  # on enforcing its own literal, silently, in both directions.
  RETRO_CAP=$(grep -oE '^#+ Standing instructions \(max [0-9]+' "$RETRO" | grep -oE '[0-9]+' | head -1)
  : > "$TMP/retro.bad"
  if [ -z "${RETRO_CAP:-}" ]; then
    echo "the standing-instructions heading states no cap this check can read" >> "$TMP/retro.bad"
    RETRO_CAP=999999
  fi
  # Columns: 1 id · 2 Born · 3 Commit · 4 Instruction · 5 Because · 6 Retire when · 7 Last fired ·
  # 8 Fired at. **This shipped reading `$7` under `-F'|'`**, and `SI-03`'s Instruction escapes a
  # pipe — so `$7` held its `Because` prose and a row with a genuinely empty retirement trigger
  # passed. The leading-pipe pattern was also `^\| SI-`, requiring exactly one space: `|SI-10 |`
  # renders identically in markdown and was invisible to BOTH halves of this section.
  mdawk '
    /^[ \t]*\|[ \t]*SI-[0-9]/ {
      n = split_row($0, c)
      if (n != 8) { printf "%s is a standing instruction with %d cells, expected 8 — this parser cannot address it\n", bare(c[1]), n; count++; next }
      count++
      r = c[6]
      if (r == "" || r == "-" || r == "—" || bare(r) == "" || bare(r) == "-" || bare(r) == "—")
        print bare(c[1]) " has no retire-when trigger"
    }
    END { printf "#counted %d\n", count }
  ' "$RETRO" > "$TMP/retro"
  RETRO_N=$(sed -n 's/^#counted //p' "$TMP/retro")
  grep -v '^#counted' "$TMP/retro" >> "$TMP/retro.bad" || true
  if [ "${RETRO_N:-0}" -gt "$RETRO_CAP" ]; then
    echo "the standing list holds ${RETRO_N} instructions; its own heading caps it at ${RETRO_CAP} — prune before adding" >> "$TMP/retro.bad"
  fi
  if [ -s "$TMP/retro.bad" ] 2>/dev/null; then
    err "the retro's own rules:"; sed 's/^/         /' "$TMP/retro.bad"
  else
    ok "the retro obeys its own rules (${RETRO_N:-0} of $RETRO_CAP standing instructions, every one with a retire-when)"
  fi
else
  dormant "the retro's own rules — no $RETRO yet"
fi

# ---------- 20. exactly one handoff is current, and the README names it ----------
# `F-01` happened because nothing noticed that a document had outlived its build. The README
# pointed at a handoff describing a tree nine commits old; an agent following it would have
# planned a device session for something that already happened, been told to *hold* a button
# deleted a day earlier, and — the destructive branch — might have "fixed" the app by reinstating
# the hold, undoing `DEC-0010`.
#
# The convention that fixes it is one line of doctrine: **one current handoff, named from exactly
# one place; every other handoff carries a supersession banner and is otherwise never edited.** A
# dated document keeps its body and carries its drift at the top, because overwriting it destroys
# the record of what was believed on the day the decisions of that day were made.
#
# This turns "someone should have updated the handoff" into an exit code, which is the only form
# of that sentence that survives contact with a deadline.
HANDOFF_DIR="$DOCS_DIR/handoff"
if [ -d "$HANDOFF_DIR" ]; then
  : > "$TMP/handoff"
  # The current one is whichever file README.md links. Not a name convention, not a date sort:
  # the README is where a reader actually starts, so it is the definition rather than a hint.
  CURRENT=$CURRENT_HANDOFF
  CURRENT_N=$CURRENT_HANDOFF_N
  # **The file the README names must exist.** `CURRENT` is a string from a grep, and the loop
  # below only ever sees files that DO exist — so a README pointing at an absent handoff, with
  # every real handoff bannered, passed with `ok: exactly one handoff is current`. Zero were.
  if [ "${CURRENT_N:-0}" -eq 1 ] && [ ! -f "$CURRENT" ]; then
    echo "README.md names $CURRENT, which does not exist" >> "$TMP/handoff"
  fi
  if [ "${CURRENT_N:-0}" -ne 1 ]; then
    echo "README.md names ${CURRENT_N:-0} files in $HANDOFF_DIR; exactly one is current" >> "$TMP/handoff"
  fi
  for h in "$HANDOFF_DIR"/*.md; do
    [ -f "$h" ] || continue
    banner=$(head -1 "$h" | grep -c 'SUPERSEDED' || true)
    if [ "$h" = "$CURRENT" ]; then
      [ "$banner" -eq 0 ] || echo "$h is the handoff README.md names, and it carries a SUPERSEDED banner" >> "$TMP/handoff"
    else
      [ "$banner" -eq 1 ] || echo "$h is not the current handoff and does not begin with a SUPERSEDED banner" >> "$TMP/handoff"
    fi
  done
  if [ -s "$TMP/handoff" ] 2>/dev/null; then
    err "handoff currency:"; sed 's/^/         /' "$TMP/handoff"
  else
    ok "exactly one handoff is current and the README names it ($(basename "${CURRENT:-none}"))"
  fi
else
  dormant "handoff currency — no $HANDOFF_DIR yet"
fi

# ---------- 21. a cited line range fits inside the file it cites ----------
# **The cheap half of a problem whose expensive half needs a person.** §11 resolves that a path
# exists and §13 that a symbol is declared; neither can say that `foo.kt:104-114` holds what the
# sentence claims, and this script's own scope note disclaims it. Step 11 — the run whose stated
# purpose was making claims checkable — wrote `WhisperModel.kt:104-114` about a file 38 lines
# long, and two more ranges that pointed at the wrong paragraph.
#
# Whether a range holds the right content is judgement. Whether it EXISTS is arithmetic, and the
# 38-line case is exactly that shape. This catches the decidable half and says nothing about the
# other, which is the honest division — `B-177` carries the rest.
#
# Only ranges naming a path are checked. A bare `:68-72` means "this file, these lines" and this
# gate has no way to know which file a sentence is about; that form is the one that rotted twice
# in step 11, and `B-177` records that preferring a symbol over a line number is the real remedy.
# **Scoped to the LIVE documents, not the whole corpus.** Pointed at everything it immediately
# found two true dangling ranges in an audit report and a plan spec — `FabricApp.kt:41-79` against
# a file now 74 lines long. Those were correct when measured, and this project's doctrine is that
# a dated document keeps its body and carries its drift at the top; a gate that reds on history
# forces exactly the rewriting the doctrine forbids. The scope is therefore what `§11` calls
# descriptive — the documents that claim to be true **now** — plus the registers a reader acts on.
RANGE_DOCS="$DOCS_DIR/DOCMAP.md $DOCS_DIR/modules $DOCS_DIR/evidence/device-gate.md $DOCS_DIR/evidence/retro.md README.md CONTEXT.md"
: > "$TMP/rangefiles"
for d in $RANGE_DOCS; do
  if [ -d "$d" ]; then find "$d" -name '*.md' >> "$TMP/rangefiles"
  elif [ -f "$d" ]; then echo "$d" >> "$TMP/rangefiles"
  fi
done

: > "$TMP/ranges"
while IFS= read -r doc; do
  [ -f "$doc" ] || continue
  # `path.ext:NN-MM` inside a backtick span, which is how this project writes them.
  grep -oE '`[A-Za-z0-9_./-]+\.[A-Za-z0-9]+:[0-9]+-[0-9]+`' "$doc" 2>/dev/null | tr -d '`' | sort -u | while IFS= read -r cite; do
    [ -n "$cite" ] || continue
    path=${cite%%:*}; span=${cite##*:}; last=${span##*-}; first=${span%%-*}
    # Resolve relative to the repository root, then beside the citing document.
    target=""
    [ -f "$path" ] && target=$path
    [ -z "$target" ] && [ -f "$(dirname "$doc")/$path" ] && target="$(dirname "$doc")/$path"
    # **A bare basename is the common form and the first version of this check missed it** —
    # `WhisperModel.kt:104-114` names no directory, so resolving only against the repository root
    # and the citing document's own directory found nothing and the check passed on the very
    # citation it was written for. Resolved by basename when the repository holds exactly one file
    # with that name; **ambiguity is skipped rather than guessed**, because picking one of two
    # `Graph.kt`s would make the arithmetic right about the wrong file.
    if [ -z "$target" ] && [ "$path" = "$(basename "$path")" ]; then
      hits=$(git ls-files "*/$path" "$path" 2>/dev/null | grep -v '^third_party/' || true)
      [ "$(printf '%s\n' "$hits" | grep -c .)" = "1" ] && target=$hits
    fi
    [ -n "$target" ] || continue      # a path that does not exist is §11's finding, not this one
    n=$(wc -l < "$target" | tr -d ' ')
    if [ "$last" -gt "$n" ]; then
      echo "$doc: cites $cite, but $target has $n lines" >> "$TMP/ranges"
    elif [ "$first" -gt "$last" ]; then
      echo "$doc: cites $cite, whose range runs backwards" >> "$TMP/ranges"
    fi
  done
done < "$TMP/rangefiles"
if [ -s "$TMP/ranges" ] 2>/dev/null; then
  err "cited line ranges that cannot exist:"; sed 's/^/         /' "$TMP/ranges"
else
  ok "every cited line range fits inside the file it cites"
fi

# ---------- 16. the Environment vocabulary is closed, and the ledger obeys it ----------
# `docs/evidence/verification.md` declares that `Environment` comes from a vocabulary "not
# invented per row". Nothing enforced that, and by `T-044` twenty rows were using two values the
# table did not carry (`device`, `jvm`). They were the RIGHT values — which is the point: a
# vocabulary extended by a row reads exactly like a vocabulary with a typo in it, and the reader
# cannot tell which one they are holding. Both are declared now; this check is what keeps the
# next one from arriving the same way.
#
# **Parsed by `tools/mdtable.awk`, not by `awk -F'|'`.** This section shipped reading `$7` with a
# comment claiming that index named the same cell `exposure.sh` reads as `$6`. It does not — `$6`
# is `Observed at` and `$7` is `Environment`, two different columns — and the comment was the
# thing a future editor would have used to "fix" an apparent off-by-one. Both scripts now take
# their cells from one splitter that understands an escaped pipe and a code span, and address
# them by name.
VERIF="${DOCS_DIR:-docs}/evidence/verification.md"
if [ ! -f "$MDTABLE" ]; then
  err "verification Environment vocabulary: $MDTABLE is missing, and every table parser depends on it"
elif [ -f "$VERIF" ]; then
  mdawk '''
    /^[ \t]*\|[ \t]*Value[ \t]*\|/ { intable = 1; next }
    intable && /^[ \t]*\|[ -]*\|[ -]*\|[ \t]*$/ { next }
    intable && !/^[ \t]*\|/ { intable = 0 }
    intable { n = split_row($0, c); if (n >= 1 && bare(c[1]) != "") print bare(c[1]) }
  ''' "$VERIF" | sort -u > "$TMP/envvocab"
  # REQ columns: 1 REQ · 2 What · 3 Run · 4 Shipped in · 5 Observed at · 6 Environment ·
  # 7 Auto · 8 Human · 9 Note. **The shape is asserted, not assumed** — a row this parser cannot
  # address is reported rather than skipped, because a skipped row and a clean row print the same.
  mdawk '''
    /^[ \t]*\|[ \t]*REQ-[0-9]/ {
      n = split_row($0, c)
      if (n != 9) { printf "!SHAPE %s has %d cells, expected 9\n", bare(c[1]), n > "/dev/stderr"; bad = 1; next }
      v = bare(c[6]); if (v == "") v = "<empty>"
      print v
    }
    END { if (bad) exit 3 }
  ''' "$VERIF" 2> "$TMP/envshape" | sort -u > "$TMP/envused"
  if [ -s "$TMP/envvocab" ]; then
    : > "$TMP/envbad"
    sed -n "s|^!SHAPE |$VERIF: unparseable row — |p" "$TMP/envshape" >> "$TMP/envbad"
    while IFS= read -r used; do
      [ -n "$used" ] || continue
      grep -qxF "$used" "$TMP/envvocab" || echo "$VERIF: Environment value '$used' is used by a row but not declared in the vocabulary table" >> "$TMP/envbad"
    done < "$TMP/envused"
    if [ -s "$TMP/envbad" ] 2>/dev/null; then
      err "verification Environment vocabulary:"; sed '''s/^/         /''' "$TMP/envbad"
    else
      ok "every Environment value used is declared ($(wc -l < "$TMP/envused" | tr -d ''' ''') distinct, from a table of $(wc -l < "$TMP/envvocab" | tr -d ''' '''))"
    fi

    # **`Auto` is a closed vocabulary too, and nothing checked it** (`B-174`). The column declares
    # `pass · partial · none` in this file's own column list; one row carried `pass, with ten
    # corrections` — written by the same run that built the check above for the column one cell to
    # its left. The values are read from the prose that declares them rather than typed here, so
    # the document stays the single source: a fourth value is added by editing the sentence, which
    # is where a reader would look for it.
    #
    # **`Human` is deliberately NOT checked this way.** Its vocabulary is *a date, or the literal
    # `never`*, and a date is not an enumeration — the check it wants is a date parser, which is a
    # different thing and would red on the day somebody writes `2026-9-4`. It is the column a
    # machine may not fill at all, so a machine reading it strictly is the wrong instinct.
    AUTOVOCAB=$(grep -oE '''\*\*Auto\*\* — [^.]*''' "$VERIF" | grep -oE '''`[a-z]+`''' | tr -d '''`''' | sort -u)
    if [ -n "$AUTOVOCAB" ]; then
      printf '''%s\n''' "$AUTOVOCAB" > "$TMP/autovocab"
      mdawk '''
        /^[ \t]*\|[ \t]*REQ-[0-9]/ {
          n = split_row($0, c)
          if (n != 9) next
          v = bare(c[7]); if (v == "") v = "<empty>"
          print v
        }
      ''' "$VERIF" | sort -u > "$TMP/autoused"
      # **The file is append-only, so the check has to understand an append.** `REQ-026` carries
      # `pass, with ten corrections` — true as prose, outside the declared set, and **not
      # editable**: this ledger's rule is that a row is corrected by appending another, and
      # `REQ-038` did exactly that. A checker that demanded the cell be rewritten would be asking
      # the project to break its own doctrine to satisfy a gate, which is how a gate teaches
      # people to delete the sentence rather than the defect. So an out-of-vocabulary value is
      # forgiven for exactly one reason: a LATER row names that REQ and says it is **restated**.
      # Anything else — a typo, a fourth value invented in place — still reds.
      mdawk '''
        /^[ \t]*\|[ \t]*REQ-[0-9]/ {
          n = split_row($0, c)
          if (n != 9) next
          if (c[9] ~ /restated/ || c[2] ~ /restated/) {
            line = c[2] " " c[9]
            while (match(line, /REQ-[0-9]+/)) {
              print substr(line, RSTART, RLENGTH)
              line = substr(line, RSTART + RLENGTH)
            }
          }
        }
      ''' "$VERIF" | sort -u > "$TMP/autorestated"
      mdawk '''
        /^[ \t]*\|[ \t]*REQ-[0-9]/ {
          n = split_row($0, c)
          if (n != 9) next
          v = bare(c[7]); if (v == "") v = "<empty>"
          printf "%s\t%s\n", bare(c[1]), v
        }
      ''' "$VERIF" > "$TMP/autorows"
      : > "$TMP/autobad"
      while IFS="$(printf '\t')" read -r rid used; do
        [ -n "$used" ] || continue
        grep -qxF "$used" "$TMP/autovocab" && continue
        grep -qxF "$rid" "$TMP/autorestated" && continue
        echo "$VERIF: $rid carries Auto value '$used', which is not one of: $(tr '\n' ' ' < "$TMP/autovocab")— and no later row restates it" >> "$TMP/autobad"
      done < "$TMP/autorows"
      if [ -s "$TMP/autobad" ] 2>/dev/null; then
        err "verification Auto vocabulary:"; sed '''s/^/         /''' "$TMP/autobad"
      else
        ok "every Auto value used is declared or restated by a later row ($(wc -l < "$TMP/autoused" | tr -d ''' ''') distinct, from a declared $(wc -l < "$TMP/autovocab" | tr -d ''' '''))"
      fi
    else
      err "verification Auto vocabulary: the column's declared values could not be read from $VERIF"
    fi
  else
    err "verification Environment vocabulary: the table could not be read from $VERIF"
  fi
else
  dormant "verification Environment vocabulary — no $VERIF yet"
fi

# ---------- 22. open board rows whose finding a decision claims to close (PRINTED) ----------
# **A disclosure, not a gate, and the distinction is the whole design.** A board row may cite a
# finding a shipped decision claims to close and still be correctly open — several here record the
# *residue* of a partly-closed finding ("`G-16`'s compression half is not closed", "`B-18`'s 72 dp
# floor reached two surfaces of five"). Redding on those would train somebody to delete the
# sentence rather than the defect.
#
# What it catches is the other kind: a row that IS the finding, fixed and never closed. Three were
# found by hand in one sweep — `C-04`, `I-06` and `B-11`, all fixed, two of them by a commit
# eleven back — and each made the board say there was work where there was none. The board is
# what a loop iteration reads at the top and re-prioritises at the bottom, so a stale open row is
# not clutter: it is a wrong instruction to whoever reads next.
#
# `F-19` is the same class from the other end — a fix that ships without touching its row. Nothing
# can decide that automatically; this narrows the search from ninety rows to a handful.
if [ -f "$MDTABLE" ] && [ -f "$BOARD" ] && [ -n "${DEC_FILE:-}" ] && [ -f "${DEC_FILE:-}" ]; then
  grep -oE 'Closes [^.]*' "$DEC_FILE" 2>/dev/null |
    grep -oE '\b[A-Z]{1,3}-[0-9]{2,3}\b' | sort -u > "$TMP/decclosed" || true
  if [ -s "$TMP/decclosed" ]; then
    mdawk '
      /^[ \t]*\|[ \t]*B-[0-9]/ {
        n = split_row($0, c)
        if (n != 10) next
        if (bare(c[9]) !~ /^open/) next
        printf "%s\t%s\n", bare(c[1]), c[2]
      }
    ' "$BOARD" > "$TMP/openrows"
    : > "$TMP/stalerows"
    while IFS="$(printf '\t')" read -r rid what; do
      [ -n "$rid" ] || continue
      for fid in $(printf '%s' "$what" | grep -oE '\b[A-Z]{1,3}-[0-9]{2,3}\b' | sort -u); do
        grep -qxF "$fid" "$TMP/decclosed" && echo "$rid cites $fid" >> "$TMP/stalerows"
      done
    done < "$TMP/openrows"
    # **It names them.** The first version printed only a count and told the reader to go and
    # read rows it would not identify — a disclosure the reader cannot read, which is the shape
    # `DEC-0052` exists to refuse. Naming them is the whole value: the search goes from ninety
    # rows to six.
    sort -u "$TMP/stalerows" 2>/dev/null > "$TMP/stalerows.u" || : > "$TMP/stalerows.u"
    n=$(awk '{print $1}' "$TMP/stalerows.u" | sort -u | grep -c . || true)
    echo "info:    board — ${n:-0} open row(s) cite a finding a decision claims to close; several are residue rows and correctly open:"
    awk '{ rows[$1] = rows[$1] " " $3 } END { for (r in rows) print "           " r " cites" rows[r] }' \
      "$TMP/stalerows.u" | sort
  fi
fi

# ---------- 23. the current handoff's numbers are the tree's numbers ----------
# **§17 guards eight documents and not the one a cold agent is told to read first.** The audit of
# 2026-09-21 opened the current handoff and found three false claims in it: "CI is green, both
# jobs" (the organisation's Actions budget had been $0 for 36 commits), "**33 decisions**" (there
# were 63) and "13 tests" for a suite that had grown to 33. §20 already computes *which* handoff
# is current and refuses a second one; nothing had ever read what it says.
#
# **Only what can be computed.** "CI is green" is a claim about a service and no gate here can
# settle it; a number is a different thing, and the entry document is exactly where a stale one
# does the most damage — the next agent plans against it. The patterns deliberately match the
# **bolded** forms, because that is how this project writes a load-bearing count, and a sentence
# that stops being bold has stopped being the claim.
if [ -n "${CURRENT:-}" ] && [ -f "${CURRENT:-}" ]; then
  : > "$TMP/handoffnums"
  nofence "$CURRENT" > "$TMP/handoff.nofence"
  # `$DECS` and not a fresh grep: the register opens with a **fenced format sample** whose heading
  # looks exactly like an entry, so counting the raw file returns one too many. The gate already
  # harvested the entries fence-stripped at §2, and two counts of one thing is how §12 and §20
  # came to disagree. This is the number the verdict line prints.
  hdecs=${DECS:-0}
  hinstr=$(count_tests ALL androidTest)
  for stated in $(grep -oE '\*\*[0-9]+ decisions' "$TMP/handoff.nofence" | grep -oE '[0-9]+'); do
    [ "$stated" = "$hdecs" ] || \
      echo "$CURRENT: states $stated decisions, the register has $hdecs" >> "$TMP/handoffnums"
  done
  for stated in $(grep -oE '\*\*The instrumented suite is [0-9]+' "$TMP/handoff.nofence" | grep -oE '[0-9]+'); do
    [ "$stated" = "$hinstr" ] || \
      echo "$CURRENT: states $stated instrumented tests, the tree has $hinstr" >> "$TMP/handoffnums"
  done
  # **The plant count, added 2026-09-22 (`B-231`).** This document opened with *"nothing in this
  # file states a number from memory"* and then said the self-test plants 28 while the script
  # planted 34 and ran 41 — the claim most likely to be believed, in the sentence that disclaims
  # believing claims. The script counts its own plants on its first line, so the document can too.
  hplants=$(grep -c '^case_fails ' scripts/selftest.sh 2>/dev/null || echo 0)
  for stated in $(grep -oE '\*\*self-test plants [0-9]+' "$TMP/handoff.nofence" | grep -oE '[0-9]+'); do
    [ "$stated" = "$hplants" ] || \
      echo "$CURRENT: states $stated planted defects, selftest.sh has $hplants" >> "$TMP/handoffnums"
  done
  if [ -s "$TMP/handoffnums" ]; then
    err "the current handoff states a number the tree does not:"; sed 's/^/         /' "$TMP/handoffnums"
  else
    ok "the current handoff's stated numbers are the tree's ($hdecs decisions, $hinstr instrumented, $hplants planted)"
  fi
else
  dormant "handoff numbers — no current handoff to read"
fi

# ---------- 24. no board id is used twice ----------
# **A duplicate row is invisible to every other check here.** §18 asks whether a closed row names
# what closed it and §22 asks whether an open row is stale; both read rows one at a time, so
# `B-182` appearing twice — once with "repairs", once with "also fixes" — passed everything and
# was found only by an audit that counted. Two rows with one id mean every lookup returns
# whichever the reader met first, and closing one leaves the other open for ever.
if [ -f "$BOARD" ]; then
  dupes=$(grep -oE '^\|[ \t]*B-[0-9]+' "$BOARD" | grep -oE 'B-[0-9]+' | sort | uniq -d || true)
  if [ -n "$dupes" ]; then
    err "board ids used more than once:"; printf '%s\n' "$dupes" | sed 's/^/         /'
  else
    ok "every board id is used once ($(grep -cE '^\|[ \t]*B-[0-9]+' "$BOARD") rows)"
  fi
fi

# ---------- 25. every task a plan names has a row on the board ----------
# `B-136`. The plan re-audit of 2026-09-20 added four tasks; **three were never seeded here**.
# `T-048` got a row only when it was closed, and `T-049`/`T-050` had none at all — so
# `checkup`, which counts this file, under-reported the open work by three tasks for two days
# and nobody could see it, because a task that exists in one document and not the other is
# invisible from both sides unless something compares them.
#
# It reads `T-0NN` ids out of the plan TABLES (a row beginning `| **T-0NN**` or `| T-0NN`), not
# out of prose: a plan discusses tasks it does not own, and matching prose would demand a row for
# every id anybody ever mentioned. The board satisfies it by naming the id anywhere — a row of its
# own, or a row that carries it — because both make it visible to a reader and to `checkup`.
PLANS_DIR="${DOCS_DIR:-docs}/evidence/plans"
if [ -d "$PLANS_DIR" ] && [ -f "$BOARD" ]; then
  : > "$TMP/planmissing"
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    for tid in $(grep -oE '^\|[ ]*\*{0,2}T-[0-9]{3}' "$plan" 2>/dev/null | grep -oE 'T-[0-9]{3}' | sort -u); do
      grep -qF "$tid" "$BOARD" || echo "$(basename "$plan") names $tid and the board has no row carrying it" >> "$TMP/planmissing"
    done
  done
  if [ -s "$TMP/planmissing" ]; then
    err "task(s) a plan names that the board does not carry:"; sort -u "$TMP/planmissing" | sed 's/^/         /'
  else
    ok "every task id a plan table names is carried by a board row"
  fi
fi

# ---------- 26. the panel geometry a UX document states is the manifest's ----------
# **`DEC-0070` raised the panel's declared minimum and named two files; a third stated the old
# numbers and nobody looked** (`B-230`). `docs/ux/screens.md` said the 2D panel declares
# `1024dp × 640dp` and that `android:minHeight` is 560 dp, for two runs after the manifest read
# 800 and 736 — in the file a designer opens first, under three green gates.
#
# This is decidable and therefore a gate rather than a note: the manifest is the single home for
# those four numbers, and a document that restates one either agrees with it or is wrong. The
# patterns match the two forms `screens.md` actually uses — a `NNNdp × NNNdp` pair, and an
# `android:<attr> is NNN dp` sentence — because a heuristic over every number in a UX document
# would fire on every dp in the file and be deleted by the third person who hit it.
#
# It says nothing about the IMMERSIVE panel, which is a separate registration in Kotlin and did
# not move: §13 already resolves the symbols that carry it.
MANIFEST=app/src/main/AndroidManifest.xml
UXSCREENS=docs/ux/screens.md
if [ -f "$MANIFEST" ] && [ -f "$UXSCREENS" ]; then
  : > "$TMP/geom"
  m_attr() { grep -oE "android:$1=\"[0-9]+dp\"" "$MANIFEST" | grep -oE '[0-9]+' | head -1; }
  M_DW=$(m_attr defaultWidth); M_DH=$(m_attr defaultHeight)
  M_MW=$(m_attr minWidth);     M_MH=$(m_attr minHeight)
  if [ -z "$M_DW" ] || [ -z "$M_DH" ] || [ -z "$M_MW" ] || [ -z "$M_MH" ]; then
    err "26 the manifest declares no panel geometry — §26 cannot decide anything and says so rather than passing"
  else
    # Every `NNNdp × NNNdp` pair in the document must be one the manifest declares: the default
    # pair or the minimum pair. A pair naming neither is a number somebody typed.
    grep -oE '[0-9]+dp . [0-9]+dp' "$UXSCREENS" | while IFS= read -r pair; do
      _w=$(printf '%s' "$pair" | grep -oE '^[0-9]+')
      _h=$(printf '%s' "$pair" | grep -oE '[0-9]+dp$' | grep -oE '[0-9]+')
      if [ "$_w" = "$M_DW" ] && [ "$_h" = "$M_DH" ]; then continue; fi
      if [ "$_w" = "$M_MW" ] && [ "$_h" = "$M_MH" ]; then continue; fi
      echo "$UXSCREENS states \"$pair\"; the manifest declares ${M_DW}dp x ${M_DH}dp default, ${M_MW}dp x ${M_MH}dp minimum" >> "$TMP/geom"
    done
    # And a sentence naming one attribute by name must name its value.
    for _a in defaultWidth defaultHeight minWidth minHeight; do
      _true=$(m_attr "$_a")
      grep -oE "android:$_a\`? is \*?\*?[0-9]+ ?dp" "$UXSCREENS" | grep -oE '[0-9]+ ?dp' | grep -oE '[0-9]+' | while IFS= read -r _said; do
        [ "$_said" = "$_true" ] || \
          echo "$UXSCREENS says android:$_a is $_said dp; the manifest says $_true" >> "$TMP/geom"
      done
    done
    if [ -s "$TMP/geom" ]; then
      err "26 a UX document states a panel geometry the manifest does not:"
      sed 's/^/         /' "$TMP/geom"
    else
      ok "every panel geometry a UX document states is the manifest's (${M_DW}x${M_DH} default, ${M_MW}x${M_MH} minimum)"
    fi
  fi
else
  dormant "26 panel geometry — no manifest or no screens document"
fi

# ---------- staleness, printed rather than gated ----------
# `docs/evidence/verification.md` defines four staleness states and delegates computing them to
# `scripts/exposure.sh`, which did not exist until `T-044` (`F-15`) — so the states had never been
# computed once and no row had ever been marked `behind` despite every row being behind.
#
# **It is printed, never gated.** The ledger says so twice and the reason is load-bearing: the
# moment `behind` becomes a number to avoid printing, rows stop being re-observed and start being
# re-worded. A non-zero exit here would make that inevitable. Its own exit code is ignored for the
# gate's verdict but shown, because a staleness tool that could not read the ledger has failed at
# something and should say so out loud.
if [ -x scripts/exposure.sh ]; then
  echo "info:    staleness — $(bash scripts/exposure.sh 2>&1 || true)"
fi

# ---------- VERDICT — nothing may run after this block ----------
if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: documentation gate"
  exit 1
fi
echo "OK: documentation gate — shape $SHAPE · ${DECS:-0} decisions · ${OQS:-0} open questions · propagation backlog ${PROP_MISSING:-0} (floor $PROP_FLOOR) · retired residue ${RESIDUE:-0} (floor $RESIDUE_FLOOR)"
exit 0
