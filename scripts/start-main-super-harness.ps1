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

$java = Get-Command java.exe -ErrorAction Stop
$shell = Get-Command pwsh.exe -ErrorAction Stop
$frontendRepo = $root
if (-not [string]::IsNullOrWhiteSpace($FrontendRoot)) {
    $frontendRepo = (Resolve-Path -LiteralPath $FrontendRoot).Path
}
$launcher = Join-Path $frontendRepo 'scripts/frontend/start-haifa-frontend.ps1'
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
if ($LocalAuth) {
    Remove-Item Env:DEER_FLOW_AUTH_DISABLED -ErrorAction SilentlyContinue
    $env:HAIFA_SUPER_HARNESS_LOCAL_AUTH = '1'
    $env:HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS = $frontendUrl
} else {
    Remove-Item Env:HAIFA_SUPER_HARNESS_LOCAL_AUTH -ErrorAction SilentlyContinue
    Remove-Item Env:HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS -ErrorAction SilentlyContinue
    $env:DEER_FLOW_AUTH_DISABLED = '1'
}
$env:DEER_FLOW_ENV = 'development'
$env:ENVIRONMENT = 'development'
$env:DEER_FLOW_TRUSTED_ORIGINS = $frontendUrl
$env:NO_PROXY = '127.0.0.1,localhost'
$env:no_proxy = '127.0.0.1,localhost'

# The Gateway reads its web Tool credentials and provider switches from its own process
# environment (WebToolConfig.fromEnvironment). A shell that started before the value was
# persisted silently strips it, and with HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER=deepseek
# but no DEEPSEEK_TOOL_API_KEY every web_search/web_fetch call fails in the SDK credential
# gate before the Tool runs. Fill only the missing product keys from the persisted Windows
# user/machine environment; scripts/lib/main_service.py owns the same rule for the refresh
# path. Record key names and scopes only, never values.
$productEnvSources = [ordered]@{}
if ($IsWindows) {
    foreach ($productKey in @('DEEPSEEK_TOOL_API_KEY', 'JINA_API_KEY',
            'HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER', 'HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER')) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($productKey, 'Process'))) {
            $productEnvSources[$productKey] = 'process'
            continue
        }
        foreach ($scope in @('User', 'Machine')) {
            $persisted = [Environment]::GetEnvironmentVariable($productKey, $scope)
            if (-not [string]::IsNullOrWhiteSpace($persisted)) {
                [Environment]::SetEnvironmentVariable($productKey, $persisted, 'Process')
                $productEnvSources[$productKey] = $scope.ToLowerInvariant()
                break
            }
        }
    }
}

$gatewayProcess = Start-Process -FilePath $java.Source -ArgumentList "-jar `"$jar`"" `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $gatewayOut -RedirectStandardError $gatewayErr

$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
$gatewayReady = $false
while ((Get-Date) -lt $deadline) {
    if ($gatewayProcess.HasExited) { break }
    try {
        $health = Invoke-RestMethod -Uri "$gatewayUrl/health" -TimeoutSec 3
        $authReady = $false
        if ($LocalAuth) {
            try {
                Invoke-WebRequest -Uri "$gatewayUrl/api/v1/auth/me" -TimeoutSec 3 | Out-Null
            } catch {
                $authReady = $_.Exception.Response.StatusCode.value__ -eq 401
            }
        } else {
            $me = Invoke-RestMethod -Uri "$gatewayUrl/api/v1/auth/me" -TimeoutSec 3
            $authReady = $me.id -ceq 'default' -and $me.email -ceq 'default@test.local' -and
                $me.system_role -ceq 'admin' -and $me.needs_setup -eq $false
        }
        if ($health.status -ceq 'ok' -and $authReady) {
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
        productEnv = $productEnvSources
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
    productEnv = $productEnvSources
    health = 'PASS'
    authMe = 'PASS'
    frontendReadiness = 'PASS'
} | ConvertTo-Json -Depth 3
