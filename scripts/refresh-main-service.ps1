#Requires -Version 7
<#
.SYNOPSIS
    Checks the fixed-port main service and refreshes it from a built JAR.

.DESCRIPTION
    Thin wrapper around scripts/lib/main_service.py.

    -Status is read-only and exits non-zero when any check fails: both ports' listening
    PIDs and command lines, the running Gateway JAR and its recorded source commit,
    /health, /api/v1/auth/me, the frontend home page and the frontend-proxied Gateway.

    -Jar <exec JAR> -ExpectSha <commit> copies the JAR next to service.json, stops only the
    exact main-service PIDs, waits for both ports to bind again, starts the copied Gateway,
    checks the frontend checkout, runs smoke checks and writes refresh-<timestamp>.json. The
    build stamp beside the JAR must record -ExpectSha. Without -AllowSlot this entry point
    only accepts Gateway 18018 / Frontend 33018 and the fixed main-service data directory;
    -AllowSlot is for the slot rehearsal only.

.EXAMPLE
    pwsh -NoProfile -File scripts/refresh-main-service.ps1 -Status
    pwsh -NoProfile -File scripts/refresh-main-service.ps1 -Jar <path> -ExpectSha <sha> -LiveSmoke
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$Status,
    [string]$Jar,
    [string]$ExpectSha,
    [string]$FrontendRoot,
    [switch]$LiveSmoke,
    [switch]$GatewayOnly,
    [int]$GatewayPort = 18018,
    [int]$FrontendPort = 33018,
    [string]$DataDirectory,
    [string]$MainServiceRoot,
    [int]$ReadyTimeoutSeconds = 180,
    [switch]$AllowSlot,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rejected = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Rejected.Count -gt 0) {
    throw "Unsupported argument(s): $($Rejected -join ' ')."
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'local-tmp/main-service-data'
}
if ([string]::IsNullOrWhiteSpace($MainServiceRoot)) {
    $MainServiceRoot = Join-Path $root 'local-tmp/main-service'
}
$module = Join-Path $root 'scripts/lib/main_service.py'

$cliArgs = @($module, '--worktree', $root, '--gateway-port', [string]$GatewayPort, '--frontend-port', [string]$FrontendPort)
if ($AllowSlot) { $cliArgs += '--allow-slot' }

if ($Status) {
    $cliArgs += 'status'
    if ($GatewayOnly) { $cliArgs += '--gateway-only' }
}
else {
    if ([string]::IsNullOrWhiteSpace($Jar) -or [string]::IsNullOrWhiteSpace($ExpectSha)) {
        throw '-Jar and -ExpectSha are required unless -Status is used.'
    }
    $cliArgs += @('refresh', '--jar', $Jar, '--expect-sha', $ExpectSha, '--data-root', $DataDirectory,
        '--main-service-root', $MainServiceRoot, '--ready-timeout-seconds', [string]$ReadyTimeoutSeconds)
    if ($GatewayOnly) { $cliArgs += '--gateway-only' }
    if (-not [string]::IsNullOrWhiteSpace($FrontendRoot)) { $cliArgs += @('--frontend-root', $FrontendRoot) }
    if ($LiveSmoke) { $cliArgs += '--live-smoke' }
}

& python -B @cliArgs
exit $LASTEXITCODE
