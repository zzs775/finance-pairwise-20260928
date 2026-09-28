$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
java -cp (Join-Path $root "out") exer.Tests
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
