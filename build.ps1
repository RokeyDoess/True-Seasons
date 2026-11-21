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
$taskTestTemp = Join-Path $taskBuild 'test-temp'
New-Item -ItemType Directory -Force -Path $taskClasses,$taskTests,$taskTestTemp,(Join-Path $taskBuild 'libs') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskBootstrap = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskServer 'paper.jar'))
try {
    $taskReader = [IO.StreamReader]::new($taskBootstrap.GetEntry('META-INF/libraries.list').Open())
    try { $taskLibraryList = $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
} finally { $taskBootstrap.Dispose() }
$taskLibs = @($taskLibraryList -split "`n" | Where-Object { $_.Trim() } | ForEach-Object {
    $taskLibrary = Join-Path (Join-Path $taskServer 'libraries') (($_ -split "`t")[2].Trim())
    if (-not (Test-Path -LiteralPath $taskLibrary)) { throw "Missing Paper runtime library: $taskLibrary" }
    $taskLibrary
})
# Include plugin libraries without reviving older copies of Paper's own dependencies.
$taskRuntimeArtifacts = @($taskLibraryList -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { (($_ -split "`t")[1] -split ':')[1] })
$taskLibs += @(Get-ChildItem -LiteralPath (Join-Path $taskServer 'libraries') -Recurse -Filter '*.jar' | Where-Object {
    $_.Directory.Parent.Name -notin $taskRuntimeArtifacts
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
& (Join-Path $JdkDirectory 'bin/java.exe') "-Djava.io.tmpdir=$taskTestTemp" '-cp' "$taskTests;$taskClasses;$taskClasspath" 'dev.trueseasons.CoreTests'
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
foreach ($taskTestMain in @('dev.trueseasons.state.TerrainTargetTests','dev.trueseasons.migration.MigrationBookkeepingTests','dev.trueseasons.migration.RegionReadinessTests','dev.trueseasons.migration.RegionProgressTests','dev.trueseasons.migration.ChunkLoadLeaseTests','dev.trueseasons.migration.RegionOrderTests','dev.trueseasons.migration.IndexHydrationBudgetTests','dev.trueseasons.migration.RecentTickHeadroomTests','dev.trueseasons.migration.ChunkCoordinateHashTests','dev.trueseasons.config.WeatherDefaultsTests','dev.trueseasons.climate.SeasonClimateTests','dev.trueseasons.snow.WinterGroundCoverTests','dev.trueseasons.snow.SnowColumnsCodecTests','dev.trueseasons.snow.GroundCoverCodecTests','dev.trueseasons.presentation.ChunkPacketEditsTests','dev.trueseasons.snow.SnowOwnershipSnapshotsTests','dev.trueseasons.snow.SnowPreviewPlanningTests','dev.trueseasons.snow.SnowmeltTests','dev.trueseasons.snow.PreviewSchedulingTests','dev.trueseasons.snow.LocalSnowLifecycleTests','dev.trueseasons.presentation.SnowPresentationBudgetTests','dev.trueseasons.weather.WinterStormRevealTests','dev.trueseasons.snow.LocalWinterSnowTests','dev.trueseasons.migration.WinterCoveragePolicyTests','dev.trueseasons.command.CommandPresentationTests','dev.trueseasons.notification.SeasonAnnouncementTests','dev.trueseasons.notification.MigrationProgressNotificationTests','dev.trueseasons.snow.SnowReplenishmentTests','dev.trueseasons.snow.SnowBreakCooldownTests','dev.trueseasons.migration.GeneratedChunkSnapshotTests','dev.trueseasons.migration.WinterExemptChunkTests','dev.trueseasons.SnowCompletionTests','dev.trueseasons.snow.SnowMigrationIntentTests','dev.trueseasons.integration.BlueMapReliabilityTests','dev.trueseasons.integration.BlueMapRegionTests','dev.trueseasons.integration.BlueMapPurgeTests','dev.trueseasons.integration.BlueMapRecoveryTests','dev.trueseasons.integration.BlueMapReceiptTests','dev.trueseasons.integration.BlueMapJournalTests','dev.trueseasons.integration.BlueMapSeasonalColorsTests','dev.trueseasons.integration.BlueMapVisualQueueTests','dev.trueseasons.visual.AutumnTreeColorsTests','dev.trueseasons.visual.SeasonColorTransitionTests')) {
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
$taskJar = Join-Path $taskBuild 'libs/TrueSeasons-1.0.0.jar'
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' $taskJar '-C' $taskClasses '.'
if ($LASTEXITCODE -ne 0) { throw 'Jar packaging failed' }
if ($Install) {
    foreach ($taskOldJar in Get-ChildItem -LiteralPath (Join-Path $taskServer 'plugins') -Filter 'TrueSeasons-*.jar') {
        if ($taskOldJar.Name -ne 'TrueSeasons-1.0.0.jar') { Move-Item -LiteralPath $taskOldJar.FullName -Destination "$($taskOldJar.FullName).disabled" -Force }
    }
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskServer 'plugins/TrueSeasons-1.0.0.jar') -Force
}
Write-Output "Built and verified: $taskJar"
