#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (Test-Path out) {
    Remove-Item -Recurse -Force out
}
New-Item -ItemType Directory -Path out | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java src, test |
    ForEach-Object { $_.FullName }

javac -encoding UTF-8 -d out $sources
if ($LASTEXITCODE -ne 0) {
    throw "compilation failed"
}

Write-Host "BUILD OK"
