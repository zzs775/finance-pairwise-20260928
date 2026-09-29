# Compiles everything and runs the zero-dependency test suite (JDK 21).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
java -cp (Join-Path $root "out") loanmonitor.test.TestRunner
