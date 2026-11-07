#!/bin/bash
# Офлайн-рендер экранов в PNG (Linux/macOS). Нужен JDK, Android SDK и APK — не нужны.
# Компилирует настоящие классы экранов с заглушками из tools/render/src.
set -e
cd "$(dirname "$0")/../.."
OUT=tools/render/out/classes
rm -rf "$OUT" && mkdir -p "$OUT"
APP=app/src/main/java/ru/gbo/aebmonitor
javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/render/src -name '*.java') \
  $APP/LinkState.java $APP/Theme.java $APP/Protocol.java $APP/KMap.java $APP/Alerts.java \
  $APP/Eco.java $APP/Stats.java $APP/Demo.java $APP/Trend.java $APP/CellStats.java \
  $APP/DashView.java $APP/TrendView.java $APP/MapView.java $APP/MultCurveView.java \
  $APP/EcoView.java $APP/StatsView.java
java -Djava.awt.headless=true -cp "$OUT" ru.gbo.aebmonitor.RenderScreens docs/screens
