#!/usr/bin/env bash
# Runs inside the Android emulator: all instrumented tests (with reports), then the @Screenshot
# tests again on an installed build so the PNGs can be pulled before anything is uninstalled
# (connectedAndroidTest uninstalls the app when it finishes). PNGs land in ./screenshots/.
set -uo pipefail
pkg="io.github.veganmarshmallow.taskboard"

./gradlew :app:connectedDebugAndroidTest --console=plain 2>&1 | tee connected.log
status=${PIPESTATUS[0]}

./gradlew :app:installDebug :app:installDebugAndroidTest --console=plain 2>&1 | tee -a connected.log
# Every class annotated @Screenshot (#22), so a feature adds its own screenshot class without editing a shared one
# or this script. A class that saves PNGs without the annotation would silently drop them, so that fails the run.
# NUL-separated so paths with spaces survive; `xargs -r` so no matches doesn't make grep read stdin.
unmarked="$(grep -rlZE 'Screenshots\.save|ScreenshotCapture\.' app/src/androidTest --include='*.kt' |
  grep -zvE '/(Screenshots|ScreenshotCapture)\.kt$' | xargs -0r grep -L '^@Screenshot[[:space:]]*$' || true)"
while IFS= read -r f; do
  [ -n "$f" ] || continue
  echo "::error file=$f,title=Screenshots::saves PNGs but is not annotated @Screenshot"
  status=1
done <<< "$unmarked"
adb shell am instrument -w -e annotation "$pkg.Screenshot" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee instrument.log

mkdir -p screenshots
for name in $(adb shell run-as "$pkg" ls files/screenshots 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$pkg" cat "files/screenshots/$name" > "screenshots/$name"
done
ls -l screenshots

grep -q '^OK (' instrument.log || { echo "Screenshot run did not pass"; status=1; }
grep -q '^OK (0 tests)' instrument.log && { echo "Screenshot run found no @Screenshot tests"; status=1; }

# #29: a widget stuck on an error recovers by itself after an app update (see the script).
.github/scripts/app-update-test.sh app/build/outputs/apk/debug/app-debug.apk || status=1
exit "$status"
