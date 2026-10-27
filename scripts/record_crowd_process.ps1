param([Parameter(Mandatory=$true)][ValidateRange(1,4)][int]$Pass)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$runtime = Join-Path $repo '.local/without-firstperson/.local/client'
$runPid = (Get-Content -LiteralPath (Join-Path $runtime 'process.json') -Raw | ConvertFrom-Json).pid
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$runPid"
if ($process.Name -ne 'javaw.exe') { throw 'Owned test process is not running.' }
$props = & 'C:/Program Files/Java/jdk-21/bin/jcmd.exe' $runPid VM.system_properties
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect test JVM.' }
$command = @($props | Select-String '^sun.java.command=').Line
if ($command -notlike '*--username AttachmentTest*--gameDir*Compatibility Mod*.local*without-firstperson*.local*client*--userType legacy*') { throw 'Unexpected test JVM identity.' }
$disabled = [bool]@($props | Select-String '^emfattachments.disable=true$').Count
if ($disabled -ne ($Pass -in 1,4)) { throw 'Actual JVM hook mode differs from planned pass.' }
$result = [pscustomobject]@{
    pid=$runPid; executable=$process.ExecutablePath; osCommandLine=$process.CommandLine
    observedJvmProperties=@($props | Select-String '^(emfattachments.disable|user.dir)=').Line
    offlineFixtureIdentityConfirmed=$true
    launchArgsSha256=(Get-FileHash -LiteralPath (Join-Path $runtime 'launch.args')).Hash
    hooksDisabled=$disabled; observedAt=(Get-Date).ToString('o')
}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $repo "verification/performance/crowd-controlled-plan/pass$Pass-process.json") -Encoding utf8
Write-Output "Verified owned benchmark JVM $runPid, hooks disabled: $disabled"
