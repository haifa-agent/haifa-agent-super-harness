$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot '../../scripts/lib/w18-differential-results.ps1')

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
}

Assert-Classification 'pass' 'passed' @() 'PASS'
Assert-Classification 'explicit-not-run' 'skipped' @(
    [pscustomobject]@{ type = 'NOT_RUN'; description = 'not configured' }
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
foreach ($allowedBranch in @('codex/w18-demo-l2-differential', 'codex/w18-v2-m1-differential', 'main')) {
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

Write-Output "W18_RESULT_CLASSIFICATION PASS assertions=7 branch_assertions=$branchAssertions oracle_assertions=$oracleAssertions case_list_assertions=$caseListAssertions"
