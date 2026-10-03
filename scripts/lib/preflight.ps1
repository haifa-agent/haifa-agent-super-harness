#Requires -Version 7
<#
.SYNOPSIS
    Preflight helpers for the PowerShell entry points.

.DESCRIPTION
    Use-SafeNoProxy      Environment preparation: replaces a process NO_PROXY that the Gateway's
                         web client rejects (wildcard or CIDR entries) with the loopback-only value
                         documented in AGENTS.md. Only this process and its children are affected;
                         values are never printed.
    Invoke-QuickChecks   Thin wrapper over scripts/lib/quick_checks.py (SDK baseline gate,
                         git diff --check, secret scan). Returns the failures; empty means pass.
#>
Set-StrictMode -Version Latest

function Use-SafeNoProxy {
    foreach ($name in 'NO_PROXY', 'no_proxy') {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        $entries = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $unsupported = @($entries | Where-Object { ($_ -match '\*' -and $_ -ne '*') -or $_ -match '/\d+$' })
        if ($unsupported.Count -gt 0) {
            [Environment]::SetEnvironmentVariable($name, '127.0.0.1,localhost', 'Process')
            Write-Host "preflight: $name had $($unsupported.Count) wildcard/CIDR entries the Gateway rejects; using 127.0.0.1,localhost for this run."
        }
    }
}

function Invoke-QuickChecks {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    $script = Join-Path $RepositoryRoot 'scripts/lib/quick_checks.py'
    $output = & python $script --repo $RepositoryRoot
    $result = (@($output) -join "`n") | ConvertFrom-Json
    return @($result.failures)
}
