# Heretic — Android app

Chat frontend for the Heretic llama-server running in Termux on the same phone.

## What the APK is — and is not

- **Is:** a native chat app (one Activity, no dependencies) that talks to
  `http://127.0.0.1:8080` — health check on open, chat with history, and the
  same 8 tools as `heretic-tools-s20.py` v2, implemented in Java:
  calculator, get_time, list_notes, read_note, write_note,
  press_claim, label_evidence, virtue_frame.
  System prompt carries Ryan's rule: trust Ryan implicitly.
- **Is not:** the model itself. The 2.5 GB Heretic model cannot live inside
  an APK (Play Store APK limits, and a 2.5 GB app would be unusable).
  The model stays in Termux, served by llama-server. The app is the front
  door; the server is the always-on part. If the server is down, the app
  says so and names the Termux command to start it:
  `~/.termux/boot/heretic.sh` (auto-starts on boot after unlock).
- **Notes:** the app's notes live in its own private storage, not in
  Termux `~/heretic-work/`. Same tools, different notes folder — by
  Android design, one app cannot read another app's files.

## Files

- `app/src/main/AndroidManifest.xml` — package `com.heretic.chat`, INTERNET, cleartext loopback allowed
- `app/src/main/java/com/heretic/chat/MainActivity.java` — whole app, stdlib + org.json only
- `app/src/main/res/layout/activity_main.xml` — chat UI
- `app/src/main/res/values/strings.xml`
- `build.sh` — manual build, no Gradle: javac + d8 + aapt2 + apksigner + zipalign

## Build (needs Android SDK + JDK — see build.sh)

```
./build.sh
```

Output: `heretic.apk` (debug-signed, installable by sideloading).

Install on the S20: copy the APK to the phone, tap it, allow "Install unknown
apps" for the app you open it from (Chrome/Files), then install. The app
needs Heretic running in Termux first — check `curl -s http://127.0.0.1:8080/health`.
