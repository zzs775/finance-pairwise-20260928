$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

New-Item -ItemType Directory -Force -Path "build/classes", "build/test-classes" | Out-Null

$mainSources = Get-ChildItem -Recurse -Filter *.java -Path "src/main" | ForEach-Object { $_.FullName }
$testSources = Get-ChildItem -Recurse -Filter *.java -Path "src/test" | ForEach-Object { $_.FullName }

Write-Host "compiling main sources..."
javac -d "build/classes" $mainSources
Write-Host "compiling test sources..."
javac -cp "build/classes" -d "build/test-classes" $testSources
Write-Host "build OK"
