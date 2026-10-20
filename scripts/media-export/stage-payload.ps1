param([Parameter(Mandatory)][string]$SourceDirectory,
      [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceCommit,
      [string]$OutputDirectory='app/build/generated/media-export')
$ErrorActionPreference='Stop'
$python=if($env:AF_PYTHON_EXE){$env:AF_PYTHON_EXE}else{(Get-Command python.exe -ErrorAction Stop).Source}
& $python (Join-Path $PSScriptRoot 'stage-payload.py') $SourceDirectory --source-commit $SourceCommit --output $OutputDirectory
if($LASTEXITCODE -ne 0){throw 'Native media payload verification/staging failed'}
