<#
  Jumpstart for a dedicated Windows server ("Project Zomboid Dedicated Server" in Steam).
  Double-click "Server einrichten.cmd", or
  powershell -ExecutionPolicy Bypass -File install-server.ps1 [-ServerDir <folder>] [-ServerName <name>] [-Uninstall]
  With a server name (default: the only %USERPROFILE%\Zomboid\Server\*.ini, if there is just one) it also
  puts ZombieBuddy and Jumpstart into that server's Mods=. ZombieBuddy (the fork) comes as a mod folder in
  the server's mods, so no Workshop item is needed for it.
#>
param(
    [string]$ServerDir = "C:\Program Files (x86)\Steam\steamapps\common\Project Zomboid Dedicated Server",
    [string]$ServerName = "",
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
    if (Test-Path "$mods\ZombieBuddy\jumpstart-fork.txt") { Remove-Item "$mods\ZombieBuddy" -Recurse -Force }
    "removed"; return
}
Copy-Item "$kit\ZombieBuddy\libs\ZombieBuddy.jar" (Join-Path $ServerDir "ZombieBuddy.jar") -Force
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
if ((Test-Path "$mods\ZombieBuddy") -and -not (Test-Path "$mods\ZombieBuddy\jumpstart-fork.txt")) {
    $backup = Join-Path $env:USERPROFILE ("Zomboid\jumpstart-backup\ZombieBuddy-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    New-Item -ItemType Directory -Force (Split-Path $backup) | Out-Null
    Move-Item "$mods\ZombieBuddy" $backup
    "your own ZombieBuddy mod folder moved to $backup"
}
if (Test-Path "$mods\ZombieBuddy") { Remove-Item "$mods\ZombieBuddy" -Recurse -Force }
Copy-Item "$kit\ZombieBuddy" "$mods\ZombieBuddy" -Recurse
"ZombieBuddy (Jumpstart fork) and -javaagent in $(Split-Path $bat -Leaf); ZombieBuddy and Jumpstart -> $mods"

$inis = Join-Path $env:USERPROFILE "Zomboid\Server"
if (-not $ServerName -and (Test-Path $inis)) {
    $found = @(Get-ChildItem $inis -Filter *.ini)
    if ($found.Count -eq 1) { $ServerName = $found[0].BaseName }
}
if ($ServerName) {
    $ini = Join-Path $inis "$ServerName.ini"
    if (-not (Test-Path $ini)) {
        # a new server: the game fills in every other option on its first start
        New-Item -ItemType Directory -Force $inis | Out-Null
        [IO.File]::WriteAllText($ini, "Mods=`r`nWorkshopItems=`r`n")
    }
    if (-not (Test-Path "$ini.before-jumpstart")) { Copy-Item $ini "$ini.before-jumpstart" }
    function Add-Entries([string]$line, [string]$key, [string[]]$items) {
        $value = $line.Substring($key.Length + 1)
        $parts = @($value.Split(';') | Where-Object { $_ -ne '' })
        $prefix = if ($key -eq 'Mods' -and ($parts | Where-Object { $_.StartsWith('\') })) { '\' } else { '' }
        foreach ($item in $items) {
            if (-not ($parts | Where-Object { $_.TrimStart('\') -eq $item })) { $parts += $prefix + $item }
        }
        return "$key=" + ($parts -join ';')
    }
    $lines = Get-Content $ini | ForEach-Object {
        if ($_ -like 'Mods=*') { Add-Entries $_ 'Mods' @('ZombieBuddy', 'Jumpstart') }
        else { $_ }
    }
    [IO.File]::WriteAllLines($ini, [string[]]$lines)
    "ZombieBuddy and Jumpstart added to $ini"
    $lines | Where-Object { $_ -like 'Mods=*' } | ForEach-Object { "    $_" }
} else {
    "Now: Mods=...;ZombieBuddy;Jumpstart in %USERPROFILE%\Zomboid\Server\<name>.ini (see SERVER.md),"
    "or run this again with -ServerName <name>."
}
"First start in a console: ZombieBuddy asks there once per new Java jar (answer y)."
