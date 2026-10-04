#Requires -Version 7
# Shared acceptance lifecycle. No SHPM writes and no process-name-wide termination.
Set-StrictMode -Version Latest

function ConvertTo-CmdQuoted {
    param([string]$Value)
    if ($Value -match '[\r\n%]') { throw 'Detached process tokens cannot contain newlines or cmd environment expansion.' }
    $sb = [Text.StringBuilder]::new()
    [void]$sb.Append('"'); $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') { $backslashes++; continue }
        if ($ch -eq '"') {
            [void]$sb.Append('\' * (($backslashes * 2) + 1)); [void]$sb.Append('"'); $backslashes = 0; continue
        }
        if ($backslashes -gt 0) { [void]$sb.Append('\' * $backslashes); $backslashes = 0 }
        [void]$sb.Append($ch)
    }
    [void]$sb.Append('\' * ($backslashes * 2)); [void]$sb.Append('"')
    $sb.ToString()
}

function Start-DetachedProcess {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$StdoutPath, [string]$StderrPath,
        [string]$WorkingDirectory, [ValidateRange(1, 3)][int]$Attempts = 3, [string]$ExitCodePath)
    $quoted = (@($FilePath) + $ArgumentList | ForEach-Object { ConvertTo-CmdQuoted $_ }) -join ' '
    $inner = "$quoted > $(ConvertTo-CmdQuoted $StdoutPath) 2> $(ConvertTo-CmdQuoted $StderrPath)"
    if ($ExitCodePath) {
        # A fresh per-run batch records even a child that exits before CIM observes its PID.
        # Separate lines expand errorlevel after the command, without delayed environment expansion.
        $batch = "$ExitCodePath.launch.cmd"
        $body = "@echo off`r`nchcp 65001 > nul`r`n$inner`r`n" +
            'set "fixtureExit=%errorlevel%"' + "`r`n" +
            "echo %fixtureExit% > $(ConvertTo-CmdQuoted $ExitCodePath)`r`n" + 'exit /b %fixtureExit%' + "`r`n"
        [IO.File]::WriteAllText($batch, $body, [Text.UTF8Encoding]::new($false))
        $inner = ConvertTo-CmdQuoted $batch
    }
    $comspec = if ($env:ComSpec) { $env:ComSpec } else { Join-Path $env:SystemRoot 'System32/cmd.exe' }
    # CIM does not inherit the caller's handles or its process-only environment.
    # Supply the environment through the startup object, never through command text.
    $environmentBlock = @([Environment]::GetEnvironmentVariables('Process').GetEnumerator() |
        ForEach-Object { "$($_.Key)=$($_.Value)" })
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
        ShowWindow = [uint16]0; EnvironmentVariables = [string[]]$environmentBlock
    }
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
            CommandLine = "$comspec /d /s /c `"$inner`""
            CurrentDirectory = $WorkingDirectory
            ProcessStartupInformation = $startup
        }
        if ($result.ReturnValue -eq 0) { return [int]$result.ProcessId }
        if ($attempt -lt $Attempts) { Start-Sleep -Milliseconds 500 }
    }
    throw "Win32_Process.Create failed (ReturnValue=$($result.ReturnValue), attempts=$Attempts)."
}

function Test-FixtureLoopbackBind {
    param([int]$Port)
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $probe.Start(); return $true } catch [Net.Sockets.SocketException] { return $false }
    finally { $probe.Stop() }
}

function Assert-FixturePortsBindable {
    param([int[]]$Ports, [string]$EvidenceDirectory)
    $excluded = netsh interface ipv4 show excludedportrange protocol=tcp
    $dynamic = netsh interface ipv4 show dynamicport tcp
    $facts = @(foreach ($port in $Ports) {
        $bindable = Test-FixtureLoopbackBind $port
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        [ordered]@{ port = $port; bindable = $bindable; pids = @($listeners | ForEach-Object { $_.OwningProcess }) }
    })
    if ($EvidenceDirectory) {
        $excluded | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'excluded-ports.log')
        $dynamic | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'dynamic-ports.log')
        $facts | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'port-preflight.json')
    }
    $bad = @($facts | Where-Object { -not $_.bindable })
    if ($bad.Count) {
        $details = ($bad | ForEach-Object { "port=$($_.port) pid=$($_.pids -join ',')" }) -join '; '
        throw "Loopback port precheck failed: $details. Excluded ranges: $($excluded -join ' ')"
    }
}

function New-RunFixture {
    param([string]$RepositoryRoot, [int]$Slot, [string]$TaskTag, [string]$BuildMode, [int[]]$Ports)
    if ($TaskTag -notmatch '^[a-z0-9-]+$') { throw 'Fixture task tag must use lowercase letters, digits and hyphens.' }
    $root = [IO.Path]::GetFullPath($RepositoryRoot)
    $base = Join-Path $root "local-tmp/slot-$Slot-$TaskTag/evidence"
    $evidence = Join-Path $base ("run-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')-$([Guid]::NewGuid().ToString('N').Substring(0,8))")
    Assert-FixtureExternalEvidence $evidence
    $null = New-Item -ItemType Directory -Force -Path $evidence
    [pscustomobject]@{
        Root = $root; Base = $base; Evidence = $evidence; BuildMode = $BuildMode
        Ports = [Collections.Generic.List[int]]::new([int[]]$Ports)
        Processes = [Collections.Generic.List[object]]::new()
        Watch = [Diagnostics.Stopwatch]::StartNew(); Timings = [ordered]@{}
        Phase = 'preparation'; PhaseStartMs = [long]0; FirstFailure = $null
        TestExitCode = 1; Cleanup = $null; RetryCount = 0
    }
}

function Assert-FixtureExternalEvidence {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if (($full -split '[/\\]') -contains 'target') { throw "Evidence cannot reside under target: $full" }
}

function Get-FixtureCommandHash {
    param([string]$CommandLine)
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($CommandLine))).ToLowerInvariant()
}

function Get-FixtureProcessFact {
    param($Process)
    # Bearer arguments remain supported for existing MCP fixtures but are never recorded verbatim.
    $safeCommand = [regex]::Replace([string]$Process.CommandLine, '(--expected-bearer\s+)("[^"]*"|\S+)', '$1<redacted>')
    [pscustomobject]@{ pid = [int]$Process.ProcessId; parentPid = [int]$Process.ParentProcessId
        name = [string]$Process.Name; created = $Process.CreationDate.ToUniversalTime().ToString('o')
        commandLine = $safeCommand; identityHash = Get-FixtureCommandHash $Process.CommandLine }
}

function Update-FixtureProcessTree {
    param($Entry)
    $queue = [Collections.Generic.Queue[int]]::new(); $queue.Enqueue($Entry.WrapperPid)
    $visited = @{}
    while ($queue.Count) {
        $parent = $queue.Dequeue()
        if ($visited.ContainsKey($parent)) { continue }; $visited[$parent] = $true
        if (-not $Entry.Owned.ContainsKey($parent)) { continue }
        $knownParent = $Entry.Owned[$parent]
        $currentParent = Get-CimInstance Win32_Process -Filter "ProcessId=$parent" -ErrorAction Stop
        if ($currentParent -and ((Get-FixtureCommandHash $currentParent.CommandLine) -ne $knownParent.identityHash -or
                $currentParent.CreationDate.ToUniversalTime().ToString('o') -ne $knownParent.created)) { continue }
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parent" -ErrorAction Stop)) {
            if ($child.CreationDate.ToUniversalTime() -lt [DateTime]::Parse($knownParent.created).ToUniversalTime()) { continue }
            $fact = Get-FixtureProcessFact $child
            if (-not $Entry.Owned.ContainsKey($fact.pid)) { $Entry.Owned[$fact.pid] = $fact }
            $queue.Enqueue($fact.pid)
            if ($Entry.Id -eq 0 -and $child.Name -notin @('cmd.exe', 'conhost.exe')) { $Entry.Id = $fact.pid }
        }
    }
}

function Start-FixtureProcess {
    param($Fixture, [string]$Name, [string]$FilePath, [string[]]$ArgumentList,
        [string]$WorkingDirectory, [string]$StdoutPath, [string]$StderrPath, [int[]]$Ports = @())
    $exitPath = "$StdoutPath.exit-code"
    if (Test-Path -LiteralPath $exitPath) { throw 'Managed command exit evidence must be fresh.' }
    $wrapper = Start-DetachedProcess -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory `
        -StdoutPath $StdoutPath -StderrPath $StderrPath -Attempts 1 -ExitCodePath $exitPath
    $entry = [pscustomobject]@{ Name = $Name; Id = 0; WrapperPid = $wrapper; Owned = @{}
        Ports = [int[]]$Ports; Stdout = $StdoutPath; Stderr = $StderrPath; ExitCodePath = $exitPath
        Started = [Diagnostics.Stopwatch]::GetTimestamp() }
    # Register before any observation that may fail, so finally owns the wrapper immediately.
    $Fixture.Processes.Add($entry)
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$wrapper" -ErrorAction Stop
    if ($null -ne $proc) { $entry.Owned[$wrapper] = Get-FixtureProcessFact $proc }
    Update-FixtureProcessTree $entry
    $entry
}

function Wait-FixtureReady {
    param($Entry, [int]$TimeoutSec, [scriptblock]$Observe, [scriptblock]$Accept, [string]$ObjectName)
    $last = 'no successful observation'
    while ([Diagnostics.Stopwatch]::GetElapsedTime($Entry.Started).TotalSeconds -lt $TimeoutSec) {
        Update-FixtureProcessTree $Entry
        $alive = @($Entry.Owned.Keys | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
        if ((Test-Path -LiteralPath $Entry.ExitCodePath) -or $alive.Count -eq 0 -or
            ($Entry.Id -gt 0 -and -not (Get-Process -Id $Entry.Id -ErrorAction SilentlyContinue))) {
            $last = 'owned process exited before readiness'; break
        }
        try { $value = & $Observe } catch { $last = $_.Exception.Message; Start-Sleep -Milliseconds 100; continue }
            if (& $Accept $value) {
                foreach ($port in $Entry.Ports) {
                    $owners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop)
                    if (@($owners | Where-Object { -not $Entry.Owned.ContainsKey([int]$_.OwningProcess) }).Count) {
                        throw "Readiness port $port belongs to an unregistered process."
                    }
                }
                if ([Diagnostics.Stopwatch]::GetElapsedTime($Entry.Started).TotalSeconds -ge $TimeoutSec) { break }
                return $value
            }
            $last = 'readiness condition was not satisfied'
        Start-Sleep -Milliseconds 100
    }
    $tail = if (Test-Path -LiteralPath $Entry.Stderr) { (Get-Content -LiteralPath $Entry.Stderr -Tail 12) -join "`n" } else { '<stderr absent>' }
    throw "$( $Entry.Name ) failed readiness for $ObjectName within ${TimeoutSec}s: $last`n$tail"
}

function Get-FixtureProcessSnapshot {
    # One bulk CIM read per observation. This is intentionally not filtered by PID so callers
    # can resolve every registered identity from a single query. A failed query must surface as
    # an error, never as an empty table that would falsely satisfy "no processes remain".
    $snapshot = @{}
    foreach ($process in @(Get-CimInstance Win32_Process -Property ProcessId, CreationDate, Name, CommandLine -ErrorAction Stop)) {
        $snapshot[[int]$process.ProcessId] = $process
    }
    $snapshot
}

function Get-FixtureOwnedRuntimeProcesses {
    param([string]$Root)
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='node.exe'" -ErrorAction Stop |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Root, [StringComparison]::OrdinalIgnoreCase) })
}

function Wait-FixtureExit {
    param($Entry, [int]$TimeoutSec)
    $deadline = [Diagnostics.Stopwatch]::GetTimestamp()
    while ([Diagnostics.Stopwatch]::GetElapsedTime($deadline).TotalSeconds -lt $TimeoutSec) {
        Update-FixtureProcessTree $Entry
        if (Test-Path -LiteralPath $Entry.ExitCodePath) {
            $value = (Get-Content -Raw -LiteralPath $Entry.ExitCodePath).Trim()
            if ($value -match '^-?\d+$') { return [int]$value }
        }
        if (-not (Get-Process -Id $Entry.WrapperPid -ErrorAction SilentlyContinue)) { throw "$($Entry.Name) exited without valid exit evidence; inspect $($Entry.Stderr)." }
        Start-Sleep -Milliseconds 100
    }
    throw "$($Entry.Name) exceeded ${TimeoutSec}s; inspect $($Entry.Stderr)."
}

function Wait-FixturePortsReleased {
    param([int[]]$Ports, [long]$Started, [int]$TimeoutSec, [string]$Root = '', [object[]]$OwnedFacts = @())
    $facts = @{}; $first = @{}
    do {
        foreach ($port in $Ports) {
            if ($facts.ContainsKey($port)) { continue }
            if (Test-FixtureLoopbackBind $port) {
                $facts[$port] = [ordered]@{ port = $port; released = $true
                    releaseMs = [Diagnostics.Stopwatch]::GetElapsedTime($Started).TotalMilliseconds
                    firstBindFailure = $first.ContainsKey($port)
                    firstTcpState = $(if ($first.ContainsKey($port)) { $first[$port] } else { @() }) }
            } elseif (-not $first.ContainsKey($port)) {
                $first[$port] = @(Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
                    Select-Object State, OwningProcess, LocalPort)
            }
        }
        # One bulk query per iteration backs both the owned-runtime and tracked-identity checks.
        $snapshot = Get-FixtureProcessSnapshot
        $owned = @()
        if ($Root) {
            $owned = @(foreach ($process in $snapshot.Values) {
                if ($process.Name -in @('java.exe', 'node.exe') -and $process.CommandLine -and
                    $process.CommandLine.Contains($Root, [StringComparison]::OrdinalIgnoreCase)) { $process }
            })
        }
        $remaining = @(foreach ($fact in $OwnedFacts) {
            $current = $snapshot[[int]$fact.pid]
            # A changed command view does not prove the original process has exited.
            # Wait for the exact creation identity to disappear; never stop a mismatching view.
            if ($null -ne $current -and $current.CreationDate.ToUniversalTime().ToString('o') -eq $fact.created) { $fact.pid }
        })
        if ($facts.Count -eq $Ports.Count -and $owned.Count -eq 0 -and $remaining.Count -eq 0 -and
            [Diagnostics.Stopwatch]::GetElapsedTime($Started).TotalSeconds -le $TimeoutSec) {
            return @($Ports | ForEach-Object { $facts[$_] })
        }
        Start-Sleep -Milliseconds 10
    } while ([Diagnostics.Stopwatch]::GetElapsedTime($Started).TotalSeconds -lt $TimeoutSec)
    $failure = [InvalidOperationException]::new("Cleanup exceeded ${TimeoutSec}s: ports=$(($Ports | Where-Object { -not $facts.ContainsKey($_) }) -join ','); ownedRuntimeProcesses=$($owned.Count); trackedPids=$($remaining -join ',').")
    $failure.Data['portFacts'] = @(foreach ($port in $Ports) {
        if ($facts.ContainsKey($port)) { $facts[$port] } else {
            @{ port = $port; released = $false; firstBindFailure = $first.ContainsKey($port); firstTcpState = $first[$port] }
        }
    })
    throw $failure
}

function Assert-FixtureRoundReleased {
    param([string]$Root, [int[]]$Ports, [string]$RoundName)
    $started = [Diagnostics.Stopwatch]::GetTimestamp()
    $null = Wait-FixturePortsReleased -Ports $Ports -Started $started -TimeoutSec 15 -Root $Root
}

function Stop-FixtureProcesses {
    param($Fixture)
    $started = [Diagnostics.Stopwatch]::GetTimestamp()
    # Original W18 allows 10 seconds for each managed process; binding consumes this same total budget.
    $budget = [Math]::Max(1, @($Fixture.Processes | Where-Object Name -ne 'Playwright').Count) * 10
    $failures = [Collections.Generic.List[string]]::new()
    $reusedPids = [Collections.Generic.List[int]]::new()
    $identityChanges = [Collections.Generic.List[object]]::new()
    foreach ($entry in @($Fixture.Processes | Sort-Object Started -Descending)) {
        try { Update-FixtureProcessTree $entry } catch { $failures.Add($_.Exception.Message) }
        foreach ($fact in @($entry.Owned.Values | Sort-Object created -Descending)) {
            try {
                $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($fact.pid)" -ErrorAction Stop
                if ($null -eq $current) { continue }
                # The original child exited and Windows reused its PID. Never stop the new owner.
                if ($current.CreationDate.ToUniversalTime().ToString('o') -ne $fact.created) {
                    $reusedPids.Add($fact.pid); continue
                }
                $currentHash = Get-FixtureCommandHash $current.CommandLine
                if ($currentHash -ne $fact.identityHash) {
                    # Refuse to stop this PID. Its command view may change while it exits;
                    # the shared cleanup deadline still requires the original creation to end.
                    $identityChanges.Add([ordered]@{ pid = $fact.pid
                        expectedName = $fact.name; expectedCreated = $fact.created; expectedHash = $fact.identityHash
                        currentName = [string]$current.Name
                        currentCreated = $current.CreationDate.ToUniversalTime().ToString('o')
                        currentHash = $currentHash; currentCommandLineNull = $null -eq $current.CommandLine
                        observedMs = [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalMilliseconds
                        stopRefused = $true; disposition = 'WAITING'; resolvedMs = $null })
                    continue
                }
                Stop-Process -Id $fact.pid -Force -ErrorAction Stop
            } catch { $failures.Add($_.Exception.Message) }
        }
    }
    $ports = @($Fixture.Ports | Select-Object -Unique)
    $ownedFacts = @($Fixture.Processes | ForEach-Object { $_.Owned.Values })
    $released = @()
    try {
        if ($Fixture.Processes.Count) {
            $released = @(Wait-FixturePortsReleased -Ports $ports -Started $started -TimeoutSec $budget -Root $Fixture.Root -OwnedFacts $ownedFacts)
        } else {
            # Preflight can reject another owner's port. We created no process and must not stop that owner.
            $released = @(foreach ($port in $ports) { @{ port = $port; bindable = Test-FixtureLoopbackBind $port; managed = $false } })
        }
    }
    catch { $released = @($_.Exception.Data['portFacts']); $failures.Add($_.Exception.Message) }
    # Post-cleanup verification shares one bulk snapshot instead of one query per PID.
    $snapshot = Get-FixtureProcessSnapshot
    $remaining = @(foreach ($fact in $ownedFacts) {
        $current = $snapshot[[int]$fact.pid]
        if ($null -ne $current -and $current.CreationDate.ToUniversalTime().ToString('o') -eq $fact.created) { $fact.pid }
    })
    $remaining = @($remaining | Select-Object -Unique)
    foreach ($change in $identityChanges) {
        $current = $snapshot[[int]$change.pid]
        $change.resolvedMs = [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalMilliseconds
        if ($null -eq $current) { $change.disposition = 'ORIGINAL_EXITED' }
        elseif ($current.CreationDate.ToUniversalTime().ToString('o') -ne $change.expectedCreated) {
            $change.disposition = 'PID_REUSED'; $reusedPids.Add($change.pid)
        } else {
            $change.disposition = 'ORIGINAL_STILL_PRESENT'
            $failures.Add("Refusing changed process identity pid=$($change.pid)")
        }
    }
    if ($remaining.Count) { $failures.Add("Managed process PIDs remain: $($remaining -join ',')") }
    $Fixture.Cleanup = [ordered]@{ success = $failures.Count -eq 0; budgetMs = $budget * 1000
        elapsedMs = [Diagnostics.Stopwatch]::GetElapsedTime($started).TotalMilliseconds
        ports = $released; remainingPids = $remaining; reusedPids = @($reusedPids | Select-Object -Unique)
        failures = $failures.ToArray(); identityChanges = $identityChanges.ToArray() }
    $registry = @($Fixture.Processes | ForEach-Object {
        [ordered]@{ name = $_.Name; actualPid = $_.Id; wrapperPid = $_.WrapperPid; ports = $_.Ports
            stdout = $_.Stdout; stderr = $_.Stderr; owned = @($_.Owned.Values) }
    })
    ConvertTo-Json -InputObject $registry -Depth 8 | Set-Content -Encoding utf8 (Join-Path $Fixture.Evidence 'managed-processes.json')
    if ($failures.Count) { throw "Managed cleanup failed: $($failures -join '; ')" }
}

function Set-FixturePhase {
    param($Fixture, [string]$Phase)
    $Fixture.Timings["$($Fixture.Phase)Ms"] = $Fixture.Watch.ElapsedMilliseconds - $Fixture.PhaseStartMs
    $Fixture.Phase = $Phase; $Fixture.PhaseStartMs = $Fixture.Watch.ElapsedMilliseconds
}

function Write-FixtureCaseResults {
    param([string]$EvidenceDirectory, [string[]]$ExpectedCases = @())
    $rows = [Collections.Generic.List[object]]::new()
    $resultFile = Join-Path $EvidenceDirectory 'results.json'
    if (Test-Path -LiteralPath $resultFile) {
        $report = Get-Content -Raw -LiteralPath $resultFile | ConvertFrom-Json
        function Read-FixtureSuite {
            param($Suite)
            foreach ($spec in @($(if ($Suite.PSObject.Properties['specs']) { $Suite.specs }))) {
                foreach ($test in @($spec.tests)) {
                    $result = @($test.results) | Select-Object -First 1
                    $status = if ($null -eq $result -or $result.status -eq 'skipped') { 'NOT_RUN' }
                        elseif ($result.status -eq 'passed') { 'PASS' } else { 'FAIL' }
                    $error = if ($null -ne $result -and $result.PSObject.Properties['error']) { $result.error } else { $null }
                    $attachments = @(if ($null -ne $result) {
                        $result.attachments | Where-Object { $_.PSObject.Properties['path'] } |
                            ForEach-Object { [IO.Path]::GetRelativePath($EvidenceDirectory, $_.path) }
                    })
                    $rows.Add([ordered]@{ name = $spec.title; result = $status; expectedStatus = $test.expectedStatus
                        firstFailure = $(if ($null -ne $error) { [string]$error.message } else { $null })
                        evidence = $attachments })
                }
            }
            foreach ($child in @($(if ($Suite.PSObject.Properties['suites']) { $Suite.suites }))) { Read-FixtureSuite $child }
        }
        foreach ($suite in @($report.suites)) { Read-FixtureSuite $suite }
    }
    foreach ($name in $ExpectedCases) {
        if (-not @($rows | Where-Object { $_.name -ceq $name }).Count) {
            $rows.Add([ordered]@{ name = $name; result = 'NOT_RUN'; firstFailure = $null; evidence = @() })
        }
    }
    ConvertTo-Json -InputObject $rows.ToArray() -Depth 10 | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'case-results.json')
}

function Write-FixtureFlowHealth {
    param($Fixture, [string[]]$ExpectedCases = @(), [hashtable]$Extra = @{})
    $archive = [Diagnostics.Stopwatch]::StartNew()
    Write-FixtureCaseResults -EvidenceDirectory $Fixture.Evidence -ExpectedCases $ExpectedCases
    if ($Fixture.FirstFailure -and $Fixture.FirstFailure.phase -eq 'assertions') {
        $firstCaseFailure = @(Get-Content -Raw (Join-Path $Fixture.Evidence 'case-results.json') | ConvertFrom-Json |
            Where-Object { $_.result -eq 'FAIL' }) | Select-Object -First 1
        if ($firstCaseFailure) { $Fixture.FirstFailure.case = $firstCaseFailure.name; $Fixture.FirstFailure.signature = $firstCaseFailure.firstFailure }
    }
    $Fixture.Timings.evidenceArchiveMs = $archive.ElapsedMilliseconds
    $health = [ordered]@{ sourceSha = (& git -C $Fixture.Root rev-parse HEAD)
        sourceDirty = [bool](& git -C $Fixture.Root status --porcelain); buildMode = $Fixture.BuildMode
        timings = $Fixture.Timings; totalMs = $Fixture.Watch.ElapsedMilliseconds
        firstFailure = $Fixture.FirstFailure; retryCount = $Fixture.RetryCount; testExitCode = $Fixture.TestExitCode
        cleanup = $Fixture.Cleanup; evidenceRoot = $Fixture.Evidence
        nextDynamicCompilation = 'N/A: production next start; cold build recorded separately'
        logs = @($Fixture.Processes | ForEach-Object { $_.Stdout; $_.Stderr }) }
    foreach ($key in $Extra.Keys) { $health[$key] = $Extra[$key] }
    $health | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $Fixture.Evidence 'flow-health.json')
}
