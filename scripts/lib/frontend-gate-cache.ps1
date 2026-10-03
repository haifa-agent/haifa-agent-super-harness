#Requires -Version 7
<#
.SYNOPSIS
    Stores and reuses a passed frozen-frontend source gate output.

.DESCRIPTION
    The cache key comes from scripts/lib/frontend_gate_cache.py (see its docstring for what it
    covers). No key means the inputs are not cacheable and the full gate must run. Only PASS
    output is ever written, under local-tmp/cache/frontend-source-gate/ in this worktree.
#>
Set-StrictMode -Version Latest

function Get-FrontendSourceGateCacheKey {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot, [string]$SourceRepository)
    $arguments = @((Join-Path $RepositoryRoot 'scripts/lib/frontend_gate_cache.py'), '--repo', $RepositoryRoot)
    if (-not [string]::IsNullOrWhiteSpace($SourceRepository)) {
        $arguments += @('--source', $SourceRepository)
    }
    $key = (@(& python @arguments) -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($key)) {
        return $null
    }
    return $key
}

function Get-FrontendSourceGateCachePath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot, [Parameter(Mandatory = $true)][string]$Key)
    return (Join-Path $RepositoryRoot "local-tmp/cache/frontend-source-gate/$Key.txt")
}

function Read-FrontendSourceGateCache {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Key)) {
        return $null
    }
    $path = Get-FrontendSourceGateCachePath -RepositoryRoot $RepositoryRoot -Key $Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $null
    }
    return (Get-Content -Raw -LiteralPath $path)
}

function Write-FrontendSourceGateCache {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$Key,
        [Parameter(Mandatory = $true)][string]$Output
    )
    if ([string]::IsNullOrWhiteSpace($Key)) {
        return
    }
    $path = Get-FrontendSourceGateCachePath -RepositoryRoot $RepositoryRoot -Key $Key
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)
    Set-Content -LiteralPath $path -Value $Output -Encoding utf8 -NoNewline
}
