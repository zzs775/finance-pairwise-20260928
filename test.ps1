$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "out"
& (Join-Path $root "build.ps1")
$testFiles = Get-ChildItem -Recurse -Path (Join-Path $root "src\test\java") -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp $out -d $out $testFiles
if ($LASTEXITCODE -ne 0) { exit 1 }
java -cp $out loanengine.AllTests
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Output "TESTS OK"
