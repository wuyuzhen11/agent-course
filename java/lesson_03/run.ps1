param(
    [string]$Question = 'Query order 123',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$lessonRootPath = $PSScriptRoot
$lessonTargetPath = Join-Path $lessonRootPath 'target'
$lessonClasspathPath = Join-Path $lessonTargetPath 'classpath.txt'
$lessonMainClassPath = Join-Path $lessonTargetPath 'classes\Main.class'
$lessonStampPath = Join-Path $lessonTargetPath 'compiled.stamp'
$lessonInputs = @(
    (Get-Item -LiteralPath (Join-Path $lessonRootPath 'pom.xml')),
    @(Get-ChildItem -LiteralPath $lessonRootPath -Filter '*.java' -File |
        ForEach-Object { Get-Item -LiteralPath $_.FullName })
)

$lessonStampTime = [datetime]::MinValue
if ((Test-Path -LiteralPath $lessonClasspathPath) -and
    (Test-Path -LiteralPath $lessonMainClassPath) -and
    (Test-Path -LiteralPath $lessonStampPath)) {
    $lessonStampTime = (Get-Item -LiteralPath $lessonStampPath).LastWriteTimeUtc
}

$lessonBuildRequired = @(
    $lessonInputs |
        Where-Object { $_.LastWriteTimeUtc -gt $lessonStampTime }
).Count -gt 0

if ($lessonBuildRequired -or
    -not (Test-Path -LiteralPath $lessonClasspathPath)) {
    $detectedJavaHomeLine = java -XshowSettings:properties -version 2>&1 |
        Where-Object { $_ -match '^\s+java\.home = (.+)$' } |
        Select-Object -First 1

    if (-not $detectedJavaHomeLine -or
        $detectedJavaHomeLine -notmatch '^\s+java\.home = (.+)$' -or
        -not (Test-Path -LiteralPath (Join-Path $Matches[1].Trim() 'bin\javac.exe'))) {
        throw 'JDK 17 with javac was not found. Install it or update PATH.'
    }

    $env:JAVA_HOME = $Matches[1].Trim()
    & mvn -q -f (Join-Path $lessonRootPath 'pom.xml') `
        dependency:build-classpath compile `
        "-Dmdep.outputFile=$lessonClasspathPath"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    Set-Content -LiteralPath $lessonStampPath -Value 'compiled' -Encoding ascii
}

$lessonDependencyClasspath = (
    Get-Content -LiteralPath $lessonClasspathPath -Raw
).Trim()
$lessonRuntimeClasspath = "$(Join-Path $lessonTargetPath 'classes');$lessonDependencyClasspath"
$lessonArguments = @()
if ($DryRun) {
    $lessonArguments += '--dry-run'
}
$lessonArguments += $Question

& java '-Dfile.encoding=UTF-8' -cp $lessonRuntimeClasspath Main @lessonArguments
exit $LASTEXITCODE
