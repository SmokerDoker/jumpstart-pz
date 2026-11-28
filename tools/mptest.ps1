<#
.SYNOPSIS
  In-game multiplayer test, fully by itself: a dedicated server with Jumpstart and a game client with
  Jumpstart on the same PC; the client joins through Lua and plays.

.DESCRIPTION
  Isolated: the server and the client each get their own -cachedir, ZombieBuddy config and BiteCheck
  folder below <TestDir>; both run without Steam (-nosteam), so nothing of your own setup changes.
  The client uses Jumpstart.jar built on the fork's test jar (Lua server on port 4444), see
  gametest.ps1; the server uses the same jar and is driven through RCON. Steps:
  - the server starts with the item test mod (tools\testmods\JSTItemTest) and reports its
    multiplayer side ready; the client joins (ConnectToServer), the character creation is skipped
    (tools\lua\mp-spawn.lua), the player is in the game
  - RCON gives the player three JSTItemTest.TestToken; the server saves and stops
  - the server starts WITHOUT the mod, the client joins again: one placeholder "… TestToken x3"
    carries the three (the server made it: it keeps the players' inventories, and told the client)
  - the server starts with the mod again: the three tokens are back, the placeholder is gone
  - once more: the tokens are still there (the server saved them)
  Results: <TestDir>\mptest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$AgentJar = (Join-Path $PSScriptRoot "..\mod\build\agent\Jumpstart.jar"),
    [string]$TestMod = (Join-Path $PSScriptRoot "testmods\JSTItemTest"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-mp-test",
    [int]$Port = 16261,
    [int]$RconPort = 27115
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$s = "$t\server"
$c = "$t\client"
$result = "$t\mptest-result.txt"

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
# a fresh data folder shows the terms of service first; the main menu comes after they are accepted
$acceptTerms = "local u = ISTermsOfServiceUI and ISTermsOfServiceUI.instance if u and u:isReallyVisible() then u:onButtonAccept(u.buttonAccept) return 'accepted' end return 'none'"
$tokens = "local n = 0 local items = getPlayer():getInventory():getItems() for i = 0, items:size() - 1 do if items:get(i):getFullType() == 'JSTItemTest.TestToken' then n = n + 1 end end return tostring(n)"
$holders = "local names = {} local items = getPlayer():getInventory():getItems() for i = 0, items:size() - 1 do local it = items:get(i) if it:getFullType() == 'Base.GamePieceRed' and it:hasModData() and it:getModData().jumpstartMissing then table.insert(names, it:getName()) end end return #names .. ' ' .. table.concat(names, '; ')"

function ServerOut() { if (Test-Path "$s\server-out.txt") { Get-Content "$s\server-out.txt" -Raw } else { "" } }
function Processes($kind) { Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($t) -and $_.CommandLine -match $kind } }

# --- RCON (the Source protocol the server speaks): one command, its answer ------------------------------
function Rcon($command) {
    try {
        $tcp = New-Object Net.Sockets.TcpClient("127.0.0.1", $RconPort)
        $st = $tcp.GetStream()
        $st.ReadTimeout = 10000
        function Send($id, $type, $body) {
            $b = [Text.Encoding]::UTF8.GetBytes($body)
            $p = New-Object byte[] (14 + $b.Length)
            [BitConverter]::GetBytes([int](10 + $b.Length)).CopyTo($p, 0)
            [BitConverter]::GetBytes([int]$id).CopyTo($p, 4)
            [BitConverter]::GetBytes([int]$type).CopyTo($p, 8)
            $b.CopyTo($p, 12)
            $st.Write($p, 0, $p.Length)
        }
        function Receive() {
            $h = New-Object byte[] 4; $n = 0; while ($n -lt 4) { $n += $st.Read($h, $n, 4 - $n) }
            $len = [BitConverter]::ToInt32($h, 0)
            $d = New-Object byte[] $len; $n = 0; while ($n -lt $len) { $n += $st.Read($d, $n, $len - $n) }
            return @([BitConverter]::ToInt32($d, 0), [Text.Encoding]::UTF8.GetString($d, 8, [Math]::Max(0, $len - 10)))
        }
        Send 1 3 "jstest"
        $auth = Receive
        if ($auth[0] -eq 1 -and $auth[1] -eq "") { $auth = Receive } # an empty answer comes first on some servers
        if ($auth[0] -eq -1) { return "ERR: RCON password refused" }
        Send 2 2 $command
        $answer = Receive
        $tcp.Close()
        return $answer[1].Trim()
    } catch { return "ERR: $($_.Exception.Message)" }
}

function StartServer($mods) {
    [IO.File]::WriteAllText("$s\Server\jstest.ini", "Mods=$mods`r`nWorkshopItems=`r`nMap=Muldraugh, KY`r`nPublic=false`r`nOpen=true`r`nDefaultPort=$Port`r`nRCONPort=$RconPort`r`nRCONPassword=jstest`r`n")
    if (Test-Path "$s\server-out.txt") { Move-Item "$s\server-out.txt" "$s\server-out-$(Get-Date -Format HHmmss).txt" -Force }
    Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-server.cmd`"" -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(6)
    do { Start-Sleep 5; $out = ServerOut } until ($out -match 'SERVER STARTED' -or $out -match 'Exception in thread "main"' -or (Get-Date) -gt $deadline)
    return ($out -match 'SERVER STARTED')
}

function StopServer() {
    Log "     server save: $(Rcon 'save')"
    Start-Sleep 5
    Log "     server quit: $(Rcon 'quit')"
    $deadline = (Get-Date).AddMinutes(2)
    while ((Processes 'GameServer') -and (Get-Date) -lt $deadline) { Start-Sleep 3 }
    Processes 'GameServer' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep 3
}

# the client starts, joins, skips the character creation of a new player, clicks "click to start"
function Join() {
    if (Test-Path "$c\game-out.txt") { Move-Item "$c\game-out.txt" "$c\game-out-$(Get-Date -Format HHmmss).txt" -Force }
    Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-client.cmd`"" -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(4)
    do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
    $deadline = (Get-Date).AddMinutes(2)
    do { Start-Sleep 3; Lua $acceptTerms | Out-Null; $menu = Lua "return tostring(MainScreen ~= nil and MainScreen.instance ~= nil and ConnectToServer.instance ~= nil and ConnectToServer.instance.title ~= nil)" } until ($menu -eq "true" -or (Get-Date) -gt $deadline)
    $r = Lua "ConnectToServer.instance:connect(MainScreen.instance.bottomPanel, '', 'tester', 'tester', '127.0.0.1', '', '$Port', '', false, true, 1) return 'OK'"
    if ($r -ne "OK") { return "connect: $r" }
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep 3
        $state = Lua "local ms = MainScreen.instance if getPlayer() then return 'in game' end if ms and ((ms.charCreationProfession and ms.charCreationProfession:isVisible()) or (MapSpawnSelect.instance and MapSpawnSelect.instance:isVisible()) or (WorldSelect.instance and WorldSelect.instance:isVisible())) then return 'creation' end local c = ConnectToServer.instance return 'connecting ' .. tostring(c and c.connecting) .. ' ' .. tostring(c and c.failMessage)"
        # joining a server with mods resets Lua: meanwhile the Lua server answers with errors, keep waiting
    } until ($state -eq "creation" -or $state -eq "in game" -or $state -like "connecting false*" -or (Get-Date) -gt $deadline)
    # Log writes to the pipeline too: kept out of what Join returns
    if ($state -eq "creation") { Log "     spawn: $(Lua (Get-Content (Join-Path $PSScriptRoot 'lua\mp-spawn.lua') -Raw))" | Out-Null }
    elseif ($state -ne "in game") { Log "     after connecting: $state" | Out-Null }
    $deadline = (Get-Date).AddMinutes(6)
    do { Start-Sleep 5; $player = Lua "return tostring(getPlayer() ~= nil and isClient())" } until ($player -eq "true" -or (Get-Date) -gt $deadline)
    $deadline = (Get-Date).AddSeconds(90)
    do { $k = Lua "return Jumpstart.testClickToStart()"; if ($k -ne "not loading") { Start-Sleep 2 } } until ($k -eq "not loading" -or (Get-Date) -gt $deadline)
    Start-Sleep 5
    return $player
}

function StopClient() {
    Processes 'MainScreenState' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep 5
}

# the inventory until it holds what is expected (the server swaps a moment after it loaded the player)
function Watch($label, $seconds, $wantTokens, $wantHolders) {
    for ($i = 0; $i -lt $seconds; $i += 3) {
        $n = Lua $tokens
        $h = Lua $holders
        if ($n -eq $wantTokens -and $h -like "$wantHolders*") { Log "     $label after $i s: tokens $n, placeholders $h"; return }
        Start-Sleep 3
    }
    Log "     $label after $seconds s: tokens $(Lua $tokens), placeholders $(Lua $holders)"
}

if (Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" }) { throw "Project Zomboid is already running; close it first" }

foreach ($dir in $s, $c) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Force "$dir\mods", "$dir\.zombie_buddy", "$dir\.bitecheck", "$dir\jumpstart" | Out-Null
    [IO.File]::WriteAllText("$dir\.zombie_buddy\mod_approvals.json", '{"formatVersion": 2, "mods": []}')
    [IO.File]::WriteAllText("$dir\jumpstart\settings.properties", "snapshots=off`r`n")
    Copy-Item (Resolve-Path $TestMod) "$dir\mods\JSTItemTest" -Recurse
}
New-Item -ItemType Directory -Force "$s\Server" | Out-Null
Remove-Item $result -ErrorAction SilentlyContinue
Copy-Item (Resolve-Path $AgentJar) "$t\Jumpstart.jar" -Force

$java = "`".\jre64\bin\java.exe`" --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:+UseZGC -XX:-CreateCoredumpOnCrash"
$server = "@echo off`r`ncd /d `"$GameDir`"`r`nSET _JAVA_OPTIONS=`r`n$java -javaagent:$t\Jumpstart.jar=hotswap=true,policy=deny-new,config_dir=$s\.zombie_buddy -Djumpstart.bitecheckDir=$s\.bitecheck -Xmx3072m -Djava.awt.headless=true -Djava.library.path=./natives/;./natives/win64/;./ -cp ./;projectzomboid.jar zombie.network.GameServer -cachedir=$s -servername jstest -adminpassword jstest -nosteam > `"$s\server-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-server.cmd", $server)
$client = "@echo off`r`ncd /d `"$GameDir`"`r`n$java -javaagent:$t\Jumpstart.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$c\.zombie_buddy -Djumpstart.bitecheckDir=$c\.bitecheck -Djumpstart.testInput=true -Dzomboid.steam=0 -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$c -nosteam > `"$c\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-client.cmd", $client)

$failures = 0
Log "mptest $(Get-Date -Format s)"

# --- 1: with the mod: the player gets three of its items ----------------------------------------------
Check "the server started with the item mod" "" (StartServer "JSTItemTest")
Check "its multiplayer side is ready" "" ((ServerOut) -match 'multiplayer: server side ready')
$player = Join
Check "the client joined and plays" $player ($player -eq "true")
$r = Lua "return tostring(getPlayer():getUsername())"
Check "the player is 'tester'" $r ($r -eq "tester")
Check "the server saw the player" "" ((ServerOut) -match 'Connected new client')
Log "     additem: $(Rcon 'additem "tester" "JSTItemTest.TestToken" 3')"
Watch "given" 30 "3" "0"
$r = Lua $tokens
Check "three tokens in the inventory" $r ($r -eq "3")
StopClient
StopServer

# --- 2: without the mod: a placeholder carries them ----------------------------------------------------
Check "the server started without the mod" "" (StartServer "")
$player = Join
Check "the client joined again" $player ($player -eq "true")
Watch "off" 60 "0" "1 "
$r = Lua $holders
Check "off: one placeholder carries the three" $r ($r -like "1 *TestToken x3")
Check "the server made it (it keeps the players' inventories)" "" ((ServerOut) -match 'placeholder for JSTItemTest')
StopClient
StopServer

# --- 3: with the mod again: the items are back ---------------------------------------------------------
Check "the server started with the mod again" "" (StartServer "JSTItemTest")
$player = Join
Check "the client joined a third time" $player ($player -eq "true")
Watch "on" 60 "3" "0"
$r = Lua $tokens
Check "on: the three tokens are back" $r ($r -eq "3")
$r = Lua $holders
Check "on: the placeholder is gone" $r ($r -like "0*")
StopClient
StopServer

# --- 4: the server saved them: still there after the next join ------------------------------------------
Check "the server started a fourth time" "" (StartServer "JSTItemTest")
$player = Join
Check "the client joined a fourth time" $player ($player -eq "true")
Watch "kept" 30 "3" "0"
$r = Lua $tokens
Check "kept: the three tokens are still there" $r ($r -eq "3")

$errors = @(Select-String -Path "$s\server-out*.txt" -Pattern 'items of missing mods: |server tick failed' -ErrorAction SilentlyContinue)
Check "no placeholder errors on the server" $errors.Count ($errors.Count -eq 0)
Log "server Jumpstart lines (all runs):"
Select-String -Path "$s\server-out*.txt" -Pattern '\[Jumpstart\]' -ErrorAction SilentlyContinue | Where-Object { $_.Line -notmatch 'hooks in|checksum|Lua API' } | Select-Object -Last 20 | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
StopClient
Processes 'GameServer' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
exit $failures
