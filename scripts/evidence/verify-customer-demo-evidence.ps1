[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$BundlePath = 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json',
    [string]$OutputDirectory = 'target/customer-demo-evidence',
    [switch]$RequireGo,
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '../lib/customer-demo-evidence.ps1')

function Write-CdeText {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, (($Content -replace "`r`n", "`n").TrimEnd() + "`n"), [Text.UTF8Encoding]::new($false))
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$resolvedBundle = if ([IO.Path]::IsPathRooted($BundlePath)) { [IO.Path]::GetFullPath($BundlePath) } else { [IO.Path]::GetFullPath((Join-Path $root $BundlePath)) }
if (-not (Test-Path -LiteralPath $resolvedBundle -PathType Leaf)) {
    throw "customer-demo evidence bundle is missing: $resolvedBundle"
}
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) { [IO.Path]::GetFullPath($OutputDirectory) } else { [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory)) }
if (-not $output.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must stay inside RepositoryRoot'
}

$bundle = Get-CdeJson $resolvedBundle
try {
    $result = Test-CdeEvidenceBundle -Root $root -Bundle $bundle -BundlePath $resolvedBundle
}
catch {
    $message = $_.Exception.Message
    $prefix = 'customer-demo evidence rejected: '
    $violations = if ($message.StartsWith($prefix)) { @($message.Substring($prefix.Length).Split('; ')) } else { @($message) }
    $null = New-Item -ItemType Directory -Force -Path $output
    $failure = [ordered]@{
        schemaVersion = 'haifa.customer-demo.evidence-failure/v1'
        validationStatus = 'FAIL'
        bundlePath = $resolvedBundle
        violations = @($violations)
    }
    Write-CdeText (Join-Path $output 'customer-demo-evidence-failure.json') ($failure | ConvertTo-Json -Depth 10)
    [Console]::Error.WriteLine($message)
    exit 2
}
$summary = $result.summary

$manifest = [ordered]@{
    schemaVersion = 'haifa.customer-demo.evidence-manifest/v1'
    validationStatus = 'PASS'
    decision = $summary.decision
    details = $summary
}
$null = New-Item -ItemType Directory -Force -Path $output
$manifestPath = Join-Path $output 'customer-demo-evidence-manifest.json'
$jsonText = $manifest | ConvertTo-Json -Depth 40
Write-CdeText $manifestPath $jsonText
$manifestHash = Get-CdeSha256 $manifestPath
Write-CdeText (Join-Path $output 'customer-demo-evidence-manifest.sha256') "$manifestHash  customer-demo-evidence-manifest.json"

$stageLines = @()
foreach ($stage in $CdeExpectedStageIds) {
    $stageLines += "- ``$stage``: $($summary.stageResults[$stage])"
}
$roundLines = @($summary.rounds | ForEach-Object {
    "- round $($_.index): $($_.status); threadId=$($_.threadId); runId=$($_.runId); fileSha256=$($_.fileSha256); downloadSha256=$($_.downloadSha256); downloadByteCount=$($_.downloadByteCount); attempts=$($_.attempts); hiddenRetry=$($_.hiddenRetry)"
})
$refLines = @($summary.evidenceReferences | ForEach-Object { "- ``$($_.path)`` sha256=$($_.sha256)" })
function Format-CdeList([object[]]$Items) {
    if (@($Items).Count -eq 0) { return '- none' }
    return (@($Items) | ForEach-Object { "- $_" }) -join "`n"
}
$report = @(
    '# Customer demo v0 evidence summary',
    '',
    "- Decision: **$($summary.decision)**",
    "- Gate status: **$($summary.gateStatus)**",
    "- Validation: **PASS**",
    "- Bundle: ``$($summary.bundlePath)`` sha256=$($summary.bundleSha256)",
    "- Oracle: ``$($summary.oraclePath)`` sha256=$($summary.oracleSha256)",
    "- Mainline SHA: $($summary.mainSha) (frozen DeerFlow $($summary.frozenFrontendSha), SDK $($summary.sdkVersion))",
    "- Model: $($summary.model.id) / $($summary.model.provider) / $($summary.model.endpointHost) ; credentialSource=$($summary.model.credentialSource) ; secretPresent=$($summary.model.secretPresent) ; paidModelCalled=$($summary.model.paidModelCalled)",
    "- Budget: authorized=$($summary.budget.authorized); maxModelCallsPerRound=$($summary.budget.maxModelCallsPerRound)",
    "- Reference fallback: allowed=$($summary.referenceFallback.fallbackAllowed); used=$($summary.referenceFallback.fallbackUsed)",
    "- Human acceptance: present=$($summary.humanAcceptance.present); path=$($summary.humanAcceptance.path)",
    "- First failure history entries: $(@($summary.firstFailureHistory).Count)",
    '',
    '## Stages',
    '',
    ($stageLines -join "`n"),
    '',
    '## Rounds',
    '',
    ($roundLines -join "`n"),
    '',
    '## Evidence references (paths + SHA-256, no credential values copied)',
    '',
    ($refLines -join "`n"),
    '',
    '## Failed items',
    '',
    (Format-CdeList $summary.failed),
    '',
    '## Blocked external items',
    '',
    (Format-CdeList $summary.blockedExternal),
    '',
    '## Upstream unsupported items',
    '',
    (Format-CdeList $summary.upstreamUnsupported),
    '',
    '## Remaining NOT_RUN',
    '',
    (Format-CdeList $summary.notRun),
    '',
    'This summary links raw files only. It does not copy secrets, cookies or customer data, and it never folds FAIL/BLOCKED_EXTERNAL/UPSTREAM_UNSUPPORTED into NOT_RUN.',
    "- Manifest SHA-256: $manifestHash"
) -join "`n"
Write-CdeText (Join-Path $output 'customer-demo-evidence-report.md') $report

if ($Json) { Write-Output $jsonText }

if ($RequireGo -and $summary.decision -ne 'GO') {
    throw "customer-demo evidence requires GO but decision is $($summary.decision)"
}
