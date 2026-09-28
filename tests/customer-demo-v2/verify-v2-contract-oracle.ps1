[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
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
#   4. Source index files exist and SHA-256 matches (snapshot + frontend + contracts).
#   5. MCP and Skill collection semantics are not narrowed.
#   6. consulting-analysis dependency review verdict is fail-closed.
# Negative self-tests deliberately corrupt a copy and require the corresponding
# check to FAIL.
# ---------------------------------------------------------------------------

$ContractRoot = Join-Path $RepositoryRoot 'contracts/customer-demo-v2'
$SnapshotRoot = Join-Path $RepositoryRoot 'local-tmp/slot-1-w00-v2-oracle/upstream-snapshot'
$Assertions = 0

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
        Assert-True (@('SUPPORTED', 'PARTIAL', 'UPSTREAM_UNSUPPORTED', 'NOT_INVENTORIED') -contains $case.reference.support) "case $($case.id) reference support invalid"
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
}

try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    # Positive path.
    Test-FixtureHashes
    Test-CsvOracle -Path $CsvPath
    Test-CaseOracle -Path $OraclePath
    Test-SourceIndex -Path $IndexPath
    Test-McpContract -Path $McpPath
    Test-SkillContract -Path $SkillPath
    Test-DependencyReview -Path $DepReviewPath

    # Negative self-tests.
    Invoke-SelfTests

    Write-Host "V2 contract/oracle verifier passed: $Assertions assertions."
}
finally {
    if (Test-Path -LiteralPath $TempRoot) { Remove-Item -LiteralPath $TempRoot -Recurse -Force }
}
