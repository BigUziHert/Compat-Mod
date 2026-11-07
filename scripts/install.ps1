[CmdletBinding()]
param([string]$Instance = 'C:\Users\caleb\curseforge\minecraft\Instances\Modded Minecraft', [switch]$EnableFirstPerson)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$mods = [IO.Path]::GetFullPath((Join-Path $Instance 'mods'))
$jar = Join-Path $repo 'build\libs\emf-attachments-compat-1.21.1-1.2.10.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the production jar first.' }
$backup = [IO.Path]::GetFullPath((Join-Path $repo ('.local\installation-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))))
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$changes = [Collections.Generic.List[object]]::new()
$target = Join-Path $mods (Split-Path $jar -Leaf)
foreach ($old in Get-ChildItem -LiteralPath $mods -File | Where-Object { $_.Name -like 'emf-attachments-compat-*.jar*' -or $_.Name -like 'fpm_x_compat-*.jar*' }) {
    $sourcePath=[IO.Path]::GetFullPath($old.FullName)
    $backupPath=[IO.Path]::GetFullPath((Join-Path $backup $old.Name))
    if (!(Split-Path $sourcePath -Parent).Equals($mods,[StringComparison]::OrdinalIgnoreCase) -or !(Split-Path $backupPath -Parent).Equals($backup,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected mod replacement path' }
    $hash=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
    Move-Item -LiteralPath $sourcePath -Destination $backupPath
    $changes.Add(@{action='removed';path=$sourcePath;backup=$backupPath;sha256=$hash})
}
Copy-Item -LiteralPath $jar -Destination $target
$changes.Add(@{ action='installed'; path=$target; sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash })
foreach ($name in $(if ($EnableFirstPerson) { @('firstperson-neoforge-2.7.3-mc1.21.1.jar') } else { @() })) {
    $enabled = Join-Path $mods $name
    $disabled = $enabled + '.disabled'
    if (Test-Path -LiteralPath $enabled) { continue }
    if (!(Test-Path -LiteralPath $disabled)) { throw "Missing verified FirstPerson dependency: $disabled" }
    Copy-Item -LiteralPath $disabled -Destination $backup
    Rename-Item -LiteralPath $disabled -NewName $name
    $changes.Add(@{ action='enabled'; path=$enabled; previousPath=$disabled; sha256=(Get-FileHash -LiteralPath $enabled -Algorithm SHA256).Hash })
}
$changes | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'changes.json') -Encoding UTF8
Write-Output "Installed compatibility mod; FirstPerson state preserved unless explicitly enabled. Backup: $backup"
$changes | ForEach-Object { [pscustomobject]$_ } | Format-Table action,path
