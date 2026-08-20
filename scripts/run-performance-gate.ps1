param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$EmulatorSerial
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} else {
    (Get-Command adb -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "adb was not found. Set ANDROID_HOME first."
}

$state = (& $adb -s $EmulatorSerial get-state 2>$null).Trim()
if ($state -ne 'device') {
    throw "Android emulator $EmulatorSerial is not ready."
}
$isEmulator = (& $adb -s $EmulatorSerial shell getprop ro.boot.qemu 2>$null).Trim()
if ($isEmulator -ne '1') {
    throw "Performance automation is restricted to an emulator; refusing device $EmulatorSerial."
}

$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = $EmulatorSerial
    & (Join-Path $projectDirectory 'gradlew.bat') `
        ':benchmark:connectedBenchmarkAndroidTest' `
        '-Pandroid.testInstrumentationRunnerArguments.class=com.affilemanager.benchmark.AfMacrobenchmark' `
        '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Macrobenchmark failed with exit code $LASTEXITCODE"
    }

    $reportRoot = Join-Path $projectDirectory 'benchmark\build\outputs\connected_android_test_additional_output\benchmark'
    & python (Join-Path $PSScriptRoot 'verify_performance.py') $reportRoot `
        '--budgets' (Join-Path $projectDirectory 'performance\budgets.json')
    if ($LASTEXITCODE -ne 0) {
        throw "Performance budget verification failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($null -eq $previousSerial) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousSerial
    }
}
