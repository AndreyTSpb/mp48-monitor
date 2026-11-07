# Offline PNG render of the app screens.
# Compiles the real view classes against tiny android.graphics/android.view stubs
# (tools/render/src) and draws them into docs/screens/*.png.
# No Android SDK, no Gradle, no APK. Needs JDK in PATH.
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$app = Join-Path $root 'app\src\main\java\ru\gbo\aebmonitor'
$out = Join-Path $PSScriptRoot 'out\classes'
$png = Join-Path $root 'docs\screens'

foreach ($tool in 'javac', 'java') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool not found in PATH. Install a JDK."
    }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' |
    ForEach-Object { $sources += $_.FullName }
foreach ($name in 'LinkState', 'Theme', 'Protocol', 'KMap', 'Alerts', 'Eco', 'Stats', 'Demo', 'Trend',
                  'CellStats', 'DashView', 'TrendView', 'MapView', 'MultCurveView', 'EcoView', 'StatsView') {
    $sources += (Join-Path $app "$name.java")
}

Write-Host "Compiling $($sources.Count) files..."
& javac -nowarn -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }

& java '-Djava.awt.headless=true' -cp $out ru.gbo.aebmonitor.RenderScreens $png
exit $LASTEXITCODE
