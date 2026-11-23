#!/usr/bin/env bash
# Runs on the build machine while the emulator is up: starts TabExplorer's real sharing code as a PC,
# runs the instrumented tests (the app on the emulator), and has the PC send to the phone while they run.
set -u
trap 'pkill -f interop.js 2>/dev/null; kill $(jobs -p) 2>/dev/null; adb forward --remove-all 2>/dev/null; true' EXIT
cd "$(dirname "$0")/../.."
W=$(mktemp -d)
mkdir -p "$W/out" "$W/pcfiles/FromPc"
echo "hello from the PC" > "$W/pcfiles/FromPc/hello.txt"
head -c 700000 /dev/urandom > "$W/pcfiles/FromPc/data.bin"

# the "PC" that takes files from the phone
(cd tools/interop && node interop.js receive "$W/id-recv" "$W/out" 47600 47601 > "$W/recv.log" 2> "$W/recv.err") &
for i in $(seq 1 30); do grep -q '^READY ' "$W/recv.log" 2>/dev/null && break; sleep 1; done
READY=$(grep '^READY ' "$W/recv.log" | sed 's/^READY //')
[ -n "$READY" ] || { echo "the PC side did not start"; cat "$W/recv.err"; exit 1; }
PCID=$(node -e "console.log(JSON.parse(process.argv[1]).id)" "$READY")
PCPUB=$(node -e "console.log(JSON.parse(process.argv[1]).pub)" "$READY")
adb forward tcp:47591 tcp:47591

# the "PC" that sends to the phone, as soon as the phone says who it is
(
  for i in $(seq 1 300); do
    ID=$(adb shell cat /sdcard/Download/tx-identity.json 2>/dev/null | tr -d '\r')
    case "$ID" in \{*) break;; esac; sleep 1
  done
  sleep 2
  cd tools/interop && node interop.js send "$W/id-send" 47610 47611 "$ID" "$W/pcfiles/FromPc" > "$W/send.log" 2> "$W/send.err"
) &
SENDER=$!

adb shell rm -f /sdcard/Download/tx-identity.json
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pcId="$PCID" \
  -Pandroid.testInstrumentationRunnerArguments.pcPub="$PCPUB" \
  -Pandroid.testInstrumentationRunnerArguments.pcPort=47600
RC=$?
kill $SENDER 2>/dev/null; wait $SENDER 2>/dev/null
echo "--- the PC that received from the phone:"; (cd "$W/out" && find . -type f | sort)
echo "--- PC sender log:"; cat "$W/send.log" "$W/send.err" 2>/dev/null | tail -15
[ -f "$W/out/Photos/a.txt" ] && [ "$(cat "$W/out/Photos/a.txt")" = "alpha from the phone" ] && [ "$(stat -c %s "$W/out/Photos/2026/b.bin")" = "600000" ] || { echo "FILES FROM THE PHONE DID NOT ARRIVE"; RC=1; }
grep -q '^RESULT {"ok":true' "$W/send.log" || { echo "PC DID NOT SEND TO THE PHONE"; RC=1; }
exit $RC
