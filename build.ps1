param(
    [string]$ServerDirectory = (Join-Path $PSScriptRoot '..\test server'),
    [string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1',
    [switch]$Install,
    [string]$PacketEventsJar = (Join-Path $PSScriptRoot 'run/packetevents-study/dependencies/packetevents-spigot-2.14.0.jar')
)
$ErrorActionPreference = 'Stop'
if ($Install) { throw 'Experimental builds must be installed explicitly into a private test server.' }
$taskServer = (Resolve-Path -LiteralPath $ServerDirectory).Path
$taskBuild = Join-Path $PSScriptRoot 'build'
$taskClasses = Join-Path $taskBuild 'classes'
$taskTests = Join-Path $taskBuild 'test-classes'
foreach ($taskOutput in @($taskClasses,$taskTests)) {
    $taskResolvedOutput = [System.IO.Path]::GetFullPath($taskOutput)
    if (-not $taskResolvedOutput.StartsWith([System.IO.Path]::GetFullPath($taskBuild) + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Build output escaped build directory' }
    if (Test-Path -LiteralPath $taskResolvedOutput) { Remove-Item -LiteralPath $taskResolvedOutput -Recurse -Force }
}
$taskTestTemp = Join-Path $taskBuild 'test-temp'
New-Item -ItemType Directory -Force -Path $taskClasses,$taskTests,$taskTestTemp,(Join-Path $taskBuild 'libs') | Out-Null
$taskLibs = @(Get-ChildItem -LiteralPath (Join-Path $taskServer 'libraries') -Recurse -Filter '*.jar' | Where-Object {
    ($_.Name -notlike 'paper-api-*' -or $_.Name -eq 'paper-api-26.3.build.177-stable.jar') -and $_.Name -notlike 'adventure-*-4.*.jar'
} | ForEach-Object FullName)
$taskLibs += @(Get-ChildItem -LiteralPath (Join-Path $taskServer 'plugins') -Filter 'bluemap-*.jar' | ForEach-Object FullName)
if(-not(Test-Path -LiteralPath $PacketEventsJar)){throw 'Supply the PacketEvents 2.14.0 API jar with -PacketEventsJar to compile optional compatibility'}
$taskLibs += (Resolve-Path -LiteralPath $PacketEventsJar).Path
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
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskClasspath" 'dev.trueseasons.CoreTests'
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
foreach ($taskTestMain in @('dev.trueseasons.migration.MigrationBookkeepingTests','dev.trueseasons.migration.RegionReadinessTests','dev.trueseasons.migration.RegionOrderTests','dev.trueseasons.migration.IndexHydrationBudgetTests','dev.trueseasons.migration.RecentTickHeadroomTests','dev.trueseasons.migration.ChunkCoordinateHashTests','dev.trueseasons.climate.SeasonClimateTests','dev.trueseasons.snow.WinterGroundCoverTests','dev.trueseasons.snow.SnowColumnsCodecTests','dev.trueseasons.snow.GroundCoverCodecTests','dev.trueseasons.snow.SnowOwnershipSnapshotsTests','dev.trueseasons.snow.SnowPreviewPlanningTests','dev.trueseasons.snow.SnowmeltTests','dev.trueseasons.snow.PreviewSchedulingTests','dev.trueseasons.presentation.SnowPresentationBudgetTests','dev.trueseasons.weather.WinterStormRevealTests','dev.trueseasons.snow.LocalWinterSnowTests','dev.trueseasons.migration.WinterCoveragePolicyTests','dev.trueseasons.command.CommandPresentationTests','dev.trueseasons.notification.SeasonAnnouncementTests','dev.trueseasons.notification.MigrationProgressNotificationTests','dev.trueseasons.snow.SnowReplenishmentTests','dev.trueseasons.snow.SnowBreakCooldownTests','dev.trueseasons.migration.GeneratedChunkSnapshotTests','dev.trueseasons.SnowCompletionTests','dev.trueseasons.snow.SnowMigrationIntentTests','dev.trueseasons.integration.BlueMapReliabilityTests','dev.trueseasons.integration.BlueMapRegionTests','dev.trueseasons.integration.BlueMapRecoveryTests','dev.trueseasons.integration.BlueMapReceiptTests','dev.trueseasons.integration.BlueMapJournalTests','dev.trueseasons.integration.BlueMapSeasonalColorsTests','dev.trueseasons.integration.BlueMapVisualQueueTests','dev.trueseasons.visual.AutumnTreeColorsTests','dev.trueseasons.visual.SeasonColorTransitionTests','dev.trueseasons.ambience.FireflyTests')) {
    & (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskClasspath" $taskTestMain
    if ($LASTEXITCODE -ne 0) { throw "$taskTestMain failed" }
}
$taskNms = Join-Path $taskServer 'versions/26.3/paper-26.3.jar'
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskNms;$taskClasspath" 'dev.trueseasons.presentation.NativeSnowPacketTests'
if ($LASTEXITCODE -ne 0) { throw 'Native snow packet tests failed' }
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskNms;$taskClasspath" 'dev.trueseasons.presentation.AutumnLeafPacketTests'
if ($LASTEXITCODE -ne 0) { throw 'Autumn leaf packet tests failed' }
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskNms;$taskClasspath" 'dev.trueseasons.presentation.SnowPresentationPacketTests'
if ($LASTEXITCODE -ne 0) { throw 'Snow presentation packet tests failed' }
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskNms;$taskClasspath" 'dev.trueseasons.presentation.SeasonalPresentationPacketTests'
if ($LASTEXITCODE -ne 0) { throw 'Combined seasonal presentation packet tests failed' }
$taskJar = Join-Path $taskBuild 'libs/TrueSeasons-0.3.5-packetevents.2.jar'
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' $taskJar '-C' $taskClasses '.'
if ($LASTEXITCODE -ne 0) { throw 'Jar packaging failed' }
Write-Output "Built and verified: $taskJar"
