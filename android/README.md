# FP-10 Android app

A thin Android wrapper around the FP-10 page, so Bluetooth works in both directions.

Why: in Chrome, Web Bluetooth could send to the piano but never received anything back (no key
presses, no SysEx replies), so recording failed. Android's own Bluetooth MIDI driver
(`MidiManager.openBluetoothDevice`) pairs the link properly and receives, the same path a
"MIDI BLE Connect" app uses.

How it fits together:
- `MainActivity` shows https://elyorkhakimov.github.io/fp10-tester/ in a WebView, so page updates
  need no new APK. Offline, it falls back to the `index.html` bundled at build time
  (served from `https://appassets.androidplatform.net/`; its saved takes are separate from the online page's).
- `PianoLink` opens the piano: USB if plugged in, otherwise a Bluetooth scan for the BLE-MIDI
  service (then a bonded FP-10 as a fallback) and `openBluetoothDevice`.
- `Bridge` is `window.FP10Native`. The page's `nativeMIDIAccess()` turns it into a Web MIDI stand-in,
  so the page's USB code path runs unchanged. Saving a take writes to `Download/FP-10`.

## Build

Reuses the Pad Games build environment (Colima profile `android`, image `pad-games-android-env`,
Gradle cache; see `Documents/atom-midi/android/README.md`). This folder is listed under `mounts`
in `~/.colima/android/colima.yaml`.

```sh
android/env/run.sh ./gradlew assembleRelease
# -> android/app/build/outputs/apk/release/app-release.apk
```

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each release. Signed with the Pad Games
key, copied to `android/signing/` (git-ignored; this repo is public). Updates must use the same key.
