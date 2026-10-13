param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRun = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'run/verification')).Path
if (-not $taskRun.StartsWith((Join-Path $PSScriptRoot 'run') + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Not a verification clone' }
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JdkDirectory $JdkDirectory }
foreach ($taskFile in @('bluemap-5.29-paper.jar','bmm-paper-2.1.19.jar')) {
    $taskPath = Join-Path $taskRun "plugins/$taskFile"
    if (Test-Path -LiteralPath $taskPath) { Rename-Item -LiteralPath $taskPath -NewName "$taskFile.disabled" }
}
foreach ($taskOldJar in Get-ChildItem -LiteralPath (Join-Path $taskRun 'plugins') -Filter 'TrueSeasons-*.jar') {
    if ($taskOldJar.Name -ne 'TrueSeasons-0.1.7.jar') { Rename-Item -LiteralPath $taskOldJar.FullName -NewName ($taskOldJar.Name + '.disabled') -Force }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.7.jar') -Destination (Join-Path $taskRun 'plugins') -Force
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskRun 'plugins/TrueSeasonsVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' (Join-Path $PSScriptRoot 'build/probe') 'plugin.yml'
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
$taskProcess = [System.Diagnostics.Process]::new()
$taskProcess.StartInfo = $taskStart
[void]$taskProcess.Start()
[System.IO.File]::WriteAllText((Join-Path $taskRun 'optional.pid'),[string]$taskProcess.Id)
$taskOut = $taskProcess.StandardOutput.ReadToEndAsync()
$taskErr = $taskProcess.StandardError.ReadToEndAsync()
$taskDeadline = [DateTime]::UtcNow.AddMinutes(4)
while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 500 }
if (-not $taskProcess.HasExited) { $taskProcess.Kill($true); throw 'Optional integration verification timed out' }
$taskProcess.WaitForExit()
$taskText = $taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult()
[System.IO.File]::WriteAllText((Join-Path $taskRun 'optional.log'),$taskText)
if (-not $taskText.Contains('PROBE_OPTIONAL_PASSED') -or -not $taskText.Contains('BlueMap: not installed') -or -not $taskText.Contains('Plugin removal safe: YES')) { throw 'Optional BlueMap test failed; inspect optional.log' }
Write-Output ([System.IO.File]::ReadAllText((Join-Path $taskRun 'optional-result.txt')))
