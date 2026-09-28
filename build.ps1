$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$mainFiles = Get-ChildItem -Recurse -Path (Join-Path $root "src\main\java") -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d $out $mainFiles
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Output "BUILD OK"
