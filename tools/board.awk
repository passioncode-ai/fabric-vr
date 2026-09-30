# board.awk — the board by state, and every open row by priority. Read with the one splitter:
#
#   awk -f tools/mdtable.awk -f tools/board.awk docs/evidence/backlog.md
#
# `mdtable.awk` defines functions only and prints nothing on its own; this file is the main block
# the entry document used to leave as `...`. A row is `open` when its State cell says open anywhere
# (so `closed (a) / open (b)` counts as open work), `closed` otherwise. `device` marks a row that
# carries `deferred: device` (`DEC-0066`).
/^[ \t]*\|[ \t]*B-[0-9]/ {
    n = split_row($0, c)
    if (n != 10) { malformed++; next }
    s = c[9]; gsub(/\*/, "", s)
    if (s ~ /open/) {
        open_n++
        p = c[8]; gsub(/\*/, "", p)
        dev = (c[10] ~ /deferred: device/) ? "device" : "-"
        if (dev == "device") device_n++
        w = c[2]; gsub(/\*/, "", w)
        rows[open_n] = sprintf("%-6s prio %-2s sev %s  %-6s %s", c[1], p, c[5], dev, substr(w, 1, 96))
        prio[open_n] = p + 0
    } else closed_n++
}
END {
    printf "board: %d open (%d deferred: device) · %d closed · %d malformed\n", open_n, device_n, closed_n, malformed
    for (i = 1; i <= open_n; i++) for (j = i + 1; j <= open_n; j++)
        if (prio[j] > prio[i]) { t = prio[i]; prio[i] = prio[j]; prio[j] = t; t = rows[i]; rows[i] = rows[j]; rows[j] = t }
    for (i = 1; i <= open_n; i++) print rows[i]
}
