[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'work-packages.js') -Raw
$match = [regex]::Match($source, '^\s*window\.WORK_PACKAGES_DATA\s*=\s*(?<json>\{[\s\S]*\})\s*;\s*$')
if (-not $match.Success) { throw 'work-packages.js is not a single JSON-backed data source' }
$data = $match.Groups['json'].Value | ConvertFrom-Json -DateKind String
$now = [DateTimeOffset]::Now
$issues = [System.Collections.Generic.List[string]]::new()
$observed = [System.Collections.Generic.List[object]]::new()
$crossCutting = @($data.workPackages | Where-Object {
    $_.PSObject.Properties.Name -contains 'tags' -and @($_.tags) -contains '长期横切'
} | ForEach-Object { [string]$_.id })

foreach ($package in @($data.workPackages | Where-Object state -eq 'in_progress')) {
    $audit = $data.executionAudit.packages.([string]$package.id)
    if ($null -eq $audit) {
        $issues.Add("$($package.id): missing execution audit")
        continue
    }
    $worktree = [string]$audit.worktree
    $branch = $null
    $dirty = $null
    if (-not (Test-Path -LiteralPath $worktree -PathType Container)) {
        $issues.Add("$($package.id): last worktree is missing: $worktree")
    }
    else {
        $branch = (& git -C $worktree branch --show-current).Trim()
        if ($LASTEXITCODE -ne 0 -or $branch -ne [string]$audit.branch) {
            $issues.Add("$($package.id): branch mismatch, expected $($audit.branch), observed $branch")
        }
        $changes = @(& git -C $worktree status --porcelain)
        if ($LASTEXITCODE -ne 0) { $issues.Add("$($package.id): cannot read worktree status") }
        $dirty = @($changes | Where-Object { $_ }).Count -gt 0
    }
    $observed.Add([ordered]@{
        id = [string]$package.id
        kind = $(if ($crossCutting -contains [string]$package.id) { 'cross_cutting' } else { 'delivery' })
        lifecycle = [string]$package.state
        assignment = [string]$audit.assignment
        owner = [string]$audit.owner
        branch = $branch
        worktree = $worktree
        dirty = $dirty
        lastProgressAt = [string]$audit.lastProgressAt
        blocker = [string]$audit.blocker
        nextAction = [string]$audit.nextAction
    })
}

$active = [System.Collections.Generic.List[string]]::new()
foreach ($group in $data.deliveryTasks.PSObject.Properties) {
    foreach ($task in @($group.Value)) {
        if ([string]$task.status -ne 'in_progress') { continue }
        $active.Add([string]$group.Name)
        $deadline = [DateTimeOffset]::Parse([string]$task.nextCheckpoint)
        if ($now -gt $deadline) {
            $issues.Add("$($group.Name)/$($task.id): checkpoint overdue since $($task.nextCheckpoint)")
        }
    }
}
$activeIds = @($active | Sort-Object -Unique)
if ($activeIds.Count -gt 3) { $issues.Add("active non-cross-cutting packages exceed 3: $($activeIds -join ', ')") }
$recorded = @($data.executionAudit.activeNonCrossCuttingPackages | ForEach-Object { [string]$_ })
if ($activeIds.Count -ne $recorded.Count) {
    $issues.Add('active task count differs from executionAudit.activeNonCrossCuttingPackages')
}
elseif ($activeIds.Count -gt 0 -and @(Compare-Object ($activeIds | Sort-Object) ($recorded | Sort-Object)).Count -gt 0) {
    $issues.Add('active task IDs differ from executionAudit.activeNonCrossCuttingPackages')
}

$slotPorts = @(20618, 25618, 30618, 35618, 40618, 45618)
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $slotPorts -ErrorAction SilentlyContinue |
    Select-Object LocalPort, OwningProcess | Sort-Object LocalPort)
if ($activeIds.Count -eq 0 -and $listeners.Count -gt 0) {
    $issues.Add("unassigned child-slot ports are listening: $(($listeners.LocalPort) -join ', ')")
}
foreach ($port in $slotPorts) {
    if (@($listeners | Where-Object LocalPort -eq $port).Count -gt 0) { continue }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() }
    catch { $issues.Add("child-slot port $port cannot bind: $($_.Exception.Message)") }
    finally { $probe.Stop() }
}

[ordered]@{
    checkedAt = $now.ToString('o')
    packageCounts = [ordered]@{
        total = @($data.workPackages).Count
        inProgress = @($data.workPackages | Where-Object state -eq 'in_progress').Count
        pending = @($data.workPackages | Where-Object state -eq 'pending').Count
        readyForAcceptance = @($data.workPackages | Where-Object state -eq 'ready_for_acceptance').Count
        complete = @($data.workPackages | Where-Object state -eq 'complete').Count
    }
    crossCutting = $crossCutting
    activeNonCrossCutting = $activeIds
    childSlotListeners = $listeners
    inProgressPackages = @($observed)
    issues = @($issues)
    agentStatus = '脚本不可见；主控还须在智能体列表中核对运行/最近 checkpoint'
} | ConvertTo-Json -Depth 8
if ($issues.Count -gt 0) { exit 1 }
