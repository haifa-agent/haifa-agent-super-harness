[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Two consecutive rounds against slot 1. Each round uses an independent data-root
# so neither round observes the other's persisted state; the second round reuses
# the already-verified build. Evidence (Playwright trace/screenshot/results plus
# gateway/frontend/provider logs) is written by run-w18-ui-smoke.ps1 directly to
# local-tmp/slot-1-w03-input-polish-ui/evidence so `mvn clean` cannot destroy it.
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$base = Join-Path $root 'local-tmp/slot-1-w03-input-polish-ui'
$evidence = Join-Path $base 'evidence'
$gatewayPort = 20618
$frontendPort = 25618
$null = New-Item -ItemType Directory -Force -Path $evidence

function Get-OwnedRuntimeProcesses {
    param([Parameter(Mandatory = $true)][string]$Root)

    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='node.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine.IndexOf($Root, [StringComparison]::OrdinalIgnoreCase) -ge 0
        })
}

# Fails loudly if the runner's own processes did not release the slot ports or if
# any java/node process still references this worktree. Never terminates anything.
function Assert-RoundReleased {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][int[]]$Ports,
        [Parameter(Mandatory = $true)][string]$RoundName
    )

    $deadline = (Get-Date).AddSeconds(15)
    $busy = @()
    $owned = @()
    do {
        $busy = @(foreach ($port in $Ports) {
            @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        })
        $owned = @(Get-OwnedRuntimeProcesses -Root $Root)
        if ($busy.Count -eq 0 -and $owned.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } until ((Get-Date) -ge $deadline)

    $busyText = ($busy | ForEach-Object { "port=$($_.LocalPort)/pid=$($_.OwningProcess)" }) -join ', '
    $ownedText = ($owned | ForEach-Object { "$($_.Name)#$($_.ProcessId)" }) -join ', '
    throw "W03 $RoundName cleanup assertion failed: listeners=[$busyText] ownedRuntimeProcesses=[$ownedText]"
}

$rounds = @(
    [ordered]@{ name = 'round-1'; data = (Join-Path $base 'round-1-data'); skip = [bool]$SkipBuild },
    [ordered]@{ name = 'round-2'; data = (Join-Path $base 'round-2-data'); skip = $true }
)
$summary = @()
foreach ($round in $rounds) {
    $started = Get-Date
    & (Join-Path $root 'scripts/run-w18-ui-smoke.ps1') `
        -RepositoryRoot $root `
        -SourceRepository $SourceRepository `
        -AgentSlot 1 `
        -GatewayPort $gatewayPort `
        -FrontendPort $frontendPort `
        -ProviderPort 0 `
        -DataDirectory $round.data `
        -InputPolish `
        -SkipBuild:$round.skip
    $exitCode = $LASTEXITCODE
    $finished = Get-Date

    # Automatic, failing cleanup gate for this round's own slot processes.
    Assert-RoundReleased -Root $root -Ports @($gatewayPort, $frontendPort) -RoundName $round.name
    $ownedAfter = @(Get-OwnedRuntimeProcesses -Root $root)

    # Remove only this round's exact evidence directory before re-preserving it, so a
    # rerun cannot leave stale/nested round results behind.
    $roundEvidence = Join-Path $evidence $round.name
    if (Test-Path -LiteralPath $roundEvidence) {
        $resolvedRound = (Resolve-Path -LiteralPath $roundEvidence).Path
        $resolvedEvidence = (Resolve-Path -LiteralPath $evidence).Path
        if (-not $resolvedRound.StartsWith(
                $resolvedEvidence + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to delete an unexpected path outside the task evidence: $resolvedRound"
        }
        Remove-Item -LiteralPath $resolvedRound -Recurse -Force
    }
    $null = New-Item -ItemType Directory -Force -Path $roundEvidence
    if (Test-Path -LiteralPath (Join-Path $evidence 'results.json') -PathType Leaf) {
        Copy-Item -LiteralPath (Join-Path $evidence 'results.json') -Destination (Join-Path $roundEvidence 'results.json') -Force
    }
    if (Test-Path -LiteralPath (Join-Path $evidence 'artifacts') -PathType Container) {
        Copy-Item -LiteralPath (Join-Path $evidence 'artifacts') -Destination (Join-Path $roundEvidence 'artifacts') -Recurse -Force
    }
    $summary += [ordered]@{
        round = $round.name
        dataDirectory = $round.data
        skipBuild = $round.skip
        exitCode = $exitCode
        startedAt = $started.ToString('o')
        finishedAt = $finished.ToString('o')
        durationSeconds = [Math]::Round(($finished - $started).TotalSeconds, 3)
        portsReleased = $true
        ownedRuntimeProcesses = $ownedAfter.Count
    }
    if ($exitCode -ne 0) {
        $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'rounds.json') -Encoding utf8
        throw "W03 input-polish UI $($round.name) failed with exit code $exitCode."
    }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'rounds.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 5
