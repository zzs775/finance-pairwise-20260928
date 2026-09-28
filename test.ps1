#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
& (Join-Path $root build.ps1)
java -cp out loancashflow.tests.TestRunner
