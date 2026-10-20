param(
    [Parameter(Mandatory=$true)][ValidateSet('collect','stage')][string]$Mode,
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-\d+$')][string]$EmulatorSerial,
    [Parameter(Mandatory=$true)][string]$AdbPath,
    [string]$FixtureDirectory=(Join-Path $PSScriptRoot '..\app\build\artwork-fixtures')
)
$ErrorActionPreference='Stop'
$afBuild=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\app\build'))
$afFixtures=[IO.Path]::GetFullPath($FixtureDirectory)
if(-not $afFixtures.StartsWith($afBuild+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Fixtures must stay inside ignored app/build' }
if((& $AdbPath -s $EmulatorSerial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'An explicit emulator is required; no physical phone is modified' }
$afApi=[int]((& $AdbPath -s $EmulatorSerial shell getprop ro.build.version.sdk).Trim())
$afPackage='com.affilemanager.app.debug'
$afNames=@('covered.opus','plain.opus')
if($Mode -eq 'collect') {
    if($afApi -lt 29) { throw 'Opus fixture generation needs API29 or newer' }
    if(Test-Path -LiteralPath $afFixtures) { throw 'Existing fixture evidence must be preserved; choose a new app/build subdirectory' }
    New-Item -ItemType Directory -Path $afFixtures | Out-Null
    foreach($afName in $afNames) {
        & $AdbPath -s $EmulatorSerial pull "/sdcard/Android/data/$afPackage/files/validation/generated-$afName" (Join-Path $afFixtures $afName) | Out-Null
        if($LASTEXITCODE -ne 0) { throw 'Run EmbeddedAudioArtworkTest on the producer before collecting' }
    }
    $afFiles=@($afNames | ForEach-Object {
        $afPath=Join-Path $afFixtures $_
        if((Get-Item -LiteralPath $afPath).Length -notin 1..131072) { throw 'Unexpected generated fixture size' }
        [ordered]@{name=$_;sha256=(Get-FileHash -LiteralPath $afPath -Algorithm SHA256).Hash}
    })
    [ordered]@{producerApi=$afApi;source='genuine MediaCodec Opus and programmatically drawn blue artwork';files=$afFiles} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $afFixtures 'manifest.json')
} else {
    $afManifest=Get-Content -Raw -LiteralPath (Join-Path $afFixtures 'manifest.json') | ConvertFrom-Json
    if($afManifest.producerApi -lt 29 -or $afManifest.files.Count -ne 2) { throw 'Invalid producer manifest' }
    foreach($afName in $afNames) {
        $afExpected=@($afManifest.files | Where-Object {$_.name -ceq $afName})
        if($afExpected.Count -ne 1 -or (Get-FileHash -LiteralPath (Join-Path $afFixtures $afName)).Hash -cne $afExpected[0].sha256) { throw 'Generated fixture identity changed' }
    }
    $afTarget="/sdcard/Android/data/$afPackage/files/artwork-fixtures"
    & $AdbPath -s $EmulatorSerial shell mkdir -p $afTarget
    if($LASTEXITCODE -ne 0) { throw 'Owned test fixture directory unavailable' }
    foreach($afName in $afNames) {
        & $AdbPath -s $EmulatorSerial push (Join-Path $afFixtures $afName) "$afTarget/$afName" | Out-Null
        if($LASTEXITCODE -ne 0) { throw 'Could not stage the verified fixture' }
    }
}
"Genuine audio fixtures: $Mode on API$afApi; no generated media in tracked source"
