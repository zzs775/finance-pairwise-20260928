$ErrorActionPreference = "Stop"
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force out | Out-Null
[IO.File]::WriteAllLines((Join-Path (Get-Location) "sources.txt"),
    (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }))
javac -encoding UTF-8 -d out '@sources.txt'
Write-Output "BUILD OK"
