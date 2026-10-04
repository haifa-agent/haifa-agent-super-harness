[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository,
    [string]$DataDirectory,
    [int]$GatewayPort = 18018,
    [int]$FrontendPort = 33018,
    [int]$ReadyTimeoutSeconds = 180,
    [switch]$GatewayOnly,
    [switch]$ProductionFrontend,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $root 'deer-flow'
}
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'local-tmp/main-service-data'
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory)
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
$gatewayUrl = "http://127.0.0.1:$GatewayPort"
$frontendUrl = "http://127.0.0.1:$FrontendPort"

if ($GatewayPort -ne 18018 -or $FrontendPort -ne 33018) {
    throw 'The main worktree must use its fixed Gateway 18018 / Frontend 33018 ports.'
}
if ($ReadyTimeoutSeconds -lt 10 -or $ReadyTimeoutSeconds -gt 600) {
    throw 'ReadyTimeoutSeconds must be between 10 and 600.'
}
$portsToCheck = if ($GatewayOnly) { @($GatewayPort) } else { @($GatewayPort, $FrontendPort) }
foreach ($port in $portsToCheck) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $pids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
        throw "Port $port is already in use by PID $pids. Inspect that exact process before stopping it."
    }
}

$java = Get-Command java.exe -ErrorAction Stop
$shell = Get-Command pwsh.exe -ErrorAction Stop
$launcher = Join-Path $root 'scripts/frontend/start-haifa-frontend.ps1'
$jar = Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'

if ($Build) {
    Push-Location $root
    try {
        & mvn.cmd -o -ntp -pl super-harness-server -am package
        if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE." }
    }
    finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Server executable JAR is missing: $jar. Run mvn -o -ntp clean verify first."
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'frontend/node_modules/.bin/next.cmd'))) {
    throw 'Frozen frontend dependencies are missing. Follow the offline Corepack installation instructions in AGENTS.md.'
}

# Perform the frozen source, pinned toolchain, loopback and same-origin gates before starting either process.
$null = & $shell.Source -NoProfile -File $launcher -DryRun `
    -RepositoryRoot $root -SourceRepository $sourceRoot -DataDirectory $dataRoot `
    -GatewayBaseUrl $gatewayUrl -FrontendPort $FrontendPort -Production:$ProductionFrontend
if ($LASTEXITCODE -ne 0) { throw 'Frozen frontend dry-run gate failed.' }

$logRoot = Join-Path $dataRoot 'logs'
$null = New-Item -ItemType Directory -Force -Path $logRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$gatewayOut = Join-Path $logRoot "gateway-$stamp.out.log"
$gatewayErr = Join-Path $logRoot "gateway-$stamp.err.log"
$frontendOut = Join-Path $logRoot "frontend-$stamp.out.log"
$frontendErr = Join-Path $logRoot "frontend-$stamp.err.log"

$env:SUPER_HARNESS_GATEWAY_KIND = 'haifa'
$env:SUPER_HARNESS_PROFILE = 'local'
$env:SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
$env:SUPER_HARNESS_PORT = [string]$GatewayPort
$env:SUPER_HARNESS_DATA_ROOT = $dataRoot
$env:SUPER_HARNESS_DATA_DIR = $dataRoot
$env:SUPER_HARNESS_ENVIRONMENT = 'development'
$env:DEER_FLOW_AUTH_DISABLED = '1'
$env:DEER_FLOW_ENV = 'development'
$env:ENVIRONMENT = 'development'
$env:DEER_FLOW_TRUSTED_ORIGINS = $frontendUrl
$env:NO_PROXY = '127.0.0.1,localhost'
$env:no_proxy = '127.0.0.1,localhost'

$gatewayProcess = Start-Process -FilePath $java.Source -ArgumentList "-jar `"$jar`"" `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $gatewayOut -RedirectStandardError $gatewayErr

$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
$gatewayReady = $false
while ((Get-Date) -lt $deadline) {
    if ($gatewayProcess.HasExited) { break }
    try {
        $health = Invoke-RestMethod -Uri "$gatewayUrl/health" -TimeoutSec 3
        $me = Invoke-RestMethod -Uri "$gatewayUrl/api/v1/auth/me" -TimeoutSec 3
        if ($health.status -ceq 'ok' -and $me.id -ceq 'default' -and
            $me.email -ceq 'default@test.local' -and $me.system_role -ceq 'admin' -and
            $me.needs_setup -eq $false) {
            $gatewayReady = $true
            break
        }
    }
    catch { Start-Sleep -Milliseconds 500 }
}
if (-not $gatewayReady) {
    throw "Gateway did not become ready. PID=$($gatewayProcess.Id); logs=$gatewayOut,$gatewayErr"
}

if ($GatewayOnly) {
    [ordered]@{
        gateway = $gatewayUrl
        dataRoot = $dataRoot
        gatewayPid = $gatewayProcess.Id
        gatewayLogs = @($gatewayOut, $gatewayErr)
        health = 'PASS'
        authMe = 'PASS'
        frontend = 'EXISTING_NOT_CHECKED'
    } | ConvertTo-Json -Depth 3
    return
}

$frontendArgs = @(
    '-NoProfile', '-File', "`"$launcher`"",
    '-RepositoryRoot', "`"$root`"",
    '-SourceRepository', "`"$sourceRoot`"",
    '-DataDirectory', "`"$dataRoot`"",
    '-GatewayBaseUrl', $gatewayUrl,
    '-FrontendPort', [string]$FrontendPort
)
if ($ProductionFrontend) { $frontendArgs += '-Production' }
$frontendProcess = Start-Process -FilePath $shell.Source -ArgumentList $frontendArgs `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr

$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
$frontendReady = $false
while ((Get-Date) -lt $deadline) {
    if ($frontendProcess.HasExited) { break }
    try {
        $response = Invoke-WebRequest -Uri $frontendUrl -TimeoutSec 5 -MaximumRedirection 5
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
            $frontendReady = $true
            break
        }
    }
    catch { Start-Sleep -Milliseconds 500 }
}
if (-not $frontendReady) {
    throw "Frontend did not become ready. PID=$($frontendProcess.Id); logs=$frontendOut,$frontendErr; gateway PID=$($gatewayProcess.Id) remains running."
}

[ordered]@{
    gateway = $gatewayUrl
    frontend = $frontendUrl
    dataRoot = $dataRoot
    gatewayPid = $gatewayProcess.Id
    frontendLauncherPid = $frontendProcess.Id
    gatewayLogs = @($gatewayOut, $gatewayErr)
    frontendLogs = @($frontendOut, $frontendErr)
    health = 'PASS'
    authMe = 'PASS'
    frontendReadiness = 'PASS'
} | ConvertTo-Json -Depth 3
