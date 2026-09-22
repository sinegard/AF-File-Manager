[CmdletBinding()]
param(
    [string]$GoExe = $env:AF_GO_EXE,
    [string]$NdkRoot = $env:ANDROID_NDK_ROOT,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\app\src\main\jniLibs')
)

$ErrorActionPreference = 'Stop'
$version = '2026.9.1'
$commit = 'f11dea9cb7079e90a982c1a2d5548ab40847fdcf'
$buildTime = '2026-09-11T13:42:53Z'

if ([string]::IsNullOrWhiteSpace($GoExe)) {
    $command = Get-Command go -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'Go 1.26 is required. Set AF_GO_EXE to go.exe.' }
    $GoExe = $command.Source
}
if (-not (Test-Path -LiteralPath $GoExe -PathType Leaf)) { throw "Go executable not found: $GoExe" }
if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    $NdkRoot = Join-Path $env:ANDROID_HOME 'ndk\27.3.13750724'
}
if (-not (Test-Path -LiteralPath $NdkRoot -PathType Container)) { throw "Android NDK not found: $NdkRoot" }

$goVersion = & $GoExe version
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch 'go1\.26(\.|\s)') { throw "Go 1.26 is required; found: $goVersion" }

$temporaryBase = [System.IO.Path]::GetTempPath()
$temporary = Join-Path $temporaryBase ("af-cloudflared-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $source = Join-Path $temporary 'source'
    git init --quiet $source
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize the temporary cloudflared checkout.' }
    git -C $source remote add origin https://github.com/cloudflare/cloudflared.git
    git -C $source fetch --quiet --depth 1 origin $commit
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the pinned cloudflared source.' }
    git -C $source checkout --quiet --detach FETCH_HEAD
    $actual = (git -C $source rev-parse HEAD).Trim()
    if ($actual -ne $commit) { throw "Unexpected cloudflared commit: $actual" }

    $toolchain = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
    $targets = @(
        @{ Abi = 'arm64-v8a'; GoArch = 'arm64'; Compiler = 'aarch64-linux-android26-clang.cmd'; GoArm = $null },
        @{ Abi = 'armeabi-v7a'; GoArch = 'arm'; Compiler = 'armv7a-linux-androideabi26-clang.cmd'; GoArm = '7' },
        @{ Abi = 'x86_64'; GoArch = 'amd64'; Compiler = 'x86_64-linux-android26-clang.cmd'; GoArm = $null },
        @{ Abi = 'x86'; GoArch = '386'; Compiler = 'i686-linux-android26-clang.cmd'; GoArm = $null }
    )
    foreach ($target in $targets) {
        $compiler = Join-Path $toolchain $target.Compiler
        if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) { throw "NDK compiler not found: $compiler" }
        $destination = Join-Path $OutputRoot (Join-Path $target.Abi 'libcloudflared.so')
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        $env:GOOS = 'android'
        $env:GOARCH = $target.GoArch
        $env:CGO_ENABLED = '1'
        $env:CC = $compiler
        if ($null -eq $target.GoArm) { Remove-Item Env:GOARM -ErrorAction SilentlyContinue } else { $env:GOARM = $target.GoArm }
        Push-Location $source
        try {
            & $GoExe build -trimpath -buildmode=pie `
                -ldflags "-s -w -buildid= -X main.Version=$version -X main.BuildTime=$buildTime" `
                -o $destination .\cmd\cloudflared
            if ($LASTEXITCODE -ne 0) { throw "cloudflared build failed for $($target.Abi)" }
        } finally {
            Pop-Location
        }
    }

    $licenseDestination = Join-Path $PSScriptRoot '..\app\src\main\assets\licenses\cloudflared-LICENSE.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $licenseDestination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination $licenseDestination -Force
} finally {
    $resolvedTemporary = [System.IO.Path]::GetFullPath($temporary)
    $resolvedBase = [System.IO.Path]::GetFullPath($temporaryBase)
    if ($resolvedTemporary.StartsWith($resolvedBase, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force -ErrorAction SilentlyContinue
    }
}
