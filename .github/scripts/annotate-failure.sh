#!/usr/bin/env bash
# Turns a failed Gradle log into GitHub annotations (readable via the check-runs API) and a
# step summary. Usage: annotate-failure.sh <gradle-log> <step-name>
set -uo pipefail
log="$1"
step="$2"
max=40
count=0

# Annotation messages must be single-line; GitHub decodes %0A as a newline.
encode() { sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"} {print}' | sed -e 's/%0A$//'; }

emit() { # title, message
  [ "$count" -ge "$max" ] && return
  count=$((count + 1))
  printf '::error title=%s::%s\n' "$1" "$2"
}

# 1. Kotlin compiler errors: "e: file:///path/File.kt:12:5 Message"
while IFS= read -r line; do
  emit "Kotlin ($step)" "$(printf '%s' "${line#e: }" | sed 's|file://[^ ]*/taskboard-widget/||' | encode)"
done < <(grep -E '^e: ' "$log" | head -n 25)

# 2. Failed tests: "SomeTest > someMethod FAILED" plus the next few lines (the assertion).
while IFS= read -r block; do
  [ -n "$block" ] && emit "Test failed ($step)" "$block"
done < <(awk '/ FAILED$/ && !/^> Task/ {n=6; buf=$0; next} n>0 {buf=buf "%0A" $0; n--; if(n==0){print buf; buf=""}} END{if(buf!="")print buf}' "$log" | head -n 15)

# 3. Lint / AAPT / manifest errors.
while IFS= read -r line; do
  emit "Build error ($step)" "$(printf '%s' "$line" | sed 's|/home/runner/work/[^ ]*/taskboard-widget/||' | encode)"
done < <(grep -E '(: Error: |error: |ERROR: |AAPT: error|Manifest merger failed)' "$log" | head -n 15)

# 4. Gradle's "What went wrong" block.
wrong="$(awk '/^\* What went wrong:/ {on=1; next} /^\* (Try|Exception is):/ {on=0} on' "$log" | grep -v '^\s*$' | head -n 25 | encode)"
[ -n "$wrong" ] && emit "Gradle: what went wrong ($step)" "$wrong"

if [ "$count" -eq 0 ]; then
  emit "Failed ($step)" "$(tail -n 30 "$log" | encode)"
fi

{
  echo "### ❌ $step failed"
  echo '```'
  { grep -E '^e: | FAILED$|: Error: ' "$log" | head -n 40; awk '/^\* What went wrong:/ {on=1} /^\* Try:/ {on=0} on' "$log" | head -n 30; } || true
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
