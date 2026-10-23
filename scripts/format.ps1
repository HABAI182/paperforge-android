param([Parameter(Mandatory=$true)][string]$Jar,[switch]$Check)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$formatterPath=(Resolve-Path -LiteralPath $Jar).Path
$javaPath=if($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location $projectRoot
try {
    $files=@(git ls-files '*.kt' '*.kts' | Where-Object { $_ -notmatch '/quality/Baseline[^/]*\.kt$' })
    $arguments=@('--kotlinlang-style')
    if($Check) { $arguments+=@('--dry-run','--set-exit-if-changed') }
    & $javaPath -jar $formatterPath @arguments @files
    if($LASTEXITCODE -ne 0) { throw 'Kotlin formatting failed' }
} finally { Pop-Location }
