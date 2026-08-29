[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$DeviceSerial,

    [string]$OutputPath,

    [string]$AdbPath
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-AdbPath {
    if ($AdbPath) {
        $provided = [IO.Path]::GetFullPath($AdbPath)
        if (-not (Test-Path -LiteralPath $provided -PathType Leaf)) {
            throw "ADB was not found at the supplied path: $provided"
        }
        return $provided
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = [Collections.Generic.List[string]]::new()
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdkRoot) {
            $candidates.Add((Join-Path $sdkRoot 'platform-tools\adb.exe'))
        }
    }

    $localProperties = Join-Path $projectDirectory 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $encodedSdkPath = $sdkLine.Substring($sdkLine.IndexOf('=') + 1)
            $sdkPath = $encodedSdkPath -replace '\\:', ':' -replace '\\\\', '\'
            $candidates.Add((Join-Path $sdkPath 'platform-tools\adb.exe'))
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
    }

    $resolved = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $resolved) {
        throw 'ADB was not found. Set ANDROID_SDK_ROOT, ANDROID_HOME, or pass -AdbPath.'
    }
    return [IO.Path]::GetFullPath($resolved)
}

$adb = Resolve-AdbPath
if (-not $OutputPath) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $projectDirectory "build\reports\device-ui\ui-$timestamp.xml"
} elseif (-not [IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $projectDirectory $OutputPath
}

$destination = [IO.Path]::GetFullPath($OutputPath)
$destinationDirectory = Split-Path -Parent $destination
New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null

$remotePath = "/data/local/tmp/af-file-manager-ui-$([Guid]::NewGuid().ToString('N')).xml"
try {
    & $adb -s $DeviceSerial shell uiautomator dump --compressed $remotePath
    if ($LASTEXITCODE -ne 0) {
        throw "UI hierarchy capture failed with exit code $LASTEXITCODE"
    }

    & $adb -s $DeviceSerial pull $remotePath $destination
    if ($LASTEXITCODE -ne 0) {
        throw "UI hierarchy download failed with exit code $LASTEXITCODE"
    }

    $contents = Get-Content -LiteralPath $destination -Raw
    if ($contents -notmatch '<hierarchy(?:\s|>)') {
        Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        throw 'The captured file is not an Android UI hierarchy.'
    }

    Get-Item -LiteralPath $destination
} finally {
    & $adb -s $DeviceSerial shell rm -f -- $remotePath | Out-Null
}
