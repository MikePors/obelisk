#!/usr/bin/env bash
# Fault injection for the REPAIR half of transform-repair-verify.
#
# The sibling script, tools/mutation-check.sh, asks: "if this CHECK were
# gone, would a test notice?" For a verify* method that question has no
# answer. A verifier fires only when a repair produced something wrong, so
# with the repair working it is unreachable by construction -- disabling it
# changes nothing, and it sits on tools/mutation-allowlist.txt as a BACKSTOP
# forever. That file is honest about the cost: such an entry "is also
# UNVERIFIED. It could be silently broken and nothing would notice."
#
# This script closes the gap from the other side. Rather than removing the
# verifier, it BREAKS THE REPAIR the verifier guards, and requires the
# verifier to notice. Each corruption lives in tools/repair-mutations.txt
# with its justification.
#
# What counts as noticing: a failing test whose stack trace names the
# verifier. Merely going red is not enough -- a broken repair can just as
# easily emit code that fails to compile or trips an unrelated assertion,
# neither of which says anything about whether the verifier works.
#
# Usage:  tools/repair-mutation-check.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
SRC="obelisk-core/src/main/java/dev/obelisk/core/refactor"
TABLE="tools/repair-mutations.txt"
WORK="$(mktemp -d)"

# Same discipline as mutation-check.sh: this edits sources in place, so
# restore on ANY exit. An interrupted run must not leave a corrupted repair
# in the working tree -- that is a silently wrong refactoring engine, which
# is worse than a broken build because it still passes compilation.
cp -r "$SRC" "$WORK/pristine"
cleanup() {
  [ -d "$WORK/pristine" ] && cp -rf "$WORK/pristine/." "$SRC/"
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

[ -f "$TABLE" ] || { echo "missing $TABLE"; exit 1; }

printf '%-32s %-42s %s\n' REPAIR VERIFIER RESULT
printf '%.0s-' {1..104}; echo

caught=0; missed=0; broken=0
while IFS='|' read -r file search replace marker _why; do
  case "$file" in ''|'#'*) continue ;; esac

  cp -rf "$WORK/pristine/." "$SRC/"

  # Apply the corruption. An entry whose search text no longer matches
  # exactly once is a FAILURE, never a skip: a mutation that silently
  # applies to nothing reports success while measuring nothing, which is
  # precisely how three bulk edits in this repo went wrong.
  if ! python3 - "$SRC/$file" "$search" "$replace" <<'PY'
import sys
path, search, replace = sys.argv[1], sys.argv[2].replace('\\n', '\n'), sys.argv[3].replace('\\n', '\n')
src = open(path).read()
n = src.count(search)
if n != 1:
    sys.stderr.write(f"search text matched {n} times, expected exactly 1\n")
    sys.exit(1)
open(path, 'w').write(src.replace(search, replace))
PY
  then
    printf '%-32s %-42s %s\n' "${file%%.java}" "$marker" "*** STALE ENTRY -- search text did not match exactly once ***"
    broken=$((broken + 1)); continue
  fi

  if ! mvn -o -q clean compile >/dev/null 2>&1; then
    # The corruption must produce a running engine that emits a WRONG
    # reference. If it will not compile, it is testing javac, not the
    # verifier -- rewrite the entry.
    printf '%-32s %-42s %s\n' "${file%%.java}" "$marker" "*** UNUSABLE -- corrupted source does not compile ***"
    broken=$((broken + 1)); continue
  fi

  mvn -o -q test >/dev/null 2>&1
  if [ $? -eq 0 ]; then
    printf '%-32s %-42s %s\n' "${file%%.java}" "$marker" "*** SURVIVED -- repair broken, nothing noticed ***"
    missed=$((missed + 1))
  elif grep -rqF "$marker" obelisk-core/target/surefire-reports/ 2>/dev/null; then
    printf '%-32s %-42s %s\n' "${file%%.java}" "$marker" "caught by its verifier"
    caught=$((caught + 1))
  else
    printf '%-32s %-42s %s\n' "${file%%.java}" "$marker" "*** WRONG FAILURE -- red, but $marker never ran ***"
    missed=$((missed + 1))
  fi
done < "$TABLE"

echo
echo "caught=$caught missed=$missed broken=$broken"
[ "$missed" -eq 0 ] || echo "A SURVIVED or WRONG FAILURE result means the verifier is not guarding its
repair. Fix the verifier -- do not delete the mutation."
[ "$broken" -eq 0 ] || echo "A STALE or UNUSABLE entry measures nothing. Update tools/repair-mutations.txt
to match the current source."
exit $(( (missed + broken) > 0 ? 1 : 0 ))
