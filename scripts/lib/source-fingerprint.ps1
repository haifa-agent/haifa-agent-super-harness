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
    if (-not (Test-Path variable:script:SourceFingerprintCache)) { $script:SourceFingerprintCache = @{} }
    $full = [IO.Path]::GetFullPath($RepositoryRoot)
    # A clean working tree is fully described by the commit, so a second identical call (for
    # example the same commit written then asserted in one gate run) can reuse the first result.
    # A dirty tree is never cached: its content can change without changing the arguments.
    $clean = -not (((& git -C $full status --porcelain 2>$null) -join "`n").Trim())
    $key = $null
    if ($clean) {
        # Resolve to a SHA: caching on the literal "HEAD" would return a stale result after a
        # later commit moves HEAD but leaves the working tree clean again.
        $resolved = ((& git -C $full rev-parse --verify "$Commit^{commit}" 2>$null) -join "`n").Trim()
        if ($resolved) {
            $key = "$full|$resolved|$MavenRepo|$Version"
            if ($script:SourceFingerprintCache.ContainsKey($key)) { return $script:SourceFingerprintCache[$key] }
        }
    }
    $script = Join-Path $PSScriptRoot 'source_fingerprint.py'
    $arguments = @('-B', $script, '--repo', $RepositoryRoot, '--commit', $Commit)
    if (-not [string]::IsNullOrWhiteSpace($MavenRepo)) { $arguments += @('--maven-repo', $MavenRepo) }
    if (-not [string]::IsNullOrWhiteSpace($Version)) { $arguments += @('--version', $Version) }
    $output = & python @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "source_fingerprint.py failed with exit code $LASTEXITCODE`: $((@($output) -join "`n"))"
    }
    $result = ((@($output) -join "`n") | ConvertFrom-Json)
    if ($null -ne $key) { $script:SourceFingerprintCache[$key] = $result }
    return $result
}
