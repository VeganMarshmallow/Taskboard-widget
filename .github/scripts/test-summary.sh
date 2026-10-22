#!/usr/bin/env bash
# Writes a JUnit results table to $GITHUB_STEP_SUMMARY and a notice annotation with the totals.
# Usage: test-summary.sh <test-results-dir> [label]
set -uo pipefail
dir="$1"
label="${2:-Core unit tests}"
tests=0; failures=0; errors=0; skipped=0
rows=""
shopt -s nullglob
for f in "$dir"/TEST-*.xml; do
  line="$(grep -m1 '<testsuite ' "$f")"
  attr() { printf '%s' "$line" | sed -n "s/.* $1=\"\([0-9]*\)\".*/\1/p"; }
  name="$(printf '%s' "$line" | sed -n 's/.* name="\([^"]*\)".*/\1/p')"
  t=$(attr tests); fl=$(attr failures); e=$(attr errors); s=$(attr skipped)
  tests=$((tests + ${t:-0})); failures=$((failures + ${fl:-0})); errors=$((errors + ${e:-0})); skipped=$((skipped + ${s:-0}))
  rows+="| ${name##*.} | ${t:-0} | ${fl:-0} | ${e:-0} |"$'\n'
done
{
  echo "### $label: $tests run, $failures failed, $errors errors, $skipped skipped"
  echo
  echo "| Suite | Tests | Failures | Errors |"
  echo "|---|---|---|---|"
  printf '%s' "$rows"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "::notice title=$label::$tests run, $failures failed, $errors errors, $skipped skipped"
