#!/usr/bin/env bash
# Poor-man's mutation testing for obelisk's refusal checks.
#
# Every safety check in this codebase is a `reject*` method called from a
# refactor's entry point. This script disables each such CALL in turn, runs
# the suite, and reports whether any test noticed.
#
# Why this exists: a test that asserts on an error message can pass because a
# DIFFERENT check produced a similar message, so the check it was written for
# is never exercised. That happened for real -- the enum-constant test in
# NameCaptureTest passed with its check disabled, because another check
# refused the same case with overlapping wording. Nothing but mutation
# testing catches that, and doing it by hand is how it got missed.
#
# A check reported as SURVIVED is either untested, or tested only by a test
# that some other check also satisfies. Both are worth fixing.
#
# Some checks are subsumed by a broader one and can never be killed. Those
# live in tools/mutation-allowlist.txt with a justification each; they are
# reported as SUBSUMED and do not fail the run, so the exit code stays a
# meaningful signal about NEW gaps rather than permanent noise.
#
# Usage:  tools/mutation-check.sh          # all checks
#         tools/mutation-check.sh Rename   # only files matching a pattern
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
FILTER="${1:-}"
SRC="obelisk-core/src/main/java/dev/obelisk/core/refactor"
WORK="$(mktemp -d)"

# Snapshot the pristine sources so every mutation starts from a clean state.
cp -r "$SRC" "$WORK/pristine"

# Restore sources on ANY exit -- including Ctrl-C, SIGTERM, or the harness
# killing a long background run. This script edits files in place, so without
# this an interrupted run leaves a live `if (false)` in the working tree,
# which is both a broken build and something that could be committed by
# accident. (Learned the hard way: a killed run did exactly that.)
cleanup() {
  [ -d "$WORK/pristine" ] && cp -rf "$WORK/pristine/." "$SRC/"
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM
restore() { cp -f "$WORK/pristine/$1" "$SRC/$1"; }

mapfile -t TARGETS < <(
  grep -rn --include='*.java' -E '^\s+reject[A-Za-z]+\(' "$SRC" \
    | grep -v 'private static' \
    | sed 's/:\s*/:/' \
    | while IFS=: read -r file line rest; do
        [ -n "$FILTER" ] && [[ "$file" != *"$FILTER"* ]] && continue
        echo "$(basename "$file")|$line|$(echo "$rest" | sed 's/^ *//')"
      done
)

printf '%-34s %-46s %s\n' FILE CHECK RESULT
printf '%.0s-' {1..110}; echo

ALLOWLIST="tools/mutation-allowlist.txt"
is_allowlisted() {  # $1=file  $2=call text
  [ -f "$ALLOWLIST" ] || return 1
  local method="${2%%(*}"
  grep -v '^[[:space:]]*#' "$ALLOWLIST" | grep -q "^${1}|${method}[[:space:]]*\(#.*\)\?$"
}

survived=0; killed=0; subsumed=0
for target in "${TARGETS[@]}"; do
  IFS='|' read -r file line call <<< "$target"
  restore "$file"
  # Disable just this one call, keeping the line numbering identical.
  awk -v n="$line" 'NR==n { sub(/^([[:space:]]*)/, "&if (false) ") } { print }' \
      "$SRC/$file" > "$WORK/mutated" && cp -f "$WORK/mutated" "$SRC/$file"

  if ! mvn -o -q clean compile >/dev/null 2>&1; then
    printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "SKIPPED (mutation did not compile)"
    restore "$file"; continue
  fi
  if mvn -o -q test >/dev/null 2>&1; then
    if is_allowlisted "$file" "$call"; then
      printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "SUBSUMED (allowlisted)"
      subsumed=$((subsumed + 1))
    else
      printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "*** SURVIVED -- no test noticed ***"
      survived=$((survived + 1))
    fi
  else
    printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "killed"
    killed=$((killed + 1))
  fi
  restore "$file"
done

echo
echo "killed=$killed subsumed=$subsumed survived=$survived"
[ "$survived" -eq 0 ] || echo "Each SURVIVED check needs a test that fails when only that check is disabled,
or an entry in $ALLOWLIST explaining which broader check subsumes it."
exit $(( survived > 0 ? 1 : 0 ))
