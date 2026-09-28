$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root "src"), (Join-Path $root "test") | ForEach-Object { $_.FullName }
javac -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Write-Host "BUILD OK"
