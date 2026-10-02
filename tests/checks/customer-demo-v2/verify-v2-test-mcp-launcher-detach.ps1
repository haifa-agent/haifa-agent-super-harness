#Requires -Version 7
<#
.SYNOPSIS
  Regression guard for W10-task-5: start-v2-test-mcp-servers.ps1 must not let the
  detached MCP server processes inherit the caller's stdout/stderr pipe handles.

.DESCRIPTION
  Invokes the launcher through two pipe-capturing callers and asserts the launcher
  returns within a bounded time while the servers keep running:
    1. a .NET Process with RedirectStandardOutput/Error (anonymous pipes);
    2. node's child_process.execSync (timeout option).
  After each launch it asserts both ports are still listening, the manifest PID is
  the port owner, then runs -Stop and asserts release/no residual/non-empty logs.
  The whole sequence is repeated -Rounds times (default 2).

.EXAMPLE
  pwsh -NoProfile -File tests/checks/customer-demo-v2/verify-v2-test-mcp-launcher-detach.ps1 `
      -ReportingPort 20638 -RegionPort 20639 -EvidenceDir local-tmp/slot-1-w10-launcher/detach
#>
[CmdletBinding()]
param(
    [int]$ReportingPort = 20638,
    [int]$RegionPort = 20639,
    [string]$EvidenceDir,
    [int]$Rounds = 2,
    [int]$TimeoutSec = 60,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$startScript = Join-Path $repoRoot 'scripts/mcp/start-v2-test-mcp-servers.ps1'

if (-not $EvidenceDir) {
    $EvidenceDir = Join-Path $repoRoot 'local-tmp/slot-1-w10-launcher/detach'
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

$script:failures = @()
$node = (Get-Command node -ErrorAction Stop).Source
$pwshExe = (Get-Command pwsh -ErrorAction Stop).Source

function Add-Failure {
    param([string]$Message)
    $script:failures += $Message
    Write-Output "  FAIL: $Message"
}

function Test-LoopbackBind {
    param([int]$Port)
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch { return $false } finally { try { $listener.Stop() } catch { } }
}

function Get-ListeningPid {
    param([int]$Port)
    $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $conn) { return 0 }
    return [int]$conn.OwningProcess
}

function Get-WorktreeMcpResidual {
    param([int[]]$Ports)
    $portRx = ($Ports | ForEach-Object { [regex]::Escape('--port ' + $_) }) -join '|'
    $found = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -match 'McpTestServer' -and
            $_.CommandLine -match [regex]::Escape($repoRoot) -and
            $_.CommandLine -match $portRx
        } | ForEach-Object { [int]$_.ProcessId })
    return $found
}

# Runs a process while capturing its stdout/stderr through real anonymous pipes.
# Returns a result object. readCompleted=false means a descendant kept the pipe
# write end open past the bound (the W10-task-5 defect signature).
function Invoke-PipeCaptured {
    param(
        [string]$FileName,
        [string[]]$ArgumentList,
        [int]$BoundSec
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FileName
    foreach ($a in $ArgumentList) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $repoRoot

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $exitDone = $proc.WaitForExit($BoundSec * 1000)
    $reads = [System.Threading.Tasks.Task]::WhenAll([System.Threading.Tasks.Task[]]@($outTask, $errTask))
    $readDone = $reads.Wait($BoundSec * 1000)
    $sw.Stop()

    $exitCode = $null
    if ($exitDone) { $exitCode = $proc.ExitCode }
    $stdout = if ($outTask.IsCompleted) { $outTask.Result } else { '' }
    $stderr = if ($errTask.IsCompleted) { $errTask.Result } else { '' }
    if (-not $exitDone) { try { $proc.Kill($true) } catch { } }

    return [pscustomobject]@{
        exited         = $exitDone
        exitCode       = $exitCode
        readCompleted  = $readDone
        elapsedMs      = [int]$sw.ElapsedMilliseconds
        stdout         = $stdout
        stderr         = $stderr
    }
}

function Invoke-LauncherStop {
    param([string]$Dir)
    return Invoke-PipeCaptured -FileName $pwshExe -ArgumentList @(
        '-NoProfile', '-File', $startScript, '-Stop', '-EvidenceDir', $Dir
    ) -BoundSec 60
}

# Asserts servers are live and the manifest PID owns each port, then stops them.
function Assert-And-Stop {
    param(
        [string]$Dir,
        [string]$CaseName
    )
    $manifestPath = Join-Path $Dir 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        Add-Failure "${CaseName}: manifest missing at $manifestPath"
        return
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    foreach ($entry in $manifest.mcpServers) {
        $port = [int]$entry.port
        $owner = Get-ListeningPid -Port $port
        if ($owner -eq 0) {
            Add-Failure "${CaseName}: $($entry.id) port $port not listening after launch"
            continue
        }
        if ($owner -ne [int]$entry.pid) {
            Add-Failure "${CaseName}: $($entry.id) manifest pid=$($entry.pid) != port owner=$owner"
        } else {
            Write-Output "  ${CaseName}: $($entry.id) port $port listening pid=$owner (manifest match)"
        }
        $stdoutLog = [string]$entry.stdout
        if (-not (Test-Path -LiteralPath $stdoutLog) -or
            (Get-Item -LiteralPath $stdoutLog).Length -eq 0) {
            Add-Failure "${CaseName}: $($entry.id) stdout log empty/missing: $stdoutLog"
        }
    }

    $stop = Invoke-LauncherStop -Dir $Dir
    if (-not $stop.readCompleted -or -not $stop.exited -or $stop.exitCode -ne 0) {
        Add-Failure "${CaseName}: -Stop did not complete cleanly (exited=$($stop.exited) code=$($stop.exitCode) read=$($stop.readCompleted))"
    }

    $deadline = (Get-Date).AddSeconds(10)
    $portsFree = $false
    while ((Get-Date) -lt $deadline) {
        if ((Test-LoopbackBind -Port $ReportingPort) -and (Test-LoopbackBind -Port $RegionPort)) {
            $portsFree = $true
            break
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $portsFree) {
        Add-Failure "${CaseName}: ports not rebindable after -Stop ($ReportingPort/$RegionPort)"
    }
    $residual = @(Get-WorktreeMcpResidual -Ports @($ReportingPort, $RegionPort))
    if ($residual.Count -gt 0) {
        Add-Failure "${CaseName}: residual MCP fixture processes: $($residual -join ', ')"
    }
    Write-Output "  ${CaseName}: ports released=$portsFree residual=$($residual -join ',')"
}

function New-LauncherArgs {
    param([string]$Dir)
    $args = @(
        '-NoProfile', '-File', $startScript,
        '-ReportingPort', "$ReportingPort", '-RegionPort', "$RegionPort",
        '-EvidenceDir', $Dir
    )
    if ($SkipBuild) { $args += '-SkipBuild' }
    return , $args
}

for ($round = 1; $round -le $Rounds; $round++) {
    $roundDir = Join-Path $EvidenceDir "round$round"
    New-Item -ItemType Directory -Force -Path $roundDir | Out-Null
    Write-Output "=== round $round ==="

    # Case A: pwsh subprocess with .NET pipe capture.
    Write-Output "  case A: pwsh subprocess, .NET redirected pipes (bound ${TimeoutSec}s)"
    $caseA = Invoke-PipeCaptured -FileName $pwshExe -ArgumentList (New-LauncherArgs -Dir $roundDir) -BoundSec $TimeoutSec
    Write-Output "    exited=$($caseA.exited) exitCode=$($caseA.exitCode) readCompleted=$($caseA.readCompleted) elapsedMs=$($caseA.elapsedMs)"
    if (-not $caseA.readCompleted) {
        Add-Failure "case A: pipe capture did not complete within ${TimeoutSec}s (handle inherited; elapsedMs=$($caseA.elapsedMs))"
    } elseif ($caseA.exitCode -ne 0) {
        Add-Failure "case A: launcher exit code $($caseA.exitCode): $($caseA.stderr)"
    }
    $caseA | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $roundDir 'launcher-capture-A.json') -Encoding UTF8
    Assert-And-Stop -Dir $roundDir -CaseName 'case A'

    # Case B: node execSync pipe capture.
    Write-Output "  case B: node child_process.execSync (timeout ${TimeoutSec}s)"
    $innerDir = $roundDir.Replace('\', '/')
    $innerScript = $startScript.Replace('\', '/')
    $skipArg = if ($SkipBuild) { ",'-SkipBuild'" } else { '' }
    $nodeScript = @"
const { execSync } = require('child_process');
const cmd = ['pwsh','-NoProfile','-File','$innerScript','-ReportingPort','$ReportingPort','-RegionPort','$RegionPort','-EvidenceDir','$innerDir'$skipArg].map(s => s.includes(' ') ? '"' + s + '"' : s).join(' ');
const out = execSync(cmd, { timeout: $($TimeoutSec * 1000), encoding: 'utf8', stdio: ['ignore','pipe','pipe'] });
process.stdout.write(out);
"@
    $caseB = Invoke-PipeCaptured -FileName $node -ArgumentList @('-e', $nodeScript) -BoundSec ($TimeoutSec + 30)
    Write-Output "    exited=$($caseB.exited) exitCode=$($caseB.exitCode) readCompleted=$($caseB.readCompleted) elapsedMs=$($caseB.elapsedMs)"
    if (-not $caseB.readCompleted -or -not $caseB.exited) {
        Add-Failure "case B: node execSync capture did not complete within $($TimeoutSec + 30)s (elapsedMs=$($caseB.elapsedMs))"
    } elseif ($caseB.exitCode -ne 0) {
        Add-Failure "case B: node execSync failed (exit $($caseB.exitCode)): $($caseB.stderr)"
    }
    $caseB | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $roundDir 'launcher-capture-B.json') -Encoding UTF8
    Assert-And-Stop -Dir $roundDir -CaseName 'case B'
}

$result = [ordered]@{
    schemaVersion = 'haifa.customer-demo-v2.w10-mcp-launcher-detach/v1'
    reportingPort = $ReportingPort
    regionPort    = $RegionPort
    rounds        = $Rounds
    timeoutSec    = $TimeoutSec
    failures      = @($script:failures)
    result        = if ($script:failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
}
$resultPath = Join-Path $EvidenceDir 'launcher-detach-result.json'
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Output "result=$($result.result) failures=$($script:failures.Count) evidence=$resultPath"
if ($script:failures.Count -gt 0) { exit 1 }
exit 0
