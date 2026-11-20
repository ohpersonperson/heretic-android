#!/usr/bin/env bash
# Heretic APK — manual build, no Gradle, no Android Studio.
# Needs: JDK 17+ (javac, keytool) and Android SDK (android.jar + build-tools).
# Set ANDROID_HOME to your SDK, e.g. ~/Android/Sdk
#   export ANDROID_HOME=$HOME/Android/Sdk
#   ./build.sh
# Output: heretic.apk (debug-signed, sideloadable).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK path}"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/34.0.0"
[ -f "$PLATFORM" ] || { echo "Missing $PLATFORM — install: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""; exit 1; }
rm -rf build && mkdir -p build/gen build/obj build/dex
"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip
"$BT/aapt2" link -o build/heretic-unsigned.apk \
  -I "$PLATFORM" --manifest app/src/main/AndroidManifest.xml \
  --java build/gen build/res.zip --auto-add-overlay
# NOTE: android.jar must be on -classpath, NOT -bootclasspath.
# -bootclasspath replaces the JDK bootstrap classes, hiding
# java.lang.invoke.LambdaMetafactory, so javac fails on lambdas:
# "cannot find symbol: method metafactory ... interface LambdaMetafactory".
javac -source 8 -target 8 -classpath "$PLATFORM" \
  -d build/obj build/gen/com/heretic/chat/R.java \
  app/src/main/java/com/heretic/chat/MainActivity.java
# --lib android.jar so d8 can resolve the interfaces lambdas desugar
# against (Runnable, View$OnClickListener, Activity). Without it d8
# warns they "were not found" and the desugaring may be incomplete.
"$BT/d8" --lib "$PLATFORM" --output build/dex build/obj/com/heretic/chat/*.class
python3 -c "import zipfile; z=zipfile.ZipFile('build/heretic-unsigned.apk','a'); z.write('build/dex/classes.dex','classes.dex'); z.close()"
"$BT/zipalign" -f 4 build/heretic-unsigned.apk build/heretic-aligned.apk
# Release signing: in CI the permanent keystore arrives as the
# KEYSTORE_BASE64 secret, so updates install in place. Locally, with
# no secret present, fall back to the throwaway debug keystore as before.
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > build/release.keystore
  "$BT/apksigner" sign --ks build/release.keystore --ks-type PKCS12 \
    --ks-pass "pass:$KEYSTORE_PASSWORD" --ks-key-alias "${KEY_ALIAS:-heretic}" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out heretic.apk build/heretic-aligned.apk
  rm -f build/release.keystore
else
  # keytool does not create parent dirs: a fresh runner has no ~/.android/.
  mkdir -p ~/.android
  [ -f ~/.android/heretic-debug.keystore ] || keytool -genkeypair -v -keystore ~/.android/heretic-debug.keystore -storepass android -alias heretic -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Heretic, O=Heretic"
  "$BT/apksigner" sign --ks ~/.android/heretic-debug.keystore --ks-pass pass:android --out heretic.apk build/heretic-aligned.apk
fi
"$BT/apksigner" verify heretic.apk && ls -l heretic.apk
