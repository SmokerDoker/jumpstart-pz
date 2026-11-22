<#
.SYNOPSIS
  In-game test of Jumpstart's BiteCheck gate with the real BiteCheck registry index.

.DESCRIPTION
  Isolated like the fork's tools/gametest.ps1: a separate Zomboid data folder (-cachedir), its own
  ZombieBuddy config and its own, empty BiteCheck folder (-Djumpstart.bitecheckDir), so the real
  signed index is downloaded fresh and nothing of your own setup changes. The game folder is not
  touched: the fork jar is passed with -javaagent.

  At start only ZombieBuddy and Jumpstart are active. The test then, through ZombieBuddy's
  experimental Lua server on 127.0.0.1:4444:
    - checks that Jumpstart is ready,
    - asks BiteCheck about a changed copy of ZBHelloWorld.jar (unverified): install without
      confirmation must be refused, and nothing may be loaded,
    - installs the real ZBHelloWorld.jar (verified in the registry) without any confirmation and
      checks that its patch is in MainScreenState,
    - switches it off and on, and updates it to ZBHelloWorld-2x.jar (also verified),
    - checks that mod_approvals.json has the two verified hashes and not the copy's.
  Steam must be running. Results: <TestDir>\gametest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$ForkJar = (Join-Path $PSScriptRoot "..\..\zombiebuddy-jumpstart\java\build\jdk25\libs\zombiebuddy-jumpstart.jar"),
    [string]$ModFolder = (Join-Path $PSScriptRoot "..\mod\build\mod\Jumpstart"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-gate-test",
    [switch]$KeepRunning
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\gametest-result.txt"
$cls = "zombie.gameStates.MainScreenState"
$text = "Hello World from ZBHelloWorld!"
$pkg = "me.zed_0xff.zb_hello_world"

function Log($line) { $line | Tee-Object -FilePath $result -Append }
function Lua($code) {
    try {
        (Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:4444/lua?sandbox=false' `
            -Body $code -ContentType 'text/plain' -TimeoutSec 60).Content.Trim().Trim('"')
    } catch { "ERR: $($_.Exception.Message)" }
}
function Has() { Lua "return ZombieBuddy.Hotswap.classHasString('$cls', '$text')" }
function Check($name, $actual, $ok) {
    Log ("{0,-4} {1}: {2}" -f ($(if ($ok) { "OK" } else { "FAIL" })), $name, $actual)
    if (-not $ok) { $script:failures++ }
}
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }
function Sha($path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }

# --- test environment ---------------------------------------------------------------------------
Remove-Item $result -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy", "$t\.bitecheck" | Out-Null
if (-not (Test-Path "$t\mods\ZBHelloWorld")) {
    git clone -q --depth 1 https://github.com/zed-0xff/ZBHelloWorld "$t\mods\ZBHelloWorld"
}
if (Test-Path "$t\mods\Jumpstart") { Remove-Item "$t\mods\Jumpstart" -Recurse -Force }
Copy-Item (Resolve-Path $ModFolder) "$t\mods\Jumpstart" -Recurse
Copy-Item (Resolve-Path $ForkJar) "$t\ZombieBuddy-fork.jar" -Force

$jarDir = "$t\mods\ZBHelloWorld\42\media\java\client"
$verified = "$jarDir\ZBHelloWorld.jar"
$verified2x = "$jarDir\ZBHelloWorld-2x.jar"
# a changed copy: same classes, one extra file, so a hash the registry does not know
$copy = "$t\ZBHelloWorld-changed.jar"
Copy-Item $verified $copy -Force
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($copy, 'Update')
$w = New-Object IO.StreamWriter(($zip.CreateEntry("jumpstart-test.txt")).Open()); $w.Write("changed"); $w.Dispose(); $zip.Dispose()

# only ZombieBuddy and Jumpstart at start; Jumpstart.jar is our own build, approve it in the test config
$modsTxt = "VERSION = 1,`r`n`r`nmods`r`n{`r`n    mod = ZombieBuddy,`r`n    mod = Jumpstart,`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n"
[IO.File]::WriteAllText("$t\mods\default.txt", $modsTxt)
$jumpstartJar = "$t\mods\Jumpstart\42\media\java\client\Jumpstart.jar"
$approvals = '{"formatVersion": 2, "mods": [{"id": "Jumpstart", "jar_hash": "' + (Sha $jumpstartJar) + '", "decision": true}]}'
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", $approvals)

$agent = "-javaagent:$t\ZombieBuddy-fork.jar=hotswap=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

# --- test -----------------------------------------------------------------------------------------
$failures = 0
Log "gametest $(Get-Date -Format s)"
Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $ping = Lua "return 'pong'" } until ($ping -eq "pong" -or (Get-Date) -gt $deadline)
$active = Lua 'return tostring(getActivatedMods():contains("Jumpstart"))'
if ($active -ne "true") {
    # a fresh -cachedir does not always pick up default.txt on the first start
    Log "activating ZombieBuddy and Jumpstart like the mod menu does"
    Lua 'local am = ActiveMods.getById("default") am:setModActive("ZombieBuddy", true) am:setModActive("Jumpstart", true) saveModsFile() getCore():ResetLua("default", "modsChanged") return 1' | Out-Null
}
$deadline = (Get-Date).AddMinutes(2)
do { Start-Sleep 5; $status = Lua "return Jumpstart.status()" } until ($status -eq "ready" -or (Get-Date) -gt $deadline)
Check "Jumpstart ready" $status ($status -eq "ready")
Check "ZBHelloWorld not loaded at start" (Has) ((Has) -eq "false")

$c = $copy.Replace('\', '/'); $v = $verified.Replace('\', '/'); $v2 = $verified2x.Replace('\', '/')
$r = Lua "return Jumpstart.check('$c')"
Check "changed copy is unverified" $r ($r -like "CONFIRM UNVERIFIED*")
$r = Lua "return Jumpstart.install('$c', '$pkg', 'ZBHelloWorldChanged', 'none')"
Check "changed copy needs confirmation" $r ($r -like "NEEDS_CONFIRMATION*")
Check "changed copy not loaded" (Has) ((Has) -eq "false")

$r = Lua "return Jumpstart.check('$v')"
Check "real jar is verified" $r ($r -like "ALLOW VERIFIED*")
$r = Lua "return Jumpstart.install('$v', '$pkg', 'ZBHelloWorld', 'none')"
Check "real jar installs without asking" $r ($r -like "DONE*")
Check "patch in after install" (Has) ((Has) -eq "true")
$r = Lua "return ZombieBuddy.Hotswap.off('ZBHelloWorld')"
Check "off" $r ($r -eq "OK ZBHelloWorld off")
Check "patch out" (Has) ((Has) -eq "false")
$r = Lua "return ZombieBuddy.Hotswap.on('ZBHelloWorld')"
Check "on" $r ($r -eq "OK ZBHelloWorld on")
$r = Lua "return Jumpstart.update('$v2', '$pkg', 'ZBHelloWorld', 'none')"
Check "update to the 2x jar (verified)" $r ($r -like "DONE*")
Check "patch in after update" (Has) ((Has) -eq "true")

$approvalsText = Get-Content "$t\.zombie_buddy\mod_approvals.json" -Raw
Check "approval for the real jar" "" ($approvalsText -match (Sha $verified))
Check "approval for the 2x jar" "" ($approvalsText -match (Sha $verified2x))
Check "no approval for the changed copy" "" (-not ($approvalsText -match (Sha $copy)))
Check "real index downloaded" "" (Test-Path "$t\.bitecheck\cache\index.toml")
Check "game still running" "" ([bool](GameProcess))

Log "Jumpstart log lines:"
Select-String -Path "$t\game-out.txt" -Pattern 'Jumpstart|hotswap:' | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"

if (-not $KeepRunning) {
    Lua 'getCore():quit() return 1' | Out-Null
    Start-Sleep 15
    GameProcess | Stop-Process -Force
}
exit $failures
