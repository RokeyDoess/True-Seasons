param(
    [string]$ServerDirectory = (Join-Path $PSScriptRoot '..\test server'),
    [string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$taskServer = (Resolve-Path -LiteralPath $ServerDirectory).Path
$taskBuild = Join-Path $PSScriptRoot 'build'
$taskClasses = Join-Path $taskBuild 'classes'
$taskTests = Join-Path $taskBuild 'test-classes'
foreach ($taskOutput in @($taskClasses,$taskTests)) {
    $taskResolvedOutput = [System.IO.Path]::GetFullPath($taskOutput)
    if (-not $taskResolvedOutput.StartsWith([System.IO.Path]::GetFullPath($taskBuild) + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Build output escaped build directory' }
    if (Test-Path -LiteralPath $taskResolvedOutput) { Remove-Item -LiteralPath $taskResolvedOutput -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $taskClasses,$taskTests,(Join-Path $taskBuild 'libs') | Out-Null
$taskLibs = @(Get-ChildItem -LiteralPath (Join-Path $taskServer 'libraries') -Recurse -Filter '*.jar' | Where-Object {
    ($_.Name -notlike 'paper-api-*' -or $_.Name -eq 'paper-api-26.3.build.177-stable.jar') -and $_.Name -notlike 'adventure-*-4.*.jar'
} | ForEach-Object FullName)
$taskLibs += @(Get-ChildItem -LiteralPath (Join-Path $taskServer 'plugins') -Filter 'bluemap-*.jar' | ForEach-Object FullName)
$taskClasspath = $taskLibs -join ';'
function Write-JavaArgs([string]$Path, [string[]]$Values) {
    $taskLines = $Values | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }
    [System.IO.File]::WriteAllLines($Path, $taskLines, [System.Text.UTF8Encoding]::new($false))
}
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$taskArgs = Join-Path $taskBuild 'compile.args'
Write-JavaArgs $taskArgs (@('--release','25','-encoding','UTF-8','-classpath',$taskClasspath,'-d',$taskClasses) + $taskSources)
& (Join-Path $JdkDirectory 'bin/javac.exe') "@$taskArgs"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item -Path (Join-Path $PSScriptRoot 'src/main/resources/*') -Destination $taskClasses -Recurse -Force
$taskTestSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
Write-JavaArgs $taskArgs (@('--release','25','-encoding','UTF-8','-classpath',"$taskClasses;$taskClasspath",'-d',$taskTests) + $taskTestSources)
& (Join-Path $JdkDirectory 'bin/javac.exe') "@$taskArgs"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& (Join-Path $JdkDirectory 'bin/java.exe') '-cp' "$taskTests;$taskClasses;$taskClasspath" 'dev.trueseasons.CoreTests'
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
foreach ($taskTestMain in @('dev.trueseasons.migration.MigrationBookkeepingTests','dev.trueseasons.migration.WinterCoveragePolicyTests','dev.trueseasons.command.CommandPresentationTests','dev.trueseasons.snow.SnowReplenishmentTests','dev.trueseasons.migration.GeneratedChunkSnapshotTests','dev.trueseasons.SnowCompletionTests','dev.trueseasons.integration.BlueMapReliabilityTests','dev.trueseasons.integration.BlueMapRecoveryTests','dev.trueseasons.integration.BlueMapReceiptTests','dev.trueseasons.integration.BlueMapJournalTests')) {
    & (Join-Path $JdkDirectory 'bin/java.exe') '-cp' "$taskTests;$taskClasses;$taskClasspath" $taskTestMain
    if ($LASTEXITCODE -ne 0) { throw "$taskTestMain failed" }
}
$taskNms = Join-Path $taskServer 'versions/26.3/paper-26.3.jar'
& (Join-Path $JdkDirectory 'bin/java.exe') '-cp' "$taskTests;$taskClasses;$taskNms;$taskClasspath" 'dev.trueseasons.presentation.NativeSnowPacketTests'
if ($LASTEXITCODE -ne 0) { throw 'Native snow packet tests failed' }
$taskJar = Join-Path $taskBuild 'libs/TrueSeasons-0.1.4.jar'
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' $taskJar '-C' $taskClasses '.'
if ($LASTEXITCODE -ne 0) { throw 'Jar packaging failed' }
if ($Install) {
    foreach ($taskOldJar in Get-ChildItem -LiteralPath (Join-Path $taskServer 'plugins') -Filter 'TrueSeasons-*.jar') {
        if ($taskOldJar.Name -ne 'TrueSeasons-0.1.4.jar') { Move-Item -LiteralPath $taskOldJar.FullName -Destination "$($taskOldJar.FullName).disabled" -Force }
    }
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskServer 'plugins/TrueSeasons-0.1.4.jar') -Force
}
Write-Output "Built and verified: $taskJar"
