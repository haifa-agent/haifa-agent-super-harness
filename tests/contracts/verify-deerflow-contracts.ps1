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
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("haifa-w00-contract-tests-" + [guid]::NewGuid().ToString('N'))

function Invoke-Verifier {
    param([string]$ContractManifest)
    $output = & pwsh -NoProfile -File $Verifier `
        -DeerFlowRoot $DeerFlowRoot `
        -ManifestPath $ContractManifest `
        -ResourceManifestPath $ResourceManifest 2>&1 | Out-String
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

    Write-Host 'PASS valid manifest'
    Write-Host 'PASS frozen SHA mismatch is rejected'
    Write-Host 'PASS missing critical SSE surface is rejected'
    Write-Host 'PASS missing endpoint source evidence is rejected'
}
finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}

