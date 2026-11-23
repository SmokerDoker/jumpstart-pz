<#
  Jumpstart test kit for Windows: puts the ZombieBuddy fork (agent jar and mod folder) and the Jumpstart
  mod in place. No ZombieBuddy from the Workshop and no ZombieBuddyInstaller needed; with zbNative.dll
  from ZombieBuddy's installer it keeps using that, otherwise the fork starts as a Java agent from
  ProjectZomboid64.json.
  Run:  double-click "Jumpstart installieren.cmd", or
        powershell -ExecutionPolicy Bypass -File install-windows.ps1      (-Uninstall to undo)
#>
param(
    [string]$GameDir = "",
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
if (-not $GameDir) { $GameDir = $env:JUMPSTART_GAME_DIR }
if (-not $GameDir) {
    # every Steam library (libraryfolders.vdf), the default one first
    $steam = (Get-ItemProperty "HKCU:\Software\Valve\Steam" -ErrorAction SilentlyContinue).SteamPath
    if (-not $steam) { $steam = "C:\Program Files (x86)\Steam" }
    $libraries = @($steam.Replace('/', '\'))
    $vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
    if (Test-Path $vdf) {
        $libraries += (Select-String $vdf -Pattern '"path"\s+"([^"]+)"' -AllMatches).Matches | ForEach-Object { $_.Groups[1].Value.Replace('\\', '\') }
    }
    $GameDir = $libraries | ForEach-Object { Join-Path $_ "steamapps\common\ProjectZomboid" } |
        Where-Object { Test-Path (Join-Path $_ "ProjectZomboid64.json") } | Select-Object -First 1
    if (-not $GameDir) { throw "Project Zomboid not found in your Steam libraries (use -GameDir <folder>)" }
}
$kit = $PSScriptRoot
$zb = Join-Path $GameDir "ZombieBuddy.jar"
$json = Join-Path $GameDir "ProjectZomboid64.json"
$zomboid = Join-Path $env:USERPROFILE "Zomboid"
$mods = Join-Path $zomboid "mods"
$zbBackup = Join-Path $zomboid "jumpstart-backup\ZombieBuddy"
if (-not (Test-Path $GameDir)) { throw "Project Zomboid not found at $GameDir (use -GameDir)" }

if ($Uninstall) {
    if (Test-Path "$zb.before-jumpstart") { Copy-Item "$zb.before-jumpstart" $zb -Force; Remove-Item "$zb.before-jumpstart"; "ZombieBuddy.jar restored" }
    if (Test-Path "$json.before-jumpstart") { Copy-Item "$json.before-jumpstart" $json -Force; Remove-Item "$json.before-jumpstart"; "ProjectZomboid64.json restored" }
    if (Test-Path "$mods\Jumpstart") { Remove-Item "$mods\Jumpstart" -Recurse -Force; "Jumpstart mod removed" }
    if (Test-Path "$mods\ZombieBuddy\jumpstart-fork.txt") { Remove-Item "$mods\ZombieBuddy" -Recurse -Force; "ZombieBuddy mod (fork) removed" }
    if ((Test-Path $zbBackup) -and -not (Test-Path "$mods\ZombieBuddy")) { Move-Item $zbBackup "$mods\ZombieBuddy"; "your own ZombieBuddy mod folder is back" }
    return
}

if (-not (Test-Path "$zb.before-jumpstart") -and (Test-Path $zb)) { Copy-Item $zb "$zb.before-jumpstart" }
Copy-Item "$kit\ZombieBuddy\libs\ZombieBuddy.jar" $zb -Force
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

# ZombieBuddy's mod folder (the fork), so no Workshop subscription is needed. A ZombieBuddy folder
# of your own (without jumpstart-fork.txt) is moved to Zomboid\jumpstart-backup and comes back with -Uninstall.
if ((Test-Path "$mods\ZombieBuddy") -and -not (Test-Path "$mods\ZombieBuddy\jumpstart-fork.txt")) {
    $target = if (Test-Path $zbBackup) { "$zbBackup-" + (Get-Date -Format "yyyyMMdd-HHmmss") } else { $zbBackup }
    New-Item -ItemType Directory -Force (Split-Path $zbBackup) | Out-Null
    Move-Item "$mods\ZombieBuddy" $target
    "your own ZombieBuddy mod folder moved to $target"
}
if (Test-Path "$mods\ZombieBuddy") { Remove-Item "$mods\ZombieBuddy" -Recurse -Force }
Copy-Item "$kit\ZombieBuddy" "$mods\ZombieBuddy" -Recurse
"ZombieBuddy mod (Jumpstart fork): $mods\ZombieBuddy"

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

""
"Done. Start Project Zomboid through Steam (no Workshop subscription needed). ZombieBuddy asks once whether Jumpstart.jar may load:"
"say yes. If BiteCheck is installed, allow Jumpstart there ('Allow anyway'). Ctrl+Shift+J opens Jumpstart."
