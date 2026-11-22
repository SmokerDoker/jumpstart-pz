<#
  Jumpstart in one step on Windows: downloads the newest test kit from GitHub, checks its SHA-256
  and installs it. In PowerShell:
    Game:              irm https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.ps1 | iex
    Dedicated server:  $env:JUMPSTART_SERVER = "C:\path\to\server"; irm https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.ps1 | iex
                       (optional: $env:JUMPSTART_SERVER_NAME = "servertest")
#>
$ErrorActionPreference = "Stop"
$repo = "SmokerDoker/jumpstart-pz"
$tag = if ($env:JUMPSTART_TAG) { $env:JUMPSTART_TAG } else { "test-kit" }
$server = $env:JUMPSTART_SERVER
$pattern = if ($server) { "jumpstart-server-" } else { "jumpstart-windows-" }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
"Jumpstart: looking for the newest kit ($tag) ..."
$release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/tags/$tag"
$asset = $release.assets | Where-Object { $_.name.StartsWith($pattern) } | Select-Object -First 1
$sums = $release.assets | Where-Object { $_.name -eq "SHA256SUMS.txt" } | Select-Object -First 1
if (-not $asset -or -not $sums) { throw "no $pattern package in the release $tag" }
$work = Join-Path ([IO.Path]::GetTempPath()) ("jumpstart-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory $work | Out-Null
try {
    "downloading $($asset.name)"
    $file = Join-Path $work $asset.name
    Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $file
    $expected = ((Invoke-WebRequest -UseBasicParsing $sums.browser_download_url).Content -split "`n" |
        Where-Object { $_ -match [regex]::Escape($asset.name) + '\s*$' } | Select-Object -First 1) -replace '\s.*$', ''
    $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
    if (-not $expected -or $expected.Trim().ToLower() -ne $actual) { throw "SHA-256 does not match: expected $expected, got $actual" }
    "SHA-256 ok: $actual"
    Expand-Archive $file (Join-Path $work "kit")
    $kit = (Get-ChildItem (Join-Path $work "kit") -Directory | Select-Object -First 1).FullName
    if ($server) {
        $serverArgs = @("-ServerDir", $server)
        if ($env:JUMPSTART_SERVER_NAME) { $serverArgs += @("-ServerName", $env:JUMPSTART_SERVER_NAME) }
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $kit "install-server.ps1") @serverArgs
    } else {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $kit "install-windows.ps1")
    }
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
