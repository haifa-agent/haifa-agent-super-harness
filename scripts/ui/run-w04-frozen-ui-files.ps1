[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [int]$GatewayPort = 20618,
    [int]$FrontendPort = 25618,
    [int]$ProviderPort = 0,
    [string]$DataDirectory,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($GatewayPort -ne 20618 -or $FrontendPort -ne 25618 -or $ProviderPort -ne 0) {
    throw 'W04 slot 1 is fixed to Gateway=20618 and Frontend=25618; controlled Provider must use OS-assigned port 0.'
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'target/agent-slot-1-ui-data'
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\', '/')
$rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $dataRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'W04 data directory must remain inside this worktree.'
}

foreach ($port in @($GatewayPort, $FrontendPort)) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Refusing occupied W04 slot port $port (PID $($listener.OwningProcess -join ','))."
    }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() }
    catch { throw "W04 slot port $port cannot bind on loopback (check Windows excluded port ranges): $($_.Exception.Message)" }
    finally { $probe.Stop() }
}

$frontendRoot = Join-Path $root 'frontend'
$expectedPackageManager = [string](Get-Content -Raw -LiteralPath (Join-Path $frontendRoot 'package.json') | ConvertFrom-Json).packageManager
$previousNetwork = [Environment]::GetEnvironmentVariable('COREPACK_ENABLE_NETWORK', 'Process')
[Environment]::SetEnvironmentVariable('COREPACK_ENABLE_NETWORK', '0', 'Process')
Push-Location $frontendRoot
try {
    $actualPnpm = (& corepack.cmd pnpm --version).Trim()
}
finally {
    Pop-Location
}
if ("pnpm@$actualPnpm" -cne $expectedPackageManager) {
    throw "The frozen frontend requires $expectedPackageManager; Corepack resolved pnpm@$actualPnpm."
}

$null = New-Item -ItemType Directory -Force -Path $dataRoot
$evidenceRoot = Join-Path $root 'target/w04-frozen-ui-files-evidence'
$null = New-Item -ItemType Directory -Force -Path $evidenceRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$serverOut = Join-Path $evidenceRoot "server-$stamp.stdout.log"
$serverErr = Join-Path $evidenceRoot "server-$stamp.stderr.log"
$frontendOut = Join-Path $evidenceRoot "frontend-$stamp.stdout.log"
$frontendErr = Join-Path $evidenceRoot "frontend-$stamp.stderr.log"
$providerOut = Join-Path $evidenceRoot "provider-$stamp.stdout.log"
$providerErr = Join-Path $evidenceRoot "provider-$stamp.stderr.log"
$providerReady = Join-Path $evidenceRoot "provider-$stamp.ready.json"
$summaryPath = Join-Path $evidenceRoot "flow-$stamp.json"

$managedEnvironment = @{
    SUPER_HARNESS_GATEWAY_KIND = 'haifa'
    SUPER_HARNESS_PROFILE = 'local'
    SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
    SUPER_HARNESS_PORT = [string]$GatewayPort
    SUPER_HARNESS_DATA_ROOT = $dataRoot
    SUPER_HARNESS_DATA_DIR = $dataRoot
    SUPER_HARNESS_ENVIRONMENT = 'development'
    DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = "http://127.0.0.1:$GatewayPort"
    DEER_FLOW_AUTH_DISABLED = '1'
    DEER_FLOW_ENV = 'development'
    ENVIRONMENT = 'development'
    DEER_FLOW_TRUSTED_ORIGINS = "http://127.0.0.1:$FrontendPort"
    NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'
    HAIFA_FRONTEND_BIND_ADDRESS = '127.0.0.1'
    HAIFA_FRONTEND_PORT = [string]$FrontendPort
    E2E_GATEWAY_PORT = [string]$GatewayPort
    E2E_FRONTEND_PORT = [string]$FrontendPort
    E2E_APP_URL = "http://127.0.0.1:$FrontendPort"
    W04_DATA_ROOT = $dataRoot
    W04_EVIDENCE_DIR = $evidenceRoot
}
$savedEnvironment = @{}
foreach ($name in $managedEnvironment.Keys) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $managedEnvironment[$name], 'Process')
}

$gatewayProcess = $null
$frontendProcess = $null
$providerProcess = $null
$providerMetadata = $null
$testExitCode = 1
$gatewayStartupMs = $null
$frontendStartupMs = $null
$total = [Diagnostics.Stopwatch]::StartNew()
try {
    & (Join-Path $root 'scripts/frontend/verify-deerflow-frontend.ps1') `
        -RepositoryRoot $root `
        -SourceRepository $SourceRepository
    if ($LASTEXITCODE -ne 0) {
        throw 'Frozen DeerFlow frontend verification failed.'
    }

    . (Join-Path $PSScriptRoot '../lib/sdk-selection.ps1')
    . (Join-Path $PSScriptRoot '../lib/build-stamp.ps1')
    $sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root
    if ($SkipBuild) {
        Assert-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
    }
    else {
        Remove-BuildStamp (Get-ServerBuildStampPath $root)
        Push-Location $root
        try {
            & mvn.cmd -o -ntp -DskipTests @(@($sdk.MavenArgument) -ne $null) package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
        Write-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
    }

    $jar = Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'
    $next = Join-Path $frontendRoot 'node_modules/next/dist/bin/next'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'Executable Haifa server JAR is missing; run without -SkipBuild.'
    }
    if (-not (Test-Path -LiteralPath $next -PathType Leaf)) {
        throw 'Repo-local Next.js executable is missing; run Corepack pnpm install --offline --frozen-lockfile from frontend.'
    }

    $providerScript = Join-Path $root 'tests/ui/smoke/loopback-openai-provider.cjs'
    $providerProcess = Start-Process -FilePath (Get-Command node.exe).Source `
        -ArgumentList @($providerScript, [string]$ProviderPort, $providerReady) `
        -WorkingDirectory $root `
        -RedirectStandardOutput $providerOut `
        -RedirectStandardError $providerErr `
        -PassThru `
        -WindowStyle Hidden
    $providerDeadline = (Get-Date).AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 100
        if (Test-Path -LiteralPath $providerReady -PathType Leaf) {
            try {
                $providerMetadata = Get-Content -Raw -LiteralPath $providerReady | ConvertFrom-Json
            }
            catch {
                $providerMetadata = $null
            }
        }
    } until (($null -ne $providerMetadata -and $providerMetadata.type -eq 'ready') -or $providerProcess.HasExited -or (Get-Date) -ge $providerDeadline)
    if ($null -eq $providerMetadata -or $providerMetadata.type -ne 'ready') {
        throw "Controlled provider failed readiness (PID $($providerProcess.Id))."
    }
    $providerUrl = [string]$providerMetadata.address
    $providerUri = [Uri]$providerUrl
    if ($providerUri.Scheme -ne 'http' -or $providerUri.Host -ne '127.0.0.1' -or $providerUri.Port -lt 1) {
        throw "Controlled provider published unsafe ready address '$providerUrl'."
    }

    $gatewayTimer = [Diagnostics.Stopwatch]::StartNew()
    $gatewayProcess = Start-Process -FilePath (Get-Command java.exe).Source `
        -ArgumentList @('-jar', $jar) `
        -WorkingDirectory $root `
        -RedirectStandardOutput $serverOut `
        -RedirectStandardError $serverErr `
        -PassThru `
        -WindowStyle Hidden
    $gatewayDeadline = (Get-Date).AddSeconds(30)
    $health = $null
    do {
        Start-Sleep -Milliseconds 200
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/health" -TimeoutSec 1
        }
        catch {
            $health = $null
        }
    } until (($null -ne $health -and $health.status -eq 'ok') -or $gatewayProcess.HasExited -or (Get-Date) -ge $gatewayDeadline)
    if ($null -eq $health -or $health.status -ne 'ok') {
        throw "Haifa gateway failed readiness (PID $($gatewayProcess.Id))."
    }
    $gatewayTimer.Stop()
    $gatewayStartupMs = $gatewayTimer.ElapsedMilliseconds

    $managedModels = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/managed-models" -TimeoutSec 3
    $existingModel = @($managedModels.models) | Where-Object { $_.name -eq 'w04-controlled' } | Select-Object -First 1
    $modelEnvelope = [ordered]@{
        config = [ordered]@{
            name = 'w04-controlled'
            display_name = 'W04 Controlled Loopback'
            provider = 'openai-compatible'
            model = 'w18-controlled-model'
            base_url = "$providerUrl/v1"
            enabled = $true
            supports_vision = $false
            context_window = 8192
            max_tokens = 1024
            api_key = 'w04-loopback-only'
        }
        expected_revision = $(if ($null -eq $existingModel) { $null } else { $existingModel.revision })
    }
    $savedModel = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$GatewayPort/api/managed-models" `
        -Method Put `
        -ContentType 'application/json' `
        -Body ($modelEnvelope | ConvertTo-Json -Depth 6 -Compress) `
        -TimeoutSec 5
    $modelEnvelope.expected_revision = $savedModel.revision
    $probe = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$GatewayPort/api/managed-models/test" `
        -Method Post `
        -ContentType 'application/json' `
        -Body ($modelEnvelope | ConvertTo-Json -Depth 6 -Compress) `
        -TimeoutSec 10
    if ($probe.ok -ne $true) {
        throw "Controlled managed-model probe failed: $($probe.message)"
    }

    $frontendTimer = [Diagnostics.Stopwatch]::StartNew()
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
        -ArgumentList @($next, 'dev', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort) `
        -WorkingDirectory $frontendRoot `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr `
        -PassThru `
        -WindowStyle Hidden
    $frontendDeadline = (Get-Date).AddSeconds(120)
    $page = $null
    do {
        Start-Sleep -Milliseconds 250
        try {
            $page = Invoke-WebRequest "http://127.0.0.1:$FrontendPort/workspace/chats/new" `
                -UseBasicParsing `
                -TimeoutSec 3
        }
        catch {
            $page = $null
        }
    } until (($null -ne $page -and $page.StatusCode -eq 200) -or $frontendProcess.HasExited -or (Get-Date) -ge $frontendDeadline)
    if ($null -eq $page -or $page.StatusCode -ne 200) {
        throw "Frozen frontend failed readiness (PID $($frontendProcess.Id))."
    }
    $frontendTimer.Stop()
    $frontendStartupMs = $frontendTimer.ElapsedMilliseconds

    Push-Location $root
    try {
        & node.exe frontend/node_modules/@playwright/test/cli.js test `
            --config tests/ui/files/playwright.config.cjs `
            --repeat-each 2
        $testExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    $total.Stop()
    foreach ($process in @($frontendProcess, $gatewayProcess, $providerProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -InputObject $process -Force
            $null = $process.WaitForExit(10000)
        }
    }
    $released = @{}
    foreach ($port in @($GatewayPort, $FrontendPort)) {
        $released[[string]$port] = $null -eq (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    }
    [ordered]@{
        gateway = "http://127.0.0.1:$GatewayPort"
        frontend = "http://127.0.0.1:$FrontendPort"
        providerPort = $(if ($null -eq $providerMetadata) { $null } else { $providerMetadata.port })
        dataRoot = $dataRoot
        packageManager = "pnpm@$actualPnpm"
        gatewayStartupMs = $gatewayStartupMs
        frontendColdStartupAndCompileMs = $frontendStartupMs
        totalMs = $total.ElapsedMilliseconds
        repeatEach = 2
        retryCount = 0
        testExitCode = $testExitCode
        logs = [ordered]@{
            gatewayStdout = $serverOut
            gatewayStderr = $serverErr
            frontendStdout = $frontendOut
            frontendStderr = $frontendErr
            providerStdout = $providerOut
            providerStderr = $providerErr
        }
        releasedPorts = $released
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    [Environment]::SetEnvironmentVariable('COREPACK_ENABLE_NETWORK', $previousNetwork, 'Process')
}

if ($testExitCode -ne 0) {
    throw "W04 frozen UI smoke failed with exit code $testExitCode. See $summaryPath"
}

Get-Content -Raw -LiteralPath $summaryPath
