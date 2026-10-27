param([Parameter(Mandatory=$true)][ValidateRange(2,4)][int]$Pass, [ValidateRange(1,9)][int]$Attempt=1)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime = [IO.Path]::GetFullPath((Join-Path $repo '.local/without-firstperson/.local/client'))
$lastPid = (Get-Content -LiteralPath (Join-Path $runtime 'process.json') -Raw | ConvertFrom-Json).pid
if (Get-Process -Id $lastPid -ErrorAction SilentlyContinue) { throw 'Stop the previous owned test client before resetting its disposable world.' }
if (Test-Path -LiteralPath (Join-Path $runtime 'emfattachments-queue.lock')) { throw 'A queue still owns this test runtime.' }
$saves = [IO.Path]::GetFullPath((Join-Path $runtime 'saves'))
$world = [IO.Path]::GetFullPath((Join-Path $saves 'AttachmentTest'))
$snapshots = [IO.Path]::GetFullPath((Join-Path $repo '.local/benchmark-snapshots'))
$archive = [IO.Path]::GetFullPath((Join-Path $snapshots ("before-pass$Pass-attempt$Attempt")))
$baseline = Join-Path $snapshots 'crowd-base'
if (!(Split-Path $world -Parent).Equals($saves,[StringComparison]::OrdinalIgnoreCase) -or
    !(Split-Path $archive -Parent).Equals($snapshots,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected benchmark world path.' }
if (Test-Path -LiteralPath $archive) { throw 'Refusing to overwrite prior benchmark evidence.' }
if (!(Test-Path -LiteralPath (Join-Path $baseline 'level.dat'))) { throw 'Baseline world snapshot is missing.' }
Move-Item -LiteralPath $world -Destination $archive
Copy-Item -LiteralPath $baseline -Destination $world -Recurse
if ((Get-FileHash -LiteralPath (Join-Path $baseline 'level.dat')).Hash -ne (Get-FileHash -LiteralPath (Join-Path $world 'level.dat')).Hash) { throw 'World reset differs.' }
$env:EMF_CROWD_PASS = [string]$Pass
try {
@'
import os, sys
from pathlib import Path
root=Path.cwd().resolve()
sys.path.insert(0,str(root/'scripts'))
import test_runtime as t
t.RUNTIME=root/'.local/without-firstperson/.local/client'
t.launch(baseline=int(os.environ['EMF_CROWD_PASS'])==4,auto_sweep=False)
'@ | python -
if ($LASTEXITCODE -ne 0) { throw 'Benchmark launch failed.' }
} finally { Remove-Item Env:EMF_CROWD_PASS }
