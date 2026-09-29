# Shared V2 evidence-contract validation and fail-closed aggregation helpers.
#
# This library is dot-sourced by the W24 V2 doctor verifier. It never writes to
# SHPM, never calls a model, and never opens a network connection.
#
# Fail-closed contract: Test-V2EvidenceInstance returns a result object whose
# `decision` is only "GO" when every oracle case id required for GO (every case
# except those carrying a human-ruled `deferral` in the oracle) is PASS on the
# Haifa backend on one main SHA with raw originals, external copies, truthful
# usage, released ports, and a signed human Pilot declaration. Every other state
# (including NOT_RUN, BLOCKED_EXTERNAL, UPSTREAM_UNSUPPORTED, a missing field, an
# inconsistent id/hash, a hidden retry, a stub, or a missing declaration) yields
# "NO_GO" and is listed in `violations`.

$script:V2RequiredNoGoConditionIds = @(
    'missing-case',
    'missing-required-evidence',
    'field-inconsistency',
    'wrong-main-sha',
    'wrong-sdk-sha',
    'wrong-fixture-hash',
    'wrong-config-hash',
    'forged-zero-usage',
    'id-mismatch',
    'missing-raw-evidence',
    'missing-external-copy',
    'l3-l2-stub',
    'l3-undeclared-tool',
    'tools-call-trace',
    'hidden-retry',
    'v1-evidence-as-v2',
    'not-run-as-pass',
    'upstream-unsupported-as-pass',
    'uncleaned',
    'nonzero-subprocess-exit',
    'missing-human-declaration',
    'deferred-as-pass'
)

$script:V2ResultVocabulary = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED', 'NOT_APPLICABLE')
$script:V2StageVocabulary = @('V2-M1', 'V2-M2', 'V2-M3', 'V2-M4', 'V2-M5', 'V2-M6')
$script:V2HaifaGatingResults = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL')

function Get-V2Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-V2Json {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 64 -DateKind String)
}

function Get-V2Prop {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $current = $Object
    foreach ($segment in $Name.Split('.')) {
        if ($null -eq $current) { return $null }
        $p = $current.PSObject.Properties[$segment]
        if ($null -eq $p) { return $null }
        $current = $p.Value
    }
    return $current
}

function Test-V2PropPresent {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    $p = $Object.PSObject.Properties[$Name]
    return ($null -ne $p)
}

function Resolve-V2EvidencePath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot, [Parameter(Mandatory = $true)][string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $RepositoryRoot $Path)
}

function Get-V2ContractPath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    return (Join-Path $RepositoryRoot 'contracts/customer-demo-v2/v2-evidence-contract.json')
}

function Get-V2OraclePath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    return (Join-Path $RepositoryRoot 'contracts/customer-demo-v2/v2-case-oracle.json')
}

function Get-V2InstanceRequiredFields {
    param([Parameter(Mandatory = $true)][object]$Contract, [Parameter(Mandatory = $true)][object]$CaseMeta)
    $fields = New-Object System.Collections.Generic.List[string]
    foreach ($f in @($Contract.evidenceRules.commonRequiredEvidence)) { [void]$fields.Add([string]$f) }
    $level = [string]$CaseMeta.level
    $stage = [string]$CaseMeta.stage
    foreach ($rule in @($Contract.evidenceRules.conditionalRequiredEvidence)) {
        $when = [string]$rule.when
        $applies = $false
        if ($when -eq "stage in ['V2-M3','V2-M4']") { $applies = ($stage -eq 'V2-M3' -or $stage -eq 'V2-M4') }
        elseif ($when -eq "stage == 'V2-M5'") { $applies = ($stage -eq 'V2-M5') }
        elseif ($when -eq "level == 'L3'") { $applies = ($level -eq 'L3') }
        else { throw "Unknown conditional required-evidence rule in contract: $when" }
        if ($applies) { foreach ($f in @($rule.require)) { [void]$fields.Add([string]$f) } }
    }
    return @($fields | Select-Object -Unique)
}

function Add-V2Violation {
    param([Parameter(Mandatory = $true)][object]$Violations, [Parameter(Mandatory = $true)][string]$Id, [Parameter(Mandatory = $true)][string]$Detail, [string]$CaseId)
    $Violations.Add([ordered]@{ id = $Id; caseId = $CaseId; detail = $Detail })
}

function Test-V2FieldPopulated {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [string]) { return (-not [string]::IsNullOrWhiteSpace($Value)) }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        return (@($Value).Count -gt 0)
    }
    return $true
}

# Validates one PASS case record. Returns nothing; appends violations.
function Test-V2PassCaseRecord {
    param(
        [Parameter(Mandatory = $true)][object]$Record,
        [Parameter(Mandatory = $true)][object]$CaseMeta,
        [Parameter(Mandatory = $true)][object]$Contract,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][object]$Pins,
        [Parameter(Mandatory = $true)][object]$Violations
    )
    $caseId = [string]$Record.id
    # These fields must be present but may legitimately be null (no retry, usage unknown).
    $allowNull = @('retryFirstCause', 'usageTotalTokens')
    $required = Get-V2InstanceRequiredFields -Contract $Contract -CaseMeta $CaseMeta
    foreach ($field in $required) {
        if (-not (Test-V2PropPresent $Record $field)) {
            Add-V2Violation $Violations 'missing-required-evidence' "case $caseId omits required evidence field '$field'" $caseId
            continue
        }
        if ($allowNull -contains $field) { continue }
        $value = Get-V2Prop $Record $field
        if (-not (Test-V2FieldPopulated $value)) {
            Add-V2Violation $Violations 'missing-required-evidence' "case $caseId has empty required evidence field '$field'" $caseId
        }
    }

    # v1 evidence can never stand in for a V2 execution.
    $source = [string](Get-V2Prop $Record 'source')
    if ($source -ne 'v2') {
        Add-V2Violation $Violations 'v1-evidence-as-v2' "case $caseId is PASS with source='$source'; only fresh v2 evidence may be PASS" $caseId
    }

    # Hidden retry: a retry chain must be disclosed with a first cause.
    $hidden = Get-V2Prop $Record 'hiddenRetry'
    if ($hidden -eq $true) {
        Add-V2Violation $Violations 'hidden-retry' "case $caseId PASS claims hiddenRetry=true" $caseId
    }
    $retryCount = Get-V2Prop $Record 'retryCount'
    if ($null -ne $retryCount -and [int]$retryCount -gt 0) {
        if (-not (Test-V2FieldPopulated (Get-V2Prop $Record 'retryFirstCause'))) {
            Add-V2Violation $Violations 'hidden-retry' "case $caseId has retryCount=$retryCount without a recorded first cause" $caseId
        }
    }

    # Truthful usage: unknown must be null, never a forged 0.
    $usageKnown = Get-V2Prop $Record 'usageKnown'
    $usageTokens = Get-V2Prop $Record 'usageTotalTokens'
    if ($usageKnown -eq $false) {
        if ($null -ne $usageTokens) {
            Add-V2Violation $Violations 'forged-zero-usage' "case $caseId marks usage unknown but records totalTokens=$usageTokens; unknown must be null" $caseId
        }
    }
    elseif ($usageKnown -eq $true) {
        if ($null -eq $usageTokens) {
            Add-V2Violation $Violations 'missing-required-evidence' "case $caseId marks usage known but totalTokens is null" $caseId
        }
        elseif ([int]$usageTokens -lt 0) {
            Add-V2Violation $Violations 'field-inconsistency' "case $caseId records a negative totalTokens" $caseId
        }
    }

    # Stable ids must agree between the case record and the provenance original.
    $artifactId = Get-V2Prop $Record 'artifactId'
    $provenanceArtifactId = Get-V2Prop $Record 'provenanceArtifactId'
    if ((Test-V2FieldPopulated $artifactId) -and (Test-V2FieldPopulated $provenanceArtifactId) -and ([string]$artifactId -ne [string]$provenanceArtifactId)) {
        Add-V2Violation $Violations 'id-mismatch' "case $caseId artifactId '$artifactId' disagrees with provenance artifactId '$provenanceArtifactId'" $caseId
    }
    $toolCallId = Get-V2Prop $Record 'toolCallId'
    $toolCallIdInProvenance = Get-V2Prop $Record 'toolCallIdInProvenance'
    if ((Test-V2FieldPopulated $toolCallId) -and (Test-V2FieldPopulated $toolCallIdInProvenance) -and ([string]$toolCallId -ne [string]$toolCallIdInProvenance)) {
        Add-V2Violation $Violations 'id-mismatch' "case $caseId toolCallId '$toolCallId' disagrees with provenance toolCallId '$toolCallIdInProvenance'" $caseId
    }

    # Raw originals must exist with matching bytes.
    $rawFiles = Get-V2Prop $Record 'rawFiles'
    if (Test-V2FieldPopulated $rawFiles) {
        foreach ($raw in @($rawFiles)) {
            $rawPath = [string](Get-V2Prop $raw 'path')
            $rawSha = [string](Get-V2Prop $raw 'sha256')
            $full = Resolve-V2EvidencePath -RepositoryRoot $RepositoryRoot -Path $rawPath
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId references missing raw original '$rawPath'" $caseId
                continue
            }
            if ((Get-V2Sha256 $full) -ne $rawSha) {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId raw original '$rawPath' SHA-256 does not match" $caseId
            }
        }
    }

    # External copy is mandatory so a later clean cannot destroy the only copy.
    $externalCopy = [string](Get-V2Prop $Record 'externalEvidenceCopy')
    $rawRoot = [string](Get-V2Prop $Record 'rawEvidenceRoot')
    if ($externalCopy.TrimEnd('\', '/') -eq $rawRoot.TrimEnd('\', '/')) {
        Add-V2Violation $Violations 'missing-external-copy' "case $caseId externalEvidenceCopy equals the raw evidence root; it is not an external copy" $caseId
    }

    # Fixture/config hashes must match both the pin and the actual bytes.
    $fixturePath = [string](Get-V2Prop $Record 'fixturePath')
    $fixtureSha = [string](Get-V2Prop $Record 'fixtureSha256')
    if ($fixturePath -and $fixtureSha) {
        $expectedFixture = [string](Get-V2Prop $Pins 'customersCsvSha256')
        if ([IO.Path]::GetFileName($fixturePath) -eq 'customers.csv') {
            $resolved = Resolve-V2EvidencePath -RepositoryRoot $RepositoryRoot -Path $fixturePath
            if ((Test-Path -LiteralPath $resolved -PathType Leaf)) {
                if ((Get-V2Sha256 $resolved) -ne $fixtureSha -or $fixtureSha -ne $expectedFixture) {
                    Add-V2Violation $Violations 'wrong-fixture-hash' "case $caseId fixture SHA-256 '$fixtureSha' does not match the pinned/actual customers.csv" $caseId
                }
            }
            else {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId fixture '$fixturePath' does not exist" $caseId
            }
        }
    }
    $configPath = [string](Get-V2Prop $Record 'configPath')
    $configSha = [string](Get-V2Prop $Record 'configSha256')
    if ($configPath -and $configSha) {
        if ([IO.Path]::GetFileName($configPath) -eq 'reporting-policy.json') {
            $expectedConfig = [string](Get-V2Prop $Pins 'reportingPolicySha256')
            $resolved = Resolve-V2EvidencePath -RepositoryRoot $RepositoryRoot -Path $configPath
            if ((Test-Path -LiteralPath $resolved -PathType Leaf)) {
                if ((Get-V2Sha256 $resolved) -ne $configSha -or $configSha -ne $expectedConfig) {
                    Add-V2Violation $Violations 'wrong-config-hash' "case $caseId config SHA-256 '$configSha' does not match the pinned/actual reporting-policy.json" $caseId
                }
            }
            else {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId config '$configPath' does not exist" $caseId
            }
        }
    }

    # Artifact download chain (M3/M4/M5).
    $downloadPath = [string](Get-V2Prop $Record 'downloadPath')
    if ($downloadPath) {
        $downloadSha = [string](Get-V2Prop $Record 'downloadSha256')
        $downloadBytes = Get-V2Prop $Record 'downloadByteCount'
        $fileSha = [string](Get-V2Prop $Record 'fileSha256')
        $downloadFull = Resolve-V2EvidencePath -RepositoryRoot $RepositoryRoot -Path $downloadPath
        if (-not (Test-Path -LiteralPath $downloadFull -PathType Leaf)) {
            Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId download '$downloadPath' is missing" $caseId
        }
        else {
            $actualSha = Get-V2Sha256 $downloadFull
            $actualBytes = (Get-Item -LiteralPath $downloadFull).Length
            if ($actualSha -ne $downloadSha) {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId download SHA-256 does not match the actual bytes" $caseId
            }
            if ($null -ne $downloadBytes -and [long]$downloadBytes -ne [long]$actualBytes) {
                Add-V2Violation $Violations 'field-inconsistency' "case $caseId downloadByteCount $downloadBytes does not match actual $actualBytes" $caseId
            }
            if ($fileSha -and $downloadSha -and $fileSha -ne $downloadSha) {
                Add-V2Violation $Violations 'field-inconsistency' "case $caseId fileSha256 and downloadSha256 disagree" $caseId
            }
        }
        $provenancePath = [string](Get-V2Prop $Record 'downloadProvenancePath')
        $provenanceSha = [string](Get-V2Prop $Record 'downloadProvenanceSha256')
        if ($provenancePath) {
            $provenanceFull = Resolve-V2EvidencePath -RepositoryRoot $RepositoryRoot -Path $provenancePath
            if (-not (Test-Path -LiteralPath $provenanceFull -PathType Leaf)) {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId provenance original '$provenancePath' is missing" $caseId
            }
            elseif ((Get-V2Sha256 $provenanceFull) -ne $provenanceSha) {
                Add-V2Violation $Violations 'missing-raw-evidence' "case $caseId provenance original SHA-256 does not match" $caseId
            }
            else {
                $provenance = Read-V2Json $provenanceFull
                if ([string](Get-V2Prop $provenance 'artifactId') -ne $artifactId) {
                    Add-V2Violation $Violations 'id-mismatch' "case $caseId provenance artifactId does not match the case artifactId" $caseId
                }
                if ([string](Get-V2Prop $provenance 'toolCallId') -ne $toolCallId) {
                    Add-V2Violation $Violations 'id-mismatch' "case $caseId provenance toolCallId does not match the case toolCallId" $caseId
                }
                $provDownloadSha = [string](Get-V2Prop $provenance 'downloadSha256')
                if ($provDownloadSha -and $provDownloadSha -ne $downloadSha) {
                    Add-V2Violation $Violations 'field-inconsistency' "case $caseId provenance downloadSha256 does not match the case downloadSha256" $caseId
                }
            }
        }
    }

    # L3 must never substitute an L2 stub and must only use a declared read-only tool.
    if ([string]$CaseMeta.level -eq 'L3') {
        if ((Get-V2Prop $Record 'l3StubSubstitution') -eq $true) {
            Add-V2Violation $Violations 'l3-l2-stub' "case $caseId is L3 but records l3StubSubstitution=true" $caseId
        }
        if (-not (Test-V2FieldPopulated (Get-V2Prop $Record 'mcpServerId')) -or -not (Test-V2FieldPopulated (Get-V2Prop $Record 'mcpTool'))) {
            Add-V2Violation $Violations 'l3-undeclared-tool' "case $caseId is L3 without a declared mcpServerId/mcpTool" $caseId
        }
        $calls = @((Get-V2Prop $Record 'mcpCalls'))
        if ($calls -contains 'tools/call') {
            Add-V2Violation $Violations 'tools-call-trace' "case $caseId contains a tools/call trace; only initialize/tools-list are authorized for discovery" $caseId
        }
        if (-not ($calls -contains 'initialize') -or -not ($calls -contains 'tools/list')) {
            Add-V2Violation $Violations 'missing-required-evidence' "case $caseId L3 mcpCalls must contain initialize and tools/list" $caseId
        }
    }

    # Port/process hygiene.
    if ((Get-V2Prop $Record 'portsReleased') -ne $true) {
        Add-V2Violation $Violations 'uncleaned' "case $caseId did not record released ports" $caseId
    }
    $exitStatus = Get-V2Prop $Record 'subprocessExitStatus'
    if ($null -ne $exitStatus -and [int]$exitStatus -ne 0) {
        Add-V2Violation $Violations 'nonzero-subprocess-exit' "case $caseId recorded subprocess exit status $exitStatus" $caseId
    }
}

# Case ids a human ruling deferred out of the GO denominator. The deferral lives
# in the oracle; a deferred case stays in every list and must still be reported,
# but only as NOT_RUN.
function Get-V2DeferredCaseIds {
    param([Parameter(Mandatory = $true)][object]$Oracle)
    $rulingIds = @(@(Get-V2Prop $Oracle 'rulings') | Where-Object { $null -ne $_ -and [string]$_.decidedBy -eq 'human' } | ForEach-Object { [string]$_.id })
    $ids = New-Object System.Collections.Generic.List[string]
    foreach ($case in @($Oracle.cases)) {
        $deferral = Get-V2Prop $case 'deferral'
        if ($null -eq $deferral) { continue }
        if ($rulingIds -notcontains [string]$deferral.rulingRef) { throw "V2 oracle case $($case.id) is deferred without a human ruling recorded in the oracle." }
        if ([string]$deferral.result -ne 'NOT_RUN') { throw "V2 oracle case $($case.id) deferral must stay NOT_RUN." }
        $ids.Add([string]$case.id)
    }
    return , $ids.ToArray()
}

function Test-V2EvidenceContract {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$ContractPath,
        [string]$OraclePath
    )
    if ([string]::IsNullOrWhiteSpace($ContractPath)) { $ContractPath = Get-V2ContractPath -RepositoryRoot $RepositoryRoot }
    if ([string]::IsNullOrWhiteSpace($OraclePath)) { $OraclePath = Get-V2OraclePath -RepositoryRoot $RepositoryRoot }
    $contractPath = $ContractPath
    $oraclePath = $OraclePath
    if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) { throw "V2 evidence contract is missing: $contractPath" }
    if (-not (Test-Path -LiteralPath $oraclePath -PathType Leaf)) { throw "V2 case oracle is missing: $oraclePath" }

    $contract = Read-V2Json $contractPath
    $oracle = Read-V2Json $oraclePath

    if ([string]$contract.schemaVersion -ne 'haifa.customer-demo-v2.evidence-contract/v1') { throw "Unexpected V2 evidence contract schemaVersion." }
    if ([string]$contract.demoId -ne 'DEMO-V2') { throw "V2 evidence contract must target DEMO-V2." }

    $oracleSha = Get-V2Sha256 $oraclePath
    $indexPath = Join-Path $RepositoryRoot ([string]$contract.generatedFrom.sourceIndex)
    if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) { throw "V2 evidence contract sourceIndex is missing." }
    $indexSha = Get-V2Sha256 $indexPath
    if ([string]$contract.generatedFrom.caseOracleSha256 -ne $oracleSha) { throw "V2 evidence contract caseOracleSha256 drifted from the actual oracle." }
    if ([string]$contract.generatedFrom.sourceIndexSha256 -ne $indexSha) { throw "V2 evidence contract sourceIndexSha256 drifted from the actual source index." }
    if ([string]$contract.pinnedHashes.caseOracleSha256 -ne $oracleSha) { throw "V2 evidence contract pinned oracle SHA drifted." }
    if ([string]$contract.pinnedHashes.sourceIndexSha256 -ne $indexSha) { throw "V2 evidence contract pinned source-index SHA drifted." }

    $csvPath = Join-Path $RepositoryRoot 'contracts/customer-demo-v2/fixtures/customers.csv'
    $policyPath = Join-Path $RepositoryRoot 'contracts/customer-demo-v2/fixtures/reporting-policy.json'
    if ((Get-V2Sha256 $csvPath) -ne [string]$contract.pinnedHashes.customersCsvSha256) { throw "V2 evidence contract customers.csv pin drifted." }
    if ((Get-V2Sha256 $policyPath) -ne [string]$contract.pinnedHashes.reportingPolicySha256) { throw "V2 evidence contract reporting-policy.json pin drifted." }

    foreach ($r in @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED', 'NOT_APPLICABLE')) {
        if (-not (@($contract.resultVocabulary) -contains $r)) { throw "V2 evidence contract resultVocabulary is missing $r." }
    }
    foreach ($d in @('GO', 'NO_GO')) {
        if (-not (@($contract.decisionVocabulary) -contains $d)) { throw "V2 evidence contract decisionVocabulary is missing $d." }
    }

    $oracleCases = @($oracle.cases)
    $oracleIds = @($oracleCases | ForEach-Object { [string]$_.id })
    $contractCases = @($contract.cases)
    if ([int]$contract.caseDenominator.count -ne $oracleIds.Count) { throw "V2 evidence contract denominator count does not match the oracle ($($contract.caseDenominator.count) vs $($oracleIds.Count))." }
    if ($contractCases.Count -ne $oracleIds.Count) { throw "V2 evidence contract case list does not match the oracle denominator." }

    $deferredIds = Get-V2DeferredCaseIds -Oracle $oracle
    $contractIds = @($contractCases | ForEach-Object { [string]$_.id })
    $missing = @($oracleIds | Where-Object { $contractIds -notcontains $_ })
    $extra = @($contractIds | Where-Object { $oracleIds -notcontains $_ })
    if ($missing.Count -gt 0) { throw "V2 evidence contract is missing oracle case(s): $($missing -join ', ')" }
    if ($extra.Count -gt 0) { throw "V2 evidence contract invents case(s) not in the oracle: $($extra -join ', ')" }
    if (@($contractIds | Select-Object -Unique).Count -ne $contractIds.Count) { throw "V2 evidence contract case ids are not unique." }

    foreach ($stage in $script:V2StageVocabulary) {
        $oracleCount = @($oracleCases | Where-Object { [string]$_.stage -eq $stage }).Count
        $declared = [int](Get-V2Prop $contract.caseDenominator.stageDistribution $stage)
        if ($declared -ne $oracleCount) { throw "V2 evidence contract stage distribution for $stage drifted ($declared vs $oracleCount)." }
    }

    foreach ($case in $contractCases) {
        $meta = $oracleCases | Where-Object { [string]$_.id -eq [string]$case.id }
        if ($null -eq $meta) { throw "V2 evidence contract case $($case.id) is not in the oracle." }
        if ([string]$case.stage -ne [string]$meta.stage) { throw "V2 evidence contract case $($case.id) stage drifted." }
        if ([string]$case.workPackage -ne [string]$meta.workPackage) { throw "V2 evidence contract case $($case.id) workPackage drifted." }
        if ([string]$case.level -ne [string]$meta.level) { throw "V2 evidence contract case $($case.id) level drifted." }
        if ([string]$case.referenceSupport -ne [string]$meta.reference.support) { throw "V2 evidence contract case $($case.id) referenceSupport drifted." }
        if ([string]$case.defaultResult -ne 'NOT_RUN') { throw "V2 evidence contract case $($case.id) must default to NOT_RUN." }
        $isDeferred = $deferredIds -contains [string]$case.id
        if ([bool]$case.requiredForStagePass -eq $isDeferred) { throw "V2 evidence contract case $($case.id) requiredForStagePass must be $(-not $isDeferred) to match the oracle deferral." }
    }
    $declaredDeferred = @(@(Get-V2Prop $contract.caseDenominator 'deferredCaseIds') | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ } | Sort-Object)
    if (($declaredDeferred -join ',') -ne (@($deferredIds | Sort-Object) -join ',')) { throw "V2 evidence contract caseDenominator.deferredCaseIds does not match the oracle deferrals." }

    $noGoIds = @($contract.noGoConditions | ForEach-Object { [string]$_.id })
    foreach ($required in $script:V2RequiredNoGoConditionIds) {
        if ($noGoIds -notcontains $required) { throw "V2 evidence contract is missing the no-go condition '$required'." }
    }
    if (-not (Test-V2FieldPopulated $contract.aggregation.goRule)) { throw "V2 evidence contract aggregation.goRule is missing." }
    if (-not (Test-V2FieldPopulated $contract.aggregation.stagePassRule)) { throw "V2 evidence contract aggregation.stagePassRule is missing." }
    if (-not (Test-V2FieldPopulated $contract.aggregation.forbiddenEvidence)) { throw "V2 evidence contract aggregation.forbiddenEvidence is missing." }

    return [pscustomobject]@{
        Contract    = $contract
        Oracle      = $oracle
        ContractSha = Get-V2Sha256 $contractPath
        OracleSha   = $oracleSha
        IndexSha    = $indexSha
        CaseCount   = $contractCases.Count
        DeferredIds = $deferredIds
    }
}

function Get-V2AggregatedStageResult {
    param([Parameter(Mandatory = $true)][object[]]$HaifaRecords)
    if (@($HaifaRecords | Where-Object { [string]$_.result -eq 'FAIL' }).Count -gt 0) { return 'FAIL' }
    if (@($HaifaRecords | Where-Object { [string]$_.result -eq 'BLOCKED_EXTERNAL' }).Count -gt 0) { return 'BLOCKED_EXTERNAL' }
    if (@($HaifaRecords | Where-Object { [string]$_.result -eq 'PASS' }).Count -eq $HaifaRecords.Count) { return 'PASS' }
    return 'NOT_RUN'
}

function Test-V2EvidenceInstance {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$InstancePath,
        [string]$ExpectedMainSha,
        [string]$ExpectedSdkBomSha256,
        [string]$ContractPath,
        [string]$OraclePath
    )

    $contractInfo = Test-V2EvidenceContract -RepositoryRoot $RepositoryRoot -ContractPath $ContractPath -OraclePath $OraclePath
    $contract = $contractInfo.Contract
    $oracle = $contractInfo.Oracle
    if (-not (Test-Path -LiteralPath $InstancePath -PathType Leaf)) { throw "V2 evidence instance is missing: $InstancePath" }
    $instance = Read-V2Json $InstancePath

    $violations = New-Object System.Collections.Generic.List[object]

    if ([string]$instance.schemaVersion -ne 'haifa.customer-demo-v2.evidence-instance/v1') { throw "Unexpected V2 evidence instance schemaVersion." }
    if ([string]$instance.demoId -ne 'DEMO-V2') { throw "V2 evidence instance must target DEMO-V2." }
    if ([string]$instance.contractRef -ne 'contracts/customer-demo-v2/v2-evidence-contract.json') { throw "V2 evidence instance contractRef is invalid." }
    if ([string]$instance.contractSha256 -ne $contractInfo.ContractSha) {
        Add-V2Violation $violations 'field-inconsistency' "instance contractSha256 does not match the committed V2 evidence contract"
    }
    if ([string]$instance.pins.contractSha256 -ne $contractInfo.ContractSha) {
        Add-V2Violation $violations 'field-inconsistency' "instance pins.contractSha256 does not match the committed V2 evidence contract"
    }
    if ([string]$instance.pins.caseOracleSha256 -ne $contractInfo.OracleSha) {
        Add-V2Violation $violations 'wrong-sha' "instance pins.caseOracleSha256 does not match the committed oracle"
    }
    if ([string]$instance.pins.sourceIndexSha256 -ne $contractInfo.IndexSha) {
        Add-V2Violation $violations 'wrong-sha' "instance pins.sourceIndexSha256 does not match the committed source index"
    }
    $csvPath = Join-Path $RepositoryRoot 'contracts/customer-demo-v2/fixtures/customers.csv'
    $policyPath = Join-Path $RepositoryRoot 'contracts/customer-demo-v2/fixtures/reporting-policy.json'
    if ([string]$instance.pins.customersCsvSha256 -ne (Get-V2Sha256 $csvPath)) {
        Add-V2Violation $violations 'wrong-fixture-hash' "instance pins.customersCsvSha256 does not match the frozen customers.csv"
    }
    if ([string]$instance.pins.reportingPolicySha256 -ne (Get-V2Sha256 $policyPath)) {
        Add-V2Violation $violations 'wrong-config-hash' "instance pins.reportingPolicySha256 does not match the frozen reporting-policy.json"
    }
    if ($ExpectedMainSha -and [string]$instance.pins.mainSha -ne $ExpectedMainSha) {
        Add-V2Violation $violations 'wrong-main-sha' "instance pins.mainSha '$($instance.pins.mainSha)' does not match expected '$ExpectedMainSha'"
    }
    if ($ExpectedSdkBomSha256 -and [string]$instance.pins.sdkBomSha256 -ne $ExpectedSdkBomSha256) {
        Add-V2Violation $violations 'wrong-sdk-sha' "instance pins.sdkBomSha256 does not match the consumed Haifa BOM"
    }

    $oracleCases = @($oracle.cases)
    $deferredIds = @($contractInfo.DeferredIds)
    $requiredCases = @($oracleCases | Where-Object { $deferredIds -notcontains [string]$_.id })
    $records = @($instance.caseResults)
    $haifaById = @{}
    $referenceById = @{}
    foreach ($record in $records) {
        $result = [string](Get-V2Prop $record 'result')
        if ($script:V2ResultVocabulary -notcontains $result) {
            Add-V2Violation $violations 'field-inconsistency' "case $($record.id) has an invalid result '$result'"
        }
        $backend = [string](Get-V2Prop $record 'backend')
        if ($backend -eq 'haifa') {
            if ($haifaById.ContainsKey([string]$record.id)) {
                Add-V2Violation $violations 'field-inconsistency' "case $($record.id) has duplicate haifa records" $record.id
            }
            else { $haifaById[[string]$record.id] = $record }
        }
        elseif ($backend -eq 'reference') {
            $referenceById[[string]$record.id] = $record
        }
        else {
            Add-V2Violation $violations 'field-inconsistency' "case $($record.id) has an invalid backend '$backend'" $record.id
        }
    }

    foreach ($case in $oracleCases) {
        $caseId = [string]$case.id
        if (-not $haifaById.ContainsKey($caseId)) {
            Add-V2Violation $violations 'missing-case' "instance omits the haifa record for case $caseId" $caseId
            continue
        }
        $record = $haifaById[$caseId]
        if ([string]$record.stage -ne [string]$case.stage) { Add-V2Violation $violations 'field-inconsistency' "case $caseId stage '$($record.stage)' does not match the oracle" $caseId }
        if ([string]$record.workPackage -ne [string]$case.workPackage) { Add-V2Violation $violations 'field-inconsistency' "case $caseId workPackage does not match the oracle" $caseId }
        if ([string]$record.level -ne [string]$case.level) { Add-V2Violation $violations 'field-inconsistency' "case $caseId level does not match the oracle" $caseId }

        $result = [string]$record.result
        if (($deferredIds -contains $caseId) -and $result -ne 'NOT_RUN') {
            Add-V2Violation $violations 'deferred-as-pass' "haifa case $caseId is deferred by a human ruling and may only be NOT_RUN (got '$result')" $caseId
        }
        if ($result -eq 'UPSTREAM_UNSUPPORTED' -or $result -eq 'NOT_APPLICABLE') {
            Add-V2Violation $violations 'upstream-unsupported-as-pass' "haifa case $caseId may not use result '$result'" $caseId
        }
        if ($result -eq 'PASS') {
            Test-V2PassCaseRecord -Record $record -CaseMeta $case -Contract $contract -RepositoryRoot $RepositoryRoot -Pins $instance.pins -Violations $violations
        }

        if ([string]$case.stage -eq 'V2-M5') {
            if (-not $referenceById.ContainsKey($caseId)) {
                Add-V2Violation $violations 'missing-case' "V2-M5 case $caseId is missing its reference record" $caseId
            }
            else {
                $refResult = [string]$referenceById[$caseId].result
                if ($refResult -eq 'NOT_APPLICABLE') {
                    # M5 requires a real reference outcome (PASS/FAIL/NOT_RUN/BLOCKED/UPSTREAM_UNSUPPORTED).
                    Add-V2Violation $violations 'field-inconsistency' "V2-M5 case $caseId reference result NOT_APPLICABLE is not a differential outcome" $caseId
                }
            }
        }
    }

    # Aggregate per stage and compare with the declared stage results.
    $declaredStages = @{}
    foreach ($stageResult in @($instance.stageResults)) {
        $declaredStages[[string]$stageResult.stage] = [string]$stageResult.result
    }
    $computedStage = @{}
    foreach ($stage in $script:V2StageVocabulary) {
        $stageHaifa = @($requiredCases | Where-Object { [string]$_.stage -eq $stage } | ForEach-Object { $haifaById[[string]$_.id] } | Where-Object { $null -ne $_ })
        $computed = if ($stageHaifa.Count -eq 0) { 'NOT_RUN' } else { Get-V2AggregatedStageResult -HaifaRecords $stageHaifa }
        $computedStage[$stage] = $computed
        if (-not $declaredStages.ContainsKey($stage)) {
            Add-V2Violation $violations 'missing-required-evidence' "instance is missing the stage result for $stage"
            continue
        }
        $declared = $declaredStages[$stage]
        if ($declared -eq 'PASS' -and $computed -ne 'PASS') {
            Add-V2Violation $violations 'not-run-as-pass' "stage $stage is declared PASS but its cases aggregate to $computed"
        }
        elseif ($declared -ne $computed) {
            Add-V2Violation $violations 'field-inconsistency' "stage $stage declared '$declared' but cases aggregate to '$computed'"
        }
    }

    $allStagesPass = $true
    foreach ($stage in $script:V2StageVocabulary) { if ($computedStage[$stage] -ne 'PASS') { $allStagesPass = $false } }
    $allCasesPass = (@($requiredCases | Where-Object { $haifaById.ContainsKey([string]$_.id) -and [string]$haifaById[[string]$_.id].result -eq 'PASS' }).Count -eq $requiredCases.Count)
    $declaration = $instance.humanPilotDeclaration
    $hasDeclaration = ($null -ne $declaration) -and (Test-V2FieldPopulated (Get-V2Prop $declaration 'signedBy')) -and (Test-V2FieldPopulated (Get-V2Prop $declaration 'signedAt')) -and (Test-V2FieldPopulated (Get-V2Prop $declaration 'scope')) -and (Test-V2FieldPopulated (Get-V2Prop $declaration 'result'))
    if (-not $hasDeclaration) {
        Add-V2Violation $violations 'missing-human-declaration' "V2 GO requires a signed human/客户 Pilot declaration"
    }
    if (-not (Test-V2FieldPopulated (Get-V2Prop $instance.pins 'mainSha'))) {
        Add-V2Violation $violations 'wrong-main-sha' "instance pins.mainSha is empty; every stage must share one main SHA"
    }

    $decision = if (($violations.Count -eq 0) -and $allStagesPass -and $allCasesPass -and $hasDeclaration) { 'GO' } else { 'NO_GO' }
    if ([string]$instance.decision -eq 'GO' -and $decision -ne 'GO') {
        Add-V2Violation $violations 'field-inconsistency' "instance self-reports GO but fail-closed aggregation is NO_GO"
    }

    return [pscustomobject]@{
        InstancePath = $InstancePath
        Decision     = $decision
        DeclaredDecision = [string]$instance.decision
        Violations   = $violations
        CaseResults  = $haifaById
        StageResults = $computedStage
    }
}
