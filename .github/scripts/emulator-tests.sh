#!/usr/bin/env bash
# Runs inside the Android emulator: all instrumented tests (with reports), then the screenshot
# tests again on an installed build so the PNGs can be pulled before anything is uninstalled
# (connectedAndroidTest uninstalls the app when it finishes). PNGs land in ./screenshots/.
set -uo pipefail
pkg="io.github.veganmarshmallow.taskboard"

./gradlew :app:connectedDebugAndroidTest --console=plain 2>&1 | tee connected.log
status=${PIPESTATUS[0]}

./gradlew :app:installDebug :app:installDebugAndroidTest --console=plain 2>&1 | tee -a connected.log
# Every *ScreenshotTest class, so features can add their own screenshot class without editing a shared one.
classes="$(find app/src/androidTest -name '*ScreenshotTest.kt' -exec basename {} .kt \; | sort | sed "s/^/$pkg./" | paste -sd, -)"
adb shell am instrument -w -e class "$classes" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee instrument.log

mkdir -p screenshots
for name in $(adb shell run-as "$pkg" ls files/screenshots 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$pkg" cat "files/screenshots/$name" > "screenshots/$name"
done
ls -l screenshots

grep -q '^OK (' instrument.log || { echo "Screenshot run did not pass"; status=1; }

# #29: a widget stuck on an error recovers by itself after an app update (a new APK installed over
# the old one). An instrumentation cannot reinstall its own app, so AppUpdateRecoveryTest runs in
# two steps around `adb install -r`. Its output is appended to instrument.log for the annotations.
update_step() {
  adb shell am instrument -w -e appUpdateStep "$1" -e class "$pkg.AppUpdateRecoveryTest#$1" \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "update-$1.log" >> instrument.log
  cat "update-$1.log"
  grep -q '^OK (1 test)' "update-$1.log"
}
adb shell appwidget grantbind --package "$pkg" || adb shell cmd appwidget grantbind --package "$pkg"
if update_step prepare &&
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee -a instrument.log &&
  update_step verify; then
  echo "App-update recovery passed"
else
  echo "App-update recovery did not pass" | tee -a instrument.log
  status=1
fi
exit "$status"
