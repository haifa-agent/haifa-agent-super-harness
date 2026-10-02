[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# W00 V2 contract/oracle verifier.
#
# Positive checks:
#   1. Fixture SHA-256 for customers.csv and reporting-policy.json.
#   2. Independent CSV oracle recomputation (row 17, region totals, high rows).
#   3. Case oracle completeness / coverage / result vocabulary.
#   4. Reference source traceability: every UPSTREAM_UNSUPPORTED/NOT_INVENTORIED
#      case/assertion cites real frozen source (existence + in-range line), and
#      approval-class cases cite the reference interaction implementation.
#   5. Source index files exist and SHA-256 matches (snapshot + frontend + contracts).
#   6. MCP and Skill collection semantics are not narrowed.
#   7. consulting-analysis dependency review verdict is fail-closed.
#   8. Human-ruled deferrals: exactly the approved set, each backed by a human
#      ruling in the oracle, never PASS, and the oracle revision is recorded.
# Negative self-tests deliberately corrupt a copy and require the corresponding
# check to FAIL.
# ---------------------------------------------------------------------------

$ContractRoot = Join-Path $RepositoryRoot 'contracts/customer-demo-v2'
$SnapshotRoot = Join-Path $RepositoryRoot 'local-tmp/slot-1-w00-v2-oracle/upstream-snapshot'
$Assertions = 0
$SkippedChecks = 0

# Approval/rejection/interaction case ids. When one of these is declared
# UPSTREAM_UNSUPPORTED it MUST cite the reference's actual interaction
# implementation (clarification_middleware.py / interaction_policy.py), not just
# a generic run route: the original R1 defect cited only thread_runs.py:945 (the
# create_run decorator) while claiming "no approval semantics".
$ApprovalCaseIds = @('V2-M3-01', 'V2-M3-02', 'V2-M3-06', 'V2-M3-07', 'V2-M3-08', 'V2-M3-09', 'V2-M4-01', 'V2-M4-03')

$CsvPath = Join-Path $ContractRoot 'fixtures/customers.csv'
$PolicyPath = Join-Path $ContractRoot 'fixtures/reporting-policy.json'
$OraclePath = Join-Path $ContractRoot 'v2-case-oracle.json'
$IndexPath = Join-Path $ContractRoot 'source-index.json'
$McpPath = Join-Path $ContractRoot 'fixtures/mcp-servers.contract.json'
$SkillPath = Join-Path $ContractRoot 'fixtures/skills.contract.json'
$DepReviewPath = Join-Path $ContractRoot 'consulting-analysis-dependency-review.json'
$TempRoot = Join-Path ([IO.Path]::GetTempPath()) ("w00-v2-oracle-" + [Guid]::NewGuid().ToString('N'))

$ExpectedCsvSha = 'ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00'
$ExpectedPolicySha = '563e216fc77f87d968b3c34994746fa1f3368380ede30c97e03782c3895a9e9a'
$FrozenCommit = 'e545c28ac301e6f9feae247c513d8ea8f83df294'

# The only assertions a human has deferred out of the V2 denominator
# (show-roadmap-2/README.md section 2.2, ruling 2026-09-29). Any other deferral
# is an unapproved change of the denominator and must fail.
$ApprovedDeferrals = @('V2-M1-04-N2', 'V2-M1-06', 'V2-M3-09')
$PreviousOracleSha = '7dcfcb08806ed465e7475bb42ce824a58934d1234eb23f5d0e5816a8920e1191'

function Assert-True([bool]$Condition, [string]$Message) {
    $script:Assertions++
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-Json([string]$Path) {
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 64 -DateKind String)
}

function Get-Prop([object]$Object, [string]$Name) {
    $p = $Object.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

# Resolve one sourceRef path to a real file. `deer-flow/...` refs live in the
# read-only upstream snapshot; `frontend/...` and everything else are committed
# in this worktree.
function Resolve-RefFile([string]$RefPath) {
    if ($RefPath.StartsWith('deer-flow/')) {
        $rel = $RefPath.Substring('deer-flow/'.Length)
        return [pscustomobject]@{ FullPath = (Join-Path $SnapshotRoot $rel); SnapshotBacked = $true }
    }
    return [pscustomobject]@{ FullPath = (Join-Path $RepositoryRoot $RefPath); SnapshotBacked = $false }
}

# Existence/line-range check for one sourceRef. Snapshot-backed refs are
# SKIPPED (not counted as PASS) when the snapshot is absent, so this verifier
# stays valid in a clean/CI checkout without local-tmp.
function Assert-RefExists([string]$CaseId, [string]$RefPath, [int]$Line, [bool]$SnapshotAvailable, [switch]$IsAssertion) {
    $resolved = Resolve-RefFile $RefPath
    $label = if ($IsAssertion) { 'unsupported assertion sourceRef' } else { 'sourceRef' }
    if ($resolved.SnapshotBacked -and -not $SnapshotAvailable) {
        $script:SkippedChecks++
        Write-Host "SKIPPED(snapshot absent): case $CaseId $label $RefPath`:$Line" -ForegroundColor DarkYellow
        return
    }
    Assert-True (Test-Path -LiteralPath $resolved.FullPath -PathType Leaf) "case $CaseId $label path does not exist: $RefPath"
    $lineCount = [IO.File]::ReadAllLines($resolved.FullPath).Count
    Assert-True ($Line -le $lineCount) "case $CaseId $label line $Line exceeds $RefPath line count $lineCount"
}

# -- 1. Fixture hashes -------------------------------------------------------
function Test-FixtureHashes {
    Assert-True ((Get-Sha256 $CsvPath) -eq $ExpectedCsvSha) "customers.csv SHA-256 must match the README oracle"
    Assert-True ((Get-Sha256 $PolicyPath) -eq $ExpectedPolicySha) "reporting-policy.json SHA-256 must match the README oracle"
}

# -- 2. Independent CSV oracle ----------------------------------------------
function Test-CsvOracle {
    param([string]$Path)
    $lines = [IO.File]::ReadAllLines($Path)
    Assert-True ($lines.Count -eq 21) "customers.csv must have a header plus 20 data rows (got $($lines.Count) lines)"
    Assert-True ($lines[0] -eq 'customer_id,region,amount_cny') "customers.csv header must be exactly customer_id,region,amount_cny"
    $rows = @()
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $parts = $lines[$i].Split(',')
        Assert-True ($parts.Count -eq 3) "row $i must have 3 fields"
        $rows += [pscustomobject]@{ Row = $i; customer_id = $parts[0]; region = $parts[1]; amount = [int]$parts[2] }
    }
    Assert-True ($rows.Count -eq 20) "expected 20 data rows"
    Assert-True ($rows[16].customer_id -eq 'C017') "data row 17 must be C017 (0-based index 16)"
    Assert-True ($rows[16].region -eq 'East') "data row 17 region must be East"
    Assert-True ($rows[16].amount -eq 1700) "data row 17 amount must be 1700"
    $east = ($rows | Where-Object { $_.region -eq 'East' } | Measure-Object -Property amount -Sum).Sum
    $west = ($rows | Where-Object { $_.region -eq 'West' } | Measure-Object -Property amount -Sum).Sum
    $total = ($rows | Measure-Object -Property amount -Sum).Sum
    Assert-True ($east -eq 10000) "East total must be 10000 (got $east)"
    Assert-True ($west -eq 11000) "West total must be 11000 (got $west)"
    Assert-True ($total -eq 21000) "grand total must be 21000 (got $total)"
    $high = @($rows | Where-Object { $_.amount -ge 1500 }).Count
    Assert-True ($high -eq 6) "rows with amount >= 1500 must be 6 (got $high)"
}

# -- 3. Case oracle ----------------------------------------------------------
function Test-CaseOracle {
    param([string]$Path)
    $oracle = Read-Json $Path
    Assert-True ($oracle.schemaVersion -eq 'haifa.customer-demo-v2.case-oracle/v1') "case oracle schemaVersion"
    Assert-True ($oracle.frozenUpstream.commit -eq $FrozenCommit) "case oracle frozen commit"
    Assert-True ($oracle.frozenUpstream.driftAllowed -eq $false) "drift must not be allowed"

    $expectedStages = @('V2-M1', 'V2-M2', 'V2-M3', 'V2-M4', 'V2-M5', 'V2-M6')
    $stageIds = @($oracle.stages | ForEach-Object { $_.id })
    foreach ($s in $expectedStages) { Assert-True ($stageIds -contains $s) "stage $s must be present" }

    $validResults = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED')
    foreach ($r in $oracle.resultVocabulary) { Assert-True ($validResults -contains $r) "result vocabulary entry $r invalid" }
    foreach ($r in $validResults) { Assert-True ($oracle.resultVocabulary -contains $r) "result vocabulary must contain $r" }

    $ids = @($oracle.cases | ForEach-Object { $_.id })
    Assert-True (@($ids | Select-Object -Unique).Count -eq $ids.Count) "case ids must be unique"
    Assert-True ($oracle.cases.Count -ge 40) "case table must cover at least 40 cases (got $($oracle.cases.Count))"

    foreach ($case in $oracle.cases) {
        Assert-True ($case.id -match '^V2-M[1-6]-[0-9]{2}$') "case id $($case.id) must match V2-Mx-yy"
        Assert-True ($expectedStages -contains $case.stage) "case $($case.id) stage invalid"
        Assert-True ($case.workPackage -match '^W[0-9]{2}$') "case $($case.id) workPackage invalid"
        Assert-True (@('L1', 'L2', 'L3') -contains $case.level) "case $($case.id) level invalid"
        Assert-True ($case.defaultResult -eq 'NOT_RUN') "case $($case.id) must default to NOT_RUN"
        Assert-True (@($case.positiveOracle).Count -ge 1) "case $($case.id) needs a positive oracle"
        Assert-True (@($case.negativeCases).Count -ge 1) "case $($case.id) needs at least one negative case"
        Assert-True (@($case.evidencePaths).Count -ge 1) "case $($case.id) needs an evidence path"
        Assert-True (-not [string]::IsNullOrWhiteSpace($case.runner)) "case $($case.id) needs a runner"
        Assert-True (@('SUPPORTED', 'PARTIAL', 'UPSTREAM_UNSUPPORTED', 'NOT_INVENTORIED', 'NOT_APPLICABLE') -contains $case.reference.support) "case $($case.id) reference support invalid"
        foreach ($ref in $case.reference.sourceRefs) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($ref.path)) "case $($case.id) sourceRef path missing"
            Assert-True ([int]$ref.line -ge 1) "case $($case.id) sourceRef line must be >= 1"
        }
        foreach ($fc in $case.reference.frontendConsumers) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($fc.path)) "case $($case.id) frontend consumer path missing"
            Assert-True ([int]$fc.line -ge 1) "case $($case.id) frontend consumer line must be >= 1"
        }
    }

    $allText = (Get-Content -LiteralPath $Path -Raw).ToLowerInvariant()
    $coverage = @{
        'model switch A->B'          = 'a→b'
        'file attach / read original' = 'read the original bytes'
        'mcp 2 server / 2 tool'      = '0..n'
        'mcp representative call'    = 'get_reporting_policy'
        'skill consulting-analysis'  = 'consulting-analysis'
        'skill 2 instances'          = 'v2-format-control'
        'write_file approval'        = 'write_file'
        'same-run steer'             = 'steer'
        'artifact download hash'     = 'download sha-256'
        'pending restart'            = 'pending approval'
        'completed restart'          = 'completed run'
        'diagnostics'                = 'diagnostics'
        'human pilot'                = 'pilot'
        'upstream unsupported'       = 'upstream_unsupported'
    }
    foreach ($key in $coverage.Keys) {
        Assert-True ($allText.Contains($coverage[$key])) "case oracle must cover capability: $key"
    }

    # CSV oracle block must match the recomputed values.
    Assert-True ($oracle.csvOracle.row17.customer_id -eq 'C017') "case oracle row17 id"
    Assert-True ($oracle.csvOracle.row17.amount_cny -eq 1700) "case oracle row17 amount"
    Assert-True ($oracle.csvOracle.regionTotals.East -eq 10000) "case oracle East total"
    Assert-True ($oracle.csvOracle.regionTotals.West -eq 11000) "case oracle West total"
    Assert-True ($oracle.csvOracle.grandTotalCny -eq 21000) "case oracle grand total"
    Assert-True ($oracle.csvOracle.highAmountRowCount -eq 6) "case oracle high amount count"
}

# -- 4. Source index ---------------------------------------------------------
function Test-SourceIndex {
    param([string]$Path)
    $index = Read-Json $Path
    Assert-True ($index.schemaVersion -eq 'haifa.customer-demo-v2.source-index/v1') "source index schemaVersion"
    Assert-True ($index.frozenUpstreamCommit -eq $FrozenCommit) "source index frozen commit"
    Assert-True (@($index.entries).Count -ge 30) "source index must list at least 30 entries"
    $snapshotAvailable = Test-Path -LiteralPath $SnapshotRoot -PathType Container
    foreach ($entry in $index.entries) {
        Assert-True ($entry.sha256 -match '^[0-9a-f]{64}$') "source index entry $($entry.path) needs a sha256"
        switch ($entry.kind) {
            'frontend' {
                $full = Join-Path $RepositoryRoot $entry.path
                Assert-True (Test-Path -LiteralPath $full) "frontend source missing: $($entry.path)"
                Assert-True ((Get-Sha256 $full) -eq $entry.sha256) "frontend SHA drift: $($entry.path)"
            }
            'contracts' {
                $full = Join-Path $RepositoryRoot $entry.path
                Assert-True (Test-Path -LiteralPath $full) "contract artifact missing: $($entry.path)"
                Assert-True ((Get-Sha256 $full) -eq $entry.sha256) "contract SHA drift: $($entry.path)"
            }
            'upstream-snapshot' {
                $full = Join-Path $RepositoryRoot $entry.sourcePath
                if ($snapshotAvailable) {
                    Assert-True (Test-Path -LiteralPath $full) "snapshot source missing: $($entry.sourcePath)"
                    Assert-True ((Get-Sha256 $full) -eq $entry.sha256) "snapshot SHA drift: $($entry.path)"
                }
            }
            default { throw "ASSERTION FAILED: unknown source index kind $($entry.kind)" }
        }
    }
    if (-not $snapshotAvailable) { Write-Warning "upstream-snapshot absent; snapshot entries were listed but not re-hashed." }
}

# -- 5. MCP contract ---------------------------------------------------------
function Test-McpContract {
    param([string]$Path)
    $mcp = Read-Json $Path
    $snapshotAvailable = Test-Path -LiteralPath $SnapshotRoot -PathType Container
    Assert-True ($mcp.schemaVersion -eq 'haifa.customer-demo-v2.mcp-contract/v1') "mcp contract schemaVersion"
    Assert-True ($mcp.frozenUpstream.commit -eq $FrozenCommit) "mcp contract frozen commit"
    Assert-True ($mcp.collectionSemantics.servers.cardinality -eq '0..N') "mcp servers must be 0..N"
    Assert-True ($mcp.collectionSemantics.tools.cardinalityPerServer -eq '0..N') "mcp tools per server must be 0..N"
    Assert-True (@($mcp.collectionSemantics.servers.forbiddenDesigns).Count -ge 3) "mcp forbidden designs must be listed"
    Assert-True ($mcp.collectionSemantics.agentSelection.noneMeaning -eq 'all enabled MCP installations are selectable') "mcp None=all"
    Assert-True ($mcp.collectionSemantics.agentSelection.emptyListMeaning -eq 'no MCP installation is selectable') "mcp []=none"
    Assert-True ($mcp.collectionSemantics.tools.crossServerDuplicateName.example -eq 'get_status') "mcp must cover a cross-server duplicate name"
    Assert-True ($mcp.servers.Count -eq 2) "mcp contract must configure exactly 2 servers"
    Assert-True ($mcp.representativeCall.serverId -eq 'v2-reporting') "representative MCP server must be v2-reporting"
    Assert-True ($mcp.representativeCall.tool -eq 'get_reporting_policy') "representative MCP tool must be get_reporting_policy"
    Assert-True ($mcp.representativeCall.stubAllowed -eq $false) "representative MCP call must not allow a stub"

    # Every frozen config key the fixture maps must really exist in the frozen
    # extensions_config.example.json (key name and cited line).
    $frozenKeys = Get-Prop $mcp 'frozenConfigKeys'
    Assert-True ($null -ne $frozenKeys) "mcp contract must map fixture fields to the frozen extensions_config keys"
    Assert-True ($frozenKeys.sourceFile -eq 'deer-flow/extensions_config.example.json') "mcp frozenConfigKeys.sourceFile must be the frozen extensions config"
    Assert-True (@($frozenKeys.mappings).Count -ge 7) "mcp frozenConfigKeys must map at least 7 frozen keys (got $(@($frozenKeys.mappings).Count))"
    if ($snapshotAvailable) {
        $frozenConfigPath = Join-Path $SnapshotRoot 'extensions_config.example.json'
        Assert-True (Test-Path -LiteralPath $frozenConfigPath -PathType Leaf) "frozen extensions_config.example.json must exist in the snapshot"
        $frozenConfig = Read-Json $frozenConfigPath
        $frozenLines = [IO.File]::ReadAllLines($frozenConfigPath)
        foreach ($mapping in $frozenKeys.mappings) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($mapping.frozenKey)) "frozenConfigKey mapping needs a frozenKey"
            Assert-True ([int]$mapping.frozenLine -ge 1) "frozenConfigKey mapping '$($mapping.frozenKey)' needs a frozenLine"
            $leaf = ($mapping.frozenKey -split '\.')[-1]
            if ($mapping.frozenKey -like 'mcpServers.*') {
                $present = @($frozenConfig.mcpServers.PSObject.Properties.Value | Where-Object { $null -ne $_.PSObject.Properties[$leaf] }).Count -ge 1
            } else {
                $present = $null -ne $frozenConfig.PSObject.Properties[$leaf]
            }
            Assert-True $present "frozenConfigKey '$($mapping.frozenKey)' leaf '$leaf' must exist in extensions_config.example.json"
            Assert-True ([int]$mapping.frozenLine -le $frozenLines.Count) "frozenConfigKey '$($mapping.frozenKey)' line $($mapping.frozenLine) exceeds extensions_config.example.json line count"
            Assert-True ($frozenLines[[int]$mapping.frozenLine - 1] -match [regex]::Escape($leaf)) "frozenConfigKey '$($mapping.frozenKey)' line $($mapping.frozenLine) must contain '$leaf'"
        }
    }
    else {
        $script:SkippedChecks += @($frozenKeys.mappings).Count
        Write-Host "SKIPPED(snapshot absent): frozenConfigKeys existence checks for $(@($frozenKeys.mappings).Count) frozen keys" -ForegroundColor DarkYellow
    }

    $serverIds = @($mcp.servers | ForEach-Object { $_.serverId })
    Assert-True (@($serverIds | Select-Object -Unique).Count -eq 2) "mcp server ids must be unique"
    Assert-True ($serverIds -contains 'v2-reporting') "mcp must include v2-reporting"
    Assert-True ($serverIds -contains 'v2-region') "mcp must include v2-region"

    $prefixed = New-Object System.Collections.Generic.List[string]
    foreach ($server in $mcp.servers) {
        Assert-True ($server.transport -eq 'http') "mcp server $($server.serverId) transport must be http"
        Assert-True (@($server.tools).Count -ge 2) "mcp server $($server.serverId) must expose >= 2 tools"
        foreach ($tool in $server.tools) {
            Assert-True ($tool.readOnly -eq $true) "mcp tool $($tool.rawName) must be read-only"
            Assert-True ($tool.prefixedName.StartsWith($server.serverId + '_')) "mcp tool $($tool.rawName) prefixedName must carry source identity $($server.serverId)_"
            $prefixed.Add($tool.prefixedName)
        }
    }
    Assert-True (@($prefixed | Select-Object -Unique).Count -eq 4) "prefixed tool names must be 4 distinct values (source identity preserved)"
    Assert-True ($prefixed -contains 'v2-reporting_get_status') "v2-reporting must expose get_status"
    Assert-True ($prefixed -contains 'v2-region_get_status') "v2-region must expose get_status"
}

# -- 6. Skill contract -------------------------------------------------------
function Test-SkillContract {
    param([string]$Path)
    $skill = Read-Json $Path
    Assert-True ($skill.schemaVersion -eq 'haifa.customer-demo-v2.skill-contract/v1') "skill contract schemaVersion"
    Assert-True ($skill.frozenUpstream.commit -eq $FrozenCommit) "skill contract frozen commit"
    Assert-True ($skill.collectionSemantics.skills.cardinality -eq '0..N') "skills must be 0..N"
    Assert-True ($skill.collectionSemantics.agentSelection.noneMeaning -eq 'all enabled skills are selectable') "skill None=all"
    Assert-True ($skill.collectionSemantics.agentSelection.emptyListMeaning -eq 'no skill is selectable') "skill []=none"
    Assert-True (@($skill.collectionSemantics.skills.forbiddenDesigns).Count -ge 3) "skill forbidden designs must be listed"
    Assert-True ($skill.skills.Count -eq 2) "skill contract must configure exactly 2 skills"

    $names = @($skill.skills | ForEach-Object { $_.name })
    Assert-True ($names -contains 'consulting-analysis') "skill contract must include consulting-analysis"
    Assert-True ($names -contains 'v2-format-control') "skill contract must include v2-format-control"
    $ca = $skill.skills | Where-Object { $_.name -eq 'consulting-analysis' }
    $fc = $skill.skills | Where-Object { $_.name -eq 'v2-format-control' }
    Assert-True ($ca.goldenScenario -eq $true) "consulting-analysis must be the Golden Scenario skill"
    Assert-True ($ca.isUpstreamResource -eq $true) "consulting-analysis must be an upstream resource"
    Assert-True ($fc.goldenScenario -eq $false) "v2-format-control must NOT enter the Golden Scenario"
    Assert-True ($fc.isUpstreamResource -eq $false) "v2-format-control must NOT be an upstream resource"
    Assert-True ($fc.mustNotImpersonateUpstream -eq $true) "v2-format-control must not impersonate upstream"
    Assert-True ($skill.representativeSkill.name -eq 'consulting-analysis') "representative skill must be consulting-analysis"
    Assert-True ($skill.representativeSkill.stubAllowed -eq $false) "representative skill must not allow a stub"

    # The Golden Scenario representative skill must be enabled; if it is not,
    # the activation case V2-M2-15 cannot pass.
    Assert-True ($ca.goldenScenario -eq $true -and $ca.isUpstreamResource -eq $true) "V2-M2-15 requires an enabled upstream consulting-analysis"
}

# -- 7. Dependency review ----------------------------------------------------
function Test-DependencyReview {
    param([string]$Path)
    $review = Read-Json $Path
    Assert-True ($review.schemaVersion -eq 'haifa.customer-demo-v2.consulting-analysis-dependency-review/v1') "dependency review schemaVersion"
    Assert-True ($review.skill -eq 'consulting-analysis') "dependency review skill"
    Assert-True ($review.frozenCommit -eq $FrozenCommit) "dependency review frozen commit"
    Assert-True ($review.verdict -eq 'NO_HARD_DEPENDENCY_ON_DEFERRED_CAPABILITIES') "dependency review must reach a no-hard-dependency verdict"
    Assert-True ($review.hardDependencyFound -eq $false) "dependency review hardDependencyFound must be false"
    Assert-True ($review.stopImplementation -eq $false) "dependency review stopImplementation must be false"
    Assert-True ($review.escalationRequired -eq $false) "dependency review escalationRequired must be false"
    Assert-True (@($review.findings).Count -ge 4) "dependency review must record findings"
    Assert-True (@($review.notRun).Count -ge 1) "dependency review must list NOT_RUN items"
    foreach ($finding in $review.findings) {
        Assert-True ($finding.dependencyClass -ne 'hard-dependency') "no finding may be a hard dependency while the verdict is no-hard-dependency"
    }
}

# -- 8. Reference source traceability ----------------------------------------
# Any case (or per-assertion entry) that claims UPSTREAM_UNSUPPORTED /
# NOT_INVENTORIED must point at real frozen source with an in-range line. This
# is what prevents a blanket "no approval semantics" claim that cites nothing.
function Test-ReferenceSourceTraceability {
    param([string]$Path)
    $oracle = Read-Json $Path
    $snapshotAvailable = Test-Path -LiteralPath $SnapshotRoot -PathType Container

    foreach ($case in $oracle.cases) {
        $refs = @($case.reference.sourceRefs)
        $unsupportedAssertions = @((Get-Prop $case.reference 'unsupportedAssertions') | Where-Object { $null -ne $_ })
        $isUnsupportedSupport = @('UPSTREAM_UNSUPPORTED', 'NOT_INVENTORIED') -contains $case.reference.support
        $isPartial = $case.reference.support -eq 'PARTIAL'
        $isNotApplicable = $case.reference.support -eq 'NOT_APPLICABLE'

        if ($isUnsupportedSupport) {
            Assert-True ($refs.Count -ge 1) "case $($case.id) reference.support=$($case.reference.support) requires at least one sourceRef"
        }

        # PARTIAL must state exactly which observable assertion the reference
        # cannot support; a bare PARTIAL with no unsupportedAssertions is the
        # same defect class as R1/R2 (an unexplained reference judgment).
        if ($isPartial) {
            Assert-True ($unsupportedAssertions.Count -ge 1) "case $($case.id) reference.support=PARTIAL requires at least one unsupportedAssertion naming the unsupported part"
        }

        # NOT_APPLICABLE is reserved for the Haifa-only V2-M6 stages; the
        # reference backend does not participate there and the reason must say
        # so with a pointer to the roadmap README.
        if ($isNotApplicable) {
            Assert-True (@('V2-M6') -contains $case.stage) "case $($case.id) NOT_APPLICABLE is only allowed on V2-M6 (got $($case.stage))"
            $naReason = [string](Get-Prop $case.reference 'reason')
            Assert-True (-not [string]::IsNullOrWhiteSpace($naReason)) "case $($case.id) NOT_APPLICABLE requires a non-empty reason"
            Assert-True ($naReason -match 'show-roadmap-2/README\.md') "case $($case.id) NOT_APPLICABLE reason must cite private-context/show-roadmap-2/README.md"
        }

        foreach ($ua in $unsupportedAssertions) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($ua.assertion)) "case $($case.id) unsupportedAssertion missing assertion text"
            Assert-True (-not [string]::IsNullOrWhiteSpace($ua.reason)) "case $($case.id) unsupportedAssertion '$($ua.assertion)' missing reason"
            Assert-True (@($ua.sourceRefs).Count -ge 1) "case $($case.id) unsupportedAssertion '$($ua.assertion)' requires at least one sourceRef"
        }
        if ($unsupportedAssertions.Count -gt 0) {
            Assert-True (@('PARTIAL', 'UPSTREAM_UNSUPPORTED', 'NOT_INVENTORIED') -contains $case.reference.support) "case $($case.id) has unsupportedAssertions but support=$($case.reference.support)"
        }

        # Existence + line range for every unsupported case ref and assertion ref.
        foreach ($r in $refs) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($r.path)) "case $($case.id) sourceRef path missing"
            Assert-True ([int]$r.line -ge 1) "case $($case.id) sourceRef line must be >= 1"
            if ($isUnsupportedSupport) { Assert-RefExists -CaseId $case.id -RefPath $r.path -Line ([int]$r.line) -SnapshotAvailable $snapshotAvailable }
        }
        foreach ($ua in $unsupportedAssertions) {
            foreach ($r in @($ua.sourceRefs)) {
                Assert-True (-not [string]::IsNullOrWhiteSpace($r.path)) "case $($case.id) unsupportedAssertion '$($ua.assertion)' sourceRef path missing"
                Assert-True ([int]$r.line -ge 1) "case $($case.id) unsupportedAssertion '$($ua.assertion)' sourceRef line must be >= 1"
                Assert-RefExists -CaseId $case.id -RefPath $r.path -Line ([int]$r.line) -SnapshotAvailable $snapshotAvailable -IsAssertion
            }
        }

        # A case cannot claim UPSTREAM_UNSUPPORTED while citing the reference's
        # own require_permission/owner_check decorator lines (create_run 945-946,
        # cancel_run 1238-1239): that would reject a capability the reference
        # already implements (the R2 V2-M3-09 defect).
        if ($case.reference.support -eq 'UPSTREAM_UNSUPPORTED') {
            foreach ($r in $refs) {
                $isOwnerCheckRef = ($r.path -match 'routers/thread_runs\.py$') -and (([int]$r.line -in @(945, 946, 1238, 1239)) -or ([string]$r.note -match 'require_permission|owner_check'))
                Assert-True (-not $isOwnerCheckRef) "case $($case.id) is UPSTREAM_UNSUPPORTED but cites the reference owner-check decorator $($r.path):$($r.line); a reference-implemented check cannot be claimed unsupported"
            }
        }

        if ($isUnsupportedSupport -and ($ApprovalCaseIds -contains $case.id)) {
            $allRefs = @($refs)
            foreach ($ua in $unsupportedAssertions) { $allRefs += @($ua.sourceRefs) }
            $citesImpl = @($allRefs | Where-Object { $_.path -match 'clarification_middleware\.py$' -or $_.path -match 'interaction_policy\.py$' }).Count
            $pathsSeen = (@($allRefs | ForEach-Object { $_.path }) -join ', ')
            Assert-True ($citesImpl -ge 1) "case $($case.id) is an approval-class UPSTREAM_UNSUPPORTED case and must cite clarification_middleware.py or interaction_policy.py (cites: $pathsSeen)"
        }
    }

    if (-not $snapshotAvailable) {
        Write-Warning "upstream-snapshot absent; snapshot-backed sourceRef existence/line checks reported SKIPPED(snapshot absent) and were not counted as PASS."
    }
}

# -- 8. Human-ruled deferrals -------------------------------------------------
function Get-OracleDeferrals($Oracle) {
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($case in $Oracle.cases) {
        if ($null -ne $case.PSObject.Properties['deferral']) { $items.Add([pscustomobject]@{ Id = [string]$case.id; Deferral = $case.deferral }) }
        foreach ($neg in @($case.negativeCases)) {
            if ($null -ne $neg.PSObject.Properties['deferral']) { $items.Add([pscustomobject]@{ Id = [string]$neg.id; Deferral = $neg.deferral }) }
        }
    }
    return $items
}

function Test-OracleDeferrals {
    param([string]$Path)
    $oracle = Read-Json $Path
    Assert-True ($null -ne $oracle.PSObject.Properties['revision']) "oracle with deferrals must record a revision"
    Assert-True ([int]$oracle.revision.number -ge 2) "oracle revision number must be >= 2"
    Assert-True ([string]$oracle.revision.previousSha256 -eq $PreviousOracleSha) "oracle revision must pin the previous oracle SHA-256"
    $rulingIds = @($oracle.rulings | Where-Object { [string]$_.decidedBy -eq 'human' -and -not [string]::IsNullOrWhiteSpace([string]$_.source) } | ForEach-Object { [string]$_.id })
    $deferrals = @(Get-OracleDeferrals $oracle)
    $ids = @($deferrals | ForEach-Object { $_.Id })
    Assert-True ((@($ids | Sort-Object) -join ',') -eq (@($ApprovedDeferrals | Sort-Object) -join ',')) "deferred set must be exactly the human-approved set ($($ApprovedDeferrals -join ', ')), got ($($ids -join ', '))"
    foreach ($item in $deferrals) {
        $d = $item.Deferral
        Assert-True ($rulingIds -contains [string]$d.rulingRef) "deferral $($item.Id) must reference a human ruling recorded in the oracle"
        foreach ($field in @('decidedAt', 'reason', 'restoreCondition')) {
            Assert-True (-not [string]::IsNullOrWhiteSpace([string]$d.$field)) "deferral $($item.Id) needs a non-empty $field"
        }
        Assert-True ([string]$d.result -eq 'NOT_RUN') "deferral $($item.Id) must stay NOT_RUN, never PASS"
    }
}

# -- Negative self-tests -----------------------------------------------------
function Assert-Throws([scriptblock]$Action, [string]$Name) {
    $script:Assertions++
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (-not $threw) { throw "ASSERTION FAILED: negative self-test '$Name' must fail but did not" }
}

function Invoke-SelfTests {
    # Wrong CSV row value.
    $badCsv = Join-Path $TempRoot 'customers-bad.csv'
    $csvText = [IO.File]::ReadAllText($CsvPath)
    [IO.File]::WriteAllText($badCsv, ($csvText -replace 'C017,East,1700', 'C017,East,1701'))
    Assert-Throws { Test-CsvOracle -Path $badCsv } 'csv-row17-value'

    # Wrong region label.
    $badRegion = Join-Path $TempRoot 'customers-region.csv'
    [IO.File]::WriteAllText($badRegion, ($csvText -replace 'C001,East,100', 'C001,West,100'))
    Assert-Throws { Test-CsvOracle -Path $badRegion } 'csv-region-value'

    # Corrupted header / row offset.
    $badHeader = Join-Path $TempRoot 'customers-header.csv'
    [IO.File]::WriteAllText($badHeader, ($csvText -replace 'customer_id,region,amount_cny', 'id,region,amount'))
    Assert-Throws { Test-CsvOracle -Path $badHeader } 'csv-header'

    # MCP tool source identity removed (both servers claim the same prefixed name).
    $badMcp = Join-Path $TempRoot 'mcp-bad.json'
    $mcp = Read-Json $McpPath
    $mcp.servers[1].tools[1].prefixedName = 'v2-reporting_get_status'
    [IO.File]::WriteAllText($badMcp, (($mcp | ConvertTo-Json -Depth 32) + "`n"))
    Assert-Throws { Test-McpContract -Path $badMcp } 'mcp-duplicate-source'

    # MCP narrowed to a single server.
    $badMcp2 = Join-Path $TempRoot 'mcp-single.json'
    $mcp2 = Read-Json $McpPath
    $mcp2.servers = @($mcp2.servers[0])
    [IO.File]::WriteAllText($badMcp2, (($mcp2 | ConvertTo-Json -Depth 32) + "`n"))
    Assert-Throws { Test-McpContract -Path $badMcp2 } 'mcp-single-server'

    # Skill contract: representative skill disabled -> activation case cannot pass.
    $badSkill = Join-Path $TempRoot 'skills-disabled.json'
    $skill = Read-Json $SkillPath
    ($skill.skills | Where-Object { $_.name -eq 'consulting-analysis' }).goldenScenario = $false
    [IO.File]::WriteAllText($badSkill, (($skill | ConvertTo-Json -Depth 32) + "`n"))
    Assert-Throws { Test-SkillContract -Path $badSkill } 'skill-representative-disabled'

    # Skill contract: negative control promoted into the Golden Scenario.
    $badSkill2 = Join-Path $TempRoot 'skills-promoted.json'
    $skill2 = Read-Json $SkillPath
    ($skill2.skills | Where-Object { $_.name -eq 'v2-format-control' }).goldenScenario = $true
    [IO.File]::WriteAllText($badSkill2, (($skill2 | ConvertTo-Json -Depth 32) + "`n"))
    Assert-Throws { Test-SkillContract -Path $badSkill2 } 'skill-negative-promoted'

    # Reference traceability: UPSTREAM_UNSUPPORTED case with empty sourceRefs.
    $badRef1 = Join-Path $TempRoot 'oracle-ref-empty.json'
    $o1 = Read-Json $OraclePath
    ($o1.cases | Where-Object { $_.id -eq 'V2-M3-07' }).reference.sourceRefs = @()
    [IO.File]::WriteAllText($badRef1, (($o1 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badRef1 } 'ref-unsupported-empty-sourcerefs'

    # Reference traceability: sourceRef points at a file that does not exist.
    $badRef2 = Join-Path $TempRoot 'oracle-ref-missing.json'
    $o2 = Read-Json $OraclePath
    ($o2.cases | Where-Object { $_.id -eq 'V2-M3-07' }).reference.sourceRefs[0].path = 'frontend/src/does-not-exist-w00.ts'
    [IO.File]::WriteAllText($badRef2, (($o2 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badRef2 } 'ref-missing-file'

    # Reference traceability: sourceRef line beyond the file's line count.
    $badRef3 = Join-Path $TempRoot 'oracle-ref-line.json'
    $o3 = Read-Json $OraclePath
    $c3 = $o3.cases | Where-Object { $_.id -eq 'V2-M3-07' }
    $c3.reference.sourceRefs[0].path = 'frontend/src/components/workspace/chats/chat-page.tsx'
    $c3.reference.sourceRefs[0].line = 999999
    [IO.File]::WriteAllText($badRef3, (($o3 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badRef3 } 'ref-line-out-of-range'

    # R1 regression guard: V2-M3-01 back to a blanket UPSTREAM_UNSUPPORTED citing
    # only thread_runs.py:945 (the create_run decorator) must FAIL.
    $badRef4 = Join-Path $TempRoot 'oracle-approval-regressed.json'
    $o4 = Read-Json $OraclePath
    $c4 = $o4.cases | Where-Object { $_.id -eq 'V2-M3-01' }
    $c4.reference.support = 'UPSTREAM_UNSUPPORTED'
    $c4.reference.sourceRefs = @([pscustomobject]@{ path = 'deer-flow/backend/app/gateway/routers/thread_runs.py'; line = 945; note = 'reference run router exposes no interaction/approval semantics' })
    $c4.reference.PSObject.Properties.Remove('unsupportedAssertions')
    [IO.File]::WriteAllText($badRef4, (($o4 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badRef4 } 'approval-class-citation-regressed'

    # Reference traceability: an unsupported assertion whose sourceRefs were stripped.
    $badRef5 = Join-Path $TempRoot 'oracle-assertion-empty.json'
    $o5 = Read-Json $OraclePath
    ($o5.cases | Where-Object { $_.id -eq 'V2-M3-01' }).reference.unsupportedAssertions[0].sourceRefs = @()
    [IO.File]::WriteAllText($badRef5, (($o5 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badRef5 } 'assertion-unsupported-empty-sourcerefs'

    # R2 rule: a PARTIAL case must name what the reference cannot support.
    $badPartial1 = Join-Path $TempRoot 'oracle-partial-empty.json'
    $p1 = Read-Json $OraclePath
    ($p1.cases | Where-Object { $_.id -eq 'V2-M2-17' }).reference.PSObject.Properties.Remove('unsupportedAssertions')
    [IO.File]::WriteAllText($badPartial1, (($p1 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badPartial1 } 'partial-missing-unsupported-assertions'

    # R2 rule: a PARTIAL unsupportedAssertion needs a non-empty reason.
    $badPartial2 = Join-Path $TempRoot 'oracle-partial-noreason.json'
    $p2 = Read-Json $OraclePath
    ($p2.cases | Where-Object { $_.id -eq 'V2-M2-17' }).reference.unsupportedAssertions[0].reason = ''
    [IO.File]::WriteAllText($badPartial2, (($p2 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badPartial2 } 'partial-assertion-missing-reason'

    # R2 rule: a PARTIAL unsupportedAssertion needs at least one sourceRef.
    $badPartial3 = Join-Path $TempRoot 'oracle-partial-nosource.json'
    $p3 = Read-Json $OraclePath
    ($p3.cases | Where-Object { $_.id -eq 'V2-M2-17' }).reference.unsupportedAssertions[0].sourceRefs = @()
    [IO.File]::WriteAllText($badPartial3, (($p3 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badPartial3 } 'partial-assertion-missing-source'

    # R2 rule: NOT_APPLICABLE requires a non-empty reason.
    $badNa1 = Join-Path $TempRoot 'oracle-na-noreason.json'
    $n1 = Read-Json $OraclePath
    ($n1.cases | Where-Object { $_.id -eq 'V2-M6-01' }).reference.reason = ''
    [IO.File]::WriteAllText($badNa1, (($n1 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badNa1 } 'not-applicable-missing-reason'

    # R2 rule: NOT_APPLICABLE is only allowed on V2-M6.
    $badNa2 = Join-Path $TempRoot 'oracle-na-wrong-stage.json'
    $n2 = Read-Json $OraclePath
    $n2c = $n2.cases | Where-Object { $_.id -eq 'V2-M3-10' }
    $n2c.reference.support = 'NOT_APPLICABLE'
    $n2c.reference | Add-Member -NotePropertyName reason -NotePropertyValue 'wrong stage per show-roadmap-2/README.md' -Force
    [IO.File]::WriteAllText($badNa2, (($n2 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badNa2 } 'not-applicable-wrong-stage'

    # R2 rule: V2-M3-09 regressed to a blanket UPSTREAM_UNSUPPORTED while citing
    # the reference's own owner_check/require_permission decorator must FAIL
    # (the reference already implements the owner check, so its foreign-approve
    # denial is differentiable and must stay PARTIAL).
    $badOwner = Join-Path $TempRoot 'oracle-uu-ownercheck.json'
    $u1 = Read-Json $OraclePath
    $u1c = $u1.cases | Where-Object { $_.id -eq 'V2-M3-09' }
    $u1c.reference.support = 'UPSTREAM_UNSUPPORTED'
    $u1c.reference.sourceRefs = @([pscustomobject]@{ path = 'deer-flow/backend/app/gateway/routers/thread_runs.py'; line = 946; note = 'require_permission owner_check' })
    [IO.File]::WriteAllText($badOwner, (($u1 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-ReferenceSourceTraceability -Path $badOwner } 'uu-cites-owner-check'

    # Deferral: an extra, unapproved deferral changes the denominator.
    $badDef1 = Join-Path $TempRoot 'oracle-deferral-extra.json'
    $d1 = Read-Json $OraclePath
    ($d1.cases | Where-Object { $_.id -eq 'V2-M3-10' }) | Add-Member -NotePropertyName deferral -NotePropertyValue ($d1.cases | Where-Object { $_.id -eq 'V2-M1-06' }).deferral -Force
    [IO.File]::WriteAllText($badDef1, (($d1 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-OracleDeferrals -Path $badDef1 } 'deferral-unapproved-extra'

    # Deferral: a deferred assertion claimed as PASS.
    $badDef2 = Join-Path $TempRoot 'oracle-deferral-pass.json'
    $d2 = Read-Json $OraclePath
    ($d2.cases | Where-Object { $_.id -eq 'V2-M1-06' }).deferral.result = 'PASS'
    [IO.File]::WriteAllText($badDef2, (($d2 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-OracleDeferrals -Path $badDef2 } 'deferral-as-pass'

    # Deferral: missing reason.
    $badDef3 = Join-Path $TempRoot 'oracle-deferral-noreason.json'
    $d3 = Read-Json $OraclePath
    ($d3.cases | Where-Object { $_.id -eq 'V2-M3-09' }).deferral.reason = ''
    [IO.File]::WriteAllText($badDef3, (($d3 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-OracleDeferrals -Path $badDef3 } 'deferral-missing-reason'

    # Deferral: ruling reference not backed by a human ruling in the oracle.
    $badDef4 = Join-Path $TempRoot 'oracle-deferral-noruling.json'
    $d4 = Read-Json $OraclePath
    $n4 = ($d4.cases | Where-Object { $_.id -eq 'V2-M1-04' }).negativeCases | Where-Object { $_.id -eq 'V2-M1-04-N2' }
    $n4.deferral.rulingRef = 'self-declared-by-runner'
    [IO.File]::WriteAllText($badDef4, (($d4 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-OracleDeferrals -Path $badDef4 } 'deferral-without-human-ruling'

    # Deferral: an approved deferral silently removed (denominator drift the other way).
    $badDef5 = Join-Path $TempRoot 'oracle-deferral-dropped.json'
    $d5 = Read-Json $OraclePath
    ($d5.cases | Where-Object { $_.id -eq 'V2-M3-09' }).PSObject.Properties.Remove('deferral')
    [IO.File]::WriteAllText($badDef5, (($d5 | ConvertTo-Json -Depth 64) + "`n"))
    Assert-Throws { Test-OracleDeferrals -Path $badDef5 } 'deferral-dropped'

    # R2 rule: a frozenConfigKeys mapping naming a key absent from the frozen
    # extensions_config.example.json must fail (only checkable with the snapshot).
    if (Test-Path -LiteralPath $SnapshotRoot -PathType Container) {
        $badMcp3 = Join-Path $TempRoot 'mcp-frozen-key.json'
        $mcp3 = Read-Json $McpPath
        $mcp3.frozenConfigKeys.mappings[0].frozenKey = 'mcpServers.<id>.does_not_exist_w00'
        [IO.File]::WriteAllText($badMcp3, (($mcp3 | ConvertTo-Json -Depth 32) + "`n"))
        Assert-Throws { Test-McpContract -Path $badMcp3 } 'mcp-frozen-config-key-missing'
    }
}

try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    # Positive path.
    Test-FixtureHashes
    Test-CsvOracle -Path $CsvPath
    Test-CaseOracle -Path $OraclePath
    Test-ReferenceSourceTraceability -Path $OraclePath
    Test-SourceIndex -Path $IndexPath
    Test-McpContract -Path $McpPath
    Test-SkillContract -Path $SkillPath
    Test-DependencyReview -Path $DepReviewPath
    Test-OracleDeferrals -Path $OraclePath

    # Negative self-tests.
    Invoke-SelfTests

    Write-Host "V2 contract/oracle verifier passed: $Assertions assertions ($SkippedChecks skipped: snapshot absent)."
}
finally {
    if (Test-Path -LiteralPath $TempRoot) { Remove-Item -LiteralPath $TempRoot -Recurse -Force }
}
