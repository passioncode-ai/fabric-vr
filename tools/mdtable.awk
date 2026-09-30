# mdtable.awk — split a GitHub-flavoured markdown table row into cells, correctly.
#
# **Why this file exists.** Five parsers were written across two scripts in one day, each one
# `awk -F'|'` indexing cells from the left, and every one of them was wrong in the same way. A
# markdown cell may contain a pipe two ways — escaped as `\|`, or inside an inline code span —
# and `-F'|'` splits on both, shifting every cell to its right. The failures were not loud:
#
#   * `check-docs.sh` §18 read `State` as `$10`; two board rows carry a `|` in their `What` cell,
#     so `$10` landed on the Prio number, `state !~ /closed/` matched, and the rows were skipped
#     **without being counted** — a closed row with no reference could hide there for ever.
#   * §19 read `Retire when` as `$7`; the one standing instruction with an escaped pipe put its
#     `Because` prose there, so a row with a genuinely empty retirement trigger passed.
#   * `exposure.sh` read `Observed at` as `$6` and reported a row `current` that was 20 commits
#     `behind` — the worst of the three, because the summary line still reads as a measurement.
#
# The irony is on the record: `exposure.sh`'s own header explains this exact hazard. It is right,
# and it protects the **last** cell — a note may contain anything because nothing is read after
# it. It says nothing about the cells to the LEFT of the one being read, and that is where every
# one of these failures lived.
#
# `split_row(line, cells)` fills `cells[1..n]` with the row's cells, trimmed, and returns n.
# Escaped pipes and pipes inside backtick spans stay inside their cell. The leading and trailing
# empty fields that `|a|b|` produces are dropped, so `cells[1]` is the first real cell — NOT the
# empty string left of the leading pipe, which is the other half of why the old indices were
# off-by-one in people's heads.
function split_row(line, cells,    i, c, n, cur, intick, esc) {
    n = 0; cur = ""; intick = 0; esc = 0
    for (i = 1; i <= length(line); i++) {
        c = substr(line, i, 1)
        if (esc)                  { cur = cur c; esc = 0; continue }
        if (c == "\\")            { esc = 1; cur = cur c; continue }
        if (c == "`")             { intick = !intick; cur = cur c; continue }
        if (c == "|" && !intick)  { cells[++n] = trim(cur); cur = ""; continue }
        cur = cur c
    }
    cells[++n] = trim(cur)
    # `| a | b |` yields an empty first and last cell; drop them so index 1 is the first column.
    if (cells[1] == "") { for (i = 1; i < n; i++) cells[i] = cells[i + 1]; delete cells[n]; n-- }
    if (n > 0 && cells[n] == "") { delete cells[n]; n-- }
    return n
}

function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }

# Strip the decoration a table cell may carry around its value, so a vocabulary check compares
# values rather than emphasis. `**—**` and `—` are the same answer; `` `jvm` `` and `jvm` are too.
function bare(s) { gsub(/[ \t`*]/, "", s); return s }

# `looks_like_sha(s)` — does this cell contain an abbreviated commit id?
#
# The first version of this test was `s ~ /[0-9a-f]{7}/`, which accepts any seven-character run
# of hex letters — and English has those: `defaced`, `effaced`, `accede`. It also accepts a
# fragment of a longer word. This one requires a WHOLE token of 7–40 hex characters containing
# at least one digit, which no English word satisfies. Deliberately not anchored to backticks:
# this project's older board rows write the commit bare, and a check that demanded decoration
# would red on 80 true rows to reject one imaginary false one.
function looks_like_sha(s,    i, n, parts) {
    n = split(s, parts, /[^0-9A-Za-z]+/)
    for (i = 1; i <= n; i++)
        if (parts[i] ~ /^[0-9a-f]{7,40}$/ && parts[i] ~ /[0-9]/) return 1
    return 0
}
