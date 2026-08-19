#!/usr/bin/env bash
# Poor-man's mutation testing for obelisk's refusal checks.
#
# Every safety check in this codebase is a `reject*` or `verify*` method
# called from a refactor's entry point. This script disables each such CALL in turn, runs
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
# SCOPE, so that "0 survived" is not over-read: this measures only calls to
# named `reject*`/`verify*` methods. A refusal written as an inline `throw
# new RefactorException` is invisible to it. The genuine safety ones have
# been extracted into named methods for exactly this reason; what remains
# inline is IO/resolver failure reporting ("Failed to write changes",
# "Could not resolve X"), which fires on environment failure rather than on
# an unsafe transformation and would need fault injection to exercise.
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
  grep -rn --include='*.java' -E '^\s+(reject|verify)[A-Za-z]+\(' "$SRC" \
    | grep -v 'private static' \
    | sed 's/:\s*/:/' \
    | while IFS=: read -r file line rest; do
        [ -n "$FILTER" ] && [[ "$file" != *"$FILTER"* ]] && continue
        echo "$(basename "$file")|$line|$(echo "$rest" | sed 's/^ *//')"
      done
)

# A reject*/verify* call that is NOT a plain statement (e.g. used as an `if`
# condition, or an argument) cannot be disabled by prefixing `if (false) `,
# so the target discovery above would silently skip it and the run would
# still report success. That happened for real: turning a check into a
# repair-driving predicate moved it into an `if` condition and quietly
# removed it from the measured set, while the total stayed the same because
# another target was added in the same change.
#
# So: find them explicitly and fail. Either make the call a plain statement,
# or rename it -- a predicate that reports a fact is not a `reject*`.
MISSHAPEN=$(grep -rn --include='*.java' -E '(reject|verify)[A-Z][A-Za-z]*\(' "$SRC" \
  | grep -vE ':[[:space:]]*(reject|verify)[A-Z][A-Za-z]*\(' \
  | grep -vE 'private static|\*|//' || true)

printf '%-34s %-46s %s\n' FILE CHECK RESULT
printf '%.0s-' {1..110}; echo

ALLOWLIST="tools/mutation-allowlist.txt"
is_allowlisted() {  # $1=file  $2=call text
  [ -f "$ALLOWLIST" ] || return 1
  local method="${2%%(*}"
  grep -v '^[[:space:]]*#' "$ALLOWLIST" | grep -q "^${1}|${method}[[:space:]]*\(#.*\)\?$"
}

survived=0; killed=0; subsumed=0; unmeasurable=0
for target in "${TARGETS[@]}"; do
  IFS='|' read -r file line call <<< "$target"
  restore "$file"
  # Disable just this one call, keeping the line numbering identical.
  awk -v n="$line" 'NR==n { sub(/^([[:space:]]*)/, "&if (false) ") } { print }' \
      "$SRC/$file" > "$WORK/mutated" && cp -f "$WORK/mutated" "$SRC/$file"

  if ! mvn -o -q clean compile >/dev/null 2>&1; then
    # An unmeasurable check is a GAP, not a pass. This used to `continue`
    # without counting, so a call that could not be mutated (e.g. the body of
    # an arrow lambda, where prefixing `if (false) ` is a syntax error)
    # vanished from the tally entirely and the run still reported success.
    printf '%-34s %-46s %s\n' "$file" "${call:0:44}" "*** UNMEASURABLE -- could not be mutated ***"
    unmeasurable=$((unmeasurable + 1))
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
if [ -n "$MISSHAPEN" ]; then
  echo
  echo "*** reject*/verify* calls that this tool CANNOT disable (not plain statements):"
  echo "$MISSHAPEN" | sed 's|^.*/||'
  misshapen=$(echo "$MISSHAPEN" | wc -l)
  unmeasurable=$(( unmeasurable + misshapen ))
fi

total=$(( killed + subsumed + survived + unmeasurable ))
echo "killed=$killed subsumed=$subsumed survived=$survived unmeasurable=$unmeasurable  (of $total targets)"
[ "$survived" -eq 0 ] || echo "Each SURVIVED check needs a test that fails when only that check is disabled,
or an entry in $ALLOWLIST explaining which broader check subsumes it."
[ "$unmeasurable" -eq 0 ] || echo "An UNMEASURABLE check cannot be verified by this tool. Rewrite the call so a
statement prefix is legal (e.g. give an arrow lambda a block body)."
exit $(( (survived + unmeasurable) > 0 ? 1 : 0 ))
