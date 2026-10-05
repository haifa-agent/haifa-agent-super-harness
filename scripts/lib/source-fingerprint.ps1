#Requires -Version 7
<#
.SYNOPSIS
    Thin wrapper around scripts/lib/source_fingerprint.py.

.DESCRIPTION
    All fingerprint logic lives in Python. This helper only builds the command line and
    parses the JSON, so the build stamp, the full-gate summary, the UI flow-health file and
    the script-regression summary all agree on what a commit's content identity is.

    -MavenRepo lets a fixture point at its own (possibly empty) local repository instead of
    the machine's real one; production callers omit it and the Python default applies.
#>
Set-StrictMode -Version Latest

function Get-SourceFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$Commit = 'HEAD',
        [string]$MavenRepo,
        [string]$Version
    )
    $script = Join-Path $PSScriptRoot 'source_fingerprint.py'
    $arguments = @('-B', $script, '--repo', $RepositoryRoot, '--commit', $Commit)
    if (-not [string]::IsNullOrWhiteSpace($MavenRepo)) { $arguments += @('--maven-repo', $MavenRepo) }
    if (-not [string]::IsNullOrWhiteSpace($Version)) { $arguments += @('--version', $Version) }
    $output = & python @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "source_fingerprint.py failed with exit code $LASTEXITCODE`: $((@($output) -join "`n"))"
    }
    return ((@($output) -join "`n") | ConvertFrom-Json)
}
