param(
    [string]$Question = '用一句话解释 Agent 的工具调用循环。',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$lessonRootPath = $PSScriptRoot
$lessonTargetPath = Join-Path $lessonRootPath 'target'
$lessonClasspathPath = Join-Path $lessonTargetPath 'classpath.txt'
$lessonMainClassPath = Join-Path $lessonTargetPath 'classes\com\example\agent\lesson02\Main.class'
$lessonInputs = @(
    (Get-Item -LiteralPath (Join-Path $lessonRootPath 'pom.xml')),
    @(Get-ChildItem -LiteralPath (Join-Path $lessonRootPath 'src\main\java') -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName } | ForEach-Object { Get-Item -LiteralPath $_ })
)
$lessonStampTime = [datetime]::MinValue
if ((Test-Path -LiteralPath $lessonClasspathPath) -and (Test-Path -LiteralPath $lessonMainClassPath)) {
    $lessonStampTime = (Get-Item -LiteralPath $lessonMainClassPath).LastWriteTimeUtc
}
$lessonBuildRequired = @($lessonInputs | Where-Object { $_.LastWriteTimeUtc -gt $lessonStampTime }).Count -gt 0
if ($lessonBuildRequired -or -not (Test-Path -LiteralPath $lessonClasspathPath)) {
    $detectedJavaHomeLine = java -XshowSettings:properties -version 2>&1 | Where-Object { $_ -match '^\s+java\.home = (.+)$' } | Select-Object -First 1
    if (-not $detectedJavaHomeLine -or $detectedJavaHomeLine -notmatch '^\s+java\.home = (.+)$' -or -not (Test-Path -LiteralPath (Join-Path $Matches[1].Trim() 'bin\javac.exe'))) {
        throw '未找到包含 javac 的 JDK 17；请先安装或调整 PATH。'
    }
    $env:JAVA_HOME = $Matches[1].Trim()
    & mvn -q -f (Join-Path $lessonRootPath 'pom.xml') dependency:build-classpath compile "-Dmdep.outputFile=$lessonClasspathPath"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$lessonDependencyClasspath = (Get-Content -LiteralPath $lessonClasspathPath -Raw).Trim()
$lessonRuntimeClasspath = "target\classes;$lessonDependencyClasspath"
$lessonArguments = @()
if ($DryRun) { $lessonArguments += '--dry-run' }
$lessonArguments += $Question
& java "-Dfile.encoding=UTF-8" -cp $lessonRuntimeClasspath com.example.agent.lesson02.Main @lessonArguments
exit $LASTEXITCODE

