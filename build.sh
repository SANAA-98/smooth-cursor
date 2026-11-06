#!/usr/bin/env bash
# Manual no-Gradle APK build: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
# Toolchain: JDK 17 + Android SDK (platform android-34, build-tools 34.0.0).
# Export JAVA_HOME and ANDROID_HOME, or put them in a local
# toolchain-env.sh file next to this script (not committed).
[ -f toolchain-env.sh ] && source toolchain-env.sh
BT=$ANDROID_HOME/build-tools/34.0.0
AJAR=$ANDROID_HOME/platforms/android-34/android.jar
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/pkg

# 1) compile + link resources (accessibility config) and manifest
$BT/aapt2 compile --dir res -o $OUT/compiled.zip
$BT/aapt2 link -I $AJAR --manifest AndroidManifest.xml $OUT/compiled.zip -o $OUT/base.apk

# 2) compile java
find src -name "*.java" > $OUT/sources.txt
$JAVA_HOME/bin/javac -encoding UTF-8 -cp "$AJAR" -d $OUT/classes @$OUT/sources.txt
cd $OUT/classes && $JAVA_HOME/bin/jar cf ../app.jar . && cd ../..

# 3) dex
$BT/d8 --min-api 26 --lib $AJAR --output $OUT/dex $OUT/app.jar

# 4) package
cd $OUT
cp base.apk app.apk
cp dex/classes.dex pkg/
cd pkg && zip -q ../app.apk classes.dex && cd ..
$BT/zipalign -f 4 app.apk aligned.apk

# 5) sign
if [ ! -f ../smooth.jks ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore ../smooth.jks -alias smooth -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass smooth123 -keypass smooth123 -dname "CN=SmoothCursor, O=Sana, C=IR" >/dev/null 2>&1
fi
$BT/apksigner sign --ks ../smooth.jks --ks-pass pass:smooth123 --key-pass pass:smooth123 \
  --out ../smooth-cursor.apk aligned.apk
$BT/apksigner verify --print-certs ../smooth-cursor.apk | head -3
ls -la ../smooth-cursor.apk
echo "BUILD OK"
