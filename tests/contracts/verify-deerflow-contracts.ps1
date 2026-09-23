[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DeerFlowRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$Verifier = Join-Path $RepositoryRoot 'scripts/verify-deerflow-contracts.ps1'
$Manifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
$ResourceManifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-seed.json'
$OracleManifest = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/core-protocol-oracle.json'
$OracleFixtures = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json'
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("haifa-w00-contract-tests-" + [guid]::NewGuid().ToString('N'))

function Invoke-Verifier {
    param(
        [string]$ContractManifest,
        [string]$CoreOracleManifest = $OracleManifest,
        [string]$CoreOracleFixtures = $OracleFixtures
    )
    $output = & pwsh -NoProfile -File $Verifier `
        -DeerFlowRoot $DeerFlowRoot `
        -ManifestPath $ContractManifest `
        -ResourceManifestPath $ResourceManifest `
        -OracleManifestPath $CoreOracleManifest `
        -OracleFixturesPath $CoreOracleFixtures 2>&1 | Out-String
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
}
finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
