[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DeerFlowRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$Verifier = Join-Path $RepositoryRoot 'scripts/contracts/verify-deerflow-contracts.ps1'
$Manifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
$ExpandedInventory = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-expanded.json'
$ResourceManifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-seed.json'
$OracleManifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/core-protocol-oracle.json'
$OracleFixtures = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json'
$ThreadHookMatrix = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/thread-hook-consumption-matrix.json'
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("haifa-w00-contract-tests-" + [guid]::NewGuid().ToString('N'))

function Invoke-Verifier {
    param(
        [string]$ContractManifest,
        [string]$ExpandedInventoryManifest = $ExpandedInventory,
        [string]$ResourceSeedManifest = $ResourceManifest,
        [string]$CoreOracleManifest = $OracleManifest,
        [string]$CoreOracleFixtures = $OracleFixtures,
        [string]$ThreadHookMatrixManifest = $ThreadHookMatrix
    )
    $output = & pwsh -NoProfile -File $Verifier `
        -DeerFlowRoot $DeerFlowRoot `
        -ManifestPath $ContractManifest `
        -ExpandedInventoryPath $ExpandedInventoryManifest `
        -ResourceManifestPath $ResourceSeedManifest `
        -OracleManifestPath $CoreOracleManifest `
        -OracleFixturesPath $CoreOracleFixtures `
        -ThreadHookMatrixPath $ThreadHookMatrixManifest 2>&1 | Out-String
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = $output
    }
}

New-Item -ItemType Directory -Path $TempRoot | Out-Null
try {
    $valid = Invoke-Verifier -ContractManifest $Manifest
    if ($valid.ExitCode -ne 0) {
        throw "Valid manifest failed verification:`n$($valid.Output)"
    }

    $shaMismatchPath = Join-Path $TempRoot 'sha-mismatch.json'
    $shaMismatch = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json -Depth 100
    $shaMismatch.source.source_sha = '0000000000000000000000000000000000000000'
    $shaMismatch | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $shaMismatchPath -Encoding utf8NoBOM
    $shaMismatchResult = Invoke-Verifier -ContractManifest $shaMismatchPath
    if ($shaMismatchResult.ExitCode -eq 0 -or $shaMismatchResult.Output -notmatch 'source SHA drifted') {
        throw "SHA mismatch gate did not fail for the expected reason:`n$($shaMismatchResult.Output)"
    }

    $missingSurfacePath = Join-Path $TempRoot 'missing-surface.json'
    $missingSurface = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json -Depth 100
    $missingSurface.surfaces = @($missingSurface.surfaces | Where-Object id -ne 'run.create.sdk-sse')
    $missingSurface | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingSurfacePath -Encoding utf8NoBOM
    $missingSurfaceResult = Invoke-Verifier -ContractManifest $missingSurfacePath
    if ($missingSurfaceResult.ExitCode -eq 0 -or $missingSurfaceResult.Output -notmatch 'Required W00 surface is missing') {
        throw "Missing critical surface gate did not fail for the expected reason:`n$($missingSurfaceResult.Output)"
    }

    $missingEvidencePath = Join-Path $TempRoot 'missing-evidence.json'
    $missingEvidence = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json -Depth 100
    $target = @($missingEvidence.surfaces | Where-Object id -eq 'auth.me.browser')[0]
    $target.evidence[0].must_contain = @('THIS_ENDPOINT_DOES_NOT_EXIST')
    $missingEvidence | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingEvidencePath -Encoding utf8NoBOM
    $missingEvidenceResult = Invoke-Verifier -ContractManifest $missingEvidencePath
    if ($missingEvidenceResult.ExitCode -eq 0 -or $missingEvidenceResult.Output -notmatch 'lost required source evidence') {
        throw "Missing endpoint evidence gate did not fail for the expected reason:`n$($missingEvidenceResult.Output)"
    }

    $runtimeClaimPath = Join-Path $TempRoot 'runtime-pass-without-evidence.json'
    $runtimeClaim = Get-Content -LiteralPath $OracleManifest -Raw | ConvertFrom-Json -Depth 100
    $runtimeClaim.contracts[0].runtime_status = 'PASS'
    $runtimeClaim | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $runtimeClaimPath -Encoding utf8NoBOM
    $runtimeClaimResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleManifest $runtimeClaimPath
    if ($runtimeClaimResult.ExitCode -eq 0 -or $runtimeClaimResult.Output -notmatch 'must not claim runtime PASS or FAIL') {
        throw "Unevidenced runtime PASS gate did not fail for the expected reason:`n$($runtimeClaimResult.Output)"
    }

    $implementationClaimPath = Join-Path $TempRoot 'implementation-claim-without-evidence.json'
    $implementationClaim = Get-Content -LiteralPath $OracleManifest -Raw | ConvertFrom-Json -Depth 100
    $implementationClaim.contracts[0].implementation_status = 'IMPLEMENTED'
    $implementationClaim | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $implementationClaimPath -Encoding utf8NoBOM
    $implementationClaimResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleManifest $implementationClaimPath
    if ($implementationClaimResult.ExitCode -eq 0 -or $implementationClaimResult.Output -notmatch 'must keep implementation NOT_ASSESSED') {
        throw "Unevidenced implementation claim gate did not fail for the expected reason:`n$($implementationClaimResult.Output)"
    }

    $invalidFixturePath = Join-Path $TempRoot 'invalid-fixture.json'
    $invalidFixture = Get-Content -LiteralPath $OracleFixtures -Raw | ConvertFrom-Json -Depth 100
    $authFixture = @($invalidFixture.fixtures | Where-Object id -eq 'auth.me.disabled-local')[0]
    $authFixture.payload.PSObject.Properties.Remove('id')
    $invalidFixture | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $invalidFixturePath -Encoding utf8NoBOM
    $invalidFixtureResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleFixtures $invalidFixturePath
    if ($invalidFixtureResult.ExitCode -eq 0 -or $invalidFixtureResult.Output -notmatch 'failed schema validation') {
        throw "Invalid fixture schema gate did not fail for the expected reason:`n$($invalidFixtureResult.Output)"
    }

    $unknownRunOptionPath = Join-Path $TempRoot 'unknown-run-option-fixture.json'
    $unknownRunOption = Get-Content -LiteralPath $OracleFixtures -Raw | ConvertFrom-Json -Depth 100
    $runFixture = @($unknownRunOption.fixtures | Where-Object id -eq 'run.create.frontend-core')[0]
    $runFixture.payload | Add-Member -NotePropertyName durability -NotePropertyValue 'sync'
    $unknownRunOption | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $unknownRunOptionPath -Encoding utf8NoBOM
    $unknownRunOptionResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleFixtures $unknownRunOptionPath
    if ($unknownRunOptionResult.ExitCode -eq 0 -or $unknownRunOptionResult.Output -notmatch 'failed schema validation') {
        throw "Unknown run option gate did not fail for the expected reason:`n$($unknownRunOptionResult.Output)"
    }

    $missingExpandedContractPath = Join-Path $TempRoot 'missing-expanded-contract.json'
    $missingExpandedContract = Get-Content -LiteralPath $OracleManifest -Raw | ConvertFrom-Json -Depth 100
    $missingExpandedContract.contracts = @($missingExpandedContract.contracts | Where-Object id -ne 'rest.projects.patch.exchange')
    $missingExpandedContract | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingExpandedContractPath -Encoding utf8NoBOM
    $missingExpandedContractResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleManifest $missingExpandedContractPath
    if ($missingExpandedContractResult.ExitCode -eq 0 -or $missingExpandedContractResult.Output -notmatch 'Required core protocol contract is missing') {
        throw "Missing expanded contract gate did not fail for the expected reason:`n$($missingExpandedContractResult.Output)"
    }

    $wrongWireEventPath = Join-Path $TempRoot 'wrong-wire-event.json'
    $wrongWireEvent = Get-Content -LiteralPath $OracleFixtures -Raw | ConvertFrom-Json -Depth 100
    $wireFixture = @($wrongWireEvent.fixtures | Where-Object id -eq 'stream.messages-tuple.wire-messages')[0]
    $wireFixture.response.wire_event = 'messages-tuple'
    $wrongWireEvent | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $wrongWireEventPath -Encoding utf8NoBOM
    $wrongWireEventResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleFixtures $wrongWireEventPath
    if ($wrongWireEventResult.ExitCode -eq 0 -or $wrongWireEventResult.Output -notmatch 'failed schema validation') {
        throw "Wrong SSE wire event gate did not fail for the expected reason:`n$($wrongWireEventResult.Output)"
    }

    $projectLeakPath = Join-Path $TempRoot 'project-response-leak.json'
    $projectLeak = Get-Content -LiteralPath $OracleFixtures -Raw | ConvertFrom-Json -Depth 100
    $projectFixture = @($projectLeak.fixtures | Where-Object id -eq 'projects.get.active')[0]
    $projectFixture.response | Add-Member -NotePropertyName user_id -NotePropertyValue 'must-not-leak'
    $projectLeak | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $projectLeakPath -Encoding utf8NoBOM
    $projectLeakResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleFixtures $projectLeakPath
    if ($projectLeakResult.ExitCode -eq 0 -or $projectLeakResult.Output -notmatch 'failed schema validation') {
        throw "Project ownership leak gate did not fail for the expected reason:`n$($projectLeakResult.Output)"
    }

    $workspaceFalsePath = Join-Path $TempRoot 'workspace-empty-claims-available.json'
    $workspaceFalse = Get-Content -LiteralPath $OracleFixtures -Raw | ConvertFrom-Json -Depth 100
    $workspaceFixture = @($workspaceFalse.fixtures | Where-Object id -eq 'workspace-changes.unavailable-empty')[0]
    $workspaceFixture.response.available = $true
    $workspaceFalse | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $workspaceFalsePath -Encoding utf8NoBOM
    $workspaceFalseResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleFixtures $workspaceFalsePath
    if ($workspaceFalseResult.ExitCode -eq 0 -or $workspaceFalseResult.Output -notmatch 'failed schema validation') {
        throw "Workspace unavailable response gate did not fail for the expected reason:`n$($workspaceFalseResult.Output)"
    }

    $missingErrorsPath = Join-Path $TempRoot 'missing-error-statuses.json'
    $missingErrors = Get-Content -LiteralPath $OracleManifest -Raw | ConvertFrom-Json -Depth 100
    $expandedContract = @($missingErrors.contracts | Where-Object id -eq 'rest.thread.messages-page.exchange')[0]
    $expandedContract.http_status.errors = @()
    $missingErrors | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingErrorsPath -Encoding utf8NoBOM
    $missingErrorsResult = Invoke-Verifier -ContractManifest $Manifest -CoreOracleManifest $missingErrorsPath
    if ($missingErrorsResult.ExitCode -eq 0 -or $missingErrorsResult.Output -notmatch 'has no fail-closed error status inventory') {
        throw "Missing error status inventory gate did not fail for the expected reason:`n$($missingErrorsResult.Output)"
    }

    $missingFamilyPath = Join-Path $TempRoot 'missing-expanded-family.json'
    $missingFamily = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $missingFamily.families = @($missingFamily.families | Where-Object id -ne 'agents')
    $missingFamily | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingFamilyPath -Encoding utf8NoBOM
    $missingFamilyResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $missingFamilyPath
    if ($missingFamilyResult.ExitCode -eq 0 -or $missingFamilyResult.Output -notmatch 'Required expanded consumer family is missing') {
        throw "Missing expanded family gate did not fail for the expected reason:`n$($missingFamilyResult.Output)"
    }

    $missingOperationPath = Join-Path $TempRoot 'missing-expanded-operation.json'
    $missingOperation = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $batchFamily = @($missingOperation.families | Where-Object id -eq 'batches')[0]
    $batchFamily.operations = @($batchFamily.operations | Where-Object id -ne 'batches.retry-item')
    $missingOperation | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingOperationPath -Encoding utf8NoBOM
    $missingOperationResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $missingOperationPath
    if ($missingOperationResult.ExitCode -eq 0 -or $missingOperationResult.Output -notmatch 'Required expanded consumer operation is missing') {
        throw "Missing expanded operation gate did not fail for the expected reason:`n$($missingOperationResult.Output)"
    }

    $driftedOperationPath = Join-Path $TempRoot 'drifted-expanded-operation.json'
    $driftedOperation = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $driftedOperation.families[0].operations[0].route_anchor = 'NO_SUCH_FROZEN_FRONTEND_ROUTE'
    $driftedOperation | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $driftedOperationPath -Encoding utf8NoBOM
    $driftedOperationResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $driftedOperationPath
    if ($driftedOperationResult.ExitCode -eq 0 -or $driftedOperationResult.Output -notmatch 'lost frontend route evidence') {
        throw "Expanded operation source drift gate did not fail for the expected reason:`n$($driftedOperationResult.Output)"
    }

    $duplicateWirePath = Join-Path $TempRoot 'duplicate-expanded-wire-key.json'
    $duplicateWire = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $duplicate = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $duplicate.families[0].operations[0].id = 'agents.list.duplicate'
    $duplicateWire.families[0].operations = @($duplicateWire.families[0].operations) + @($duplicate.families[0].operations[0])
    $duplicateWire | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $duplicateWirePath -Encoding utf8NoBOM
    $duplicateWireResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $duplicateWirePath
    if ($duplicateWireResult.ExitCode -eq 0 -or $duplicateWireResult.Output -notmatch 'Duplicate consumer method/path key') {
        throw "Duplicate wire key gate did not fail for the expected reason:`n$($duplicateWireResult.Output)"
    }

    $unaccountedModulePath = Join-Path $TempRoot 'unaccounted-api-module.json'
    $unaccountedModule = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $unaccountedModule.source_module_accounting.shared_modules = @($unaccountedModule.source_module_accounting.shared_modules | Where-Object source_path -ne 'frontend/src/core/sidecar/api.ts')
    $unaccountedModule | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $unaccountedModulePath -Encoding utf8NoBOM
    $unaccountedModuleResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $unaccountedModulePath
    if ($unaccountedModuleResult.ExitCode -eq 0 -or $unaccountedModuleResult.Output -notmatch 'Frozen frontend API source module is unaccounted') {
        throw "Unaccounted frozen API module gate did not fail for the expected reason:`n$($unaccountedModuleResult.Output)"
    }

    $driftedNonApiPath = Join-Path $TempRoot 'drifted-non-api-source.json'
    $driftedNonApi = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $driftedNonApi.non_api_accounted_modules[0].source_anchors[0] = 'NO_SUCH_LANGGRAPH_WRAPPER'
    $driftedNonApi | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $driftedNonApiPath -Encoding utf8NoBOM
    $driftedNonApiResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $driftedNonApiPath
    if ($driftedNonApiResult.ExitCode -eq 0 -or $driftedNonApiResult.Output -notmatch 'lost source anchor') {
        throw "Non-API wrapper source drift gate did not fail for the expected reason:`n$($driftedNonApiResult.Output)"
    }

    $unknownNonApiSurfacePath = Join-Path $TempRoot 'unknown-non-api-surface.json'
    $unknownNonApiSurface = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $unknownNonApiSurface.non_api_accounted_modules[0].surface_ids[0] = 'NO_SUCH_W00_SURFACE'
    $unknownNonApiSurface | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $unknownNonApiSurfacePath -Encoding utf8NoBOM
    $unknownNonApiSurfaceResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $unknownNonApiSurfacePath
    if ($unknownNonApiSurfaceResult.ExitCode -eq 0 -or $unknownNonApiSurfaceResult.Output -notmatch 'points to unknown surface') {
        throw "Unknown non-API surface gate did not fail for the expected reason:`n$($unknownNonApiSurfaceResult.Output)"
    }

    $missingNonApiPath = Join-Path $TempRoot 'missing-accounted-non-api.json'
    $missingNonApi = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $missingNonApi.non_api_accounted_modules = @($missingNonApi.non_api_accounted_modules | Where-Object source_path -ne 'frontend/src/core/api/api-client.ts')
    $missingNonApi | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingNonApiPath -Encoding utf8NoBOM
    $missingNonApiResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $missingNonApiPath
    if ($missingNonApiResult.ExitCode -eq 0 -or $missingNonApiResult.Output -notmatch 'Required accounted non-API source is missing') {
        throw "Missing accounted non-API source gate did not fail for the expected reason:`n$($missingNonApiResult.Output)"
    }

    $falseCompletionPath = Join-Path $TempRoot 'false-expanded-completion.json'
    $falseCompletion = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $falseCompletion.completion_status = 'COMPLETE'
    $falseCompletion | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $falseCompletionPath -Encoding utf8NoBOM
    $falseCompletionResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $falseCompletionPath
    if ($falseCompletionResult.ExitCode -eq 0 -or $falseCompletionResult.Output -notmatch 'Known residuals forbid a full W00 completion claim') {
        throw "False W00 completion gate did not fail for the expected reason:`n$($falseCompletionResult.Output)"
    }

    $falseExternalPath = Join-Path $TempRoot 'false-external-call.json'
    $falseExternal = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $falseExternal.excluded_external_calls[0].source_anchor = 'NO_SUCH_EXTERNAL_URL'
    $falseExternal | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $falseExternalPath -Encoding utf8NoBOM
    $falseExternalResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $falseExternalPath
    if ($falseExternalResult.ExitCode -eq 0 -or $falseExternalResult.Output -notmatch 'lost source evidence') {
        throw "False external exclusion gate did not fail for the expected reason:`n$($falseExternalResult.Output)"
    }

    $missingNextRoutePath = Join-Path $TempRoot 'missing-next-route.json'
    $missingNextRoute = Get-Content -LiteralPath $ExpandedInventory -Raw | ConvertFrom-Json -Depth 100
    $missingNextRoute.next_route_accounting.mock_only_not_parity = @($missingNextRoute.next_route_accounting.mock_only_not_parity | Where-Object { $_ -ne 'frontend/src/app/mock/api/models/route.ts' })
    $missingNextRoute | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingNextRoutePath -Encoding utf8NoBOM
    $missingNextRouteResult = Invoke-Verifier -ContractManifest $Manifest -ExpandedInventoryManifest $missingNextRoutePath
    if ($missingNextRouteResult.ExitCode -eq 0 -or $missingNextRouteResult.Output -notmatch 'Frozen Next Route Handler is unaccounted') {
        throw "Missing Next Route Handler gate did not fail for the expected reason:`n$($missingNextRouteResult.Output)"
    }

    $missingLicensePath = Join-Path $TempRoot 'missing-nested-license.json'
    $missingLicense = Get-Content -LiteralPath $ResourceManifest -Raw | ConvertFrom-Json -Depth 100
    $missingLicense.resources = @($missingLicense.resources | Where-Object id -ne 'deerflow.buzz-provider-icon-license')
    $missingLicense | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingLicensePath -Encoding utf8NoBOM
    $missingLicenseResult = Invoke-Verifier -ContractManifest $Manifest -ResourceSeedManifest $missingLicensePath
    if ($missingLicenseResult.ExitCode -eq 0 -or $missingLicenseResult.Output -notmatch 'Required nested license resource is missing') {
        throw "Missing nested license gate did not fail for the expected reason:`n$($missingLicenseResult.Output)"
    }

    $driftedLicensePath = Join-Path $TempRoot 'drifted-nested-license.json'
    $driftedLicense = Get-Content -LiteralPath $ResourceManifest -Raw | ConvertFrom-Json -Depth 100
    $licenseResource = @($driftedLicense.resources | Where-Object id -eq 'deerflow.lobe-icons-license')[0]
    $licenseResource.sha256 = '0000000000000000000000000000000000000000000000000000000000000000'
    $driftedLicense | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $driftedLicensePath -Encoding utf8NoBOM
    $driftedLicenseResult = Invoke-Verifier -ContractManifest $Manifest -ResourceSeedManifest $driftedLicensePath
    if ($driftedLicenseResult.ExitCode -eq 0 -or $driftedLicenseResult.Output -notmatch 'hash drifted') {
        throw "Nested license hash drift gate did not fail for the expected reason:`n$($driftedLicenseResult.Output)"
    }

    $missingHookEntryPath = Join-Path $TempRoot 'missing-hook-consumption-entry.json'
    $missingHookEntry = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $missingHookEntry.entries = @($missingHookEntry.entries | Where-Object id -ne 'hooks.useThreadStream.stop.cancel')
    $missingHookEntry | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $missingHookEntryPath -Encoding utf8NoBOM
    $missingHookEntryResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $missingHookEntryPath
    if ($missingHookEntryResult.ExitCode -eq 0 -or $missingHookEntryResult.Output -notmatch 'Required thread hook consumption entry is missing') {
        throw "Missing hook consumption branch gate did not fail for the expected reason:`n$($missingHookEntryResult.Output)"
    }

    $selfCertifiedRemovalPath = Join-Path $TempRoot 'self-certified-hook-removal.json'
    $selfCertifiedRemoval = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $selfCertifiedRemoval.entries = @($selfCertifiedRemoval.entries | Where-Object id -ne 'hooks.useThreadStream.stop.cancel')
    $selfCertifiedRemoval.required_entry_ids = @($selfCertifiedRemoval.required_entry_ids | Where-Object { $_ -ne 'hooks.useThreadStream.stop.cancel' })
    $selfCertifiedRemoval | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $selfCertifiedRemovalPath -Encoding utf8NoBOM
    $selfCertifiedRemovalResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $selfCertifiedRemovalPath
    if ($selfCertifiedRemovalResult.ExitCode -eq 0 -or $selfCertifiedRemovalResult.Output -notmatch 'independent verifier baseline') {
        throw "Self-certified hook removal gate did not fail for the expected reason:`n$($selfCertifiedRemovalResult.Output)"
    }

    $driftedHookAnchorPath = Join-Path $TempRoot 'drifted-hook-anchor.json'
    $driftedHookAnchor = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $driftedHookAnchor.entries[0].source_anchors[0] = 'NO_SUCH_HOOK_ANCHOR'
    $driftedHookAnchor | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $driftedHookAnchorPath -Encoding utf8NoBOM
    $driftedHookAnchorResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $driftedHookAnchorPath
    if ($driftedHookAnchorResult.ExitCode -eq 0 -or $driftedHookAnchorResult.Output -notmatch 'lost source anchor') {
        throw "Drifted hook consumption source anchor gate did not fail for the expected reason:`n$($driftedHookAnchorResult.Output)"
    }

    $runtimeHookClaimPath = Join-Path $TempRoot 'runtime-hook-claim.json'
    $runtimeHookClaim = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $runtimeHookClaim.runtime_status = 'PASS'
    $runtimeHookClaim | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $runtimeHookClaimPath -Encoding utf8NoBOM
    $runtimeHookClaimResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $runtimeHookClaimPath
    if ($runtimeHookClaimResult.ExitCode -eq 0 -or $runtimeHookClaimResult.Output -notmatch 'must not claim runtime PASS or FAIL') {
        throw "Static hook matrix runtime PASS gate did not fail for the expected reason:`n$($runtimeHookClaimResult.Output)"
    }

    $duplicateHookEntryPath = Join-Path $TempRoot 'duplicate-hook-entry.json'
    $duplicateHookEntry = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $duplicateHookEntry.entries = @($duplicateHookEntry.entries) + @($duplicateHookEntry.entries[0])
    $duplicateHookEntry | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $duplicateHookEntryPath -Encoding utf8NoBOM
    $duplicateHookEntryResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $duplicateHookEntryPath
    if ($duplicateHookEntryResult.ExitCode -eq 0 -or $duplicateHookEntryResult.Output -notmatch 'Duplicate thread hook consumption entry') {
        throw "Duplicate hook consumption entry gate did not fail for the expected reason:`n$($duplicateHookEntryResult.Output)"
    }

    $conflictHookEntryPath = Join-Path $TempRoot 'conflict-hook-entry.json'
    $conflictHookEntry = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $conflictHookEntry.entries[1].trigger = $conflictHookEntry.entries[0].trigger
    $conflictHookEntry | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $conflictHookEntryPath -Encoding utf8NoBOM
    $conflictHookEntryResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $conflictHookEntryPath
    if ($conflictHookEntryResult.ExitCode -eq 0 -or $conflictHookEntryResult.Output -notmatch 'Conflicting thread hook consumption mapping') {
        throw "Conflicting hook consumption mapping gate did not fail for the expected reason:`n$($conflictHookEntryResult.Output)"
    }

    $unknownHookSurfacePath = Join-Path $TempRoot 'unknown-hook-surface.json'
    $unknownHookSurface = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $unknownHookSurface.entries[0].surface_ids[0] = 'NO_SUCH_SURFACE'
    $unknownHookSurface | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $unknownHookSurfacePath -Encoding utf8NoBOM
    $unknownHookSurfaceResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $unknownHookSurfacePath
    if ($unknownHookSurfaceResult.ExitCode -eq 0 -or $unknownHookSurfaceResult.Output -notmatch 'points to unknown surface') {
        throw "Unknown hook consumption surface gate did not fail for the expected reason:`n$($unknownHookSurfaceResult.Output)"
    }

    $sdkInferenceHookEntryPath = Join-Path $TempRoot 'sdk-inference-hook-entry.json'
    $sdkInferenceHookEntry = Get-Content -LiteralPath $ThreadHookMatrix -Raw | ConvertFrom-Json -Depth 100
    $sdkInferenceHookEntry.entries[0].evidence_kind = 'local_sdk_inference'
    $sdkInferenceHookEntry | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $sdkInferenceHookEntryPath -Encoding utf8NoBOM
    $sdkInferenceHookEntryResult = Invoke-Verifier -ContractManifest $Manifest -ThreadHookMatrixManifest $sdkInferenceHookEntryPath
    if ($sdkInferenceHookEntryResult.ExitCode -eq 0 -or $sdkInferenceHookEntryResult.Output -notmatch 'non-frozen evidence') {
        throw "Unavailable SDK inference gate did not fail for the expected reason:`n$($sdkInferenceHookEntryResult.Output)"
    }

    Write-Host 'PASS valid manifest'
    Write-Host 'PASS frozen SHA mismatch is rejected'
    Write-Host 'PASS missing critical SSE surface is rejected'
    Write-Host 'PASS missing endpoint source evidence is rejected'
    Write-Host 'PASS static Oracle cannot claim runtime PASS without evidence'
    Write-Host 'PASS static Oracle cannot claim implementation without evidence'
    Write-Host 'PASS invalid REST fixture is rejected by its frozen schema'
    Write-Host 'PASS undeclared run options are rejected instead of silently ignored'
    Write-Host 'PASS missing expanded protocol contract is rejected'
    Write-Host 'PASS messages-tuple request cannot drift to a messages-tuple wire event'
    Write-Host 'PASS project responses cannot leak undeclared ownership fields'
    Write-Host 'PASS workspace unavailable fixture cannot claim available=true'
    Write-Host 'PASS expanded contracts must retain explicit error statuses'
    Write-Host 'PASS missing expanded consumer family is rejected'
    Write-Host 'PASS missing required expanded operation is rejected'
    Write-Host 'PASS drifted frontend operation source is rejected'
    Write-Host 'PASS duplicate consumer method/path across W00 partitions is rejected'
    Write-Host 'PASS unaccounted frozen API source module is rejected'
    Write-Host 'PASS drifted non-API wrapper source anchor is rejected'
    Write-Host 'PASS unknown non-API wrapper surface is rejected'
    Write-Host 'PASS missing accounted non-API wrapper is rejected'
    Write-Host 'PASS residual inventory cannot claim W00 completion'
    Write-Host 'PASS unverified external-only exclusion is rejected'
    Write-Host 'PASS unaccounted Next Route Handler is rejected'
    Write-Host 'PASS missing nested license resource is rejected'
    Write-Host 'PASS nested license hash drift is rejected'
    Write-Host 'PASS missing hook consumption branch is rejected'
    Write-Host 'PASS entry and required-id co-removal is rejected by independent baseline'
    Write-Host 'PASS drifted hook consumption source anchor is rejected'
    Write-Host 'PASS static hook matrix cannot claim runtime PASS'
    Write-Host 'PASS duplicate hook consumption entry is rejected'
    Write-Host 'PASS conflicting hook consumption mapping is rejected'
    Write-Host 'PASS unknown hook consumption surface is rejected'
    Write-Host 'PASS unavailable SDK inference cannot be claimed'
}
finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
