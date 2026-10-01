# Shared SHPM snapshot access and governance rules.
# SHPM (Super Harness Project Manager) is the single source of task, assignment,
# checkpoint, verification and customer-demo gate state. Gates read an exported
# snapshot so that the same rules can run against a live export or a test fixture.

$script:ShpmWorkPackageStates = @('pending', 'in_progress', 'ready_for_acceptance', 'blocked', 'complete')
$script:ShpmWorkPackageKinds = @('delivery', 'cross_cutting')
$script:ShpmTaskStates = @('pending', 'developing', 'ready_for_acceptance', 'blocked', 'finished')
$script:ShpmStageStatuses = @('pending', 'in_progress', 'blocked', 'finished')
$script:ShpmStageResults = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'UPSTREAM_UNSUPPORTED')
# Human scope decision 2026-09-28 (mirrors SHPM MAX_DELIVERY_TASKS).
$script:ShpmMaxDeliveryTasks = 8
# Per-package human scope decisions above the default cap.
# 2026-10-01: W05 may hold 9 tasks (UX-V1 clarification ordering fix W05-task-9).
$script:ShpmDeliveryTaskCapOverrides = @{ 'W05' = 9 }
$script:ShpmSlotPorts = @{ 1 = '20618/25618'; 2 = '30618/35618'; 3 = '40618/45618' }
$script:ShpmRequiredTables = @('work_package', 'task', 'sprint', 'sprint_stage', 'relation', 'assignment', 'verification', 'issue', 'todo', 'event')

function Assert-ShpmCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-ShpmProp {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    # StrictMode throws on `.Name` of an empty property collection (e.g. a stage without details_json).
    if (@($Object.PSObject.Properties | ForEach-Object { $_.Name }) -contains $Name) { return $Object.$Name }
    return $null
}

function Get-ShpmDetails {
    param([object]$Row)
    $raw = [string](Get-ShpmProp $Row 'details_json')
    if ([string]::IsNullOrWhiteSpace($raw)) { return [pscustomobject]@{} }
    try { return $raw | ConvertFrom-Json -DateKind String }
    catch { throw "$(Get-ShpmProp $Row 'id') has invalid details_json" }
}

function Resolve-ShpmDatabase {
    param([string]$Database)
    $path = if (-not [string]::IsNullOrWhiteSpace($Database)) { $Database }
        elseif (-not [string]::IsNullOrWhiteSpace($env:SHPM_DB)) { $env:SHPM_DB }
        else { Join-Path $env:USERPROFILE '.shpm/data/project.db' }
    # Never let shpm fall back to creating an empty local-data/project.db.
    Assert-ShpmCondition (Test-Path -LiteralPath $path -PathType Leaf) "SHPM database is missing: $path"
    return (Resolve-Path -LiteralPath $path).Path
}

function Export-ShpmSnapshot {
    param([string]$Database, [string]$OutputPath)
    $db = Resolve-ShpmDatabase $Database
    Assert-ShpmCondition ($null -ne (Get-Command shpm -ErrorAction SilentlyContinue)) 'shpm command is not available on PATH'
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath)
    $previousEncoding = $env:PYTHONIOENCODING
    $env:PYTHONIOENCODING = 'utf-8'
    try {
        $output = @(& shpm export snapshot --db $db -o $OutputPath --json 2>&1)
        Assert-ShpmCondition ($LASTEXITCODE -eq 0) "shpm export snapshot failed (exit $LASTEXITCODE): $($output -join ' ')"
    }
    finally { $env:PYTHONIOENCODING = $previousEncoding }
    return $OutputPath
}

function Read-ShpmSnapshot {
    param([string]$Path)
    Assert-ShpmCondition (Test-Path -LiteralPath $Path -PathType Leaf) "SHPM snapshot is missing: $Path"
    try { $snapshot = Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -Depth 64 -DateKind String }
    catch { throw "SHPM snapshot contains invalid JSON: $($_.Exception.Message)" }
    Assert-ShpmCondition ((Get-ShpmProp $snapshot 'schema_version') -eq 1) 'SHPM snapshot schema_version must be 1'
    $tables = Get-ShpmProp $snapshot 'tables'
    Assert-ShpmCondition ($null -ne $tables) 'SHPM snapshot has no tables'
    foreach ($table in $script:ShpmRequiredTables) {
        Assert-ShpmCondition ($tables.PSObject.Properties.Name -contains $table) "SHPM snapshot is missing table $table"
    }
    return $snapshot
}

function Get-ShpmIsoTime {
    param([object]$Value, [string]$Field)
    Assert-ShpmCondition ($Value -is [string] -and
        $Value -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$') `
        "$Field must be an ISO 8601 timestamp with a timezone"
    try { return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw "$Field is not a valid timestamp: $Value" }
}

function Test-ShpmEvidenceReference {
    param([string]$Value)
    return $Value -match '(?i)(?:\bPASS\b|\bcommit\b|\bbranch\s+[0-9a-f]{7,40}\b|\bmain\s+[0-9a-f]{7,40}\b|SHA-?256|\bRun\s+[0-9a-f-]{16,}\b|\bArtifact\b|\btests?\s+PASS\b)'
}

function Test-ShpmGovernance {
    param([object]$Snapshot, [string]$RepositoryRoot)
    $t = $Snapshot.tables
    $packages = @($t.work_package)
    $tasks = @($t.task)
    $assignments = @($t.assignment)
    $relations = @($t.relation)
    $verifications = @($t.verification)

    # Work packages: the fixed W00-W24 ledger and lifecycle timestamps.
    Assert-ShpmCondition ($packages.Count -eq 25) 'SHPM must contain exactly 25 work packages'
    $expectedIds = @(0..24 | ForEach-Object { 'W{0:D2}' -f $_ })
    $actualIds = @($packages | ForEach-Object { [string]$_.id })
    Assert-ShpmCondition (@($actualIds | Sort-Object -Unique).Count -eq 25) 'work-package ids must be unique'
    Assert-ShpmCondition (@(Compare-Object ($expectedIds | Sort-Object) ($actualIds | Sort-Object)).Count -eq 0) `
        'work-package ids must be exactly W00 through W24'
    $byId = @{}
    foreach ($package in $packages) {
        $id = [string]$package.id
        $byId[$id] = $package
        Assert-ShpmCondition ($script:ShpmWorkPackageStates -contains [string]$package.state) "$id has an invalid state"
        Assert-ShpmCondition ($script:ShpmWorkPackageKinds -contains [string]$package.kind) "$id has an invalid kind"
        $started = $null
        $completed = $null
        if ($null -ne $package.started_at) { $started = Get-ShpmIsoTime $package.started_at "$id.started_at" }
        if ($null -ne $package.completed_at) { $completed = Get-ShpmIsoTime $package.completed_at "$id.completed_at" }
        switch ([string]$package.state) {
            'pending' {
                Assert-ShpmCondition ($null -eq $started -and $null -eq $completed) `
                    "$id pending lifecycle requires started_at=null and completed_at=null"
            }
            'in_progress' {
                Assert-ShpmCondition ($null -ne $started -and $null -eq $completed) `
                    "$id in_progress lifecycle requires started_at and completed_at=null"
            }
            'ready_for_acceptance' {
                Assert-ShpmCondition ($null -ne $started -and $null -eq $completed) `
                    "$id ready_for_acceptance lifecycle requires started_at and completed_at=null"
            }
            'blocked' {
                Assert-ShpmCondition ($null -eq $completed) "$id blocked lifecycle requires completed_at=null"
            }
            'complete' {
                Assert-ShpmCondition ($null -ne $started -and $null -ne $completed) "$id complete lifecycle requires both timestamps"
                Assert-ShpmCondition ($completed -ge $started) "$id completed_at precedes started_at"
            }
        }
        if ([string]$package.state -in @('ready_for_acceptance', 'complete')) {
            $details = Get-ShpmDetails $package
            $textEvidence = @(@(Get-ShpmProp $details 'evidence') | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
            $passRecords = @($verifications | Where-Object {
                $_.subject_type -eq 'work_package' -and $_.subject_id -eq $id -and $_.result -eq 'PASS' })
            Assert-ShpmCondition (($textEvidence.Count + $passRecords.Count) -gt 0) "$id is $($package.state) but has no evidence"
            Assert-ShpmCondition ($passRecords.Count -gt 0 -or
                @($textEvidence | Where-Object { Test-ShpmEvidenceReference $_ }).Count -gt 0) `
                "$id is $($package.state) but has no machine-recognizable evidence reference"
        }
    }

    # Work-package prerequisites (relation kind=requires): existence, no self edge, no cycle.
    $requires = @($relations | Where-Object { $_.kind -eq 'requires' })
    $edges = @{}
    foreach ($relation in $requires) {
        $source = [string]$relation.source_id
        $target = [string]$relation.target_id
        Assert-ShpmCondition ($relation.source_type -eq 'work_package' -and $relation.target_type -eq 'work_package') `
            "requires relation $($relation.id) must link work packages"
        Assert-ShpmCondition $byId.ContainsKey($source) "requires relation references missing work package $source"
        Assert-ShpmCondition $byId.ContainsKey($target) "$source references missing prerequisite $target"
        Assert-ShpmCondition ($source -ne $target) "$source cannot reference itself as a prerequisite"
        if (-not $edges.ContainsKey($source)) { $edges[$source] = [System.Collections.Generic.List[string]]::new() }
        Assert-ShpmCondition (-not $edges[$source].Contains($target)) "$source contains duplicate prerequisite $target"
        $edges[$source].Add($target)
    }
    $colors = @{}
    function Visit-ShpmPackage([string]$Id, [string[]]$Stack) {
        if ($colors[$Id] -eq 'gray') { throw "work-package prerequisite cycle detected: $(($Stack + $Id) -join ' -> ')" }
        if ($colors[$Id] -eq 'black') { return }
        $colors[$Id] = 'gray'
        if ($edges.ContainsKey($Id)) { foreach ($next in $edges[$Id]) { Visit-ShpmPackage $next ($Stack + $Id) } }
        $colors[$Id] = 'black'
    }
    foreach ($id in $actualIds) { Visit-ShpmPackage $id @() }

    # Tasks: prompt paths, per-package bounds and delivery/acceptance facts.
    $promptRoot = Join-Path $RepositoryRoot 'private-docs'
    $promptCheck = if (Test-Path -LiteralPath $promptRoot -PathType Container) { 'PASS' } else { 'NOT_RUN' }
    $taskById = @{}
    foreach ($task in $tasks) {
        $id = [string]$task.id
        Assert-ShpmCondition (-not $taskById.ContainsKey($id)) "task id $id must be unique"
        $taskById[$id] = $task
        Assert-ShpmCondition $byId.ContainsKey([string]$task.work_package_id) "$id references missing work package $($task.work_package_id)"
        Assert-ShpmCondition ($script:ShpmTaskStates -contains [string]$task.state) "$id has an invalid state"
        Assert-ShpmCondition (-not [string]::IsNullOrWhiteSpace([string]$task.title)) "$id is missing a title"
        $prompt = [string]$task.prompt_path
        # A task blocked before it ever started (for example superseded while pending) needs no
        # prompt; any task that has started must carry the prompt it was dispatched with.
        $neverStarted = [string]$task.state -eq 'blocked' -and $null -eq $task.started_at
        if ([string]$task.state -ne 'pending' -and -not $neverStarted) {
            Assert-ShpmCondition (-not [string]::IsNullOrWhiteSpace($prompt)) "$id left pending without a prompt_path"
        }
        if ($promptCheck -eq 'PASS' -and -not [string]::IsNullOrWhiteSpace($prompt)) {
            Assert-ShpmCondition (Test-Path -LiteralPath (Join-Path $RepositoryRoot $prompt) -PathType Leaf) `
                "$id prompt_path does not exist: $prompt"
        }
        $taskAssignments = @($assignments | Where-Object { $_.task_id -eq $id })
        switch ([string]$task.state) {
            'developing' {
                Assert-ShpmCondition (@($taskAssignments | Where-Object state -eq 'running').Count -eq 1) `
                    "$id is developing without exactly one running assignment"
            }
            'ready_for_acceptance' {
                Assert-ShpmCondition (@($taskAssignments | Where-Object {
                    -not [string]::IsNullOrWhiteSpace([string]$_.delivered_sha) }).Count -gt 0) `
                    "$id is ready without delivery evidence: delivered_sha"
            }
            'finished' {
                Assert-ShpmCondition ($null -ne $task.completed_at) "$id is finished without completed_at"
                Assert-ShpmCondition (@($taskAssignments | Where-Object {
                    $_.state -eq 'accepted' -and [string]$_.merged_sha -match '^[0-9a-f]{7,40}$' }).Count -gt 0) `
                    "$id cannot finish before mainline acceptance: merged_sha"
            }
        }
    }
    foreach ($package in $packages | Where-Object kind -eq 'delivery') {
        $count = @($tasks | Where-Object work_package_id -eq $package.id).Count
        $cap = if ($script:ShpmDeliveryTaskCapOverrides.ContainsKey([string]$package.id)) {
            [int]$script:ShpmDeliveryTaskCapOverrides[[string]$package.id]
        } else { $script:ShpmMaxDeliveryTasks }
        Assert-ShpmCondition ($count -le $cap) "$($package.id) must have at most $cap tasks; more requires human scope decision"
        if ([string]$package.state -eq 'in_progress') {
            Assert-ShpmCondition ($count -gt 0) "$($package.id) needs a bounded delivery task"
        }
    }

    # Running assignments: slot/port table, isolation and the 15-minute checkpoint rule.
    $running = @($assignments | Where-Object state -eq 'running')
    $portGroups = @()
    $dataRoots = @()
    foreach ($assignment in $running) {
        $id = [string]$assignment.id
        Assert-ShpmCondition $taskById.ContainsKey([string]$assignment.task_id) "$id references missing task $($assignment.task_id)"
        Assert-ShpmCondition ([string]$taskById[[string]$assignment.task_id].state -eq 'developing') `
            "$id is running but task $($assignment.task_id) is not developing"
        foreach ($field in @('branch', 'worktree', 'base_sha', 'data_root', 'model', 'ports_json')) {
            Assert-ShpmCondition (-not [string]::IsNullOrWhiteSpace([string](Get-ShpmProp $assignment $field))) `
                "$id is running without a complete assignment card: $field"
        }
        $ports = [string]$assignment.ports_json | ConvertFrom-Json
        $group = "$($ports.gateway)/$($ports.frontend)"
        $slot = [int](Get-ShpmProp $assignment 'slot')
        Assert-ShpmCondition ($script:ShpmSlotPorts.ContainsKey($slot) -and $script:ShpmSlotPorts[$slot] -eq $group) `
            "$id slot and child port group do not match"
        $portGroups += $group
        $dataRoots += [string]$assignment.data_root
        $details = Get-ShpmDetails $assignment
        $next = Get-ShpmProp $details 'next_checkpoint_at'
        Assert-ShpmCondition ($null -ne $next) "$id is running without next_checkpoint_at"
        $nextAt = Get-ShpmIsoTime ([string]$next) "$id.next_checkpoint_at"
        $lastRaw = Get-ShpmProp $details 'last_checkpoint_at'
        $anchor = if ($null -ne $lastRaw) { Get-ShpmIsoTime ([string]$lastRaw) "$id.last_checkpoint_at" }
            else { Get-ShpmIsoTime ([string]$assignment.updated_at) "$id.updated_at" }
        Assert-ShpmCondition ($nextAt -gt $anchor -and $nextAt -le $anchor.AddMinutes(15).AddSeconds(5)) `
            "$id next checkpoint must be within 15 minutes"
    }
    Assert-ShpmCondition ($running.Count -le 3) 'running assignments exceed the three child slots'
    Assert-ShpmCondition (@($portGroups | Sort-Object -Unique).Count -eq $portGroups.Count) 'running assignments cannot share a port group'
    Assert-ShpmCondition (@($dataRoots | Sort-Object -Unique).Count -eq $dataRoots.Count) 'running assignments cannot share a data root'

    # Customer-demo sprints and stage gates.
    $sprints = @($t.sprint)
    Assert-ShpmCondition ($sprints.Count -gt 0) 'SHPM must contain at least one customer-demo sprint'
    $stageSummaries = @()
    foreach ($sprint in $sprints) {
        $sid = [string]$sprint.id
        Assert-ShpmCondition ([string]$sprint.decision -in @('NO_GO', 'GO')) "$sid decision must be NO_GO or GO"
        Assert-ShpmCondition (-not [string]::IsNullOrWhiteSpace([string]$sprint.roadmap_path)) "$sid is missing roadmap_path"
        if ($promptCheck -eq 'PASS') {
            Assert-ShpmCondition (Test-Path -LiteralPath (Join-Path $RepositoryRoot ([string]$sprint.roadmap_path)) -PathType Leaf) `
                "$sid roadmap_path does not exist: $($sprint.roadmap_path)"
        }
        $sprintDetails = Get-ShpmDetails $sprint
        $stages = @($t.sprint_stage | Where-Object sprint_id -eq $sid | Sort-Object { [int]$_.sequence })
        Assert-ShpmCondition ($stages.Count -gt 0) "$sid has no stages"
        for ($index = 0; $index -lt $stages.Count; $index++) {
            $stage = $stages[$index]
            $stageId = [string]$stage.id
            Assert-ShpmCondition ([int]$stage.sequence -eq $index + 1) "$sid stages must be sequenced 1..N without gaps"
            Assert-ShpmCondition ($script:ShpmStageStatuses -contains [string]$stage.work_status) "$stageId has an invalid work_status"
            Assert-ShpmCondition ($script:ShpmStageResults -contains [string]$stage.result) "$stageId has an invalid gate result"
            $links = @($relations | Where-Object { $_.source_type -eq 'sprint_stage' -and $_.source_id -eq $stageId })
            foreach ($link in $links) {
                if ($link.target_type -eq 'work_package') {
                    Assert-ShpmCondition $byId.ContainsKey([string]$link.target_id) "$stageId maps to missing work package $($link.target_id)"
                }
                elseif ($link.target_type -eq 'task') {
                    Assert-ShpmCondition $taskById.ContainsKey([string]$link.target_id) "$stageId maps to missing task $($link.target_id)"
                }
            }
            if ([string]$stage.work_status -eq 'finished') {
                Assert-ShpmCondition ([string]$stage.result -eq 'PASS') "$stageId finished work_status requires PASS gate result"
            }
            if ([string]$stage.result -eq 'PASS') {
                Assert-ShpmCondition ([string]$stage.work_status -eq 'finished') "$stageId PASS requires finished work_status"
                if ($index -gt 0) {
                    Assert-ShpmCondition ([string]$stages[$index - 1].result -eq 'PASS') `
                        "$stageId cannot PASS before $($stages[$index - 1].id) PASS"
                }
                $details = Get-ShpmDetails $stage
                $linkedTaskIds = @($links | Where-Object target_type -eq 'task' | ForEach-Object { [string]$_.target_id })
                $stagePassRecords = @($verifications | Where-Object {
                    $_.result -eq 'PASS' -and (($_.subject_type -eq 'sprint_stage' -and $_.subject_id -eq $stageId) -or
                        ($_.subject_type -eq 'task' -and $linkedTaskIds -contains [string]$_.subject_id)) })
                $recordedProof = -not [string]::IsNullOrWhiteSpace([string](Get-ShpmProp $details 'proof')) -and
                    [string](Get-ShpmProp $details 'mainCommit') -match '^[0-9a-f]{7,40}$' -and
                    $null -ne (Get-ShpmProp $details 'acceptedAt')
                Assert-ShpmCondition ($recordedProof -or $stagePassRecords.Count -gt 0) `
                    "$stageId PASS requires independent gate evidence"
                foreach ($required in @($links | Where-Object { $_.target_type -eq 'task' -and $_.kind -eq 'delivers' -and $_.required })) {
                    Assert-ShpmCondition ([string]$taskById[[string]$required.target_id].state -eq 'finished') `
                        "$stageId PASS requires task $($required.target_id) finished"
                }
            }
        }
        if ([string]$sprint.decision -eq 'GO') {
            Assert-ShpmCondition (@($stages | Where-Object result -ne 'PASS').Count -eq 0) "$sid GO requires all stage gates PASS"
            $mainSha = [string](Get-ShpmProp $sprintDetails 'mainSha')
            Assert-ShpmCondition ($mainSha -match '^[0-9a-f]{7,40}$') "$sid GO requires a mainline Git SHA"
            Assert-ShpmCondition (-not [string]::IsNullOrWhiteSpace([string](Get-ShpmProp $sprintDetails 'humanAcceptance'))) `
                "$sid GO requires explicit human acceptance"
            foreach ($stage in $stages) {
                $commit = [string](Get-ShpmProp (Get-ShpmDetails $stage) 'mainCommit')
                if (-not [string]::IsNullOrWhiteSpace($commit)) {
                    Assert-ShpmCondition ($commit -eq $mainSha) "$sid GO requires $($stage.id) to be revalidated on the final mainline SHA"
                }
            }
        }
        $stageSummaries += [ordered]@{
            id = $sid
            state = [string]$sprint.state
            decision = [string]$sprint.decision
            passedStages = @($stages | Where-Object result -eq 'PASS').Count
            totalStages = $stages.Count
        }
    }

    return [ordered]@{
        status = 'PASS'
        source = 'SHPM snapshot'
        snapshotSchemaVersion = 1
        packageCount = $packages.Count
        stateCounts = [ordered]@{
            pending = @($packages | Where-Object state -eq 'pending').Count
            in_progress = @($packages | Where-Object state -eq 'in_progress').Count
            ready_for_acceptance = @($packages | Where-Object state -eq 'ready_for_acceptance').Count
            blocked = @($packages | Where-Object state -eq 'blocked').Count
            complete = @($packages | Where-Object state -eq 'complete').Count
        }
        taskCount = $tasks.Count
        runningAssignments = $running.Count
        promptPathCheck = $promptCheck
        sprints = $stageSummaries
    }
}
