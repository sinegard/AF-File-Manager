param(
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android\af-file-manager-signing')
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keystorePath = Join-Path $SigningDirectory 'af-file-manager-release.jks'
$credentialPath = Join-Path $SigningDirectory 'signing-credential.xml'

if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw "Release keystore not found: $keystorePath"
}
if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "DPAPI credential not found: $credentialPath"
}

$credential = Import-Clixml -LiteralPath $credentialPath
$plainPassword = $credential.GetNetworkCredential().Password
try {
    $env:AF_KEYSTORE_PATH = $keystorePath
    $env:AF_KEYSTORE_PASSWORD = $plainPassword
    $env:AF_KEY_ALIAS = 'af-file-manager-release'
    $env:AF_KEY_PASSWORD = $plainPassword

    & (Join-Path $projectDirectory 'gradlew.bat') testDebugUnitTest lintDebug assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Release build failed with exit code $LASTEXITCODE"
    }
} finally {
    $plainPassword = $null
    Remove-Item Env:AF_KEYSTORE_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:AF_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:AF_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:AF_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$apk = Join-Path $projectDirectory 'app\build\outputs\apk\release\app-release.apk'
Get-FileHash -LiteralPath $apk -Algorithm SHA256
