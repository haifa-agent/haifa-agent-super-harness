[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Generator = Join-Path $RepositoryRoot 'scripts/generate-evidence-report.ps1'
$TempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$TempRoot = Join-Path $TempParent ("w24-evidence-test-" + [Guid]::NewGuid().ToString('N'))
$Assertions = 0
$BaseSnapshot = Join-Path $TempRoot 'base-shpm-snapshot.json'
. (Join-Path $RepositoryRoot 'scripts/lib/shpm-governance.ps1')

function Assert-True([bool]$Condition, [string]$Message) {
    $script:Assertions++
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Copy-FixtureFile([string]$Destination, [string]$RelativePath) {
    $source = Join-Path $RepositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { return }
    $target = Join-Path $Destination $RelativePath
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)
    Copy-Item -LiteralPath $source -Destination $target
}

function New-Fixture([string]$Name, [ValidateSet('PASS', 'FAIL', 'NOT_RUN')][string]$Surefire = 'PASS') {
    $root = Join-Path $TempRoot $Name
    $null = New-Item -ItemType Directory -Force -Path $root
    foreach ($relative in @(
        'pom.xml',
        'contracts/upstream/deerflow/frontend-consumer-manifest.json',
        'contracts/upstream/deerflow/frontend-consumer-expanded.json',
        'contracts/upstream/deerflow/resource-seed.json',
        'third-party/deerflow/manifest.json',
        'third-party/deerflow/LICENSES/deer-flow-MIT.txt',
        'third-party/deerflow/patches/0001-memory-bff-use-server-gateway.patch',
        'third-party/deerflow/patches/0002-active-steer-composer.patch',
        'frontend/package.json',
        'frontend/pnpm-lock.yaml',
        'frontend/next.config.js'
    )) { Copy-FixtureFile $root $relative }
    $resourceSeed = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-seed.json') -Raw |
        ConvertFrom-Json -DateKind String
    foreach ($resource in $resourceSeed.resources) {
        if ($resource.import_status -eq 'IMPORTED') {
            Copy-FixtureFile $root ([string]$resource.intended_local_path)
        }
    }
    Copy-Item -LiteralPath $BaseSnapshot -Destination (Join-Path $root 'shpm-snapshot.json')
    if ($Surefire -ne 'NOT_RUN') {
        $report = Join-Path $root 'sample/target/surefire-reports/TEST-sample.xml'
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $report)
        $failures = if ($Surefire -eq 'FAIL') { 1 } else { 0 }
        $body = if ($Surefire -eq 'FAIL') {
            '<testcase name="fails"><failure message="expected failure"/></testcase>'
        } else {
            '<testcase name="passes"/>'
        }
        [IO.File]::WriteAllText($report,
            "<testsuite name=`"sample`" tests=`"1`" failures=`"$failures`" errors=`"0`" skipped=`"0`">$body</testsuite>`n",
            [Text.UTF8Encoding]::new($false))
    }
    return $root
}

function Read-Snap([string]$Root) {
    return Get-Content -LiteralPath (Join-Path $Root 'shpm-snapshot.json') -Raw -Encoding utf8 |
        ConvertFrom-Json -Depth 64 -DateKind String
}

function Write-Snap([string]$Root, [object]$Snapshot) {
    [IO.File]::WriteAllText((Join-Path $Root 'shpm-snapshot.json'),
        ($Snapshot | ConvertTo-Json -Depth 64), [Text.UTF8Encoding]::new($false))
}

function Get-Row([object]$Snapshot, [string]$Table, [string]$Id) {
    return @($Snapshot.tables.$Table | Where-Object id -eq $Id)[0]
}

function Set-Details([object]$Row, [scriptblock]$Change) {
    $details = if ([string]::IsNullOrWhiteSpace([string]$Row.details_json)) { [pscustomobject]@{} }
        else { [string]$Row.details_json | ConvertFrom-Json -DateKind String }
    & $Change $details
    $Row.details_json = $details | ConvertTo-Json -Depth 20 -Compress
}

function Add-Relation([object]$Snapshot, [string]$SourceType, [string]$SourceId, [string]$TargetType,
        [string]$TargetId, [string]$Kind, [int]$Required = 0) {
    $Snapshot.tables.relation = @($Snapshot.tables.relation) + [pscustomobject]@{
        id = "rel-test-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"; source_type = $SourceType; source_id = $SourceId
        target_type = $TargetType; target_id = $TargetId; kind = $Kind; required = $Required
        created_at = '2026-09-28T00:00:00+00:00'
    }
}

function Set-DevelopingTask([object]$Snapshot, [string]$TaskId, [string]$AssignmentId, [int]$Slot, [string]$Ports,
        [string]$DataRoot, [string]$Last = '2026-09-28T12:00:00+08:00', [string]$Next = '2026-09-28T12:15:00+08:00') {
    # Slot-negative fixtures must be independent of the live export's unrelated
    # running lanes. Otherwise two synthetic assignments can hit the global
    # three-slot limit before the intended shared-port/data-root assertion.
    $liveRunning = @($Snapshot.tables.assignment | Where-Object {
        $_.state -eq 'running' -and $_.id -notlike 'asg-test-*'
    })
    $Snapshot.tables.assignment = @($Snapshot.tables.assignment | Where-Object {
        $_.state -ne 'running' -or $_.id -like 'asg-test-*'
    })
    foreach ($live in $liveRunning) {
        $liveTask = Get-Row $Snapshot 'task' ([string]$live.task_id)
        if ($liveTask.state -eq 'developing') { $liveTask.state = 'blocked' }
    }
    $task = Get-Row $Snapshot 'task' $TaskId
    $task.state = 'developing'
    if ([string]::IsNullOrWhiteSpace([string]$task.prompt_path)) { $task.prompt_path = 'private-docs/prompts/test-prompt.md' }
    $gateway, $frontend = $Ports.Split('/')
    $Snapshot.tables.assignment = @($Snapshot.tables.assignment) + [pscustomobject]@{
        id = $AssignmentId; task_id = $TaskId; executor_type = 'opencode'; executor_ref = 'test'; model = 'test/model'
        state = 'running'; repository = 'D:/workspace/test'; base_sha = '52ad9a7'; branch = "test/$AssignmentId"
        worktree = "D:/workspace/test/$AssignmentId"; slot = $Slot
        ports_json = "{`"gateway`": $gateway, `"frontend`": $frontend}"; data_root = $DataRoot
        delivered_sha = $null; merged_sha = $null; started_at = $Last; ended_at = $null; created_at = $Last; updated_at = $Last
        details_json = "{`"next_checkpoint_at`": `"$Next`", `"last_checkpoint_at`": `"$Last`"}"
    }
}

function Remove-ActiveAssignmentsFromFixture([object]$Snapshot) {
    # These two conflict cases add their own assignments. Keep the assertions
    # independent of how many real child slots are occupied in the live export.
    foreach ($assignment in @($Snapshot.tables.assignment | Where-Object state -eq 'running')) {
        (Get-Row $Snapshot 'task' ([string]$assignment.task_id)).state = 'blocked'
    }
    $Snapshot.tables.assignment = @($Snapshot.tables.assignment | Where-Object state -ne 'running')
}

function Invoke-Generator([string]$Root, [string]$Output = 'target/evidence', [switch]$RequireSurefire) {
    $arguments = @('-NoProfile', '-File', $Generator, '-RepositoryRoot', $Root, '-OutputDirectory', $Output,
        '-ShpmSnapshot', (Join-Path $Root 'shpm-snapshot.json'))
    if ($RequireSurefire) { $arguments += '-RequireSurefire' }
    $result = @(& pwsh @arguments 2>&1)
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($result -join "`n") }
}

function Assert-Fails([string]$Root, [string]$Pattern, [switch]$RequireSurefire) {
    $result = Invoke-Generator $Root -RequireSurefire:$RequireSurefire
    Assert-True ($result.ExitCode -ne 0) "expected generator failure matching '$Pattern'"
    Assert-True ($result.Output -match $Pattern) "failure output did not match '$Pattern': $($result.Output)"
}

function New-DemoEvidenceFixture([string]$Name) {
    $root = New-Fixture $Name
    Copy-FixtureFile $root 'contracts/customer-demo/customer-demo-v0-oracle.json'
    Copy-FixtureFile $root 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'
    foreach ($relative in @(
        'contracts/customer-demo/evidence/m0-doctor-authorized.json',
        'contracts/customer-demo/evidence/m1-real-model-production-tool.json',
        'contracts/customer-demo/evidence/m1-real-model-approval-final.png',
        'contracts/customer-demo/evidence/m1-real-model-approval-final.txt'
    )) { Copy-FixtureFile $root $relative }
    # Classification negatives use a self-contained pre-M2 fixture, not the
    # current GO bundle whose raw M3/M4 evidence intentionally lives outside Git.
    $bundle = Read-DemoBundle $root
    $bundle.decision = 'NO_GO'
    $bundle.referenceFallbackEvidence.evidencePath = $null
    $bundle.referenceFallbackEvidence.evidenceSha256 = $null
    $bundle.humanAcceptance.present = $false
    $bundle.humanAcceptance.path = $null
    $bundle.humanAcceptance.sha256 = $null
    $bundle.humanAcceptance.acceptedBy = $null
    $bundle.humanAcceptance.acceptedAt = $null
    $bundle.firstFailureHistory = @($bundle.firstFailureHistory | Where-Object id -ne 'm4-cold-frontend-doctor-timeout')
    $bundle.stages[0].evidence = @(@{ path = 'contracts/customer-demo/evidence/m0-doctor-authorized.json'; sha256 = '9d12485b93a7b7d30873f5f9aedccd3df574d411141f2a711528ce8b4f0d2e0d' })
    $bundle.stages[1].evidence = @(@{ path = 'contracts/customer-demo/evidence/m1-real-model-production-tool.json'; sha256 = '3f655b0523f59971d15abc27897a651bdd3f4bac1f6d31a19421e21b9fc20a2a' })
    foreach ($index in 2..4) {
        $bundle.stages[$index].workStatus = 'pending'
        $bundle.stages[$index].result = 'NOT_RUN'
        $bundle.stages[$index].proof = $null
        $bundle.stages[$index].mainCommit = $null
        $bundle.stages[$index].acceptedAt = $null
        $bundle.stages[$index].evidence = @()
    }
    foreach ($round in $bundle.rounds) { $round.status = 'NOT_RUN' }
    Write-DemoBundle $root $bundle
    return $root
}

function Read-DemoBundle([string]$Root) {
    return Get-Content -LiteralPath (Join-Path $Root 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json') -Raw |
        ConvertFrom-Json -Depth 64 -DateKind String
}

function Write-DemoBundle([string]$Root, [object]$Data) {
    [IO.File]::WriteAllText((Join-Path $Root 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'),
        (($Data | ConvertTo-Json -Depth 64) + "`n"), [Text.UTF8Encoding]::new($false))
}

function Read-EvidenceManifest([string]$Root) {
    return Get-Content -LiteralPath (Join-Path $Root 'target/evidence/evidence-manifest.json') -Raw |
        ConvertFrom-Json -Depth 40 -DateKind String
}

try {
    # A stage/assignment without details_json yields an empty object; property lookup must not throw under StrictMode.
    Assert-True ($null -eq (Get-ShpmProp ([pscustomobject]@{}) 'proof')) "Get-ShpmProp must tolerate an empty details object"
    Assert-True ((Get-ShpmProp ([pscustomobject]@{ proof = 'x' }) 'proof') -eq 'x') "Get-ShpmProp must read an existing property"
    $null = New-Item -ItemType Directory -Force -Path $TempRoot
    # Fixtures mutate a copy of the live SHPM export; the live database is never written.
    $null = Export-ShpmSnapshot -Database $env:SHPM_DB -OutputPath $BaseSnapshot

    $valid = New-Fixture 'valid'
    $first = Invoke-Generator $valid 'target/evidence-one' -RequireSurefire
    Assert-True ($first.ExitCode -eq 0) "valid evidence fixture must pass: $($first.Output)"
    $manifestOnePath = Join-Path $valid 'target/evidence-one/evidence-manifest.json'
    $manifestOne = Get-Content -LiteralPath $manifestOnePath -Raw | ConvertFrom-Json -DateKind String
    Assert-True ($manifestOne.governance.status -eq 'PASS') "governance status must be PASS"
    Assert-True ($manifestOne.governance.source -eq 'SHPM snapshot') "governance must read the SHPM snapshot"
    Assert-True ($manifestOne.governance.promptPathCheck -eq 'NOT_RUN') "prompt paths without private-docs must stay NOT_RUN"
    Assert-True ($manifestOne.PSObject.Properties.Name -notcontains 'dashboard') "dashboard consumers are no longer part of the gate"
    Assert-True ($manifestOne.inputs.haifaSdkVersion -eq '0.1.2-SNAPSHOT') "Haifa SDK version must come from pom.xml"
    Assert-True ($manifestOne.mavenSurefire.status -eq 'PASS') "Surefire status must be PASS"
    Assert-True ($manifestOne.deerflow.runtimeStatus -eq 'NOT_RUN') "runtime NOT_RUN must be preserved"
    Assert-True ($manifestOne.overallStatus -eq 'NOT_RUN') "overall cannot become PASS while runtime is NOT_RUN"
    $second = Invoke-Generator $valid 'target/evidence-two' -RequireSurefire
    Assert-True ($second.ExitCode -eq 0) "second deterministic generation must pass"
    $firstHash = (Get-FileHash -LiteralPath $manifestOnePath -Algorithm SHA256).Hash
    $secondHash = (Get-FileHash -LiteralPath (Join-Path $valid 'target/evidence-two/evidence-manifest.json') -Algorithm SHA256).Hash
    Assert-True ($firstHash -eq $secondHash) "identical inputs must produce identical manifest hashes"

    $withPrompts = New-Fixture 'with-prompts'
    $snap = Read-Snap $withPrompts
    $paths = @(@($snap.tables.task | Where-Object { $_.prompt_path } | ForEach-Object { [string]$_.prompt_path }) +
        @($snap.tables.sprint | ForEach-Object { [string]$_.roadmap_path }))
    foreach ($path in $paths | Sort-Object -Unique) { Copy-FixtureFile $withPrompts $path }
    $promptResult = Invoke-Generator $withPrompts
    Assert-True ($promptResult.ExitCode -eq 0) "registered prompt and roadmap paths must resolve: $($promptResult.Output)"
    Assert-True ((Read-EvidenceManifest $withPrompts).governance.promptPathCheck -eq 'PASS') "prompt path check must run when private-docs exists"
    @($snap.tables.task | Where-Object { $_.prompt_path })[0].prompt_path = 'private-docs/prompts/does-not-exist.md'
    Write-Snap $withPrompts $snap
    Assert-Fails $withPrompts 'prompt_path does not exist'

    $missingDb = New-Fixture 'missing-db'
    $missingDbResult = @(& pwsh -NoProfile -File $Generator -RepositoryRoot $missingDb -OutputDirectory 'target/evidence' `
        -ShpmDatabase (Join-Path $missingDb 'no-such.db') 2>&1)
    Assert-True ($LASTEXITCODE -ne 0) 'a missing SHPM database must fail closed'
    Assert-True (($missingDbResult -join "`n") -match 'SHPM database is missing') 'missing database must be reported, never recreated'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $missingDb 'local-data'))) 'the gate must not create an empty local SHPM database'

    $notRun = New-Fixture 'not-run' 'NOT_RUN'
    $notRunResult = Invoke-Generator $notRun
    Assert-True ($notRunResult.ExitCode -eq 0) "missing Surefire may be aggregated as NOT_RUN"
    $notRunManifest = Get-Content -LiteralPath (Join-Path $notRun 'target/evidence/evidence-manifest.json') -Raw |
        ConvertFrom-Json -DateKind String
    Assert-True ($notRunManifest.mavenSurefire.status -eq 'NOT_RUN') "missing Surefire must not become PASS"
    Assert-Fails $notRun 'Surefire evidence is required but status is NOT_RUN' -RequireSurefire

    $failedTests = New-Fixture 'failed-tests' 'FAIL'
    Assert-Fails $failedTests 'Surefire evidence contains failures or errors'
    $failedManifest = Get-Content -LiteralPath (Join-Path $failedTests 'target/evidence/evidence-manifest.json') -Raw |
        ConvertFrom-Json -DateKind String
    Assert-True ($failedManifest.mavenSurefire.status -eq 'FAIL') "failing tests must remain FAIL in emitted evidence"

    # Each SHPM governance rule must fail closed on a single targeted mutation.
    $cases = [ordered]@{
        'bad-schema' = @({ param($s) $s.schema_version = 2 }, 'schema_version must be 1')
        'missing-table' = @({ param($s) $s.tables.PSObject.Properties.Remove('verification') }, 'missing table verification')
        'duplicate-id' = @({ param($s) $s.tables.work_package[24].id = 'W23' }, 'ids must be unique')
        'bad-lifecycle' = @({ param($s) $w = Get-Row $s 'work_package' 'W24'; $w.state = 'pending'; $w.started_at = '2026-09-22T21:00:00+08:00' }, 'pending lifecycle requires')
        'bad-ready-lifecycle' = @({ param($s) $w = Get-Row $s 'work_package' 'W24'; $w.state = 'ready_for_acceptance'; $w.completed_at = '2026-09-22T22:00:00+08:00' }, 'ready_for_acceptance lifecycle requires')
        'missing-prerequisite' = @({ param($s) Add-Relation $s 'work_package' 'W00' 'work_package' 'W99' 'requires' }, 'references missing prerequisite W99')
        'self-reference' = @({ param($s) Add-Relation $s 'work_package' 'W00' 'work_package' 'W00' 'requires' }, 'cannot reference itself')
        'cycle' = @({ param($s) Add-Relation $s 'work_package' 'W00' 'work_package' 'W01' 'requires' }, 'prerequisite cycle detected')
        'too-many-tasks' = @({ param($s) $base = Get-Row $s 'task' 'W05-task-1'; $existing = @($s.tables.task | Where-Object work_package_id -eq 'W05').Count; $extras = @(($existing + 1)..9 | ForEach-Object { $t = $base.PSObject.Copy(); $t.id = "W05-task-$_"; $t }); $s.tables.task = @($s.tables.task) + $extras }, 'must have at most 8 tasks')
        'developing-without-assignment' = @({ param($s) $t = Get-Row $s 'task' 'W04-task-2'; $t.state = 'developing'; $t.prompt_path = 'private-docs/prompts/test.md' }, 'developing without exactly one running assignment')
        'started-without-prompt' = @({ param($s) $t = Get-Row $s 'task' 'W04-task-2'; $t.state = 'blocked'; $t.started_at = '2026-09-28T10:00:00+08:00' }, 'left pending without a prompt_path')
        'old-slot-ports' = @({ param($s) Set-DevelopingTask $s 'W04-task-2' 'asg-test-1' 2 '38018/53018' 'D:/workspace/test/a' }, 'slot and child port group do not match')
        'late-checkpoint' = @({ param($s) Set-DevelopingTask $s 'W04-task-2' 'asg-test-1' 2 '30618/35618' 'D:/workspace/test/a' '2026-09-28T12:00:00+08:00' '2026-09-28T12:30:00+08:00' }, 'next checkpoint must be within 15 minutes')
        'shared-port-group' = @({ param($s) Remove-ActiveAssignmentsFromFixture $s; Set-DevelopingTask $s 'W04-task-2' 'asg-test-1' 2 '30618/35618' 'D:/workspace/test/a'; Set-DevelopingTask $s 'W04-task-3' 'asg-test-2' 2 '30618/35618' 'D:/workspace/test/b' }, 'cannot share a port group')
        'shared-data-root' = @({ param($s) Remove-ActiveAssignmentsFromFixture $s; Set-DevelopingTask $s 'W04-task-2' 'asg-test-1' 2 '30618/35618' 'D:/workspace/test/a'; Set-DevelopingTask $s 'W04-task-3' 'asg-test-2' 3 '40618/45618' 'D:/workspace/test/a' }, 'cannot share a data root')
        'ready-without-delivery' = @({ param($s) $t = Get-Row $s 'task' 'W04-task-2'; $t.state = 'ready_for_acceptance'; $t.prompt_path = 'private-docs/prompts/test.md' }, 'ready without delivery evidence: delivered_sha')
        'finished-without-merge' = @({ param($s) foreach ($a in @($s.tables.assignment | Where-Object task_id -eq 'W05-task-2')) { $a.merged_sha = $null } }, 'cannot finish before mainline acceptance: merged_sha')
        'skipped-demo-gate' = @({ param($s) $m0 = Get-Row $s 'sprint_stage' 'DEMO-V0/M0'; $m0.result = 'NOT_RUN'; $m0.work_status = 'in_progress'; (Get-Row $s 'sprint' 'DEMO-V0').decision = 'NO_GO' }, 'DEMO-V0/M1 cannot PASS before DEMO-V0/M0 PASS')
        'invalid-demo-mapping' = @({ param($s) Add-Relation $s 'sprint_stage' 'DEMO-V0/M1' 'work_package' 'W99' 'evidence_source' }, 'DEMO-V0/M1 maps to missing work package W99')
        'stage-pass-without-proof' = @({ param($s) Set-Details (Get-Row $s 'sprint_stage' 'DEMO-V0/M0') { param($d) $d.proof = $null } }, 'DEMO-V0/M0 PASS requires independent gate evidence')
        'stage-pass-unfinished-task' = @({
            param($s)
            # Keep this negative independent of live W03 acceptance: its task-1
            # can legitimately move from developing to finished between runs.
            $task = (Get-Row $s 'task' 'W03-task-1').PSObject.Copy()
            $task.id = 'W03-task-8'
            $task.number = 'task-8'
            $task.state = 'pending'
            $task.started_at = $null
            $task.completed_at = $null
            $s.tables.task = @($s.tables.task) + $task
            Add-Relation $s 'sprint_stage' 'DEMO-V0/M0' 'task' $task.id 'delivers' 1
        }, 'DEMO-V0/M0 PASS requires task W03-task-8 finished')
        'self-certified-go' = @({ param($s) (Get-Row $s 'sprint' 'DEMO-V2').decision = 'GO' }, 'DEMO-V2 GO requires all stage gates PASS')
        'go-without-human' = @({ param($s) Set-Details (Get-Row $s 'sprint' 'DEMO-V0') { param($d) $d.humanAcceptance = $null } }, 'DEMO-V0 GO requires explicit human acceptance')
        'go-stale-stage-sha' = @({ param($s) Set-Details (Get-Row $s 'sprint_stage' 'DEMO-V0/M2') { param($d) $d.mainCommit = '1234567' } }, 'DEMO-V0 GO requires DEMO-V0/M2 to be revalidated')
        'missing-evidence' = @({ param($s) Set-Details (Get-Row $s 'work_package' 'W01') { param($d) $d.evidence = @() }; $s.tables.verification = @($s.tables.verification | Where-Object subject_id -ne 'W01') }, 'W01 is complete but has no evidence')
        'unverifiable-evidence' = @({ param($s) Set-Details (Get-Row $s 'work_package' 'W01') { param($d) $d.evidence = @('looks good') }; $s.tables.verification = @($s.tables.verification | Where-Object subject_id -ne 'W01') }, 'no machine-recognizable evidence reference')
    }
    foreach ($name in $cases.Keys) {
        $fixture = New-Fixture $name
        $snap = Read-Snap $fixture
        & $cases[$name][0] $snap
        Write-Snap $fixture $snap
        Assert-Fails $fixture $cases[$name][1]
    }

    $fakeRuntimePass = New-Fixture 'fake-runtime-pass'
    $contractPath = Join-Path $fakeRuntimePass 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
    $contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json -DateKind String
    $contract.runtime_status = 'PASS'
    [IO.File]::WriteAllText($contractPath, ($contract | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    Assert-Fails $fakeRuntimePass 'claims runtime PASS without runtime_evidence'

    $badImportedHash = New-Fixture 'bad-imported-hash'
    Add-Content -LiteralPath (Join-Path $badImportedHash 'frontend/package.json') -Value 'tamper'
    Assert-Fails $badImportedHash 'hash mismatch'

    $demoStageFail = New-DemoEvidenceFixture 'demo-stage-fail'
    $demoBundle = Read-DemoBundle $demoStageFail
    $demoBundle.stages[2].result = 'FAIL'
    Write-DemoBundle $demoStageFail $demoBundle
    $stageFailResult = Invoke-Generator $demoStageFail
    Assert-True ($stageFailResult.ExitCode -ne 0) 'stage FAIL must fail the aggregate gate'
    $demoStageFailManifest = Read-EvidenceManifest $demoStageFail
    Assert-True ($demoStageFailManifest.customerDemoEvidence.status -eq 'FAIL') 'stage FAIL must propagate to customerDemo.status'
    Assert-True ($demoStageFailManifest.overallStatus -eq 'FAIL') 'stage FAIL must propagate to overallStatus'
    Assert-True (@($demoStageFailManifest.customerDemoEvidence.failed) -contains 'stage:M2=FAIL') 'failed stage must be listed as FAIL'
    Assert-True (@($demoStageFailManifest.customerDemoEvidence.notRun) -notcontains 'stage:M2=FAIL') 'failed stage must not be disguised as NOT_RUN'

    $demoRoundFail = New-DemoEvidenceFixture 'demo-round-fail'
    $demoBundle = Read-DemoBundle $demoRoundFail
    $demoBundle.rounds[0].status = 'FAIL'
    Write-DemoBundle $demoRoundFail $demoBundle
    $roundFailResult = Invoke-Generator $demoRoundFail
    Assert-True ($roundFailResult.ExitCode -ne 0) 'round FAIL must fail the aggregate gate'
    $demoRoundFailManifest = Read-EvidenceManifest $demoRoundFail
    Assert-True ($demoRoundFailManifest.customerDemoEvidence.status -eq 'FAIL') 'round FAIL must propagate to customerDemo.status'
    Assert-True ($demoRoundFailManifest.overallStatus -eq 'FAIL') 'round FAIL must propagate to overallStatus'
    Assert-True (@($demoRoundFailManifest.customerDemoEvidence.failed) -contains 'round:1=FAIL') 'failed round must be listed as FAIL'
    Assert-True (@($demoRoundFailManifest.customerDemoEvidence.notRun) -notcontains 'round:1=FAIL') 'failed round must not be disguised as NOT_RUN'

    $demoBlocked = New-DemoEvidenceFixture 'demo-blocked-external'
    $demoBundle = Read-DemoBundle $demoBlocked
    $demoBundle.stages[2].result = 'BLOCKED_EXTERNAL'
    Write-DemoBundle $demoBlocked $demoBundle
    $blockedResult = Invoke-Generator $demoBlocked
    Assert-True ($blockedResult.ExitCode -eq 0) 'BLOCKED_EXTERNAL is not FAIL and must not abort generation'
    $demoBlockedManifest = Read-EvidenceManifest $demoBlocked
    Assert-True ($demoBlockedManifest.customerDemoEvidence.status -eq 'BLOCKED_EXTERNAL') 'blocked state must be preserved on customerDemo.status'
    Assert-True ($demoBlockedManifest.overallStatus -eq 'BLOCKED_EXTERNAL') 'blocked state must propagate to overallStatus'
    Assert-True (@($demoBlockedManifest.customerDemoEvidence.blockedExternal) -contains 'stage:M2=BLOCKED_EXTERNAL') 'blocked stage must be reported separately'
    Assert-True (@($demoBlockedManifest.customerDemoEvidence.notRun) -notcontains 'stage:M2=BLOCKED_EXTERNAL') 'blocked stage must not be disguised as NOT_RUN'
    Assert-True (@($demoBlockedManifest.customerDemoEvidence.failed).Count -eq 0) 'blocked state must not be reported as FAIL'

    $demoUpstream = New-DemoEvidenceFixture 'demo-upstream-unsupported'
    $demoBundle = Read-DemoBundle $demoUpstream
    $demoBundle.stages[2].result = 'UPSTREAM_UNSUPPORTED'
    Write-DemoBundle $demoUpstream $demoBundle
    $upstreamResult = Invoke-Generator $demoUpstream
    Assert-True ($upstreamResult.ExitCode -eq 0) 'UPSTREAM_UNSUPPORTED is not FAIL and must not abort generation'
    $demoUpstreamManifest = Read-EvidenceManifest $demoUpstream
    Assert-True ($demoUpstreamManifest.customerDemoEvidence.status -eq 'UPSTREAM_UNSUPPORTED') 'upstream state must be preserved on customerDemo.status'
    Assert-True (@($demoUpstreamManifest.customerDemoEvidence.upstreamUnsupported) -contains 'stage:M2=UPSTREAM_UNSUPPORTED') 'upstream stage must be reported separately'
    Assert-True (@($demoUpstreamManifest.customerDemoEvidence.notRun) -notcontains 'stage:M2=UPSTREAM_UNSUPPORTED') 'upstream stage must not be disguised as NOT_RUN'

    Write-Host "W24 evidence verifier tests passed: $Assertions assertions."
}
finally {
    $resolved = [IO.Path]::GetFullPath($TempRoot)
    if ($resolved.StartsWith($TempParent + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
