#Requires -Version 7
<#
.SYNOPSIS
    Runs all script regression checks and writes one summary JSON.

.DESCRIPTION
    Thin wrapper around scripts/lib/script_regressions.py (the manifest, discovery,
    execution and summary live there). Fails when a check file in tests/checks/build
    or tests/checks/ui is missing from the manifest, or when any check fails. There is
    no switch to skip a single check.

.EXAMPLE
    pwsh -NoProfile -File scripts/verify-scripts.ps1
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rejected = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Rejected.Count -gt 0) {
    throw "Unsupported argument(s): $($Rejected -join ' '). Use scripts/lib/script_regressions.py for options."
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$module = Join-Path $root 'scripts/lib/script_regressions.py'
& python -B $module --repo $root
exit $LASTEXITCODE
