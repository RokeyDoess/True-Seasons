param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1')
$ErrorActionPreference = 'Stop'
$taskSource = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\test server')).Path
$taskRun = Join-Path $PSScriptRoot 'run/verification'
if (Test-Path -LiteralPath $taskRun) { throw 'Verification folder already exists. Rename it before starting a fresh verification.' }
& (Join-Path $PSScriptRoot 'build.ps1') -JdkDirectory $JdkDirectory
New-Item -ItemType Directory -Path $taskRun,(Join-Path $taskRun 'plugins') | Out-Null
foreach ($taskName in @('libraries','cache','versions','world','config','bluemap','plugins')) {
    Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination $taskRun -Recurse -Force
}
foreach ($taskName in @('paper.jar','eula.txt','bukkit.yml','spigot.yml')) {
    Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination (Join-Path $taskRun $taskName)
}
$taskWebConfig = Join-Path $taskRun 'plugins/BlueMap/webserver.conf'
if (Test-Path -LiteralPath $taskWebConfig) {
    [System.IO.File]::WriteAllText($taskWebConfig,[System.IO.File]::ReadAllText($taskWebConfig).Replace('enabled: true','enabled: false'))
}
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
'@)
foreach ($taskOldJar in Get-ChildItem -LiteralPath (Join-Path $taskRun 'plugins') -Filter 'TrueSeasons-*.jar') {
    if ($taskOldJar.Name -ne 'TrueSeasons-0.1.4.jar') { Move-Item -LiteralPath $taskOldJar.FullName -Destination "$($taskOldJar.FullName).disabled" -Force }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.4.jar') -Destination (Join-Path $taskRun 'plugins')
$taskProbe = Join-Path $PSScriptRoot 'build/probe'
New-Item -ItemType Directory -Force -Path $taskProbe | Out-Null
[System.IO.File]::WriteAllText((Join-Path $taskProbe 'plugin.yml'), @'
name: TrueSeasonsVerification
version: 1.0
main: dev.trueseasons.ServerProbe
api-version: '26.3'
depend: [TrueSeasons]
'@)
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskRun 'plugins/TrueSeasonsVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' $taskProbe 'plugin.yml'
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
$taskPluginConfig = Join-Path $taskRun 'plugins/TrueSeasons'
New-Item -ItemType Directory -Force -Path $taskPluginConfig | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'src/main/resources/*.yml') -Destination $taskPluginConfig
$taskMainConfig = Join-Path $taskPluginConfig 'config.yml'
$taskMainText = [System.IO.File]::ReadAllText($taskMainConfig).Replace('pause-below-tps: 18.5','pause-below-tps: 0.0')
$taskMainText = $taskMainText.Replace('max-concurrent-chunks: 2','max-concurrent-chunks: 4').Replace('max-chunks-per-tick: 1','max-chunks-per-tick: 4')
$taskMainText = $taskMainText.Replace('rescan-seconds: 300','rescan-seconds: 30')
[System.IO.File]::WriteAllText($taskMainConfig,$taskMainText)
$taskIntegrationConfig = Join-Path $taskPluginConfig 'integrations.yml'
[System.IO.File]::WriteAllText($taskIntegrationConfig,[System.IO.File]::ReadAllText($taskIntegrationConfig).Replace('flush-seconds: 30','flush-seconds: 5'))
# Run four normal stop/start cycles, including a completed-winter checkpoint.
for ($taskCycle = 1; $taskCycle -le 4; $taskCycle++) {
    $taskStart = [System.Diagnostics.ProcessStartInfo]::new()
    $taskStart.FileName = Join-Path $JdkDirectory 'bin/java.exe'
    $taskStart.WorkingDirectory = $taskRun
    $taskStart.UseShellExecute = $false
    $taskStart.CreateNoWindow = $true
    $taskStart.RedirectStandardOutput = $true
    $taskStart.RedirectStandardError = $true
    $taskStart.RedirectStandardInput = $true
    foreach ($taskArg in @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','nogui')) { $taskStart.ArgumentList.Add($taskArg) }
    $taskProcess = [System.Diagnostics.Process]::new()
    $taskProcess.StartInfo = $taskStart
    [void]$taskProcess.Start()
    [System.IO.File]::WriteAllText((Join-Path $taskRun 'server.pid'),[string]$taskProcess.Id)
    $taskOut = $taskProcess.StandardOutput.ReadToEndAsync()
    $taskErr = $taskProcess.StandardError.ReadToEndAsync()
    $taskDeadline = [DateTime]::UtcNow.AddMinutes(20)
    $taskNextStatus = [DateTime]::UtcNow.AddSeconds(30)
    while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) {
        if ([DateTime]::UtcNow -gt $taskNextStatus) {
            $taskProcess.StandardInput.WriteLine('ts status')
            $taskNextStatus = [DateTime]::UtcNow.AddSeconds(30)
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $taskProcess.HasExited) {
        $taskProcess.StandardInput.WriteLine('stop')
        if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true); throw 'Verification timed out' }
    }
    $taskProcess.WaitForExit()
    [System.IO.File]::WriteAllText((Join-Path $taskRun "cycle-$taskCycle.log"),$taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult())
    $taskCycleLog = [System.IO.File]::ReadAllText((Join-Path $taskRun "cycle-$taskCycle.log"))
    if ($taskCycleLog.Contains('Encountered an unexpected exception')) { throw "Server startup failed in cycle $taskCycle; inspect cycle-$taskCycle.log" }
    $taskResult = Join-Path $taskRun 'verification-result.txt'
    if ((Test-Path -LiteralPath $taskResult) -and [System.IO.File]::ReadAllText($taskResult).StartsWith('FAIL')) {
        throw [System.IO.File]::ReadAllText($taskResult)
    }
    Write-Output "Server verification cycle $taskCycle completed."
}
$taskResult = Join-Path $taskRun 'verification-result.txt'
if (-not (Test-Path -LiteralPath $taskResult)) { throw 'Verification did not complete; inspect run/verification/cycle-*.log' }
Write-Output ([System.IO.File]::ReadAllText($taskResult))
