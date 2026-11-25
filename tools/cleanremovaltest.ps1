<#
.SYNOPSIS
  In-game test of clean removal and save snapshots: a Lua mod switched on live, off, on again and
  uninstalled through the secure screen, with Lua's state checked after each step.

.DESCRIPTION
  Isolated like gametest.ps1 (own -cachedir, ZombieBuddy config, BiteCheck folder); nothing of your
  own setup changes. Uses Jumpstart.jar built on the fork's test jar (Lua server on port 4444), see
  gametest.ps1. The test mod tools\testmods\JSTCleanTest replaces luautils.stringStarts, sets
  JST_Marker and the table JSTCleanTest, adds an OnTick handler, opens a JSTPanel window, writes
  Zomboid\Lua\jstcleantest.txt and makes the ModData table "JSTest".

  Switching mods needs a running single player game: the script starts the game and then WAITS until
  you start a new game or load a save (any sandbox save in the test folder; the first run: New Game,
  any settings). Then it does the rest by itself:
  - opening the screen makes a snapshot of the save first (keys wait meanwhile); the zip must exist
  - A switches JSTCleanTest on live: its globals, function, handler, window, file and table appear
  - A switches it off: JST_Marker and JSTCleanTest are nil, luautils.stringStarts is the game's again,
    the OnTick handler is stopped, the window is closed
  - A switches it on again: everything is back (the window stays closed: it opened at game start)
  - U, J, J uninstalls it: the file is in Zomboid\jumpstart\archive, ModData "JSTest" is gone
  Results: <TestDir>\cleanremovaltest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$AgentJar = (Join-Path $PSScriptRoot "..\mod\build\agent\Jumpstart.jar"),
    [string]$TestMod = (Join-Path $PSScriptRoot "testmods\JSTCleanTest"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-clean-test",
    [int]$WaitForSaveMinutes = 10
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\cleanremovaltest-result.txt"

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
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }

New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy", "$t\.bitecheck", "$t\jumpstart" | Out-Null
Remove-Item $result -ErrorAction SilentlyContinue
foreach ($old in "$t\mods\JSTCleanTest", "$t\mods-uninstalled", "$t\jumpstart\archive", "$t\Lua\jstcleantest.txt") {
    if (Test-Path $old) { Remove-Item $old -Recurse -Force }
}
Copy-Item (Resolve-Path $TestMod) "$t\mods\JSTCleanTest" -Recurse
Copy-Item (Resolve-Path $AgentJar) "$t\Jumpstart.jar" -Force
[IO.File]::WriteAllText("$t\jumpstart\settings.properties", "snapshots=on`r`n")
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", '{"formatVersion": 2, "mods": []}')

$agent = "-javaagent:$t\Jumpstart.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck -Djumpstart.testInput=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -XX:-CreateCoredumpOnCrash -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

$failures = 0
Log "cleanremovaltest $(Get-Date -Format s)"
Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
Check "game and Lua server up" $ping ($ping -eq "pong")

Write-Host "Start a new single player game (or load one) in the game window now. Waiting up to $WaitForSaveMinutes minutes..."
$deadline = (Get-Date).AddMinutes($WaitForSaveMinutes)
do { Start-Sleep 5; $player = Lua "return tostring(getPlayer() ~= nil)" } until ($player -eq "true" -or (Get-Date) -gt $deadline)
Check "a single player game runs" $player ($player -eq "true")
Start-Sleep 5
$gameFn = Lua "return tostring(luautils.stringStarts)"

# --- snapshot before the first switch ------------------------------------------------------------
Key open
$deadline = (Get-Date).AddMinutes(3)
# the screen says when the copy is done (or failed); no key is pressed meanwhile
do { Start-Sleep 2; $s = Screen } until ($s -like "*snapshot of your save was made*" -or $s -like "*No snapshot*" -or (Get-Date) -gt $deadline)
Check "the screen says the snapshot is done" $s ($s -like "*snapshot of your save was made*")
$zip = Get-ChildItem "$t\jumpstart\snapshots" -Recurse -Filter *.zip -ErrorAction SilentlyContinue | Select-Object -First 1
Check "a snapshot of the save was made" "$($zip.FullName)" ($null -ne $zip)
Key esc

# --- on live ---------------------------------------------------------------------------------------
Key open
$r = SelectMod "JSTCleanTest"
Check "select the test mod" $r ($r -like "*selected=JSTCleanTest *")
Key a
Start-Sleep 3
$r = Lua "return tostring(JST_Marker) .. ' ' .. type(JSTCleanTest) .. ' ' .. tostring(luautils.stringStarts ~= nil)"
Check "on: its globals are there" $r ($r -like "true table true")
$modFn = Lua "return tostring(luautils.stringStarts)"
Check "on: luautils.stringStarts is the mod's" $modFn ($modFn -ne $gameFn)
Lua "JST_TableRef = JSTCleanTest; JST_PanelRef = JSTCleanTest.panel and JSTCleanTest.panel.javaObject; return 1" | Out-Null
$r = Lua "return tostring(JST_PanelRef ~= nil and UIManager.getUI():contains(JST_PanelRef))"
Check "on: its window is open" $r ($r -eq "true")
$r = Lua "return tostring(ModData.exists('JSTest'))"
Check "on: its ModData table exists" $r ($r -eq "true")
Check "on: its file was written" "" (Test-Path "$t\Lua\jstcleantest.txt")

# --- off -------------------------------------------------------------------------------------------
Key a
Start-Sleep 2
$r = Lua "return tostring(JST_Marker) .. ' ' .. tostring(JSTCleanTest)"
Check "off: its globals are gone" $r ($r -eq "nil nil")
$r = Lua "return tostring(luautils.stringStarts)"
Check "off: the game's luautils.stringStarts is back" $r ($r -eq $gameFn)
$r = Lua "return tostring(UIManager.getUI():contains(JST_PanelRef))"
Check "off: its window is closed" $r ($r -eq "false")
$t1 = Lua "return tostring(JST_TableRef.ticks)"
Start-Sleep 2
$t2 = Lua "return tostring(JST_TableRef.ticks)"
Check "off: its OnTick handler is stopped" "$t1 -> $t2" ($t1 -eq $t2)

# --- on again --------------------------------------------------------------------------------------
Key a
Start-Sleep 2
$r = Lua "return tostring(JST_Marker) .. ' ' .. tostring(luautils.stringStarts)"
Check "on again: its globals and function are back" $r ($r -eq "true $modFn")
$t1 = Lua "return tostring(JST_TableRef.ticks)"
Start-Sleep 2
$t2 = Lua "return tostring(JST_TableRef.ticks)"
Check "on again: its OnTick handler runs" "$t1 -> $t2" ($t1 -ne $t2)

# --- uninstall -------------------------------------------------------------------------------------
Key u
Start-Sleep 2
Key j
Start-Sleep 4
Key j
Start-Sleep 3
$r = Lua "return tostring(ModData.exists('JSTest'))"
Check "uninstalled: its ModData table left the save" $r ($r -eq "false")
Check "uninstalled: its file left Zomboid\Lua" "" (-not (Test-Path "$t\Lua\jstcleantest.txt"))
$archived = Get-ChildItem "$t\jumpstart\archive" -Recurse -Filter jstcleantest.txt -ErrorAction SilentlyContinue | Select-Object -First 1
Check "uninstalled: its file is in the archive" "$($archived.FullName)" ($null -ne $archived)
$stash = Get-ChildItem $t -Recurse -Filter JSTest.bin -ErrorAction SilentlyContinue | Select-Object -First 1
Check "uninstalled: its ModData table was kept aside" "$($stash.FullName)" ($null -ne $stash)
Key esc

$errors = @(Select-String -Path "$t\game-out.txt" -Pattern 'secure screen error' -ErrorAction SilentlyContinue)
Check "no secure screen errors" $errors.Count ($errors.Count -eq 0)
Log "Jumpstart log lines:"
Select-String -Path "$t\game-out.txt" -Pattern '\[Jumpstart\]' | Select-Object -Last 30 | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
Lua 'getCore():quit() return 1' | Out-Null
Start-Sleep 15
GameProcess | Stop-Process -Force
exit $failures
