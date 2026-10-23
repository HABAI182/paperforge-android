param(
    [string]$Python = 'python',
    [Parameter(Mandatory=$true)][string]$Adb,
    [ValidateSet('baseline','current')][string]$Mode = 'current',
    [string]$Dataset = '',
    [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Dataset) { $Dataset = Join-Path $ProjectRoot 'bench-private/synthetic-v1' }
if (-not $Output) { $Output = Join-Path $ProjectRoot "bench-results/$Mode-android" }
if (-not (Test-Path -LiteralPath (Join-Path $Dataset 'manifest.json'))) {
    & $Python (Join-Path $PSScriptRoot 'generate_fixtures.py') --output $Dataset
    if ($LASTEXITCODE) { throw 'Fixture generation failed' }
}
& $Python (Join-Path $PSScriptRoot 'android.py') $Dataset --adb $Adb --mode $Mode --output $Output
if ($LASTEXITCODE) { throw 'Dataset transfer failed' }
& (Join-Path $ProjectRoot 'gradlew.bat') -p $ProjectRoot connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cn.paperforge.app.quality.QualityBenchmarkTest' "-Pandroid.testInstrumentationRunnerArguments.benchMode=$Mode" --console=plain
if ($LASTEXITCODE) { throw 'Benchmark failed' }
& $Python (Join-Path $PSScriptRoot 'android.py') $Dataset --adb $Adb --mode $Mode --output $Output --collect-only
if ($LASTEXITCODE) { throw 'Result collection failed' }
