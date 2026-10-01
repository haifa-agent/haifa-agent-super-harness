Set-StrictMode -Version Latest

function Assert-W18DifferentialBranch {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Branch
    )

    $allowed = @('codex/w18-demo-l2-differential', 'codex/w18-v2-m1-differential', 'codex/w18-v2-m1-rerun', 'main')
    if ($allowed -cnotcontains $Branch) {
        throw "W18 differential runner rejects branch '$Branch'. Allowed: $($allowed -join ', ')."
    }
}

# Maps a Playwright case title to its customer-demo-v2 Oracle case id (main case
# or negative), e.g. "haifa: V2-M1-05 model reads ..." -> "V2-M1-05" and
# "... V2-M1-03-N1 ..." -> "V2-M1-03-N1". Returns $null when the title is not an
# Oracle case (legacy W18 differential cases).
function Get-W18OracleCaseId {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Title
    )

    $match = [regex]::Match($Title, 'V2-M[1-6]-\d{2}(?:-N\d+)?')
    if ($match.Success) { return $match.Value }
    return $null
}

# Returns Oracle id -> human ruling id for every case or negative case that the
# committed customer-demo-v2 oracle defers (single-identity ruling 2026-09-29).
# A deferred id stays in the per-case results but may only be NOT_RUN and is not
# required for the stage.
function Get-W18OracleDeferrals {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$OraclePath
    )

    $oracle = Get-Content -Raw -LiteralPath $OraclePath | ConvertFrom-Json -Depth 64
    $deferrals = @{}
    foreach ($case in @($oracle.cases)) {
        if ($null -ne $case.PSObject.Properties['deferral']) { $deferrals[[string]$case.id] = [string]$case.deferral.rulingRef }
        foreach ($neg in @($case.negativeCases)) {
            if ($null -ne $neg.PSObject.Properties['deferral']) { $deferrals[[string]$neg.id] = [string]$neg.deferral.rulingRef }
        }
    }
    return $deferrals
}

# A deferred Oracle id reported as anything but NOT_RUN is a classification
# failure: a deferral can never be turned into coverage.
function Get-W18DeferredStatus {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Status
    )

    if ($Status -ceq 'NOT_RUN') { return 'NOT_RUN' }
    return "FAIL(deferred-reported-$Status)"
}

function Get-W18DifferentialClassification {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$PlaywrightStatus,
        [object[]]$Annotations = @()
    )

    if ($PlaywrightStatus -ceq 'passed') {
        return [ordered]@{ status = 'PASS'; reason = $null }
    }

    # A non-PASS classification is accepted only for a deliberately skipped
    # case. A failed/timed-out test stays FAIL even if it also carries an
    # annotation, so an annotation cannot hide a missing capability.
    if ($PlaywrightStatus -ceq 'skipped') {
        $allowed = @('NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED')
        $classificationAnnotations = @($Annotations | Where-Object {
                $type = [string]$_.type
                $allowed -ccontains $type
            })
        if (
            $classificationAnnotations.Count -eq 1 -and
            -not [string]::IsNullOrWhiteSpace([string]$classificationAnnotations[0].description)
        ) {
            return [ordered]@{
                status = [string]$classificationAnnotations[0].type
                reason = ([string]$classificationAnnotations[0].description).Trim()
            }
        }
    }

    return [ordered]@{ status = "FAIL($PlaywrightStatus)"; reason = $null }
}
