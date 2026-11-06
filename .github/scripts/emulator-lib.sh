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

# Makes sure no emulator from a failed attempt is left before the runner starts a new one: asks it to
# quit, waits up to <timeout> seconds (default 30) for qemu to exit, then kills it and removes the
# AVD lock files it may have left (or the next launch says another instance is running).
stop_emulator() {
  local deadline=$((SECONDS + ${1:-30}))
  timeout 10 adb emu kill > /dev/null 2>&1 || true
  while pgrep -f qemu-system > /dev/null; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      pkill -9 -f qemu-system || true
      sleep 1
      break
    fi
    sleep 1
  done
  rm -rf "${ANDROID_AVD_HOME:-$HOME/.android/avd}"/*.avd/*.lock
}

# Restarts adb's server (#80), so a retry does not inherit a connection to the emulator that died.
restart_adb() {
  timeout 10 adb kill-server > /dev/null 2>&1 || true
  timeout 30 adb start-server > /dev/null 2>&1 || true
}

# What to do after an emulator attempt (#80). Usage: emulator_verdict <outcome> <results-dir> <attempt>
# Prints `pass` (the attempt succeeded), `retry` (it failed before any instrumented test result
# existed, and this was the first attempt) or `fail` (a test result exists, so a test or the
# screenshot run really failed, or the retry failed too). At most one retry per job.
emulator_verdict() {
  if [ "$1" = success ]; then echo pass
  elif has_test_results "$2"; then echo fail
  elif [ "${3:-1}" -lt 2 ]; then echo retry
  else echo fail; fi
}
