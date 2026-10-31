# Builds the OpenList gomobile binding into app/libs/openlistlib.aar.
#
# The AAR is a 28 MB binary and is deliberately not committed; this script
# reproduces it. It needs Go, gomobile, the Android SDK and an NDK:
#
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   gomobile init
#
# Usage:  .\build.ps1                 # arm64 only, what phones need
#         .\build.ps1 -Targets "android/arm64,android/amd64"   # + emulator
#         .\build.ps1 -Reclone        # throw the source tree away first

param(
    [string]$Tag = "v4.2.9",
    [string]$Targets = "android/arm64",
    [string]$AndroidApi = "26",
    [switch]$Reclone
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here ".work"
$src = Join-Path $work "OpenList"
$outDir = Join-Path $here "..\app\libs"
$aar = Join-Path $outDir "openlistlib.aar"

function Require($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "$name not found on PATH"
    }
}

Require "go"
Require "git"

$gomobile = Get-Command "gomobile" -ErrorAction SilentlyContinue
if (-not $gomobile) {
    $gomobilePath = Join-Path (go env GOPATH) "bin\gomobile.exe"
    if (-not (Test-Path $gomobilePath)) {
        throw "gomobile not found. Run: go install golang.org/x/mobile/cmd/gomobile@latest"
    }
    $gomobile = $gomobilePath
} else {
    $gomobile = $gomobile.Source
}

# gomobile reads both variables; the SDK path also lives in local.properties.
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "D:\Android\Sdk" }
if (-not (Test-Path $env:ANDROID_HOME)) { throw "ANDROID_HOME does not point at an SDK: $($env:ANDROID_HOME)" }
if (-not $env:ANDROID_NDK_HOME) {
    $ndk = Get-ChildItem (Join-Path $env:ANDROID_HOME "ndk") -Directory |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ndk) { throw "No NDK under $env:ANDROID_HOME\ndk" }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}

if ($Reclone -and (Test-Path $src)) { Remove-Item $src -Recurse -Force }
if (-not (Test-Path (Join-Path $src "go.mod")) -or -not (Test-Path (Join-Path $work "tag.txt")) -or (Get-Content (Join-Path $work "tag.txt") -Raw).Trim() -ne $Tag) {
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    if (Test-Path $src) { Remove-Item $src -Recurse -Force }
    Write-Host "Cloning OpenList $Tag ..."
    git clone --depth 1 --branch $Tag https://github.com/OpenListTeam/OpenList.git $src
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
    Set-Content (Join-Path $work "tag.txt") $Tag
}

# The wrapper reaches internal packages, so it has to sit inside the module.
# Copy-Item onto an existing directory silently keeps the old files, so the
# previous wrapper is removed first; otherwise edits never reach the build.
$libDir = Join-Path $src "openlistlib"
if (Test-Path $libDir) { Remove-Item $libDir -Recurse -Force }
Copy-Item (Join-Path $here "openlistlib") $libDir -Recurse -Force

# Upstream embeds public/dist, but the source tree ships it empty and the
# server panics at startup without index.html and these four folders. The app
# only speaks HTTP to the API, so placeholders are enough.
$dist = Join-Path $src "public\dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
foreach ($d in @("assets", "images", "streamer", "static")) {
    $sub = Join-Path $dist $d
    New-Item -ItemType Directory -Force -Path $sub | Out-Null
    $keep = Join-Path $sub ".keep"
    if (-not (Test-Path $keep)) { Set-Content $keep "" -NoNewline }
}
$index = Join-Path $dist "index.html"
if (-not (Test-Path $index)) {
    Set-Content $index "<!doctype html><html><head><title>OpenList</title></head><body>OpenList local server</body></html>" -NoNewline
}

Push-Location $src
try {
    # The stock times package uses a syscall Android does not expose.
    go mod edit -replace "github.com/djherbis/times@v1.6.0=github.com/jing332/times@latest"
    # gomobile refuses to bind a module that does not depend on gobind.
    go get -tool golang.org/x/mobile/cmd/gobind
    if ($LASTEXITCODE -ne 0) { throw "go get gobind failed" }

    Push-Location (Join-Path $src "openlistlib")
    try {
        Write-Host "Binding $Targets (androidapi $AndroidApi) ..."
        & $gomobile bind "-androidapi" $AndroidApi "-target" $Targets "-ldflags" "-s -w" "-o" "openlistlib.aar"
        if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed" }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item (Join-Path $src "openlistlib\openlistlib.aar") $aar -Force
$mb = [math]::Round((Get-Item $aar).Length / 1MB, 1)
Write-Host "Wrote $aar ($mb MB)"
