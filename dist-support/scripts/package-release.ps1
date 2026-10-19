# Thin wrapper for dist-support/scripts/package-release.py.
# All logic lives in Python; this only locates an interpreter and forwards args/exit code.
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$RemainingArgs
)

$ErrorActionPreference = 'Stop'

$core = Join-Path $PSScriptRoot 'package-release.py'
if (-not (Test-Path -LiteralPath $core -PathType Leaf)) {
    throw "core script not found: $core"
}

$pythonExe = $null
$pythonPrefix = @()
foreach ($candidate in @('python', 'py')) {
    $found = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($null -ne $found) {
        $pythonExe = $found.Source
        if ($candidate -eq 'py') { $pythonPrefix = @('-3') }
        break
    }
}
if ($null -eq $pythonExe) {
    throw 'python (>= 3.8) is required to run package-release.py'
}

$env:PYTHONIOENCODING = 'utf-8'
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    # Non-fatal: output stays readable, the tool still runs.
}

& $pythonExe @pythonPrefix $core @RemainingArgs
exit $LASTEXITCODE
