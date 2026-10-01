#Requires -Version 7
<#
.SYNOPSIS
  One full round of V2 local test MCP verification: start both servers, run the
  official-SDK-client verifier (including the independent-failure kill step),
  stop the servers, and assert the ports/processes are released.

.EXAMPLE
  pwsh -NoProfile -File scripts/verify-v2-test-mcp-servers.ps1 -Round round1
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Round,
    [int]$ReportingPort = 20628,
    [int]$RegionPort = 20629,
    [string]$EvidenceDir,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$startScript = Join-Path $PSScriptRoot 'start-v2-test-mcp-servers.ps1'
$moduleDir = Join-Path $repoRoot 'tests/customer-demo-v2/mcp-servers'

if (-not $EvidenceDir) {
    $EvidenceDir = Join-Path $repoRoot 'local-tmp/slot-1-w10-test-mcp/evidence'
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$manifestPath = Join-Path $EvidenceDir 'manifest.json'
$verificationPath = Join-Path $EvidenceDir "verification-$Round.json"
$summaryPath = Join-Path $EvidenceDir "run-$Round.json"

function Test-LoopbackBind {
    param([int]$Port)
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch { return $false } finally { try { $listener.Stop() } catch { } }
}

$startedAt = Get-Date
$verifierExit = $null
$failure = $null
$verifierOutput = @()

try {
    if ($SkipBuild) {
        & $startScript -ReportingPort $ReportingPort -RegionPort $RegionPort -EvidenceDir $EvidenceDir -SkipBuild
    } else {
        & $startScript -ReportingPort $ReportingPort -RegionPort $RegionPort -EvidenceDir $EvidenceDir
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $regionEntry = $manifest.mcpServers | Where-Object { $_.id -eq 'v2-region' }
    $cpFile = Join-Path $moduleDir 'target/classpath.txt'
    $deps = (Get-Content -LiteralPath $cpFile -Raw).Trim()
    $classpath = "$(Join-Path $moduleDir 'target/classes');$deps"
    $java = (Get-Command java -ErrorAction Stop).Source

    $verifierArgs = @('-cp', $classpath, 'io.haifa.superharness.testmcp.V2McpVerifier',
        '--reporting-url', "http://127.0.0.1:$ReportingPort/mcp",
        '--region-url', "http://127.0.0.1:$RegionPort/mcp",
        '--region-pid', "$($regionEntry.pid)",
        '--out', $verificationPath)
    $verifierOutput = & $java @verifierArgs 2>&1 | ForEach-Object { "$_" }
    $verifierExit = $LASTEXITCODE
    if ($verifierExit -ne 0) {
        $failure = "verifier exited with code $verifierExit"
    }
} catch {
    $failure = $_.Exception.Message
} finally {
    if (Test-Path -LiteralPath $manifestPath) {
        try { & $startScript -Stop -EvidenceDir $EvidenceDir } catch { $failure = "${failure}; stop error: $($_.Exception.Message)" }
    }
    Start-Sleep -Milliseconds 700
}

$portsReleased = (Test-LoopbackBind -Port $ReportingPort) -and (Test-LoopbackBind -Port $RegionPort)
$residual = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'McpTestServer' -and $_.CommandLine -match [regex]::Escape($repoRoot) })
$residualIds = @($residual | ForEach-Object { $_.ProcessId })
if (-not $portsReleased) { $failure = "$failure; ports not released" }
if ($residualIds.Count -gt 0) { $failure = "$failure; residual MCP fixture processes: $($residualIds -join ', ')" }

$finishedAt = Get-Date
$summary = [ordered]@{
    schemaVersion   = 'haifa.customer-demo-v2.w10-mcp-verification-run/v1'
    round           = $Round
    startedAt       = $startedAt.ToUniversalTime().ToString('o')
    finishedAt      = $finishedAt.ToUniversalTime().ToString('o')
    durationMs      = [int]($finishedAt - $startedAt).TotalMilliseconds
    verifierExitCode = $verifierExit
    retryCount      = 0
    verification    = $verificationPath
    portsReleased   = $portsReleased
    residualProcesses = @($residualIds)
    failure         = $failure
    result          = if ($failure) { 'FAIL' } else { 'PASS' }
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

$verifierOutput | ForEach-Object { Write-Output $_ }
Write-Output "run summary: $summaryPath"
Write-Output "durationMs=$($summary.durationMs) verifierExitCode=$verifierExit portsReleased=$portsReleased retryCount=0"
if ($failure) { Write-Output "FAILURE: $failure"; exit 1 }
exit 0
