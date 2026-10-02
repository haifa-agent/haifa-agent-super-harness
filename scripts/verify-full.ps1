#Requires -Version 7
<#
.SYNOPSIS
    Runs the full offline gate (mvn clean verify) and stamps the exec JAR it produced.

.DESCRIPTION
    Runs
        mvn -o -ntp clean verify
    with the Haifa SDK version from local-tmp/sdk-selection.json (see
    scripts/lib/sdk-selection.ps1). On success it writes the server build stamp, so UI
    runners on the same commit can reuse this JAR with -SkipServerBuild instead of
    packaging and running the unit tests a second time.

    Maven output goes to a log file under local-tmp. A JSON summary is written to stdout.
    No stamp is written when the gate fails or when server sources are uncommitted.

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$LowMemory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rejected = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Rejected.Count -gt 0) {
    throw "Unsupported argument(s): $($Rejected -join ' '). This entry point always runs the full gate."
}

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/sdk-selection.ps1')
. (Join-Path $PSScriptRoot 'lib/build-stamp.ps1')
$sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root

$mavenArguments = @('-o', '-ntp')
if ($sdk.MavenArgument) {
    $mavenArguments += $sdk.MavenArgument
}
if ($LowMemory) {
    $mavenArguments += '-DargLine=-Xmx768m -XX:+UseSerialGC'
}
$mavenArguments += @('clean', 'verify')

$logDirectory = Join-Path $root 'local-tmp/verify-full'
$null = New-Item -ItemType Directory -Force -Path $logDirectory
$logPath = Join-Path $logDirectory "verify-full-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').log"

$savedMavenOpts = $env:MAVEN_OPTS
$watch = [Diagnostics.Stopwatch]::StartNew()
Push-Location $root
try {
    if ($LowMemory) {
        $env:MAVEN_OPTS = '-Xmx512m -XX:+UseSerialGC'
    }
    & mvn.cmd @mavenArguments *> $logPath
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    $env:MAVEN_OPTS = $savedMavenOpts
    $watch.Stop()
}

$counts = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($line in Get-Content -LiteralPath $logPath) {
    if ($line -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$') {
        $counts.tests += [int]$Matches[1]
        $counts.failures += [int]$Matches[2]
        $counts.errors += [int]$Matches[3]
        $counts.skipped += [int]$Matches[4]
    }
}

$stampPath = Get-ServerBuildStampPath $root
if ($exitCode -eq 0) {
    Write-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
}
else {
    Remove-BuildStamp $stampPath
}

[ordered]@{
    status = $(if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' })
    exitCode = $exitCode
    command = "mvn.cmd $($mavenArguments -join ' ')"
    sourceSha = (& git -C $root rev-parse HEAD).Trim()
    sdkSelection = $sdk.Status
    haifaVersion = $sdk.HaifaVersion
    tests = $counts.tests
    failures = $counts.failures
    errors = $counts.errors
    skipped = $counts.skipped
    elapsedMs = $watch.ElapsedMilliseconds
    serverBuildStamp = $(if (Test-Path -LiteralPath $stampPath -PathType Leaf) { $stampPath } else { $null })
    logPath = $logPath
} | ConvertTo-Json

exit $exitCode
