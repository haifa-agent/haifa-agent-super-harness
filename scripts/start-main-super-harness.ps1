[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository,
    [string]$DataDirectory,
    [string]$JarPath,
    [string]$FrontendRoot,
    [int]$GatewayPort = 18018,
    [int]$FrontendPort = 33018,
    [int]$ReadyTimeoutSeconds = 180,
    [switch]$GatewayOnly,
    [switch]$ProductionFrontend,
    [switch]$Build,
    [switch]$AllowSlot,
    [switch]$LocalAuth
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $root 'deer-flow'
}
$mainDataRoot = [IO.Path]::GetFullPath((Join-Path $root 'local-tmp/main-service-data'))
if ($LocalAuth -and -not $PSBoundParameters.ContainsKey('DataDirectory')) {
    throw 'Local auth profile requires -DataDirectory for a fresh data root, separate from local-tmp/main-service-data.'
}
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'local-tmp/main-service-data'
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory)
if ($LocalAuth) {
    if ($dataRoot -eq $mainDataRoot) {
        throw 'Local auth profile must not use the main service data directory.'
    }
    $legacyRuntime = Test-Path -LiteralPath (Join-Path $dataRoot 'runtime/haifa-agent.sqlite')
    $legacyOwner = Test-Path -LiteralPath (Join-Path $dataRoot 'owners/default')
    $authUsers = Test-Path -LiteralPath (Join-Path $dataRoot 'auth/users.json')
    if (($legacyRuntime -or $legacyOwner) -and -not $authUsers) {
        throw 'Local auth profile refuses a data root that already contains an auth-disabled runtime or owners/default. Use a fresh directory, or one that already has auth/users.json.'
    }
}
$sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
$gatewayUrl = "http://127.0.0.1:$GatewayPort"
$frontendUrl = "http://127.0.0.1:$FrontendPort"

if ($LocalAuth -and -not $AllowSlot) {
    throw 'Local auth profile requires -AllowSlot, its own ports, and a fresh data directory.'
}
if ($LocalAuth -and ($GatewayPort -eq 18018 -or $FrontendPort -eq 33018)) {
    throw 'Local auth profile must not use the main ports 18018/33018.'
}
if (-not $AllowSlot -and ($GatewayPort -ne 18018 -or $FrontendPort -ne 33018)) {
    throw 'The main worktree must use its fixed Gateway 18018 / Frontend 33018 ports. Use -AllowSlot only for a slot rehearsal.'
}
if (-not $AllowSlot) {
    $expectedDataRoot = [IO.Path]::GetFullPath((Join-Path $root 'local-tmp/main-service-data'))
    if ($dataRoot -ne $expectedDataRoot) {
        throw "The main worktree data directory must be '$expectedDataRoot'. Use -AllowSlot only for a slot rehearsal."
    }
}
elseif (-not $dataRoot.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
    throw "-AllowSlot requires the data directory to stay inside '$root'."
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

$shell = Get-Command pwsh.exe -ErrorAction Stop
$null = Get-Command java.exe -ErrorAction Stop
$frontendRepo = $root
if (-not [string]::IsNullOrWhiteSpace($FrontendRoot)) {
    $frontendRepo = (Resolve-Path -LiteralPath $FrontendRoot).Path
}
$launcher = Join-Path $frontendRepo 'scripts/frontend/start-haifa-frontend.ps1'
$mainServiceModule = Join-Path $PSScriptRoot 'lib/main_service.py'
$jar = if ([string]::IsNullOrWhiteSpace($JarPath)) {
    Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'
}
else {
    (Resolve-Path -LiteralPath $JarPath).Path
}

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
if (-not (Test-Path -LiteralPath (Join-Path $frontendRepo 'frontend/node_modules/.bin/next.cmd'))) {
    throw 'Frozen frontend dependencies are missing. Follow the offline Corepack installation instructions in AGENTS.md.'
}

# Perform the frozen source, pinned toolchain, loopback and same-origin gates before starting either process.
$null = & $shell.Source -NoProfile -File $launcher -DryRun `
    -RepositoryRoot $frontendRepo -SourceRepository $sourceRoot -DataDirectory $dataRoot `
    -GatewayBaseUrl $gatewayUrl -FrontendPort $FrontendPort -Production:$ProductionFrontend `
    -LocalAuth:$LocalAuth
if ($LASTEXITCODE -ne 0) { throw 'Frozen frontend dry-run gate failed.' }

$logRoot = Join-Path $dataRoot 'logs'
$null = New-Item -ItemType Directory -Force -Path $logRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$frontendOut = Join-Path $logRoot "frontend-$stamp.out.log"
$frontendErr = Join-Path $logRoot "frontend-$stamp.err.log"

# The Gateway child environment (local profile, auth profile, persisted product keys) is
# built by scripts/lib/main_service.py:gateway_environment. Only what the frontend child
# inherits stays in this process, so this wrapper owns no credential rule of its own.
$env:DEER_FLOW_ENV = 'development'
$env:ENVIRONMENT = 'development'
$env:DEER_FLOW_TRUSTED_ORIGINS = $frontendUrl
$env:NO_PROXY = '127.0.0.1,localhost'
$env:no_proxy = '127.0.0.1,localhost'

# The Gateway reads its web Tool credentials and provider switches from its own process
# environment (WebToolConfig.fromEnvironment). scripts/lib/main_service.py owns the local
# profile, the fallback to the persisted Windows environment and the readiness contract, so
# direct starts and refreshes cannot drift, and the credentials are handed to the Java child
# only. The wrapper resolves paths, prepares the inherited environment and delegates.
$authCheck = if ($LocalAuth) { 'unauthorized' } else { 'default' }
$gatewayResult = & python -B $mainServiceModule --worktree $root --gateway-port $GatewayPort `
    --frontend-port $FrontendPort start --jar $jar --data-root $dataRoot --log-dir $logRoot `
    --stamp $stamp --ready-timeout-seconds $ReadyTimeoutSeconds --auth $authCheck
$gatewayExit = $LASTEXITCODE
if ($gatewayExit -ne 0) {
    throw "Gateway start failed with exit code ${gatewayExit}: $($gatewayResult -join ' ')"
}
$gateway = ($gatewayResult -join "`n") | ConvertFrom-Json
if (-not $gateway.ready) {
    throw "Gateway did not become ready. PID=$($gateway.pid); logs=$($gateway.logs -join ',')"
}

if ($GatewayOnly) {
    [ordered]@{
        gateway = $gatewayUrl
        dataRoot = $dataRoot
        gatewayPid = $gateway.pid
        gatewayLogs = @($gateway.logs)
        productEnv = $gateway.productEnvironment
        health = 'PASS'
        authMe = 'PASS'
        frontend = 'EXISTING_NOT_CHECKED'
    } | ConvertTo-Json -Depth 3
    return
}

$frontendArgs = @(
    '-NoProfile', '-File', "`"$launcher`"",
    '-RepositoryRoot', "`"$frontendRepo`"",
    '-SourceRepository', "`"$sourceRoot`"",
    '-DataDirectory', "`"$dataRoot`"",
    '-GatewayBaseUrl', $gatewayUrl,
    '-FrontendPort', [string]$FrontendPort
)
if ($ProductionFrontend) { $frontendArgs += '-Production' }
if ($LocalAuth) { $frontendArgs += '-LocalAuth' }
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
    throw "Frontend did not become ready. PID=$($frontendProcess.Id); logs=$frontendOut,$frontendErr; gateway PID=$($gateway.pid) remains running."
}

[ordered]@{
    gateway = $gatewayUrl
    frontend = $frontendUrl
    dataRoot = $dataRoot
    gatewayPid = $gateway.pid
    frontendLauncherPid = $frontendProcess.Id
    gatewayLogs = @($gateway.logs)
    frontendLogs = @($frontendOut, $frontendErr)
    productEnv = $gateway.productEnvironment
    health = 'PASS'
    authMe = 'PASS'
    frontendReadiness = 'PASS'
} | ConvertTo-Json -Depth 3
