#Requires -Version 7
<#
Runs the fixed four-round V2-M2 MCP differential on one isolated data root:
reference cold/warm, then Haifa cold/warm. A failed round remains FAIL and is
never silently retried; each invocation writes its own external evidence dir.
The operator prepares the repo-local JAR and offline frontend dependencies first.
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [string]$SessionId = (Get-Date -Format 'yyyyMMdd-HHmmss')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$sessionRoot = Join-Path $root "local-tmp/slot-1-w18-v2-m2/data/$SessionId"
if (Test-Path -LiteralPath $sessionRoot) {
    throw "Refusing to reuse an existing evidence/data root: $sessionRoot"
}

$os = Get-CimInstance Win32_OperatingSystem
$freeGb = [math]::Round($os.FreePhysicalMemory / 1MB, 2)
$otherMaven = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(java|mvn|mvnw|pwsh|cmd)\.exe$' -and
        $_.CommandLine -match '(maven|mvnw|spring-boot:run)' -and
        $_.CommandLine -notmatch [regex]::Escape($root)
    })
if ($freeGb -le 4 -or $otherMaven.Count -gt 0) {
    throw "Resource preflight failed: free physical memory=${freeGb}GB; other Maven process count=$($otherMaven.Count). No round started."
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'))) {
    throw 'Haifa exec JAR is missing; run the full repository gate first when resources allow.'
}
foreach ($file in @('frontend/node_modules/.bin/next.cmd', 'frontend/node_modules/.bin/playwright.cmd')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $file))) {
        throw "Offline frontend dependency missing: $file"
    }
}

$null = New-Item -ItemType Directory -Force -Path $sessionRoot
$rounds = @(
    [ordered]@{ backend = 'reference'; round = 1; temperature = 'cold' },
    [ordered]@{ backend = 'reference'; round = 2; temperature = 'warm' },
    [ordered]@{ backend = 'haifa'; round = 1; temperature = 'cold' },
    [ordered]@{ backend = 'haifa'; round = 2; temperature = 'warm' }
)
$results = @()
foreach ($item in $rounds) {
    $label = "$($item.backend)-r$($item.round)"
    $transcript = Join-Path $sessionRoot "$label-console.log"
    $started = Get-Date
    $failure = $null
    try {
        & (Join-Path $root 'scripts/ui/run-w18-v2-m2-mcp.ps1') `
            -Backend $item.backend -Round $item.round `
            -RepositoryRoot $root -SourceRepository $SourceRepository `
            -DataDirectory $sessionRoot -SkipBuild:($item.round -eq 2 -or $item.backend -eq 'haifa') `
            *>&1 | Tee-Object -FilePath $transcript
    }
    catch {
        $failure = $_.Exception.Message
        "FAIL $label $failure" | Add-Content -LiteralPath $transcript -Encoding utf8
    }
    $results += [ordered]@{
        backend = $item.backend
        round = $item.round
        temperature = $item.temperature
        result = $(if ($failure) { 'FAIL' } else { 'PASS' })
        failure = $failure
        startedAt = $started.ToUniversalTime().ToString('o')
        elapsedSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        retryCount = 0
        consoleLog = $transcript
    }
}
$summary = [ordered]@{
    schemaVersion = 'w18-v2-m2-four-rounds/v1'
    sessionRoot = $sessionRoot
    harnessSha = (& git -C $root rev-parse HEAD).Trim()
    frozenDeerFlowSha = (& git -C $SourceRepository rev-parse HEAD).Trim()
    freePhysicalGbAtStart = $freeGb
    rounds = $results
    stageResult = 'NOT_RUN'
    stageReason = 'W15 Skill slice and controller acceptance are outside this MCP runner'
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $sessionRoot 'four-round-summary.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 6
if (@($results | Where-Object { $_.result -ne 'PASS' }).Count -gt 0) {
    throw 'One or more V2-M2 MCP rounds failed; raw first failures remain in the session evidence root.'
}
