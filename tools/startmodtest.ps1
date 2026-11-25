<#
.SYNOPSIS
  In-game test: a Lua mod that came with the save is switched off completely and on again through
  the secure screen (Jumpstart's record of the game's Lua load, LuaStartJournal).

.DESCRIPTION
  Isolated like cleanremovaltest.ps1 (own -cachedir, ZombieBuddy config, BiteCheck folder); nothing of
  your own setup changes. Uses Jumpstart.jar built on the fork's test jar (Lua server on port 4444).
  The test mod tools\testmods\JSTCleanTest is in the mod list BEFORE the game starts, so the new game
  loads it with the save (not live). The script starts the game and WAITS until you start a new
  single player game (any settings). Then:
  - on: JST_Marker, JSTCleanTest, its luautils.stringStarts, window and OnTick handler are there
  - A switches it off: its globals are nil, the game's luautils.stringStarts is back, the window is
    closed, the handler is stopped, and the screen says "off" (not "off, fully with the next load")
  - A switches it on again: globals and function are back, the handler runs
  Results: <TestDir>\startmodtest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$AgentJar = (Join-Path $PSScriptRoot "..\mod\build\agent\Jumpstart.jar"),
    [string]$TestMod = (Join-Path $PSScriptRoot "testmods\JSTCleanTest"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-start-test",
    [int]$WaitForSaveMinutes = 10
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\startmodtest-result.txt"

function Log($line) { $line | Tee-Object -FilePath $result -Append }
function Lua($code) {
    try {
        (Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:4444/lua?sandbox=false' `
            -Body $code -ContentType 'text/plain' -TimeoutSec 60).Content.Trim().Trim('"')
    } catch { "ERR: $($_.Exception.Message)" }
}
function Check($name, $actual, $ok) {
    Log ("{0,-4} {1}: {2}" -f ($(if ($ok) { "OK" } else { "FAIL" })), $name, $actual)
    if (-not $ok) { $script:failures++ }
}
function Key($name) { $k = Lua "return Jumpstart.testKey('$name')"; if ($k -ne "OK") { Log "     key $name`: $k" }; Start-Sleep -Milliseconds 800 }
function Screen() { Lua "return Jumpstart.testScreen()" }
function SelectMod($id) {
    1..5 | ForEach-Object { Key pgup }
    for ($i = 0; $i -lt 80; $i++) { $s = Screen; if ($s -like "* selected=$id *") { return $s }; Key down }
    return Screen
}
function Unpause() {
    $s = Lua "if getGameSpeed() == 0 then setGameSpeed(1) end return tostring(getGameSpeed())"
    if ($s -ne "1") { Log "     game speed: $s" }
    Start-Sleep 1
}
function Ticks() {
    Unpause | Out-Null # its log line must not become part of the result
    $t1 = Lua "return tostring(JST_TableRef.ticks)"
    Start-Sleep 2
    $t2 = Lua "return tostring(JST_TableRef.ticks)"
    return @($t1, $t2)
}
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }

New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy", "$t\.bitecheck", "$t\jumpstart" | Out-Null
Remove-Item $result -ErrorAction SilentlyContinue
foreach ($old in "$t\mods\JSTCleanTest", "$t\Saves", "$t\Lua\jstcleantest.txt") {
    if (Test-Path $old) { Remove-Item $old -Recurse -Force }
}
Copy-Item (Resolve-Path $TestMod) "$t\mods\JSTCleanTest" -Recurse
Copy-Item (Resolve-Path $AgentJar) "$t\Jumpstart.jar" -Force
# the mod is on before the game starts: a new game loads it with the save
[IO.File]::WriteAllText("$t\mods\default.txt", "VERSION = 1,`r`n`r`nmods`r`n{`r`n    mod = JSTCleanTest,`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n")
[IO.File]::WriteAllText("$t\jumpstart\settings.properties", "snapshots=off`r`n")
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", '{"formatVersion": 2, "mods": []}')

$agent = "-javaagent:$t\Jumpstart.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck -Djumpstart.testInput=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -XX:-CreateCoredumpOnCrash -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

$failures = 0
Log "startmodtest $(Get-Date -Format s)"
Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
Check "game and Lua server up" $ping ($ping -eq "pong")

Write-Host "Start a NEW single player game in the game window now (any settings). Waiting up to $WaitForSaveMinutes minutes..."
$deadline = (Get-Date).AddMinutes($WaitForSaveMinutes)
do { Start-Sleep 5; $player = Lua "return tostring(getPlayer() ~= nil)" } until ($player -eq "true" -or (Get-Date) -gt $deadline)
Check "a single player game runs" $player ($player -eq "true")
Start-Sleep 5

# --- on, with the save ------------------------------------------------------------------------------
$r = Lua "return tostring(JST_Marker) .. ' ' .. type(JSTCleanTest)"
Check "with the save: its globals are there" $r ($r -eq "true table")
Lua "JST_TableRef = JSTCleanTest; JST_Game = JSTCleanTest.gameStringStarts; JST_Mod = luautils.stringStarts; JST_PanelRef = JSTCleanTest.panel and JSTCleanTest.panel.javaObject; return 1" | Out-Null
$r = Lua "return tostring(JST_Mod ~= JST_Game)"
Check "with the save: luautils.stringStarts is the mod's" $r ($r -eq "true")
# the test mod opens its window in OnGameStart; with a new game that is not always on screen yet
$r = Lua "return tostring(JST_PanelRef ~= nil and UIManager.getUI():contains(JST_PanelRef))"
Log "     with the save: its window is open: $r"
$tk = Ticks
Check "with the save: its OnTick handler runs" "$($tk[0]) -> $($tk[1])" ($tk[0] -ne $tk[1])
$rec = @(Select-String -Path "$t\game-out.txt" -Pattern 'Lua load recorded: (\d+)' -ErrorAction SilentlyContinue | Select-Object -Last 1)
Check "the game's Lua load was recorded" "$($rec.Line)" ($rec.Count -eq 1 -and [int]$rec[0].Matches[0].Groups[1].Value -gt 0)

# --- off -------------------------------------------------------------------------------------------
Key open
$s = SelectMod "JSTCleanTest"
Check "select the test mod (on)" $s ($s -match 'selected=JSTCleanTest state=lua:on(?!,)')
Key a
Start-Sleep 2
$s = Screen
Check "the screen says off, not 'with the next load'" $s ($s -match 'state=lua:off(?!,)')
Key esc
Start-Sleep 2
$r = Lua "return tostring(JST_Marker) .. ' ' .. tostring(JSTCleanTest)"
Check "off: its globals are gone" $r ($r -eq "nil nil")
$r = Lua "return tostring(luautils.stringStarts == JST_Game)"
Check "off: the game's luautils.stringStarts is back" $r ($r -eq "true")
$r = Lua "return tostring(UIManager.getUI():contains(JST_PanelRef))"
Check "off: its window is closed" $r ($r -eq "false")
$tk = Ticks
Check "off: its OnTick handler is stopped" "$($tk[0]) -> $($tk[1])" ($tk[0] -eq $tk[1])

# --- on again --------------------------------------------------------------------------------------
Key open
SelectMod "JSTCleanTest" | Out-Null
Key a
Start-Sleep 2
$s = Screen
Check "the screen says on" $s ($s -match 'state=lua:on(?!,)')
Key esc
Start-Sleep 2
$r = Lua "return tostring(JST_Marker) .. ' ' .. tostring(luautils.stringStarts == JST_Mod)"
Check "on again: its globals and function are back" $r ($r -eq "true true")
$tk = Ticks
Check "on again: its OnTick handler runs" "$($tk[0]) -> $($tk[1])" ($tk[0] -ne $tk[1])

$errors = @(Select-String -Path "$t\game-out.txt" -Pattern 'secure screen error' -ErrorAction SilentlyContinue)
Check "no secure screen errors" $errors.Count ($errors.Count -eq 0)
Log "Jumpstart log lines:"
Select-String -Path "$t\game-out.txt" -Pattern '\[Jumpstart\]' | Select-Object -Last 20 | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
Lua 'getCore():quit() return 1' | Out-Null
Start-Sleep 15
GameProcess | Stop-Process -Force
exit $failures
