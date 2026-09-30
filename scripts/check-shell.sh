#!/usr/bin/env bash
# check-shell.sh — every shell script in this repository parses.
#
# **`bash -n`, and it exists because one mistake made it three times.**
#
# An apostrophe inside a single-quoted `awk` program closes the string, and the rest of the
# program is then parsed as shell. Written three separate times in one session — `awk's record
# number`, `the tag object's id`, and once before that — each time inside a comment explaining
# something careful, each time discovered by running the gate and reading a `syntax error near
# unexpected token` from a line that looks like awk.
#
# The class is wider than the apostrophe: an unbalanced quote, a heredoc whose terminator moved, a
# `$(` never closed. All of them are decided by the parser in milliseconds, and none of them is
# decided by anything else here until the script is run — which for `selftest.sh`, at three
# minutes, is an expensive way to learn about a typo.
#
# It parses; it does not lint. Whether a script is CORRECT is what the gates and `selftest.sh`
# answer. This answers whether `bash` can read it at all, which is the question that was being
# answered by accident.
set -eu

: > /tmp/.check-shell-bad.$$
trap 'rm -f /tmp/.check-shell-bad.$$' EXIT

n=0
while IFS= read -r f; do
  n=$((n + 1))
  bash -n "$f" 2>>/tmp/.check-shell-bad.$$ || echo "  ^ in $f" >> /tmp/.check-shell-bad.$$
done <<EOF
$(git ls-files --cached --others --exclude-standard '*.sh' | grep -v '^third_party/')
EOF

if [ -s /tmp/.check-shell-bad.$$ ]; then
  echo "FAIL: a shell script does not parse:"
  sed 's/^/         /' /tmp/.check-shell-bad.$$
  exit 1
fi
echo "OK: every shell script parses ($n)"
