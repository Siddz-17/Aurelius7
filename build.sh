#!/bin/bash
# Gradle-free APK build: aapt -> javac -> dx -> aapt add -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
rm -rf build gen && mkdir -p build/obj gen out
aapt package -f -m -J gen -M AndroidManifest.xml -S res -I $SDK
mkdir -p build/stubs
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath $SDK -d build/stubs $(find stubs -name '*.java')
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath $SDK -cp build/stubs -d build/obj \
  $(find src gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/obj
aapt package -f -M AndroidManifest.xml -S res -A assets -I $SDK -F build/app.unsigned.apk
(cd build && aapt add app.unsigned.apk classes.dex)
zipalign -f -p 4 build/app.unsigned.apk build/app.aligned.apk
[ -f aurelius7.keystore ] || keytool -genkeypair -noprompt -keystore aurelius7.keystore -storepass aurelius7 -keypass aurelius7 \
  -alias aurelius7 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Aurelius7, O=Sid" 2>/dev/null
apksigner sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks aurelius7.keystore --ks-pass pass:aurelius7 --key-pass pass:aurelius7 \
  --out out/AURELIUS7-v$VER.apk build/app.aligned.apk
apksigner verify --print-certs out/AURELIUS7-v$VER.apk | head -1
ls -la out/AURELIUS7-v$VER.apk
