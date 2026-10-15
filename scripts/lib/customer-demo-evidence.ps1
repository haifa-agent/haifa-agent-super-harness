# Shared, fail-closed validation logic for the customer demo v0 evidence bundle.
# Dot-source this file; it defines functions and read-only constants only and must
# not emit output when dot-sourced. It never stores or prints credential values.

$CdeResultVocabulary = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED')
$CdeAllowedWorkStatus = @('pending', 'in_progress', 'ready_for_acceptance', 'blocked', 'complete')
$CdeExpectedStageIds = @('M0', 'M1', 'M2', 'M3', 'M4')
$CdeExpectedCaseIds = @(
    'run-created', 'approval-pending', 'reload-reconnect', 'approval-exactly-once',
    'artifact-download', 'active-steer', 'terminal', 'cleanup'
)
$CdePinnedDeerFlowSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
$CdeAllowedLocalHosts = @('127.0.0.1', 'localhost', '::1')
$CdeDefaultOraclePath = 'contracts/customer-demo/customer-demo-v0-oracle.json'
$CdeDefaultBundlePath = 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'

function Test-CdeProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    if ($Object -is [System.Collections.IDictionary]) { return $Object.Contains($Name) }
    return ($Object.PSObject.Properties.Name -contains $Name)
}

function Get-CdeValue {
    param($Object, [string]$Name, $Default = $null)
    if (Test-CdeProperty $Object $Name) {
        if ($Object -is [System.Collections.IDictionary]) { return $Object[$Name] }
        return $Object.$Name
    }
    return $Default
}

function Get-CdeSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-CdeJson {
    param([string]$Path)
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 64 -DateKind String)
}

function Add-CdeViolation {
    param([System.Collections.Generic.List[string]]$Violations, [string]$Message)
    if (-not $Violations.Contains($Message)) { $Violations.Add($Message) }
}

function Assert-CdeCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Resolve-CdeEvidencePath {
    param([string]$Root, [string]$RelativeOrAbsolute)
    if ([string]::IsNullOrWhiteSpace($RelativeOrAbsolute)) { return $null }
    if ([IO.Path]::IsPathRooted($RelativeOrAbsolute)) { return [IO.Path]::GetFullPath($RelativeOrAbsolute) }
    return [IO.Path]::GetFullPath((Join-Path $Root $RelativeOrAbsolute))
}

function Assert-CdeInsideRoot {
    param([System.Collections.Generic.List[string]]$Violations, [string]$Root, [string]$FullPath, [string]$Label)
    $rootFull = [IO.Path]::GetFullPath($Root)
    if (-not $FullPath.StartsWith($rootFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Add-CdeViolation $Violations "$Label escapes the repository root: $FullPath"
        return $false
    }
    return $true
}

function Test-CdeEvidenceReference {
    param(
        [System.Collections.Generic.List[string]]$Violations,
        [string]$Root,
        $Reference,
        [string]$Label,
        [System.Collections.Generic.List[object]]$Resolved
    )
    $pathName = if (Test-CdeProperty $Reference 'path') { 'path' } elseif (Test-CdeProperty $Reference 'evidencePath') { 'evidencePath' } else { $null }
    if ($null -eq $pathName -or [string]::IsNullOrWhiteSpace([string](Get-CdeValue $Reference $pathName ''))) {
        Add-CdeViolation $Violations "$Label is missing an evidence path"
        return
    }
    $shaName = if (Test-CdeProperty $Reference 'sha256') { 'sha256' } elseif (Test-CdeProperty $Reference 'evidenceSha256') { 'evidenceSha256' } else { $null }
    $relative = [string](Get-CdeValue $Reference $pathName)
    $expectedSha = if ($null -ne $shaName) { [string](Get-CdeValue $Reference $shaName '') } else { '' }
    if ([string]::IsNullOrWhiteSpace($expectedSha)) {
        Add-CdeViolation $Violations "$Label is missing a declared SHA-256"
        return
    }
    $full = Resolve-CdeEvidencePath $Root $relative
    if (-not (Assert-CdeInsideRoot $Violations $Root $full $Label)) { return }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        Add-CdeViolation $Violations "$Label points at a missing evidence file: $relative"
        return
    }
    $actual = Get-CdeSha256 $full
    if ($actual -ne $expectedSha) {
        Add-CdeViolation $Violations "$Label hash mismatch for $relative (declared $expectedSha, actual $actual)"
    }
    if ($null -ne $Resolved) {
        $Resolved.Add([ordered]@{ path = $relative; sha256 = $actual })
    }
}

function Test-CdeOracle {
    param(
        [string]$Root,
        $Bundle,
        [System.Collections.Generic.List[string]]$Violations
    )
    if (-not (Test-CdeProperty $Bundle 'oracle')) {
        Add-CdeViolation $Violations 'bundle is missing the oracle binding'
        return $null
    }
    $oracleRef = $Bundle.oracle
    if (-not (Test-CdeProperty $oracleRef 'path') -or [string]::IsNullOrWhiteSpace([string]$oracleRef.path)) {
        Add-CdeViolation $Violations 'bundle.oracle.path is required'
        return $null
    }
    $relative = [string]$oracleRef.path
    $full = Resolve-CdeEvidencePath $Root $relative
    if (-not (Assert-CdeInsideRoot $Violations $Root $full 'bundle.oracle')) { return $null }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        Add-CdeViolation $Violations "oracle contract is missing: $relative"
        return $null
    }
    $actualSha = Get-CdeSha256 $full
    $declaredSha = [string](Get-CdeValue $oracleRef 'sha256' '')
    if ($declaredSha -ne $actualSha) {
        Add-CdeViolation $Violations "oracle sha256 mismatch (declared $declaredSha, actual $actualSha)"
    }
    $plan = Get-CdeJson $full
    Assert-CdeCondition ($null -ne $plan) 'oracle contract is empty'

    if ([string](Get-CdeValue $plan 'schemaVersion') -ne 'haifa.customer-demo.oracle/v1') {
        Add-CdeViolation $Violations 'oracle schemaVersion drifted'
    }
    if ([string](Get-CdeValue $plan 'demoId') -ne 'DEMO-V0') {
        Add-CdeViolation $Violations 'oracle demoId must be DEMO-V0'
    }
    if ([string](Get-CdeValue $plan.frozenFrontend 'commit') -ne $CdePinnedDeerFlowSha) {
        Add-CdeViolation $Violations 'oracle drifted from the frozen DeerFlow commit'
    }
    if ((Get-CdeValue $plan.frozenFrontend 'fallbackAllowed') -ne $false) {
        Add-CdeViolation $Violations 'oracle must forbid DeerFlow backend fallback'
    }
    if ([string](Get-CdeValue $plan.runtime 'sdkVersion') -ne '0.1.3-SNAPSHOT') {
        Add-CdeViolation $Violations 'oracle must consume Haifa Agent 0.1.3-SNAPSHOT'
    }
    if ((Get-CdeValue $plan.budget 'authorizationRequired') -ne $true -or
        (Get-CdeValue $plan.budget 'paidRunAllowedByThisFile') -ne $false) {
        Add-CdeViolation $Violations 'oracle must not authorize a paid model run'
    }
    if ([int](Get-CdeValue $plan.story 'rounds' 0) -ne 2) {
        Add-CdeViolation $Violations 'oracle must require exactly two consecutive rounds'
    }
    if ([string](Get-CdeValue $plan.story 'tool') -ne 'write_file') {
        Add-CdeViolation $Violations 'oracle must require the production write_file tool'
    }
    if ((Get-CdeValue $plan.story.steerWindow 'manualRetryAllowed') -ne $false -or
        (Get-CdeValue $plan.story.steerWindow 'testOnlyDelayAllowed') -ne $false -or
        [string](Get-CdeValue $plan.story.steerWindow 'target') -ne 'same-run') {
        Add-CdeViolation $Violations 'oracle Steer window must be same-run without test delays or manual retries'
    }
    $caseIds = @(Get-CdeValue $plan 'oracle' @() | ForEach-Object { [string](Get-CdeValue $_ 'id') })
    if ((@($caseIds).Count -ne $CdeExpectedCaseIds.Count) -or
        (($caseIds -join ',') -ne ($CdeExpectedCaseIds -join ','))) {
        Add-CdeViolation $Violations 'oracle case identity or order drifted'
    }
    $vocab = @(Get-CdeValue $plan 'resultVocabulary' @() | ForEach-Object { [string]$_ })
    if (($vocab -join ',') -ne ($CdeResultVocabulary -join ',')) {
        Add-CdeViolation $Violations 'oracle result vocabulary drifted'
    }
    return [pscustomobject]@{
        path = $relative
        fullPath = $full
        sha256 = $actualSha
        plan = $plan
    }
}

function Get-CdeSecretFindings {
    param([string]$RawText)
    $findings = [System.Collections.Generic.List[string]]::new()
    $patterns = @(
        @{ Id = 'private-key'; Pattern = '-----BEGIN [A-Z ]*PRIVATE KEY-----' },
        @{ Id = 'provider-key'; Pattern = '\bsk-[A-Za-z0-9_-]{16,}' },
        @{ Id = 'bearer-token'; Pattern = '(?i)\bBearer\s+[A-Za-z0-9._-]{20,}' },
        @{ Id = 'named-secret'; Pattern = '(?i)"(?:api[_-]?key|apikey|secret|password|passwd|passphrase|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization[_-]?token)"\s*:\s*"[^"]{8,}"' }
    )
    foreach ($entry in $patterns) {
        if ([regex]::IsMatch($RawText, $entry.Pattern)) { $findings.Add($entry.Id) }
    }
    return $findings
}

function Get-CdeNetworkHost {
    param([string]$Target)
    $value = $Target.Trim()
    if ($value -match '^[a-zA-Z][a-zA-Z0-9+.-]*://') {
        try { return ([Uri]$value).Host } catch { return $null }
    }
    if ($value -match '^\[(?<host>[^\]]+)\]') { return $Matches['host'] }
    if ($value -match '^(?<host>[^:]+)(?::\d+)?$') { return $Matches['host'] }
    return $null
}

function Test-CdeRounds {
    param(
        [string]$Root,
        $Bundle,
        $OraclePlan,
        [System.Collections.Generic.List[string]]$Violations,
        [System.Collections.Generic.List[object]]$Resolved
    )
    $expectedRounds = [int](Get-CdeValue $OraclePlan.story 'rounds' 2)
    $rounds = @(Get-CdeValue $Bundle 'rounds' @())
    if ($rounds.Count -ne $expectedRounds) {
        Add-CdeViolation $Violations "bundle must contain exactly $expectedRounds rounds; found $($rounds.Count)"
        return @()
    }

    $idFields = @('threadId', 'runId', 'toolCallId', 'interactionId', 'artifactId')
    $seen = @{}
    $threadIds = @{}
    $fileNames = @{}
    $bindFields = @('mainSha', 'frozenFrontendSha', 'sdkVersion', 'configSha256', 'dataRoot', 'modelId')
    $bindValues = @{}
    foreach ($field in $bindFields) { $bindValues[$field] = [System.Collections.Generic.List[string]]::new() }

    for ($position = 0; $position -lt $rounds.Count; $position++) {
        $round = $rounds[$position]
        $label = "round $($position + 1)"
        $index = [int](Get-CdeValue $round 'index' 0)
        $sequence = [int](Get-CdeValue $round 'sequence' 0)
        if ($index -ne ($position + 1)) { Add-CdeViolation $Violations "$label index must be $($position + 1)" }
        if ($sequence -ne ($position + 1)) { Add-CdeViolation $Violations "$label sequence must be consecutive at $($position + 1)" }
        $status = [string](Get-CdeValue $round 'status' '')
        if ($CdeResultVocabulary -notcontains $status) { Add-CdeViolation $Violations "$label has invalid status '$status'" }

        foreach ($field in $idFields) {
            $value = [string](Get-CdeValue $round $field '')
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                if ($seen.ContainsKey($value)) {
                    Add-CdeViolation $Violations "$label reuses duplicate id '$value' already seen at $($seen[$value])"
                }
                else { $seen[$value] = $label }
            }
        }
        $threadId = [string](Get-CdeValue $round 'threadId' '')
        if (-not [string]::IsNullOrWhiteSpace($threadId)) {
            if ($threadIds.ContainsKey($threadId)) { Add-CdeViolation $Violations "$label reuses threadId '$threadId'" }
            else { $threadIds[$threadId] = $true }
        }
        $fileName = [string](Get-CdeValue $round 'fileName' '')
        if (-not [string]::IsNullOrWhiteSpace($fileName)) {
            if ($fileNames.ContainsKey($fileName)) { Add-CdeViolation $Violations "$label reuses artifact file name '$fileName'" }
            else { $fileNames[$fileName] = $true }
        }

        foreach ($field in $bindFields) {
            $value = [string](Get-CdeValue $round $field '')
            if (-not [string]::IsNullOrWhiteSpace($value)) { $bindValues[$field].Add($value) }
        }

        $attempts = [int](Get-CdeValue $round 'attempts' 1)
        if ($attempts -lt 1) { Add-CdeViolation $Violations "$label attempts must be at least 1" }
        if ((Get-CdeValue $round 'hiddenRetry' $false) -eq $true) {
            Add-CdeViolation $Violations "$label declares a hidden retry"
        }
        if ($attempts -gt 1) {
            $firstFailure = Get-CdeValue $round 'firstFailure' $null
            if ($null -eq $firstFailure) {
                Add-CdeViolation $Violations "$label ran $attempts attempts but omits the first failure"
            }
            else {
                if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $firstFailure 'summary' ''))) {
                    Add-CdeViolation $Violations "$label firstFailure is missing a summary"
                }
                if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $firstFailure 'evidencePath' ''))) {
                    Add-CdeViolation $Violations "$label firstFailure is missing raw evidence"
                }
                else {
                    Test-CdeEvidenceReference $Violations $Root $firstFailure "$label firstFailure" $Resolved
                }
            }
        }

        if ($status -eq 'PASS') {
            foreach ($field in $idFields) {
                if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $round $field ''))) {
                    Add-CdeViolation $Violations "$label is PASS but missing $field"
                }
            }
            if ([string]::IsNullOrWhiteSpace($fileName)) { Add-CdeViolation $Violations "$label is PASS but missing fileName" }
            foreach ($field in @('fileSha256', 'downloadSha256')) {
                $value = [string](Get-CdeValue $round $field '')
                if ($value -notmatch '^[0-9a-f]{64}$') { Add-CdeViolation $Violations "$label is PASS but $field is not a SHA-256" }
            }
            $fileSha256 = [string](Get-CdeValue $round 'fileSha256' '')
            $downloadSha256 = [string](Get-CdeValue $round 'downloadSha256' '')
            if ($fileSha256 -match '^[0-9a-f]{64}$' -and $downloadSha256 -match '^[0-9a-f]{64}$' -and
                $fileSha256 -ne $downloadSha256) {
                Add-CdeViolation $Violations "$label fileSha256 $fileSha256 does not match downloadSha256 $downloadSha256; the production write_file output must equal the downloaded Artifact bytes"
            }
            if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $round 'artifactProvenance' ''))) {
                Add-CdeViolation $Violations "$label is PASS but the artifact has no provenance"
            }
            if ([int](Get-CdeValue $round 'downloadByteCount' -1) -le 0) {
                Add-CdeViolation $Violations "$label is PASS but the artifact download has no byte count"
            }

            $provenanceEvidence = Get-CdeValue $round 'artifactProvenanceEvidence' $null
            if ($null -eq $provenanceEvidence -or
                [string]::IsNullOrWhiteSpace([string](Get-CdeValue $provenanceEvidence 'path' ''))) {
                Add-CdeViolation $Violations "$label is PASS but the artifact provenance original record is missing"
            }
            else {
                Test-CdeEvidenceReference $Violations $Root $provenanceEvidence "$label provenance evidence" $Resolved
                $provenanceRelative = [string](Get-CdeValue $provenanceEvidence 'path')
                $provenanceFull = Resolve-CdeEvidencePath $Root $provenanceRelative
                if (Test-Path -LiteralPath $provenanceFull -PathType Leaf) {
                    $provenanceRecord = $null
                    try { $provenanceRecord = Get-CdeJson $provenanceFull }
                    catch { Add-CdeViolation $Violations "$label provenance original record is not valid JSON: $provenanceRelative" }
                    if ($null -ne $provenanceRecord) {
                        foreach ($bindField in @('artifactId', 'runId', 'toolCallId')) {
                            $expected = [string](Get-CdeValue $round $bindField '')
                            $found = [string](Get-CdeValue $provenanceRecord $bindField '')
                            if ($found -ne $expected) {
                                Add-CdeViolation $Violations "$label provenance record $bindField mismatch (expected '$expected', found '$found')"
                            }
                        }
                        $expectedTool = [string](Get-CdeValue $OraclePlan.story 'tool' '')
                        $foundTool = [string](Get-CdeValue $provenanceRecord 'tool' '')
                        if ($foundTool -ne $expectedTool) {
                            Add-CdeViolation $Violations "$label provenance record tool mismatch (expected '$expectedTool', found '$foundTool')"
                        }
                        $expectedArtifactKind = [string](Get-CdeValue $OraclePlan.story 'artifact' '')
                        $foundArtifactKind = [string](Get-CdeValue $provenanceRecord 'artifact' '')
                        if ($foundArtifactKind -ne $expectedArtifactKind) {
                            Add-CdeViolation $Violations "$label provenance record artifact mismatch (expected '$expectedArtifactKind', found '$foundArtifactKind')"
                        }
                        $expectedProvenanceDownloadSha = [string](Get-CdeValue $round 'downloadSha256' '')
                        $foundProvenanceDownloadSha = [string](Get-CdeValue $provenanceRecord 'downloadSha256' '')
                        if ($foundProvenanceDownloadSha -ne $expectedProvenanceDownloadSha) {
                            Add-CdeViolation $Violations "$label provenance record downloadSha256 mismatch (expected '$expectedProvenanceDownloadSha', found '$foundProvenanceDownloadSha')"
                        }
                        $expectedProvenanceBytes = [int](Get-CdeValue $round 'downloadByteCount' -1)
                        $foundProvenanceBytes = [int](Get-CdeValue $provenanceRecord 'downloadByteCount' -1)
                        if ($foundProvenanceBytes -ne $expectedProvenanceBytes) {
                            Add-CdeViolation $Violations "$label provenance record downloadByteCount mismatch (expected $expectedProvenanceBytes, found $foundProvenanceBytes)"
                        }
                    }
                }
            }

            $downloadEvidence = Get-CdeValue $round 'downloadEvidence' $null
            if ($null -eq $downloadEvidence -or
                [string]::IsNullOrWhiteSpace([string](Get-CdeValue $downloadEvidence 'path' ''))) {
                Add-CdeViolation $Violations "$label is PASS but the real downloaded file evidence is missing"
            }
            else {
                Test-CdeEvidenceReference $Violations $Root $downloadEvidence "$label download evidence" $Resolved
                $downloadRelative = [string](Get-CdeValue $downloadEvidence 'path')
                $downloadFull = Resolve-CdeEvidencePath $Root $downloadRelative
                if (Test-Path -LiteralPath $downloadFull -PathType Leaf) {
                    $actualDownloadSha = Get-CdeSha256 $downloadFull
                    $declaredDownloadSha = [string](Get-CdeValue $round 'downloadSha256' '')
                    if ($actualDownloadSha -ne $declaredDownloadSha) {
                        Add-CdeViolation $Violations "$label downloadSha256 '$declaredDownloadSha' does not match the actual downloaded bytes '$actualDownloadSha'"
                    }
                    $declaredFileSha = [string](Get-CdeValue $round 'fileSha256' '')
                    if ($actualDownloadSha -ne $declaredFileSha) {
                        Add-CdeViolation $Violations "$label fileSha256 '$declaredFileSha' does not match the actual downloaded bytes '$actualDownloadSha'"
                    }
                    $actualDownloadBytes = (Get-Item -LiteralPath $downloadFull).Length
                    $declaredDownloadBytes = [int](Get-CdeValue $round 'downloadByteCount' -1)
                    if ($actualDownloadBytes -ne $declaredDownloadBytes) {
                        Add-CdeViolation $Violations "$label downloadByteCount $declaredDownloadBytes does not match the actual downloaded length $actualDownloadBytes"
                    }
                }
            }
            if ([string](Get-CdeValue $round 'approvalBefore' '') -ne 'pending' -or
                [string](Get-CdeValue $round 'approvalAfter' '') -ne 'approved') {
                Add-CdeViolation $Violations "$label is PASS but the approval transition is not pending->approved"
            }
            $steer = Get-CdeValue $round 'steer' $null
            if ((Get-CdeValue $steer 'sameRun' $false) -ne $true) {
                Add-CdeViolation $Violations "$label is PASS but Steer did not target the same Run"
            }
            if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $steer 'request' '')) -or
                [string]::IsNullOrWhiteSpace([string](Get-CdeValue $steer 'response' '')) -or
                [string]::IsNullOrWhiteSpace([string](Get-CdeValue $steer 'ackText' ''))) {
                Add-CdeViolation $Violations "$label is PASS but the Steer request/response/ack is incomplete"
            }
            elseif ([string](Get-CdeValue $steer 'ackText' '') -notmatch "DEMO-STEER-ACK-$($position + 1)") {
                Add-CdeViolation $Violations "$label Steer acknowledgement does not match DEMO-STEER-ACK-$($position + 1)"
            }
            if ($null -eq (Get-CdeValue $round 'usage' $null)) {
                Add-CdeViolation $Violations "$label is PASS but usage is missing"
            }
            if (@(Get-CdeValue $round 'timeline' @()).Count -eq 0) {
                Add-CdeViolation $Violations "$label is PASS but the timeline is empty"
            }
            if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $round 'playwrightPath' ''))) {
                Add-CdeViolation $Violations "$label is PASS but no Playwright evidence is recorded"
            }
            else {
                $ref = [ordered]@{ path = (Get-CdeValue $round 'playwrightPath'); sha256 = (Get-CdeValue $round 'playwrightSha256' '') }
                if ([string]::IsNullOrWhiteSpace([string]$ref.sha256)) {
                    Add-CdeViolation $Violations "$label Playwright evidence has no declared hash"
                }
                else { Test-CdeEvidenceReference $Violations $Root $ref "$label Playwright evidence" $Resolved }
            }
            $logPaths = @(Get-CdeValue $round 'logPaths' @())
            if ($logPaths.Count -eq 0) { Add-CdeViolation $Violations "$label is PASS but no log paths are recorded" }
            foreach ($logRef in $logPaths) {
                if ($logRef -is [string]) {
                    Add-CdeViolation $Violations "$label log reference must include a hash, not a bare path"
                }
                else { Test-CdeEvidenceReference $Violations $Root $logRef "$label log evidence" $Resolved }
            }
            $cleanup = Get-CdeValue $round 'processCleanup' $null
            if ((Get-CdeValue $cleanup 'portsReleased' $false) -ne $true) {
                Add-CdeViolation $Violations "$label is PASS but ports were not released"
            }
            if (-not (Test-CdeProperty $cleanup 'processes')) {
                Add-CdeViolation $Violations "$label is PASS but process cleanup is not recorded"
            }
            $targets = @(Get-CdeValue $round 'networkTargets' @())
            if ($targets.Count -eq 0) { Add-CdeViolation $Violations "$label is PASS but no network targets are recorded" }
            foreach ($target in $targets) {
                $hostName = Get-CdeNetworkHost ([string]$target)
                $allowed = ($CdeAllowedLocalHosts -contains $hostName) -or ($hostName -eq [string](Get-CdeValue $OraclePlan.model 'endpointHost' ''))
                if (-not $allowed) { Add-CdeViolation $Violations "$label contacted an unexpected network host '$hostName'" }
            }
        }
    }

    foreach ($field in $bindFields) {
        $distinct = @($bindValues[$field] | Sort-Object -Unique)
        if ($distinct.Count -gt 1) {
            Add-CdeViolation $Violations "rounds are not bound to one $field value: $($distinct -join ', ')"
        }
        $top = [string](Get-CdeValue $Bundle $field '')
        if ($distinct.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($top) -and $top -ne $distinct[0]) {
            Add-CdeViolation $Violations "bundle.$field '$top' does not match round value '$($distinct[0])'"
        }
    }
    return $rounds
}

function Test-CdeStages {
    param(
        [string]$Root,
        $Bundle,
        [System.Collections.Generic.List[string]]$Violations,
        [System.Collections.Generic.List[object]]$Resolved
    )
    $stages = @(Get-CdeValue $Bundle 'stages' @())
    if ($stages.Count -ne $CdeExpectedStageIds.Count) {
        Add-CdeViolation $Violations "bundle must contain exactly five stages M0-M4; found $($stages.Count)"
        return $stages
    }
    $passCommits = [System.Collections.Generic.List[string]]::new()
    for ($position = 0; $position -lt $stages.Count; $position++) {
        $stage = $stages[$position]
        $expectedId = $CdeExpectedStageIds[$position]
        $id = [string](Get-CdeValue $stage 'id' '')
        if ($id -ne $expectedId) { Add-CdeViolation $Violations "stage at position $($position + 1) must be $expectedId" }
        $workStatus = [string](Get-CdeValue $stage 'workStatus' '')
        $result = [string](Get-CdeValue $stage 'result' '')
        if ($CdeAllowedWorkStatus -notcontains $workStatus) { Add-CdeViolation $Violations "$id has invalid workStatus '$workStatus'" }
        if ($CdeResultVocabulary -notcontains $result) { Add-CdeViolation $Violations "$id has invalid result '$result'" }
        if ($result -eq 'PASS' -and $workStatus -ne 'complete') {
            Add-CdeViolation $Violations "$id result PASS requires workStatus complete"
        }
        if ($workStatus -eq 'complete' -and $result -ne 'PASS') {
            Add-CdeViolation $Violations "$id workStatus complete requires result PASS"
        }
        if ($result -eq 'PASS') {
            foreach ($field in @('proof', 'mainCommit', 'acceptedAt')) {
                if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $stage $field ''))) {
                    Add-CdeViolation $Violations "$id PASS requires $field"
                }
            }
            $commit = [string](Get-CdeValue $stage 'mainCommit' '')
            if ($commit -notmatch '^[0-9a-f]{7,40}$') { Add-CdeViolation $Violations "$id mainCommit must be a Git SHA" }
            else { $passCommits.Add($commit) }
            if ($position -gt 0 -and [string](Get-CdeValue $stages[$position - 1] 'result' '') -ne 'PASS') {
                Add-CdeViolation $Violations "$id cannot PASS before $($CdeExpectedStageIds[$position - 1]) PASS"
            }
            $evidence = @(Get-CdeValue $stage 'evidence' @())
            if ($evidence.Count -eq 0) { Add-CdeViolation $Violations "$id PASS requires raw evidence" }
            foreach ($ref in $evidence) { Test-CdeEvidenceReference $Violations $Root $ref "$id evidence" $Resolved }
        }
        if ($id -eq 'M4' -and $result -eq 'PASS') {
            $acceptance = Get-CdeValue $Bundle 'humanAcceptance' $null
            if ((Get-CdeValue $acceptance 'present' $false) -ne $true) {
                Add-CdeViolation $Violations 'M4 PASS requires explicit human acceptance'
            }
        }
    }

    $stagesArray = @($stages)
    $dependencies = @{ 'M1' = 'M0'; 'M2' = 'M1'; 'M3' = 'M2'; 'M4' = 'M3' }
    for ($position = 1; $position -lt $stagesArray.Count; $position++) {
        $id = [string](Get-CdeValue $stagesArray[$position] 'id' '')
        $depends = @(Get-CdeValue $stagesArray[$position] 'dependsOn' @() | ForEach-Object { [string]$_ })
        if ($dependencies.ContainsKey($id)) {
            if ($depends.Count -ne 1 -or $depends[0] -ne $dependencies[$id]) {
                Add-CdeViolation $Violations "$id must depend only on $($dependencies[$id])"
            }
        }
    }

    $distinctCommits = @($passCommits | Sort-Object -Unique)
    if ($distinctCommits.Count -gt 1) {
        Add-CdeViolation $Violations "PASS stages are bound to different mainline SHAs: $($distinctCommits -join ', ')"
    }
    $mainSha = [string](Get-CdeValue $Bundle 'mainSha' '')
    if ($distinctCommits.Count -ge 1) {
        if ([string]::IsNullOrWhiteSpace($mainSha)) {
            Add-CdeViolation $Violations 'a PASS stage requires a mainline SHA on the bundle'
        }
        elseif ($mainSha -ne $distinctCommits[0]) {
            Add-CdeViolation $Violations "bundle.mainSha '$mainSha' does not match PASS stage commit '$($distinctCommits[0])'"
        }
    }
    return $stages
}

function Test-CdeEvidenceBundle {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Bundle,
        [Parameter(Mandatory = $true)][string]$BundlePath
    )
    $violations = [System.Collections.Generic.List[string]]::new()
    $resolved = [System.Collections.Generic.List[object]]::new()
    $rootFull = (Resolve-Path -LiteralPath $Root).Path

    if ([string](Get-CdeValue $Bundle 'schemaVersion' '') -ne 'haifa.customer-demo.evidence-bundle/v1') {
        Add-CdeViolation $violations 'bundle schemaVersion must be haifa.customer-demo.evidence-bundle/v1'
    }
    if ([string](Get-CdeValue $Bundle 'demoId' '') -ne 'DEMO-V0') {
        Add-CdeViolation $violations 'bundle demoId must be DEMO-V0'
    }

    $oracle = Test-CdeOracle $rootFull $Bundle $violations
    $oraclePlan = if ($null -ne $oracle) { $oracle.plan } else { $null }

    if ($null -ne $oraclePlan) {
        if ([string](Get-CdeValue $Bundle 'frozenFrontendSha' '') -ne $CdePinnedDeerFlowSha) {
            Add-CdeViolation $violations 'bundle frozenFrontendSha must equal the frozen DeerFlow commit'
        }
        if ([string](Get-CdeValue $Bundle 'sdkVersion' '') -ne [string](Get-CdeValue $oraclePlan.runtime 'sdkVersion' '')) {
            Add-CdeViolation $violations 'bundle sdkVersion must equal the oracle runtime SDK version'
        }
        $model = Get-CdeValue $Bundle 'model' $null
        foreach ($field in @('id', 'provider', 'endpointHost', 'credentialSource')) {
            if ([string](Get-CdeValue $model $field '') -ne [string](Get-CdeValue $oraclePlan.model $field '')) {
                Add-CdeViolation $violations "bundle model.$field must equal the oracle model.$field"
            }
        }
        if ((Get-CdeValue $model 'secretPresent' $true) -ne $false) {
            Add-CdeViolation $violations 'bundle model must not contain a credential value'
        }
        if ([int](Get-CdeValue $Bundle.budget 'maxModelCallsPerRound' 0) -ne [int](Get-CdeValue $oraclePlan.budget 'maxModelCallsPerRound' 0)) {
            Add-CdeViolation $violations 'bundle budget.maxModelCallsPerRound must equal the oracle budget'
        }
        if ([string](Get-CdeValue $Bundle 'dataRootPolicy' '') -ne [string](Get-CdeValue $oraclePlan.runtime 'dataRootPolicy' '')) {
            Add-CdeViolation $violations 'bundle dataRootPolicy must equal the oracle runtime policy'
        }
    }

    if ((Get-CdeValue $Bundle 'roundResetBetweenRounds' $true) -ne $false) {
        Add-CdeViolation $violations 'rounds must not reset state between the two runs'
    }

    $fallback = Get-CdeValue $Bundle 'referenceFallbackEvidence' $null
    if ($null -eq $fallback) {
        Add-CdeViolation $violations 'bundle must record reference fallback evidence'
    }
    else {
        if ((Get-CdeValue $fallback 'fallbackAllowed' $true) -ne $false) {
            Add-CdeViolation $violations 'reference fallback must be forbidden'
        }
        if ((Get-CdeValue $fallback 'fallbackUsed' $true) -ne $false) {
            Add-CdeViolation $violations 'reference fallback was used during the demo chain'
        }
        if ((Get-CdeValue $fallback 'fallbackUsed' $true) -eq $false -and
            -not [string]::IsNullOrWhiteSpace([string](Get-CdeValue $fallback 'evidencePath' ''))) {
            Test-CdeEvidenceReference $violations $rootFull $fallback 'reference fallback evidence' $resolved
        }
    }

    $rounds = @()
    $stages = @()
    if ($null -ne $oraclePlan) {
        $rounds = @(Test-CdeRounds $rootFull $Bundle $oraclePlan $violations $resolved)
        $stages = @(Test-CdeStages $rootFull $Bundle $violations $resolved)
    }

    $realChainPass = @($stages | Where-Object {
        [string](Get-CdeValue $_ 'id' '') -in @('M3', 'M4') -and [string](Get-CdeValue $_ 'result' '') -eq 'PASS'
    }).Count -gt 0
    if ($realChainPass -and ($null -eq $fallback -or [string]::IsNullOrWhiteSpace([string](Get-CdeValue $fallback 'evidencePath' '')))) {
        Add-CdeViolation $violations 'a PASS on M3/M4 requires raw reference fallback evidence'
    }

    $history = @(Get-CdeValue $Bundle 'firstFailureHistory' @())
    foreach ($entry in $history) {
        if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $entry 'id' ''))) {
            Add-CdeViolation $violations 'first failure history entry is missing an id'
        }
        if ((Get-CdeValue $entry 'originalAvailable' $false) -eq $true) {
            if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $entry 'evidencePath' ''))) {
                Add-CdeViolation $violations "first failure history '$(Get-CdeValue $entry 'id' '')' claims an original without a path"
            }
            else { Test-CdeEvidenceReference $violations $rootFull $entry "first failure history '$(Get-CdeValue $entry 'id' '')'" $resolved }
        }
        elseif ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $entry 'note' ''))) {
            Add-CdeViolation $violations "first failure history '$(Get-CdeValue $entry 'id' '')' must state that it is a summary, not the original"
        }
    }
    $retried = @($rounds | Where-Object { [int](Get-CdeValue $_ 'attempts' 1) -gt 1 })
    if ($retried.Count -gt 0 -and $history.Count -eq 0) {
        Add-CdeViolation $violations 'a retried round requires the first failure to be recorded in the history'
    }

    $acceptance = Get-CdeValue $Bundle 'humanAcceptance' $null
    if ($null -eq $acceptance) {
        Add-CdeViolation $violations 'bundle must record the human acceptance state'
    }
    elseif ((Get-CdeValue $acceptance 'present' $false) -eq $true) {
        foreach ($field in @('acceptedBy', 'acceptedAt')) {
            if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $acceptance $field ''))) {
                Add-CdeViolation $violations "human acceptance is present but missing $field"
            }
        }
        if ([string]::IsNullOrWhiteSpace([string](Get-CdeValue $acceptance 'path' ''))) {
            Add-CdeViolation $violations 'human acceptance is present but has no evidence path'
        }
        else { Test-CdeEvidenceReference $violations $rootFull $acceptance 'human acceptance' $resolved }
    }

    $decision = [string](Get-CdeValue $Bundle 'decision' '')
    if ($decision -notin @('NO_GO', 'GO')) {
        Add-CdeViolation $violations 'bundle decision must be NO_GO or GO'
    }
    if ($decision -eq 'GO') {
        $nonPass = @($stages | Where-Object { [string](Get-CdeValue $_ 'result' '') -ne 'PASS' })
        if ($nonPass.Count -gt 0) {
            Add-CdeViolation $violations 'GO requires every stage M0-M4 to PASS'
        }
        $mainSha = [string](Get-CdeValue $Bundle 'mainSha' '')
        if ($mainSha -notmatch '^[0-9a-f]{7,40}$') {
            Add-CdeViolation $violations 'GO requires a mainline Git SHA'
        }
        else {
            foreach ($stage in $stages) {
                if ([string](Get-CdeValue $stage 'mainCommit' '') -ne $mainSha) {
                    Add-CdeViolation $violations "GO requires $(Get-CdeValue $stage 'id' '') to be revalidated on the final mainline SHA"
                }
            }
            foreach ($round in $rounds) {
                if ([string](Get-CdeValue $round 'mainSha' '') -ne $mainSha) {
                    Add-CdeViolation $violations "GO requires round $(Get-CdeValue $round 'index' 0) to be bound to the final mainline SHA"
                }
            }
        }
        if ($rounds.Count -ne 2 -or @($rounds | Where-Object { [string](Get-CdeValue $_ 'status' '') -ne 'PASS' }).Count -gt 0) {
            Add-CdeViolation $violations 'GO requires both rounds to PASS'
        }
        $model = Get-CdeValue $Bundle 'model' $null
        if ((Get-CdeValue $model 'paidModelCalled' $false) -ne $true -or
            (Get-CdeValue $Bundle.budget 'authorized' $false) -ne $true) {
            Add-CdeViolation $violations 'GO requires an authorized paid model run'
        }
        if ($null -eq $acceptance -or (Get-CdeValue $acceptance 'present' $false) -ne $true) {
            Add-CdeViolation $violations 'GO requires explicit human acceptance'
        }
        if ($history.Count -eq 0) {
            Add-CdeViolation $violations 'GO requires the documented first-failure history'
        }
    }

    $rawText = ''
    if (Test-Path -LiteralPath $BundlePath -PathType Leaf) { $rawText = Get-Content -LiteralPath $BundlePath -Raw }
    $secretFindings = Get-CdeSecretFindings $rawText
    foreach ($finding in $secretFindings) {
        Add-CdeViolation $violations "bundle matched a sensitive value pattern: $finding"
    }

    if ($violations.Count -gt 0) {
        throw ("customer-demo evidence rejected: " + ($violations -join '; '))
    }

    $stageResults = [ordered]@{}
    foreach ($stage in $stages) { $stageResults[[string](Get-CdeValue $stage 'id' '')] = [string](Get-CdeValue $stage 'result' '') }
    $roundSummary = @()
    foreach ($round in $rounds) {
        $roundSummary += [ordered]@{
            index = [int](Get-CdeValue $round 'index' 0)
            status = [string](Get-CdeValue $round 'status' '')
            threadId = [string](Get-CdeValue $round 'threadId' '')
            runId = [string](Get-CdeValue $round 'runId' '')
            artifactId = [string](Get-CdeValue $round 'artifactId' '')
            fileSha256 = [string](Get-CdeValue $round 'fileSha256' '')
            downloadSha256 = [string](Get-CdeValue $round 'downloadSha256' '')
            downloadByteCount = [int](Get-CdeValue $round 'downloadByteCount' 0)
            attempts = [int](Get-CdeValue $round 'attempts' 1)
            hiddenRetry = (Get-CdeValue $round 'hiddenRetry' $false)
        }
    }
    $historySummary = @($history | ForEach-Object {
        [ordered]@{
            id = [string](Get-CdeValue $_ 'id' '')
            stage = [string](Get-CdeValue $_ 'stage' '')
            originalAvailable = (Get-CdeValue $_ 'originalAvailable' $false)
            note = [string](Get-CdeValue $_ 'note' '')
        }
    })
    $bundleSha = if (Test-Path -LiteralPath $BundlePath -PathType Leaf) { Get-CdeSha256 $BundlePath } else { '' }

    $failed = @()
    $notRun = @()
    $blockedExternal = @()
    $upstreamUnsupported = @()
    foreach ($stage in $stages) {
        $result = [string](Get-CdeValue $stage 'result' '')
        $item = "stage:$([string](Get-CdeValue $stage 'id' ''))=$result"
        switch ($result) {
            'FAIL' { $failed += $item }
            'NOT_RUN' { $notRun += $item }
            'BLOCKED_EXTERNAL' { $blockedExternal += $item }
            'UPSTREAM_UNSUPPORTED' { $upstreamUnsupported += $item }
        }
    }
    foreach ($round in $rounds) {
        $status = [string](Get-CdeValue $round 'status' '')
        $item = "round:$([int](Get-CdeValue $round 'index' 0))=$status"
        switch ($status) {
            'FAIL' { $failed += $item }
            'NOT_RUN' { $notRun += $item }
            'BLOCKED_EXTERNAL' { $blockedExternal += $item }
            'UPSTREAM_UNSUPPORTED' { $upstreamUnsupported += $item }
        }
    }
    $gateStatus = if ($failed.Count -gt 0) { 'FAIL' }
        elseif ($decision -eq 'GO') { 'PASS' }
        elseif ($blockedExternal.Count -gt 0) { 'BLOCKED_EXTERNAL' }
        elseif ($upstreamUnsupported.Count -gt 0) { 'UPSTREAM_UNSUPPORTED' }
        else { 'NOT_RUN' }

    return [pscustomobject]@{
        summary = [ordered]@{
            schemaVersion = 'haifa.customer-demo.evidence-summary/v1'
            demoId = [string](Get-CdeValue $Bundle 'demoId' '')
            bundlePath = (Resolve-CdeEvidencePath $rootFull $BundlePath)
            bundleSha256 = $bundleSha
            oraclePath = if ($null -ne $oracle) { $oracle.path } else { '' }
            oracleSha256 = if ($null -ne $oracle) { $oracle.sha256 } else { '' }
            decision = $decision
            gateStatus = $gateStatus
            failed = @($failed)
            blockedExternal = @($blockedExternal)
            upstreamUnsupported = @($upstreamUnsupported)
            mainSha = [string](Get-CdeValue $Bundle 'mainSha' '')
            frozenFrontendSha = [string](Get-CdeValue $Bundle 'frozenFrontendSha' '')
            sdkVersion = [string](Get-CdeValue $Bundle 'sdkVersion' '')
            stageResults = $stageResults
            allStagesPass = (@($stages | Where-Object { [string](Get-CdeValue $_ 'result' '') -ne 'PASS' }).Count -eq 0)
            rounds = $roundSummary
            firstFailureHistory = $historySummary
            humanAcceptance = [ordered]@{
                present = (Get-CdeValue $acceptance 'present' $false)
                path = [string](Get-CdeValue $acceptance 'path' '')
                sha256 = [string](Get-CdeValue $acceptance 'sha256' '')
            }
            referenceFallback = [ordered]@{
                fallbackAllowed = (Get-CdeValue $fallback 'fallbackAllowed' $false)
                fallbackUsed = (Get-CdeValue $fallback 'fallbackUsed' $false)
                evidencePath = [string](Get-CdeValue $fallback 'evidencePath' '')
                evidenceSha256 = [string](Get-CdeValue $fallback 'evidenceSha256' '')
            }
            model = [ordered]@{
                id = [string](Get-CdeValue $Bundle.model 'id' '')
                provider = [string](Get-CdeValue $Bundle.model 'provider' '')
                endpointHost = [string](Get-CdeValue $Bundle.model 'endpointHost' '')
                credentialSource = [string](Get-CdeValue $Bundle.model 'credentialSource' '')
                secretPresent = (Get-CdeValue $Bundle.model 'secretPresent' $false)
                paidModelCalled = (Get-CdeValue $Bundle.model 'paidModelCalled' $false)
            }
            budget = [ordered]@{
                authorized = (Get-CdeValue $Bundle.budget 'authorized' $false)
                maxModelCallsPerRound = [int](Get-CdeValue $Bundle.budget 'maxModelCallsPerRound' 0)
                authorizationRef = [string](Get-CdeValue $Bundle.budget 'authorizationRef' '')
            }
            evidenceReferences = @($resolved)
            notRun = $notRun
            sensitiveFindings = @($secretFindings)
            status = $(if ($decision -eq 'GO') { 'GO' } else { 'NO_GO' })
        }
        violations = @($violations)
    }
}
