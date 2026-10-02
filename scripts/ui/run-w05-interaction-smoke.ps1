[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

& (Join-Path $PSScriptRoot 'run-w18-ui-smoke.ps1') `
    -RepositoryRoot $RepositoryRoot `
    -SourceRepository $SourceRepository `
    -GatewayPort 30618 `
    -FrontendPort 35618 `
    -ProviderPort 0 `
    -DataDirectory (Join-Path $RepositoryRoot 'target/agent-slot-2-interaction-data') `
    -Interaction `
    -SkipBuild:$SkipBuild
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
