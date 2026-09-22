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
        'contracts/upstream/deerflow/resource-seed.json',
        'third-party/deerflow/manifest.json',
        'third-party/deerflow/LICENSES/deer-flow-MIT.txt',
        'third-party/deerflow/patches/0001-memory-bff-use-server-gateway.patch',
        'frontend/package.json',
        'frontend/pnpm-lock.yaml',
        'frontend/next.config.js'
    )) { Copy-FixtureFile $root $relative }
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

try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    $valid = New-Fixture 'valid'
    $first = Invoke-Generator $valid 'target/evidence-one' -RequireSurefire
    Assert-True ($first.ExitCode -eq 0) "valid evidence fixture must pass"
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
    $data.schemaVersion = 3
    Write-WorkData $badSchema $data
    Assert-Fails $badSchema 'schemaVersion must be 4'

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

    Write-Host "W24 evidence verifier tests passed: $Assertions assertions."
}
finally {
    $resolved = [IO.Path]::GetFullPath($TempRoot)
    if ($resolved.StartsWith($TempParent + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
