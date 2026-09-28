Set-StrictMode -Version Latest

function Assert-W18DifferentialBranch {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Branch
    )

    $allowed = @('codex/w18-demo-l2-differential', 'main')
    if ($allowed -cnotcontains $Branch) {
        throw "W18 differential runner rejects branch '$Branch'. Allowed: $($allowed -join ', ')."
    }
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
