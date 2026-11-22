<#
.SYNOPSIS
  Builds the test kit zip for friends: the ZombieBuddy fork jar, the Jumpstart mod folder, the
  install scripts for Windows, macOS and Linux and the test plan (kit\TESTPLAN.md).
  Run after `gradle :mod:modFolder` here and `shadowJar` in the fork.
#>
param(
    [string]$ForkJar = (Join-Path $PSScriptRoot "..\..\zombiebuddy-jumpstart\java\build\jdk25\libs\zombiebuddy-jumpstart.jar"),
    [string]$ModFolder = (Join-Path $PSScriptRoot "..\mod\build\mod\Jumpstart"),
    [string]$Out = (Join-Path $PSScriptRoot "..\build\jumpstart-test-kit.zip")
)
$ErrorActionPreference = "Stop"
$stage = Join-Path ([IO.Path]::GetTempPath()) "jumpstart-test-kit"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $PSScriptRoot "..\kit\*") $stage -Recurse
Copy-Item (Resolve-Path $ForkJar) (Join-Path $stage "ZombieBuddy.jar")
Copy-Item (Resolve-Path $ModFolder) (Join-Path $stage "Jumpstart") -Recurse
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
if (Test-Path $Out) { Remove-Item $Out }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $Out)
Get-ChildItem $stage -Recurse -File | ForEach-Object { "{0,10}  {1}" -f $_.Length, $_.FullName.Substring($stage.Length + 1) }
"kit: $Out"
