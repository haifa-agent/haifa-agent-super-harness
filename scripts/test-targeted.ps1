#Requires -Version 7
<#
.SYNOPSIS
    Runs targeted tests with the reactor scope and SDK version this repository requires.

.DESCRIPTION
    Always runs
        mvn -o -ntp -pl <Module> -am "-Dtest=<patterns>" "-Dsurefire.failIfNoSpecifiedTests=false" test
    so sibling modules come from this worktree's sources, never from stale snapshots in
    the local Maven repository. The Haifa SDK version comes only from
    local-tmp/sdk-selection.json (see scripts/lib/sdk-selection.ps1).

    Maven output goes to a log file under local-tmp. A JSON summary is written to stdout.
    Matching zero tests is a failure.

.EXAMPLE
    pwsh -NoProfile -File scripts/test-targeted.ps1 -Test LocalServerConfigurationTest

.EXAMPLE
    pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'AgentSoulPromptTest,UpdateAgentToolTest' -LowMemory
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$Test,
    [string]$Module = 'super-harness-server',
    [switch]$LowMemory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rejected = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Rejected.Count -gt 0) {
    throw "Unsupported argument(s): $($Rejected -join ' '). This entry point only runs targeted tests; goals, -DskipTests and -Dhaifa.version cannot be passed."
}
$patterns = @($Test | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($patterns.Count -eq 0) {
    throw '-Test requires at least one test class pattern.'
}

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/sdk-selection.ps1')
$sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root

$mavenArguments = @('-o', '-ntp', '-pl', $Module, '-am', "-Dtest=$($patterns -join ',')", '-Dsurefire.failIfNoSpecifiedTests=false')
if ($sdk.MavenArgument) {
    $mavenArguments += $sdk.MavenArgument
}
if ($LowMemory) {
    $mavenArguments += '-DargLine=-Xmx768m -XX:+UseSerialGC'
}
$mavenArguments += 'test'

$logDirectory = Join-Path $root 'local-tmp/test-targeted'
$null = New-Item -ItemType Directory -Force -Path $logDirectory
$logPath = Join-Path $logDirectory "test-targeted-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').log"

$savedMavenOpts = $env:MAVEN_OPTS
$savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$watch = [Diagnostics.Stopwatch]::StartNew()
Push-Location $root
try {
    if ($LowMemory) {
        $env:MAVEN_OPTS = '-Xmx512m -XX:+UseSerialGC'
        $env:JAVA_TOOL_OPTIONS = '-Xmx96m -XX:+UseSerialGC'
    }
    & mvn.cmd @mavenArguments *> $logPath
    $mavenExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    $env:MAVEN_OPTS = $savedMavenOpts
    $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
    $watch.Stop()
}

# Surefire prints one per-class line (ending in "Time elapsed ...") and one summary line
# per module. Only the summary lines are added up.
$counts = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($line in Get-Content -LiteralPath $logPath) {
    if ($line -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$') {
        $counts.tests += [int]$Matches[1]
        $counts.failures += [int]$Matches[2]
        $counts.errors += [int]$Matches[3]
        $counts.skipped += [int]$Matches[4]
    }
}

$exitCode = $mavenExitCode
$status = if ($mavenExitCode -ne 0) { 'FAIL' } elseif ($counts.tests -eq 0) { 'FAIL_NO_TESTS_MATCHED' } else { 'PASS' }
if ($status -eq 'FAIL_NO_TESTS_MATCHED') {
    $exitCode = 1
}

[ordered]@{
    status = $status
    exitCode = $exitCode
    command = "mvn.cmd $($mavenArguments -join ' ')"
    sdkSelection = $sdk.Status
    haifaVersion = $sdk.HaifaVersion
    tests = $counts.tests
    failures = $counts.failures
    errors = $counts.errors
    skipped = $counts.skipped
    elapsedMs = $watch.ElapsedMilliseconds
    logPath = $logPath
} | ConvertTo-Json

exit $exitCode
