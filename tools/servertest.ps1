<#
.SYNOPSIS
  Starts a dedicated Project Zomboid server with the ZombieBuddy fork and Jumpstart in an isolated
  folder and checks that Jumpstart's multiplayer side comes up: the server loads the shared
  Jumpstart.jar, patches its command channel and tick, and reports its Java mods.

.DESCRIPTION
  Nothing of your own setup is touched: own -cachedir (<TestDir>), own ZombieBuddy config, own
  BiteCheck folder, -nosteam. The ZombieBuddy mod folder is copied from your Workshop subscription.
  Results: <TestDir>\servertest-result.txt; exit code = failed checks.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$ForkJar = (Join-Path $PSScriptRoot "..\..\zombiebuddy-jumpstart\java\build\jdk25\libs\zombiebuddy-jumpstart.jar"),
    [string]$ModFolder = (Join-Path $PSScriptRoot "..\mod\build\mod\Jumpstart"),
    [string]$ZombieBuddyMod = "C:\Program Files (x86)\Steam\steamapps\workshop\content\108600\3619862853\mods\ZombieBuddy",
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-server-test",
    [int]$Minutes = 6
)
$ErrorActionPreference = "Stop"
$t = $TestDir
$result = "$t\servertest-result.txt"
function Log($line) { $line | Tee-Object -FilePath $result -Append }
function Check($name, $ok, $detail = "") {
    Log ("{0,-4} {1} {2}" -f ($(if ($ok) { "OK" } else { "FAIL" })), $name, $detail)
    if (-not $ok) { $script:failures++ }
}
function Sha($path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

New-Item -ItemType Directory -Force "$t\mods", "$t\Server", "$t\.zombie_buddy", "$t\.bitecheck" | Out-Null
Remove-Item $result, "$t\server-out.txt" -ErrorAction SilentlyContinue
foreach ($m in "Jumpstart", "ZombieBuddy") { if (Test-Path "$t\mods\$m") { Remove-Item "$t\mods\$m" -Recurse -Force } }
Copy-Item (Resolve-Path $ModFolder) "$t\mods\Jumpstart" -Recurse
Copy-Item $ZombieBuddyMod "$t\mods\ZombieBuddy" -Recurse
if (-not (Test-Path "$t\mods\ZBHelloWorld")) { git clone -q --depth 1 https://github.com/zed-0xff/ZBHelloWorld "$t\mods\ZBHelloWorld" }
Copy-Item (Resolve-Path $ForkJar) "$t\ZombieBuddy-fork.jar" -Force

# the server never asks: approve Jumpstart and ZBHelloWorld (verified) in the test config
$approvals = '{"formatVersion": 2, "mods": [' +
    '{"id": "Jumpstart", "jar_hash": "' + (Sha "$t\mods\Jumpstart\42\media\java\Jumpstart.jar") + '", "decision": true},' +
    '{"id": "ZBHelloWorld", "jar_hash": "' + (Sha "$t\mods\ZBHelloWorld\42\media\java\client\ZBHelloWorld.jar") + '", "decision": true}]}'
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", $approvals)
[IO.File]::WriteAllText("$t\Server\jstest.ini", "Mods=ZombieBuddy;Jumpstart;ZBHelloWorld`r`nWorkshopItems=`r`nMap=Muldraugh, KY`r`nPublic=false`r`nOpen=true`r`n")

$agent = "-javaagent:$t\ZombieBuddy-fork.jar=hotswap=true,policy=deny-new,config_dir=$t\.zombie_buddy"
$cmd = "@echo off`r`ncd /d `"$GameDir`"`r`nSET _JAVA_OPTIONS=`r`n`".\jre64\bin\java.exe`" $agent -Djumpstart.bitecheckDir=$t\.bitecheck --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:+UseZGC -XX:-CreateCoredumpOnCrash -Xmx3072m -Djava.awt.headless=true -Djava.library.path=./natives/;./natives/win64/;./ -cp ./;projectzomboid.jar zombie.network.GameServer -cachedir=$t -servername jstest -adminpassword jstest -nosteam > `"$t\server-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-server.cmd", $cmd)

$failures = 0
Log "servertest $(Get-Date -Format s)"
$proc = Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-server.cmd`"" -WindowStyle Hidden -PassThru
$deadline = (Get-Date).AddMinutes($Minutes)
$want = 'multiplayer: server side ready', 'hooks in zombie.network.GameServer', 'hooks in zombie.network.ServerMap'
do {
    Start-Sleep 5
    $out = if (Test-Path "$t\server-out.txt") { Get-Content "$t\server-out.txt" -Raw } else { "" }
    $all = $true; foreach ($w in $want) { if ($out -notmatch [regex]::Escape($w)) { $all = $false } }
} until ($all -or (Get-Date) -gt $deadline -or $out -match 'SERVER STARTED|Exception in thread "main"')
Start-Sleep 10
$out = Get-Content "$t\server-out.txt" -Raw
Check "ZombieBuddy fork active on the server" ($out -match 'Jumpstart fork')
Check "Jumpstart loaded on the server (shared jar)" ($out -match 'io.github.jumpstartpz.mod')
foreach ($w in $want) { Check $w ($out -match [regex]::Escape($w)) }
Check "the client-only ZBHelloWorld is in the server's list for clients" ($out -match 'Java mods for the clients: \[ZBHelloWorld\]')
Check "no multiplayer errors" (-not ($out -match 'multiplayer server side not available|server tick failed|server command failed'))
Log "server lines:"
Select-String -Path "$t\server-out.txt" -Pattern 'Jumpstart|hotswap|SERVER STARTED' | Select-Object -First 25 | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'zombie.network.GameServer' -and $_.CommandLine -match [regex]::Escape($t) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
exit $failures
