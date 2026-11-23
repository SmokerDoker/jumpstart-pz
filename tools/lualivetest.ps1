<#
.SYNOPSIS
  In-game test: a Java mod's Lua functions are there right after it is loaded live.

.DESCRIPTION
  ZombieBuddy hands a Java mod's Lua classes and global functions to Lua when Lua starts. A mod
  loaded while the game runs (Jumpstart's live install) used to get them only at the next load, so a
  mod that checks for its Java part at once (Knox Inventory: knoxJavaVersion) said its Java part was
  missing. The fork now exposes them right after the live load.

  Isolated like gametest.ps1 (own -cachedir, own ZombieBuddy config); the game folder and your own
  setup are not touched. Uses the fork's test jar (Lua server) and hotswap_lua=true to load the jar
  from Lua, on the game's main thread like Jumpstart's secure screen. Default test mod: Knox Inventory
  from your Workshop folder. Steam must be running. Exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$ForkJar = (Join-Path $PSScriptRoot "..\..\zombiebuddy-jumpstart\java\build\jdk25\libs\zombiebuddy-jumpstart-experimental.jar"),
    [string]$ModJar = "C:\Program Files (x86)\Steam\steamapps\workshop\content\108600\3816775774\mods\KnoxInventoryBeta\42\media\java\client\KnoxInventoryJava.jar",
    [string]$Package = "knox.java",
    [string]$ModId = "KnoxInventoryBeta",
    [string]$LuaGlobal = "knoxJavaVersion",
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-lualive-test"
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\lualivetest-result.txt"
function Log($line) { $line | Tee-Object -FilePath $result -Append }
function Lua($code) {
    try {
        (Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:4444/lua?sandbox=false' `
            -Body $code -ContentType 'text/plain' -TimeoutSec 60).Content.Trim().Trim('"')
    } catch {
        $body = ""
        try { $body = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        "ERR: $($_.Exception.Message) $body"
    }
}
function Check($name, $actual, $ok) {
    Log ("{0,-4} {1}: {2}" -f ($(if ($ok) { "OK" } else { "FAIL" })), $name, $actual)
    if (-not $ok) { $script:failures++ }
}
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }
if (-not (Test-Path $ModJar)) { throw "test mod jar not found: $ModJar" }
New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy" | Out-Null
Remove-Item $result -ErrorAction SilentlyContinue
Copy-Item (Resolve-Path $ForkJar) "$t\ZombieBuddy-fork.jar" -Force
$jar = "$t\mod.jar"
Copy-Item $ModJar $jar -Force
$sha = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json",
    '{"formatVersion": 2, "mods": [{"id": "' + $ModId + '", "jar_hash": "' + $sha + '", "decision": true}]}')
[IO.File]::WriteAllText("$t\mods\default.txt", "VERSION = 1,`r`n`r`nmods`r`n{`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n")

$agent = "-javaagent:$t\ZombieBuddy-fork.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -XX:-CreateCoredumpOnCrash -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

$failures = 0
Log "lualivetest $(Get-Date -Format s): $ModId ($sha)"
Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
Check "game and Lua server up" $ping ($ping -eq "pong")

$r = Lua "return tostring($LuaGlobal)"
Check "$LuaGlobal is not there before the live load" $r ($r -eq "nil")
$path = $jar.Replace('\', '/')
# a big mod takes longer than the Lua server waits for an answer (5 s): the log says when it is done
$r = Lua "return tostring(ZombieBuddy.Hotswap.loadJar('$path', '$Package', '$ModId'))"
$deadline = (Get-Date).AddMinutes(2)
do { Start-Sleep 2; $done = Select-String -Path "$t\game-out.txt" -Pattern "hotswap: loaded $ModId" -Quiet } until ($done -or (Get-Date) -gt $deadline)
Check "live load" "$r" $done
$r = Lua "return type($LuaGlobal)"
Check "$LuaGlobal is there right after the live load" $r ($r -eq "function")
$r = Lua "local ok, v = pcall($LuaGlobal) return tostring(ok) .. ' ' .. tostring(v)"
Check "$LuaGlobal() answers" $r ($r -like "true *")

Log "log lines:"
Select-String -Path "$t\game-out.txt" -Pattern 'hotswap:|Exposing global functions|Lua exposure' | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
Lua 'getCore():quit() return 1' | Out-Null
Start-Sleep 15
GameProcess | Stop-Process -Force
exit $failures
