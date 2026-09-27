[CmdletBinding()]
param([string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$doctor = Join-Path $root 'scripts/verify-customer-demo-doctor.ps1'
$oracle = Join-Path $root 'contracts/customer-demo/customer-demo-v0-oracle.json'
$temp = Join-Path ([IO.Path]::GetTempPath()) ("haifa-demo-doctor-" + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temp
$passed = [System.Collections.Generic.List[string]]::new()

try {
    $validOutput = Join-Path $temp 'valid.json'
    & $doctor -RepositoryRoot $root -PlanPath $oracle -StaticOnly -OutputPath $validOutput | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) 'valid static doctor must exit successfully'
    $valid = Get-Content -Raw -LiteralPath $validOutput | ConvertFrom-Json -Depth 20
    Assert-True ($valid.overall -ceq 'NOT_RUN') 'static doctor must preserve NOT_RUN'
    Assert-True ($valid.paidModelCalled -eq $false) 'doctor must not claim a paid model call'
    Assert-True (@($valid.checks | Where-Object id -eq 'oracle-contract' | Where-Object status -eq 'PASS').Count -eq 1) 'oracle contract must pass'
    Assert-True (@($valid.checks | Where-Object id -eq 'live-services' | Where-Object status -eq 'NOT_RUN').Count -eq 1) 'live services must remain NOT_RUN in static mode'
    $passed.Add('valid-static-not-run')

    foreach ($mutation in @(
        @{ Name = 'wrong-frozen-sha'; Apply = { param($value) $value.frozenFrontend.commit = '0000000000000000000000000000000000000000' }; Pattern = 'frozen DeerFlow commit' },
        @{ Name = 'one-round'; Apply = { param($value) $value.story.rounds = 1 }; Pattern = 'exactly two consecutive rounds' },
        @{ Name = 'fallback-enabled'; Apply = { param($value) $value.frozenFrontend.fallbackAllowed = $true }; Pattern = 'forbid DeerFlow backend fallback' },
        @{ Name = 'manual-retry'; Apply = { param($value) $value.story.steerWindow.manualRetryAllowed = $true }; Pattern = 'manual retries' }
    )) {
        $copy = Get-Content -Raw -LiteralPath $oracle | ConvertFrom-Json -Depth 20
        & $mutation.Apply $copy
        $path = Join-Path $temp "$($mutation.Name).json"
        [IO.File]::WriteAllText($path, ($copy | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
        $message = $null
        try {
            & $doctor -RepositoryRoot $root -PlanPath $path -StaticOnly | Out-Null
        }
        catch { $message = $_.Exception.Message }
        Assert-True (-not [string]::IsNullOrWhiteSpace($message)) "$($mutation.Name) must be rejected"
        Assert-True ($message -match $mutation.Pattern) "$($mutation.Name) rejection must name the violated contract"
        $passed.Add($mutation.Name)
    }
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Customer demo doctor tests passed: $($passed.Count) assertions."
