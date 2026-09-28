param(
    [switch]$Test
)
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "target\classes"
$testOut = Join-Path $root "target\test-classes"
New-Item -ItemType Directory -Force -Path $out, $testOut | Out-Null
$mainFiles = Get-ChildItem -Recurse (Join-Path $root "src\main\java") -Filter *.java | ForEach-Object FullName
javac -d $out $mainFiles
if ($LASTEXITCODE -ne 0) { exit 1 }
if ($Test) {
    $testFiles = Get-ChildItem -Recurse (Join-Path $root "src\test\java") -Filter *.java | ForEach-Object FullName
    javac -cp $out -d $testOut $testFiles
    if ($LASTEXITCODE -ne 0) { exit 1 }
    java -cp "$out;$testOut" fix.recovery.test.TestRunner
}
