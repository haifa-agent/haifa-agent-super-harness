#Requires -Version 7
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
. (Join-Path $root 'scripts/lib/run-fixture.ps1')
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root "local-tmp/slot-1-eng-runner/evidence/library-$(Get-Date -Format yyyyMMdd-HHmmss-fff)" }
$null = New-Item -ItemType Directory -Force $EvidenceDirectory
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Action) {
    & $Action
    $results.Add(@{ name = $Name; result = 'PASS' })
    Write-Output "PASS $Name"
}
Check 'reject-target-evidence' {
    $rejected = $false
    try { Assert-FixtureExternalEvidence (Join-Path $root 'target/evidence') } catch { $rejected = $true }
    if (-not $rejected) { throw 'target evidence was accepted' }
}
Check 'preflight-preserves-unmanaged-listener' {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $port = $listener.LocalEndpoint.Port
        $rejected = $false
        try { Assert-FixturePortsBindable @($port) $EvidenceDirectory } catch { $rejected = $true }
        if (-not $rejected -or (Test-FixtureLoopbackBind $port)) { throw 'Occupied port preflight did not fail or stopped its owner' }
    } finally { $listener.Stop() }
    if (-not (Test-FixtureLoopbackBind $port)) { throw 'Probe port did not release' }
}
Check 'real-provider-and-dead-child-finally-cleanup' {
    $f = New-RunFixture $root 1 eng-runner-library diagnostic @()
    try {
        $ready = Join-Path $f.Evidence 'provider.ready.json'
        $p = Start-FixtureProcess -Fixture $f -Name Provider -FilePath (Get-Command node.exe).Source `
            -ArgumentList @((Join-Path $root 'tests/ui/smoke/loopback-openai-provider.cjs'), '0', $ready) `
            -WorkingDirectory $root -StdoutPath (Join-Path $f.Evidence 'provider.out.log') -StderrPath (Join-Path $f.Evidence 'provider.err.log')
        $meta = Wait-FixtureReady $p 15 { Get-Content -Raw $ready | ConvertFrom-Json } { $args[0] -and $args[0].type -eq 'ready' } $ready
        if ($meta.pid -ne $p.Id) { throw 'Provider actual PID mismatch' }
        $f.Ports.Add(([Uri]$meta.address).Port)
        $health = Invoke-RestMethod "$($meta.address)/health" -TimeoutSec 2
        if ($health.status -ne 'ok') { throw 'Provider was not really ready' }
        $dead = Start-FixtureProcess -Fixture $f -Name DeadChild -FilePath (Get-Command node.exe).Source `
            -ArgumentList @((Join-Path $f.Evidence 'missing-node-script.cjs')) -WorkingDirectory $root `
            -StdoutPath (Join-Path $f.Evidence 'dead.out.log') -StderrPath (Join-Path $f.Evidence 'dead.err.log')
        $failed = $false
        $watch = [Diagnostics.Stopwatch]::StartNew()
        try { $null = Wait-FixtureReady $dead 5 { $false } { $args[0] } 'never-ready' }
        catch {
            $_.ToString() | Set-Content (Join-Path $f.Evidence 'expected-dead-child-failure.txt')
            $failed = $_.Exception.Message -match 'MODULE_NOT_FOUND'
        }
        if (-not $failed -or $watch.Elapsed.TotalSeconds -ge 5) { throw 'Dead child was not rejected promptly with stderr evidence' }
    } finally { Stop-FixtureProcesses $f; Write-FixtureFlowHealth $f -ExpectedCases @('diagnostic: no UI assertions') }
    if (-not $f.Cleanup.success -or @(Get-FixtureOwnedRuntimeProcesses $root).Count) { throw 'Managed processes remained after finally' }
}
Check 'case-results-preserve-fail-and-not-run' {
    $report = @{ suites = @(@{ specs = @(
        @{ title = 'failed'; tests = @(@{ expectedStatus='passed'; results=@(@{status='failed'; error=@{message='original-failure'}; attachments=@(@{path=(Join-Path $EvidenceDirectory 'raw.trace.zip')})}) }) },
        @{ title = 'skipped'; tests = @(@{ expectedStatus='skipped'; results=@(@{status='skipped'; attachments=@()}) }) }
    ); suites=@() }) }
    $report | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'results.json')
    Write-FixtureCaseResults $EvidenceDirectory
    $rows = @(Get-Content -Raw (Join-Path $EvidenceDirectory 'case-results.json') | ConvertFrom-Json)
    if ($rows[0].result -ne 'FAIL' -or $rows[0].firstFailure -ne 'original-failure' -or $rows[0].evidence[0] -ne 'raw.trace.zip' -or $rows[1].result -ne 'NOT_RUN') {
        throw 'Reporter changed failure/skipped status or lost original evidence'
    }
}
Check 'managed-command-preserves-nonzero-exit' {
    $f = New-RunFixture $root 1 eng-runner-library diagnostic @()
    $scriptFile = Join-Path $f.Evidence 'exit-seven.cjs'
    [IO.File]::WriteAllText($scriptFile, 'setTimeout(() => process.exit(7), 2000);')
    try {
        $p = Start-FixtureProcess -Fixture $f -Name Playwright -FilePath (Get-Command node.exe).Source `
            -ArgumentList @($scriptFile) -WorkingDirectory $root `
            -StdoutPath (Join-Path $f.Evidence 'command.out.log') -StderrPath (Join-Path $f.Evidence 'command.err.log')
        $code = Wait-FixtureExit $p 10
        if ($code -ne 7) { throw "Detached command lost nonzero exit: $code" }
    } finally { Stop-FixtureProcesses $f; Write-FixtureFlowHealth $f -ExpectedCases @('diagnostic: no UI assertions') }
}
ConvertTo-Json -InputObject $results.ToArray() | Set-Content (Join-Path $EvidenceDirectory 'verification-results.json')
