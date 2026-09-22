[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    & mvn -o -ntp verify
    if ($LASTEXITCODE -ne 0) {
        throw "Offline Maven verification failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
