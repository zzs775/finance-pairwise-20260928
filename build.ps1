param([switch]$Test)
$ErrorActionPreference = 'Stop'
if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory -Force out | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java -Path src,test | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d out $files
if ($Test) { java -cp out fi.Tests }
