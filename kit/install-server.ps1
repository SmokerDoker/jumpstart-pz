<#
  Jumpstart for a dedicated Windows server ("Project Zomboid Dedicated Server" in Steam).
  powershell -ExecutionPolicy Bypass -File install-server.ps1 [-ServerDir <folder>] [-Uninstall]
  Then add the mods to your server's .ini (see SERVER.md).
#>
param(
    [string]$ServerDir = "C:\Program Files (x86)\Steam\steamapps\common\Project Zomboid Dedicated Server",
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$kit = $PSScriptRoot
$bat = @("StartServer64.bat", "ProjectZomboidServer.bat") | ForEach-Object { Join-Path $ServerDir $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bat) { throw "no StartServer64.bat in $ServerDir (use -ServerDir)" }
$mods = Join-Path $env:USERPROFILE "Zomboid\mods"
if ($Uninstall) {
    if (Test-Path "$bat.before-jumpstart") { Copy-Item "$bat.before-jumpstart" $bat -Force; Remove-Item "$bat.before-jumpstart" }
    Remove-Item (Join-Path $ServerDir "ZombieBuddy.jar") -ErrorAction SilentlyContinue
    Remove-Item "$mods\Jumpstart" -Recurse -Force -ErrorAction SilentlyContinue
    "removed"; return
}
Copy-Item "$kit\ZombieBuddy.jar" (Join-Path $ServerDir "ZombieBuddy.jar") -Force
$text = Get-Content $bat -Raw
if ($text -notmatch 'ZombieBuddy\.jar') {
    Copy-Item $bat "$bat.before-jumpstart" -Force
    # the .bat changes into its own folder first, so the relative path works with spaces in the folder name
    $text = ([regex]'java\.exe"').Replace($text, 'java.exe" -javaagent:ZombieBuddy.jar=hotswap=true', 1)
    [IO.File]::WriteAllText($bat, $text)
}
if ((Get-Content $bat -Raw) -notmatch 'ZombieBuddy\.jar') { throw "could not add -javaagent to $bat; add it after java.exe by hand" }
New-Item -ItemType Directory -Force $mods | Out-Null
if (Test-Path "$mods\Jumpstart") { Remove-Item "$mods\Jumpstart" -Recurse -Force }
Copy-Item "$kit\Jumpstart" "$mods\Jumpstart" -Recurse
"ZombieBuddy (Jumpstart fork) and -javaagent in $(Split-Path $bat -Leaf); Jumpstart -> $mods\Jumpstart"
"Now: Mods=...;ZombieBuddy;Jumpstart and WorkshopItems=...;3619862853 in %USERPROFILE%\Zomboid\Server\<name>.ini (see SERVER.md)"
