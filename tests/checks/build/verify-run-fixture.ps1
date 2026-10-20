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
    if (-not $f.Cleanup.success -or @(Get-FixtureOwnedRuntimeProcesses $root $f.StartedAt).Count) { throw 'Managed processes remained after finally' }
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
Check 'release-waits-for-tracked-child-without-runtime-or-ports' {
    $scriptFile = Join-Path $EvidenceDirectory 'delayed-child.ps1'
    [IO.File]::WriteAllText($scriptFile, 'Start-Sleep -Milliseconds 1500')
    $child = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',$scriptFile) -WindowStyle Hidden -PassThru
    try {
        $owned = Get-FixtureProcessFact (Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)" -ErrorAction Stop)
        $started = [Diagnostics.Stopwatch]::GetTimestamp()
        $null = Wait-FixturePortsReleased -Ports @() -Started $started -TimeoutSec 5 -OwnedFacts @($owned)
        $child.Refresh()
        if (-not $child.HasExited -or [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalMilliseconds -lt 1000) {
            throw 'Release returned while a tracked non-runtime child was still exiting'
        }
    } finally { if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }; $child.Dispose() }
}
Check 'pre-existing-runtime-is-not-owned-by-a-later-fixture' {
    $scriptFile = Join-Path $EvidenceDirectory 'pre-existing-runtime.cjs'
    [IO.File]::WriteAllText($scriptFile, 'setTimeout(() => {}, 15000);')
    $child = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @($scriptFile, $root) `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        # The node process predates the fixture but its command line contains the repository
        # root, so ownership must be bounded by the fixture start time (as with the main service).
        Start-Sleep -Milliseconds 300
        $fixture = New-RunFixture $root 1 eng-runner-library diagnostic @()
        $started = [Diagnostics.Stopwatch]::GetTimestamp()
        $null = Wait-FixturePortsReleased -Ports @() -Started $started -TimeoutSec 2 -Root $root -Since $fixture.StartedAt
        if (@(Get-FixtureOwnedRuntimeProcesses $root $fixture.StartedAt).Count -ne 0) {
            throw 'Pre-existing runtime was treated as fixture-owned'
        }
        $child.Refresh()
        if ($child.HasExited) { throw 'Pre-existing runtime was stopped' }
    } finally { if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }; $child.Dispose() }
}
Check 'tracked-child-timeout-retains-original-deadline' {
    $scriptFile = Join-Path $EvidenceDirectory 'blocked-child.ps1'
    [IO.File]::WriteAllText($scriptFile, 'Start-Sleep -Seconds 10')
    $child = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',$scriptFile) -WindowStyle Hidden -PassThru
    try {
        $owned = Get-FixtureProcessFact (Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)" -ErrorAction Stop)
        $started = [Diagnostics.Stopwatch]::GetTimestamp()
        $rejected = $false
        try { $null = Wait-FixturePortsReleased -Ports @() -Started $started -TimeoutSec 1 -OwnedFacts @($owned) }
        catch { $rejected = $_.Exception.Message -like "*Cleanup exceeded 1s*trackedPids=$($child.Id)*" }
        $child.Refresh()
        if (-not $rejected -or $child.HasExited -or [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalSeconds -gt 3) {
            throw 'Tracked child escaped timeout or changed the deadline'
        }
    } finally { if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }; $child.Dispose() }
}
# These controls use real, directly owned Windows processes and live CIM reads.
# Only the recorded expectation is changed; no process query or stop function is mocked.
function New-IdentityControlChild([string]$Label, [int]$LifetimeMs) {
    $scriptFile = Join-Path $EvidenceDirectory "$Label.ps1"
    $readyFile = Join-Path $EvidenceDirectory "$Label.ready"
    [IO.File]::WriteAllText($scriptFile, "[IO.File]::WriteAllText('$($readyFile.Replace("'", "''"))','ready'); Start-Sleep -Milliseconds $LifetimeMs")
    $child = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',$scriptFile) -WindowStyle Hidden -PassThru
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path -LiteralPath $readyFile) -and $clock.Elapsed.TotalSeconds -lt 3) { Start-Sleep -Milliseconds 10 }
    if (-not (Test-Path -LiteralPath $readyFile)) {
        if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }; $child.Dispose()
        throw 'Identity control child did not become ready before the deadline'
    }
    $fact = Get-FixtureProcessFact (Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)" -ErrorAction Stop)
    [pscustomobject]@{ Process = $child; Fact = $fact }
}
function New-IdentityControlFixture($Control) {
    $fixture = New-RunFixture $root 1 eng-runner-library diagnostic @()
    $entry = [pscustomobject]@{ Name='IdentityControl'; Id=$Control.Process.Id; WrapperPid=$Control.Process.Id
        Owned=@{}; Ports=@(); Stdout=''; Stderr=''; Started=[Diagnostics.Stopwatch]::GetTimestamp() }
    $entry.Owned[$Control.Fact.pid] = $Control.Fact
    $fixture.Processes.Add($entry)
    $fixture
}
Check 'changed-command-refuses-stop-and-waits-for-natural-exit' {
    $control = New-IdentityControlChild 'identity-short' 2500
    try {
        $control.Fact.identityHash = '0' * 64
        $fixture = New-IdentityControlFixture $control
        Stop-FixtureProcesses $fixture
        $control.Process.Refresh()
        $changes = @($fixture.Cleanup.identityChanges)
        if (-not $fixture.Cleanup.success -or -not $control.Process.HasExited -or $control.Process.ExitCode -ne 0 -or $changes.Count -ne 1 -or
                -not $changes[0].stopRefused -or $changes[0].disposition -ne 'ORIGINAL_EXITED' -or
                $changes[0].expectedCreated -ne $changes[0].currentCreated -or
                $changes[0].expectedHash -eq $changes[0].currentHash -or $fixture.Cleanup.budgetMs -ne 10000 -or
                $fixture.Cleanup.elapsedMs -gt 10000) { throw 'Changed command escaped exact-creation natural-exit validation' }
        $fixture.Cleanup | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDirectory 'identity-short-cleanup.json')
    } finally { if (-not $control.Process.HasExited) { $control.Process.Kill(); $control.Process.WaitForExit() }; $control.Process.Dispose() }
}
Check 'changed-command-persistent-process-stays-alive-and-fails-original-deadline' {
    $control = New-IdentityControlChild 'identity-long' 30000
    try {
        $control.Fact.identityHash = '0' * 64
        $fixture = New-IdentityControlFixture $control
        $rejected = $false
        try { Stop-FixtureProcesses $fixture }
        catch { $rejected = $_.Exception.Message -like "*Refusing changed process identity pid=$($control.Process.Id)*" }
        $control.Process.Refresh()
        $changes = @($fixture.Cleanup.identityChanges)
        if (-not $rejected -or $fixture.Cleanup.success -or $control.Process.HasExited -or $changes.Count -ne 1 -or
                -not $changes[0].stopRefused -or $changes[0].disposition -ne 'ORIGINAL_STILL_PRESENT' -or
                $fixture.Cleanup.remainingPids -notcontains $control.Process.Id -or $fixture.Cleanup.budgetMs -ne 10000 -or
                $fixture.Cleanup.elapsedMs -lt 10000 -or $fixture.Cleanup.elapsedMs -gt 13000) {
            throw 'Persistent changed command was stopped or escaped the original cleanup deadline'
        }
        $fixture.Cleanup | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDirectory 'identity-long-cleanup.json')
    } finally { if (-not $control.Process.HasExited) { $control.Process.Kill(); $control.Process.WaitForExit() }; $control.Process.Dispose() }
}
Check 'different-creation-preserves-real-live-process-as-reused-pid' {
    $control = New-IdentityControlChild 'identity-reused' 30000
    try {
        $control.Fact.created = ([DateTime]::Parse($control.Fact.created).AddSeconds(-1)).ToUniversalTime().ToString('o')
        $fixture = New-IdentityControlFixture $control
        Stop-FixtureProcesses $fixture
        $control.Process.Refresh()
        if (-not $fixture.Cleanup.success -or $control.Process.HasExited -or
                $fixture.Cleanup.reusedPids -notcontains $control.Process.Id -or
                @($fixture.Cleanup.identityChanges).Count -ne 0 -or @($fixture.Cleanup.remainingPids).Count -ne 0) {
            throw 'A different creation identity was stopped or mistaken for the original tracked process'
        }
        $fixture.Cleanup | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDirectory 'identity-reused-cleanup.json')
    } finally { if (-not $control.Process.HasExited) { $control.Process.Kill(); $control.Process.WaitForExit() }; $control.Process.Dispose() }
}
Check 'bulk-snapshot-releases-seventeen-exiting-children-under-five-seconds' {
    $controls = [Collections.Generic.List[object]]::new()
    try {
        for ($i = 0; $i -lt 17; $i++) { $controls.Add((New-IdentityControlChild "bulk-$i" 2500)) }
        $ownedFacts = @($controls | ForEach-Object { $_.Fact })
        # Bounded, object-specific wait for every registered child to actually exit, so the
        # release assertion measures the bulk observation path rather than startup churn.
        $exitWatch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $alive = @($controls | Where-Object { $_.Process.Refresh(); -not $_.Process.HasExited })
            if ($alive.Count) { Start-Sleep -Milliseconds 25 }
        } while ($alive.Count -and $exitWatch.Elapsed.TotalSeconds -lt 15)
        if ($alive.Count) { throw 'Bulk snapshot control children did not exit within 15s' }
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
        $started = [Diagnostics.Stopwatch]::GetTimestamp()
        $facts = @(Wait-FixturePortsReleased -Ports @($port) -Started $started -TimeoutSec 10 -OwnedFacts $ownedFacts)
        $elapsedMs = [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalMilliseconds
        if ($elapsedMs -ge 5000) { throw "Bulk snapshot release waited ${elapsedMs}ms for 17 tracked children (expected <5000ms)" }
        if (@($facts).Count -ne 1 -or -not $facts[0].released) { throw 'Bulk snapshot did not report the port released' }
        [pscustomobject]@{ tracked = $ownedFacts.Count; elapsedMs = [math]::Round($elapsedMs, 1); released = [bool]$facts[0].released } |
            ConvertTo-Json | Set-Content (Join-Path $EvidenceDirectory 'bulk-snapshot-release.json')
    } finally {
        foreach ($control in $controls) {
            $control.Process.Refresh()
            if (-not $control.Process.HasExited) { $control.Process.Kill(); $control.Process.WaitForExit() }
            $control.Process.Dispose()
        }
    }
}
ConvertTo-Json -InputObject $results.ToArray() | Set-Content (Join-Path $EvidenceDirectory 'verification-results.json')
