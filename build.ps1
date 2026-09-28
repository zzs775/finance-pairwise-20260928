#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -d out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Write-Host "BUILD OK"
