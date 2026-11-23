#!/usr/bin/env bash
# Installs the app, gives it the permissions a person would, and runs the emulator tests.
set -e
cd "$(dirname "$0")/../.."
# a slow CI emulator makes system apps (the launcher) "not respond"; that dialog would steal the test's window
adb shell settings put global hide_error_dialogs 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
gradle --no-daemon :app:installDebug
P=com.tabexplorer.android
adb shell appops set $P MANAGE_EXTERNAL_STORAGE allow
for perm in POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO; do adb shell pm grant $P android.permission.$perm || true; done
bash tools/ci/emulator-run.sh
