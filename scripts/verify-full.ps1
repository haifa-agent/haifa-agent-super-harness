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

    When Maven fails, the surefire reports are read into ``failedTests``. If every failure is
    registered in tests/known-intermittent.json (at most two, none covered by the change since
    main, and the failed module still ran its other test classes), the failed classes are rerun
    three times and the reactor-skipped modules are built; only then is the summary PASS with a
    ``knownIntermittent`` object. -NoKnownIntermittent keeps the plain FAIL. The first failure's
    log and reports are copied to local-tmp/verify-full/<name>-first-fail/ regardless.

    Maven output goes to a log file under local-tmp. The JSON summary is written to stdout
    and next to the log (same name, .json) for ``shpm verify ingest``.
    No stamp is written when the gate fails or when server sources are uncommitted.

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1 -Forks 1 -LowMemory

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1 -NoReuse

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-full.ps1 -NoKnownIntermittent
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [ValidateRange(1, 4)]
    [int]$Forks = 3,
    [switch]$LowMemory,
    [switch]$NoReuse,
    [switch]$NoKnownIntermittent,
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

# Runs the plan scripts/lib/known_intermittent.py produced. It only executes the commands the
# plan names: archive the first failure, rerun the failed classes, run the reactor-skipped
# modules' tests, then package the failed module. Any failing step returns $null and the gate
# stays FAIL. The caller writes the stamp and the summary.
function Invoke-KnownIntermittentBranch {
    param($Plan, [string]$LogPath, [string]$SummaryPath)
    $firstFail = Join-Path (Split-Path -Parent $SummaryPath) `
        ("$([IO.Path]::GetFileNameWithoutExtension($SummaryPath))-first-fail")
    $null = New-Item -ItemType Directory -Force -Path $firstFail
    Copy-Item -LiteralPath $LogPath -Destination (Join-Path $firstFail 'maven-first-fail.log') -Force
    foreach ($module in @($Plan.rerunModules)) {
        $reports = Join-Path $root "$module/target/surefire-reports"
        if (Test-Path -LiteralPath $reports -PathType Container) {
            Copy-Item -LiteralPath $reports -Destination (Join-Path $firstFail "$module-surefire-reports") -Recurse -Force
        }
    }
    $rerunLogs = [Collections.Generic.List[string]]::new()
    $testTargeted = Join-Path $PSScriptRoot 'test-targeted.ps1'
    # test-targeted.ps1 runs "-pl <module> -am -Dtest=<classes>": upstream modules are compiled
    # but only the named classes run, so neither step repeats the failed module's whole suite.
    function Invoke-TargetedClasses([string]$Module, [string[]]$Classes, [string]$Log) {
        $arguments = @('-NoLogo', '-NoProfile', '-File', $testTargeted, '-Module', $Module, '-Test', ($Classes -join ','))
        if ($useLowMemory) { $arguments += '-LowMemory' }
        $output = & pwsh @arguments 2>&1
        $code = $LASTEXITCODE
        ($output -join "`n") | Set-Content -LiteralPath $Log -Encoding utf8
        return $code
    }
    for ($pass = 1; $pass -le [int]$Plan.reruns; $pass++) {
        foreach ($group in @($Plan.rerunByModule)) {
            $rerunLog = Join-Path $firstFail "rerun-$pass-$($group.module).log"
            $rerunLogs.Add($rerunLog)
            if ((Invoke-TargetedClasses $group.module @($group.classes) $rerunLog) -ne 0) {
                Write-Host "verify-full: known-intermittent rerun $pass failed in $($group.module); keeping FAIL."
                return $null
            }
        }
    }
    # Modules the reactor skipped after the failure never ran their tests: run exactly those.
    foreach ($group in @($Plan.skippedModuleTests)) {
        if (@($group.classes).Count -eq 0) { continue }
        $skippedLog = Join-Path $firstFail "skipped-$($group.module).log"
        if ((Invoke-TargetedClasses $group.module @($group.classes) $skippedLog) -ne 0) {
            Write-Host "verify-full: tests of the reactor-skipped module '$($group.module)' failed; keeping FAIL."
            return $null
        }
    }
    Push-Location $root
    try {
        foreach ($module in @($Plan.rerunModules)) {
            & mvn.cmd -o -ntp -pl $module -am -DskipTests package *> (Join-Path $firstFail "$module-package.log")
            if ($LASTEXITCODE -ne 0) {
                Write-Host "verify-full: packaging the failed module '$module' failed; keeping FAIL."
                return $null
            }
        }
    }
    finally {
        Pop-Location
    }
    return [ordered]@{
        reruns = [int]$Plan.reruns
        entries = @(foreach ($entry in @($Plan.matched)) {
            [ordered]@{ test = $entry.test; module = $entry.module; issue = $entry.issue
                firstFailureEvidence = $firstFail; rerunLogs = @($rerunLogs) }
        })
    }
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

# A fingerprint of a dirty working tree, or one computed without the SDK JARs, does not
# describe what Maven is about to build: it is neither reused nor written to the summary.
if ($null -ne $fingerprint -and -not $fingerprint.reusable) {
    Write-Host "verify-full: fingerprint not usable for reuse ($($fingerprint.notReusableReason)); running the gate."
    $fingerprint = $null
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

# On failure, read the surefire reports and decide (in Python) whether only registered
# intermittent tests failed. failedTests is recorded either way; knownIntermittent turns a
# confirmed intermittent failure into PASS with the provenance and the rerun count.
$failedTests = @()
$knownIntermittent = $null
if ($exitCode -ne 0) {
    $planArguments = @('-B', (Join-Path $PSScriptRoot 'lib/known_intermittent.py'),
        '--repo', $root, '--log', $logPath, '--manifest', 'tests/known-intermittent.json', '--base', 'main')
    if ($NoKnownIntermittent) { $planArguments += '--no-known-intermittent' }
    $plan = $null
    try {
        $plan = ((@(& python @planArguments) -join "`n") | ConvertFrom-Json)
    }
    catch {
        Write-Host "verify-full: known-intermittent plan unavailable ($($_.Exception.Message)); recording plain FAIL."
    }
    if ($null -ne $plan) {
        $failedTests = @($plan.failedTests)
        Write-Host "verify-full: $($failedTests.Count) failed test(s); known-intermittent branch: $($plan.use) ($($plan.reason))"
        if ($plan.use) {
            $knownIntermittent = Invoke-KnownIntermittentBranch -Plan $plan -LogPath $logPath -SummaryPath $summaryPath
            if ($null -ne $knownIntermittent) {
                $exitCode = 0
            }
        }
    }
}

$stampPath = Get-ServerBuildStampPath $root
if ($exitCode -eq 0) {
    Write-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
}
else {
    Remove-BuildStamp $stampPath
}

$summaryFields = [ordered]@{
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
    failedTests = @($failedTests)
    # The Maven stopwatch stopped after the first invocation; a recovered gate also spent time
    # on reruns, skipped-module tests and packaging, so it reports the whole run.
    elapsedMs = $(if ($null -ne $knownIntermittent) { $runWatch.ElapsedMilliseconds } else { $watch.ElapsedMilliseconds })
    serverBuildStamp = $(if (Test-Path -LiteralPath $stampPath -PathType Leaf) { $stampPath } else { $null })
    logPath = $logPath
    summaryPath = $summaryPath
}
if ($null -ne $knownIntermittent) { $summaryFields.knownIntermittent = $knownIntermittent }

Write-Summary $summaryFields

exit $exitCode
