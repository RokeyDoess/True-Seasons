param([string]$JdkDirectory = 'C:\Program Files\Java\jdk-25.0.4.1', [switch]$SkipBuild, [switch]$Resume)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify-region-migration.ps1') -JdkDirectory $JdkDirectory -SkipBuild:$SkipBuild -Resume:$Resume
