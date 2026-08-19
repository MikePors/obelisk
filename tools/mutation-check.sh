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
# Usage:  tools/mutation-check.sh          # all checks
#         tools/mutation-check.sh Rename   # only files matching a pattern
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
FILTER="${1:-}"
SRC="obelisk-core/src/main/java/dev/obelisk/core/refactor"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Snapshot the pristine sources so every mutation starts from a clean state.
cp -r "$SRC" "$WORK/pristine"
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

survived=0; killed=0
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
    printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "*** SURVIVED -- no test noticed ***"
    survived=$((survived + 1))
  else
    printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "killed"
    killed=$((killed + 1))
  fi
  restore "$file"
done

# Belt and braces: make sure we really did put everything back.
cp -rf "$WORK/pristine/." "$SRC/"

echo
echo "killed=$killed survived=$survived"
[ "$survived" -eq 0 ] || echo "Each SURVIVED check needs a test that fails when only that check is disabled."
exit $(( survived > 0 ? 1 : 0 ))
