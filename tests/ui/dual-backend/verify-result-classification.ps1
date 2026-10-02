$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot '../../../scripts/lib/w18-differential-results.ps1')

$script:classificationAssertions = 0
function Assert-Classification(
    [string]$Name,
    [string]$PlaywrightStatus,
    [object[]]$Annotations,
    [string]$Expected
) {
    $actual = Get-W18DifferentialClassification `
        -PlaywrightStatus $PlaywrightStatus `
        -Annotations $Annotations
    if ($actual.status -cne $Expected) {
        throw "$Name expected $Expected but got $($actual.status)"
    }
    $script:classificationAssertions++
}

Assert-Classification 'pass' 'passed' @() 'PASS'
Assert-Classification 'explicit-not-run' 'skipped' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = 'not configured' }
) 'NOT_RUN'
# Models the real classifyAndSkip output used by the V2-M1-04-N2 case: an
# in-body test.skip() also records a 'skip' annotation next to the explicit
# NOT_RUN claim; the classification must still be NOT_RUN, not FAIL(skipped).
Assert-Classification 'real-skip-with-explicit-not-run' 'skipped' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = 'dual-identity precondition not established' },
    [pscustomobject]@{ type = 'skip'; description = 'dual-identity precondition not established' }
) 'NOT_RUN'
Assert-Classification 'explicit-upstream-unsupported' 'skipped' @(
    [pscustomobject]@{ type = 'skip'; description = 'ordinary Playwright annotation' },
    [pscustomobject]@{ type = 'UPSTREAM_UNSUPPORTED'; description = 'reference protocol differs' }
) 'UPSTREAM_UNSUPPORTED'
Assert-Classification 'unannotated-skip-is-fail' 'skipped' @() 'FAIL(skipped)'
Assert-Classification 'ambiguous-classifications-are-fail' 'skipped' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = 'first claim' },
    [pscustomobject]@{ type = 'BLOCKED_EXTERNAL'; description = 'second claim' }
) 'FAIL(skipped)'
Assert-Classification 'blank-reason-is-fail' 'skipped' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = '   ' }
) 'FAIL(skipped)'
Assert-Classification 'annotation-cannot-hide-failure' 'failed' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = 'must not mask failure' }
) 'FAIL(failed)'

$branchAssertions = 0
foreach ($allowedBranch in @('codex/w18-demo-l2-differential', 'codex/w18-v2-m1-differential', 'codex/w18-v2-m1-rerun', 'main')) {
    Assert-W18DifferentialBranch -Branch $allowedBranch
    $branchAssertions++
}
try {
    Assert-W18DifferentialBranch -Branch 'feat-unrelated'
    throw 'unrelated branch unexpectedly passed the W18 runner guard'
}
catch {
    if ($_.Exception.Message -eq 'unrelated branch unexpectedly passed the W18 runner guard') { throw }
    $branchAssertions++
}

$oracleAssertions = 0
function Assert-OracleCaseId([string]$Title, [string]$Expected) {
    $actual = Get-W18OracleCaseId -Title $Title
    if ([string]::IsNullOrEmpty($Expected)) {
        if ($null -ne $actual) { throw "title '$Title' expected no Oracle id but got '$actual'" }
    }
    elseif ($actual -cne $Expected) {
        throw "title '$Title' expected Oracle id '$Expected' but got '$actual'"
    }
    $script:oracleAssertions++
}
Assert-OracleCaseId 'haifa: V2-M1-01 same-conversation model switch' 'V2-M1-01'
Assert-OracleCaseId 'reference: V2-M1-05 model reads the saved original' 'V2-M1-05'
Assert-OracleCaseId 'haifa: V2-M1-03-N1 unsafe filename is rejected' 'V2-M1-03-N1'
Assert-OracleCaseId 'haifa: V2-M1-01-N2 unknown B is refused and never silently served by A' 'V2-M1-01-N2'
Assert-OracleCaseId 'haifa: V2-M1-05-N3 recomputed region and grand totals' 'V2-M1-05-N3'
Assert-OracleCaseId 'haifa: V2-M6-02-L3 evidence' 'V2-M6-02'
Assert-OracleCaseId 'haifa: no-cookie startup and same-origin API' $null
# V2-M1-04-N2 is a two-identity (owner vs. different caller) Oracle. The
# unknown-Thread 404 diagnostic must NOT be extracted as that Oracle id, while
# the dedicated NOT_RUN case must still map to V2-M1-04-N2.
Assert-OracleCaseId 'haifa: unknown Thread attach to a shelf document returns 404 (diagnostic, no Oracle id)' $null
Assert-OracleCaseId 'reference: unknown Thread attach to a shelf document returns 404 (diagnostic, no Oracle id)' $null
Assert-OracleCaseId 'haifa: V2-M1-04-N2 attaching to a Thread the caller cannot write' 'V2-M1-04-N2'

# Static Oracle case-list check: every V2-M1 case and negative this task must
# report on has to appear as its own test title so the runner emits an
# independent oracleCases row. This is a source-level guard, not a substitute
# for the executed round evidence.
$specText = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'v2-m1-model-file.spec.cjs')
$requiredCaseIds = @(
    'V2-M1-01', 'V2-M1-01-N2', 'V2-M1-01-N3',
    'V2-M1-02',
    'V2-M1-03', 'V2-M1-03-N1', 'V2-M1-03-N2', 'V2-M1-03-N3',
    'V2-M1-04', 'V2-M1-04-N1', 'V2-M1-04-N2',
    'V2-M1-05', 'V2-M1-05-N1', 'V2-M1-05-N2', 'V2-M1-05-N3'
)
$caseListAssertions = 0
foreach ($caseId in $requiredCaseIds) {
    if ($specText -notmatch [regex]::Escape(": $caseId ")) {
        throw "v2-m1 spec is missing an independent test title for Oracle case $caseId"
    }
    $caseListAssertions++
}

# V2-M1-04-N2 must be reported as a deliberate NOT_RUN on both sides because the
# dual-identity (owner vs. a different caller) precondition is not established
# under the forced auth-disabled profile. These source-level guards keep the
# dedicated NOT_RUN title, its annotation and its reason from silently drifting
# back to a missing-Thread 404 being claimed as the cross-owner Oracle.
$n2DiagnosticTitle = 'unknown Thread attach to a shelf document returns 404 (diagnostic, no Oracle id)'
if (-not $specText.Contains($n2DiagnosticTitle)) {
    throw "v2-m1 spec is missing the no-Oracle-id unknown-Thread diagnostic title"
}
$caseListAssertions++
if ($specText.Contains('V2-M1-04-N2 attaching to an unknown Thread')) {
    throw "the unknown-Thread 404 diagnostic must not carry the V2-M1-04-N2 Oracle id"
}
$caseListAssertions++
if (-not $specText.Contains('dual-identity precondition not established')) {
    throw "V2-M1-04-N2 must record the missing dual-identity precondition"
}
$caseListAssertions++
if (-not $specText.Contains('testInfo.annotations.push({ type: "NOT_RUN"')) {
    throw "V2-M1-04-N2 must push an explicit NOT_RUN annotation"
}
$caseListAssertions++

# review4: both spec files must observe the frozen composer's own completion
# before a follow-up Turn and must capture the active-Steer path (and its raw
# request/response) if Enter is still mis-routed, instead of surfacing a bare
# response timeout. These are source-level guards for the wait/capture helpers;
# they never replace the executed round evidence.
$differentialSpecText = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'differential.spec.cjs')
$review4Sources = @(
    @{ name = 'v2-m1-model-file.spec.cjs'; text = $specText; submit = 'async function sendRun(' },
    @{ name = 'differential.spec.cjs'; text = $differentialSpecText; submit = 'async function sendAndCaptureRun(' }
)
foreach ($source in $review4Sources) {
    foreach ($needle in @(
        'function composerStopIcon(',
        'svg.lucide-square',
        'function composerReadyIcon(',
        'svg.lucide-arrow-up',
        'async function waitForComposerReady(',
        'if (!readiness.ready)',
        'Promise.race(',
        'error.steer = {',
        $source.submit
    )) {
        if (-not $source.text.Contains($needle)) {
            throw "$($source.name) is missing the review4 composer-ready/steer-capture guard '$needle'"
        }
        $caseListAssertions++
    }
}
if (-not $differentialSpecText.Contains('upload-model-context-failure')) {
    throw "differential.spec.cjs must persist the upload-context failure (no POST/staged file) before failing"
}
$caseListAssertions++
if (-not $specText.Contains('v2-m1-02-next-run-failure')) {
    throw "v2-m1-model-file.spec.cjs must persist the V2-M1-02 steer capture before failing"
}
$caseListAssertions++

# Single-identity ruling (2026-09-29): the committed oracle defers exactly
# V2-M1-04-N2, V2-M1-06 and V2-M3-09. The runner may report them only as
# NOT_RUN with the ruling id and never as coverage.
$deferralAssertions = 0
$oracleDeferrals = Get-W18OracleDeferrals -OraclePath (Join-Path $PSScriptRoot '../../../contracts/customer-demo-v2/v2-case-oracle.json')
$deferredIds = @($oracleDeferrals.Keys | Sort-Object)
if (($deferredIds -join ',') -ne 'V2-M1-04-N2,V2-M1-06,V2-M3-09') { throw "oracle deferrals must be exactly V2-M1-04-N2,V2-M1-06,V2-M3-09 (got $($deferredIds -join ','))" }
$deferralAssertions++
foreach ($id in $deferredIds) {
    if ($oracleDeferrals[$id] -cne 'human-2026-09-29-single-identity') { throw "deferral $id must cite the human single-identity ruling" }
    $deferralAssertions++
}
if ((Get-W18DeferredStatus -Status 'NOT_RUN') -cne 'NOT_RUN') { throw 'a deferred NOT_RUN must stay NOT_RUN' }
$deferralAssertions++
foreach ($status in @('PASS', 'FAIL(failed)', 'BLOCKED_EXTERNAL')) {
    if ((Get-W18DeferredStatus -Status $status) -notlike 'FAIL(deferred-reported-*') { throw "a deferred case reported as $status must classify as FAIL" }
    $deferralAssertions++
}
if (-not $specText.Contains('deferral: "human-2026-09-29-single-identity"')) { throw 'V2-M1-04-N2 raw evidence must cite the single-identity deferral ruling' }
$deferralAssertions++

Write-Output "W18_RESULT_CLASSIFICATION PASS deferral_assertions=$deferralAssertions assertions=$script:classificationAssertions branch_assertions=$branchAssertions oracle_assertions=$oracleAssertions case_list_assertions=$caseListAssertions"
