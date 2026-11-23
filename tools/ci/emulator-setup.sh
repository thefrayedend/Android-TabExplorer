#!/usr/bin/env bash
# Installs the app, gives it the permissions a person would, and runs the emulator tests.
set -e
cd "$(dirname "$0")/../.."
gradle --no-daemon :app:installDebug
P=com.tabexplorer.android
adb shell appops set $P MANAGE_EXTERNAL_STORAGE allow
for perm in POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO; do adb shell pm grant $P android.permission.$perm || true; done
bash tools/ci/emulator-run.sh
