[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# W24 V2 doctor/evidence fail-closed verifier.
#
# Positive paths:
#   - The V2 doctor runs in -StaticOnly mode and emits machine-readable checks.
#   - The committed V2 evidence contract matches the 48-case oracle.
#   - The blank M0 baseline instance is all NOT_RUN and aggregates to NO_GO.
#   - A fully-populated synthetic V2 instance aggregates to GO.
# Negative paths (must never produce GO / must be rejected):
#   missing case, wrong main/SDK SHA, wrong fixture/config hash, forged 0 Usage,
#   Tool/Artifact id mismatch, missing raw original, missing external copy,
#   L3-for-L2 stub, undeclared L3 tool, tools/call trace, hidden retry,
#   V1 evidence as V2, NOT_RUN-as-PASS, UPSTREAM_UNSUPPORTED-as-PASS, uncleaned
#   ports, non-zero subprocess exit, missing human declaration, contract
#   denominator tampering, and doctor L3 selection undecided.
# ---------------------------------------------------------------------------

. (Join-Path $PSScriptRoot '../..' 'scripts/lib/v2-evidence-contract.ps1')

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$doctor = Join-Path $root 'scripts/verify-customer-demo-v2-doctor.ps1'
$assertions = 0

$decisionsPath = Join-Path $root 'contracts/customer-demo-v2/v2-m0-decisions.json'
$decisions = Read-V2Json $decisionsPath
$l3SelectionRecord = Get-V2Prop $decisions 'decisions.l3McpSelection'
$recordedL3Tool = [string](Get-V2Prop $l3SelectionRecord 'tool')
$recordedL3Fact = [string](Get-V2Prop $l3SelectionRecord 'factSpec')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    $script:assertions++
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}
function Assert-Throws {
    param([scriptblock]$Action, [string]$Name)
    $script:assertions++
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (-not $threw) { throw "ASSERTION FAILED: negative '$Name' must fail but did not" }
}
function Get-JsonProperty {
    param([object]$Object, [string]$Name)
    return (Get-V2Prop $Object $Name)
}
function Invoke-DoctorJson {
    param([hashtable]$Arguments, [string]$OutFile)
    $splat = @{ RepositoryRoot = $root; OutputPath = $OutFile }
    foreach ($k in $Arguments.Keys) { $splat[$k] = $Arguments[$k] }
    & $doctor @splat | Out-Null
    if (-not (Test-Path -LiteralPath $OutFile -PathType Leaf)) { throw "doctor did not produce $OutFile" }
    return (Read-V2Json $OutFile)
}
function Get-Check {
    param([object]$Report, [string]$Id)
    return @($Report.checks | Where-Object { [string]$_.id -eq $Id })[0]
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("w24-v2-doctor-verify-" + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Force -Path $temp

function New-DecisionsFixture {
    param([string]$Name, [scriptblock]$Mutate)
    $clone = Get-Content -Raw -LiteralPath $decisionsPath | ConvertFrom-Json -Depth 40
    & $Mutate $clone
    $path = Join-Path $temp "decisions-$Name.json"
    [IO.File]::WriteAllText($path, ($clone | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
    return $path
}

try {
    # =====================================================================
    # 1. Doctor static positive + missing-parameter fail-closed behaviour.
    # =====================================================================
    $staticReport = Invoke-DoctorJson @{ StaticOnly = $true } (Join-Path $temp 'doctor-static.json')
    Assert-True ($staticReport.schemaVersion -eq 'haifa.customer-demo-v2.doctor-report/v1') 'doctor schemaVersion'
    Assert-True ($staticReport.demoId -eq 'DEMO-V2') 'doctor demoId'
    Assert-True ($staticReport.paidModelCalled -eq $false) 'doctor must not claim a paid model call'
    foreach ($id in @('super-harness-git', 'v2-case-baseline', 'evidence-contract', 'frozen-upstream', 'haifa-sdk',
            'toolchain', 'ports', 'data-root', 'continuation-key', 'live-model', 'l2-mcp-transport',
            'l3-mcp-discovery', 'l3-mcp-selection', 'w04-authorization', 'w03-w05-order', 'w16-dependency-review', 'shpm-database')) {
        Assert-True ($null -ne (Get-Check $staticReport $id)) "doctor must emit check $id"
    }
    Assert-True ($staticReport.overall -ne 'PASS') 'a static doctor with unrun live items must not be PASS'
    Assert-True ((Get-Check $staticReport 'haifa-sdk').status -eq 'NOT_RUN') 'missing -MavenRepository must be NOT_RUN, not PASS'
    Assert-True ((Get-Check $staticReport 'data-root').status -eq 'NOT_RUN') 'missing -DataDirectory must be NOT_RUN'
    Assert-True ((Get-Check $staticReport 'continuation-key').status -eq 'NOT_RUN') 'missing -ContinuationKeyRef must be NOT_RUN'
    Assert-True ((Get-Check $staticReport 'live-model').status -eq 'NOT_RUN') 'missing managed model source must be NOT_RUN'
    Assert-True ((Get-Check $staticReport 'l2-mcp-transport').status -eq 'NOT_RUN') 'L2 MCP must be NOT_RUN until W10 delivers'
    Assert-True ((Get-Check $staticReport 'shpm-database').status -eq 'NOT_RUN') 'missing SHPM db must be NOT_RUN'
    Assert-True ((Get-Check $staticReport 'w04-authorization').status -eq 'PASS') 'an AUTHORIZED W04 record must PASS'
    Assert-True ((Get-Check $staticReport 'w04-authorization').status -ne 'FAIL') 'W04 authorization gate is not a doctor FAIL'
    Assert-True ((Get-Check $staticReport 'w04-authorization').detail -match 'W04 whole-package PASS') 'AUTHORIZED W04 detail must state the bounded scope (slice PASS != whole-package PASS)'
    Assert-True ((Get-Check $staticReport 'l3-mcp-selection').status -eq 'NOT_RUN') 'without a discovery result the recorded L3 tool cannot be confirmed -> NOT_RUN'
    Assert-True ((Get-Check $staticReport 'v2-case-baseline').status -eq 'PASS') 'v2 case baseline must PASS'
    Assert-True ((Get-Check $staticReport 'evidence-contract').status -eq 'PASS') 'evidence contract must PASS'

    # =====================================================================
    # 1b. W04 authorization follows the recorded value: NOT_AUTHORIZED ->
    #     BLOCKED_EXTERNAL, unknown/missing -> FAIL.
    # =====================================================================
    $w04NotAuthorized = New-DecisionsFixture 'w04-not-authorized' { param($d) $d.decisions.w04RecoveryAuthorization.decision = 'NOT_AUTHORIZED' }
    $w04NotAuthReport = Invoke-DoctorJson @{ StaticOnly = $true; V2DecisionsPath = $w04NotAuthorized } (Join-Path $temp 'doctor-w04-not-authorized.json')
    Assert-True ((Get-Check $w04NotAuthReport 'w04-authorization').status -eq 'BLOCKED_EXTERNAL') 'NOT_AUTHORIZED W04 must be BLOCKED_EXTERNAL'
    Assert-True ((Get-Check $w04NotAuthReport 'w04-authorization').status -ne 'FAIL') 'NOT_AUTHORIZED W04 is a recorded gate, not a FAIL'

    $w04Unknown = New-DecisionsFixture 'w04-unknown' { param($d) $d.decisions.w04RecoveryAuthorization.decision = 'MAYBE_LATER' }
    $w04UnknownReport = Invoke-DoctorJson @{ StaticOnly = $true; V2DecisionsPath = $w04Unknown } (Join-Path $temp 'doctor-w04-unknown.json')
    Assert-True ((Get-Check $w04UnknownReport 'w04-authorization').status -eq 'FAIL') 'an unknown W04 decision value must FAIL'
    Assert-True ($w04UnknownReport.overall -eq 'FAIL') 'an unknown W04 decision must make the overall FAIL'

    $w04Missing = New-DecisionsFixture 'w04-missing' { param($d) $d.decisions.w04RecoveryAuthorization = $null }
    $w04MissingReport = Invoke-DoctorJson @{ StaticOnly = $true; V2DecisionsPath = $w04Missing } (Join-Path $temp 'doctor-w04-missing.json')
    Assert-True ((Get-Check $w04MissingReport 'w04-authorization').status -eq 'FAIL') 'a missing W04 decision must FAIL'
    Assert-True ($w04MissingReport.overall -eq 'FAIL') 'a missing W04 decision must make the overall FAIL'

    # =====================================================================
    # 2. Doctor L3 discovery from an offline tools/list fixture.
    # =====================================================================
    $sampleFixture = Join-Path $root 'tests/customer-demo-v2/fixtures/l3-mcp-tools-list.sample.json'
    $l3Report = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture } (Join-Path $temp 'doctor-l3.json')
    $l3 = Get-Check $l3Report 'l3-mcp-discovery'
    Assert-True ($l3.status -eq 'NOT_RUN') 'offline L3 fixture without a live initialize must be NOT_RUN'
    Assert-True ([int]$l3.evidence.toolCount -ge 1) 'L3 fixture tools must be parsed'
    Assert-True ($l3.evidence.toolsCallTrace -eq $false) 'sample fixture must not contain a tools/call trace'
    Assert-True ($recordedL3Tool -eq 'calculate') "the committed decision record must authorize 'calculate' (got '$recordedL3Tool')"
    Assert-True (-not [string]::IsNullOrWhiteSpace($recordedL3Fact)) 'the committed decision record must carry a factSpec'
    Assert-True ((Get-Check $l3Report 'l3-mcp-selection').status -eq 'PASS') 'the recorded calculate tool present with readOnlyHint=true must PASS without explicit params'
    Assert-True ($l3Report.overall -ne 'PASS') 'doctor must not PASS while other live items are NOT_RUN'

    # Explicit parameters must agree with the recorded tool/factSpec.
    $selectedReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; L3McpSelectedTool = $recordedL3Tool; L3McpFactSpec = $recordedL3Fact } (Join-Path $temp 'doctor-l3-selected.json')
    Assert-True ((Get-Check $selectedReport 'l3-mcp-selection').status -eq 'PASS') 'explicit parameters equal to the record must PASS'
    $badToolReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; L3McpSelectedTool = 'currency_convert'; L3McpFactSpec = $recordedL3Fact } (Join-Path $temp 'doctor-l3-badtool.json')
    Assert-True ((Get-Check $badToolReport 'l3-mcp-selection').status -eq 'FAIL') 'an explicit tool disagreeing with the record must FAIL'
    $badFactReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; L3McpSelectedTool = $recordedL3Tool; L3McpFactSpec = 'some-other-fact' } (Join-Path $temp 'doctor-l3-badfact.json')
    Assert-True ((Get-Check $badFactReport 'l3-mcp-selection').status -eq 'FAIL') 'an explicit factSpec disagreeing with the record must FAIL'
    $badSelectReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; L3McpSelectedTool = 'not_a_tool'; L3McpFactSpec = 'x' } (Join-Path $temp 'doctor-l3-badselect.json')
    Assert-True ((Get-Check $badSelectReport 'l3-mcp-selection').status -eq 'FAIL') 'an undeclared L3 tool must FAIL'

    # Missing decision record -> PENDING_HUMAN_DECISION.
    $noL3Record = New-DecisionsFixture 'l3-missing' { param($d) $d.decisions.l3McpSelection = $null }
    $noL3RecordReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; V2DecisionsPath = $noL3Record } (Join-Path $temp 'doctor-l3-norecord.json')
    Assert-True ((Get-Check $noL3RecordReport 'l3-mcp-selection').status -eq 'PENDING_HUMAN_DECISION') 'a missing l3McpSelection record must be PENDING_HUMAN_DECISION'

    # Recorded tool absent from discovery -> FAIL.
    $absentTool = New-DecisionsFixture 'l3-absent-tool' { param($d) $d.decisions.l3McpSelection.tool = 'no_such_tool' }
    $absentToolReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $sampleFixture; V2DecisionsPath = $absentTool } (Join-Path $temp 'doctor-l3-absent.json')
    Assert-True ((Get-Check $absentToolReport 'l3-mcp-selection').status -eq 'FAIL') 'a recorded tool absent from discovery must FAIL'

    # Recorded tool present but readOnlyHint != true -> FAIL.
    $readOnlyFalseFixture = Join-Path $root 'tests/customer-demo-v2/fixtures/l3-mcp-tools-list.readonly-false.json'
    $readOnlyFalseReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $readOnlyFalseFixture } (Join-Path $temp 'doctor-l3-readonly-false.json')
    Assert-True ((Get-Check $readOnlyFalseReport 'l3-mcp-selection').status -eq 'FAIL') 'a recorded tool without readOnlyHint=true must FAIL'

    # A tools/call trace in the discovery fixture must FAIL the doctor.
    $callFixture = Join-Path $root 'tests/customer-demo-v2/fixtures/l3-mcp-tools-list.tools-call.json'
    $callReport = Invoke-DoctorJson @{ StaticOnly = $true; L3McpToolsFixture = $callFixture } (Join-Path $temp 'doctor-tools-call.json')
    Assert-True ((Get-Check $callReport 'l3-mcp-discovery').status -eq 'FAIL') 'a tools/call trace must FAIL L3 discovery'
    Assert-True ($callReport.overall -eq 'FAIL') 'a doctor with a tools/call trace must be FAIL'

    # =====================================================================
    # 3. Contract positive.
    # =====================================================================
    $contractInfo = Test-V2EvidenceContract -RepositoryRoot $root
    $oracle = $contractInfo.Oracle
    $contract = $contractInfo.Contract
    Assert-True ($contractInfo.CaseCount -eq 48) "contract must bind all 48 oracle cases (got $($contractInfo.CaseCount))"
    $oracleIds = @($oracle.cases | ForEach-Object { [string]$_.id } | Sort-Object)
    $contractIds = @($contract.cases | ForEach-Object { [string]$_.id } | Sort-Object)
    Assert-True (($oracleIds -join ',') -eq ($contractIds -join ',')) 'contract case ids must equal the oracle denominator'

    # =====================================================================
    # 4. Blank M0 baseline -> NOT_RUN / NO_GO.
    # =====================================================================
    $baselinePath = Join-Path $root 'contracts/customer-demo-v2/evidence/v2-m0-baseline.json'
    $baselineResult = Test-V2EvidenceInstance -RepositoryRoot $root -InstancePath $baselinePath
    Assert-True ($baselineResult.DeclaredDecision -eq 'NO_GO') 'blank M0 baseline must declare NO_GO'
    Assert-True ($baselineResult.Decision -eq 'NO_GO') 'blank M0 baseline must aggregate to NO_GO'
    Assert-True (@($baselineResult.CaseResults.Values | Where-Object { $_.result -eq 'NOT_RUN' }).Count -eq 48) 'blank M0 baseline must be all NOT_RUN'
    $baselinePinViolations = @($baselineResult.Violations | Where-Object { @('field-inconsistency', 'wrong-sha', 'wrong-fixture-hash', 'wrong-config-hash', 'deferred-as-pass') -contains [string]$_.id })
    Assert-True ($baselinePinViolations.Count -eq 0) "re-frozen M0 baseline must pin the committed contract/oracle/fixtures (got: $(@($baselinePinViolations | ForEach-Object { $_.detail }) -join '; '))"
    $deferredIds = @($contractInfo.DeferredIds | Sort-Object)
    Assert-True (($deferredIds -join ',') -eq 'V2-M1-06,V2-M3-09') "human-ruled deferred cases must be exactly V2-M1-06,V2-M3-09 (got: $($deferredIds -join ','))"

    # =====================================================================
    # 5. Synthetic fully-populated instance -> GO.
    # =====================================================================
    $csvSha = Get-V2Sha256 (Join-Path $root 'contracts/customer-demo-v2/fixtures/customers.csv')
    $policySha = Get-V2Sha256 (Join-Path $root 'contracts/customer-demo-v2/fixtures/reporting-policy.json')
    $indexSha = Get-V2Sha256 (Join-Path $root 'contracts/customer-demo-v2/source-index.json')
    $oracleSha = Get-V2Sha256 (Join-Path $root 'contracts/customer-demo-v2/v2-case-oracle.json')
    $contractSha = Get-V2Sha256 (Join-Path $root 'contracts/customer-demo-v2/v2-evidence-contract.json')
    $headSha = ((& git -C $root rev-parse HEAD).Trim())
    $sdkPin = ('a' * 64)

    $rawDir = Join-Path $temp 'evidence/raw'
    $extDir = Join-Path $temp 'evidence/external'
    $downloadDir = Join-Path $temp 'evidence/downloads'
    $provDir = Join-Path $temp 'evidence/provenance'
    foreach ($d in @($rawDir, $extDir, $downloadDir, $provDir)) { $null = New-Item -ItemType Directory -Force -Path $d }
    $rawFile = Join-Path $rawDir 'http-run.json'
    [IO.File]::WriteAllText($rawFile, "{""runId"":""synthetic""}`n", [Text.UTF8Encoding]::new($false))
    $rawSha = Get-V2Sha256 $rawFile

    $caseRecords = New-Object System.Collections.Generic.List[object]
    foreach ($case in $oracle.cases) {
        $id = [string]$case.id
        $record = [ordered]@{
            id = $id; stage = [string]$case.stage; workPackage = [string]$case.workPackage; level = [string]$case.level
            backend = 'haifa'; result = 'PASS'; source = 'v2'; profile = 'haifa-v2-contract'; scenario = 'golden-scenario'
            fixturePath = 'contracts/customer-demo-v2/fixtures/customers.csv'; fixtureSha256 = $csvSha
            configPath = 'contracts/customer-demo-v2/fixtures/reporting-policy.json'; configSha256 = $policySha
            runId = "run-$id"; runAttempt = 1; startedAt = '2026-09-28T00:00:00Z'; endedAt = '2026-09-28T00:00:01Z'
            coldMs = 10; warmMs = 5; totalMs = 15; retryCount = 0; hiddenRetry = $false; retryFirstCause = $null
            rawEvidenceRoot = $rawDir; rawFiles = @([ordered]@{ path = $rawFile; sha256 = $rawSha })
            externalEvidenceCopy = $extDir
            modelId = 'main-deepseek'; modelProvider = 'deepseek'; usageKnown = $true; usageTotalTokens = 100
            portsReleased = $true; subprocessExitStatus = 0
        }
        if (@('V2-M3', 'V2-M4', 'V2-M5') -contains [string]$case.stage) {
            $artifactId = "artifact-$id"; $toolCallId = "tool-$id"
            $downloadFile = Join-Path $downloadDir "$id.txt"
            [IO.File]::WriteAllText($downloadFile, "customer-summary $id`n", [Text.UTF8Encoding]::new($false))
            $downloadSha = Get-V2Sha256 $downloadFile
            $downloadBytes = (Get-Item -LiteralPath $downloadFile).Length
            $provFile = Join-Path $provDir "$id.json"
            [IO.File]::WriteAllText($provFile, ([ordered]@{ artifactId = $artifactId; toolCallId = $toolCallId; downloadSha256 = $downloadSha; runId = "run-$id" } | ConvertTo-Json -Compress) + "`n", [Text.UTF8Encoding]::new($false))
            $provSha = Get-V2Sha256 $provFile
            $record.toolCallId = $toolCallId; $record.artifactId = $artifactId
            $record.provenanceArtifactId = $artifactId; $record.toolCallIdInProvenance = $toolCallId
            $record.downloadPath = $downloadFile; $record.downloadSha256 = $downloadSha; $record.downloadByteCount = $downloadBytes
            $record.downloadProvenancePath = $provFile; $record.downloadProvenanceSha256 = $provSha; $record.fileSha256 = $downloadSha
        }
        if ([string]$case.stage -eq 'V2-M5') { $record.referenceResult = 'PASS' }
        # A human-ruled deferred case is reported but may only be NOT_RUN.
        if ($deferredIds -contains $id) { $record.result = 'NOT_RUN' }
        if ([string]$case.level -eq 'L3') {
            $record.interactionId = "interaction-$id"; $record.mcpServerId = 'utility-mcp'; $record.mcpTool = 'time_now'
            $record.mcpCalls = @('initialize', 'tools/list'); $record.l3StubSubstitution = $false
        }
        [void]$caseRecords.Add($record)
    }

    # M5 differential requires a separate reference record per case.
    $referenceRecords = New-Object System.Collections.Generic.List[object]
    foreach ($case in @($oracle.cases | Where-Object { [string]$_.stage -eq 'V2-M5' })) {
        $refResult = switch ([string]$case.reference.support) {
            'SUPPORTED' { 'PASS' }
            'PARTIAL' { 'PASS' }
            'UPSTREAM_UNSUPPORTED' { 'UPSTREAM_UNSUPPORTED' }
            default { 'PASS' }
        }
        [void]$referenceRecords.Add([ordered]@{
            id = [string]$case.id; stage = [string]$case.stage; workPackage = [string]$case.workPackage
            level = [string]$case.level; backend = 'reference'; result = $refResult
        })
    }
    foreach ($refRecord in $referenceRecords) { [void]$caseRecords.Add($refRecord) }

    $baseInstance = [ordered]@{
        schemaVersion = 'haifa.customer-demo-v2.evidence-instance/v1'; demoId = 'DEMO-V2'
        contractRef = 'contracts/customer-demo-v2/v2-evidence-contract.json'; contractSha256 = $contractSha
        generatedAt = '2026-09-28T00:00:00Z'
        pins = [ordered]@{ mainSha = $headSha; sdkCommit = 'synthetic-sdk-commit'; sdkBomSha256 = $sdkPin
            customersCsvSha256 = $csvSha; reportingPolicySha256 = $policySha; caseOracleSha256 = $oracleSha
            sourceIndexSha256 = $indexSha; contractSha256 = $contractSha }
        humanPilotDeclaration = [ordered]@{ signedBy = 'human-reviewer'; signedAt = '2026-09-28T01:00:00Z'; scope = 'V2-M6 golden scenario Pilot'; result = 'accepted' }
        stageResults = @([ordered]@{ stage = 'V2-M1'; result = 'PASS' }, [ordered]@{ stage = 'V2-M2'; result = 'PASS' },
            [ordered]@{ stage = 'V2-M3'; result = 'PASS' }, [ordered]@{ stage = 'V2-M4'; result = 'PASS' },
            [ordered]@{ stage = 'V2-M5'; result = 'PASS' }, [ordered]@{ stage = 'V2-M6'; result = 'PASS' })
        caseResults = $caseRecords
        decision = 'GO'; reason = 'synthetic positive instance'
    }
    $baseInstanceJson = $baseInstance | ConvertTo-Json -Depth 40
    $baseInstancePath = Join-Path $temp 'instance-go.json'
    [IO.File]::WriteAllText($baseInstancePath, $baseInstanceJson, [Text.UTF8Encoding]::new($false))
    $goResult = Test-V2EvidenceInstance -RepositoryRoot $root -InstancePath $baseInstancePath -ExpectedMainSha $headSha -ExpectedSdkBomSha256 $sdkPin
    Assert-True ($goResult.Decision -eq 'GO') "synthetic complete instance must aggregate to GO (violations: $(@($goResult.Violations | ForEach-Object { $_.id }) -join ','))"

    # =====================================================================
    # 6. Negative instance mutations -> NO_GO with the expected violation.
    # =====================================================================
    function Assert-NoGo {
        param([scriptblock]$Mutate, [string]$ViolationId, [string]$Name)
        $clone = $baseInstanceJson | ConvertFrom-Json -Depth 40
        & $Mutate $clone
        $path = Join-Path $temp "$Name.json"
        [IO.File]::WriteAllText($path, ($clone | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
        $res = Test-V2EvidenceInstance -RepositoryRoot $root -InstancePath $path -ExpectedMainSha $headSha -ExpectedSdkBomSha256 $sdkPin
        Assert-True ($res.Decision -eq 'NO_GO') "$Name must aggregate to NO_GO"
        $ids = @($res.Violations | ForEach-Object { [string]$_.id })
        Assert-True ($ids -contains $ViolationId) "$Name must record violation '$ViolationId' (got: $($ids -join ','))"
    }
    function Get-HaifaRecord {
        param([object]$Instance, [string]$CaseId)
        return @($Instance.caseResults | Where-Object { [string]$_.id -eq $CaseId -and [string]$_.backend -eq 'haifa' })[0]
    }

    Assert-NoGo { param($i) $i.caseResults = @($i.caseResults | Where-Object { [string]$_.id -ne 'V2-M1-01' }) } 'missing-case' 'missing-case'
    Assert-NoGo { param($i) $i.pins.mainSha = ('0' * 40) } 'wrong-main-sha' 'wrong-main-sha'
    Assert-NoGo { param($i) $i.pins.sdkBomSha256 = ('f' * 64) } 'wrong-sdk-sha' 'wrong-sdk-sha'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').fixtureSha256 = ('0' * 64) } 'wrong-fixture-hash' 'wrong-fixture-hash'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').configSha256 = ('0' * 64) } 'wrong-config-hash' 'wrong-config-hash'
    Assert-NoGo { param($i) $r = Get-HaifaRecord $i 'V2-M6-01'; $r.usageKnown = $false; $r.usageTotalTokens = 0 } 'forged-zero-usage' 'forged-zero-usage'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M3-01').provenanceArtifactId = 'different-artifact' } 'id-mismatch' 'id-mismatch'
    Assert-NoGo { param($i) $r = Get-HaifaRecord $i 'V2-M1-01'; $r.rawFiles = @([ordered]@{ path = 'does/not/exist.json'; sha256 = $rawSha }) } 'missing-raw-evidence' 'missing-raw-evidence'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').externalEvidenceCopy = $rawDir } 'missing-external-copy' 'missing-external-copy'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M6-01').l3StubSubstitution = $true } 'l3-l2-stub' 'l3-l2-stub'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M6-01').mcpTool = '' } 'l3-undeclared-tool' 'l3-undeclared-tool'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M6-01').mcpCalls = @('initialize', 'tools/list', 'tools/call') } 'tools-call-trace' 'tools-call-trace'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').hiddenRetry = $true } 'hidden-retry' 'hidden-retry'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').source = 'v1' } 'v1-evidence-as-v2' 'v1-evidence-as-v2'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').result = 'NOT_RUN' } 'not-run-as-pass' 'not-run-as-pass'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').result = 'UPSTREAM_UNSUPPORTED' } 'upstream-unsupported-as-pass' 'upstream-unsupported-as-pass'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').portsReleased = $false } 'uncleaned' 'uncleaned'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-01').subprocessExitStatus = 1 } 'nonzero-subprocess-exit' 'nonzero-subprocess-exit'
    Assert-NoGo { param($i) $i.humanPilotDeclaration = $null } 'missing-human-declaration' 'missing-human-declaration'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M1-06').result = 'PASS' } 'deferred-as-pass' 'deferred-as-pass'
    Assert-NoGo { param($i) (Get-HaifaRecord $i 'V2-M3-09').result = 'FAIL' } 'deferred-as-pass' 'deferred-reported-as-run'
    Assert-NoGo { param($i) $i.caseResults = @($i.caseResults | Where-Object { [string]$_.id -ne 'V2-M1-06' }) } 'missing-case' 'deferred-case-omitted'

    # =====================================================================
    # 7. Contract tampering -> rejected.
    # =====================================================================
    $goodContractText = Get-Content -Raw -LiteralPath (Join-Path $root 'contracts/customer-demo-v2/v2-evidence-contract.json')
    function Assert-ContractRejected {
        param([scriptblock]$Mutate, [string]$Name)
        $c = $goodContractText | ConvertFrom-Json -Depth 40
        & $Mutate $c
        $p = Join-Path $temp "$Name.contract.json"
        [IO.File]::WriteAllText($p, ($c | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
        Assert-Throws { Test-V2EvidenceContract -RepositoryRoot $root -ContractPath $p } $Name
    }
    Assert-ContractRejected { param($c) $c.cases = @($c.cases | Where-Object { [string]$_.id -ne 'V2-M1-01' }) } 'contract-missing-case'
    Assert-ContractRejected { param($c) $fake = $c.cases[0] | Select-Object *; $fake.id = 'V2-M9-99'; $c.cases = @($c.cases) + @($fake) } 'contract-invented-case'
    Assert-ContractRejected { param($c) ($c.cases | Where-Object { [string]$_.id -eq 'V2-M1-01' }).stage = 'V2-M6' } 'contract-restaged-case'
    Assert-ContractRejected { param($c) $c.pinnedHashes.customersCsvSha256 = ('0' * 64) } 'contract-fixture-pin'
    Assert-ContractRejected { param($c) $c.generatedFrom.caseOracleSha256 = ('0' * 64) } 'contract-oracle-pin'
    Assert-ContractRejected { param($c) $c.noGoConditions = @($c.noGoConditions | Where-Object { [string]$_.id -ne 'hidden-retry' }) } 'contract-missing-nogo'
    Assert-ContractRejected { param($c) ($c.cases | Where-Object { [string]$_.id -eq 'V2-M1-06' }).requiredForStagePass = $true } 'contract-deferred-marked-required'
    Assert-ContractRejected { param($c) ($c.cases | Where-Object { [string]$_.id -eq 'V2-M1-01' }).requiredForStagePass = $false } 'contract-undeferred-marked-optional'
    Assert-ContractRejected { param($c) $c.caseDenominator.deferredCaseIds = @('V2-M1-06') } 'contract-deferred-list-drift'
    Assert-ContractRejected { param($c) $c.noGoConditions = @($c.noGoConditions | Where-Object { [string]$_.id -ne 'deferred-as-pass' }) } 'contract-missing-deferred-nogo'

    # =====================================================================
    # 8. Fixture SHA baseline matches the frozen README oracle.
    # =====================================================================
    Assert-True ($csvSha -eq 'ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00') 'customers.csv SHA must equal the frozen oracle'
    Assert-True ($policySha -eq '563e216fc77f87d968b3c34994746fa1f3368380ede30c97e03782c3895a9e9a') 'reporting-policy.json SHA must equal the frozen oracle'

    Write-Host "W24 V2 doctor/evidence fail-closed verifier passed: $assertions assertions."
}
finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
}
