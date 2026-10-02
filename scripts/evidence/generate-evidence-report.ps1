[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$OutputDirectory = "target/evidence",
    [switch]$RequireSurefire,
    # Pre-exported SHPM snapshot (tests/fixtures). When omitted, the live SHPM database is exported.
    [string]$ShpmSnapshot,
    [string]$ShpmDatabase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot '../lib/customer-demo-evidence.ps1')
. (Join-Path $PSScriptRoot '../lib/shpm-governance.ps1')

$PinnedDeerFlowSha = "e545c28ac301e6f9feae247c513d8ea8f83df294"
$RuntimeStatuses = @("PASS", "FAIL", "NOT_RUN")
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Get-RelativePath {
    param([string]$Root, [string]$Path)
    return ([IO.Path]::GetRelativePath($Root, $Path)).Replace('\', '/')
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-JsonFile {
    param([string]$Path)
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "Required evidence input is missing: $Path"
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -DateKind String
    }
    catch {
        throw "Invalid JSON evidence input '$Path': $($_.Exception.Message)"
    }
}

function Get-SurefireEvidence {
    param([string]$Root)
    $reportFiles = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter 'TEST-*.xml' |
        Where-Object { $_.FullName -match '[\\/]target[\\/]surefire-reports[\\/]' } |
        Sort-Object FullName)
    $reports = @()
    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($file in $reportFiles) {
        try { [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw }
        catch { throw "Invalid Surefire XML '$($file.FullName)': $($_.Exception.Message)" }
        $suite = $xml.testsuite
        Assert-Condition ($null -ne $suite) "Surefire report has no testsuite root: $($file.FullName)"
        $tests = [int]$suite.tests
        $failures = [int]$suite.failures
        $errors = [int]$suite.errors
        $skipped = if ($null -eq $suite.skipped -or [string]$suite.skipped -eq '') { 0 } else { [int]$suite.skipped }
        $status = if ($failures -gt 0 -or $errors -gt 0) { "FAIL" } elseif ($tests -gt 0) { "PASS" } else { "NOT_RUN" }
        $reports += [ordered]@{
            path = Get-RelativePath $Root $file.FullName
            sha256 = Get-Sha256 $file.FullName
            status = $status
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
        }
        $totals.tests += $tests
        $totals.failures += $failures
        $totals.errors += $errors
        $totals.skipped += $skipped
    }
    $overall = if ($totals.failures -gt 0 -or $totals.errors -gt 0) {
        "FAIL"
    } elseif ($reportFiles.Count -gt 0 -and $totals.tests -gt 0) {
        "PASS"
    } else {
        "NOT_RUN"
    }
    return [ordered]@{ status = $overall; totals = $totals; reports = $reports }
}

function Assert-RuntimeStatus {
    param([object]$Node, [string]$Label)
    $status = [string]$Node.runtime_status
    Assert-Condition ($script:RuntimeStatuses -contains $status) "$Label has invalid runtime_status '$status'"
    if ($status -eq 'PASS') {
        Assert-Condition ($Node.PSObject.Properties.Name -contains 'runtime_evidence' -and
            @($Node.runtime_evidence).Count -gt 0) "$Label claims runtime PASS without runtime_evidence"
    }
    return $status
}

function Get-DeerFlowEvidence {
    param([string]$Root)
    $consumerPath = Join-Path $Root 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
    $resourcePath = Join-Path $Root 'contracts/upstream/deerflow/resource-seed.json'
    $frontendPath = Join-Path $Root 'third-party/deerflow/manifest.json'
    $consumer = Get-JsonFile $consumerPath
    $resources = Get-JsonFile $resourcePath
    $frontend = Get-JsonFile $frontendPath

    Assert-Condition ([string]$consumer.source.source_sha -eq $PinnedDeerFlowSha) `
        "frontend consumer manifest is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$resources.source.source_sha -eq $PinnedDeerFlowSha) `
        "resource seed is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$frontend.upstream.commit -eq $PinnedDeerFlowSha) `
        "frontend provenance is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$consumer.status -eq 'SOURCE_VERIFIED') `
        "frontend consumer manifest must retain SOURCE_VERIFIED static status"
    Assert-Condition ([string]$resources.status -eq 'SOURCE_VERIFIED') `
        "resource seed must retain SOURCE_VERIFIED static status"

    $runtimeStatuses = @()
    $runtimeStatuses += Assert-RuntimeStatus $consumer 'frontend consumer manifest'
    foreach ($surface in @($consumer.surfaces)) {
        Assert-Condition ([string]$surface.status -eq 'SOURCE_VERIFIED') `
            "surface '$($surface.id)' must retain SOURCE_VERIFIED static status"
        $runtimeStatuses += Assert-RuntimeStatus $surface "surface '$($surface.id)'"
    }
    $runtimeStatuses += Assert-RuntimeStatus $resources 'resource seed'

    foreach ($resource in @($resources.resources)) {
        Assert-Condition ([string]$resource.status -eq 'SOURCE_VERIFIED') `
            "resource '$($resource.id)' must retain SOURCE_VERIFIED static status"
        Assert-Condition (@('IMPORTED', 'NOT_RUN') -contains [string]$resource.import_status) `
            "resource '$($resource.id)' has invalid import_status"
        if ([string]$resource.import_status -eq 'IMPORTED') {
            $local = Join-Path $Root ([string]$resource.intended_local_path)
            Assert-Condition (Test-Path -LiteralPath $local -PathType Leaf) `
                "imported resource '$($resource.id)' is missing its local file"
            # Extracted/adapted imports have a local byte hash distinct from source provenance.
            # A present but invalid local hash must fail, never fall back to the source hash.
            $hashField = if ($resource.PSObject.Properties.Name -contains 'local_sha256') { 'local_sha256' } else { 'sha256' }
            $localHash = [string](Get-ShpmProp $resource $hashField)
            Assert-Condition ($localHash -cmatch '^[0-9a-f]{64}$') `
                "imported resource '$($resource.id)' has invalid $hashField"
            Assert-Condition ((Get-Sha256 $local) -eq $localHash) `
                "imported resource '$($resource.id)' hash mismatch"
        }
    }

    $license = Join-Path $Root ([string]$frontend.upstream.licenseFile)
    $lockfile = Join-Path $Root ([string]$frontend.build.lockfile)
    Assert-Condition ((Get-Sha256 $license) -eq [string]$frontend.upstream.licenseSha256) `
        "frontend provenance license hash mismatch"
    Assert-Condition ((Get-Sha256 $lockfile) -eq [string]$frontend.build.lockfileSha256) `
        "frontend provenance lockfile hash mismatch"
    foreach ($patch in @($frontend.import.patches)) {
        $patchPath = Join-Path $Root ([string]$patch.file)
        Assert-Condition (Test-Path -LiteralPath $patchPath -PathType Leaf) `
            "recorded frontend patch is missing: $($patch.file)"
        Assert-Condition ((Get-Sha256 $patchPath) -eq [string]$patch.sha256) `
            "recorded frontend patch hash mismatch: $($patch.file)"
    }

    $runtime = if ($runtimeStatuses -contains 'FAIL') { 'FAIL' }
        elseif ($runtimeStatuses -contains 'NOT_RUN') { 'NOT_RUN' }
        else { 'PASS' }
    return [ordered]@{
        validationStatus = "PASS"
        runtimeStatus = $runtime
        sourceSha = $PinnedDeerFlowSha
        consumerManifest = [ordered]@{
            path = Get-RelativePath $Root $consumerPath
            sha256 = Get-Sha256 $consumerPath
            status = [string]$consumer.status
            runtimeStatus = [string]$consumer.runtime_status
            surfaceCount = @($consumer.surfaces).Count
        }
        resourceSeed = [ordered]@{
            path = Get-RelativePath $Root $resourcePath
            sha256 = Get-Sha256 $resourcePath
            status = [string]$resources.status
            runtimeStatus = [string]$resources.runtime_status
            resourceCount = @($resources.resources).Count
        }
        frontendProvenance = [ordered]@{
            path = Get-RelativePath $Root $frontendPath
            sha256 = Get-Sha256 $frontendPath
            sourceTree = [string]$frontend.upstream.sourceTree
            inventorySha256 = [string]$frontend.import.contentInventorySha256
            patchStatus = [string]$frontend.import.patchStatus
        }
    }
}

function Write-DeterministicText {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, (($Content -replace "`r`n", "`n").TrimEnd() + "`n"), $Utf8NoBom)
}

function Get-CustomerDemoEvidence {
    param([string]$Root)
    $bundlePath = Join-Path $Root 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'
    if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
        return [ordered]@{
            status = 'NOT_RUN'
            decision = 'NO_GO'
            detail = 'customer demo evidence bundle is absent'
            failed = @()
            blockedExternal = @()
            upstreamUnsupported = @()
            notRun = @()
        }
    }
    try {
        $bundle = Get-CdeJson $bundlePath
        $result = Test-CdeEvidenceBundle -Root $Root -Bundle $bundle -BundlePath $bundlePath
        $summary = $result.summary
        return [ordered]@{
            status = $summary.gateStatus
            decision = $summary.decision
            bundleSha256 = $summary.bundleSha256
            oracleSha256 = $summary.oracleSha256
            mainSha = $summary.mainSha
            stageResults = $summary.stageResults
            rounds = $summary.rounds
            humanAcceptancePresent = $summary.humanAcceptance.present
            referenceFallbackUsed = $summary.referenceFallback.fallbackUsed
            firstFailureHistory = $summary.firstFailureHistory
            failed = $summary.failed
            blockedExternal = $summary.blockedExternal
            upstreamUnsupported = $summary.upstreamUnsupported
            notRun = $summary.notRun
        }
    }
    catch {
        return [ordered]@{
            status = 'FAIL'
            decision = 'NO_GO'
            detail = $_.Exception.Message
            failed = @()
            blockedExternal = @()
            upstreamUnsupported = @()
            notRun = @()
        }
    }
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
Assert-Condition ($output.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase)) "OutputDirectory must stay inside RepositoryRoot"

$null = New-Item -ItemType Directory -Force -Path $output
$snapshotPath = if (-not [string]::IsNullOrWhiteSpace($ShpmSnapshot)) {
    (Resolve-Path -LiteralPath $ShpmSnapshot).Path
} else {
    Export-ShpmSnapshot -Database $ShpmDatabase -OutputPath (Join-Path $output 'shpm-snapshot.json')
}
$snapshot = Read-ShpmSnapshot $snapshotPath
$governance = Test-ShpmGovernance -Snapshot $snapshot -RepositoryRoot $root
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$haifaSdkVersion = [string]$pom.project.properties.'haifa.version'
$surefire = Get-SurefireEvidence $root
$deerflow = Get-DeerFlowEvidence $root
$customerDemo = Get-CustomerDemoEvidence $root

$overall = if ($surefire.status -eq 'FAIL' -or $deerflow.runtimeStatus -eq 'FAIL' -or $customerDemo.status -eq 'FAIL') {
    'FAIL'
} elseif ($surefire.status -eq 'PASS' -and $deerflow.runtimeStatus -eq 'PASS' -and $customerDemo.status -eq 'PASS') {
    'PASS'
} elseif ($customerDemo.status -in @('BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED')) {
    $customerDemo.status
} else {
    'NOT_RUN'
}

$manifest = [ordered]@{
    schemaVersion = 1
    overallStatus = $overall
    statusPolicy = "PASS/FAIL/NOT_RUN/BLOCKED_EXTERNAL/UPSTREAM_UNSUPPORTED are preserved; any stage or round FAIL propagates to the aggregate; static validation never upgrades runtime NOT_RUN"
    architecturePolicy = [ordered]@{
        status = "NOT_RUN"
        decision = "A product model more complex than frozen DeerFlow requires pre-code maintainer review"
        w24Scope = "QA-only aggregation; no product domain object or business runtime is introduced"
    }
    governance = $governance
    mavenSurefire = $surefire
    deerflow = $deerflow
    customerDemoEvidence = $customerDemo
    inputs = [ordered]@{
        shpmSnapshotSha256 = Get-Sha256 $snapshotPath
        shpmSnapshotExportedAt = [string]$snapshot.exported_at
        haifaSdkVersion = $haifaSdkVersion
    }
}

$manifestPath = Join-Path $output 'evidence-manifest.json'
Write-DeterministicText $manifestPath ($manifest | ConvertTo-Json -Depth 20)
$manifestHash = Get-Sha256 $manifestPath
Write-DeterministicText (Join-Path $output 'evidence-manifest.sha256') "$manifestHash  evidence-manifest.json"

$demoNotes = @(
    "- Customer demo evidence bundle: **$($customerDemo.status)** (decision $($customerDemo.decision))"
)
if (@($customerDemo.failed).Count -gt 0) {
    $demoNotes += "- Customer demo failed items: $(($customerDemo.failed) -join ', ')"
}
if (@($customerDemo.blockedExternal).Count -gt 0) {
    $demoNotes += "- Customer demo blocked-external items: $(($customerDemo.blockedExternal) -join ', ')"
}
if (@($customerDemo.upstreamUnsupported).Count -gt 0) {
    $demoNotes += "- Customer demo upstream-unsupported items: $(($customerDemo.upstreamUnsupported) -join ', ')"
}

$reportLines = @(
    '# W24 deterministic evidence report',
    '',
    "- Overall: **$overall**",
    "- SHPM governance: **$($governance.status)** ($($governance.packageCount) work packages, $($governance.taskCount) tasks, $($governance.runningAssignments) running assignments; prompt paths $($governance.promptPathCheck))",
    "- Maven Surefire: **$($surefire.status)** ($($surefire.totals.tests) tests, $($surefire.totals.failures) failures, $($surefire.totals.errors) errors, $($surefire.totals.skipped) skipped)",
    "- DeerFlow static provenance/contracts: **$($deerflow.validationStatus)**",
    "- DeerFlow runtime parity: **$($deerflow.runtimeStatus)**"
) + $demoNotes + @(
    '- Product-model complexity gate: **NOT_RUN** (manual review is required when a product model would be more complex than frozen DeerFlow)',
    ('- Frozen DeerFlow SHA: `{0}`' -f $deerflow.sourceSha),
    ('- Manifest SHA-256: `{0}`' -f $manifestHash),
    '',
    'Static SOURCE_VERIFIED or IMPORTED evidence does not prove runtime parity. A stage or round FAIL propagates to the aggregate; runtime NOT_RUN remains NOT_RUN.'
)
Write-DeterministicText (Join-Path $output 'evidence-report.md') ($reportLines -join "`n")

Write-Output ([pscustomobject]@{
    overallStatus = $overall
    manifest = Get-RelativePath $root $manifestPath
    sha256 = $manifestHash
    surefireStatus = $surefire.status
    deerflowRuntimeStatus = $deerflow.runtimeStatus
} | ConvertTo-Json -Compress)

if ($surefire.status -eq 'FAIL') {
    throw "Surefire evidence contains failures or errors"
}
if ($RequireSurefire -and $surefire.status -ne 'PASS') {
    throw "Surefire evidence is required but status is $($surefire.status)"
}
if ($deerflow.runtimeStatus -eq 'FAIL') {
    throw "DeerFlow contract runtime evidence contains FAIL"
}
if ($customerDemo.status -eq 'FAIL') {
    throw "Customer demo evidence bundle failed fail-closed validation"
}
