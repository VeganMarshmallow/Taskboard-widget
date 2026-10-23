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
exit "$status"
