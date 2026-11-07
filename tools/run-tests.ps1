# Юнит-тесты на Windows без Gradle и Android SDK (нужен JDK в PATH).
#
# Скрипт собирает только чистую логику (протокол, карта, предупреждения, статистика,
# эко-индикатор, формат записи) и гоняет JUnit. APK не собирается, Android SDK не нужен.
#
# Почему не `gradlew test`: на Windows с кириллическим именем профиля Gradle пишет
# classpath во временный файл в CP1251, путь портится и тестовый процесс не стартует
# («Could not find or load main class ...GradleWorkerMain»). В Android Studio те же
# тесты можно запустить правой кнопкой по app/src/test, если эта проблема не мешает.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$main = Join-Path $root 'app\src\main\java\ru\gbo\aebmonitor'
$test = Join-Path $root 'app\src\test\java\ru\gbo\aebmonitor'
$out = Join-Path $root 'app\build\tmp\junit-direct'

foreach ($tool in 'javac', 'java') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool не найден в PATH. Установите JDK (в Android Studio: File → Project Structure → SDK)."
    }
}

function Find-Jar([string]$pattern) {
    $base = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $found = Get-ChildItem -Path $base -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $found) {
        throw "Не найден $pattern в кэше Gradle. Один раз соберите проект в Android Studio (или запустите тесты оттуда), чтобы JUnit скачался."
    }
    return $found.FullName
}

$junit = Find-Jar 'junit-4*.jar'
$hamcrest = Find-Jar 'hamcrest-core-*.jar'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @(
    (Join-Path $main 'Protocol.java'),
    (Join-Path $main 'Alerts.java'),
    (Join-Path $main 'KMap.java'),
    (Join-Path $main 'Stats.java'),
    (Join-Path $main 'Eco.java'),
    (Join-Path $main 'CsvFormat.java')
)
Get-ChildItem $test -Filter '*.java' | ForEach-Object { $sources += $_.FullName }

Write-Host "Компиляция $($sources.Count) файлов…"
& javac -nowarn -encoding UTF-8 -cp "$junit;$hamcrest" -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Ошибка компиляции' }

Copy-Item (Join-Path $root 'app\src\test\resources\vectors.txt') $out -Force

$classes = @(
    'ru.gbo.aebmonitor.ProtocolTest',
    'ru.gbo.aebmonitor.AlertsTest',
    'ru.gbo.aebmonitor.KMapTest',
    'ru.gbo.aebmonitor.StatsTest',
    'ru.gbo.aebmonitor.EcoTest',
    'ru.gbo.aebmonitor.CsvFormatTest'
)
& java -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore @classes
$code = $LASTEXITCODE

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
exit $code
