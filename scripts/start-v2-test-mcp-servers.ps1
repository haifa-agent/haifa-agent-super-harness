#Requires -Version 7
<#
.SYNOPSIS
  Start/stop the two independent V2 L2 local test MCP servers (v2-reporting / v2-region).

.DESCRIPTION
  Builds tests/customer-demo-v2/mcp-servers (standalone, not in the root reactor),
  prechecks both loopback ports with a real TcpListener bind, starts each server as
  its own OS process, waits for an MCP initialize handshake, and records a manifest.
  With -Stop it terminates only the PIDs recorded in the manifest after matching the
  process command line, then verifies both ports can be rebound.

.EXAMPLE
  pwsh -NoProfile -File scripts/start-v2-test-mcp-servers.ps1 -DelayMs 0
  pwsh -NoProfile -File scripts/start-v2-test-mcp-servers.ps1 -Stop
#>
[CmdletBinding()]
param(
    [int]$ReportingPort = 20628,
    [int]$RegionPort = 20629,
    [string]$EvidenceDir,
    [long]$DelayMs = 0,
    [switch]$EnableNegativeControls,
    [string]$RegionExpectedBearer,
    [switch]$Stop,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/run-fixture.ps1')
$moduleDir = Join-Path $repoRoot 'tests/customer-demo-v2/mcp-servers'
$policyFile = Join-Path $repoRoot 'contracts/customer-demo-v2/fixtures/reporting-policy.json'
$policySha = '563e216fc77f87d968b3c34994746fa1f3368380ede30c97e03782c3895a9e9a'

if (-not $EvidenceDir) {
    $EvidenceDir = Join-Path $repoRoot 'local-tmp/slot-1-w10-test-mcp/evidence'
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$manifestPath = Join-Path $EvidenceDir 'manifest.json'

function Assert-PortsBindable {
    param([int[]]$Ports)
    Assert-FixturePortsBindable -Ports $Ports -EvidenceDirectory $EvidenceDir
}

function Get-McpClasspath {
    if (-not $SkipBuild) {
        & mvn -o -ntp -q -f (Join-Path $moduleDir 'pom.xml') compile | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "maven compile failed with exit code $LASTEXITCODE" }
    }
    & mvn -o -ntp -q -f (Join-Path $moduleDir 'pom.xml') dependency:build-classpath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "maven dependency:build-classpath failed with exit code $LASTEXITCODE" }
    $cpFile = Join-Path $moduleDir 'target/classpath.txt'
    if (-not (Test-Path -LiteralPath $cpFile)) { throw "classpath file not produced: $cpFile" }
    $deps = (Get-Content -LiteralPath $cpFile -Raw).Trim()
    $classes = Join-Path $moduleDir 'target/classes'
    return "$classes;$deps"
}

function Invoke-JsonRpc {
    param(
        [string]$Url,
        [string]$Method,
        [hashtable]$Params,
        [int]$Id = 1,
        [hashtable]$Headers = @{}
    )
    $payload = @{
        jsonrpc = '2.0'
        id      = $Id
        method  = $Method
        params  = $Params
    } | ConvertTo-Json -Depth 10 -Compress
    $resp = Invoke-WebRequest -Uri $Url -Method Post -ContentType 'application/json' `
        -Headers $Headers -Body $payload -TimeoutSec 10 -SkipHttpErrorCheck
    if ($resp.StatusCode -ne 200) {
        throw "JSON-RPC $Method returned HTTP $($resp.StatusCode): $($resp.Content)"
    }
    return $resp.Content | ConvertFrom-Json
}

function Wait-McpInitialize {
    param(
        [string]$Url,
        [string]$ExpectedServerId,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 30
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $init = Invoke-JsonRpc -Url $Url -Method 'initialize' -Headers $Headers -Params @{
                protocolVersion = '2025-06-18'
                capabilities    = @{}
                clientInfo      = @{ name = 'w10-start-precheck'; version = '1.0.0' }
            }
            if ($init.result.serverInfo.name -eq $ExpectedServerId) {
                # best-effort initialized notification
                try {
                    $note = @{ jsonrpc = '2.0'; method = 'notifications/initialized' } | ConvertTo-Json -Depth 5 -Compress
                    Invoke-WebRequest -Uri $Url -Method Post -ContentType 'application/json' `
                        -Headers $Headers -Body $note -TimeoutSec 10 -SkipHttpErrorCheck | Out-Null
                } catch { }
                return $init
            }
            $lastError = "initialize returned serverInfo.name=$($init.result.serverInfo.name)"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 300
    }
    throw "server $ExpectedServerId did not become ready at $Url within ${TimeoutSec}s: $lastError"
}

function Get-McpToolNames {
    param([string]$Url, [hashtable]$Headers = @{})
    $list = Invoke-JsonRpc -Url $Url -Method 'tools/list' -Params @{} -Id 2 -Headers $Headers
    return @($list.result.tools | ForEach-Object { $_.name })
}

function Stop-TestMcpServers {
    param([string]$ManifestPath)
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        Write-Output 'no manifest found; nothing to stop'
        return
    }
    $manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
    foreach ($entry in $manifest.mcpServers) {
        $targets = @()
        foreach ($pidProp in @('pid', 'wrapperPid')) {
            $prop = $entry.PSObject.Properties[$pidProp]
            if ($null -ne $prop -and $null -ne $prop.Value) { $targets += [int]$prop.Value }
        }
        foreach ($targetPid in $targets) {
            $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $targetPid" -ErrorAction SilentlyContinue
            if ($null -eq $proc) {
                Write-Output "pid $targetPid ($($entry.id)) already exited"
                continue
            }
            $cmd = [string]$proc.CommandLine
            $isFixture = $proc.Name -eq 'java.exe' -and $cmd -match 'McpTestServer' -and $cmd -match [regex]::Escape($entry.id)
            $isWrapper = $proc.Name -eq 'cmd.exe' -and $cmd -match 'McpTestServer' -and $cmd -match [regex]::Escape($entry.id)
            if (-not $isFixture -and -not $isWrapper) {
                throw "refusing to stop pid ${targetPid}: command line does not match the expected MCP fixture server or its wrapper"
            }
            Stop-Process -Id $targetPid -Force
            Write-Output "stopped $($entry.id) pid $targetPid ($($proc.Name))"
        }
    }
    Start-Sleep -Milliseconds 500
    $ports = @($manifest.mcpServers | ForEach-Object { [int]$_.port })
    Assert-PortsBindable -Ports $ports
    Write-Output "ports released and rebindable: $($ports -join ', ')"
}

if ($Stop) {
    Stop-TestMcpServers -ManifestPath $manifestPath
    return
}

Assert-PortsBindable -Ports @($ReportingPort, $RegionPort)

$classpath = Get-McpClasspath
$java = (Get-Command java -ErrorAction Stop).Source

function Get-JavaChildPid {
    # The manifest must record the Java PID that actually owns the listening port,
    # not the cmd.exe wrapper, so -Stop and the SDK verifier can target it.
    param(
        [int]$WrapperPid,
        [string]$ServerId,
        [int]$Port,
        [int]$TimeoutSec = 40
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $child = Get-CimInstance Win32_Process -Filter "ParentProcessId = $WrapperPid" -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -match [regex]::Escape($ServerId) -and
                $_.CommandLine -match [regex]::Escape("$Port")
            } | Select-Object -First 1
        if ($null -ne $child) { return [int]$child.ProcessId }
        Start-Sleep -Milliseconds 200
    }
    return 0
}

function Start-FixtureServer {
    param(
        [string]$ServerId,
        [int]$Port,
        [string[]]$ExtraArgs = @()
    )
    $stdout = Join-Path $EvidenceDir "$ServerId-stdout.log"
    $stderr = Join-Path $EvidenceDir "$ServerId-stderr.log"
    Remove-Item -LiteralPath $stdout, $stderr -ErrorAction SilentlyContinue
    $argList = @('-cp', $classpath, 'io.haifa.superharness.testmcp.McpTestServer',
        '--server-id', $ServerId, '--port', "$Port", '--delay-ms', "$DelayMs") + $ExtraArgs
    $wrapperPid = Start-DetachedProcess -FilePath $java -ArgumentList $argList `
        -StdoutPath $stdout -StderrPath $stderr -WorkingDirectory $repoRoot
    $javaPid = Get-JavaChildPid -WrapperPid $wrapperPid -ServerId $ServerId -Port $Port
    if ($javaPid -eq 0) {
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $wrapperPid" -ErrorAction SilentlyContinue)) {
            try { Stop-Process -Id $child.ProcessId -Force } catch { }
        }
        try { Stop-Process -Id $wrapperPid -Force } catch { }
        throw "detached MCP server $ServerId (port $Port) did not start under wrapper pid $wrapperPid"
    }
    return [pscustomobject]@{ wrapperPid = $wrapperPid; pid = $javaPid; stdout = $stdout; stderr = $stderr }
}

$negativeArgs = @()
if ($EnableNegativeControls) { $negativeArgs = @('--negative-controls', 'true') }
$regionArgs = @($negativeArgs)
if ($RegionExpectedBearer) { $regionArgs += @('--expected-bearer', $RegionExpectedBearer) }
$reporting = Start-FixtureServer -ServerId 'v2-reporting' -Port $ReportingPort `
    -ExtraArgs (@('--policy-file', $policyFile, '--policy-sha256', $policySha) + $negativeArgs)
$region = Start-FixtureServer -ServerId 'v2-region' -Port $RegionPort -ExtraArgs $regionArgs
$regionHeaders = if ($RegionExpectedBearer) { @{ Authorization = $RegionExpectedBearer } } else { @{} }

$startedAt = (Get-Date).ToUniversalTime().ToString('o')
try {
    $reportingUrl = "http://127.0.0.1:$ReportingPort/mcp"
    $regionUrl = "http://127.0.0.1:$RegionPort/mcp"
    Wait-McpInitialize -Url $reportingUrl -ExpectedServerId 'v2-reporting' | Out-Null
    Wait-McpInitialize -Url $regionUrl -ExpectedServerId 'v2-region' -Headers $regionHeaders | Out-Null
} catch {
    foreach ($s in @($reporting, $region)) {
        foreach ($procId in @($s.pid, $s.wrapperPid)) {
            try {
                if (Get-Process -Id $procId -ErrorAction SilentlyContinue) { Stop-Process -Id $procId -Force }
            } catch { }
        }
    }
    throw
}

$manifest = [ordered]@{
    schemaVersion = 'haifa.customer-demo-v2.w10-mcp-manifest/v1'
    generatedAt   = $startedAt
    evidenceDir   = $EvidenceDir
    delayMs       = $DelayMs
    negativeControls = [bool]$EnableNegativeControls
    mcpServers    = @(
        [ordered]@{
            id              = 'v2-reporting'
            pid             = $reporting.pid
            wrapperPid      = $reporting.wrapperPid
            url             = "http://127.0.0.1:$ReportingPort/mcp"
            port            = $ReportingPort
            tools           = (Get-McpToolNames -Url "http://127.0.0.1:$ReportingPort/mcp")
            fixtureSha256   = $policySha
            startedAt       = $startedAt
            stdout          = $reporting.stdout
            stderr          = $reporting.stderr
        },
        [ordered]@{
            id              = 'v2-region'
            pid             = $region.pid
            wrapperPid      = $region.wrapperPid
            url             = "http://127.0.0.1:$RegionPort/mcp"
            port            = $RegionPort
            tools           = (Get-McpToolNames -Url "http://127.0.0.1:$RegionPort/mcp" -Headers $regionHeaders)
            authRequired    = [bool]$RegionExpectedBearer
            fixtureSha256   = $null
            startedAt       = $startedAt
            stdout          = $region.stdout
            stderr          = $region.stderr
        }
    )
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output "mcp servers ready; manifest: $manifestPath"
Write-Output "  v2-reporting pid=$($reporting.pid) url=http://127.0.0.1:$ReportingPort/mcp"
Write-Output "  v2-region    pid=$($region.pid) url=http://127.0.0.1:$RegionPort/mcp"
