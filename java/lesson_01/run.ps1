param(
    [string]$Question = '查询订单 123',
    [switch]$Repeat,
    [switch]$ShowHelp
)

$ErrorActionPreference = 'Stop'
$lessonBuildPath = Join-Path $PSScriptRoot '.build'
$lessonStampPath = Join-Path $lessonBuildPath 'compiled.stamp'
$lessonMainClass = Join-Path $lessonBuildPath 'Main.class'
$lessonSources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File)
$lessonCompileRequired = $true

if ((Test-Path -LiteralPath $lessonStampPath) -and (Test-Path -LiteralPath $lessonMainClass)) {
    $lessonStamp = (Get-Item -LiteralPath $lessonStampPath).LastWriteTimeUtc
    $lessonCompileRequired = @($lessonSources | Where-Object { $_.LastWriteTimeUtc -gt $lessonStamp }).Count -gt 0
}

if ($lessonCompileRequired) {
    New-Item -ItemType Directory -Path $lessonBuildPath -Force | Out-Null
    $lessonSourcePaths = @($lessonSources.FullName)
    & javac -encoding UTF-8 -d $lessonBuildPath @lessonSourcePaths
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Set-Content -LiteralPath $lessonStampPath -Value 'compiled' -Encoding UTF8
}

$lessonArguments = @()
if ($ShowHelp) {
    $lessonArguments += '--help'
} else {
    if ($Repeat) { $lessonArguments += '--repeat' }
    $lessonArguments += $Question
}

& java '-Dfile.encoding=UTF-8' -cp $lessonBuildPath Main @lessonArguments
exit $LASTEXITCODE
