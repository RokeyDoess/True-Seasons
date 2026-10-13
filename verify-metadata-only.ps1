param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskExpected = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run/verification'))
$taskRun = (Resolve-Path -LiteralPath $taskExpected).Path
if (-not [string]::Equals($taskRun, $taskExpected, [StringComparison]::OrdinalIgnoreCase)) { throw 'Exact existing verification clone required' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRun 'verification-result.txt'))) { throw 'Completed verification clone required' }
if (Get-NetTCPConnection -LocalPort 25585 -State Listen -ErrorAction SilentlyContinue) { throw 'Stop the existing verification server before running this driver' }
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JdkDirectory $JdkDirectory }
function Disable-CloneJar([System.IO.FileInfo]$Jar) {
    $taskPluginRoot = [System.IO.Path]::GetFullPath((Join-Path $taskRun 'plugins'))
    if (-not [string]::Equals($Jar.DirectoryName, $taskPluginRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Jar must stay inside the verification plugins directory' }
    $taskArchived = $Jar.Name + '.disabled'
    if (Test-Path -LiteralPath (Join-Path $taskPluginRoot $taskArchived)) { $taskArchived += '.' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
    Rename-Item -LiteralPath $Jar.FullName -NewName $taskArchived
}
$taskOldProbe = Join-Path $taskRun 'plugins/TrueSeasonsVerification.jar'
if (Test-Path -LiteralPath $taskOldProbe) { Disable-CloneJar (Get-Item -LiteralPath $taskOldProbe) }
foreach ($taskOld in Get-ChildItem -LiteralPath (Join-Path $taskRun 'plugins') -Filter 'TrueSeasons-*.jar') {
    if ($taskOld.Name -ne 'TrueSeasons-0.1.6.jar') { Disable-CloneJar $taskOld }
}
$taskOtherProbes = @(Get-ChildItem -LiteralPath (Join-Path $taskRun 'plugins') -Filter 'TrueSeasons*Verification.jar' | Where-Object Name -ne 'TrueSeasonsMetadataOnlyVerification.jar')
if ($taskOtherProbes.Count -gt 0) { throw 'An unrelated verification probe is active; disable it before this focused run' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.1.6.jar') -Destination (Join-Path $taskRun 'plugins/TrueSeasons-0.1.6.jar') -Force
$taskProbe = Join-Path $PSScriptRoot 'build/metadata-only-probe'
New-Item -ItemType Directory -Path $taskProbe -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $taskProbe 'plugin.yml'), @'
name: TrueSeasonsMetadataOnlyVerification
version: 1.0
main: dev.trueseasons.migration.MetadataOnlyMigrationRuntimeProbe
api-version: '26.3'
depend: [TrueSeasons, BlueMap]
'@)
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskRun 'plugins/TrueSeasonsMetadataOnlyVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' $taskProbe 'plugin.yml'
if ($LASTEXITCODE -ne 0) { throw 'Metadata-only probe packaging failed' }
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
[System.IO.File]::WriteAllText((Join-Path $taskRun 'metadata-only.pid'), [string]$taskProcess.Id)
$taskOut = $taskProcess.StandardOutput.ReadToEndAsync(); $taskErr = $taskProcess.StandardError.ReadToEndAsync()
$taskDeadline = [DateTime]::UtcNow.AddMinutes(5)
while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 500 }
if (-not $taskProcess.HasExited) {
    $taskProcess.StandardInput.WriteLine('stop')
    if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill($true) }
}
$taskProcess.WaitForExit()
$taskText = $taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult()
[System.IO.File]::WriteAllText((Join-Path $taskRun 'metadata-only.log'), $taskText)
if (-not $taskText.Contains('PROBE_METADATA_ONLY_PASSED') -or $taskText.Contains('PROBE_METADATA_ONLY_FAILED')) { throw 'Metadata-only verification failed or timed out; inspect metadata-only.log' }
Write-Output ([System.IO.File]::ReadAllText((Join-Path $taskRun 'metadata-only-result.txt')))
