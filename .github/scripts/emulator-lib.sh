#!/usr/bin/env bash
# Helpers for the emulator jobs (#58). Source it: . .github/scripts/emulator-lib.sh

# True when <dir> holds at least one JUnit result (TEST-*.xml), i.e. the instrumented tests ran.
has_test_results() {
  compgen -G "$1/TEST-*.xml" > /dev/null
}

# Waits up to <timeout> seconds (default 180) until the device reports boot completed and its
# package manager answers, i.e. system_server is up. After a snapshot restore, boot_completed is 1
# at once while system_server can still be restarting (DeadSystemException on the first adb shell).
wait_for_system() {
  local deadline=$((SECONDS + ${1:-180})) interval="${2:-2}"
  until [ "$(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] &&
    timeout 30 adb shell pm path android 2>/dev/null | grep -q '^package:'; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "::error title=Emulator::system not ready after ${1:-180}s"
      return 1
    fi
    sleep "$interval"
  done
}
