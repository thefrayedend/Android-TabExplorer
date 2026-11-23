# TabExplorer for Android: project notes

Jon's file explorer for Android phones and tablets. It speaks TabExplorer's sharing language, so Jon can
right-click a file on his PC and Send to > his phone, and share from his phone to his PCs.
**Don't change the TabExplorer desktop app from here** (it lives in `E:\Explorer`, another chat).
Original brief: `E:\AndroidExplorer\android-handoff.zip` (ANDROID-HANDOFF.md).

## How to work with Jon
1. Build what he asks, the first time. No extra features; suggest ideas, build only on yes.
2. "We're just talking" means only talk.
3. Short, plain answers. Bring an opinion, a catch, a better idea.
4. Every feature gets an automated test. Say plainly what was tested and what wasn't (emulator vs real phone).
5. Keep this file and CHANGELOG.md current.
6. Never put his email or any password into anything.
7. Sideloaded APK only (like PrintHarbor). APKs go to `E:\AndroidExplorer\apk`.

## Layout
- `share/`  Kotlin JVM library, no Android classes: crypto, frames, discovery (UDP 47591), send/receive (TCP).
  Same protocol as TabExplorer's `lib/share.js` (copy in `tools/interop/share.js`; **the code wins** over any text).
- `core/`   Kotlin JVM library: sorting, file kinds, copy/move/rename/delete, Recycle Bin (30 days).
- `app/`    The Android app: Jetpack Compose UI, Media3 player, services.
- `tools/interop/`  `share.js` (TabExplorer's own), `peer.js`, `interop.js` (test harness), `vectors.js` (fixed-key test values).
- `tools/ci/`  Scripts the emulator job uses.

## Build and test (everything runs on GitHub Actions: `.github/workflows/build.yml`)
- No Android SDK in Claude's workspace, so CI is the compiler. Push, then read the run:
  `gh run list -R thefrayedend/android-tabexplorer`; logs through `gh run view <id> --log-failed`.
- Job `build`: `:share:test` and `:core:test` (JVM, includes real interop with Node running share.js),
  then `:app:assembleRelease`, then publishes the APK to the release **latest**.
- Job `emulator`: Android 34 emulator; `app/src/androidTest` drives the real app (files, viewers, music,
  Recycle Bin, sharing) and runs `PcInteropTest` against TabExplorer's real sharing code on the build machine.
- Download the APK: `gh api -H "Accept: application/octet-stream" repos/thefrayedend/android-tabexplorer/releases/assets/<id>`.
- Local build needs Gradle 8.10.2 and JDK 17 (no wrapper checked in: could not be generated offline).
- Signed with the sideload key `app/tabexplorer-sideload.jks` (committed on purpose, like PrintHarbor's), so each build installs over the last.

## Sharing, short version (see share.js for the truth)
Identity = 24-hex id + X25519 key (public key travels as base64 SPKI DER); code = SHA-256 of that base64 text.
Discovery: UDP JSON to 239.255.47.59:47591 + broadcast. Send: plain hellos, then HKDF'd AES-256-GCM frames.
Our extra announcement field `kind` ("phone"/"tablet") is ignored by TabExplorer 1.7.0.
Received files go to `Download/TabExplorer`. A `MulticastLock` is held while sharing runs.
"Ready to receive" = foreground service (`ShareService`); off = receives only while the app is open.

## Not built yet / known gaps
- QR pairing, add-by-address and the `kind` icon need work on the desktop side too (handoff section 5).
- Version 2: SMB/NAS, storage-use screen, ZIP, search, groups. Later: Office/3D previews, 7z/RAR, app updates via TabExplorer.
- Split view on tablets, drag between folders: not built.
- Not yet tested on a real phone (emulator only).
