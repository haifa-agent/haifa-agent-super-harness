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
        'work-packages.js',
        'dashboard.html',
        'work-package-dag.html',
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

function Read-WorkData([string]$Root) {
    $source = Get-Content -LiteralPath (Join-Path $Root 'work-packages.js') -Raw
    $match = [regex]::Match($source, '^\s*window\.WORK_PACKAGES_DATA\s*=\s*(?<json>\{[\s\S]*\})\s*;\s*$')
    return $match.Groups['json'].Value | ConvertFrom-Json -DateKind String
}

function Write-WorkData([string]$Root, [object]$Data) {
    $json = $Data | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText((Join-Path $Root 'work-packages.js'),
        "window.WORK_PACKAGES_DATA = $json;`n", [Text.UTF8Encoding]::new($false))
}

function Invoke-Generator([string]$Root, [string]$Output = 'target/evidence', [switch]$RequireSurefire) {
    $arguments = @('-NoProfile', '-File', $Generator, '-RepositoryRoot', $Root, '-OutputDirectory', $Output)
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
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    $valid = New-Fixture 'valid'
    $first = Invoke-Generator $valid 'target/evidence-one' -RequireSurefire
    Assert-True ($first.ExitCode -eq 0) "valid evidence fixture must pass: $($first.Output)"
    $manifestOnePath = Join-Path $valid 'target/evidence-one/evidence-manifest.json'
    $manifestOne = Get-Content -LiteralPath $manifestOnePath -Raw | ConvertFrom-Json -DateKind String
    Assert-True ($manifestOne.governance.status -eq 'PASS') "governance status must be PASS"
    Assert-True ($manifestOne.mavenSurefire.status -eq 'PASS') "Surefire status must be PASS"
    Assert-True ($manifestOne.deerflow.runtimeStatus -eq 'NOT_RUN') "runtime NOT_RUN must be preserved"
    Assert-True ($manifestOne.overallStatus -eq 'NOT_RUN') "overall cannot become PASS while runtime is NOT_RUN"
    $expectedPageCount = if (Test-Path -LiteralPath (Join-Path $RepositoryRoot 'work-package-dag.html')) { 2 } else { 1 }
    Assert-True (@($manifestOne.dashboard.pages).Count -eq $expectedPageCount) "all dashboard consumers must be verified"
    $second = Invoke-Generator $valid 'target/evidence-two' -RequireSurefire
    Assert-True ($second.ExitCode -eq 0) "second deterministic generation must pass"
    $firstHash = (Get-FileHash -LiteralPath $manifestOnePath -Algorithm SHA256).Hash
    $secondHash = (Get-FileHash -LiteralPath (Join-Path $valid 'target/evidence-two/evidence-manifest.json') -Algorithm SHA256).Hash
    Assert-True ($firstHash -eq $secondHash) "identical inputs must produce identical manifest hashes"

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

    $badSchema = New-Fixture 'bad-schema'
    $data = Read-WorkData $badSchema
    $data.schemaVersion = 4
    Write-WorkData $badSchema $data
    Assert-Fails $badSchema 'schemaVersion must be 5'

    $duplicate = New-Fixture 'duplicate-id'
    $data = Read-WorkData $duplicate
    $data.workPackages[24].id = 'W23'
    Write-WorkData $duplicate $data
    Assert-Fails $duplicate 'ids must be unique'

    $lifecycle = New-Fixture 'bad-lifecycle'
    $data = Read-WorkData $lifecycle
    $data.workPackages[24].state = 'pending'
    $data.workPackages[24].startedAt = '2026-09-22T21:00:00+08:00'
    Write-WorkData $lifecycle $data
    Assert-Fails $lifecycle 'pending lifecycle requires'

    $readyLifecycle = New-Fixture 'bad-ready-lifecycle'
    $data = Read-WorkData $readyLifecycle
    $data.workPackages[24].state = 'ready_for_acceptance'
    $data.workPackages[24].completedAt = '2026-09-22T22:00:00+08:00'
    Write-WorkData $readyLifecycle $data
    Assert-Fails $readyLifecycle 'ready_for_acceptance lifecycle requires'

    $missingPrerequisite = New-Fixture 'missing-prerequisite'
    $data = Read-WorkData $missingPrerequisite
    $data.workPackages[0].prerequisites = @('W99')
    Write-WorkData $missingPrerequisite $data
    Assert-Fails $missingPrerequisite 'references missing prerequisite W99'

    $selfReference = New-Fixture 'self-reference'
    $data = Read-WorkData $selfReference
    $data.workPackages[0].prerequisites = @('W00')
    Write-WorkData $selfReference $data
    Assert-Fails $selfReference 'cannot reference itself'

    $cycle = New-Fixture 'cycle'
    $data = Read-WorkData $cycle
    $data.workPackages[0].prerequisites = @('W01')
    Write-WorkData $cycle $data
    Assert-Fails $cycle 'prerequisite cycle detected'

    $crossCuttingTask = New-Fixture 'cross-cutting-delivery-task'
    $data = Read-WorkData $crossCuttingTask
    $data.workPackages[3] | Add-Member -NotePropertyName tags -NotePropertyValue @('长期横切')
    Write-WorkData $crossCuttingTask $data
    Assert-Fails $crossCuttingTask 'W03 is cross-cutting and must not use a bounded delivery task group'

    $tooManyTasks = New-Fixture 'too-many-delivery-tasks'
    $data = Read-WorkData $tooManyTasks
    $data.deliveryTasks.W04 = @($data.deliveryTasks.W04) + @($data.deliveryTasks.W04[0])
    Write-WorkData $tooManyTasks $data
    Assert-Fails $tooManyTasks 'must have 1 to 4 delivery tasks'

    $missingTaskCard = New-Fixture 'missing-active-task-card'
    $data = Read-WorkData $missingTaskCard
    $data.deliveryTasks.W04[1].status = 'in_progress'
    Write-WorkData $missingTaskCard $data
    Assert-Fails $missingTaskCard 'in progress without a complete task card: prompt'

    $oldSlotPorts = New-Fixture 'old-slot-ports'
    $data = Read-WorkData $oldSlotPorts
    $task = $data.deliveryTasks.W04[1]
    $task.status = 'in_progress'
    $task.prompt = 'private-docs/prompts/39-deerflow-parity-delivery/W04-Developing/W04-FaultIsolation-Pending.md'
    $task | Add-Member -NotePropertyMembers @{
        owner = '/root/test'; branch = 'feat-test'; worktree = 'D:/workspace/test'
        slot = '2'; ports = '38018/53018'; dataRoot = 'D:/workspace/test/local-tmp'
        exit = 'controlled failure test'; lastCheckpointAt = '2026-09-27T12:00:00+08:00'
        nextCheckpoint = '2026-09-27T12:15:00+08:00'
    }
    Write-WorkData $oldSlotPorts $data
    Assert-Fails $oldSlotPorts 'slot and child port group do not match'

    $unreviewedTask = New-Fixture 'unreviewed-delivery-task'
    $data = Read-WorkData $unreviewedTask
    $data.deliveryTasks.W04[0].status = 'ready_for_acceptance'
    Write-WorkData $unreviewedTask $data
    Assert-Fails $unreviewedTask 'is ready without delivery evidence: commit'

    $missingDemoStage = New-Fixture 'missing-demo-stage'
    $data = Read-WorkData $missingDemoStage
    $data.customerDemo.stages = @($data.customerDemo.stages | Where-Object id -ne 'M3')
    Write-WorkData $missingDemoStage $data
    Assert-Fails $missingDemoStage 'customer demo must contain exactly M0 through M4'

    $invalidDemoMapping = New-Fixture 'invalid-demo-mapping'
    $data = Read-WorkData $invalidDemoMapping
    $data.customerDemo.stages[1].workPackages = @('W99')
    Write-WorkData $invalidDemoMapping $data
    Assert-Fails $invalidDemoMapping 'M1 maps to missing work package W99'

    $skippedDemoGate = New-Fixture 'skipped-demo-gate'
    $data = Read-WorkData $skippedDemoGate
    $data.customerDemo.stages[0].workStatus = 'in_progress'
    $data.customerDemo.stages[0].result = 'NOT_RUN'
    $data.customerDemo.stages[0].proof = $null
    $data.customerDemo.stages[0].mainCommit = $null
    $data.customerDemo.stages[0].acceptedAt = $null
    $data.customerDemo.stages[1].workStatus = 'complete'
    $data.customerDemo.stages[1].result = 'PASS'
    $data.customerDemo.stages[1].proof = 'independent test evidence'
    $data.customerDemo.stages[1].mainCommit = '1234567'
    $data.customerDemo.stages[1].acceptedAt = '2026-09-27T14:00:00+08:00'
    Write-WorkData $skippedDemoGate $data
    Assert-Fails $skippedDemoGate 'M1 cannot PASS before M0 PASS'

    $selfCertifiedDemo = New-Fixture 'self-certified-demo-go'
    $data = Read-WorkData $selfCertifiedDemo
    $data.customerDemo.stages[4].workStatus = 'pending'
    $data.customerDemo.stages[4].result = 'NOT_RUN'
    $data.customerDemo.stages[4].proof = $null
    $data.customerDemo.stages[4].mainCommit = $null
    $data.customerDemo.stages[4].acceptedAt = $null
    $data.customerDemo.humanAcceptance = $null
    $data.customerDemo.decision = 'GO'
    Write-WorkData $selfCertifiedDemo $data
    Assert-Fails $selfCertifiedDemo 'customer demo GO requires all M0-M4 gates PASS'

    $missingAudit = New-Fixture 'missing-execution-audit'
    $data = Read-WorkData $missingAudit
    $data.executionAudit.packages.W24.owner = ''
    Write-WorkData $missingAudit $data
    Assert-Fails $missingAudit 'W24 execution audit is missing owner'

    $missingEvidence = New-Fixture 'missing-evidence'
    $data = Read-WorkData $missingEvidence
    $complete = @($data.workPackages | Where-Object state -eq 'complete')[0]
    $complete.evidence = @()
    Write-WorkData $missingEvidence $data
    Assert-Fails $missingEvidence 'complete but has no evidence'

    $unverifiableEvidence = New-Fixture 'unverifiable-evidence'
    $data = Read-WorkData $unverifiableEvidence
    $complete = @($data.workPackages | Where-Object state -eq 'complete')[0]
    $complete.evidence = @('looks good')
    Write-WorkData $unverifiableEvidence $data
    Assert-Fails $unverifiableEvidence 'no machine-recognizable evidence reference'

    $mutatingDashboard = New-Fixture 'mutating-dashboard'
    $dashboardPath = Join-Path $mutatingDashboard 'dashboard.html'
    $dashboard = (Get-Content -LiteralPath $dashboardPath -Raw).Replace('milestones.slice().sort(', 'milestones.sort(')
    [IO.File]::WriteAllText($dashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
    Assert-Fails $mutatingDashboard 'must sort a copied milestones array'

    $embeddedData = New-Fixture 'embedded-data'
    $dashboardPath = Join-Path $embeddedData 'dashboard.html'
    $dashboard = (Get-Content -LiteralPath $dashboardPath -Raw).Replace(
        'const dashboardData = window.WORK_PACKAGES_DATA;',
        'window.WORK_PACKAGES_DATA = {}; const dashboardData = window.WORK_PACKAGES_DATA;')
    [IO.File]::WriteAllText($dashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
    Assert-Fails $embeddedData 'must consume window.WORK_PACKAGES_DATA exactly once|must not define WORK_PACKAGES_DATA'

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
