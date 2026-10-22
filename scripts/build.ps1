param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$DeviceTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) {
    $javaCommand = Get-Command java -ErrorAction Stop
    $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
}
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-36\android.jar'))) {
    throw "SDK Platform 36 missing under $SdkRoot. Install it with Android Studio or sdkmanager."
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value ('sdk.dir=' + $SdkRoot.Replace('\','/').Replace(':','\:')) -Encoding utf8
Push-Location $projectRoot
try {
    & .\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or local checks failed.' }
    if ($DeviceTests) {
        & .\gradlew.bat connectedDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android device checks failed.' }
    }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
    Write-Output "APK: $apk"
} finally { Pop-Location }
