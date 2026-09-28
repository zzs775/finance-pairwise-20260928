param(
    [switch]$Clean
)
# JDK-only build and test script (no external dependencies).
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$out = Join-Path $root "out"

if ($Clean -and (Test-Path -LiteralPath $out)) {
    Remove-Item -LiteralPath $out -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root "src"), `
    (Join-Path $root "test") | ForEach-Object FullName
Write-Output "Compiling $($sources.Count) Java files..."
javac -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) {
    throw "compilation failed"
}
Write-Output "Compilation succeeded."

if (-not $Clean) {
    # Default target also runs the test suite.
}
Write-Output "Running tests..."
java -cp $out fx.EngineTest
if ($LASTEXITCODE -ne 0) {
    throw "tests failed"
}
Write-Output "Build and tests OK."
