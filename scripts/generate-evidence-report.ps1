[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputDirectory = "target/evidence",
    [switch]$RequireSurefire
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$PinnedDeerFlowSha = "e545c28ac301e6f9feae247c513d8ea8f83df294"
$AllowedStates = @("pending", "in_progress", "ready_for_acceptance", "blocked", "complete")
$RuntimeStatuses = @("PASS", "FAIL", "NOT_RUN")
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Get-RelativePath {
    param([string]$Root, [string]$Path)
    return ([IO.Path]::GetRelativePath($Root, $Path)).Replace('\', '/')
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-JsonFile {
    param([string]$Path)
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "Required evidence input is missing: $Path"
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -DateKind String
    }
    catch {
        throw "Invalid JSON evidence input '$Path': $($_.Exception.Message)"
    }
}

function Get-WorkPackageData {
    param([string]$Path)
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "work-packages.js is missing"
    $source = Get-Content -LiteralPath $Path -Raw
    $match = [regex]::Match($source,
        '^\s*window\.WORK_PACKAGES_DATA\s*=\s*(?<json>\{[\s\S]*\})\s*;\s*$')
    Assert-Condition $match.Success "work-packages.js must contain only window.WORK_PACKAGES_DATA = <JSON>;"
    try {
        return $match.Groups['json'].Value | ConvertFrom-Json -DateKind String
    }
    catch {
        throw "work-packages.js contains invalid JSON: $($_.Exception.Message)"
    }
}

function Get-IsoTime {
    param([object]$Value, [string]$Field)
    Assert-Condition ($Value -is [string] -and
        $Value -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$') `
        "$Field must be an ISO 8601 timestamp with a timezone"
    try {
        return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    catch {
        throw "$Field is not a valid timestamp: $Value"
    }
}

function Test-EvidenceReference {
    param([string]$Value)
    return $Value -match '(?i)(?:\bPASS\b|\bcommit\b|\bbranch\s+[0-9a-f]{7,40}\b|\bmain\s+[0-9a-f]{7,40}\b|SHA-?256|\bRun\s+[0-9a-f-]{16,}\b|\bArtifact\b|\btests?\s+PASS\b)'
}

function Test-WorkPackages {
    param([object]$Data)

    Assert-Condition ($Data.schemaVersion -eq 5) "work-packages schemaVersion must be 5"
    $packages = @($Data.workPackages)
    Assert-Condition ($packages.Count -eq 25) "work-packages must contain exactly 25 entries"
    $expectedIds = @(0..24 | ForEach-Object { "W{0:D2}" -f $_ })
    $actualIds = @($packages | ForEach-Object { [string]$_.id })
    Assert-Condition (@($actualIds | Sort-Object -Unique).Count -eq 25) "work-package ids must be unique"
    Assert-Condition (@(Compare-Object ($expectedIds | Sort-Object) ($actualIds | Sort-Object)).Count -eq 0) `
        "work-package ids must be exactly W00 through W24"

    $byId = @{}
    foreach ($package in $packages) {
        $id = [string]$package.id
        $byId[$id] = $package
        Assert-Condition ($AllowedStates -contains [string]$package.state) "$id has an invalid state"
        Assert-Condition ($package.PSObject.Properties.Name -contains 'startedAt') "$id is missing startedAt"
        Assert-Condition ($package.PSObject.Properties.Name -contains 'completedAt') "$id is missing completedAt"

        $started = $null
        $completed = $null
        if ($null -ne $package.startedAt) { $started = Get-IsoTime $package.startedAt "$id.startedAt" }
        if ($null -ne $package.completedAt) { $completed = Get-IsoTime $package.completedAt "$id.completedAt" }

        switch ([string]$package.state) {
            'pending' {
                Assert-Condition ($null -eq $started -and $null -eq $completed) `
                    "$id pending lifecycle requires startedAt=null and completedAt=null"
            }
            'in_progress' {
                Assert-Condition ($null -ne $started -and $null -eq $completed) `
                    "$id in_progress lifecycle requires startedAt and completedAt=null"
            }
            'ready_for_acceptance' {
                Assert-Condition ($null -ne $started -and $null -eq $completed) `
                    "$id ready_for_acceptance lifecycle requires startedAt and completedAt=null"
                $evidence = @($package.evidence)
                Assert-Condition ($evidence.Count -gt 0) "$id is ready_for_acceptance but has no evidence"
                Assert-Condition (@($evidence | Where-Object { Test-EvidenceReference ([string]$_) }).Count -gt 0) `
                    "$id is ready_for_acceptance but has no machine-recognizable evidence reference"
            }
            'blocked' {
                Assert-Condition ($null -eq $completed) "$id blocked lifecycle requires completedAt=null"
            }
            'complete' {
                Assert-Condition ($null -ne $started -and $null -ne $completed) `
                    "$id complete lifecycle requires both timestamps"
                Assert-Condition ($completed -ge $started) "$id completedAt precedes startedAt"
                $evidence = @($package.evidence)
                Assert-Condition ($evidence.Count -gt 0) "$id is complete but has no evidence"
                Assert-Condition (@($evidence | Where-Object { Test-EvidenceReference ([string]$_) }).Count -gt 0) `
                    "$id is complete but has no machine-recognizable evidence reference"
            }
        }
    }

    foreach ($package in $packages) {
        $id = [string]$package.id
        $prerequisites = @($package.prerequisites | ForEach-Object { [string]$_ })
        Assert-Condition (@($prerequisites | Sort-Object -Unique).Count -eq $prerequisites.Count) `
            "$id contains duplicate prerequisites"
        foreach ($prerequisite in $prerequisites) {
            Assert-Condition $byId.ContainsKey($prerequisite) "$id references missing prerequisite $prerequisite"
            Assert-Condition ($prerequisite -ne $id) "$id cannot reference itself as a prerequisite"
        }
    }

    $colors = @{}
    function Visit-Package([string]$Id, [string[]]$Stack) {
        if ($colors[$Id] -eq 'gray') {
            throw "work-package prerequisite cycle detected: $($Stack + $Id -join ' -> ')"
        }
        if ($colors[$Id] -eq 'black') { return }
        $colors[$Id] = 'gray'
        foreach ($prerequisite in @($byId[$Id].prerequisites)) {
            Visit-Package ([string]$prerequisite) ($Stack + $Id)
        }
        $colors[$Id] = 'black'
    }
    foreach ($id in $actualIds) { Visit-Package $id @() }

    $crossCutting = @($packages | Where-Object {
        $_.PSObject.Properties.Name -contains 'tags' -and @($_.tags) -contains '长期横切'
    } | ForEach-Object { [string]$_.id })
    $taskGroups = @($Data.deliveryTasks.PSObject.Properties.Name)
    $activeTaskPackages = @()
    $activeTaskPorts = @()
    $activeTaskRoots = @()
    foreach ($id in $taskGroups) {
        Assert-Condition $byId.ContainsKey($id) "delivery tasks reference missing work package $id"
        Assert-Condition ($crossCutting -notcontains $id) "$id is cross-cutting and must not use a bounded delivery task group"
        $tasks = @($Data.deliveryTasks.$id)
        Assert-Condition ($tasks.Count -ge 1 -and $tasks.Count -le 4) "$id must have 1 to 4 delivery tasks; more requires human scope decision"
        for ($index = 0; $index -lt $tasks.Count; $index++) {
            $task = $tasks[$index]
            Assert-Condition ([string]$task.id -eq "task-$($index + 1)") "$id delivery task ids must be sequential task-1 through task-4"
            Assert-Condition ($AllowedStates -contains [string]$task.status) "$id/$($task.id) has an invalid state"
            Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$task.title)) "$id/$($task.id) is missing a title"
            if ([string]$task.status -eq 'in_progress') {
                $activeTaskPackages += $id
                foreach ($field in @('prompt', 'owner', 'branch', 'worktree', 'slot', 'ports', 'dataRoot', 'exit', 'lastCheckpointAt', 'nextCheckpoint')) {
                    Assert-Condition ($task.PSObject.Properties.Name -contains $field -and
                        -not [string]::IsNullOrWhiteSpace([string]$task.$field)) `
                        "$id/$($task.id) is in progress without a complete task card: $field"
                }
                $lastCheckpoint = Get-IsoTime $task.lastCheckpointAt "$id/$($task.id).lastCheckpointAt"
                $nextCheckpoint = Get-IsoTime $task.nextCheckpoint "$id/$($task.id).nextCheckpoint"
                Assert-Condition ($nextCheckpoint -gt $lastCheckpoint -and
                    $nextCheckpoint -le $lastCheckpoint.AddMinutes(15)) `
                    "$id/$($task.id) next checkpoint must be within 15 minutes"
                $slotPorts = @{ '1' = '20618/25618'; '2' = '30618/35618'; '3' = '40618/45618' }
                Assert-Condition ($slotPorts.ContainsKey([string]$task.slot) -and
                    [string]$task.ports -eq $slotPorts[[string]$task.slot]) `
                    "$id/$($task.id) slot and child port group do not match"
                $activeTaskPorts += [string]$task.ports
                $activeTaskRoots += [string]$task.dataRoot
            }
            if ([string]$task.status -in @('ready_for_acceptance', 'complete')) {
                foreach ($field in @('commit', 'targetedTests', 'runtimeEvidence', 'exit')) {
                    Assert-Condition ($task.PSObject.Properties.Name -contains $field -and
                        -not [string]::IsNullOrWhiteSpace([string]$task.$field)) `
                        "$id/$($task.id) is ready without delivery evidence: $field"
                }
                Assert-Condition ($task.PSObject.Properties.Name -contains 'notRun') `
                    "$id/$($task.id) must explicitly list NOT_RUN cases"
                Assert-Condition ($task.PSObject.Properties.Name -contains 'worktreeClean' -and $task.worktreeClean -eq $true) `
                    "$id/$($task.id) delivery worktree is not recorded clean"
            }
            if ([string]$task.status -eq 'complete') {
                foreach ($field in @('mainCommit', 'reviewEvidence', 'mainSmoke', 'acceptedAt')) {
                    Assert-Condition ($task.PSObject.Properties.Name -contains $field -and
                        -not [string]::IsNullOrWhiteSpace([string]$task.$field)) `
                        "$id/$($task.id) cannot complete before mainline review and acceptance: $field"
                }
                $null = Get-IsoTime $task.acceptedAt "$id/$($task.id).acceptedAt"
                if ($task.PSObject.Properties.Name -contains 'requiresHumanAcceptance' -and $task.requiresHumanAcceptance -eq $true) {
                    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$task.humanAcceptance)) `
                        "$id/$($task.id) requires explicit human acceptance"
                }
            }
        }
    }
    foreach ($package in $packages | Where-Object { $_.state -eq 'in_progress' -and $crossCutting -notcontains $_.id }) {
        Assert-Condition ($taskGroups -contains [string]$package.id) "$($package.id) needs a bounded delivery task group"
    }
    Assert-Condition ($activeTaskPackages.Count -le 3) "active development tasks consume the reserved recovery/acceptance slot"
    Assert-Condition (@($activeTaskPorts | Sort-Object -Unique).Count -eq $activeTaskPorts.Count) `
        "active delivery tasks cannot share a port group"
    Assert-Condition (@($activeTaskRoots | Sort-Object -Unique).Count -eq $activeTaskRoots.Count) `
        "active delivery tasks cannot share a data root"
    $activeTaskPackages = @($activeTaskPackages | Sort-Object -Unique)
    Assert-Condition ($activeTaskPackages.Count -le 3) "active non-cross-cutting development exceeds the default limit of three"
    $recordedActive = @($Data.executionAudit.activeNonCrossCuttingPackages | ForEach-Object { [string]$_ })
    Assert-Condition ($activeTaskPackages.Count -eq $recordedActive.Count) `
        "execution audit active package count does not match delivery tasks"
    if ($activeTaskPackages.Count -gt 0) {
        Assert-Condition (@(Compare-Object ($activeTaskPackages | Sort-Object) ($recordedActive | Sort-Object)).Count -eq 0) `
            "execution audit active packages do not match delivery tasks"
    }
    foreach ($package in $packages | Where-Object state -eq 'in_progress') {
        $audit = $Data.executionAudit.packages.([string]$package.id)
        Assert-Condition ($null -ne $audit) "$($package.id) has no current execution audit"
        foreach ($field in @('assignment', 'owner', 'worktree', 'branch', 'lastProgressAt', 'lastEvidence', 'blocker', 'nextAction')) {
            Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$audit.$field)) `
                "$($package.id) execution audit is missing $field"
        }
        $null = Get-IsoTime $audit.lastProgressAt "$($package.id).lastProgressAt"
    }

    foreach ($milestone in @($Data.milestones)) {
        $null = Get-IsoTime $milestone.at "milestone.at"
    }

    return [ordered]@{
        status = "PASS"
        schemaVersion = 5
        packageCount = 25
        stateCounts = [ordered]@{
            pending = @($packages | Where-Object state -eq 'pending').Count
            in_progress = @($packages | Where-Object state -eq 'in_progress').Count
            ready_for_acceptance = @($packages | Where-Object state -eq 'ready_for_acceptance').Count
            blocked = @($packages | Where-Object state -eq 'blocked').Count
            complete = @($packages | Where-Object state -eq 'complete').Count
        }
    }
}

function Test-ConsumerPage {
    param([string]$Path, [bool]$RequireMilestoneOrdering)
    $source = Get-Content -LiteralPath $Path -Raw
    $name = Split-Path -Leaf $Path
    Assert-Condition ([regex]::Matches($source,
        '<script\s+src=["''](?:\./)?work-packages\.js["'']\s*>').Count -eq 1) `
        "$name must load work-packages.js exactly once"
    Assert-Condition ([regex]::Matches($source, 'window\.WORK_PACKAGES_DATA').Count -eq 1) `
        "$name must consume window.WORK_PACKAGES_DATA exactly once"
    Assert-Condition (-not [regex]::IsMatch($source, 'window\.WORK_PACKAGES_DATA\s*=')) `
        "$name must not define WORK_PACKAGES_DATA"
    Assert-Condition (-not [regex]::IsMatch($source, '\bworkPackages\s*:')) `
        "$name must not embed a workPackages array"
    Assert-Condition (-not [regex]::IsMatch($source,
        '(?i)(?:===|==|case\s+)[\s(]*["'']W\d{2}["'']|["'']W\d{2}["'']\s*:')) `
        "$name must not contain work-package-id-specific rendering logic"
    if ($RequireMilestoneOrdering) {
        Assert-Condition ([regex]::IsMatch($source,
            'dashboardData\.milestones\.slice\(\)\.sort\(\s*\(left,\s*right\)\s*=>\s*Date\.parse\(right\.at\)\s*-\s*Date\.parse\(left\.at\)\s*\)')) `
            "$name must sort a copied milestones array by at descending"
        Assert-Condition (-not [regex]::IsMatch($source, 'dashboardData\.milestones\.sort\(')) `
            "$name must not sort the source milestones array in place"
    }
    return [ordered]@{ page = $name; status = "PASS"; sha256 = Get-Sha256 $Path }
}

function Test-DashboardConsumers {
    param([string]$Root)
    $results = @()
    $dashboard = Join-Path $Root 'dashboard.html'
    Assert-Condition (Test-Path -LiteralPath $dashboard -PathType Leaf) "dashboard.html is missing"
    $results += Test-ConsumerPage $dashboard $true
    $dag = Join-Path $Root 'work-package-dag.html'
    if (Test-Path -LiteralPath $dag -PathType Leaf) {
        $results += Test-ConsumerPage $dag $false
    }
    return [ordered]@{ status = "PASS"; pages = $results }
}

function Get-SurefireEvidence {
    param([string]$Root)
    $reportFiles = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter 'TEST-*.xml' |
        Where-Object { $_.FullName -match '[\\/]target[\\/]surefire-reports[\\/]' } |
        Sort-Object FullName)
    $reports = @()
    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($file in $reportFiles) {
        try { [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw }
        catch { throw "Invalid Surefire XML '$($file.FullName)': $($_.Exception.Message)" }
        $suite = $xml.testsuite
        Assert-Condition ($null -ne $suite) "Surefire report has no testsuite root: $($file.FullName)"
        $tests = [int]$suite.tests
        $failures = [int]$suite.failures
        $errors = [int]$suite.errors
        $skipped = if ($null -eq $suite.skipped -or [string]$suite.skipped -eq '') { 0 } else { [int]$suite.skipped }
        $status = if ($failures -gt 0 -or $errors -gt 0) { "FAIL" } elseif ($tests -gt 0) { "PASS" } else { "NOT_RUN" }
        $reports += [ordered]@{
            path = Get-RelativePath $Root $file.FullName
            sha256 = Get-Sha256 $file.FullName
            status = $status
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
        }
        $totals.tests += $tests
        $totals.failures += $failures
        $totals.errors += $errors
        $totals.skipped += $skipped
    }
    $overall = if ($totals.failures -gt 0 -or $totals.errors -gt 0) {
        "FAIL"
    } elseif ($reportFiles.Count -gt 0 -and $totals.tests -gt 0) {
        "PASS"
    } else {
        "NOT_RUN"
    }
    return [ordered]@{ status = $overall; totals = $totals; reports = $reports }
}

function Assert-RuntimeStatus {
    param([object]$Node, [string]$Label)
    $status = [string]$Node.runtime_status
    Assert-Condition ($script:RuntimeStatuses -contains $status) "$Label has invalid runtime_status '$status'"
    if ($status -eq 'PASS') {
        Assert-Condition ($Node.PSObject.Properties.Name -contains 'runtime_evidence' -and
            @($Node.runtime_evidence).Count -gt 0) "$Label claims runtime PASS without runtime_evidence"
    }
    return $status
}

function Get-DeerFlowEvidence {
    param([string]$Root)
    $consumerPath = Join-Path $Root 'contracts/upstream/deerflow/frontend-consumer-manifest.json'
    $resourcePath = Join-Path $Root 'contracts/upstream/deerflow/resource-seed.json'
    $frontendPath = Join-Path $Root 'third-party/deerflow/manifest.json'
    $consumer = Get-JsonFile $consumerPath
    $resources = Get-JsonFile $resourcePath
    $frontend = Get-JsonFile $frontendPath

    Assert-Condition ([string]$consumer.source.source_sha -eq $PinnedDeerFlowSha) `
        "frontend consumer manifest is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$resources.source.source_sha -eq $PinnedDeerFlowSha) `
        "resource seed is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$frontend.upstream.commit -eq $PinnedDeerFlowSha) `
        "frontend provenance is not bound to the frozen DeerFlow SHA"
    Assert-Condition ([string]$consumer.status -eq 'SOURCE_VERIFIED') `
        "frontend consumer manifest must retain SOURCE_VERIFIED static status"
    Assert-Condition ([string]$resources.status -eq 'SOURCE_VERIFIED') `
        "resource seed must retain SOURCE_VERIFIED static status"

    $runtimeStatuses = @()
    $runtimeStatuses += Assert-RuntimeStatus $consumer 'frontend consumer manifest'
    foreach ($surface in @($consumer.surfaces)) {
        Assert-Condition ([string]$surface.status -eq 'SOURCE_VERIFIED') `
            "surface '$($surface.id)' must retain SOURCE_VERIFIED static status"
        $runtimeStatuses += Assert-RuntimeStatus $surface "surface '$($surface.id)'"
    }
    $runtimeStatuses += Assert-RuntimeStatus $resources 'resource seed'

    foreach ($resource in @($resources.resources)) {
        Assert-Condition ([string]$resource.status -eq 'SOURCE_VERIFIED') `
            "resource '$($resource.id)' must retain SOURCE_VERIFIED static status"
        Assert-Condition (@('IMPORTED', 'NOT_RUN') -contains [string]$resource.import_status) `
            "resource '$($resource.id)' has invalid import_status"
        if ([string]$resource.import_status -eq 'IMPORTED') {
            $local = Join-Path $Root ([string]$resource.intended_local_path)
            Assert-Condition (Test-Path -LiteralPath $local -PathType Leaf) `
                "imported resource '$($resource.id)' is missing its local file"
            Assert-Condition ((Get-Sha256 $local) -eq [string]$resource.sha256) `
                "imported resource '$($resource.id)' hash mismatch"
        }
    }

    $license = Join-Path $Root ([string]$frontend.upstream.licenseFile)
    $lockfile = Join-Path $Root ([string]$frontend.build.lockfile)
    Assert-Condition ((Get-Sha256 $license) -eq [string]$frontend.upstream.licenseSha256) `
        "frontend provenance license hash mismatch"
    Assert-Condition ((Get-Sha256 $lockfile) -eq [string]$frontend.build.lockfileSha256) `
        "frontend provenance lockfile hash mismatch"
    foreach ($patch in @($frontend.import.patches)) {
        $patchPath = Join-Path $Root ([string]$patch.file)
        Assert-Condition (Test-Path -LiteralPath $patchPath -PathType Leaf) `
            "recorded frontend patch is missing: $($patch.file)"
        Assert-Condition ((Get-Sha256 $patchPath) -eq [string]$patch.sha256) `
            "recorded frontend patch hash mismatch: $($patch.file)"
    }

    $runtime = if ($runtimeStatuses -contains 'FAIL') { 'FAIL' }
        elseif ($runtimeStatuses -contains 'NOT_RUN') { 'NOT_RUN' }
        else { 'PASS' }
    return [ordered]@{
        validationStatus = "PASS"
        runtimeStatus = $runtime
        sourceSha = $PinnedDeerFlowSha
        consumerManifest = [ordered]@{
            path = Get-RelativePath $Root $consumerPath
            sha256 = Get-Sha256 $consumerPath
            status = [string]$consumer.status
            runtimeStatus = [string]$consumer.runtime_status
            surfaceCount = @($consumer.surfaces).Count
        }
        resourceSeed = [ordered]@{
            path = Get-RelativePath $Root $resourcePath
            sha256 = Get-Sha256 $resourcePath
            status = [string]$resources.status
            runtimeStatus = [string]$resources.runtime_status
            resourceCount = @($resources.resources).Count
        }
        frontendProvenance = [ordered]@{
            path = Get-RelativePath $Root $frontendPath
            sha256 = Get-Sha256 $frontendPath
            sourceTree = [string]$frontend.upstream.sourceTree
            inventorySha256 = [string]$frontend.import.contentInventorySha256
            patchStatus = [string]$frontend.import.patchStatus
        }
    }
}

function Write-DeterministicText {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, (($Content -replace "`r`n", "`n").TrimEnd() + "`n"), $Utf8NoBom)
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
Assert-Condition ($output.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase)) "OutputDirectory must stay inside RepositoryRoot"

$dataPath = Join-Path $root 'work-packages.js'
$data = Get-WorkPackageData $dataPath
$governance = Test-WorkPackages $data
$dashboard = Test-DashboardConsumers $root
$surefire = Get-SurefireEvidence $root
$deerflow = Get-DeerFlowEvidence $root

$overall = if ($surefire.status -eq 'FAIL' -or $deerflow.runtimeStatus -eq 'FAIL') {
    'FAIL'
} elseif ($surefire.status -eq 'NOT_RUN' -or $deerflow.runtimeStatus -eq 'NOT_RUN') {
    'NOT_RUN'
} else {
    'PASS'
}

$manifest = [ordered]@{
    schemaVersion = 1
    overallStatus = $overall
    statusPolicy = "PASS/FAIL/NOT_RUN are preserved; static validation never upgrades runtime NOT_RUN"
    architecturePolicy = [ordered]@{
        status = "NOT_RUN"
        decision = "A product model more complex than frozen DeerFlow requires pre-code maintainer review"
        w24Scope = "QA-only aggregation; no product domain object or business runtime is introduced"
    }
    governance = $governance
    dashboard = $dashboard
    mavenSurefire = $surefire
    deerflow = $deerflow
    inputs = [ordered]@{
        workPackagesSha256 = Get-Sha256 $dataPath
        haifaSdkVersion = [string]$data.baseline.haifaSdkVersion
    }
}

$null = New-Item -ItemType Directory -Force -Path $output
$manifestPath = Join-Path $output 'evidence-manifest.json'
Write-DeterministicText $manifestPath ($manifest | ConvertTo-Json -Depth 20)
$manifestHash = Get-Sha256 $manifestPath
Write-DeterministicText (Join-Path $output 'evidence-manifest.sha256') "$manifestHash  evidence-manifest.json"

$reportLines = @(
    '# W24 deterministic evidence report',
    '',
    "- Overall: **$overall**",
    "- Governance: **$($governance.status)** ($($governance.packageCount) unique packages, schema $($governance.schemaVersion))",
    "- Dashboard consumers: **$($dashboard.status)** ($(@($dashboard.pages).Count) pages)",
    "- Maven Surefire: **$($surefire.status)** ($($surefire.totals.tests) tests, $($surefire.totals.failures) failures, $($surefire.totals.errors) errors, $($surefire.totals.skipped) skipped)",
    "- DeerFlow static provenance/contracts: **$($deerflow.validationStatus)**",
    "- DeerFlow runtime parity: **$($deerflow.runtimeStatus)**",
    '- Product-model complexity gate: **NOT_RUN** (manual review is required when a product model would be more complex than frozen DeerFlow)',
    ('- Frozen DeerFlow SHA: `{0}`' -f $deerflow.sourceSha),
    ('- Manifest SHA-256: `{0}`' -f $manifestHash),
    '',
    'Static SOURCE_VERIFIED or IMPORTED evidence does not prove runtime parity. Runtime NOT_RUN remains NOT_RUN.'
)
Write-DeterministicText (Join-Path $output 'evidence-report.md') ($reportLines -join "`n")

Write-Output ([pscustomobject]@{
    overallStatus = $overall
    manifest = Get-RelativePath $root $manifestPath
    sha256 = $manifestHash
    surefireStatus = $surefire.status
    deerflowRuntimeStatus = $deerflow.runtimeStatus
} | ConvertTo-Json -Compress)

if ($surefire.status -eq 'FAIL') {
    throw "Surefire evidence contains failures or errors"
}
if ($RequireSurefire -and $surefire.status -ne 'PASS') {
    throw "Surefire evidence is required but status is $($surefire.status)"
}
if ($deerflow.runtimeStatus -eq 'FAIL') {
    throw "DeerFlow contract runtime evidence contains FAIL"
}
