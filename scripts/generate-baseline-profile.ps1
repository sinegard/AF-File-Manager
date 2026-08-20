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
if ((& $adb -s $EmulatorSerial get-state 2>$null).Trim() -ne 'device' -or
    (& $adb -s $EmulatorSerial shell getprop ro.boot.qemu 2>$null).Trim() -ne '1') {
    throw "A ready Android emulator is required; refusing $EmulatorSerial."
}

$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = $EmulatorSerial
    & (Join-Path $projectDirectory 'gradlew.bat') `
        ':benchmark:connectedProfileAndroidTest' `
        '-Pandroid.testInstrumentationRunnerArguments.class=com.affilemanager.benchmark.BaselineProfileGenerator' `
        '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Baseline Profile generation failed with exit code $LASTEXITCODE"
    }

    $outputRoot = Join-Path $projectDirectory 'benchmark\build\outputs\connected_android_test_additional_output\profile'
    $extendedOutputRoot = if ($outputRoot.StartsWith('\\')) { "\\?\UNC\$($outputRoot.Substring(2))" } else { "\\?\$outputRoot" }
    function Find-NewestGeneratedFile([string]$name) {
        return [System.IO.Directory]::EnumerateFiles(
            $extendedOutputRoot,
            $name,
            [System.IO.SearchOption]::AllDirectories
        ) | Sort-Object { [System.IO.File]::GetLastWriteTimeUtc($_) } -Descending | Select-Object -First 1
    }
    $journeyBaseline = Find-NewestGeneratedFile 'BaselineProfileGenerator_criticalUserJourneys-baseline-prof.txt'
    $startup = Find-NewestGeneratedFile 'BaselineProfileGenerator_startup-startup-prof.txt'
    if ([string]::IsNullOrWhiteSpace($journeyBaseline) -or [string]::IsNullOrWhiteSpace($startup)) {
        throw "Generated journey and Startup Profile files are both required."
    }
    $combinedBaseline = @(
        [System.IO.File]::ReadAllLines($startup)
        [System.IO.File]::ReadAllLines($journeyBaseline)
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
    if ($combinedBaseline.Count -eq 0) {
        throw "The generated Baseline Profile is empty."
    }
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines(
        (Join-Path $projectDirectory 'app\src\main\baseline-prof.txt'),
        $combinedBaseline,
        $utf8WithoutBom
    )
    [System.IO.File]::Copy(
        $startup,
        (Join-Path $projectDirectory 'app\src\main\startup-prof.txt'),
        $true
    )
} finally {
    if ($null -eq $previousSerial) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousSerial
    }
}
