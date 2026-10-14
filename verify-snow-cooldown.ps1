param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRun = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'run/verification')).Path
if (-not $taskRun.StartsWith((Join-Path $PSScriptRoot 'run') + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Not a verification clone' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRun 'verification-result.txt'))) { throw 'Completed verification clone required' }
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JdkDirectory $JdkDirectory }
foreach ($taskOld in Get-ChildItem -LiteralPath (Join-Path $taskRun 'plugins') -Filter 'TrueSeasons*.jar') {
    $taskArchivedName = $taskOld.Name + '.disabled'
    if (Test-Path -LiteralPath (Join-Path $taskRun "plugins/$taskArchivedName")) { $taskArchivedName += '.' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
    Rename-Item -LiteralPath $taskOld.FullName -NewName $taskArchivedName
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.10.jar') -Destination (Join-Path $taskRun 'plugins') -Force
$taskProbe = Join-Path $PSScriptRoot 'build/snow-cooldown-probe'
New-Item -ItemType Directory -Path $taskProbe -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $taskProbe 'plugin.yml'), @'
name: TrueSeasonsSnowCooldownVerification
version: 1.0
main: dev.trueseasons.snow.SnowCooldownRuntimeProbe
api-version: '26.3'
depend: [TrueSeasons]
'@)
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskRun 'plugins/TrueSeasonsSnowCooldownVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' $taskProbe 'plugin.yml'
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
$taskStart = [System.Diagnostics.ProcessStartInfo]::new()
$taskStart.FileName = Join-Path $JdkDirectory 'bin/java.exe'
$taskStart.WorkingDirectory = $taskRun
$taskStart.UseShellExecute = $false
$taskStart.CreateNoWindow = $true
$taskStart.RedirectStandardOutput = $true
$taskStart.RedirectStandardError = $true
$taskStart.RedirectStandardInput = $true
foreach ($taskArg in @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','nogui')) { $taskStart.ArgumentList.Add($taskArg) }
$taskProcess = [System.Diagnostics.Process]::new(); $taskProcess.StartInfo = $taskStart
[void]$taskProcess.Start()
[System.IO.File]::WriteAllText((Join-Path $taskRun 'snow-cooldown.pid'), [string]$taskProcess.Id)
$taskOut = $taskProcess.StandardOutput.ReadToEndAsync(); $taskErr = $taskProcess.StandardError.ReadToEndAsync()
$taskDeadline = [DateTime]::UtcNow.AddMinutes(3)
while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 500 }
if (-not $taskProcess.HasExited) { $taskProcess.StandardInput.WriteLine('stop'); if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true) }; throw 'Snow cooldown verification timed out' }
$taskProcess.WaitForExit()
$taskText = $taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult()
[System.IO.File]::WriteAllText((Join-Path $taskRun 'snow-cooldown.log'), $taskText)
if (-not $taskText.Contains('PROBE_SNOW_COOLDOWN_PASSED') -or $taskText.Contains('PROBE_SNOW_COOLDOWN_FAILED')) { throw 'Snow cooldown verification failed; inspect snow-cooldown.log' }
Write-Output ([System.IO.File]::ReadAllText((Join-Path $taskRun 'snow-cooldown-result.txt')))
