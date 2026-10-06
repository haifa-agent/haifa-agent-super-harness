[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$ShpmDatabase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/shpm-governance.ps1')

# SHPM is the single source of dispatch state; this audit compares it with the
# worktrees, checkpoint deadlines and child-slot ports observed on this machine.
$database = Resolve-ShpmDatabase $ShpmDatabase
$snapshotPath = Join-Path ([IO.Path]::GetTempPath()) ("shpm-audit-" + [Guid]::NewGuid().ToString('N') + '.json')
try {
    $null = Export-ShpmSnapshot -Database $database -OutputPath $snapshotPath
    $snapshot = Read-ShpmSnapshot $snapshotPath
}
finally {
    if (Test-Path -LiteralPath $snapshotPath) { Remove-Item -LiteralPath $snapshotPath -Force }
}
$tables = $snapshot.tables
$now = [DateTimeOffset]::Now
$issues = [System.Collections.Generic.List[string]]::new()
$observed = [System.Collections.Generic.List[object]]::new()

try { $null = Test-ShpmGovernance -Snapshot $snapshot -RepositoryRoot $RepositoryRoot }
catch { $issues.Add("SHPM governance: $($_.Exception.Message)") }

$previousEncoding = $env:PYTHONIOENCODING
$env:PYTHONIOENCODING = 'utf-8'
try {
    $reconcile = (& shpm reconcile --db $database --json 2>&1 | Out-String) | ConvertFrom-Json -DateKind String
    $driftCount = [int]$reconcile.data.drift_count
    if ($driftCount -gt 0) {
        foreach ($drift in @($reconcile.data.drifts)) { $issues.Add("SHPM reconcile drift: $($drift | ConvertTo-Json -Compress -Depth 6)") }
    }
}
catch {
    $driftCount = $null
    $issues.Add("SHPM reconcile failed: $($_.Exception.Message)")
}
finally { $env:PYTHONIOENCODING = $previousEncoding }

$running = @($tables.assignment | Where-Object state -eq 'running')
foreach ($assignment in $running) {
    $id = [string]$assignment.id
    $worktree = [string]$assignment.worktree
    $branch = $null
    $dirty = $null
    if (-not (Test-Path -LiteralPath $worktree -PathType Container)) {
        $issues.Add("${id}: worktree is missing: $worktree")
    }
    else {
        $branch = (& git -C $worktree branch --show-current).Trim()
        if ($LASTEXITCODE -ne 0 -or $branch -ne [string]$assignment.branch) {
            $issues.Add("${id}: branch mismatch, expected $($assignment.branch), observed $branch")
        }
        $changes = @(& git -C $worktree status --porcelain)
        if ($LASTEXITCODE -ne 0) { $issues.Add("${id}: cannot read worktree status") }
        $dirty = @($changes | Where-Object { $_ }).Count -gt 0
    }
    $details = Get-ShpmDetails $assignment
    $next = Get-ShpmProp $details 'next_checkpoint_at'
    if ($null -eq $next) { $issues.Add("${id}: no next checkpoint recorded") }
    elseif ($now -gt [DateTimeOffset]::Parse([string]$next)) {
        $issues.Add("${id}: checkpoint overdue since $next")
    }
    $observed.Add([ordered]@{
        id = $id
        task = [string]$assignment.task_id
        executor = "$($assignment.executor_type) $($assignment.model)"
        slot = $assignment.slot
        ports = [string]$assignment.ports_json
        branch = $branch
        worktree = $worktree
        dirty = $dirty
        nextCheckpointAt = [string]$next
        lastCheckpoint = Get-ShpmProp $details 'last_checkpoint'
    })
}

$slotPorts = @(20618, 25618, 30618, 35618, 40618, 45618, 11618, 16618)
$assignedPorts = @($running | ForEach-Object {
    $ports = [string]$_.ports_json | ConvertFrom-Json
    [int]$ports.gateway; [int]$ports.frontend
})
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $slotPorts -ErrorAction SilentlyContinue |
    Select-Object LocalPort, OwningProcess | Sort-Object LocalPort)
foreach ($listener in $listeners) {
    if ($assignedPorts -notcontains [int]$listener.LocalPort) {
        $issues.Add("unassigned child-slot port is listening: $($listener.LocalPort) (PID $($listener.OwningProcess))")
    }
}
foreach ($port in $slotPorts) {
    if (@($listeners | Where-Object LocalPort -eq $port).Count -gt 0) { continue }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() }
    catch { $issues.Add("child-slot port $port cannot bind: $($_.Exception.Message)") }
    finally { $probe.Stop() }
}

$sprints = @($tables.sprint | ForEach-Object {
    $sid = [string]$_.id
    $stages = @($tables.sprint_stage | Where-Object sprint_id -eq $sid | Sort-Object { [int]$_.sequence })
    [ordered]@{
        id = $sid
        state = [string]$_.state
        decision = [string]$_.decision
        passedStages = @($stages | Where-Object result -eq 'PASS').Count
        totalStages = $stages.Count
        stages = @($stages | ForEach-Object {
            [ordered]@{ id = [string]$_.id; workStatus = [string]$_.work_status; result = [string]$_.result; nextAction = [string]$_.next_action }
        })
    }
})

[ordered]@{
    checkedAt = $now.ToString('o')
    source = "SHPM $database (exported_at $($snapshot.exported_at))"
    packageCounts = [ordered]@{
        total = @($tables.work_package).Count
        inProgress = @($tables.work_package | Where-Object state -eq 'in_progress').Count
        pending = @($tables.work_package | Where-Object state -eq 'pending').Count
        readyForAcceptance = @($tables.work_package | Where-Object state -eq 'ready_for_acceptance').Count
        blocked = @($tables.work_package | Where-Object state -eq 'blocked').Count
        complete = @($tables.work_package | Where-Object state -eq 'complete').Count
    }
    crossCutting = @($tables.work_package | Where-Object kind -eq 'cross_cutting' | ForEach-Object { [string]$_.id })
    sprints = $sprints
    runningAssignments = @($observed)
    childSlotListeners = $listeners
    reconcileDriftCount = $driftCount
    openIssues = @($tables.issue | Where-Object status -eq 'open' | ForEach-Object { "$($_.id) [$($_.severity)] $($_.title)" })
    issues = @($issues)
    agentStatus = '脚本不可见；主控还须在智能体列表中核对运行/最近 checkpoint'
} | ConvertTo-Json -Depth 8
if ($issues.Count -gt 0) { exit 1 }
