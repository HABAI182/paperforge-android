# Publishes a verified, signed PaperForge build as a GitHub Release with the authenticated GitHub CLI, then checks
# what was published the way users and the in-app updater see it.
#
#   scripts/publish_release.ps1 -Version 2.0.1 -Assets <folder from build_distribution.py> -Notes <release notes .md>
#
# Run it only after the full device/unit suite passed and the signed release was built from the commit the tag
# points to. It never overwrites anything: it stops if the release already exists (drafts included), never deletes
# or re-uploads assets, never reads tokens (gh uses its own keyring login), and needs the tag to be pushed already.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Assets,
    [Parameter(Mandatory = $true)][string]$Notes,
    [string]$Repo = "HABAI182/paperforge-android",
    [string]$Gh = "C:\Program Files\GitHub CLI\gh.exe"
)
$ErrorActionPreference = "Stop"
$tag = "v$Version"
$apkName = "PaperForge-$Version-full-arm64-v8a.apk"
$files = @($apkName, "release-manifest.json", "SHA256SUMS.txt") | ForEach-Object { Join-Path $Assets $_ }
foreach ($f in $files + $Notes) { if (-not (Test-Path $f)) { throw "missing $f" } }

function Sha([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

# The manifest must describe this APK, as a non-candidate stable release from a clean tree.
$manifest = Get-Content (Join-Path $Assets "release-manifest.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$artifact = $manifest.artifacts | Where-Object { $_.file -eq $apkName }
if ($manifest.candidateOnly -or $manifest.releaseHold) { throw "manifest is a candidate or on hold" }
if ($manifest.workingTreeDirty) { throw "built from a dirty working tree" }
if ($manifest.versionName -ne $Version -or -not $artifact) { throw "manifest is not for $apkName" }
$apkPath = Join-Path $Assets $apkName
if ($artifact.sha256 -ne (Sha $apkPath) -or $artifact.bytes -ne (Get-Item $apkPath).Length) { throw "APK does not match the manifest" }
$sums = Get-Content (Join-Path $Assets "SHA256SUMS.txt") -Encoding UTF8
if (-not ($sums -match "^$($artifact.sha256)\s+$([regex]::Escape($apkName))$")) { throw "SHA256SUMS.txt does not list the APK hash" }

# Never overwrite: the release must not exist, and the tag must already be on GitHub at the manifest's commit.
$existing = & $Gh release view $tag --repo $Repo --json tagName 2>$null
if ($LASTEXITCODE -eq 0 -and $existing) { throw "release $tag already exists; nothing was changed" }
$remoteTag = git ls-remote --tags origin "refs/tags/$tag^{}"
if (-not $remoteTag) { throw "tag $tag is not on GitHub; push it first" }
if (-not $remoteTag.StartsWith($manifest.commit)) { throw "tag $tag does not point at the built commit $($manifest.commit)" }

& $Gh release create $tag --repo $Repo --verify-tag --title "PaperForge $tag" --notes-file $Notes --latest @files
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }

# What was published: GitHub's digests, then anonymous downloads through the "latest" links the app uses.
$work = Join-Path ([IO.Path]::GetTempPath()) "paperforge-published-$Version"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory $work | Out-Null
$latest = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest"
if ($latest.tag_name -ne $tag) { throw "latest release is $($latest.tag_name), not $tag" }
foreach ($f in $files) {
    $name = Split-Path $f -Leaf
    $asset = $latest.assets | Where-Object { $_.name -eq $name }
    if ($asset.digest -ne "sha256:$(Sha $f)") { throw "GitHub digest differs for $name" }
    $out = Join-Path $work $name
    Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/$name" -OutFile $out -UseBasicParsing
    if ((Sha $out) -ne (Sha $f)) { throw "downloaded $name differs from the built file" }
    "ok  $name  $(Sha $out)"
}
"published https://github.com/$Repo/releases/tag/$tag"
