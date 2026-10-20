[CmdletBinding()]
param(
    [string]$DeerFlowRoot,
    [string]$ManifestPath,
    [string]$ExpandedInventoryPath,
    [string]$ResourceManifestPath,
    [string]$OracleManifestPath,
    [string]$OracleFixturesPath,
    [string]$ThreadHookMatrixPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedSourceSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
}
if ([string]::IsNullOrWhiteSpace($ResourceManifestPath)) {
    $ResourceManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-seed.json'
}
if ([string]::IsNullOrWhiteSpace($ExpandedInventoryPath)) {
    $ExpandedInventoryPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-expanded.json'
}
if ([string]::IsNullOrWhiteSpace($OracleManifestPath)) {
    $OracleManifestPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/core-protocol-oracle.json'
}
if ([string]::IsNullOrWhiteSpace($OracleFixturesPath)) {
    $OracleFixturesPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json'
}
if ([string]::IsNullOrWhiteSpace($ThreadHookMatrixPath)) {
    $ThreadHookMatrixPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/thread-hook-consumption-matrix.json'
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
    # -InputObject preserves singleton arrays; pipeline input would unwrap them
    # and accidentally validate the only element instead of the wire array.
    $json = ConvertTo-Json -InputObject $Payload -Depth 100 -Compress
    $isValid = Test-Json -Json $json -SchemaFile $SchemaPath -ErrorAction SilentlyContinue
    if (-not $isValid) {
        throw "Fixture '$FixtureId' failed schema validation against '$SchemaPath'."
    }
}

function Assert-SchemaExists {
    param(
        [string]$SchemaPath,
        [string]$ContractId,
        [string]$Side
    )
    if ([string]::IsNullOrWhiteSpace($SchemaPath)) {
        throw "Contract '$ContractId' has no $Side schema."
    }
    $absolutePath = Join-Path $RepositoryRoot $SchemaPath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Contract '$ContractId' $Side schema is missing: $SchemaPath"
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

function Get-OptionalProperty {
    param(
        [object]$Object,
        [string]$Name
    )
    if ($null -eq $Object) {
        return $null
    }
    if ($Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $null
}

function Get-ExtractedResourceBytes {
    param(
        [string]$SourcePath,
        [object]$Extraction,
        [string]$ResourceId
    )
    $kind = [string]$Extraction.kind
    if ($kind -ne 'python-double-quoted-literal-region') {
        throw "Resource '$ResourceId' declares an unsupported extraction kind '$kind'."
    }
    $encodingName = [string]$Extraction.encoding
    if ($encodingName -ne 'utf-8') {
        throw "Resource '$ResourceId' extraction encoding must be utf-8."
    }
    # Latin1 round-trips every byte 1:1, so anchors and the extracted region keep exact bytes.
    $bytes = [System.IO.File]::ReadAllBytes($SourcePath)
    $latin1 = [System.Text.Encoding]::Latin1.GetString($bytes)
    $startAnchor = [string]$Extraction.start_anchor
    $endAnchor = [string]$Extraction.end_anchor
    if ([string]::IsNullOrEmpty($startAnchor) -or [string]::IsNullOrEmpty($endAnchor)) {
        throw "Resource '$ResourceId' extraction is missing an anchor."
    }
    $start = $latin1.IndexOf($startAnchor, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "Resource '$ResourceId' extraction start anchor was not found in $SourcePath."
    }
    $contentStart = $start + $startAnchor.Length
    $end = $latin1.IndexOf($endAnchor, $contentStart, [System.StringComparison]::Ordinal)
    if ($end -lt 0) {
        throw "Resource '$ResourceId' extraction end anchor was not found in $SourcePath."
    }
    $region = $latin1.Substring($contentStart, $end - $contentStart)
    $failOnBackslash = $true
    if ($Extraction.PSObject.Properties.Name -contains 'fail_on_backslash') {
        $failOnBackslash = [bool]$Extraction.fail_on_backslash
    }
    if ($failOnBackslash) {
        foreach ($character in $region.ToCharArray()) {
            if ($character -eq [char]92) {
                throw "Resource '$ResourceId' extracted region contains a backslash; failing closed."
            }
        }
    }
    return [System.Text.Encoding]::Latin1.GetBytes($region)
}

function Get-BytesSha256 {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Assert-ThreadHookMatrix {
    param(
        [object]$Matrix,
        [string]$SourceRoot,
        [object]$ManifestSurfaces,
        [System.Collections.Generic.HashSet[string]]$ExpandedOperationIds
    )

    if ($null -eq $Matrix) {
        throw 'Expanded inventory lost the thread hook consumption matrix link.'
    }
    Assert-Equal $Matrix.schema_version 'w00.thread-hook-consumption/v1' 'Unexpected thread hook consumption matrix schema'
    Assert-Equal $Matrix.source_sha $ExpectedSourceSha 'Thread hook consumption matrix source SHA drifted'
    Assert-Equal $Matrix.status 'SOURCE_VERIFIED' 'Thread hook consumption matrix must remain source-verified'
    Assert-Equal $Matrix.runtime_status 'NOT_RUN' 'Thread hook consumption matrix must not claim runtime PASS or FAIL'

    $matrixSourcePath = [string]$Matrix.source_path
    if ($matrixSourcePath -ne 'frontend/src/core/threads/hooks.ts') {
        throw "Thread hook consumption matrix points at an unexpected source: $matrixSourcePath"
    }
    $absoluteSource = Join-Path $SourceRoot $matrixSourcePath
    if (-not (Test-Path -LiteralPath $absoluteSource -PathType Leaf)) {
        throw "Thread hook consumption matrix source is missing at frozen checkout: $matrixSourcePath"
    }
    $source = Get-Content -LiteralPath $absoluteSource -Raw

    $sdkBoundary = Get-OptionalProperty -Object $Matrix -Name 'sdk_boundary'
    if ($null -eq $sdkBoundary) {
        throw 'Thread hook consumption matrix has no SDK boundary record.'
    }
    foreach ($field in @('package', 'locked_version', 'lockfile', 'lockfile_integrity', 'classification', 'note')) {
        if ([string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $sdkBoundary -Name $field))) {
            throw "Thread hook consumption SDK boundary lacks '$field'."
        }
    }
    $localInstallAvailable = [bool](Get-OptionalProperty -Object $sdkBoundary -Name 'local_install_available')
    if ($localInstallAvailable) {
        $packageFiles = @(Get-OptionalProperty -Object $sdkBoundary -Name 'package_files')
        if ($packageFiles.Count -eq 0) {
            throw 'Thread hook consumption SDK boundary claims a local install without package file hashes.'
        }
    }

    if (-not ($Matrix.PSObject.Properties.Name -contains 'entries')) {
        throw 'Thread hook consumption matrix has no entries.'
    }
    $entries = @($Matrix.entries)
    if ($entries.Count -eq 0) {
        throw 'Thread hook consumption matrix contains no entries.'
    }

    # The required frozen-hook census is verifier-owned. A matrix edit must not be able to
    # remove both an entry and its own required_entry_ids marker to certify itself as complete.
    $expectedHookEntryIds = @(
        'hooks.useThreadStream.client.config',
        'hooks.useThreadStream.onCreated.agent-metadata',
        'hooks.useThreadStream.onUpdateEvent.thread-state',
        'hooks.useThreadStream.onCustomEvent.task-events',
        'hooks.useThreadStream.onError.retry-and-invalidate',
        'hooks.useThreadStream.onFinish.invalidate',
        'hooks.useThreadStream.active-run-rejoin',
        'hooks.useThreadStream.stop.cancel',
        'hooks.buildRunContext.mode-gating',
        'hooks.sendMessage.uploads',
        'hooks.sendMessage.submit',
        'hooks.submitPreparedReplay.submit',
        'hooks.regenerateMessage.prepare',
        'hooks.editAndRegenerateMessage.prepare',
        'hooks.useThreadHistory.messages-page',
        'hooks.useThreads.search',
        'hooks.useInfiniteThreads.search',
        'hooks.useThreadRuns.list',
        'hooks.useThreadMetadata.get',
        'hooks.useThreadTokenUsage.browser',
        'hooks.useBranchThread.branch',
        'hooks.usePinThread.patch-metadata',
        'hooks.useMoveThreadToProject.move',
        'hooks.useRunDetail.get',
        'hooks.useDeleteThread.delete',
        'hooks.deleteLocalThreadData.delete',
        'hooks.findSidecarThreadIdsForParent.search',
        'hooks.useRenameThread.update-state'
    )

    $surfaceIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($surface in $ManifestSurfaces) {
        $null = $surfaceIds.Add([string]$surface.id)
    }

    $entryIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $hookTriggers = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($entry in $entries) {
        $entryId = [string](Get-OptionalProperty -Object $entry -Name 'id')
        if ([string]::IsNullOrWhiteSpace($entryId)) {
            throw 'Thread hook consumption entry has no id.'
        }
        if (-not $entryIds.Add($entryId)) {
            throw "Duplicate thread hook consumption entry: $entryId"
        }
        foreach ($field in @('hook', 'trigger', 'condition', 'consumer', 'auth', 'evidence_kind')) {
            if ([string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $entry -Name $field))) {
                throw "Thread hook consumption entry '$entryId' lacks '$field'."
            }
        }
        Assert-OneOf ([string]$entry.consumer) @('langgraph_sdk', 'browser_fetch') "Thread hook consumption entry '$entryId' has an invalid consumer"
        $hookTrigger = "$([string]$entry.hook)|$([string]$entry.trigger)"
        if (-not $hookTriggers.Add($hookTrigger)) {
            throw "Conflicting thread hook consumption mapping for hook/trigger: $hookTrigger"
        }
        $sdkCall = [string](Get-OptionalProperty -Object $entry -Name 'sdk_call')
        $httpMethod = [string](Get-OptionalProperty -Object $entry -Name 'method')
        $httpPath = [string](Get-OptionalProperty -Object $entry -Name 'path')
        $hasSdkCall = -not [string]::IsNullOrWhiteSpace($sdkCall)
        $hasHttpCall = (-not [string]::IsNullOrWhiteSpace($httpMethod)) -and (-not [string]::IsNullOrWhiteSpace($httpPath))
        if ($hasSdkCall -and $hasHttpCall) {
            throw "Conflicting thread hook consumption mapping for '$entryId': both sdk_call and HTTP method/path are set."
        }
        if ([string]$entry.consumer -eq 'langgraph_sdk' -and -not $hasSdkCall) {
            throw "Thread hook consumption entry '$entryId' claims an SDK consumer without sdk_call."
        }
        if ([string]$entry.consumer -eq 'browser_fetch' -and -not $hasHttpCall) {
            throw "Thread hook consumption entry '$entryId' claims a browser consumer without method/path."
        }
        $entryRuntime = Get-OptionalProperty -Object $entry -Name 'runtime_status'
        if ($null -ne $entryRuntime) {
            Assert-Equal ([string]$entryRuntime) 'NOT_RUN' "Thread hook consumption entry '$entryId' must not claim runtime PASS or FAIL"
        }
        if (-not $localInstallAvailable -and [string]$entry.evidence_kind -ne 'frozen_hook_source') {
            throw "Thread hook consumption entry '$entryId' claims non-frozen evidence without an installed SDK package."
        }

        $anchors = @(Get-OptionalProperty -Object $entry -Name 'source_anchors')
        if ($anchors.Count -eq 0) {
            throw "Thread hook consumption entry '$entryId' has no source anchors."
        }
        foreach ($anchorValue in $anchors) {
            $anchor = [string]$anchorValue
            if (-not $source.Contains($anchor, [System.StringComparison]::Ordinal)) {
                throw "Thread hook consumption entry '$entryId' lost source anchor '$anchor' in '$matrixSourcePath'."
            }
        }

        $wireFields = @(Get-OptionalProperty -Object $entry -Name 'wire_fields')
        if ($wireFields.Count -eq 0) {
            throw "Thread hook consumption entry '$entryId' has no wire fields."
        }

        $bound = 0
        foreach ($surfaceIdValue in @(Get-OptionalProperty -Object $entry -Name 'surface_ids')) {
            $surfaceId = [string]$surfaceIdValue
            if (-not $surfaceIds.Contains($surfaceId)) {
                throw "Thread hook consumption entry '$entryId' points to unknown surface '$surfaceId'."
            }
            $bound += 1
        }
        foreach ($operationIdValue in @(Get-OptionalProperty -Object $entry -Name 'operation_ids')) {
            $operationId = [string]$operationIdValue
            if (-not $ExpandedOperationIds.Contains($operationId)) {
                throw "Thread hook consumption entry '$entryId' points to unknown operation '$operationId'."
            }
            $bound += 1
        }
        if ($bound -eq 0) {
            throw "Thread hook consumption entry '$entryId' binds no surface or operation."
        }
    }

    foreach ($requiredEntryIdValue in @(Get-OptionalProperty -Object $Matrix -Name 'required_entry_ids')) {
        $requiredEntryId = [string]$requiredEntryIdValue
        if (-not $entryIds.Contains($requiredEntryId)) {
            throw "Required thread hook consumption entry is missing: $requiredEntryId"
        }
    }
    $declaredRequiredIds = @((Get-OptionalProperty -Object $Matrix -Name 'required_entry_ids') | ForEach-Object { [string]$_ })
    if ($declaredRequiredIds.Count -ne $expectedHookEntryIds.Count -or
            @(Compare-Object ($expectedHookEntryIds | Sort-Object) ($declaredRequiredIds | Sort-Object)).Count -gt 0) {
        throw 'Thread hook consumption required_entry_ids drifted from the independent verifier baseline.'
    }
    foreach ($expectedEntryId in $expectedHookEntryIds) {
        if (-not $entryIds.Contains($expectedEntryId)) {
            throw "Independent frozen hook baseline entry is missing: $expectedEntryId"
        }
    }

    $errorSemantics = Get-OptionalProperty -Object $Matrix -Name 'error_semantics'
    if ($null -eq $errorSemantics) {
        throw 'Thread hook consumption matrix has no error semantics record.'
    }
    foreach ($field in @('browser_fetch', 'langgraph_sdk')) {
        if ([string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $errorSemantics -Name $field))) {
            throw "Thread hook consumption error semantics lacks '$field'."
        }
    }
    foreach ($anchorValue in @(Get-OptionalProperty -Object $errorSemantics -Name 'source_anchors')) {
        $anchor = [string]$anchorValue
        if ([string]::IsNullOrWhiteSpace($anchor) -or -not $source.Contains($anchor, [System.StringComparison]::Ordinal)) {
            throw "Thread hook consumption error semantics lost source anchor '$anchor'."
        }
    }

    $excludedBranches = @(Get-OptionalProperty -Object $Matrix -Name 'excluded_branches')
    if ($excludedBranches.Count -eq 0) {
        throw 'Thread hook consumption matrix lists no excluded branches.'
    }
    foreach ($branch in $excludedBranches) {
        if ([string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $branch -Name 'id')) -or
            [string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $branch -Name 'reason'))) {
            throw 'Thread hook consumption excluded branch lacks an id or reason.'
        }
    }
    $gaps = @(Get-OptionalProperty -Object $Matrix -Name 'not_inventoried')
    if ($gaps.Count -eq 0) {
        throw 'Thread hook consumption matrix lists no not-inventoried gaps.'
    }
    foreach ($gap in $gaps) {
        if ([string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $gap -Name 'id')) -or
            [string]::IsNullOrWhiteSpace([string](Get-OptionalProperty -Object $gap -Name 'reason'))) {
            throw 'Thread hook consumption not-inventoried gap lacks an id or reason.'
        }
    }
}

$resolvedDeerFlowRoot = Resolve-DeerFlowRoot -ExplicitRoot $DeerFlowRoot
$manifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ManifestPath))
$expandedInventory = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ExpandedInventoryPath))
$resourceManifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ResourceManifestPath))
$oracleManifest = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($OracleManifestPath))
$oracleFixtures = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($OracleFixturesPath))

Assert-Equal $manifest.schema_version 'w00.frontend-consumer-contract/v1' 'Unexpected contract manifest schema'
Assert-Equal $manifest.scope 'incremental_seed' 'W00 manifest must remain an incremental seed'
Assert-Equal $manifest.status 'SOURCE_VERIFIED' 'Static manifest status must be SOURCE_VERIFIED'
Assert-Equal $manifest.runtime_status 'NOT_RUN' 'Static extraction must not claim runtime PASS'
Assert-Equal $manifest.source.source_sha $ExpectedSourceSha 'Contract manifest source SHA drifted'
Assert-Equal $manifest.core_protocol_oracle 'contracts/upstream/deerflow/core-protocol-oracle.json' 'Frontend manifest lost the core protocol Oracle link'
Assert-Equal $manifest.expanded_inventory 'contracts/upstream/deerflow/frontend-consumer-expanded.json' 'Frontend manifest lost expanded consumer inventory link'
Assert-Equal $expandedInventory.schema_version 'w00.frontend-consumer-expanded/v1' 'Unexpected expanded consumer inventory schema'
Assert-Equal $expandedInventory.source_sha $ExpectedSourceSha 'Expanded consumer inventory source SHA drifted'
Assert-Equal $expandedInventory.status 'SOURCE_VERIFIED' 'Expanded consumer inventory must remain source-verified'
Assert-Equal $expandedInventory.runtime_status 'NOT_RUN' 'Expanded consumer inventory must not claim runtime PASS or FAIL'
Assert-Equal $expandedInventory.completion_status 'PARTIAL' 'Known residuals forbid a full W00 completion claim'
Assert-Equal $expandedInventory.consumer 'browser_fetch' 'Expanded operations must remain in the browser fetch partition'
Assert-Equal $resourceManifest.source.source_sha $ExpectedSourceSha 'Resource manifest source SHA drifted'
Assert-Equal $resourceManifest.runtime_status 'NOT_RUN' 'Resource seed must not claim runtime PASS'
Assert-Equal $resourceManifest.repository_import.status 'IMPORTED' 'Frozen W18 frontend import must be recorded separately from runtime status'
Assert-Equal $resourceManifest.repository_import.runtime_status 'NOT_RUN' 'Imported source must not claim runtime parity PASS'

Assert-Equal $oracleManifest.schema_version 'w00.core-protocol-oracle/v2' 'Unexpected core protocol Oracle schema'
Assert-Equal $oracleManifest.source.source_sha $ExpectedSourceSha 'Core protocol Oracle source SHA drifted'
Assert-Equal $oracleManifest.source_status 'SOURCE_VERIFIED' 'Core protocol Oracle source status is invalid'
Assert-Equal $oracleManifest.contract_status 'STATIC_CONTRACT_VERIFIED' 'Core protocol Oracle contract status is invalid'
Assert-Equal $oracleManifest.implementation_status 'NOT_ASSESSED' 'Static core protocol Oracle must keep implementation NOT_ASSESSED'
Assert-Equal $oracleManifest.runtime_status 'NOT_RUN' 'Static core protocol Oracle must not claim runtime PASS or FAIL'
Assert-Equal $oracleManifest.fixtures_path 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json' 'Core protocol Oracle fixture link drifted'
Assert-Equal $oracleFixtures.schema_version 'w00.core-protocol-fixtures/v2' 'Unexpected core protocol fixture schema'
Assert-Equal $oracleFixtures.source_sha $ExpectedSourceSha 'Core protocol fixtures source SHA drifted'
Assert-Equal $oracleFixtures.source_status 'SOURCE_VERIFIED' 'Core protocol fixture source status is invalid'
Assert-Equal $oracleFixtures.contract_status 'STATIC_CONTRACT_VERIFIED' 'Core protocol fixture contract status is invalid'
Assert-Equal $oracleFixtures.implementation_status 'NOT_ASSESSED' 'Static core protocol fixtures must keep implementation NOT_ASSESSED'
Assert-Equal $oracleFixtures.runtime_status 'NOT_RUN' 'Static core protocol fixtures must not claim runtime PASS or FAIL'

$w18ManifestPath = Join-Path $RepositoryRoot ([string]$resourceManifest.repository_import.manifest_path)
$w18Manifest = Read-JsonFile -Path $w18ManifestPath
Assert-Equal $w18Manifest.upstream.commit $ExpectedSourceSha 'W18 import manifest is not bound to the frozen DeerFlow SHA'
Assert-Equal $w18Manifest.upstream.sourceTree $resourceManifest.repository_import.source_tree 'W18 import source tree drifted from the W00 seed'
Assert-Equal $w18Manifest.import.localPath $resourceManifest.repository_import.frontend_local_path 'W18 frontend import path drifted from the W00 seed'
$embeddedLicensePaths = @($resourceManifest.license_review.frontend_embedded_license_paths)
foreach ($embeddedLicensePath in @($w18Manifest.upstream.embeddedLicenseFiles)) {
    if ($embeddedLicensePaths -notcontains [string]$embeddedLicensePath) {
        throw "Nested frontend license is missing from W00 review: $embeddedLicensePath"
    }
}

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

$expandedFamilies = @($expandedInventory.families)
$expandedFamilyIds = @($expandedFamilies | ForEach-Object { [string]$_.id })
if (@($expandedFamilyIds | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Expanded consumer inventory has duplicate family IDs.'
}
foreach ($requiredId in @($expandedInventory.required_family_ids)) {
    if ($expandedFamilyIds -notcontains [string]$requiredId) {
        throw "Required expanded consumer family is missing: $requiredId"
    }
}
$expandedOperationIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$wireKeys = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($surface in $surfaces) {
    foreach ($method in @($surface.methods)) {
        $key = "$([string]$surface.consumer)|$([string]$method)|$([string]$surface.public_path)"
        if (-not $wireKeys.Add($key)) {
            throw "Duplicate original consumer method/path key: $key"
        }
    }
}
$gatewayAppSource = Get-Content -LiteralPath (Join-Path $resolvedDeerFlowRoot 'backend/app/gateway/app.py') -Raw
foreach ($family in $expandedFamilies) {
    $familyId = [string]$family.id
    $sourcePath = [string]$family.source_path
    if (-not $sourcePath.StartsWith('frontend/src/', [System.StringComparison]::Ordinal) -or
        -not $sourcePath.EndsWith('.ts', [System.StringComparison]::Ordinal)) {
        throw "Expanded consumer family '$familyId' has an invalid frontend source path."
    }
    $absoluteSource = Join-Path $resolvedDeerFlowRoot $sourcePath
    if (-not (Test-Path -LiteralPath $absoluteSource -PathType Leaf)) {
        throw "Expanded consumer family '$familyId' source is missing: $sourcePath"
    }
    $source = Get-Content -LiteralPath $absoluteSource -Raw
    $gatewayRegistration = [string]$family.gateway_registration
    if ([string]::IsNullOrWhiteSpace($gatewayRegistration) -or
        -not $gatewayAppSource.Contains($gatewayRegistration, [System.StringComparison]::Ordinal)) {
        throw "Expanded consumer family '$familyId' lost gateway registration evidence."
    }
    if ($gatewayRegistration -notmatch '^app\.include_router\(([a-z_]+)\.router\)$' -or
        -not (Test-Path -LiteralPath (Join-Path $resolvedDeerFlowRoot "backend/app/gateway/routers/$($Matches[1]).py") -PathType Leaf)) {
        throw "Expanded consumer family '$familyId' has no matching frozen gateway router file."
    }
    if ([string]::IsNullOrWhiteSpace([string]$family.registration_condition) -or
        [string]::IsNullOrWhiteSpace([string]$family.auth)) {
        throw "Expanded consumer family '$familyId' lacks registration/auth semantics."
    }
    $operations = @($family.operations)
    if ($operations.Count -eq 0) {
        throw "Expanded consumer family '$familyId' has no operations."
    }
    foreach ($operation in $operations) {
        $operationId = [string]$operation.id
        if (-not $expandedOperationIds.Add($operationId)) {
            throw "Expanded consumer inventory has duplicate operation '$operationId'."
        }
        Assert-OneOf $operation.method @('GET', 'POST', 'PUT', 'PATCH', 'DELETE') "Expanded operation '$operationId' has an invalid method"
        if (-not ([string]$operation.path).StartsWith('/api/', [System.StringComparison]::Ordinal)) {
            throw "Expanded operation '$operationId' lacks a public API path."
        }
        $wireKey = "$([string]$expandedInventory.consumer)|$([string]$operation.method)|$([string]$operation.path)"
        if (-not $wireKeys.Add($wireKey)) {
            throw "Duplicate consumer method/path key across W00 partitions: $wireKey"
        }
        $functionAnchor = "function $([string]$operation.function)("
        $functionStart = $source.IndexOf($functionAnchor, [System.StringComparison]::Ordinal)
        if ($functionStart -lt 0) {
            throw "Expanded operation '$operationId' lost frontend function evidence '$functionAnchor'."
        }
        $nextExport = $source.IndexOf("`nexport ", $functionStart + $functionAnchor.Length, [System.StringComparison]::Ordinal)
        $functionEnd = if ($nextExport -ge 0) { $nextExport } else { $source.Length }
        $functionSource = $source.Substring($functionStart, $functionEnd - $functionStart)
        $routeAnchor = [string]$operation.route_anchor
        if ([string]::IsNullOrWhiteSpace($routeAnchor) -or
            -not $functionSource.Contains($routeAnchor, [System.StringComparison]::Ordinal)) {
            throw "Expanded operation '$operationId' lost frontend route evidence '$routeAnchor'."
        }
        if ([string]::IsNullOrWhiteSpace([string]$operation.response)) {
            throw "Expanded operation '$operationId' lacks a response shape."
        }
    }
}
foreach ($requiredId in @($expandedInventory.required_operation_ids)) {
    if (-not $expandedOperationIds.Contains([string]$requiredId)) {
        throw "Required expanded consumer operation is missing: $requiredId"
    }
}
$accountedModules = [System.Collections.Generic.List[string]]::new()
$expandedNonApiModules = [System.Collections.Generic.List[string]]::new()
foreach ($family in $expandedFamilies) {
    $familySourcePath = [string]$family.source_path
    if ($familySourcePath.EndsWith('/api.ts', [System.StringComparison]::Ordinal)) {
        $accountedModules.Add($familySourcePath)
    }
    else {
        $expandedNonApiModules.Add($familySourcePath)
    }
}
foreach ($modulePath in @($expandedInventory.source_module_accounting.seeded_modules)) {
    $accountedModules.Add([string]$modulePath)
}
foreach ($sharedModule in @($expandedInventory.source_module_accounting.shared_modules)) {
    Assert-Equal $sharedModule.status 'SOURCE_VERIFIED' "Shared module '$($sharedModule.source_path)' has an invalid status"
    $sharedPath = [string]$sharedModule.source_path
    $sharedSourcePath = Join-Path $resolvedDeerFlowRoot $sharedPath
    if (-not (Test-Path -LiteralPath $sharedSourcePath -PathType Leaf)) {
        throw "Shared frontend API source module is missing: $sharedPath"
    }
    $sharedSource = Get-Content -LiteralPath $sharedSourcePath -Raw
    foreach ($anchor in @($sharedModule.source_anchors)) {
        if (-not $sharedSource.Contains([string]$anchor, [System.StringComparison]::Ordinal)) {
            throw "Shared frontend API source module '$sharedPath' lost callsite '$anchor'."
        }
    }
    foreach ($operationId in @($sharedModule.operation_ids)) {
        if (-not $expandedOperationIds.Contains([string]$operationId) -and $ids -notcontains [string]$operationId) {
            throw "Shared frontend API source module '$sharedPath' points to unknown operation '$operationId'."
        }
    }
    $accountedModules.Add($sharedPath)
}
foreach ($residual in @($expandedInventory.source_module_accounting.residual_modules)) {
    Assert-Equal $residual.status 'NOT_INVENTORIED' "Residual module '$($residual.source_path)' cannot claim completion"
    if ([string]::IsNullOrWhiteSpace([string]$residual.reason)) {
        throw "Residual module '$($residual.source_path)' has no reason."
    }
    $accountedModules.Add([string]$residual.source_path)
}
$duplicateAccountedModules = @($accountedModules | Group-Object | Where-Object Count -gt 1)
if ($duplicateAccountedModules.Count -gt 0) {
    throw "Frontend API source module was accounted more than once: $($duplicateAccountedModules[0].Name)"
}
$frozenApiModules = @(Get-ChildItem -LiteralPath (Join-Path $resolvedDeerFlowRoot 'frontend/src/core') -Recurse -Filter 'api.ts' -File |
    ForEach-Object { [System.IO.Path]::GetRelativePath($resolvedDeerFlowRoot, $_.FullName).Replace('\', '/') })
foreach ($modulePath in $frozenApiModules) {
    if ($accountedModules -notcontains $modulePath) {
        throw "Frozen frontend API source module is unaccounted: $modulePath"
    }
}
foreach ($modulePath in $accountedModules) {
    if ($frozenApiModules -notcontains $modulePath) {
        throw "Accounted frontend API source module is absent from frozen checkout: $modulePath"
    }
}
foreach ($residual in @($expandedInventory.non_api_module_residuals)) {
    Assert-Equal $residual.status 'NOT_INVENTORIED' "Non-API residual '$($residual.source_path)' cannot claim completion"
    if ([string]::IsNullOrWhiteSpace([string]$residual.reason) -or
        -not (Test-Path -LiteralPath (Join-Path $resolvedDeerFlowRoot ([string]$residual.source_path)) -PathType Leaf)) {
        throw "Non-API residual '$($residual.source_path)' lacks a reason or frozen source file."
    }
    if ($expandedNonApiModules -contains [string]$residual.source_path) {
        throw "Non-API source is both inventoried and residual: $($residual.source_path)"
    }
}
$accountedNonApiPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($accounted in @($expandedInventory.non_api_accounted_modules)) {
    Assert-Equal $accounted.status 'SOURCE_VERIFIED' "Non-API source '$($accounted.source_path)' has an invalid status"
    $accountedPath = [string]$accounted.source_path
    if (-not $accountedNonApiPaths.Add($accountedPath) -or
        @($expandedInventory.non_api_module_residuals | Where-Object source_path -eq $accountedPath).Count -gt 0) {
        throw "Non-API source '$accountedPath' has duplicate or residual classification."
    }
    $accountedSourcePath = Join-Path $resolvedDeerFlowRoot $accountedPath
    if (-not $accountedPath.StartsWith('frontend/src/', [System.StringComparison]::Ordinal) -or
        -not (Test-Path -LiteralPath $accountedSourcePath -PathType Leaf)) {
        throw "Accounted non-API source is absent from frozen checkout: $accountedPath"
    }
    $accountedSource = Get-Content -LiteralPath $accountedSourcePath -Raw
    if (@($accounted.source_anchors).Count -eq 0 -or @($accounted.surface_ids).Count -eq 0) {
        throw "Accounted non-API source '$accountedPath' lacks source anchors or surface IDs."
    }
    foreach ($anchor in @($accounted.source_anchors)) {
        if (-not $accountedSource.Contains([string]$anchor, [System.StringComparison]::Ordinal)) {
            throw "Accounted non-API source '$accountedPath' lost source anchor '$anchor'."
        }
    }
    foreach ($surfaceId in @($accounted.surface_ids)) {
        if ($ids -notcontains [string]$surfaceId) {
            throw "Accounted non-API source '$accountedPath' points to unknown surface '$surfaceId'."
        }
    }
}
foreach ($requiredPath in @($expandedInventory.required_non_api_accounted_paths)) {
    if (-not $accountedNonApiPaths.Contains([string]$requiredPath)) {
        throw "Required accounted non-API source is missing: $requiredPath"
    }
}
if (@($expandedInventory.required_non_api_accounted_paths | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Required accounted non-API source path was listed more than once.'
}
foreach ($excluded in @($expandedInventory.excluded_external_calls)) {
    Assert-Equal $excluded.status 'EXCLUDED_EXTERNAL' "Excluded call '$($excluded.source_path)' has an invalid classification"
    if ([string]::IsNullOrWhiteSpace([string]$excluded.reason) -or
        ([string]$excluded.source_anchor).StartsWith('/api/', [System.StringComparison]::Ordinal)) {
        throw "Excluded call '$($excluded.source_path)' lacks external-only evidence."
    }
    $excludedPath = Join-Path $resolvedDeerFlowRoot ([string]$excluded.source_path)
    if (-not (Test-Path -LiteralPath $excludedPath -PathType Leaf) -or
        -not (Get-Content -LiteralPath $excludedPath -Raw).Contains([string]$excluded.source_anchor, [System.StringComparison]::Ordinal)) {
        throw "Excluded external call '$($excluded.source_path)' lost source evidence."
    }
    if ($expandedNonApiModules -contains [string]$excluded.source_path -or
        @($expandedInventory.non_api_module_residuals | Where-Object source_path -eq $excluded.source_path).Count -gt 0) {
        throw "External call '$($excluded.source_path)' has conflicting inventory classification."
    }
}
$nextRoutes = [System.Collections.Generic.List[string]]::new()
foreach ($category in @('gateway_bff', 'external_only', 'mock_only_not_parity')) {
    foreach ($route in @($expandedInventory.next_route_accounting.$category)) {
        $nextRoutes.Add([string]$route)
    }
}
if (@($nextRoutes | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Next Route Handler was classified more than once.'
}
$frozenNextRoutes = @(Get-ChildItem -LiteralPath (Join-Path $resolvedDeerFlowRoot 'frontend/src/app') -Recurse -Filter 'route.ts' -File |
    ForEach-Object { [System.IO.Path]::GetRelativePath($resolvedDeerFlowRoot, $_.FullName).Replace('\', '/') })
foreach ($route in $frozenNextRoutes) {
    if ($nextRoutes -notcontains $route) {
        throw "Frozen Next Route Handler is unaccounted: $route"
    }
}
foreach ($route in $nextRoutes) {
    if ($frozenNextRoutes -notcontains $route) {
        throw "Classified Next Route Handler is missing from frozen checkout: $route"
    }
}
foreach ($route in @($expandedInventory.next_route_accounting.mock_only_not_parity)) {
    if (-not ([string]$route).StartsWith('frontend/src/app/mock/', [System.StringComparison]::Ordinal)) {
        throw "Non-mock Next Route Handler was mislabeled mock-only: $route"
    }
}
$browserWsCallsites = @(Get-ChildItem -LiteralPath (Join-Path $resolvedDeerFlowRoot 'frontend/src') -Recurse -File |
    Where-Object { $_.Extension -in @('.ts', '.tsx') } |
    Where-Object { (Get-Content -LiteralPath $_.FullName -Raw).Contains('new WebSocket(', [System.StringComparison]::Ordinal) } |
    ForEach-Object { [System.IO.Path]::GetRelativePath($resolvedDeerFlowRoot, $_.FullName).Replace('\', '/') })
if ($browserWsCallsites.Count -ne 1 -or $browserWsCallsites[0] -ne 'frontend/src/components/workspace/browser-view/use-browser-stream.ts') {
    throw "Frozen browser WebSocket callsites drifted: $($browserWsCallsites -join ', ')"
}
if (@($expandedInventory.source_module_accounting.residual_modules).Count -eq 0 -and
    @($expandedInventory.non_api_module_residuals).Count -eq 0) {
    throw 'Expanded inventory claims PARTIAL but lists no residual source modules.'
}

$expandedHookMatrixLink = [string](Get-OptionalProperty -Object $expandedInventory -Name 'hooks_consumption_matrix')
if ($expandedHookMatrixLink -ne 'contracts/upstream/deerflow/thread-hook-consumption-matrix.json') {
    throw 'Expanded inventory lost the thread hook consumption matrix link.'
}
$threadHookMatrix = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($ThreadHookMatrixPath))
Assert-ThreadHookMatrix -Matrix $threadHookMatrix -SourceRoot $resolvedDeerFlowRoot -ManifestSurfaces $surfaces -ExpandedOperationIds $expandedOperationIds

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
    Assert-Equal $contract.implementation_status 'NOT_ASSESSED' "Static contract '$($contract.id)' must keep implementation NOT_ASSESSED"
    Assert-Equal $contract.runtime_status 'NOT_RUN' "Contract '$($contract.id)' must not claim runtime PASS or FAIL"
    $hasExchangeSchemas = $contract.PSObject.Properties.Name -contains 'request_schema_path' -or
        $contract.PSObject.Properties.Name -contains 'response_schema_path'
    if ($hasExchangeSchemas) {
        Assert-SchemaExists -SchemaPath ([string]$contract.request_schema_path) -ContractId ([string]$contract.id) -Side 'request'
        Assert-SchemaExists -SchemaPath ([string]$contract.response_schema_path) -ContractId ([string]$contract.id) -Side 'response'
        $successStatus = $contract.http_status.success
        if ($successStatus -isnot [int] -and $successStatus -isnot [long]) {
            throw "Contract '$($contract.id)' has no numeric success HTTP status."
        }
        $errorStatuses = @($contract.http_status.errors)
        if ($errorStatuses.Count -eq 0) {
            throw "Contract '$($contract.id)' has no fail-closed error status inventory."
        }
        foreach ($errorStatus in $errorStatuses) {
            if ($errorStatus.status -isnot [int] -and $errorStatus.status -isnot [long]) {
                throw "Contract '$($contract.id)' contains a non-numeric error status."
            }
            if ([string]::IsNullOrWhiteSpace([string]$errorStatus.condition)) {
                throw "Contract '$($contract.id)' contains an error status without a condition."
            }
        }
    }
    else {
        Assert-SchemaExists -SchemaPath ([string]$contract.schema_path) -ContractId ([string]$contract.id) -Side 'payload'
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
    $hasExchangeSchemas = $contract.PSObject.Properties.Name -contains 'request_schema_path' -or
        $contract.PSObject.Properties.Name -contains 'response_schema_path'
    if ($hasExchangeSchemas) {
        Assert-Equal $fixture.request_schema_path $contract.request_schema_path "Fixture '$($fixture.id)' request schema does not match contract '$contractId'"
        Assert-Equal $fixture.response_schema_path $contract.response_schema_path "Fixture '$($fixture.id)' response schema does not match contract '$contractId'"
        Assert-JsonMatchesSchema -Payload $fixture.request -SchemaPath (Join-Path $RepositoryRoot ([string]$fixture.request_schema_path)) -FixtureId "$($fixture.id) request"
        Assert-JsonMatchesSchema -Payload $fixture.response -SchemaPath (Join-Path $RepositoryRoot ([string]$fixture.response_schema_path)) -FixtureId "$($fixture.id) response"
    }
    else {
        Assert-Equal $fixture.schema_path $contract.schema_path "Fixture '$($fixture.id)' schema does not match contract '$contractId'"
        $schemaPath = Join-Path $RepositoryRoot ([string]$fixture.schema_path)
        Assert-JsonMatchesSchema -Payload $fixture.payload -SchemaPath $schemaPath -FixtureId ([string]$fixture.id)
    }
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
    $id = $resource.id
    $hasSource = ($resource.PSObject.Properties.Name -contains 'source_path') -and
        -not [string]::IsNullOrWhiteSpace([string]$resource.source_path)
    $hasLocalHash = $resource.PSObject.Properties.Name -contains 'local_sha256'
    $hasExtraction = $resource.PSObject.Properties.Name -contains 'extraction'
    if (-not $hasSource -and -not $hasLocalHash) {
        throw "Resource '$id' declares neither a frozen source nor a local content hash."
    }
    $resourcePath = $null
    $sourceHash = $null
    if ($hasSource) {
        $resourcePath = Join-Path $resolvedDeerFlowRoot ([string]$resource.source_path)
        if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) {
            throw "Resource '$id' source is missing: $($resource.source_path)"
        }
        $sourceHash = (Get-FileHash -LiteralPath $resourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-Equal $sourceHash ([string]$resource.sha256).ToLowerInvariant() "Resource '$id' hash drifted"
    }
    if ($hasExtraction) {
        if (-not $hasSource) {
            throw "Resource '$id' declares an extraction without a frozen source_path."
        }
        $extractedBytes = Get-ExtractedResourceBytes -SourcePath $resourcePath -Extraction $resource.extraction -ResourceId $id
        Assert-Equal (Get-BytesSha256 -Bytes $extractedBytes) ([string]$resource.local_sha256).ToLowerInvariant() `
            "Resource '$id' extracted bytes drifted from local_sha256"
    }
    if ($resource.import_status -eq 'IMPORTED') {
        $importedPath = Join-Path $RepositoryRoot ([string]$resource.intended_local_path)
        if (-not (Test-Path -LiteralPath $importedPath -PathType Leaf)) {
            throw "Resource '$id' claims IMPORTED but local file is missing: $($resource.intended_local_path)"
        }
        $importedHash = (Get-FileHash -LiteralPath $importedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hasLocalHash) {
            Assert-Equal $importedHash ([string]$resource.local_sha256).ToLowerInvariant() `
                "Resource '$id' local content differs from local_sha256"
        }
        else {
            Assert-Equal $importedHash $sourceHash "Resource '$id' imported content differs from frozen source"
        }
    }
}
$resourceIds = @($resourceManifest.resources | ForEach-Object { [string]$_.id })
foreach ($requiredId in @($resourceManifest.required_license_resource_ids)) {
    if ($resourceIds -notcontains [string]$requiredId) {
        throw "Required nested license resource is missing: $requiredId"
    }
    $reviewedResource = @($resourceManifest.resources | Where-Object id -eq $requiredId)[0]
    if ([string]::IsNullOrWhiteSpace([string]$reviewedResource.license_assessment)) {
        throw "Required nested license resource lacks an assessment: $requiredId"
    }
}

$surfaceSummary = $surfaces | Group-Object consumer | Sort-Object Name | ForEach-Object {
    "$($_.Name)=$($_.Count)"
}
Write-Host "Verified DeerFlow W00 contract seed at $ExpectedSourceSha"
Write-Host "Surfaces: $($surfaces.Count) ($($surfaceSummary -join ', '))"
Write-Host "Expanded browser inventory: families=$($expandedFamilies.Count); operations=$($expandedOperationIds.Count); runtime=NOT_RUN"
Write-Host "Frozen core API modules accounted=$($frozenApiModules.Count); explicit residuals=$(@($expandedInventory.source_module_accounting.residual_modules).Count + @($expandedInventory.non_api_module_residuals).Count); completion=PARTIAL"
Write-Host "Next Route Handlers accounted=$($frozenNextRoutes.Count) (gateway BFF=$(@($expandedInventory.next_route_accounting.gateway_bff).Count), external=$(@($expandedInventory.next_route_accounting.external_only).Count), mock-only=$(@($expandedInventory.next_route_accounting.mock_only_not_parity).Count))"
Write-Host "Browser WebSocket callsites accounted=$($browserWsCallsites.Count)"
$oracleKindSummary = $oracleContracts | Group-Object kind | Sort-Object Name | ForEach-Object {
    "$($_.Name)=$($_.Count)"
}
Write-Host "Core protocol contracts: $($oracleContracts.Count) ($($oracleKindSummary -join ', ')); fixtures=$($fixtures.Count); implementation=$($oracleManifest.implementation_status); runtime=NOT_RUN"
$importedCount = @($resourceManifest.resources | Where-Object import_status -eq 'IMPORTED').Count
Write-Host "Resources: $(@($resourceManifest.resources).Count) (IMPORTED=$importedCount, NOT_RUN=$(@($resourceManifest.resources).Count - $importedCount)); runtime_status=NOT_RUN"
