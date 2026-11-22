<#
  Jumpstart test kit for Windows: puts the ZombieBuddy fork and the Jumpstart mod in place.
  Works with ZombieBuddy installed the normal way (ZombieBuddyInstaller.exe, zbNative.dll) and without:
  then the fork starts as a Java agent from ProjectZomboid64.json.
  Run:  double-click "Jumpstart installieren.cmd", or
        powershell -ExecutionPolicy Bypass -File install-windows.ps1      (-Uninstall to undo)
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
    } else {
        # no ZombieBuddyInstaller: the fork starts as a plain Java agent
        $agent = "-javaagent:" + $zb.Replace('\', '/') + "=hotswap=true"
        $text = ([regex]'"vmArgs"\s*:\s*\[').Replace($text, "`"vmArgs`": [`r`n`t`t`"$agent`",", 1)
        if ($text -notmatch 'hotswap=true') { throw "could not add $agent to ProjectZomboid64.json; add it to vmArgs by hand" }
    }
    [IO.File]::WriteAllText($json, $text)
    "ProjectZomboid64.json: hotswap=true"
}

New-Item -ItemType Directory -Force $mods | Out-Null
if (Test-Path "$mods\Jumpstart") { Remove-Item "$mods\Jumpstart" -Recurse -Force }
Copy-Item "$kit\Jumpstart" "$mods\Jumpstart" -Recurse
"Jumpstart mod: $mods\Jumpstart"

# ZombieBuddy and Jumpstart on in the main menu's mod list (a copy of the old list stays next to it)
$list = Join-Path $mods "default.txt"
if (-not (Test-Path $list)) { [IO.File]::WriteAllText($list, "VERSION = 1,`r`n`r`nmods`r`n{`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n") }
foreach ($id in "Jumpstart", "ZombieBuddy") { # inserted at the top: ZombieBuddy ends up first
    $t = Get-Content $list -Raw
    if ($t -notmatch "mod = \\?$id,") {
        if (-not (Test-Path "$list.before-jumpstart")) { Copy-Item $list "$list.before-jumpstart" }
        $t = ([regex]'(?m)^mods\r?\n\{\r?\n').Replace($t, "mods`r`n{`r`n    mod = $id,`r`n", 1)
        [IO.File]::WriteAllText($list, $t)
    }
}
"ZombieBuddy and Jumpstart enabled in $list"

$steamapps = Split-Path (Split-Path $GameDir)
if (-not (Test-Path (Join-Path $steamapps "workshop\content\108600\3619862853"))) {
    ""
    "ZombieBuddy is not subscribed in the Steam Workshop yet: opening its page, click Subscribe."
    Start-Process "steam://url/CommunityFilePage/3619862853" -ErrorAction SilentlyContinue
}
""
"Done. Start Project Zomboid through Steam. ZombieBuddy asks once whether Jumpstart.jar may load:"
"say yes. If BiteCheck is installed, allow Jumpstart there ('Allow anyway'). Ctrl+Shift+J opens Jumpstart."
