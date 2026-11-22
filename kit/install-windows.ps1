<#
  Jumpstart test kit for Windows: puts the ZombieBuddy fork and the Jumpstart mod in place.
  Needs ZombieBuddy installed once the normal way (ZombieBuddyInstaller.exe), so zbNative.dll is there.
  Run:  powershell -ExecutionPolicy Bypass -File install-windows.ps1      (-Uninstall to undo)
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$kit = $PSScriptRoot
$zb = Join-Path $GameDir "ZombieBuddy.jar"
$json = Join-Path $GameDir "ProjectZomboid64.json"
$mods = Join-Path $env:USERPROFILE "Zomboid\mods"
if (-not (Test-Path $GameDir)) { throw "Project Zomboid not found at $GameDir (use -GameDir)" }

if ($Uninstall) {
    if (Test-Path "$zb.before-jumpstart") { Copy-Item "$zb.before-jumpstart" $zb -Force; Remove-Item "$zb.before-jumpstart"; "ZombieBuddy.jar restored" }
    if (Test-Path "$json.before-jumpstart") { Copy-Item "$json.before-jumpstart" $json -Force; Remove-Item "$json.before-jumpstart"; "ProjectZomboid64.json restored" }
    if (Test-Path "$mods\Jumpstart") { Remove-Item "$mods\Jumpstart" -Recurse -Force; "Jumpstart mod removed" }
    return
}
if (-not (Test-Path (Join-Path $GameDir "zbNative.dll"))) { throw "zbNative.dll is missing: install ZombieBuddy first with ZombieBuddyInstaller.exe" }

if (-not (Test-Path "$zb.before-jumpstart") -and (Test-Path $zb)) { Copy-Item $zb "$zb.before-jumpstart" }
Copy-Item "$kit\ZombieBuddy.jar" $zb -Force
"ZombieBuddy.jar: Jumpstart fork"

$text = Get-Content $json -Raw
if ($text -notmatch 'hotswap=true') {
    if (-not (Test-Path "$json.before-jumpstart")) { Copy-Item $json "$json.before-jumpstart" }
    if ($text -match '"-agentlib:zbNative=([^"]*)"') {
        $text = $text -replace '"-agentlib:zbNative=([^"]*)"', '"-agentlib:zbNative=$1,hotswap=true"'
    } elseif ($text.Contains('"-agentlib:zbNative"')) {
        $text = $text.Replace('"-agentlib:zbNative"', '"-agentlib:zbNative=hotswap=true"')
    } else { throw "ProjectZomboid64.json has no -agentlib:zbNative entry: install ZombieBuddy first" }
    [IO.File]::WriteAllText($json, $text)
    "ProjectZomboid64.json: hotswap=true"
}

New-Item -ItemType Directory -Force $mods | Out-Null
if (Test-Path "$mods\Jumpstart") { Remove-Item "$mods\Jumpstart" -Recurse -Force }
Copy-Item "$kit\Jumpstart" "$mods\Jumpstart" -Recurse
"Jumpstart mod: $mods\Jumpstart"
""
"Done. In the game: enable ZombieBuddy and Jumpstart in the mod menu; ZombieBuddy asks once whether"
"Jumpstart.jar may load (say yes). Then Ctrl+Shift+J opens Jumpstart's screen."
