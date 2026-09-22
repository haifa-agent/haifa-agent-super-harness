[CmdletBinding()]
param(
    [string]$DeerFlowRoot,
    [string]$ManifestPath,
    [string]$ResourceManifestPath
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

Assert-Equal $manifest.schema_version 'w00.frontend-consumer-contract/v1' 'Unexpected contract manifest schema'
Assert-Equal $manifest.scope 'incremental_seed' 'W00 manifest must remain an incremental seed'
Assert-Equal $manifest.status 'SOURCE_VERIFIED' 'Static manifest status must be SOURCE_VERIFIED'
Assert-Equal $manifest.runtime_status 'NOT_RUN' 'Static extraction must not claim runtime PASS'
Assert-Equal $manifest.source.source_sha $ExpectedSourceSha 'Contract manifest source SHA drifted'
Assert-Equal $resourceManifest.source.source_sha $ExpectedSourceSha 'Resource manifest source SHA drifted'
Assert-Equal $resourceManifest.runtime_status 'NOT_RUN' 'Resource seed must not claim runtime PASS'

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
    Assert-Equal $resource.import_status 'NOT_RUN' "Resource '$($resource.id)' must not claim import completion"
    $resourcePath = Join-Path $resolvedDeerFlowRoot ([string]$resource.source_path)
    if (-not (Test-Path -LiteralPath $resourcePath -PathType Leaf)) {
        throw "Resource '$($resource.id)' source is missing: $($resource.source_path)"
    }
    $actualHash = (Get-FileHash -LiteralPath $resourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal $actualHash ([string]$resource.sha256).ToLowerInvariant() "Resource '$($resource.id)' hash drifted"
}

$surfaceSummary = $surfaces | Group-Object consumer | Sort-Object Name | ForEach-Object {
    "$($_.Name)=$($_.Count)"
}
Write-Host "Verified DeerFlow W00 contract seed at $ExpectedSourceSha"
Write-Host "Surfaces: $($surfaces.Count) ($($surfaceSummary -join ', '))"
Write-Host "Resources: $(@($resourceManifest.resources).Count); runtime_status=NOT_RUN"

