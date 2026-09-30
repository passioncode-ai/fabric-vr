#!/usr/bin/env python3
"""R2, as a check rather than as a rule nobody re-reads.

A `viewModelScope.launch { … }` body runs on `Dispatchers.Main.immediate`. Reading the Keystore,
stat-ing a file, encoding JSON or building an HTTP call inside one is a stall on the thread that
draws — and in the Space that is a frozen rectangle hanging in the room while the passthrough world
behind it keeps moving.

Fourteen call sites were found by hand in the 2026-09-20 audit. A list of fourteen is not a fix,
because the fifteenth is written next week by somebody reading the file rather than the audit.
This is what stops that.

**Its honest baseline was 1, not the eleven `T-019` predicted, and the gap is worth knowing.**
`T-017` moved every view model's `Graph.` reach into a constructor default argument, so the
blocking call became `modelStore()` rather than `Graph.modelStore()` and this scanner stopped
seeing it — the check got weaker at the exact moment the code got better, silently. The remedy
was not a longer seam list: `T-019`'s rule R1 made those accessors `suspend`, so they switch
their own thread and a launch body calling one is safe by construction and the compiler enforces
the rest. What is left here is the work a view model does **itself** — a WAV write, a JSON
encode, an HTTP call — which no accessor can own and which nothing else checks.

It brace-counts rather than greps, because the question is *which block* the seam is in and a line
regex cannot see block structure. Run it directly for a report, or through `MainThreadPolicyTest`,
which asserts the finding count is zero.

Exit code is the output: 0 clean, 1 findings, 2 the check proved nothing.
"""
from __future__ import annotations

import pathlib
import sys

# Seams that block. Each one is a Keystore decrypt, a filesystem call, CPU proportional to the
# note base, or an HTTP client construction — all measured in the audit, none of them cheap.
SEAMS = (
    "Graph.",
    "File(",
    "WavWriter",
    "settings.get",
    ".readBytes()",
    ".writeBytes(",
    "encodeToString",
    "newCall(",
)

# What makes a block innocent: it moved itself off the drawing thread. `flowOn` counts because a
# flow that declares its own upstream context is the same promise made one layer down.
EXCUSES = ("withContext", "flowOn")

# **Every spelling that starts a coroutine on the view model's own scope.**
#
# It was the single literal `viewModelScope.launch`, and `B-159` moved all thirty-six of those
# behind `launchGuarded` — which gave the escaping exception an owner and, in the same stroke,
# left this scanner matching **nothing at all**. That is the failure this file's own canary
# comment names, arriving through a change that made the code better: `T-017` did it once before
# by turning `Graph.modelStore()` into `modelStore()`, and the note above records it. A list, so
# the next wrapper is one line here rather than a silence.
#
# `guarded` is `SettingsViewModel`'s own binding of `launchGuarded`. One is a suffix of the
# other, so the boundary check below is what keeps a `launchGuarded` from being reported a second
# time as a bare `guarded`: the character before it is `h`, which is part of a name. `_starts`
# returns a set of indices for the same reason — two spellings must never yield one body twice.
LAUNCHES = ("viewModelScope.launch", "launchGuarded", "guarded")


def _line_of(text: str, i: int) -> str:
    return text[text.rfind("\n", 0, i) + 1 : text.find("\n", i)].lstrip()


def _starts(text: str):
    """Yields every index at which a coroutine on the view model's scope begins, in order."""
    seen = set()
    for token in LAUNCHES:
        i = 0
        while True:
            i = text.find(token, i)
            if i < 0:
                break
            # Not a longer identifier that merely ends in this one: `launchGuarded` must not be
            # reported a second time as a bare `guarded`, and a `reguarded` is not this.
            before = text[i - 1] if i else " "
            after = text[i + len(token)] if i + len(token) < len(text) else " "
            # **`@` excluded, and comment lines skipped.** A `return@guarded` is a jump out of a
            # block, not the start of one, and the KDoc on `stopThenDelete` contains the phrase
            # "rather than guarded by" — both would open a block at the next `{` anywhere in the
            # file and report a seam that is nowhere near them. `check-seams.sh` records the rule
            # this obeys: a gate that reds on the prose explaining its own rule gets switched off
            # rather than obeyed.
            named = not (before.isalnum() or before in "_.@") and not (after.isalnum() or after == "_")
            if named and not _line_of(text, i).startswith(("*", "//", "/*")):
                seen.add(i)
            i += 1
    return sorted(seen)


def blocks(text: str):
    """Yields (start_line, body) for every view-model coroutine block, nesting included."""
    for i in _starts(text):
        brace = text.find("{", i)
        if brace < 0:
            return
        depth, j = 0, brace
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        yield text.count("\n", 0, i) + 1, text[brace : j + 1]


def findings(path: pathlib.Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    out = []
    for line, body in blocks(text):
        if any(e in body for e in EXCUSES):
            continue
        hit = [s for s in SEAMS if s in body]
        if hit:
            out.append(f"{path}:{line}: launch body reaches {', '.join(hit)} with no {'/'.join(EXCUSES)}")
    return out


def scanned_blocks(roots: list[pathlib.Path]) -> list[tuple[pathlib.Path, int]]:
    """How many blocks the scan actually looked at — the number a silent pass has to justify."""
    out = []
    for root in roots:
        for f in sorted(root.rglob("*.kt")):
            out += [(f, line) for line, _ in blocks(f.read_text(encoding="utf-8"))]
    return out


def scan(roots: list[pathlib.Path]) -> list[str]:
    out = []
    for root in roots:
        for f in sorted(root.rglob("*.kt")):
            out += findings(f)
    return out


# The canary, for the reason this project keeps relearning: a scanner that matches nothing prints
# the same silence as a clean tree. It exercises THIS module's own functions — a canary that tests
# a different pattern proves that Python can read a file, which was never in doubt.
CANARY_DIRTY = """
class Fake : ViewModel() {
    fun bad() {
        viewModelScope.launch {
            val m = Graph.whisperModel()
            _state.value = m
        }
    }
}
"""
# The same defect behind `B-159`'s wrapper, twice, because that wrapper is how the bare spelling
# stopped appearing in this tree at all. Without these two the scanner would report a clean tree
# for a codebase it can no longer see into.
CANARY_DIRTY_GUARDED = """
class Fake : ViewModel() {
    /** A doc comment that merely says the word guarded, and a jump out of one below. */
    fun bad() {
        launchGuarded {
            val m = Graph.whisperModel()
            _state.value = m
        }
    }
    fun alsoBad() {
        guarded {
            val bytes = File(root, name).readBytes()
            _state.value = bytes.size
        }
    }
    fun jumpsOut() {
        launchGuarded {
            val m = withContext(io) { Graph.whisperModel() } ?: return@guarded
            _state.value = m
        }
    }
}
"""
CANARY_CLEAN = """
class Fake : ViewModel() {
    fun good() {
        viewModelScope.launch {
            val m = withContext(io) { Graph.whisperModel() }
            _state.value = m
        }
    }
    fun alsoGood() {
        launchGuarded {
            val m = withContext(io) { Graph.whisperModel() }
            _state.value = m
        }
    }
}
"""


def self_test(tmp: pathlib.Path) -> str | None:
    dirty = tmp / "Dirty.kt"
    dirty.write_text(CANARY_DIRTY)
    guarded = tmp / "DirtyGuarded.kt"
    guarded.write_text(CANARY_DIRTY_GUARDED)
    clean = tmp / "Clean.kt"
    clean.write_text(CANARY_CLEAN)
    if len(findings(dirty)) != 1:
        return f"the scanner found {len(findings(dirty))} reaches in a file planted with exactly one"
    if len(findings(guarded)) != 2:
        return (
            f"the scanner found {len(findings(guarded))} of 2 reaches behind launchGuarded — "
            "the tree has no bare viewModelScope.launch left, so it would scan nothing"
        )
    if findings(clean):
        return "the scanner reported a block that DOES switch context — it would red on correct code"
    return None


def main(argv: list[str]) -> int:
    import tempfile

    repo = pathlib.Path(argv[1]) if len(argv) > 1 else pathlib.Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory() as td:
        broken = self_test(pathlib.Path(td))
    if broken:
        print(f"ERR:     {broken} — this check proves nothing")
        return 2

    roots = [p for p in (repo / "app/src/main", repo / "core-notes/src/main", repo / "feature-assistant/src/main") if p.is_dir()]
    if not roots:
        print(f"ERR:     no source roots under {repo}")
        return 2
    found = scan(roots)
    if found:
        print(f"ERR:     {len(found)} launch block(s) do blocking work on the main thread (R2):")
        for f in found:
            print("         " + f.replace(str(repo) + "/", ""))
        return 1
    print(
        f"ok:      none of the {len(scanned_blocks(roots))} view-model coroutines reaches a "
        "blocking seam without switching (canary matched)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
