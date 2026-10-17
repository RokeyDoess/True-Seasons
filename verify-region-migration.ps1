param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1', [switch]$SkipBuild, [switch]$Resume, [switch]$Reuse,
    [ValidatePattern('^[a-zA-Z0-9._-]+$')][string]$RunName='region-verification-0.2.2')
$ErrorActionPreference = 'Stop'
$taskSourceCandidate = Join-Path $PSScriptRoot 'run/verification'
if (-not (Test-Path -LiteralPath $taskSourceCandidate)) { $taskSourceCandidate = Join-Path $PSScriptRoot '../test server' }
$taskSource = (Resolve-Path -LiteralPath $taskSourceCandidate).Path
$taskRuntime = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../test server')).Path
$taskRun = [System.IO.Path]::GetFullPath((Join-Path (Join-Path $PSScriptRoot 'run') $RunName))
$taskRunRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $taskRun.StartsWith($taskRunRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid verification path' }
if ((Test-Path -LiteralPath $taskRun) -and -not ($Resume -or $Reuse)) { throw 'Preserve previous region verification before running again' }
if ($Reuse -and ($Resume -or -not (Test-Path -LiteralPath (Join-Path $taskRun 'region-verification-clone.txt')) -or (Test-Path -LiteralPath (Join-Path $taskRun 'region-probe-phase.txt')))) { throw 'Reuse requires a stopped startup-only clone with no partial verification checkpoint' }
if ($Resume -and -not (Test-Path -LiteralPath (Join-Path $taskRun 'region-verification-clone.txt'))) { throw 'Existing controlled clone required for resume' }
if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Verification port is in use' }
$taskPidFile=Join-Path $taskRun 'server.pid'
if (Test-Path -LiteralPath $taskPidFile) {
    $taskPidText=[IO.File]::ReadAllText($taskPidFile).Trim()
    if ($taskPidText -match '^\d+$') {
        $taskProcessCheck=Get-Process -Id ([int]$taskPidText) -ErrorAction SilentlyContinue
        if ($taskProcessCheck -and $taskProcessCheck.ProcessName -eq 'java') { throw 'Verification server process is still running or saving' }
    }
}
$taskSourceProperties = Join-Path $taskSource 'server.properties'
if (-not $Resume -and (Test-Path -LiteralPath $taskSourceProperties)) {
    $taskPort = [regex]::Match([System.IO.File]::ReadAllText($taskSourceProperties),'(?m)^server-port=(\d+)')
    if ($taskPort.Success -and (Get-NetTCPConnection -LocalPort ([int]$taskPort.Groups[1].Value) -State Listen -ErrorAction SilentlyContinue)) { throw 'Stop the source server before copying its world' }
}
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JdkDirectory $JdkDirectory }
if (-not ($Resume -or $Reuse)) {
New-Item -ItemType Directory -Path $taskRun,(Join-Path $taskRun 'plugins') | Out-Null
foreach ($taskName in @('world','config','bluemap','plugins')) {
    Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination $taskRun -Recurse -Force
}
foreach ($taskName in @('libraries','cache','versions')) { Copy-Item -LiteralPath (Join-Path $taskRuntime $taskName) -Destination $taskRun -Recurse }
Copy-Item -LiteralPath (Join-Path $taskRuntime 'paper.jar') -Destination $taskRun
foreach ($taskName in @('eula.txt','bukkit.yml','spigot.yml')) { Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination $taskRun }
}
$taskPlugins = [System.IO.Path]::GetFullPath((Join-Path $taskRun 'plugins'))
foreach ($taskJar in Get-ChildItem -LiteralPath $taskPlugins -Filter 'TrueSeasons*.jar') {
    if ($taskJar.DirectoryName -ne $taskPlugins) { throw 'Probe jar outside clone' }
    Rename-Item -LiteralPath $taskJar.FullName -NewName ($taskJar.Name + '.disabled.' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../test server/plugins/bluemap-5.29-paper.jar') -Destination $taskPlugins -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.2.2.jar') -Destination $taskPlugins
$taskProbeBuild = Join-Path $PSScriptRoot 'build/region-probe'
New-Item -ItemType Directory -Path $taskProbeBuild -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $taskProbeBuild 'plugin.yml'), @'
name: TrueSeasonsRegionVerification
version: 1.0
main: dev.trueseasons.migration.RegionMigrationRuntimeProbe
api-version: '26.3'
depend: [TrueSeasons, BlueMap]
'@)
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskPlugins 'TrueSeasonsRegionVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' $taskProbeBuild 'plugin.yml'
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
[System.IO.File]::WriteAllText((Join-Path $taskRun 'region-verification-clone.txt'), 'Disposable region-order verification; copied completed old verification, original untouched.')
[System.IO.File]::WriteAllText((Join-Path $taskRun 'server.properties'), @'
server-ip=127.0.0.1
server-port=25585
online-mode=false
level-name=world
view-distance=2
simulation-distance=2
enable-rcon=false
enable-query=false
max-players=1
white-list=true
enforce-whitelist=true
'@)
[System.IO.File]::WriteAllText((Join-Path $taskRun 'whitelist.json'),'[]')
$taskSettings = Join-Path $taskPlugins 'TrueSeasons'
Copy-Item -Path (Join-Path $PSScriptRoot 'src/main/resources/*.yml') -Destination $taskSettings -Force
$taskConfig = Join-Path $taskSettings 'config.yml'
$taskText = [System.IO.File]::ReadAllText($taskConfig).Replace('max-concurrent-chunks: 2','max-concurrent-chunks: 4').Replace('max-chunks-per-tick: 1','max-chunks-per-tick: 4').Replace('pause-below-tps: 18.5','pause-below-tps: 0.0')
[System.IO.File]::WriteAllText($taskConfig,$taskText)
$taskIntegration = Join-Path $taskSettings 'integrations.yml'
[System.IO.File]::WriteAllText($taskIntegration,[System.IO.File]::ReadAllText($taskIntegration).Replace('flush-seconds: 30','flush-seconds: 5'))
$taskWeb = Join-Path $taskPlugins 'BlueMap/webserver.conf'
[System.IO.File]::WriteAllText($taskWeb,[System.IO.File]::ReadAllText($taskWeb).Replace('enabled: true','enabled: false'))
foreach ($taskCycle in $(if ($Resume) { @(3) } else { @(1,2) })) {
    $taskStart = [System.Diagnostics.ProcessStartInfo]::new()
    $taskStart.FileName = Join-Path $JdkDirectory 'bin/java.exe'; $taskStart.WorkingDirectory = $taskRun
    $taskStart.UseShellExecute = $false; $taskStart.CreateNoWindow = $true
    $taskStart.RedirectStandardOutput = $true; $taskStart.RedirectStandardError = $true; $taskStart.RedirectStandardInput = $true
    foreach ($taskArgument in @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','nogui')) { $taskStart.ArgumentList.Add($taskArgument) }
    $taskProcess = [System.Diagnostics.Process]::new(); $taskProcess.StartInfo = $taskStart; [void]$taskProcess.Start()
    [System.IO.File]::WriteAllText((Join-Path $taskRun 'server.pid'),[string]$taskProcess.Id)
    $taskOut = $taskProcess.StandardOutput.ReadToEndAsync(); $taskErr = $taskProcess.StandardError.ReadToEndAsync()
    $taskDeadline = [DateTime]::UtcNow.AddMinutes(22)
    while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 500 }
    if (-not $taskProcess.HasExited) { $taskProcess.StandardInput.WriteLine('stop'); if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true) }; throw 'Region verification timed out' }
    $taskProcess.WaitForExit(); $taskLog = $taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult()
    [System.IO.File]::WriteAllText((Join-Path $taskRun "cycle-$taskCycle.log"),$taskLog)
    if ($taskLog.Contains('PROBE_REGION_FAILED') -or -not $taskLog.Contains($(if ($taskCycle -eq 1) { 'PROBE_REGION_RESTART_READY' } else { 'PROBE_REGION_ALL_PASSED' }))) { throw "Region verification failed in cycle $taskCycle; inspect clone logs" }
    Write-Output "Region verification cycle $taskCycle passed."
}
Write-Output ([System.IO.File]::ReadAllText((Join-Path $taskRun 'region-verification-result.txt')))
