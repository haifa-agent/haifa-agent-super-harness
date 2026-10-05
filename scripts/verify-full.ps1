#Requires -Version 7
<#
.SYNOPSIS
    Runs the full offline gate (mvn clean verify) and stamps the exec JAR it produced.

.DESCRIPTION
    First runs the second-scale checks from scripts/lib/preflight.ps1 (SDK baseline gate,
    git diff --check, secret scan of the changes since main) and stops before Maven when one
    fails. A process NO_PROXY with wildcard/CIDR entries is replaced for this run only.

    Then it computes the commit's Java input fingerprint (see scripts/lib/source_fingerprint.py)
    and, unless -NoReuse is given, reuses the newest real PASS summary in local-tmp/verify-full
    with the same fingerprint instead of starting Maven. A reused summary has status PASS and a
    ``reuse`` object naming the source summary, its commit and the fingerprint; it is never
    reused again. Otherwise it runs
        mvn -o -ntp -DforkCount=<Forks> -DreuseForks=true clean verify     (Forks defaults to 3)
    with the Haifa SDK version from local-tmp/sdk-selection.json (see
    scripts/lib/sdk-selection.ps1). On success it writes the server build stamp, so UI
    runners on the same fingerprint can reuse this JAR with -SkipServerBuild instead of
    packaging and running the unit tests a second time.

    Maven output goes to a log file under local-tmp. The JSON summary is written to stdout
    and next to the log (same name, .json) for scripts/governance/shpm_record_gates.py.
    No stamp is written when the gate fails or when server sources are uncommitted.

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1 -Forks 1 -LowMemory

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1 -NoReuse
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [ValidateRange(1, 4)]
    [int]$Forks = 3,
    [switch]$LowMemory,
    [switch]$NoReuse,
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
. (Join-Path $PSScriptRoot 'lib/preflight.ps1')

$logDirectory = Join-Path $root 'local-tmp/verify-full'
$null = New-Item -ItemType Directory -Force -Path $logDirectory
$logPath = Join-Path $logDirectory "verify-full-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').log"
$summaryPath = [IO.Path]::ChangeExtension($logPath, '.json')

function Write-Summary {
    param([System.Collections.IDictionary]$Summary)
    $json = $Summary | ConvertTo-Json -Depth 6
    $json | Set-Content -LiteralPath $summaryPath -Encoding utf8
    $json
}

function Get-SummaryValue {
    param($Object, [string]$Name, $Default = $null)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}

Use-SafeNoProxy
$sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root
$sourceSha = (& git -C $root rev-parse HEAD).Trim()
$runWatch = [Diagnostics.Stopwatch]::StartNew()

$preflightFailures = @(Invoke-QuickChecks -RepositoryRoot $root)
if ($preflightFailures.Count -gt 0) {
    Write-Summary ([ordered]@{
        status = 'FAIL_PREFLIGHT'
        exitCode = 1
        sourceSha = $sourceSha
        preflightFailures = $preflightFailures
        summaryPath = $summaryPath
    })
    exit 1
}

$fingerprint = $null
try {
    $fingerprint = Get-SourceFingerprint -RepositoryRoot $root
}
catch {
    Write-Host "verify-full: source fingerprint unavailable ($($_.Exception.Message)); gate reuse disabled."
}

if (-not $NoReuse -and $null -ne $fingerprint -and
    -not [string]::IsNullOrWhiteSpace($fingerprint.javaInputFingerprint)) {
    $discover = & python -B (Join-Path $PSScriptRoot 'governance/evidence_reuse.py') `
        --reusable-summary $logDirectory --fingerprint $fingerprint.javaInputFingerprint
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace((@($discover) -join ''))) {
        $reuseResult = ((@($discover) -join "`n") | ConvertFrom-Json)
        if (-not [string]::IsNullOrWhiteSpace($reuseResult.reusableSummary)) {
            $original = Get-Content -Raw -LiteralPath $reuseResult.reusableSummary | ConvertFrom-Json
            $stampPath = Get-ServerBuildStampPath $root
            $reusedSummary = [ordered]@{
                status = 'PASS'
                exitCode = 0
                command = "reuse $($reuseResult.reusableSummary)"
                sourceSha = $sourceSha
                sourceTree = $fingerprint.sourceTree
                sdkIdentity = $fingerprint.sdkIdentity
                javaInputFingerprint = $fingerprint.javaInputFingerprint
                sdkSelection = $sdk.Status
                haifaVersion = $sdk.HaifaVersion
                forks = Get-SummaryValue $original 'forks' $null
                forksSelection = Get-SummaryValue $original 'forksSelection' 'reused'
                lowMemory = Get-SummaryValue $original 'lowMemory' $null
                freePhysicalMb = Get-SummaryValue $original 'freePhysicalMb' $null
                freeCommitMb = Get-SummaryValue $original 'freeCommitMb' $null
                tests = Get-SummaryValue $original 'tests' 0
                failures = Get-SummaryValue $original 'failures' 0
                errors = Get-SummaryValue $original 'errors' 0
                skipped = Get-SummaryValue $original 'skipped' 0
                elapsedMs = $runWatch.ElapsedMilliseconds
                serverBuildStamp = $(if (Test-Path -LiteralPath $stampPath -PathType Leaf) { $stampPath } else { $null })
                logPath = Get-SummaryValue $original 'logPath' $null
                summaryPath = $summaryPath
                reuse = [ordered]@{
                    fromSummary = $reuseResult.reusableSummary
                    fromSourceSha = Get-SummaryValue $original 'sourceSha' $null
                    fingerprint = $fingerprint.javaInputFingerprint
                }
            }
            $known = Get-SummaryValue $original 'knownIntermittent' $null
            if ($null -ne $known) { $reusedSummary.knownIntermittent = $known }
            $null = Write-Summary $reusedSummary
            Write-Host "verify-full: reused passing summary '$($reuseResult.reusableSummary)' (fingerprint $($fingerprint.javaInputFingerprint))."
            exit 0
        }
    }
}

$forksSelection = 'explicit'
$freePhysicalMb = $null
$freeCommitMb = $null
$useLowMemory = [bool]$LowMemory
if (-not $PSBoundParameters.ContainsKey('Forks') -and -not $LowMemory) {
    $probe = $null
    try {
        $probeJson = & python -B (Join-Path $PSScriptRoot 'lib/gate_resources.py') 2>$null
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($probeJson)) {
            $probe = $probeJson | ConvertFrom-Json
        }
    }
    catch { $probe = $null }
    if ($null -eq $probe -or -not $probe.probeAvailable) {
        $Forks = 3
        $forksSelection = 'default-probe-unavailable'
    }
    else {
        $Forks = [int]$probe.forks
        $useLowMemory = [bool]$probe.lowMemory
        $freePhysicalMb = [int]$probe.freePhysicalMb
        $freeCommitMb = [int]$probe.freeCommitMb
        $forksSelection = 'auto'
    }
}

$mavenArguments = @('-o', '-ntp', "-DforkCount=$Forks", '-DreuseForks=true')
if ($sdk.MavenArgument) {
    $mavenArguments += $sdk.MavenArgument
}
if ($useLowMemory) {
    $mavenArguments += '-DargLine=-Xmx768m -XX:+UseSerialGC'
}
$mavenArguments += @('clean', 'verify')

$savedMavenOpts = $env:MAVEN_OPTS
$watch = [Diagnostics.Stopwatch]::StartNew()
Push-Location $root
try {
    if ($useLowMemory) {
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

Write-Summary ([ordered]@{
    status = $(if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' })
    exitCode = $exitCode
    command = "mvn.cmd $($mavenArguments -join ' ')"
    sourceSha = $sourceSha
    sourceTree = $(if ($null -ne $fingerprint) { $fingerprint.sourceTree } else { $null })
    sdkIdentity = $(if ($null -ne $fingerprint) { $fingerprint.sdkIdentity } else { $null })
    javaInputFingerprint = $(if ($null -ne $fingerprint) { $fingerprint.javaInputFingerprint } else { $null })
    sdkSelection = $sdk.Status
    haifaVersion = $sdk.HaifaVersion
    forks = $Forks
    forksSelection = $forksSelection
    lowMemory = $useLowMemory
    freePhysicalMb = $freePhysicalMb
    freeCommitMb = $freeCommitMb
    tests = $counts.tests
    failures = $counts.failures
    errors = $counts.errors
    skipped = $counts.skipped
    elapsedMs = $watch.ElapsedMilliseconds
    serverBuildStamp = $(if (Test-Path -LiteralPath $stampPath -PathType Leaf) { $stampPath } else { $null })
    logPath = $logPath
    summaryPath = $summaryPath
})

exit $exitCode
