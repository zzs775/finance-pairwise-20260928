$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
java -cp "$root/build/classes;$root/build/test-classes" csm.TestRunner
