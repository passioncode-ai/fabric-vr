#!/usr/bin/env bash
# selftest.sh — the gates' own negative tests: every check is watched FAILING.
#
# **Why this exists, in one sentence each.**
#
# `SI-06` says a gate is not a gate until a violation has been planted and watched failing it.
# Five sections of the documentation gate and one staleness script were written across two days
# with that rule stated in their own headers and in three decision records — and the only evidence
# any of them could fail was *prose claiming a plant had been watched*. A blind verification then
# found that **five of the six could be evaded**, three of them by a defect already present in
# the tree: a pipe inside a code span shifted the cells a parser read, a missing space after a
# leading pipe made a row invisible, and a stale number inside a fenced block satisfied a count
# check that read the raw file and took the first match.
#
# `scripts/exposure.sh` went further: its usage block cited "its own self-test" and there was no
# such thing. That is the `F-15` shape — a citation to an artefact that is not there, surviving a
# green gate — reproduced *inside the script written to close `F-15`*. This file is what makes
# that sentence true.
#
# **The rule this encodes:** a check that cannot be shown failing is a check nobody can trust,
# and the showing belongs in the repository, not in a commit message. Each case below plants one
# defect into a COPY of the tree, runs the real gate, and asserts the gate refuses it. A case
# that stops failing is a check that has stopped working.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PASS=0; FAIL=0
WORK=$(mktemp -d) || { echo "selftest: cannot create a work directory"; exit 2; }
trap 'rm -rf "$WORK"' EXIT

# Plant into a copy: the gate reads real paths, so the copy is a whole checkout-shaped tree.
# Cheap because only the documents are copied — the gate touches no source.
# **The pristine tree is built ONCE**, then each case copies it with a single `cp -R`.
#
# It was built per case, file by file, with a `mkdir` and a `cp` process for each of 311 paths —
# roughly seven thousand process spawns for a run of twenty-three cases. That is the kind of cost
# that gets a gate deleted rather than fixed, so it is fixed.
TEMPLATE="$WORK/pristine"
build_template() {
  mkdir -p "$TEMPLATE"
  # Tracked AND untracked-but-not-ignored: a self-test that copied only tracked files would test
  # the previous commit rather than the work in hand — `tools/mdtable.awk` was new and untracked,
  # and eleven cases "failed" for that one reason.
  git ls-files --cached --others --exclude-standard | grep -v '^third_party/' | while IFS= read -r f; do
    mkdir -p "$TEMPLATE/$(dirname "$f")"
    cp "$f" "$TEMPLATE/$f"
  done
  # `.git` and `third_party` are SYMLINKED, not copied. The first is for §9's SHA checks and the
  # count check's history; the second is a submodule of some thirty thousand files that the native
  # bridge check needs the headers of — copying it would dominate the whole run, and **skipping it
  # would make `check-native.sh` report "no whisper.h" and pass**, which is a self-test case that
  # proves nothing while looking like it passed.
  # **No plant may WRITE through either of these.** `cp -R` preserves symlinks, so a write to a
  # path under `.git/` or `third_party/` inside a case's copy lands on the REAL repository and the
  # REAL submodule. Reading them is the point — §9 needs the history and the native check needs
  # whisper's headers — and no case writes there today. The two native cases made perl-editing a
  # source file the idiom, one directory away from the submodule, so the rule is written down
  # rather than left to be discovered (`DEC-0062`). A case that needs to mutate a header copies
  # it into the tree first.
  ln -sfn "$ROOT/.git" "$TEMPLATE/.git"
  [ -d "$ROOT/third_party" ] && ln -sfn "$ROOT/third_party" "$TEMPLATE/third_party"
}

tree_hash() {
  ( cd "$WORK/t" && find . -path ./.git -prune -o -path ./third_party -prune -o -type f -print0 \
      2>/dev/null | xargs -0 shasum 2>/dev/null | shasum )
}

prepare() {
  rm -rf "$WORK/t"
  cp -R "$TEMPLATE" "$WORK/t"
}

# case <name> <planting-shell> <command> <expected-exit-is-nonzero> <must-print>
case_fails() {
  name=$1; plant=$2; cmd=$3; needle=$4
  prepare
  # `NOPLANT` for a case whose defect is in the COMMAND rather than in the tree — a typo'd flag,
  # a missing file, an argument the script must refuse. Those are real cases and they change
  # nothing on disk, so the emptiness check below would call them broken. Declared, never
  # inferred: a silent `true` is indistinguishable from a plant that stopped matching.
  if [ "$plant" = "NOPLANT" ]; then
    out=$( cd "$WORK/t" && eval "$cmd" 2>&1 ) && rc=0 || rc=$?
  else
    before=$(tree_hash)
    # **A plant that cannot be applied is a case that proves nothing, and it used to print `SKIP`
    # and return** — leaving the summary reading `0 failed` over a check nobody ran. It happened on
    # 2026-09-22 to a `perl` pattern written with `.` where the document had a multi-byte `×`: the
    # case was added, the suite reported 49 passed and 0 failed, and the gate it was written for
    # had never been exercised once. That is the same defect as a plant that changes nothing, which
    # this script already refuses by name — so it gets the same verdict.
    #
    # **This branch has no case of its own, and that is a limitation rather than an oversight**
    # (`B-193`'s precedent). A case proving it would need a plant whose command exits non-zero,
    # which is exactly what this branch now calls a failure — the case would report FAIL when it
    # worked. The evidence it is live is the run that found it: the geometry plant SKIPped, the
    # totals read `49 passed, 0 failed`, and after this change the same tree reports 50 with the
    # case actually exercised.
    if ! ( cd "$WORK/t" && eval "$plant" ); then
      echo "  FAIL $name — the plant could not be applied, so this case proves nothing about the gate"
      FAIL=$((FAIL + 1))
      return
    fi
    after=$(tree_hash)
  # **A plant that changed nothing is not a passing gate.** `perl -0pi -e 's/a/b/'` exits 0 when
  # its pattern matches nothing, so a plant whose target text has since been edited applies
  # silently and the case reports "the gate PASSED with the defect planted" — blaming the gate for
  # the plant going stale. It happened the first time a plant's line was rewritten (`DEC-0062`).
  # The tree is hashed before and after: no difference, no verdict.
    if [ "$before" = "$after" ]; then
      echo "  FAIL $name — the plant changed nothing, so this case proves nothing about the gate"
      FAIL=$((FAIL + 1))
      return
    fi
  fi
  # **One run, not two.** It used to run the gate once for its output and again for its exit code,
  # which doubled the cost of every case and — worse — meant the output being read and the code
  # being trusted came from two different invocations.
  # `if`, not a bare assignment: under `set -e` an assignment from a FAILING command substitution
  # exits the script — and every case here expects a failure, so the first one killed the run
  # after printing one line. An `if` context disarms `set -e` for exactly this.
  if [ "$plant" != "NOPLANT" ]; then
    if out=$( cd "$WORK/t" && eval "$cmd" 2>&1 ); then rc=0; else rc=$?; fi
  fi
  if [ "$rc" -eq 0 ]; then
    echo "  FAIL $name — the gate PASSED with the defect planted"
    FAIL=$((FAIL + 1))
  elif ! printf '%s' "$out" | grep -qF "$needle"; then
    echo "  FAIL $name — refused, but did not say why (expected to mention: $needle)"
    FAIL=$((FAIL + 1))
  else
    echo "  ok   $name"
    PASS=$((PASS + 1))
  fi
}

# **A gate can be wrong while exiting 0.** `check-device-gate.sh` printed
# `ok: … contains the change` over a ledger row reading **not run** — the exit code was right and
# the sentence was not, and no case here could say so, because `case_fails` wants a refusal and
# `case_passes` reads only the code. This third shape asserts the WORDS of a passing verdict,
# which is the only way to hold a gate that is allowed to pass to what it is allowed to claim.
# `plant` may be `NOPLANT` when the assertion is about the tree as it stands.
case_says() {
  name=$1; plant=$2; cmd=$3; needle=$4
  prepare
  if [ "$plant" != "NOPLANT" ]; then
    before=$(tree_hash)
    # **A plant that cannot be applied is a case that proves nothing, and it used to print `SKIP`
    # and return** — leaving the summary reading `0 failed` over a check nobody ran. It happened on
    # 2026-09-22 to a `perl` pattern written with `.` where the document had a multi-byte `×`: the
    # case was added, the suite reported 49 passed and 0 failed, and the gate it was written for
    # had never been exercised once. That is the same defect as a plant that changes nothing, which
    # this script already refuses by name — so it gets the same verdict.
    #
    # **This branch has no case of its own, and that is a limitation rather than an oversight**
    # (`B-193`'s precedent). A case proving it would need a plant whose command exits non-zero,
    # which is exactly what this branch now calls a failure — the case would report FAIL when it
    # worked. The evidence it is live is the run that found it: the geometry plant SKIPped, the
    # totals read `49 passed, 0 failed`, and after this change the same tree reports 50 with the
    # case actually exercised.
    if ! ( cd "$WORK/t" && eval "$plant" ); then
      echo "  FAIL $name — the plant could not be applied, so this case proves nothing about the gate"
      FAIL=$((FAIL + 1))
      return
    fi
    if [ "$before" = "$(tree_hash)" ]; then
      echo "  FAIL $name — the plant changed nothing, so this case proves nothing about the gate"
      FAIL=$((FAIL + 1))
      return
    fi
  fi
  if out=$( cd "$WORK/t" && eval "$cmd" 2>&1 ); then rc=0; else rc=$?; fi
  if [ "$rc" -ne 0 ]; then
    echo "  FAIL $name — the gate REFUSED (exit $rc) where it should pass and say: $needle"
    FAIL=$((FAIL + 1))
  elif ! printf '%s' "$out" | grep -qF "$needle"; then
    echo "  FAIL $name — it passed, but did not say: $needle"
    FAIL=$((FAIL + 1))
  else
    echo "  ok   $name"
    PASS=$((PASS + 1))
  fi
}
# And the other half, which is the one people forget: the gate must PASS on a clean tree.
# A check wired to fail on everything is as useless as one wired to pass on everything, and it
# is the shape a broken parser takes when its shape assertion is too strict.
case_passes() {
  name=$1; cmd=$2
  prepare
  if ( cd "$WORK/t" && eval "$cmd" >/dev/null 2>&1 ); then
    echo "  ok   $name"
    PASS=$((PASS + 1))
  else
    echo "  FAIL $name — the gate REFUSED a clean tree"
    FAIL=$((FAIL + 1))
  fi
}

build_template
# **The script states its own split**, so no document has to. Three of them restated it and all
# three were wrong in three different ways (`DEC-0062`) — a number copied into prose is wrong the
# next time a case is added, by somebody who will not think to look.
echo "selftest: the gates, watched failing — $(grep -c '^case_fails ' "$0") planted defects, $(grep -c '^case_passes ' "$0") controls"

GATE='bash scripts/check-docs.sh'

case_passes "control — the documentation gate passes a clean tree" "$GATE"

# ---- §16, the Environment vocabulary
case_fails "16 an undeclared Environment value" \
  "perl -0pi -e 's/\| jvm \| pass \|/| staging | pass |/' docs/evidence/verification.md" \
  "$GATE" "staging"

case_fails "16 an undeclared value hidden behind a piped cell" \
  "perl -0pi -e 's/\| REQ-033 \| The second secret/| REQ-033 | The second secret \`a | b\`/; s/\| jvm \| pass \|/| staging | pass |/' docs/evidence/verification.md" \
  "$GATE" "staging"

# ---- §17, stated test counts
#
# **The plants read the number instead of carrying it.** They carried `161` until `REQ-045` added
# ten tests to `:app`: the `perl` substitutions then matched nothing, the gate stayed green over
# an unplanted tree, and three cases reported a pass that proved nothing — which the framework's
# stale-plant detection caught, and which is the whole reason it exists. A plant that hardcodes a
# number retires itself the first time the number legitimately moves, and a retired plant is
# indistinguishable from a working one until somebody reads the list. Counted the same way the
# gate counts (the `@Test` annotation at the start of a line under `app/src/test`, not a mention
# of it in prose), so the two cannot disagree.
APP_TESTS=$(grep -rhE '^[[:space:]]*@Test([^[:alnum:]_]|$)' app/src/test 2>/dev/null | wc -l | tr -d ' ')

case_fails "17 a drifted count" \
  "perl -0pi -e 's/\*\*\d+ JVM tests/**999 JVM tests/' docs/modules/app.md" \
  "$GATE" "the tree has $APP_TESTS"

case_fails "17 a stale count fenced above the real claim" \
  "perl -0pi -e 's/\*\*\d+ JVM tests in \`:app\`\*\*/**999 JVM tests in \`:app\`**/' docs/modules/app.md && printf '\n\`\`\`text\n**$APP_TESTS JVM tests in \`:app\`**\n\`\`\`\n' >> docs/modules/app.md" \
  "$GATE" "states 999"

case_fails "17 the guarded claim deleted outright" \
  "perl -0pi -e 's/\*\*\d+ JVM tests in \`:app\`\*\*/a comfortable number of tests/' docs/modules/app.md" \
  "$GATE" "states no count"

# ---- §5, the wrapped field ----------------------------------------------------------------
#
# The propagation harvest read **one line** for eleven runs, and a `Consequences / affects:` list
# that wraps is the normal case: 63 of 263 targets sat on a continuation line and the gate printed
# `backlog: 0` over every one of them. Both cases below plant on the SECOND line of a field, which
# is the only place that distinguishes the fix from what it replaced.

case_fails "5 a document named on a continuation line does not cite the decision" \
  "perl -0pi -e 's/\`docs\/modules\/core-common.md\`, \`docs\/evidence\/backlog.md\`/\`docs\/modules\/core-common.md\`, \`docs\/OPEN_QUESTIONS.md\`, \`docs\/evidence\/backlog.md\`/' docs/DECISIONS.md" \
  "$GATE" "DEC-0046 -> docs/OPEN_QUESTIONS.md"

case_fails "5 a path named on a continuation line does not resolve" \
  "perl -0pi -e 's/\`docs\/evidence\/specs\/2026-09-19-v1-notes-core-carryover.md\`,/\`docs\/evidence\/specs\/2026-09-19-v1-notes-core-carryover.md\`, \`scripts\/no-such-gate.sh\`,/' docs/DECISIONS.md" \
  "$GATE" "scripts/no-such-gate.sh"

# ---- the UX linter: one fact, two homes -----------------------------------------------------
#
# `[U080]` compares an entry's status to its own `Coverage:` and cannot see the index above it.
# Three scenarios read `implemented` in their bodies and `draft` in the index for two runs, and
# the index is what a reader opens first.

case_fails "U081 the index and the entry disagree about a status" \
  "perl -0pi -e 's/^\| SCN-001 \| Write and keep a text note \| notes \| P-01 \| ST-001, FLW-01 \| implemented \|/| SCN-001 | Write and keep a text note | notes | P-01 | ST-001, FLW-01 | draft |/m' docs/ux/scenarios.md" \
  "python3 docs/ux/lint.py" "[U081]"

# `B-171`: a backticked path in UX prose resolves. Planted exactly as the row measured it.
case_fails "U082 a UX document cites a file that does not exist" \
  "printf '\\nSee \\x60docs/ux/does-not-exist.md\\x60 for the rest.\\n' >> docs/ux/scenarios.md" \
  "python3 docs/ux/lint.py" "[U082]"

# ---- the instrumented sources: a gate this framework cannot plant into, said out loud
#
# `check-all.sh` compiles `androidTest` since `REQ-045`'s group, because `REQ-048` changed
# `PermissionRequester.requestRecordAudio` and `app/src/androidTest` stopped compiling while every
# gate printed green for a whole group of work. There is **no case here for it**, and that is a
# limitation rather than an oversight: a plant would have to run a real Android build inside the
# copied tree, and the copy has no `local.properties` — Gradle fails there for an environment
# reason, so the case would "refuse" without the plant doing anything. A case that passes for the
# wrong reason is worse than no case, and this framework hashes the tree precisely to refuse that.
#
# The evidence that the check works is therefore NOT here: the break was real, it was watched
# failing (`:app:compileDebugAndroidTestKotlin`, `SpaceBackTest.kt:190`, "overrides nothing") and
# watched passing after the fix, in the run that added the task. If this ever needs a case, the
# honest shape is a fixture tree with its own `local.properties`, which is `B-193`.

# ---- the submodule pin: B-167, and the one line that made moving it look routine
case_fails "native a .gitmodules branch invites the submodule to move" \
  "printf '\tbranch = master\n' >> .gitmodules" \
  "bash scripts/check-native.sh 2>&1" "one-word supply-chain change"

case_fails "native the pin and the version the documents name disagree" \
  "perl -0pi -e 's/whisper\.cpp v1\.9\.4/whisper.cpp v9.9.9/' README.md" \
  "bash scripts/check-native.sh 2>&1" "the documents say v9.9.9"

# ---- B-104: the bridge is linked for 16 KB pages
case_fails "native the 16 KB page-size flag is gone" \
  "perl -0pi -e 's/ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON/ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=OFF/' feature-stt/build.gradle.kts" \
  "bash scripts/check-native.sh 2>&1" "will not load on a 16 KB-page kernel"

# ---- the pre-push hook: it must refuse by exit code, not by reading words
#
# `SI-03`, twice. The hook exists so the chain cannot be built wrong; a hook nobody watched
# refusing is the same unproven gate as any other. Planted by making `check-all.sh` exit non-zero
# in the copied tree — the hook must pass that code up rather than swallow it.
# **The hook reads a receipt now, not the gates** (2026-09-22). It used to run `check-all.sh`
# itself, which held git's SSH connection open for minutes and got the push killed by `SIGPIPE`
# **after** the gates had passed — see the hook's own header. So the cases below are about the
# receipt: absent, stale, and current. Each is fed a ref line on stdin the way git feeds one,
# because the hook must DRAIN that input or git takes the same signal from the other end.

REFLINE="printf 'refs/heads/f %040d refs/heads/f %040d\\n' 1 2"
# `GATES_RECEIPT` points at the COPY. Inside `$WORK/t` the `.git` here is a symlink to the real
# repository, and writing a receipt through it is exactly what this script's header forbids.
RCP="GATES_RECEIPT=./.gates-receipt"

case_fails "pre-push refuses a tree with no gate receipt" \
  "rm -f ./.gates-receipt; printf 'a line the receipt cannot know about\\n' >> README.md" \
  "$REFLINE | env $RCP bash .githooks/pre-push" "no gate receipt"

case_fails "pre-push refuses a receipt that names a different tree" \
  "env $RCP bash -c 'git ls-files -z | xargs -0 shasum -a 256 2>/dev/null | shasum -a 256 | cut -d\" \" -f1 > ./.gates-receipt'; printf 'a change the gates never saw\\n' >> README.md" \
  "$REFLINE | env $RCP bash .githooks/pre-push" "different tree"

case_passes "control — pre-push passes on the tree its receipt names" \
  "git ls-files -z | xargs -0 shasum -a 256 2>/dev/null | shasum -a 256 | cut -d' ' -f1 > ./.gates-receipt && $REFLINE | env $RCP bash .githooks/pre-push"

# The drain itself, asserted rather than assumed: a writer that fills the pipe and then reports
# whether ITS write survived. Without the `cat` the hook exits first and this prints `EPIPE`.
case_says "pre-push drains the ref list git writes to its stdin" NOPLANT \
  "git ls-files -z | xargs -0 shasum -a 256 2>/dev/null | shasum -a 256 | cut -d' ' -f1 > ./.gates-receipt; \
   GATES_RECEIPT=./.gates-receipt python3 -c \"import subprocess
p = subprocess.Popen(['bash', '.githooks/pre-push'], stdin=subprocess.PIPE,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
line = b'refs/heads/f ' + b'1'*40 + b' refs/heads/f ' + b'2'*40 + chr(10).encode()
try:
    p.stdin.write(line * 4096); p.stdin.close(); print('DRAINED')
except BrokenPipeError:
    print('EPIPE')
p.wait()\"" \
  "DRAINED"

# ---- the device gate: what a passing verdict is allowed to claim
#
# The audit of 2026-09-21 found this script planting nothing at all into `check-device-gate.sh`,
# and the gate meanwhile answering `ok: … contains the change` over a row that says **not run**.
# Six of the last seven rows said that, so the sentence covered nearly the whole ledger.
#
# **These cases run against a FIXTURE history, never this repository's** (`DEC-0101`). They used
# to take the real root commit as the base and rewrite the newest real row, which made them a
# claim about the shape of this repository's history: when it was re-published on 2026-09-30 as
# ONE commit, the base became HEAD, the gate exited at "no instrumented sources changed" before
# any check, and all three cases failed on a clean `main` while the gate itself was unchanged.
# The gate's subject is three facts — the suite changed since a base, the newest row's commit
# contains that change, and what the row's Result cell says — and a fixture can state all three
# without depending on which commits happen to exist.
#
# `devgate_fixture <result-cell>` turns the case's copy into its own repository: a base commit
# without `app/src/androidTest`, a commit that adds it, and a ledger row naming that commit with
# the given Result. **It removes the copy's `.git` only when that is the symlink `prepare` made,
# and refuses otherwise** — the symlink points at the real repository, and this script's header
# forbids writing through it. Removing a symlink never touches its target.
# `NOROW` builds the history and appends no row: the ledger then holds only rows whose commits
# this fixture has never had, which is the shape of the real ledger after publication.
devgate_fixture() {
  [ -L .git ] || { echo "devgate_fixture: .git is not the copy's symlink — refusing to touch it"; return 1; }
  rm .git
  git init -q . && fx add -A && fx rm -r -q --cached app/src/androidTest &&
    fx commit -q -m 'fixture: before the instrumented suite' && fx tag fixture-base &&
    fx add -A && fx commit -q -m 'fixture: the instrumented suite changes' || return 1
  if [ "$1" = NOROW ]; then
    printf '\nA line the fixture commits so the plant is visible in the tree.\n' >> README.md
  else
    printf '| 2026-10-01 | `%s` | — | `device-unknown` | %s | selftest fixture row |\n' \
      "$(git rev-parse --short HEAD)" "$1" >> docs/evidence/device-gate.md
  fi
  fx add -A && fx commit -q -m 'fixture: the ledger row'
}
# The fixture's git: no hooks, no signing, an identity of its own, so nothing in the machine's
# configuration can make a fixture commit fail or prompt.
fx() { git -c core.hooksPath=/dev/null -c commit.gpgsign=false -c user.name=selftest -c user.email=selftest@invalid "$@"; }
DEVGATE='bash scripts/check-device-gate.sh fixture-base'

case_says "device-gate an acknowledgement says so instead of claiming a run" \
  "devgate_fixture '**not run**'" \
  "$DEVGATE" "ACKNOWLEDGEMENT"

case_says "device-gate an executed row is reported as a run" \
  "devgate_fixture '**99 run, 0 red**'" \
  "$DEVGATE" "RAN: 99 run, 0 red"

case_fails "device-gate a newest row whose Result cell cannot be read" \
  "devgate_fixture ''" \
  "$DEVGATE" "certifies nothing"

# The ledger after publication: thirty rows, none of whose commits this history has. The gate
# said "has no commit in any row, so it records nothing" about it, which was false.
case_fails "device-gate a changed suite whose every row names a commit this history lacks" \
  "devgate_fixture NOROW" \
  "$DEVGATE" "pre-publication history"

# ---- §23, the current handoff's numbers are the tree's
#
# Planted the way the defect actually arrived: a number that was true when it was written and
# went stale under a register that kept growing. The `\d+` is deliberate — see the §17 note above
# on plants that carry a literal.
# **The plant finds the door the same way the gate does**, from the README — it named
# `2026-09-20-v2-entry.md` until that handoff was superseded, and then patched an archived file
# while the gate read the live one: the tree changed, so the staleness check passed it, and the
# case reported a gate that "passed with the defect planted". Same class as the literal `161`
# above, one indirection later.
CURRENT_HANDOFF=$(grep -oE 'docs/handoff/[0-9-]+[a-z0-9-]*\.md' README.md | head -1)

case_fails "23 the entry document states a decision count the register outgrew" \
  "perl -0pi -e 's/\*\*\d+ decisions\*\*/**33 decisions**/' $CURRENT_HANDOFF" \
  "$GATE" "states 33 decisions"

case_fails "23 the entry document undercounts the instrumented suite" \
  "perl -0pi -e 's/\*\*The instrumented suite is \d+/**The instrumented suite is 13/' $CURRENT_HANDOFF" \
  "$GATE" "states 13 instrumented"

# **A control, not a plant: prose that names the annotation is not a test.** The count read every
# occurrence of the text `@Test`, so one KDoc line made the entry document's "34" pass over a suite
# of 33 (audit 2026-09-23). The plant is that KDoc line's shape, in a file of its own; a gate that
# counts mentions again sees 34 against the document's 33 and refuses a tree that has no defect.
case_passes "control — 23 a KDoc line naming @Test is not counted as a test" \
  "printf '/**\\n * The \\x60@Test\\x60 body is prose here, not an annotation.\\n */\\n' > app/src/androidTest/SelftestMention.kt && $GATE"

# ---- §24, no board id twice
#
# The duplicate is planted as a COPY of a real row rather than a stub, because that is the shape
# that survived: `B-182` appeared twice with two differently-worded `Home` cells, and every
# row-at-a-time check passed it.
case_fails "26 a UX document states a panel minimum the manifest does not" \
  "perl -0pi -e 's/\\x60android:minHeight\\x60 is \\*\\*\\d+ dp\\*\\*/\\x60android:minHeight\\x60 is **560 dp**/' docs/ux/screens.md" \
  "$GATE" "the manifest says"

case_fails "26 a UX document states a panel size pair the manifest does not" \
  "perl -CSD -0pi -e 's/1024dp/999dp/' docs/ux/screens.md" \
  "$GATE" "the manifest declares"

case_fails "23 the entry document states a plant count the self-test outgrew" \
  "perl -0pi -e 's/\\*\\*self-test plants \\d+ defects\\*\\*/**self-test plants 99 defects**/' docs/handoff/2026-09-22-v3-entry.md" \
  "$GATE" "states 99 planted defects"

case_fails "24 a board row copied under an id that already exists" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/backlog.md'); t = p.read_text().split('\n')
for i, l in enumerate(t):
    if l.startswith('| B-1'):
        t.insert(i + 1, l)
        break
p.write_text('\n'.join(t))
P" \
  "$GATE" "used more than once"

# ---- §16b, the Auto column (B-174)
case_fails "16 an Auto value nobody declared" \
  "perl -0pi -e 's/\| jvm \| pass \|/| jvm | mostly |/' docs/evidence/verification.md" \
  "$GATE" "carries Auto value"

# And the forgiveness is not a hole: the restating row is what buys it, so removing that row must
# bring the refusal back for the one value the ledger corrected by appending.
case_fails "16 an out-of-vocabulary Auto value whose restating row is gone" \
  "perl -0pi -e 's/restated/re-worded/g' docs/evidence/verification.md" \
  "$GATE" "REQ-026 carries Auto value"

# ---- §25, a plan task with no board row (B-136)
case_fails "25 a plan names a task the board does not carry" \
  "perl -0pi -e 's/\| \*\*T-049\*\*/| **T-777**/' docs/evidence/plans/2026-09-20-v2-plan.md" \
  "$GATE" "names T-777"

# ---- §18, a closed board row names what closed it
case_fails "18 a closed row with no reference" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/backlog.md'); t = p.read_text().split('\n')
for i, l in enumerate(t):
    if l.startswith('| B-120 '):
        c = l.split('|')
        c[-3] = ' closed '          # B-120 is open; a plant must make it closed to be a defect
        c[-2] = ' it was fixed, obviously '
        t[i] = '|'.join(c)
p.write_text('\n'.join(t))
P" \
  "$GATE" "B-120"

case_fails "18 the same, on the row whose What cell carries a pipe" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/backlog.md'); t = p.read_text().split('\n')
for i, l in enumerate(t):
    if l.startswith('| B-139 '):
        c = l.split('|'); c[10] = ' closed '; c[11] = ' needs a headset '; t[i] = '|'.join(c)
p.write_text('\n'.join(t))
P" \
  "$GATE" "B-139"

# ---- §19, the retro's own rules
case_fails "19 a standing instruction with no retire-when" \
  "perl -0pi -e 's/\| A platform-level assertion for \"the scene composed\" exists\. \|/| |/' docs/evidence/retro.md" \
  "$GATE" "retire-when"

case_fails "19 the same, evading the leading-pipe pattern" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/retro.md'); s = p.read_text()
i = s.index('| SI-09 |'); j = s.index('\n', i) + 1
p.write_text(s[:j] + '|SI-10 | 2026-09-21 | \`41b4a95\` | **Planted.** | because |  | — | — |\n' + s[j:])
P" \
  "$GATE" "retire-when"

case_fails "19 more instructions than the heading's own cap" \
  "perl -0pi -e 's/\(max 10/(max 3/' docs/evidence/retro.md" \
  "$GATE" "caps it at 3"

# ---- §20, handoff currency
case_fails "20 a superseded handoff with its banner stripped" \
  "perl -0pi -e 's/^> \*\*SUPERSEDED[^\n]*\n//' docs/handoff/2026-09-19-research-handoff.md" \
  "$GATE" "SUPERSEDED banner"

case_fails "20 the README naming a second handoff" \
  "printf '\nAlso see [the old one](docs/handoff/2026-09-19-v1-build-handoff.md).\n' >> README.md" \
  "$GATE" "exactly one is current"

# ---- §21, a cited line range fits inside the file it cites
case_fails "21 a range past the end of the file it cites" \
  "perl -0pi -e 's/\`WhisperModel\.kt:21-25\`/\`WhisperModel.kt:104-114\`/' docs/modules/feature-stt.md" \
  "$GATE" "has 38 lines"

case_fails "21 a range that runs backwards" \
  "perl -0pi -e 's/\`WhisperModel\.kt:21-25\`/\`WhisperModel.kt:25-21\`/' docs/modules/feature-stt.md" \
  "$GATE" "runs backwards"

# ---- §9, commit references
case_fails "9 a commit reference that does not resolve" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/retro.md'); s = p.read_text()
i = s.index('| 2026-09-21 | Step 11')
p.write_text(s[:i] + '| 2026-09-21 | Planted | \`deadbee1\` | x | — |\n' + s[i:])
P" \
  "$GATE" "does not resolve"

# **The pre-publication list is a third state, not a hole** (`DEC-0101`). The case above is the
# half that matters most: a reference to a missing commit that is NOT on the list is still refused.
# The three below hold the list itself to what it claims — reported rather than passed, closed,
# and true about every entry.
case_says "9 a pre-publication reference is reported NOT_CHECKED, not passed" NOPLANT \
  "$GATE" "commit(s) of the pre-publication history"

case_fails "9 the pre-publication list grown to let a new dead reference through" \
  "printf 'deadbee1\n' >> docs/pre-publication-commits.txt && python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/retro.md'); s = p.read_text()
i = s.index('| 2026-09-21 | Step 11')
p.write_text(s[:i] + '| 2026-09-21 | Planted | \`deadbee1\` | x | — |\n' + s[i:])
P" \
  "$GATE" "the pre-publication list changed"

case_fails "9 a listed commit that this history has" \
  "git rev-parse --short HEAD >> docs/pre-publication-commits.txt" \
  "$GATE" "as pre-publication, but reachable from HEAD"

# The other branch of §9 — a commit that RESOLVES but is unreachable from HEAD, the
# amended-away case — cannot be planted from a fixture, because a dangling commit is not stable
# across a `gc` and inventing one inside the copy would need a second repository. It was watched
# by hand on 2026-09-21: a detached commit was made, its SHA planted, and the gate reported
# `resolves but is NOT reachable from HEAD`. Recorded here rather than silently skipped.

# ---- a gate that silently loses a check (`B-164`)
case_fails "164 a whole section deleted from a gate script" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('scripts/check-strings.sh'); t = p.read_text().split('\n')
start = next(i for i, l in enumerate(t) if l.startswith('# --- 3. the library modules'))
end = next(i for i, l in enumerate(t) if i > start and 'every library-module string has a reference' in l) + 2
p.write_text('\n'.join(t[:start] + t[end:]))
P" \
  "bash scripts/check-all.sh" "a check went away"

# ---- B-159: a view-model coroutine with no owner for what escapes it
#
# The defect is planted as the shape it actually had — a bare `viewModelScope.launch` in a screen
# — rather than as a stub file, because the rule's whole value is that it holds against a launch
# written next week by somebody reading `VoiceViewModel.kt` instead of `Guarded.kt`. The plant
# rewrites the FIRST `launchGuarded {` in that file; if the helper is ever renamed the plant
# matches nothing, the framework's tree-hash check says so, and this case retires loudly rather
# than reporting a pass it did not earn.
SEAMS='bash scripts/check-seams.sh'

case_passes "seams the tree as it stands has no unowned view-model coroutine" "$SEAMS"

case_fails "159 a viewModelScope.launch with nothing owning its exception" \
  "perl -0pi -e 's/launchGuarded \{/viewModelScope.launch {/' app/src/main/kotlin/ai/passioncode/fabricvr/ui/VoiceViewModel.kt" \
  "$SEAMS" "has no owner for what escapes it"

# ---- every shell script parses
SHELL_GATE='bash scripts/check-shell.sh'

case_passes "shell every script parses as it stands" "$SHELL_GATE"

case_fails "shell an apostrophe inside a single-quoted awk program" \
  "perl -0pi -e \"s/from the id of the tag object/from the tag object's id/\" scripts/check-docs.sh" \
  "$SHELL_GATE" "syntax error"

# ---- the native bridge still compiles
NATIVE='bash scripts/check-native.sh'

case_passes "native the bridge compiles as it stands" "$NATIVE"

case_fails "native a syntax error in the bridge" \
  "perl -0pi -e 's/std::lock_guard<std::mutex> guard\(g_runs_mutex\);/std::lock_guard<std::mutex> guard(g_runs_mutex)/' feature-stt/src/main/cpp/fabricvr_whisper.cpp" \
  "$NATIVE" "expected ';'"

case_fails "native a misspelled whisper_full_params field" \
  "perl -0pi -e 's/params\.abort_callback +=/params.abort_calback              =/' feature-stt/src/main/cpp/fabricvr_whisper.cpp" \
  "$NATIVE" "no member named"

# ---- exposure.sh: it exits 0 on any finding, and 2 only when it cannot read the ledger
EXPO='bash scripts/exposure.sh'

case_passes "exposure reports whatever it finds and exits 0" "$EXPO"

case_fails "exposure refuses a ledger with no rows it can read" \
  "printf '# nothing here\n' > docs/evidence/verification.md" \
  "$EXPO" "refusing to report zero"

case_fails "exposure refuses a ledger that is not there" \
  "rm -f docs/evidence/verification.md" \
  "$EXPO" "no ledger at"

case_fails "exposure refuses a row it cannot address" \
  "python3 - <<'P'
import pathlib
p = pathlib.Path('docs/evidence/verification.md'); t = p.read_text().split('\n')
for i, l in enumerate(t):
    if l.startswith('| REQ-033 |'):
        c = l.split('|'); del c[3]; t[i] = '|'.join(c)   # one cell fewer: the shape must be refused
p.write_text('\n'.join(t))
P" \
  "$EXPO" "cannot address"

case_fails "exposure refuses an unknown argument" "NOPLANT" \
  "bash scripts/exposure.sh --deatil" "unknown argument"

echo "selftest: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
