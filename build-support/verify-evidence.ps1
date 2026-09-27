[CmdletBinding()]
param(
    [string]$OutputDirectory = "target/evidence"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Push-Location $root
try {
    & mvn -o -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Offline Maven reactor verification failed with exit code $LASTEXITCODE"
    }

    & (Join-Path $root 'tests/evidence/verify-evidence-report.ps1') -RepositoryRoot $root

    & (Join-Path $root 'tests/customer-demo/verify-customer-demo-evidence.ps1') -RepositoryRoot $root

    & (Join-Path $root 'scripts/generate-evidence-report.ps1') `
        -RepositoryRoot $root -OutputDirectory $OutputDirectory -RequireSurefire
}
finally {
    Pop-Location
}
