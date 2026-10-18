param([string]$JdkDirectory='C:\Program Files\Java\jdk-25.0.4.1')
$ErrorActionPreference='Stop'
$taskSource=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'run/region-verification-0.1.7')).Path
$taskRun=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run/seasonal-colors-verification-0.3.1'))
$taskRunRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run'))+[System.IO.Path]::DirectorySeparatorChar
if(-not $taskRun.StartsWith($taskRunRoot,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path -LiteralPath $taskRun)){throw 'Preserve existing controlled colour verification'}
if(Get-NetTCPConnection -LocalPort 25588 -State Listen -ErrorAction SilentlyContinue){throw 'Colour verification port is in use'}
New-Item -ItemType Directory -Path $taskRun,(Join-Path $taskRun 'plugins') | Out-Null
foreach($taskName in @('libraries','cache','versions','world','config','bluemap')){Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination $taskRun -Recurse}
foreach($taskName in @('paper.jar','eula.txt','bukkit.yml','spigot.yml')){Copy-Item -LiteralPath (Join-Path $taskSource $taskName) -Destination $taskRun}
Copy-Item -LiteralPath (Join-Path $taskSource 'plugins/BlueMap') -Destination (Join-Path $taskRun 'plugins') -Recurse
Copy-Item -LiteralPath (Join-Path $taskSource 'plugins/TrueSeasons') -Destination (Join-Path $taskRun 'plugins') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../test server/plugins/bluemap-5.29-paper.jar') -Destination (Join-Path $taskRun 'plugins')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/libs/TrueSeasons-0.3.1.jar') -Destination (Join-Path $taskRun 'plugins')
# Use the current stopped server's launcher/version to check Paper 26.3 build 181 as well.
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../test server/paper.jar') -Destination (Join-Path $taskRun 'paper.jar') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../test server/versions/26.3/paper-26.3.jar') -Destination (Join-Path $taskRun 'versions/26.3/paper-26.3.jar') -Force
[System.IO.File]::WriteAllText((Join-Path $taskRun 'seasonal-colors-clone.txt'),'Disposable colour receipt verification; original world untouched.')
[System.IO.File]::WriteAllText((Join-Path $taskRun 'server.properties'),"server-ip=127.0.0.1`nserver-port=25588`nonline-mode=false`nlevel-name=world`nview-distance=2`nsimulation-distance=2`nenable-rcon=false`nenable-query=false`nmax-players=1`nwhite-list=true`nenforce-whitelist=true`n")
[System.IO.File]::WriteAllText((Join-Path $taskRun 'whitelist.json'),'[]')
$taskSettings=Join-Path $taskRun 'plugins/TrueSeasons'
Copy-Item -Path (Join-Path $PSScriptRoot 'src/main/resources/*.yml') -Destination $taskSettings -Force
$taskConfig=Join-Path $taskSettings 'config.yml'
$taskText=[System.IO.File]::ReadAllText($taskConfig).Replace('max-concurrent-chunks: 2','max-concurrent-chunks: 4').Replace('max-chunks-per-tick: 1','max-chunks-per-tick: 4').Replace('pause-below-tps: 18.5','pause-below-tps: 0.0')
[System.IO.File]::WriteAllText($taskConfig,$taskText)
$taskIntegration=Join-Path $taskSettings 'integrations.yml'
[System.IO.File]::WriteAllText($taskIntegration,[System.IO.File]::ReadAllText($taskIntegration).Replace('flush-seconds: 30','flush-seconds: 5').Replace('max-regions-per-flush: 1','max-regions-per-flush: 4'))
$taskState=Join-Path $taskSettings 'state/jobs.yml'
$taskText=[System.IO.File]::ReadAllText($taskState)
foreach($taskChange in @(@('override','AUTUMN'),@('cleanup','false'),@('clean','false'),@('paused','false'),@('revision','100'))){$taskText=[regex]::Replace($taskText,'(?m)^'+$taskChange[0]+':.*$',$taskChange[0]+': '+$taskChange[1])}
[System.IO.File]::WriteAllText($taskState,$taskText)
$taskProbe=Join-Path $PSScriptRoot 'build/seasonal-colors-probe';New-Item -ItemType Directory -Force -Path $taskProbe | Out-Null
[System.IO.File]::WriteAllText((Join-Path $taskProbe 'plugin.yml'),"name: TrueSeasonsSeasonalColorsVerification`nversion: 1.0`nmain: dev.trueseasons.presentation.SeasonalColorsRuntimeProbe`napi-version: '26.3'`ndepend: [TrueSeasons, BlueMap]`n")
& (Join-Path $JdkDirectory 'bin/jar.exe') '--create' '--file' (Join-Path $taskRun 'plugins/TrueSeasonsSeasonalColorsVerification.jar') '-C' (Join-Path $PSScriptRoot 'build/test-classes') '.' '-C' $taskProbe 'plugin.yml'
if($LASTEXITCODE-ne 0){throw 'Colour probe packaging failed'}
$taskStart=[System.Diagnostics.ProcessStartInfo]::new();$taskStart.FileName=Join-Path $JdkDirectory 'bin/java.exe';$taskStart.WorkingDirectory=$taskRun
$taskStart.UseShellExecute=$false;$taskStart.CreateNoWindow=$true;$taskStart.RedirectStandardOutput=$true;$taskStart.RedirectStandardError=$true;$taskStart.RedirectStandardInput=$true
foreach($taskArgument in @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','nogui')){$taskStart.ArgumentList.Add($taskArgument)}
$taskProcess=[System.Diagnostics.Process]::new();$taskProcess.StartInfo=$taskStart;[void]$taskProcess.Start()
$taskOut=$taskProcess.StandardOutput.ReadToEndAsync();$taskErr=$taskProcess.StandardError.ReadToEndAsync();$taskDeadline=[DateTime]::UtcNow.AddMinutes(11)
$taskNextStatus=[DateTime]::UtcNow.AddSeconds(30)
try {
    while(-not $taskProcess.HasExited-and[DateTime]::UtcNow-lt$taskDeadline){
        if(Test-Path -LiteralPath (Join-Path $taskRun 'stop-request.txt')){$taskProcess.StandardInput.WriteLine('stop');break}
        if([DateTime]::UtcNow-ge$taskNextStatus){$taskProcess.StandardInput.WriteLine('ts status');$taskNextStatus=[DateTime]::UtcNow.AddSeconds(30)}
        Start-Sleep -Milliseconds 500
    }
} finally {
    if(-not $taskProcess.HasExited){
        $taskProcess.StandardInput.WriteLine('stop')
        if(-not $taskProcess.WaitForExit(30000)){throw 'Controlled colour server is still saving; preserve its process and inspect logs'}
    }
}
$taskLog=$taskOut.GetAwaiter().GetResult()+$taskErr.GetAwaiter().GetResult();[System.IO.File]::WriteAllText((Join-Path $taskRun 'verification.log'),$taskLog)
if(-not $taskLog.Contains('PROBE_SEASONAL_COLORS_PASSED')-or$taskLog.Contains('PROBE_SEASONAL_COLORS_FAILED')){throw 'Seasonal colour verification failed; inspect controlled clone logs'}
Write-Output ([System.IO.File]::ReadAllText((Join-Path $taskRun 'seasonal-colors-result.txt')))
