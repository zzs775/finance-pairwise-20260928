# Minimal cross-platform-friendly CLI smoke test (PowerShell).
$ErrorActionPreference = "Stop"
$cli = Join-Path $PSScriptRoot "..\build\rebalancer_cli.exe"
if (-not (Test-Path $cli)) {
    $cli = Join-Path $PSScriptRoot "../build/rebalancer_cli"
}
$fixture = Join-Path $PSScriptRoot "..\examples\two_assets.txt"
$output = & $cli $fixture
$joined = ($output -join "`n")
if ($joined -notmatch "status proved_optimal") {
    Write-Error "expected proved_optimal:`n$output"
    exit 1
}
if ($joined -notmatch "(?m)^proven 1") {
    Write-Error "expected proven 1"
    exit 1
}

$budgeted = Get-Content $fixture
$budgeted += "window 10"
$budgeted += "budget 3"
$out2 = $budgeted | & $cli
$joined2 = ($out2 -join "`n")
if ($joined2 -notmatch "status budget_exhausted") {
    Write-Error "expected budget_exhausted:`n$joined2"
    exit 1
}
if ($joined2 -match "(?m)^proven 1") {
    Write-Error "budget run must not claim proven"
    exit 1
}
Write-Output "cli e2e ok"
