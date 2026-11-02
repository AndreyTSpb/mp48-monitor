#!/bin/bash
# Сборка APK без Gradle и Android Studio (Ubuntu/Debian):
#   sudo apt install default-jdk android-sdk-platform-23 android-sdk-build-tools \
#                    dalvik-exchange apksigner zipalign aapt zip
# Версия, minSdk, targetSdk и applicationId берутся из app/build.gradle.
# Подпись — keystore/keystore.properties, если есть, иначе отладочный ключ keystore/debug.jks.
set -e
cd "$(dirname "$0")/.."
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
OUT=build-nogradle
G=app/build.gradle
val() { grep -E "^\s*$1\s" "$G" | head -1 | sed -E "s/^\s*$1\s+'?([^' ]+)'?.*/\1/"; }
APP_ID=$(val applicationId); VCODE=$(val versionCode); VNAME=$(val versionName)
MIN=$(val minSdk); TARGET=$(val targetSdk)
echo "Сборка $APP_ID $VNAME ($VCODE), minSdk $MIN, targetSdk $TARGET"

for t in aapt javac dalvik-exchange zipalign apksigner keytool zip; do
  command -v $t >/dev/null || { echo "Не найдено: $t"; exit 1; }
done

rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes
# манифест для aapt: добавить package, версию и uses-sdk (в Gradle-проекте они в build.gradle)
sed -e "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$APP_ID\" android:versionCode=\"$VCODE\" android:versionName=\"$VNAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN\" android:targetSdkVersion=\"$TARGET\" />#" \
    app/src/main/AndroidManifest.xml > $OUT/AndroidManifest.xml
grep -q "package=\"$APP_ID\"" $OUT/AndroidManifest.xml || { echo "Не удалось подготовить манифест"; exit 1; }

aapt package -f -m -J $OUT/gen -M $OUT/AndroidManifest.xml -S app/src/main/res -I "$JAR" -F $OUT/res.apk
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" \
  -d $OUT/classes $(find app/src/main/java $OUT/gen -name '*.java') 2>&1 \
  | grep -v -e JAVA_TOOL -e "warning: \[options\]" -e "^[0-9] warning" || true
test -f $OUT/classes/ru/gbo/aebmonitor/MainActivity.class || { echo "Ошибка компиляции"; exit 1; }
dalvik-exchange --dex --min-sdk-version=$MIN --output=$OUT/classes.dex $OUT/classes 2>&1 | grep -v JAVA_TOOL || true
cp $OUT/res.apk $OUT/unsigned.apk
(cd $OUT && zip -q unsigned.apk classes.dex)
zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

PROPS=keystore/keystore.properties
if [ -f "$PROPS" ]; then
  prop() { grep -E "^$1=" "$PROPS" | cut -d= -f2-; }
  KS=$(prop storeFile); KP=$(prop storePassword); KA=$(prop keyAlias); KK=$(prop keyPassword)
else
  KS=keystore/debug.jks; KP=android; KA=debug; KK=android
  if [ ! -f "$KS" ]; then
    mkdir -p keystore
    keytool -genkeypair -keystore "$KS" -alias $KA -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass $KP -keypass $KK -dname "CN=Debug" >/dev/null 2>&1
  fi
  echo "Внимание: подпись отладочным ключом (нет $PROPS)"
fi
APK=$OUT/AEB-Monitor-$VNAME.apk
apksigner sign --ks "$KS" --ks-pass "pass:$KP" --key-pass "pass:$KK" --ks-key-alias "$KA" \
  --min-sdk-version $MIN --out "$APK" $OUT/aligned.apk 2>&1 | grep -v JAVA_TOOL || true
apksigner verify "$APK" >/dev/null 2>&1 || { echo "Ошибка подписи"; exit 1; }
echo "Готово: $APK"
