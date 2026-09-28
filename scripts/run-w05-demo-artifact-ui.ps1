[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [string]$DataDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'local-tmp/slot-1-w05-m2'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

& (Join-Path $PSScriptRoot 'run-w18-ui-smoke.ps1') `
    -RepositoryRoot $RepositoryRoot `
    -SourceRepository $SourceRepository `
    -AgentSlot 1 `
    -GatewayPort 20618 `
    -FrontendPort 25618 `
    -ProviderPort 0 `
    -DataDirectory $DataDirectory `
    -DemoArtifact `
    -SkipBuild:$SkipBuild
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
