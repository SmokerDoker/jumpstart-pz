<#
.SYNOPSIS
  In-game test of Jumpstart: the secure screen, the BiteCheck gate with the real registry index,
  and that Lua can neither install anything nor open the screen.

.DESCRIPTION
  Isolated like the fork's tools/gametest.ps1: a separate Zomboid data folder (-cachedir), its own
  ZombieBuddy config and its own, empty BiteCheck folder (-Djumpstart.bitecheckDir), so the real
  signed index is downloaded fresh and nothing of your own setup changes. The game folder is not
  touched: the fork jar is passed with -javaagent.

  Two switches exist only for this test: -Djumpstart.testInput=true -Djumpstart.cloudUrl=http://localhost:8787 lets the test press the secure
  screen's keys through Lua (the real screen reads only the physical keyboard), and the ZombieBuddy
  option hotswap_lua=true allows the bytecode probe that shows whether ZBHelloWorld's patch is in.

  At start only ZombieBuddy and Jumpstart are active; ZBHelloWorld and a changed copy of it (its
  own mod folder, hashes the registry does not know) are in the mods folder. The test checks that
  - Lua's Jumpstart API has nothing that installs or switches, Lua cannot reach GLFW, and fake
    Ctrl+Shift+J events in the game's key queue do not open the screen,
  - in the screen the changed copy asks, J does nothing before the delay, N cancels, nothing loads,
  - the verified ZBHelloWorld loads without a question, A switches it off and on, Enter reloads it,
  - mod_approvals.json has the verified hash and not the copy's.
  Steam must be running. Results: <TestDir>\gametest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    # the fork's test jar with experimental.jar (its Lua server drives this test): in the fork's java/,
    # gradle -PgameClasspath=<game>\projectzomboid.jar shadowJarExperimental. The player jar has no Lua server.
    [string]$ForkJar = (Join-Path $PSScriptRoot "..\..\zombiebuddy-jumpstart\java\build\jdk25\libs\zombiebuddy-jumpstart-experimental.jar"),
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
function Key($name) { $k = Lua "return Jumpstart.testKey('$name')"; if ($k -ne "OK") { Log "     key $name`: $k" }; Start-Sleep -Milliseconds 800 }
function Screen() { Lua "return Jumpstart.testScreen()" }
function SelectMod($id) {
    1..5 | ForEach-Object { Key pgup }
    for ($i = 0; $i -lt 60; $i++) { $s = Screen; if ($s -like "* selected=$id *") { return $s }; Key down }
    return Screen
}
function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }
function Sha($path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

if (GameProcess) { throw "Project Zomboid is already running; close it first" }

# --- test environment ---------------------------------------------------------------------------
Remove-Item $result -ErrorAction SilentlyContinue
Remove-Item "$t\.bitecheck\usage.toml" -ErrorAction SilentlyContinue # sharing: start off, nothing sent today
New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy", "$t\.bitecheck" | Out-Null
if (-not (Test-Path "$t\mods\ZBHelloWorld")) {
    git clone -q --depth 1 https://github.com/zed-0xff/ZBHelloWorld "$t\mods\ZBHelloWorld"
}
if (Test-Path "$t\mods\Jumpstart") { Remove-Item "$t\mods\Jumpstart" -Recurse -Force }
Copy-Item (Resolve-Path $ModFolder) "$t\mods\Jumpstart" -Recurse
Copy-Item (Resolve-Path $ForkJar) "$t\ZombieBuddy-fork.jar" -Force

$verified = "$t\mods\ZBHelloWorld\42\media\java\client\ZBHelloWorld.jar"
# a changed copy as its own mod folder: same classes, one extra file in each jar, so hashes the
# registry does not know
$changedMod = "$t\mods\ZBHelloWorldChanged"
if (Test-Path $changedMod) { Remove-Item $changedMod -Recurse -Force }
New-Item -ItemType Directory -Force "$changedMod\common", "$changedMod\42\media\java\client" | Out-Null
(Get-Content "$t\mods\ZBHelloWorld\common\mod.info") -replace '^name=.*', 'name=ZBHelloWorld (changed copy)' -replace '^id=.*', 'id=ZBHelloWorldChanged' |
    Set-Content "$changedMod\common\mod.info"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
foreach ($jar in "ZBHelloWorld.jar", "ZBHelloWorld-2x.jar") {
    $copy = "$changedMod\42\media\java\client\$jar"
    Copy-Item "$t\mods\ZBHelloWorld\42\media\java\client\$jar" $copy -Force
    $zip = [IO.Compression.ZipFile]::Open($copy, 'Update')
    $w = New-Object IO.StreamWriter(($zip.CreateEntry("jumpstart-test.txt")).Open()); $w.Write("changed"); $w.Dispose(); $zip.Dispose()
}
$changed = "$changedMod\42\media\java\client\ZBHelloWorld.jar"

# only ZombieBuddy and Jumpstart at start; Jumpstart.jar is our own build, approve it in the test config
$modsTxt = "VERSION = 1,`r`n`r`nmods`r`n{`r`n    mod = ZombieBuddy,`r`n    mod = Jumpstart,`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n"
[IO.File]::WriteAllText("$t\mods\default.txt", $modsTxt)
$jumpstartJar = "$t\mods\Jumpstart\42\media\java\Jumpstart.jar"
$approvals = '{"formatVersion": 2, "mods": [{"id": "Jumpstart", "jar_hash": "' + (Sha $jumpstartJar) + '", "decision": true}]}'
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", $approvals)

$agent = "-javaagent:$t\ZombieBuddy-fork.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$start = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck -Djumpstart.testInput=true -Djumpstart.cloudUrl=http://localhost:8787 -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $start)

# a stand-in for the Jumpstart cloud on localhost:8787 (library answers, usage reports to files)
$mock = "$t\mock-cloud"
Start-Process powershell -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSScriptRoot\mock-cloud.ps1`"", '-OutDir', "`"$mock`"" -WindowStyle Hidden
$i = 0; while (-not (Test-Path "$mock\ready") -and $i -lt 20) { Start-Sleep 1; $i++ }

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

# --- Lua can read, but not change anything or open the secure screen ------------------------------
$r = Lua "return tostring(Jumpstart.install) .. ' ' .. tostring(Jumpstart.update) .. ' ' .. tostring(Jumpstart.on) .. ' ' .. tostring(Jumpstart.off)"
Check "Lua API has no install, update, on or off" $r ($r -eq "nil nil nil nil")
$r = Lua "return tostring(GLFW)"
Check "Lua cannot reach GLFW (the physical keyboard)" $r ($r -eq "nil")
$r = Lua "local ok, err = pcall(function() Keyboard.addKeyEvent(29, 1) Keyboard.addKeyEvent(42, 1) Keyboard.addKeyEvent(36, 1) Keyboard.addKeyEvent(36, 0) Keyboard.addKeyEvent(42, 0) Keyboard.addKeyEvent(29, 0) end) return tostring(ok)"
Log "     (fake Ctrl+Shift+J through the game's key queue from Lua: pcall=$r)"
Start-Sleep 2
$r = Screen
Check "fake keys from Lua do not open the secure screen" $r ($r -like "CLOSED*")

# --- the secure screen, keys pressed through the test hook ------------------------------------------
$r = Lua "return Jumpstart.check('$($changed.Replace('\', '/'))')"
Check "changed copy is unverified" $r ($r -like "CONFIRM UNVERIFIED*")
$r = Lua "return Jumpstart.check('$($verified.Replace('\', '/'))')"
Check "real jar is verified" $r ($r -like "ALLOW VERIFIED*")
Check "ZBHelloWorld not loaded" (Lua "return Jumpstart.stateOf('ZBHelloWorld')") ((Lua "return Jumpstart.stateOf('ZBHelloWorld')") -eq "not loaded")

Key open
$i = 0; do { $r = Screen; if ($r -notlike "LIST*") { Start-Sleep 1 }; $i++ } until ($r -like "LIST*" -or $i -gt 10)
Check "Ctrl+Shift+J opens the screen" $r ($r -like "LIST*")
$r = SelectMod "ZBHelloWorldChanged"
Check "select the changed copy" $r ($r -like "*selected=ZBHelloWorldChanged *")
Key enter
$r = Screen
Check "Enter on the changed copy asks" $r ($r -like "CONFIRM_ONCE*asking=CONFIRM*")
Key j
$r = Screen
Check "J right away does nothing (delay)" $r ($r -like "CONFIRM_ONCE*")
Key n
$r = Screen
Check "N cancels" $r ($r -like "LIST*")
Check "changed copy not loaded" (Has) ((Has) -eq "false")

$r = SelectMod "ZBHelloWorld"
Check "select ZBHelloWorld" $r ($r -like "*selected=ZBHelloWorld state=not loaded*")
Key enter
$r = Screen
Check "Enter loads the verified jar without a question" $r ($r -like "LIST*state=on*message=DONE*")
Check "patch in after loading" (Has) ((Has) -eq "true")
Key a
$r = Screen
Check "A switches it off" $r ($r -like "*state=off*")
Check "patch out" (Has) ((Has) -eq "false")
Key a
$r = Screen
Check "A switches it on" $r ($r -like "*state=on*")
Check "patch in" (Has) ((Has) -eq "true")
Key enter
$r = Screen
Check "Enter on a loaded mod reloads its jar" $r ($r -like "*state=on*message=DONE*updated*")
Check "patch in after reload" (Has) ((Has) -eq "true")
Key esc
Start-Sleep 1
$r = Screen
Check "Esc closes" $r ($r -like "CLOSED*")

# --- marketplace and sharing, against the stand-in cloud (nothing is subscribed on Steam) ---------
Key open
Key tab
$r = Screen
Check "Tab: Java library" $r ($r -like "LIST tab=JAVA_LIBRARY*")
Lua "return Jumpstart.testType('fps')" | Out-Null
Start-Sleep 1
Key enter
Start-Sleep 2
$r = Screen
Check "search in the Java library" $r ($r -like "*query=fps results=2 selected=900001*")
Check "the request asked for Java mods" "" ((Get-Content "$mock\requests.txt" -Raw) -match "kind=java&sort=popular&q=fps")
Key down
$r = Screen
Check "select the second result" $r ($r -like "*selected=900002*")
Key tab
Key enter
Start-Sleep 2
$r = Screen
Check "Lua library shows the most popular" $r ($r -like "LIST tab=LUA_LIBRARY*results=1 selected=900101*")
Key tab
Start-Sleep 3
$r = Screen
Check "sharing is off by default" $r ($r -like "LIST tab=SHARING sharing=false*")
Check "nothing sent while off" "" (-not (Test-Path "$mock\usage-1.txt"))
Key enter
Start-Sleep 1
$r = Screen
Check "Enter turns sharing on" $r ($r -like "*sharing=true*")
# the report goes out on Jumpstart's background thread, after whatever it is busy with
$deadline = (Get-Date).AddSeconds(30)
while (-not (Test-Path "$mock\usage-1.txt") -and (Get-Date) -lt $deadline) { Start-Sleep 1 }
$sent = Get-Content "$mock\usage-1.txt" -Raw -ErrorAction SilentlyContinue
Check "a report was sent" $sent ($sent -like "jumpstart-usage 1*")
$changedSha = Sha $changed
Check "the report lists the unverified changed copy" "" ($sent -match "jar\t$changedSha\t\tZBHelloWorldChanged\t\d+")
Check "the report does not list the verified jar" "" (-not ($sent -match (Sha $verified)))
Check "the report has no paths or names" "" (-not ($sent -match [regex]::Escape($env:USERNAME)) -and -not ($sent -match ':\\'))
Key enter
Start-Sleep 1
$r = Screen
Check "Enter turns sharing off again" $r ($r -like "*sharing=false*")
Key esc
Start-Sleep 1

$errors = @(Select-String -Path "$t\game-out.txt" -Pattern 'secure screen error|secure screen not available' -ErrorAction SilentlyContinue)
Check "no secure screen errors" $errors.Count ($errors.Count -eq 0)

$approvalsText = Get-Content "$t\.zombie_buddy\mod_approvals.json" -Raw
Check "approval for the real jar" "" ($approvalsText -match (Sha $verified))
Check "no approval for the changed copy" "" (-not ($approvalsText -match (Sha $changed)))
Check "real index downloaded" "" (Test-Path "$t\.bitecheck\cache\index.toml")
Check "game still running" "" ([bool](GameProcess))
# the mouse: Lua cannot move the cursor while the screen is open (Mouse.setXY patched, and accepted by the JVM)
$hooked = Select-String -Path "$t\game-out.txt" -Pattern 'hooks in zombie.input.Mouse' -Quiet
$refused = Select-String -Path "$t\game-out.txt" -Pattern 'VerifyError|could not patch zombie.input.Mouse' -Quiet
Check "Mouse.setXY is blocked while the screen is open" "" ($hooked -and -not $refused)

Log "Jumpstart log lines:"
Select-String -Path "$t\game-out.txt" -Pattern 'Jumpstart|hotswap:' | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"

New-Item -ItemType File -Force "$mock\stop" | Out-Null

if (-not $KeepRunning) {
    Lua 'getCore():quit() return 1' | Out-Null
    Start-Sleep 15
    GameProcess | Stop-Process -Force
}
exit $failures
