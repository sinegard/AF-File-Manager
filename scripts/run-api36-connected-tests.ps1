param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$TestClass = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$androidHome = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($androidHome)) {
    throw "ANDROID_HOME is not set."
}
$adb = Join-Path $androidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "adb.exe was not found under ANDROID_HOME."
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
    })
    if ($devices.Count -ne 1) {
        throw "Connect exactly one API 36 test device or pass -Serial. Found $($devices.Count)."
    }
    $Serial = $devices[0]
}

if (-not $Serial.StartsWith("emulator-", [System.StringComparison]::Ordinal)) {
    throw "The API 36 connected suite may only run on an emulator. Refusing physical device '$Serial'."
}

$apiLevel = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
if ($LASTEXITCODE -ne 0 -or $apiLevel -ne "36") {
    throw "The selected device must run API 36. $Serial reports API '$apiLevel'."
}

$fixture = Join-Path $androidHome "emulator\resources\default.mp4"
if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) {
    throw "The Android emulator video fixture was not found: $fixture"
}

$remoteFixtureDirectory = "/sdcard/Download/AFFileManagerTest"
$remoteFixture = "$remoteFixtureDirectory/video-gesture.mp4"
$previousSerial = $env:ANDROID_SERIAL
try {
    & $adb -s $Serial shell mkdir -p $remoteFixtureDirectory
    if ($LASTEXITCODE -ne 0) { throw "Could not prepare the device fixture directory." }
    & $adb -s $Serial push $fixture $remoteFixture
    if ($LASTEXITCODE -ne 0) { throw "Could not copy the video fixture to $Serial." }

    $env:ANDROID_SERIAL = $Serial
    $gradleArguments = @(':app:connectedDebugAndroidTest', '--console=plain')
    if (-not [string]::IsNullOrWhiteSpace($TestClass)) {
        $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass"
    }
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Connected tests failed." }
} finally {
    & $adb -s $Serial shell rm -f $remoteFixture | Out-Null
    if ($null -eq $previousSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue }
    else { $env:ANDROID_SERIAL = $previousSerial }
}
