#!/usr/bin/env bash
set -u

test_status=0
./gradlew :app:connectedDebugAndroidTest --stacktrace || test_status=$?
mkdir -p app/build/screenshots
adb pull /sdcard/Android/data/io.github.openmusic/files/screenshots/. app/build/screenshots/ || true
adb logcat -d > app/build/smoke-logcat.txt || true
exit "$test_status"
