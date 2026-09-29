# Zero-dependency build for the loan covenant monitoring engine (JDK 21).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "out"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root "src") |
    ForEach-Object { $_.FullName }
javac -d $out $sources
$tests = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root "test") |
    ForEach-Object { $_.FullName }
javac -cp $out -d $out $tests
Write-Host "BUILD OK -> $out"
