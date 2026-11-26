#!/usr/bin/env bash
set -u

# This emulator's Pixel Launcher can ANR while prewarming. Stop that external process
# before launching the test Activity; OpenMusic failures remain visible to the runner.
adb shell am force-stop com.google.android.apps.nexuslauncher || exit $?

test_status=0
./gradlew :app:connectedDebugAndroidTest --stacktrace || test_status=$?
# AGP collects additionalTestOutputDir before it uninstalls the test app.
# Screenshots are in build/outputs/connected_android_test_additional_output/.
adb logcat -d > app/build/smoke-logcat.txt || true
exit "$test_status"
