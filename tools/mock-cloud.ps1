<#
.SYNOPSIS
  A stand-in for the Jumpstart cloud Worker (cloud/) on http://localhost:8787 for the in-game test:
  fixed library answers, and every usage report saved to <OutDir>\usage-<n>.txt.
  Stops when <OutDir>\stop exists or after 30 minutes.
#>
param([string]$OutDir = "$env:TEMP\jumpstart-mock-cloud", [int]$Port = 8787)
New-Item -ItemType Directory -Force $OutDir | Out-Null
Remove-Item "$OutDir\*" -Force -ErrorAction SilentlyContinue
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
"listening" | Set-Content "$OutDir\ready"
$java = "jumpstart-library 1`ntotal`t2`nnext`t`nitem`t900001`tMock Java Mod`t123`t1763700000`t1`tBuild 42`tA Java mod from the mock cloud`nitem`t900002`tMock Java Mod Two`t45`t1763700000`t1`tBuild 42`tAnother one`n"
$lua = "jumpstart-library 1`ntotal`t1`nnext`t`nitem`t900101`tMock Lua Mod`t999`t1763700000`t0`tBuild 42`tA Lua mod from the mock cloud`n"
$n = 0
$deadline = (Get-Date).AddMinutes(30)
while ((Get-Date) -lt $deadline -and -not (Test-Path "$OutDir\stop")) {
    $pending = $listener.BeginGetContext($null, $null)
    while (-not $pending.AsyncWaitHandle.WaitOne(500)) {
        if ((Test-Path "$OutDir\stop") -or (Get-Date) -gt $deadline) { $listener.Stop(); return }
    }
    $ctx = $listener.EndGetContext($pending)
    $path = $ctx.Request.Url.AbsolutePath
    $query = $ctx.Request.Url.Query
    Add-Content "$OutDir\requests.txt" "$($ctx.Request.HttpMethod) $path$query"
    $body = "not found`n"; $status = 404
    if ($path -eq "/v1/library") {
        $status = 200
        $body = if ($query -match "kind=java") { $java } else { $lua }
    } elseif ($path -eq "/v1/usage" -and $ctx.Request.HttpMethod -eq "POST") {
        $n++
        $reader = New-Object IO.StreamReader($ctx.Request.InputStream, [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText("$OutDir\usage-$n.txt", $reader.ReadToEnd())
        $status = 200; $body = "OK mock`n"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $ctx.Response.StatusCode = $status
    $ctx.Response.ContentType = "text/plain; charset=utf-8"
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
}
$listener.Stop()
