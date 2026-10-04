#Requires -Version 7
<#
.SYNOPSIS
    Thin PowerShell wrapper for the L3 Golden Scenario runner (V2-M6-01 & V2-M6-02).
#>
[CmdletBinding()]
param(
    [ValidateSet(1, 2)]
    [int]$Round = 1,
    [int]$GatewayPort = 20618,
    [int]$McpPort = 8091,
    [int]$Slot = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script = Join-Path $root 'scripts/ui/run_l3_golden_scenario.py'
$python = (Get-Command python.exe -ErrorAction Stop).Source

& $python $script --round $Round --port $GatewayPort --mcp-port $McpPort --slot $Slot
exit $LASTEXITCODE
