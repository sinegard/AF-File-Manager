param([string]$AndroidSdkRoot = $env:ANDROID_HOME, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdkRoot -or -not $env:JAVA_HOME) { throw 'Set ANDROID_HOME and JAVA_HOME first.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'app/build/issues-158-163/split-fixtures' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$aapt = Join-Path $AndroidSdkRoot 'build-tools/36.0.0/aapt2.exe'
$signer = Join-Path $AndroidSdkRoot 'build-tools/36.0.0/apksigner.bat'
$platform = Join-Path $AndroidSdkRoot 'platforms/android-36/android.jar'
$fixture = Join-Path $PSScriptRoot 'fixtures/split-apk'
function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Fixture tool failed: $Program ($LASTEXITCODE)" }
}
Run-Checked $aapt @('compile', '--dir', "$fixture/base/res", '-o', "$output/resources.zip")
Run-Checked $aapt @('link', '--manifest', "$fixture/base/AndroidManifest.xml", '-I', $platform, "$output/resources.zip", '-o', "$output/base-unsigned.apk")
Run-Checked $aapt @('link', '--manifest', "$fixture/config/AndroidManifest.xml", '-I', $platform, '-o', "$output/config-unsigned.apk")
foreach ($key in @('fixture', 'mismatched')) {
    if (-not (Test-Path -LiteralPath "$output/$key.jks")) {
        Run-Checked (Join-Path $env:JAVA_HOME 'bin/keytool.exe') @('-genkeypair', '-keystore', "$output/$key.jks", '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-alias', 'fixture', '-keyalg', 'RSA', '-validity', '365', '-dname', "CN=AF isolated test $key", '-noprompt')
    }
}
Run-Checked $signer @('sign', '--ks', "$output/fixture.jks", '--ks-pass', 'pass:fixture-only', '--out', "$output/base.apk", "$output/base-unsigned.apk")
Run-Checked $signer @('sign', '--ks', "$output/fixture.jks", '--ks-pass', 'pass:fixture-only', '--out', "$output/config.apk", "$output/config-unsigned.apk")
Run-Checked $signer @('sign', '--ks', "$output/mismatched.jks", '--ks-pass', 'pass:fixture-only', '--out', "$output/wrong-key.apk", "$output/config-unsigned.apk")
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($bundle in @('valid', 'wrong-signature')) {
    $stream = [IO.File]::Open("$output/$bundle.apks", [IO.FileMode]::Create)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$output/base.apk", 'base.apk') | Out-Null
        $split = if ($bundle -eq 'valid') { 'config.apk' } else { 'wrong-key.apk' }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$output/$split", 'config.en.apk') | Out-Null
    } finally { $zip.Dispose(); $stream.Dispose() }
}
Write-Output "Fixture bundles: $output"
