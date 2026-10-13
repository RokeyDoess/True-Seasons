<#
Controlled completed-winter maintenance comparison, using an archived disposable clone.
PrepareHarness compiles and copies only; it never starts Java. Run PrepareCheckpoint,
then Capture, only after the main native/BlueMap verification servers are stopped.
The original server and archived clone remain untouched. Both capture arms start from
the identical flushed 0.1.6 winter snapshot, use the same 30-second rescan configuration,
and load the same 256 central FULL chunks; force-ticket halo chunks must already be FULL.
Spark's installed SamplerModule was inspected: --save-to-file bypasses the Bytebin POST
on timeout. All resulting profiles remain inside the local benchmark clone.
This isolates no-player snow maintenance without BlueMap, rather than a production load
or a migration benchmark. Random column selection and JVM scheduling remain variable;
the single old-then-new ordering is exploratory, not a statistically repeated A/B study.
Interpret Spark inclusive samples as wall time including waits, not CPU utilization.
#>
param(
    [ValidateSet('PrepareHarness','PrepareCheckpoint','Capture')][string]$Stage = 'PrepareHarness',
    [string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1',
    [string]$OldJar = (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.5.jar'),
    [string]$NewJar = (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.6.jar')
)
$ErrorActionPreference = 'Stop'
$taskSource = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'run/verification-0.1.5')).Path
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run/performance-verification'))
$taskRunRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $taskRoot.StartsWith($taskRunRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Benchmark must stay under project/run' }
$taskOldJar = (Resolve-Path -LiteralPath $OldJar).Path
$taskNewJar = (Resolve-Path -LiteralPath $NewJar).Path
$taskProbeBuild = Join-Path $PSScriptRoot 'build/snow-performance-probe'
$taskProbeJar = Join-Path $taskProbeBuild 'TrueSeasonsSnowPerformance.jar'
$taskCheckpoint = Join-Path $taskRoot 'checkpoint'

function Assert-Stopped([string]$Directory) {
    foreach ($taskPidFile in Get-ChildItem -LiteralPath $Directory -Filter '*.pid') {
        $taskPidText = [System.IO.File]::ReadAllText($taskPidFile.FullName).Trim()
        if ($taskPidText -match '^\d+$') {
            $taskRunning = Get-Process -Id ([int]$taskPidText) -ErrorAction SilentlyContinue
            if ($taskRunning -and $taskRunning.ProcessName -eq 'java') { throw "Server may still be running: $Directory" }
        }
    }
}
function Copy-Server([string]$Source, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination) { throw "Destination already exists: $Destination" }
    if (-not ([System.IO.Path]::GetFullPath($Destination)).StartsWith($taskRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Copy destination escaped benchmark directory'
    }
    New-Item -ItemType Directory -Path $Destination,(Join-Path $Destination 'plugins') | Out-Null
    foreach ($taskName in @('libraries','cache','versions','world','config')) {
        Copy-Item -LiteralPath (Join-Path $Source $taskName) -Destination $Destination -Recurse
    }
    foreach ($taskName in @('paper.jar','eula.txt','bukkit.yml','spigot.yml')) {
        Copy-Item -LiteralPath (Join-Path $Source $taskName) -Destination (Join-Path $Destination $taskName)
    }
    Copy-Item -LiteralPath (Join-Path $Source 'plugins/TrueSeasons') -Destination (Join-Path $Destination 'plugins') -Recurse
    [System.IO.File]::WriteAllText((Join-Path $Destination 'snow-performance-clone.txt'), 'Dedicated disposable completed-winter benchmark; no players; no BlueMap.')
    [System.IO.File]::WriteAllText((Join-Path $Destination 'server.properties'), @'
server-ip=127.0.0.1
server-port=25587
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
    [System.IO.File]::WriteAllText((Join-Path $Destination 'whitelist.json'), '[]')
    [System.IO.File]::WriteAllText((Join-Path $Destination 'ops.json'), '[]')
    Copy-Item -LiteralPath $taskProbeJar -Destination (Join-Path $Destination 'plugins')
}
function Run-Probe([string]$Directory, [string]$Mode, [string]$Jar) {
    Assert-Stopped $Directory
    Copy-Item -LiteralPath $Jar -Destination (Join-Path $Directory 'plugins/TrueSeasons.jar') -Force
    $taskStart = [System.Diagnostics.ProcessStartInfo]::new()
    $taskStart.FileName = Join-Path $JdkDirectory 'bin/java.exe'
    $taskStart.WorkingDirectory = $Directory
    $taskStart.UseShellExecute = $false
    $taskStart.CreateNoWindow = $true
    $taskStart.RedirectStandardOutput = $true
    $taskStart.RedirectStandardError = $true
    $taskStart.RedirectStandardInput = $true
    foreach ($taskArgument in @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false',"-Dtrueseasons.benchmark.mode=$Mode",'-jar','paper.jar','nogui')) {
        $taskStart.ArgumentList.Add($taskArgument)
    }
    $taskProcess = [System.Diagnostics.Process]::new(); $taskProcess.StartInfo = $taskStart
    [void]$taskProcess.Start()
    [System.IO.File]::WriteAllText((Join-Path $Directory 'benchmark.pid'), [string]$taskProcess.Id)
    $taskStdout = $taskProcess.StandardOutput.ReadToEndAsync(); $taskStderr = $taskProcess.StandardError.ReadToEndAsync()
    $taskDeadline = [DateTime]::UtcNow.AddMinutes(14)
    try {
        while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 500 }
        if (-not $taskProcess.HasExited) {
            $taskProcess.StandardInput.WriteLine('spark profiler cancel')
            $taskProcess.StandardInput.WriteLine('stop')
            if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true) }
            throw "Benchmark timed out: $Directory"
        }
    } finally {
        if (-not $taskProcess.HasExited) {
            $taskProcess.StandardInput.WriteLine('spark profiler cancel'); $taskProcess.StandardInput.WriteLine('stop')
            if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true) }
        }
        $taskProcess.WaitForExit()
        $taskText = $taskStdout.GetAwaiter().GetResult() + $taskStderr.GetAwaiter().GetResult()
        [System.IO.File]::WriteAllText((Join-Path $Directory 'benchmark.log'), $taskText)
    }
    if (-not $taskText.Contains('PROBE_SNOW_PERFORMANCE_PASSED') -or $taskText.Contains('PROBE_SNOW_PERFORMANCE_FAILED')) {
        throw "Benchmark probe failed; inspect $Directory/benchmark.log"
    }
    if ($taskText -match 'https://spark\.lucko\.me/') { throw 'Unexpected external Spark upload URL' }
    if ($Mode -eq 'capture' -and @(Get-ChildItem -LiteralPath $Directory -Recurse -Filter '*.sparkprofile').Count -ne 1) {
        throw 'Expected exactly one saved local Spark profile'
    }
    Write-Output ([System.IO.File]::ReadAllText((Join-Path $Directory 'snow-performance-result.txt')))
}

if ($Stage -eq 'PrepareHarness') {
    Assert-Stopped $taskSource
    if (Test-Path -LiteralPath $taskRoot) { throw 'Benchmark directory exists; preserve it and use the next stage.' }
    New-Item -ItemType Directory -Path $taskRoot,$taskProbeBuild,(Join-Path $taskProbeBuild 'classes') | Out-Null
    $taskLibraries = @(Get-ChildItem -LiteralPath (Join-Path $taskSource 'libraries') -Recurse -Filter '*.jar' | Where-Object {
        ($_.Name -notlike 'paper-api-*' -or $_.Name -eq 'paper-api-26.3.build.177-stable.jar') -and $_.Name -notlike 'adventure-*-4.*.jar'
    } | ForEach-Object FullName)
    $taskClasspath = (@($taskOldJar) + $taskLibraries) -join ';'
    $taskArguments = @('--release','25','-encoding','UTF-8','-classpath',$taskClasspath,'-d',(Join-Path $taskProbeBuild 'classes'),
        (Join-Path $PSScriptRoot 'src/test/java/dev/trueseasons/snow/SnowPerformanceRuntimeProbe.java'))
    $taskArgumentFile = Join-Path $taskProbeBuild 'compile.args'
    [System.IO.File]::WriteAllLines($taskArgumentFile, ($taskArguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }), [System.Text.UTF8Encoding]::new($false))
    & (Join-Path $JdkDirectory 'bin/javac.exe') "@$taskArgumentFile"
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark probe compilation against 0.1.5 API failed' }
    [System.IO.File]::WriteAllText((Join-Path $taskProbeBuild 'plugin.yml'), @'
name: TrueSeasonsSnowPerformance
version: 1.0
main: dev.trueseasons.snow.SnowPerformanceRuntimeProbe
api-version: '26.3'
depend: [TrueSeasons]
'@)
    & (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' $taskProbeJar '-C' (Join-Path $taskProbeBuild 'classes') '.' '-C' $taskProbeBuild 'plugin.yml'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark probe packaging failed' }
    Copy-Server $taskSource $taskCheckpoint
    $taskIntegration = Join-Path $taskCheckpoint 'plugins/TrueSeasons/integrations.yml'
    [System.IO.File]::WriteAllText($taskIntegration, [System.IO.File]::ReadAllText($taskIntegration).Replace('enabled: true','enabled: false'))
    $taskJobs = Join-Path $taskCheckpoint 'plugins/TrueSeasons/state/jobs.yml'
    $taskStateText = [System.IO.File]::ReadAllText($taskJobs)
    $taskStateText = [regex]::Replace($taskStateText, '(?m)^override:.*$', 'override: WINTER')
    $taskStateText = [regex]::Replace($taskStateText, '(?m)^cleanup:.*$', 'cleanup: false')
    $taskStateText = [regex]::Replace($taskStateText, '(?m)^clean:.*$', 'clean: false')
    $taskStateText = [regex]::Replace($taskStateText, '(?m)^paused:.*$', 'paused: false')
    $taskStateText = [regex]::Replace($taskStateText, '(?m)^revision:.*$', 'revision: 100')
    [System.IO.File]::WriteAllText($taskJobs, $taskStateText)
    Write-Output 'Harness prepared and compiled against 0.1.5. No server launched. Next: -Stage PrepareCheckpoint after other verification servers stop.'
    exit
}
if (-not (Test-Path -LiteralPath $taskProbeJar)) { throw 'Run -Stage PrepareHarness first' }
if ($Stage -eq 'PrepareCheckpoint') {
    if (Test-Path -LiteralPath (Join-Path $taskRoot 'checkpoint-ready.txt')) { throw 'Checkpoint already prepared; preserve it and run Capture.' }
    Run-Probe $taskCheckpoint 'prepare' $taskNewJar
    [System.IO.File]::WriteAllText((Join-Path $taskRoot 'checkpoint-ready.txt'), [DateTimeOffset]::UtcNow.ToString('O'))
    Write-Output 'Completed winter checkpoint ready. Next: -Stage Capture while other verification servers remain stopped.'
    exit
}
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'checkpoint-ready.txt'))) { throw 'Run -Stage PrepareCheckpoint first' }
Assert-Stopped $taskCheckpoint
foreach ($taskVersion in @('0.1.5','0.1.6')) {
    $taskArm = Join-Path $taskRoot $taskVersion
    Copy-Server $taskCheckpoint $taskArm
}
$taskManifest = foreach ($taskFile in Get-ChildItem -LiteralPath (Join-Path $taskCheckpoint 'world') -Recurse -File) {
    if ($taskFile.Name -eq 'session.lock') { continue }
    $taskRelative = [System.IO.Path]::GetRelativePath($taskCheckpoint, $taskFile.FullName)
    $taskHash = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash
    foreach ($taskVersion in @('0.1.5','0.1.6')) {
        if ((Get-FileHash -LiteralPath (Join-Path (Join-Path $taskRoot $taskVersion) $taskRelative) -Algorithm SHA256).Hash -ne $taskHash) {
            throw "Input snapshot mismatch for $taskVersion $taskRelative"
        }
    }
    [pscustomobject]@{ file=$taskRelative; sha256=$taskHash; bytes=$taskFile.Length }
}
$taskMetadata = [ordered]@{
    source='run/verification-0.1.5'; central_chunks=256; full_halo='-11..10 squared'; warmup_ticks=600; sample_seconds=60;
    spark_command='spark profiler start --timeout 60 --interval 4 --save-to-file'; bluemap=$false; players=0;
    old_jar_sha256=(Get-FileHash -LiteralPath $taskOldJar -Algorithm SHA256).Hash;
    new_jar_sha256=(Get-FileHash -LiteralPath $taskNewJar -Algorithm SHA256).Hash;
    checkpoint_files=$taskManifest
}
[System.IO.File]::WriteAllText((Join-Path $taskRoot 'benchmark-input.json'), ($taskMetadata | ConvertTo-Json -Depth 5))
Run-Probe (Join-Path $taskRoot '0.1.5') 'capture' $taskOldJar
Run-Probe (Join-Path $taskRoot '0.1.6') 'capture' $taskNewJar
$taskOldResult = [System.IO.File]::ReadAllText((Join-Path $taskRoot '0.1.5/snow-performance-result.txt'))
$taskNewResult = [System.IO.File]::ReadAllText((Join-Path $taskRoot '0.1.6/snow-performance-result.txt'))
$taskStartPattern = 'SAMPLING_START loaded=(\d+) central=256 ownership=([0-9a-f]+)'
$taskOldStart = [regex]::Match($taskOldResult, $taskStartPattern)
$taskNewStart = [regex]::Match($taskNewResult, $taskStartPattern)
if (-not $taskOldStart.Success -or -not $taskNewStart.Success -or $taskOldStart.Groups[1].Value -ne $taskNewStart.Groups[1].Value -or $taskOldStart.Groups[2].Value -ne $taskNewStart.Groups[2].Value) {
    throw 'Loaded chunk counts or physical/ownership snow inputs differed at sampling start; do not report this as a controlled comparison.'
}
Write-Output 'Both controlled local captures complete. Decode the profiles before interpreting results; this isolates no-player snow maintenance without BlueMap.'
