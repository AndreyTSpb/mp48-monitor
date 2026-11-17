# Unit tests on Windows without Gradle / Android SDK (JDK must be in PATH).
# Compiles only the pure-Java logic and runs JUnit. No APK is built.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$main = Join-Path $root 'app\src\main\java\ru\gbo\aebmonitor'
$test = Join-Path $root 'app\src\test\java\ru\gbo\aebmonitor'
$out = Join-Path $root 'app\build\tmp\junit-direct'

foreach ($tool in 'javac', 'java') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool not found in PATH. Install a JDK (Android Studio: File - Project Structure - SDK)."
    }
}

function Find-Jar([string]$pattern) {
    $base = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $found = Get-ChildItem -Path $base -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $found) {
        throw "Not found $pattern in the Gradle cache. Build the project once in Android Studio."
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
    (Join-Path $main 'CsvFormat.java'),
    (Join-Path $main 'EcuBlock.java'),
    (Join-Path $main 'Kwp2000.java'),
    (Join-Path $main 'Ch340Baud.java')
)
Get-ChildItem $test -Filter '*.java' | ForEach-Object { $sources += $_.FullName }

Write-Host "Compiling $($sources.Count) files..."
& javac -nowarn -encoding UTF-8 -cp "$junit;$hamcrest" -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }

Copy-Item (Join-Path $root 'app\src\test\resources\vectors.txt') $out -Force

$classes = @(
    'ru.gbo.aebmonitor.ProtocolTest',
    'ru.gbo.aebmonitor.AlertsTest',
    'ru.gbo.aebmonitor.KMapTest',
    'ru.gbo.aebmonitor.StatsTest',
    'ru.gbo.aebmonitor.EcoTest',
    'ru.gbo.aebmonitor.CsvFormatTest',
    'ru.gbo.aebmonitor.EcuBlockTest',
    'ru.gbo.aebmonitor.Kwp2000Test',
    'ru.gbo.aebmonitor.Ch340BaudTest'
)
& java -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore @classes
$code = $LASTEXITCODE

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
exit $code
