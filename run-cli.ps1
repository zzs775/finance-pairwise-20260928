#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root build.ps1)
java -cp (Join-Path $root out) csm.cli.CommandLine
