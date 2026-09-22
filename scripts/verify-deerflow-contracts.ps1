[CmdletBinding()]
param(
    [string]$DeerFlowRoot,
    [string]$ManifestPath,
    [string]$ResourceManifestPath,
    [string]$OracleManifestPath,
    [string]$OracleFixturesPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedSourceSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
}
if ([string]::IsNullOrWhiteSpace($ResourceManifestPath)) {
    $ResourceManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-seed.json'
}
if ([string]::IsNullOrWhiteSpace($OracleManifestPath)) {
    $OracleManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/core-protocol-oracle.json'
}
if ([string]::IsNullOrWhiteSpace($OracleFixturesPath)) {
    $OracleFixturesPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json'
}

function Resolve-DeerFlowRoot {
    param([string]$ExplicitRoot)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($ExplicitRoot)) {
        $candidates.Add($ExplicitRoot)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:DEERFLOW_SOURCE_ROOT)) {
        $candidates.Add($env:DEERFLOW_SOURCE_ROOT)
    }
    $candidates.Add((Join-Path $RepositoryRoot 'deer-flow'))
    $workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot '../..'))
    $candidates.Add((Join-Path $workspaceRoot 'haifa-agent-super-harness/deer-flow'))

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        if ((Test-Path -LiteralPath (Join-Path $resolved '.git')) -or
            (Test-Path -LiteralPath (Join-Path $resolved 'frontend'))) {
            return $resolved
        }
    }

    throw 'Unable to locate DeerFlow. Pass -DeerFlowRoot or set DEERFLOW_SOURCE_ROOT.'
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required manifest is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}

function Assert-Equal {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Message
    )
    if ($Actual -ne $Expected) {
        throw "$Message (expected '$Expected', actual '$Actual')"
    }
}

function Assert-OneOf {
    param(
        [object]$Actual,
        [object[]]$Allowed,
        [string]$Message
    )
    if ($Allowed -notcontains $Actual) {
        throw "$Message (allowed '$($Allowed -join ', ')', actual '$Actual')"
    }
}

function Assert-JsonMatchesSchema {
    param(
        [object]$Payload,
        [string]$SchemaPath,
        [string]$FixtureId
    )
    if (-not (Test-Path -LiteralPath $SchemaPath -PathType Leaf)) {
        throw "Fixture '$FixtureId' schema is missing: $SchemaPath"
    }
    $json = $Payload | ConvertTo-Json -Depth 100 -Compress
    $isValid = Test-Json -Json $json -SchemaFile $SchemaPath -ErrorAction SilentlyContinue
    if (-not $isValid) {
        throw "Fixture '$FixtureId' failed schema validation against '$SchemaPath'."
    }
}

function Assert-Evidence {
    param(
        [object]$Evidence,
        [string]$SourceRoot,
        [string]$SurfaceId
    )

    $sourcePath = [string]$Evidence.source_path
    if ([string]::IsNullOrWhiteSpace($sourcePath)) {
        throw "Surface '$SurfaceId' contains evidence without source_path."
    }
    $absolutePath = Join-Path $SourceRoot $sourcePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Surface '$SurfaceId' source path is missing at frozen checkout: $sourcePath"
    }
    $content = Get-Content -LiteralPath $absolutePath -Raw
    $needles = @($Evidence.must_contain)
    if ($needles.Count -eq 0) {
        throw "Surface '$SurfaceId' evidence '$sourcePath' has no must_contain assertions."
    }
    foreach ($needleValue in $needles) {
        $needle = [string]$needleValue
        if (-not $content.Contains($needle, [System.StringComparison]::Ordinal)) {
            throw "Surface '$SurfaceId' lost required source evidence '$needle' in '$sourcePath'."
        }
    }
}

$resolvedDeerFlowRoot = Resolve-DeerFlowRoot -ExplicitRoot $DeerFlowRoot
$manifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ManifestPath))
$resourceManifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ResourceManifestPath))
$oracleManifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($OracleManifestPath))
$oracleFixtures = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($OracleFixturesPath))

Assert-Equal $manifest.schema_version 'w00.frontend-consumer-contract/v1' 'Unexpected contract manifest schema'
Assert-Equal $manifest.scope 'incremental_seed' 'W00 manifest must remain an incremental seed'
Assert-Equal $manifest.status 'SOURCE_VERIFIED' 'Static manifest status must be SOURCE_VERIFIED'
Assert-Equal $manifest.runtime_status 'NOT_RUN' 'Static extraction must not claim runtime PASS'
Assert-Equal $manifest.source.source_sha $ExpectedSourceSha 'Contract manifest source SHA drifted'
Assert-Equal $manifest.core_protocol_oracle 'contracts/upstream/deerflow/core-protocol-oracle.json' 'Frontend manifest lost the core protocol Oracle link'
Assert-Equal $resourceManifest.source.source_sha $ExpectedSourceSha 'Resource manifest source SHA drifted'
Assert-Equal $resourceManifest.runtime_status 'NOT_RUN' 'Resource seed must not claim runtime PASS'
Assert-Equal $resourceManifest.repository_import.status 'IMPORTED' 'Frozen W18 frontend import must be recorded separately from runtime status'
Assert-Equal $resourceManifest.repository_import.runtime_status 'NOT_RUN' 'Imported source must not claim runtime parity PASS'

Assert-Equal $oracleManifest.schema_version 'w00.core-protocol-oracle/v1' 'Unexpected core protocol Oracle schema'
Assert-Equal $oracleManifest.source.source_sha $ExpectedSourceSha 'Core protocol Oracle source SHA drifted'
Assert-Equal $oracleManifest.source_status 'SOURCE_VERIFIED' 'Core protocol Oracle source status is invalid'
Assert-Equal $oracleManifest.contract_status 'STATIC_CONTRACT_VERIFIED' 'Core protocol Oracle contract status is invalid'
Assert-OneOf $oracleManifest.implementation_status @('NOT_ASSESSED', 'IMPLEMENTED', 'NOT_IMPLEMENTED') 'Core protocol Oracle implementation status is invalid'
Assert-Equal $oracleManifest.runtime_status 'NOT_RUN' 'Static core protocol Oracle must not claim runtime PASS or FAIL'
Assert-Equal $oracleManifest.fixtures_path 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json' 'Core protocol Oracle fixture link drifted'
Assert-Equal $oracleFixtures.schema_version 'w00.core-protocol-fixtures/v1' 'Unexpected core protocol fixture schema'
Assert-Equal $oracleFixtures.source_sha $ExpectedSourceSha 'Core protocol fixtures source SHA drifted'
Assert-Equal $oracleFixtures.source_status 'SOURCE_VERIFIED' 'Core protocol fixture source status is invalid'
Assert-Equal $oracleFixtures.contract_status 'STATIC_CONTRACT_VERIFIED' 'Core protocol fixture contract status is invalid'
Assert-OneOf $oracleFixtures.implementation_status @('NOT_ASSESSED', 'IMPLEMENTED', 'NOT_IMPLEMENTED') 'Core protocol fixture implementation status is invalid'
Assert-Equal $oracleFixtures.runtime_status 'NOT_RUN' 'Static core protocol fixtures must not claim runtime PASS or FAIL'

$w18ManifestPath = Join-Path $RepositoryRoot ([string]$resourceManifest.repository_import.manifest_path)
$w18Manifest = Read-JsonFile -Path $w18ManifestPath
Assert-Equal $w18Manifest.upstream.commit $ExpectedSourceSha 'W18 import manifest is not bound to the frozen DeerFlow SHA'
Assert-Equal $w18Manifest.upstream.sourceTree $resourceManifest.repository_import.source_tree 'W18 import source tree drifted from the W00 seed'
Assert-Equal $w18Manifest.import.localPath $resourceManifest.repository_import.frontend_local_path 'W18 frontend import path drifted from the W00 seed'

$gitHead = (& git -C $resolvedDeerFlowRoot rev-parse HEAD 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not resolve DeerFlow HEAD at '$resolvedDeerFlowRoot': $gitHead"
}
Assert-Equal $gitHead $ExpectedSourceSha 'DeerFlow checkout is not the frozen W00 snapshot'

$surfaces = @($manifest.surfaces)
if ($surfaces.Count -eq 0) {
    throw 'Contract manifest contains no surfaces.'
}

$ids = @($surfaces | ForEach-Object { [string]$_.id })
$duplicateIds = @($ids | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
if ($duplicateIds.Count -gt 0) {
    throw "Duplicate surface IDs: $($duplicateIds -join ', ')"
}

foreach ($requiredId in @($manifest.required_surface_ids)) {
    if ($ids -notcontains [string]$requiredId) {
        throw "Required W00 surface is missing: $requiredId"
    }
}

$requiredConsumers = @('browser_fetch', 'next_ssr', 'next_route_handler', 'langgraph_sdk', 'browser_websocket', 'next_rewrite')
foreach ($requiredConsumer in $requiredConsumers) {
    if (@($surfaces | Where-Object consumer -eq $requiredConsumer).Count -eq 0) {
        throw "Required consumer layer is missing: $requiredConsumer"
    }
}

$requiredCategories = @('auth', 'rest', 'sse', 'file', 'browser', 'ws', 'next-bff', 'proxy')
foreach ($requiredCategory in $requiredCategories) {
    if (@($surfaces | Where-Object category -eq $requiredCategory).Count -eq 0) {
        throw "Required contract category is missing: $requiredCategory"
    }
}

foreach ($surface in $surfaces) {
    Assert-Equal $surface.status 'SOURCE_VERIFIED' "Surface '$($surface.id)' has an invalid static status"
    Assert-Equal $surface.runtime_status 'NOT_RUN' "Surface '$($surface.id)' must not claim runtime PASS"
    if ([string]::IsNullOrWhiteSpace([string]$surface.registration_condition)) {
        throw "Surface '$($surface.id)' does not explain its registration/configuration condition."
    }
    if (@($surface.methods).Count -eq 0) {
        throw "Surface '$($surface.id)' has no HTTP/upgrade method."
    }
    $evidenceItems = @($surface.evidence)
    if ($evidenceItems.Count -eq 0) {
        throw "Surface '$($surface.id)' has no source evidence."
    }
    foreach ($evidence in $evidenceItems) {
        Assert-Evidence -Evidence $evidence -SourceRoot $resolvedDeerFlowRoot -SurfaceId ([string]$surface.id)
    }
}

$oracleContracts = @($oracleManifest.contracts)
if ($oracleContracts.Count -eq 0) {
    throw 'Core protocol Oracle contains no contracts.'
}
$oracleContractIds = @($oracleContracts | ForEach-Object { [string]$_.id })
$duplicateOracleContractIds = @($oracleContractIds | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
if ($duplicateOracleContractIds.Count -gt 0) {
    throw "Duplicate core protocol contract IDs: $($duplicateOracleContractIds -join ', ')"
}
foreach ($requiredId in @($oracleManifest.required_contract_ids)) {
    if ($oracleContractIds -notcontains [string]$requiredId) {
        throw "Required core protocol contract is missing: $requiredId"
    }
}
foreach ($contract in $oracleContracts) {
    Assert-Equal $contract.source_status 'SOURCE_VERIFIED' "Contract '$($contract.id)' source status is invalid"
    Assert-Equal $contract.contract_status 'STATIC_CONTRACT_VERIFIED' "Contract '$($contract.id)' static status is invalid"
    Assert-OneOf $contract.implementation_status @('NOT_ASSESSED', 'IMPLEMENTED', 'NOT_IMPLEMENTED') "Contract '$($contract.id)' implementation status is invalid"
    Assert-Equal $contract.runtime_status 'NOT_RUN' "Contract '$($contract.id)' must not claim runtime PASS or FAIL"
    $schemaPath = Join-Path $RepositoryRoot ([string]$contract.schema_path)
    if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
        throw "Contract '$($contract.id)' schema is missing: $($contract.schema_path)"
    }
    foreach ($evidence in @($contract.evidence)) {
        Assert-Evidence -Evidence $evidence -SourceRoot $resolvedDeerFlowRoot -SurfaceId ([string]$contract.id)
    }
}

$fixtures = @($oracleFixtures.fixtures)
if ($fixtures.Count -eq 0) {
    throw 'Core protocol fixture file contains no fixtures.'
}
$fixtureIds = @($fixtures | ForEach-Object { [string]$_.id })
$duplicateFixtureIds = @($fixtureIds | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
if ($duplicateFixtureIds.Count -gt 0) {
    throw "Duplicate core protocol fixture IDs: $($duplicateFixtureIds -join ', ')"
}
foreach ($fixture in $fixtures) {
    $contractId = [string]$fixture.contract_id
    if ($oracleContractIds -notcontains $contractId) {
        throw "Fixture '$($fixture.id)' references unknown contract '$contractId'."
    }
    $contract = @($oracleContracts | Where-Object id -eq $contractId)[0]
    Assert-Equal $fixture.schema_path $contract.schema_path "Fixture '$($fixture.id)' schema does not match contract '$contractId'"
    $schemaPath = Join-Path $RepositoryRoot ([string]$fixture.schema_path)
    Assert-JsonMatchesSchema -Payload $fixture.payload -SchemaPath $schemaPath -FixtureId ([string]$fixture.id)
}
foreach ($contractId in @($oracleManifest.required_contract_ids)) {
    if (@($fixtures | Where-Object contract_id -eq $contractId).Count -eq 0) {
        throw "Required core protocol contract has no fixture: $contractId"
    }
}

$runCreateSse = @($surfaces | Where-Object id -eq 'run.create.sdk-sse')[0]
foreach ($requiredMode in @('messages-tuple', 'updates', 'custom')) {
    if (@($runCreateSse.stream_modes) -notcontains $requiredMode) {
        throw "Run SSE seed lost required frontend stream mode: $requiredMode"
    }
}

$browserWs = @($surfaces | Where-Object id -eq 'browser.live.browser-ws')[0]
foreach ($requiredFrame in @('url', 'tabs', 'nav_rejected')) {
    if (@($browserWs.server_frames) -notcontains $requiredFrame) {
        throw "Browser WS seed lost required server frame: $requiredFrame"
    }
}

foreach ($resource in @($resourceManifest.resources)) {
    Assert-Equal $resource.status 'SOURCE_VERIFIED' "Resource '$($resource.id)' has an invalid static status"
    $allowedImportStatuses = @('IMPORTED', 'NOT_RUN')
    if ($allowedImportStatuses -notcontains [string]$resource.import_status) {
        throw "Resource '$($resource.id)' has invalid import_status '$($resource.import_status)'; parity PASS is never a resource import status."
    }
    $resourcePath = Join-Path $resolvedDeerFlowRoot ([string]$resource.source_path)
    if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) {
        throw "Resource '$($resource.id)' source is missing: $($resource.source_path)"
    }
    $actualHash = (Get-FileHash -LiteralPath $resourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal $actualHash ([string]$resource.sha256).ToLowerInvariant() "Resource '$($resource.id)' hash drifted"
    if ($resource.import_status -eq 'IMPORTED') {
        $importedPath = Join-Path $RepositoryRoot ([string]$resource.intended_local_path)
        if (-not (Test-Path -LiteralPath $importedPath -PathType Leaf)) {
            throw "Resource '$($resource.id)' claims IMPORTED but local file is missing: $($resource.intended_local_path)"
        }
        $importedHash = (Get-FileHash -LiteralPath $importedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-Equal $importedHash $actualHash "Resource '$($resource.id)' imported content differs from frozen source"
    }
}

$surfaceSummary = $surfaces | Group-Object consumer | Sort-Object Name | ForEach-Object {
    "$($_.Name)=$($_.Count)"
}
Write-Host "Verified DeerFlow W00 contract seed at $ExpectedSourceSha"
Write-Host "Surfaces: $($surfaces.Count) ($($surfaceSummary -join ', '))"
$oracleKindSummary = $oracleContracts | Group-Object kind | Sort-Object Name | ForEach-Object {
    "$($_.Name)=$($_.Count)"
}
Write-Host "Core protocol contracts: $($oracleContracts.Count) ($($oracleKindSummary -join ', ')); fixtures=$($fixtures.Count); implementation=$($oracleManifest.implementation_status); runtime=NOT_RUN"
$importedCount = @($resourceManifest.resources | Where-Object import_status -eq 'IMPORTED').Count
Write-Host "Resources: $(@($resourceManifest.resources).Count) (IMPORTED=$importedCount, NOT_RUN=$(@($resourceManifest.resources).Count - $importedCount)); runtime_status=NOT_RUN"
