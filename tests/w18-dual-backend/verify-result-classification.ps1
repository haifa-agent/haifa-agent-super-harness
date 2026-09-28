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
foreach ($allowedBranch in @('codex/w18-demo-l2-differential', 'main')) {
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

Write-Output "W18_RESULT_CLASSIFICATION PASS assertions=7 branch_assertions=$branchAssertions"
