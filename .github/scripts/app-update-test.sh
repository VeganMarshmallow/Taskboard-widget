#!/usr/bin/env bash
# #29 / #42: AppUpdateRecoveryTest around a real app update. An instrumentation cannot reinstall
# its own app, so this runs its `prepare` step, `adb install -r`, then its `verify` step.
#
# `am instrument` force-stops the target app before it starts, which kills its process and drops
# any broadcast still queued for it. Started right after the install, `verify` could drop the
# MY_PACKAGE_REPLACED broadcast before AppUpdatedReceiver ran, so nothing restarted the refresh
# and the test timed out (#42). So `verify` starts only once the receiver has logged that it
# handled the update (or after RECEIVER_WAIT_S, so a receiver that never runs still fails the test).
# On failure, the JobScheduler state and the app's logcat are printed so the failure explains itself.
#
# Usage: app-update-test.sh <apk>. Appends the test output to instrument.log (for the annotations).
# Env: ADB (default adb), RECEIVER_WAIT_S (default 60), POLL_S (seconds, default and minimum 1).
set -uo pipefail
pkg="io.github.veganmarshmallow.taskboard"
apk="$1"
adb="${ADB:-adb}"
wait_s="${RECEIVER_WAIT_S:-60}"
poll_s="${POLL_S:-1}"
[ "$poll_s" -ge 1 ] 2>/dev/null || poll_s=1 # 0 or junk would never time out
# Logged by AppUpdatedReceiver when it handles the update, whether or not it restarts a refresh.
receiver_tag="AppUpdatedReceiver"
receiver_mark="handled MY_PACKAGE_REPLACED"

update_step() {
  "$adb" shell am instrument -w -e appUpdateStep "$1" -e class "$pkg.AppUpdateRecoveryTest#$1" \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "update-$1.log" >> instrument.log
  cat "update-$1.log"
  grep -q '^OK (1 test)' "update-$1.log"
}

receiver_ran() {
  "$adb" logcat -d -s "$receiver_tag:I" 2>/dev/null | grep -qF "$receiver_mark"
}

# Waits for AppUpdatedReceiver, never fails: a receiver that never runs is the test's to report.
await_receiver() {
  local waited=0
  until receiver_ran; do
    if [ "$waited" -ge "$wait_s" ]; then
      echo "AppUpdatedReceiver did not report MY_PACKAGE_REPLACED within ${wait_s}s; running verify anyway"
      return 0
    fi
    sleep "$poll_s"
    waited=$((waited + poll_s))
  done
  echo "AppUpdatedReceiver handled the update after ~${waited}s"
}

diagnostics() {
  echo "::group::App-update diagnostics: JobScheduler"
  "$adb" shell dumpsys jobscheduler "$pkg" 2>&1 | head -n 200
  echo "::endgroup::"
  echo "::group::App-update diagnostics: logcat"
  "$adb" logcat -d -v time 2>&1 |
    grep -E "$pkg|$receiver_tag|BoardJob|JobScheduler|Force stopping|instr" | tail -n 200
  echo "::endgroup::"
}

"$adb" shell appwidget grantbind --package "$pkg" || "$adb" shell cmd appwidget grantbind --package "$pkg"
if update_step prepare &&
  "$adb" logcat -c &&
  "$adb" install -r -t "$apk" 2>&1 | tee -a instrument.log &&
  await_receiver &&
  update_step verify; then
  echo "App-update recovery passed"
else
  echo "App-update recovery did not pass" | tee -a instrument.log
  diagnostics
  exit 1
fi
