#!/bin/bash
# Юнит-тесты без Gradle (нужны JDK и junit4: sudo apt install junit4).
# В Android Studio те же тесты запускаются из app/src/test.
set -e
cd "$(dirname "$0")/.."
CP=/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar
OUT=$(mktemp -d)
J=app/src/main/java/ru/gbo/aebmonitor
javac -nowarn -encoding UTF-8 -cp $CP -d $OUT \
  $J/Protocol.java $J/Alerts.java $J/KMap.java $J/Stats.java $J/Eco.java $J/CsvFormat.java \
  app/src/test/java/ru/gbo/aebmonitor/*.java
cp app/src/test/resources/* $OUT/
java -cp $OUT:$CP org.junit.runner.JUnitCore \
  ru.gbo.aebmonitor.ProtocolTest \
  ru.gbo.aebmonitor.AlertsTest \
  ru.gbo.aebmonitor.KMapTest \
  ru.gbo.aebmonitor.StatsTest \
  ru.gbo.aebmonitor.EcoTest \
  ru.gbo.aebmonitor.CsvFormatTest
rm -rf $OUT
