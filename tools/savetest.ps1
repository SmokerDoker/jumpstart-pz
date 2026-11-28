<#
.SYNOPSIS
  In-game test of loading the save again (L), items of a mod that is off (placeholders) and the
  performance measurement (P), fully by itself.

.DESCRIPTION
  Isolated like cleanremovaltest.ps1 (own -cachedir, ZombieBuddy config, BiteCheck folder); nothing of
  your own setup changes. Uses Jumpstart.jar built on the fork's test jar (Lua server on port 4444),
  see gametest.ps1. Steam must be running. The test mod tools\testmods\JSTItemTest brings the item
  JSTItemTest.TestToken.
  - a new game starts by itself (tools\lua\new-game.lua)
  - A switches JSTItemTest on live; three tokens go into the player's inventory
  - A switches it off, L twice: the game saves, goes to the main menu and continues the save by itself;
    the tokens are gone and one placeholder (Base.GamePieceRed, "... TestToken x3") carries them
  - A switches it on, L twice: the three tokens are back, the placeholder is gone
  - its craft recipe is there after the live load, and the game's own recipes are all still there
    with their inputs (the live load must not reset them)
  - P measures 10 seconds of play; the screen shows the summary when it opens again
  - C writes a crash report (a zip in Zomboid\jumpstart\reports)
  Results: <TestDir>\savetest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$AgentJar = (Join-Path $PSScriptRoot "..\mod\build\agent\Jumpstart.jar"),
    [string]$TestMod = (Join-Path $PSScriptRoot "testmods\JSTItemTest"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-save-test"
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\savetest-result.txt"

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
# a loaded game waits on "click to start" (GameLoadingState): the test clicks it; then the game runs
function Unpause() {
    $deadline = (Get-Date).AddSeconds(90)
    do { $c = Lua "return Jumpstart.testClickToStart()"; if ($c -ne "not loading") { Start-Sleep 2 } } until ($c -eq "not loading" -or (Get-Date) -gt $deadline)
    if ($c -ne "not loading") { Log "     click to start: $c" }
    $s = Lua "if getGameSpeed() == 0 then setGameSpeed(1) end return tostring(getGameSpeed())"
    if ($s -ne "1") { Log "     game speed: $s" }
    Start-Sleep 1
}
function WaitForPlayer($minutes) {
    $deadline = (Get-Date).AddMinutes($minutes)
    do { Start-Sleep 5; $p = Lua "return tostring(getPlayer() ~= nil)" } until ($p -eq "true" -or (Get-Date) -gt $deadline)
    return $p
}
# L twice in the installed tab: saves, main menu, the same save again
function ReloadSave() {
    Lua "JST_BeforeReload = true return 1" | Out-Null # gone with the old Lua state: marks the save as loaded again
    Key open
    SelectMod "JSTItemTest" | Out-Null
    Key l
    $s = Screen
    Key l
    return $s
}
# the save loaded again: a fresh Lua state (the marker is gone) with a player; Lua does not answer while it loads
function WaitForReload($minutes) {
    $deadline = (Get-Date).AddMinutes($minutes)
    do { Start-Sleep 5; $p = Lua "return tostring(JST_BeforeReload == nil and getPlayer() ~= nil)" } until ($p -eq "true" -or (Get-Date) -gt $deadline)
    return $p
}
$tokens = "local n = 0 local items = getPlayer():getInventory():getItems() for i = 0, items:size() - 1 do if items:get(i):getFullType() == 'JSTItemTest.TestToken' then n = n + 1 end end return tostring(n)"
$holders = "local names = {} local items = getPlayer():getInventory():getItems() for i = 0, items:size() - 1 do local it = items:get(i) if it:getFullType() == 'Base.GamePieceRed' and it:hasModData() and it:getModData().jumpstartMissing then table.insert(names, it:getName()) end end return #names .. ' ' .. table.concat(names, '; ')"
# the inventory every 3 s until it holds what is expected, at most $seconds
function Watch($label, $seconds, $wantTokens, $wantHolders) {
    for ($i = 0; $i -lt $seconds; $i += 3) {
        $n = Lua $tokens
        $h = Lua $holders
        if ($n -eq $wantTokens -and $h -like "$wantHolders*") { Log "     $label after $i s: tokens $n, placeholders $h"; return }
        Start-Sleep 3
    }
    Log "     $label after $seconds s: tokens $(Lua $tokens), placeholders $(Lua $holders)"
}
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }

New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy", "$t\.bitecheck", "$t\jumpstart" | Out-Null
Remove-Item $result -ErrorAction SilentlyContinue
foreach ($old in "$t\mods\JSTItemTest", "$t\Saves", "$t\mods\default.txt", "$t\jumpstart\snapshots", "$t\jumpstart\reports") {
    if (Test-Path $old) { Remove-Item $old -Recurse -Force }
}
Copy-Item (Resolve-Path $TestMod) "$t\mods\JSTItemTest" -Recurse
Copy-Item (Resolve-Path $AgentJar) "$t\Jumpstart.jar" -Force
[IO.File]::WriteAllText("$t\jumpstart\settings.properties", "snapshots=off`r`n")
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", '{"formatVersion": 2, "mods": []}')

$agent = "-javaagent:$t\Jumpstart.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck -Djumpstart.testInput=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -XX:-CreateCoredumpOnCrash -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

$failures = 0
Log "savetest $(Get-Date -Format s)"
Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
Check "game and Lua server up" $ping ($ping -eq "pong")

# with the lean main menu the first answer is RESET, or an error as Lua resets under the request
# (its New Game loads the mods first): then again
$newGame = ""
for ($try = 0; $try -lt 2 -and $newGame -notlike "OK*"; $try++) {
    $menuReady = (Get-Date).AddMinutes(3)
    do { Start-Sleep 3; $menu = Lua "return tostring(MainScreen ~= nil and MainScreen.instance ~= nil and MainScreen.instance.desc ~= nil and JST_NewGameClicked == nil)" } until ($menu -eq "true" -or (Get-Date) -gt $menuReady)
    $newGame = Lua (Get-Content (Join-Path $PSScriptRoot "lua\new-game.lua") -Raw)
    if ($newGame -notlike "OK*") { Log "     new game: $newGame" }
}
Check "a new game starts by itself" $newGame ($newGame -like "OK*")
$player = WaitForPlayer 5
Check "a single player game runs" $player ($player -eq "true")
Start-Sleep 5
Unpause

# --- the mod on, three of its items ------------------------------------------------------------------
# the game's own recipes before: how many, and the inputs of each (a live load must leave them as they are)
$recipeSum = "local list = getScriptManager():getAllCraftRecipes() local n = 0 for i = 0, list:size() - 1 do n = n + list:get(i):getInputs():size() end return list:size() .. ' recipes, ' .. n .. ' inputs'"
$recipesBefore = Lua $recipeSum
Log "     recipes before: $recipesBefore"
Key open
$r = SelectMod "JSTItemTest"
Check "select the test mod" $r ($r -like "*selected=JSTItemTest *")
Key a
Start-Sleep 3
Key esc
$r = Lua "return tostring(getScriptManager():getItem('JSTItemTest.TestToken') ~= nil)"
Check "on: its item exists" $r ($r -eq "true")
$r = Lua "local list = getScriptManager():getAllCraftRecipes() for i = 0, list:size() - 1 do local c = list:get(i) if c:getName() == 'MakeTestToken' then return 'found, ' .. c:getInputs():size() .. ' input' end end return 'missing'"
Check "on: its recipe is there" $r ($r -like "found*")
$recipesAfter = Lua $recipeSum
$before = [int]($recipesBefore -split ' ')[0]
$inputsBefore = [int]($recipesBefore -split ' ')[2]
$after = [int]($recipesAfter -split ' ')[0]
$inputsAfter = [int]($recipesAfter -split ' ')[2]
Check "on: the game's recipes are all still there, with their inputs" "$recipesBefore -> $recipesAfter" ($after -eq $before + 1 -and $inputsAfter -ge $inputsBefore + 1)
Lua "for i = 1, 3 do getPlayer():getInventory():AddItem('JSTItemTest.TestToken') end return 1" | Out-Null
$r = Lua $tokens
Check "three tokens in the inventory" $r ($r -eq "3")

# --- off, load the save again: a placeholder ------------------------------------------------------
Key open
SelectMod "JSTItemTest" | Out-Null
Key a
Start-Sleep 3
$r = Screen
Log "     after A: $r"
Key esc
$asked = ReloadSave
Check "L asks first" $asked ($asked -like "*L*")
$player = WaitForReload 5
Check "the save is loaded again by itself" $player ($player -eq "true")
Unpause # the work runs once the game does: right after "click to start"
Watch "off" 60 "0" "1 "
Start-Sleep 3
$r = Lua "return tostring(getScriptManager():getItem('JSTItemTest.TestToken') ~= nil)"
Check "off after the load: its item type is gone" $r ($r -eq "false")
$r = Lua $tokens
Check "off: no tokens" $r ($r -eq "0")
$r = Lua $holders
Check "off: one placeholder carries the three" $r ($r -like "1 *TestToken x3")

# --- on, load the save again: the items are back --------------------------------------------------
Key open
SelectMod "JSTItemTest" | Out-Null
Key a
Start-Sleep 3
Log "     after A: $(Screen)"
Key esc
ReloadSave | Out-Null
$player = WaitForReload 5
Check "the save is loaded again by itself (2)" $player ($player -eq "true")
Unpause # the work runs once the game does: right after "click to start"
Watch "on" 60 "3" "0"
Start-Sleep 3
$r = Lua $tokens
Check "on: the three tokens are back" $r ($r -eq "3")
$r = Lua $holders
Check "on: the placeholder is gone" $r ($r -like "0*")

# --- P: performance ---------------------------------------------------------------------------------
Key open
Log "     before P: $(Screen)"
Key p
Log "     after P: $(Screen)"
Start-Sleep 14
Key open
Start-Sleep 1
$r = Screen
Check "P: the summary is shown" $r ($r -like "*Measured*" -or $r -like "*Gemessen*")

# --- C: crash report --------------------------------------------------------------------------------
Key c
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep 2; $zip = Get-ChildItem "$t\jumpstart\reports" -Filter *.zip -ErrorAction SilentlyContinue | Select-Object -First 1 } until ($zip -or (Get-Date) -gt $deadline)
Check "C: a crash report was written" "$($zip.FullName)" ($null -ne $zip)
if ($zip) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [IO.Compression.ZipFile]::OpenRead($zip.FullName)
    $names = ($z.Entries | ForEach-Object { $_.FullName }) -join ', '
    $z.Dispose()
    Check "C: it holds the console log and the mod list" $names ($names -like "*console*" -and $names -like "*mods*")
}
Key esc

$errors = @(Select-String -Path "$t\game-out.txt" -Pattern 'secure screen error|items of missing mods: ' -ErrorAction SilentlyContinue)
Check "no secure screen or missing item errors" $errors.Count ($errors.Count -eq 0)
Log "Jumpstart log lines:"
Select-String -Path "$t\game-out.txt" -Pattern '\[Jumpstart\]' | Select-Object -Last 30 | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
Lua 'getCore():quit() return 1' | Out-Null
Start-Sleep 15
GameProcess | Stop-Process -Force
exit $failures
