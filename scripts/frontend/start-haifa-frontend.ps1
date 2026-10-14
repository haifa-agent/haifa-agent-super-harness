[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$GatewayBaseUrl = 'http://127.0.0.1:18018',
    [int]$FrontendPort = 33018,
    [string]$DataDirectory,
    [string]$SourceRepository,
    [switch]$Production,
    [switch]$DryRun,
    [switch]$LocalAuth
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'target/w18-haifa-config-data'
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory)

foreach ($name in @(
    'NEXT_PUBLIC_BACKEND_BASE_URL',
    'NEXT_PUBLIC_LANGGRAPH_BASE_URL',
    'NEXT_PUBLIC_API_URL',
    'LANGGRAPH_API_URL',
    'DEER_FLOW_REFERENCE_BACKEND_URL',
    'REFERENCE_BACKEND_URL',
    'DEER_FLOW_BACKEND_URL',
    'SKIP_ENV_VALIDATION'
)) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        throw "Refusing inherited backend/reference override: $name must be unset."
    }
}

foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
    $name = [string]$entry.Key
    if ($name -match '^NEXT_PUBLIC_.*(TOKEN|SECRET|PASSWORD|COOKIE|CREDENTIAL|API_KEY)' -and
        -not [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
        throw "Refusing secret-like public environment variable: $name"
    }
}

foreach ($name in @('DEER_FLOW_ENV', 'ENVIRONMENT', 'SUPER_HARNESS_ENVIRONMENT')) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if (($value ?? '').Trim().ToLowerInvariant() -in @('prod', 'production')) {
        throw "Refusing to replace explicit production marker: $name=$value"
    }
}

$requiredInheritedValues = @{
    SUPER_HARNESS_GATEWAY_KIND = 'haifa'
    SUPER_HARNESS_PROFILE = 'local'
    SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
    DEER_FLOW_AUTH_DISABLED = '1'
}
foreach ($entry in $requiredInheritedValues.GetEnumerator()) {
    $value = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($value) -and $value.Trim() -cne $entry.Value) {
        throw "Refusing conflicting inherited value: $($entry.Key)=$value"
    }
}
$inheritedGateway = [Environment]::GetEnvironmentVariable(
    'DEER_FLOW_INTERNAL_GATEWAY_BASE_URL',
    'Process'
)
if (-not [string]::IsNullOrWhiteSpace($inheritedGateway) -and
    $inheritedGateway.Trim().TrimEnd('/') -cne $GatewayBaseUrl.Trim().TrimEnd('/')) {
    throw 'Refusing conflicting inherited DEER_FLOW_INTERNAL_GATEWAY_BASE_URL.'
}

$gatewayUri = $null
if (-not [Uri]::TryCreate($GatewayBaseUrl, [UriKind]::Absolute, [ref]$gatewayUri)) {
    throw 'GatewayBaseUrl must be an absolute URL.'
}
if ($FrontendPort -lt 1 -or $FrontendPort -gt 65535) {
    throw 'FrontendPort must be between 1 and 65535.'
}

$null = New-Item -ItemType Directory -Force -Path $dataRoot

$env:SUPER_HARNESS_GATEWAY_KIND = 'haifa'
$env:SUPER_HARNESS_PROFILE = 'local'
$env:SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
$env:SUPER_HARNESS_PORT = [string]$gatewayUri.Port
$env:SUPER_HARNESS_DATA_ROOT = $dataRoot
$env:SUPER_HARNESS_ENVIRONMENT = 'development'
$env:DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = $GatewayBaseUrl.TrimEnd('/')
if ($LocalAuth) {
    Remove-Item Env:DEER_FLOW_AUTH_DISABLED -ErrorAction SilentlyContinue
    $env:HAIFA_SUPER_HARNESS_LOCAL_AUTH = '1'
    $env:HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS = "http://127.0.0.1:$FrontendPort"
} else {
    Remove-Item Env:HAIFA_SUPER_HARNESS_LOCAL_AUTH -ErrorAction SilentlyContinue
    Remove-Item Env:HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS -ErrorAction SilentlyContinue
    $env:DEER_FLOW_AUTH_DISABLED = '1'
}
$env:DEER_FLOW_ENV = 'development'
$env:ENVIRONMENT = 'development'
$env:DEER_FLOW_TRUSTED_ORIGINS = "http://127.0.0.1:$FrontendPort"
$env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'
$env:HAIFA_FRONTEND_BIND_ADDRESS = '127.0.0.1'
$env:HAIFA_FRONTEND_PORT = [string]$FrontendPort

$verifier = Join-Path $root 'scripts/frontend/verify-haifa-frontend-profile.ps1'
$verifyArguments = @{ RepositoryRoot = $root }
if (-not [string]::IsNullOrWhiteSpace($SourceRepository)) {
    $verifyArguments.SourceRepository = $SourceRepository
}
$verified = (& $verifier @verifyArguments | Out-String) | ConvertFrom-Json

$frontendRoot = Join-Path $root 'frontend'
$nodeCommand = Get-Command 'node.exe' -ErrorAction SilentlyContinue
if ($null -eq $nodeCommand) {
    $nodeCommand = Get-Command 'node' -ErrorAction SilentlyContinue
}
if ($null -eq $nodeCommand) {
    throw 'Node.js is required to start the frozen frontend.'
}
$pnpmCommand = Get-Command 'pnpm.cmd' -ErrorAction SilentlyContinue
if ($null -eq $pnpmCommand) {
    $pnpmCommand = Get-Command 'pnpm' -ErrorAction SilentlyContinue
}
if ($null -eq $pnpmCommand) {
    throw 'pnpm is required to start the frozen frontend.'
}

$manifest = Get-Content -LiteralPath (Join-Path $root 'third-party/deerflow/manifest.json') -Raw |
    ConvertFrom-Json
Push-Location $frontendRoot
try {
    $nodeVersion = (& $nodeCommand.Source --version).Trim()
    $pnpmVersion = (& $pnpmCommand.Source --version).Trim()
}
finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to resolve the pinned frontend package manager.'
}
if ($nodeVersion -notmatch '^v(?<major>\d+)\.' -or [int]$Matches.major -lt 22) {
    throw "The frozen frontend requires Node.js 22 or newer; got $nodeVersion."
}
$expectedPackageManager = [string]$manifest.build.packageManager
if ("pnpm@$pnpmVersion" -cne $expectedPackageManager) {
    throw "The frozen frontend requires $expectedPackageManager; got pnpm@$pnpmVersion."
}

$launch = [ordered]@{
    executable = $(if ($Production) { $nodeCommand.Source } else { $pnpmCommand.Source })
    nodeVersion = $nodeVersion
    packageManager = "pnpm@$pnpmVersion"
    workingDirectory = $frontendRoot
    arguments = $(if ($Production) {
        $next = Join-Path $frontendRoot 'node_modules/next/dist/bin/next'
        if (-not (Test-Path -LiteralPath $next -PathType Leaf)) {
            throw 'Repo-local Next.js executable is missing.'
        }
        . (Join-Path $root 'scripts/lib/build-stamp.ps1')
        Assert-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $GatewayBaseUrl.TrimEnd('/')
        @($next, 'start', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort)
    } else {
        @('dev', '--', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort)
    })
}

if ($DryRun) {
    [ordered]@{
        verifiedProfile = $verified
        launch = $launch
        dryRun = $true
    } | ConvertTo-Json -Depth 6
    return
}

try {
    $health = Invoke-RestMethod -Uri ($GatewayBaseUrl.TrimEnd('/') + '/health') -TimeoutSec 3
    if ($health.status -cne 'ok') {
        throw 'Haifa gateway health response did not contain status=ok.'
    }
    if ($LocalAuth) {
        try {
            Invoke-WebRequest -Uri ($GatewayBaseUrl.TrimEnd('/') + '/api/v1/auth/me') -TimeoutSec 3 | Out-Null
            throw 'Local auth profile must reject /api/v1/auth/me without a session.'
        } catch {
            $status = $_.Exception.Response.StatusCode.value__
            if ($status -ne 401) { throw }
        }
    } else {
        $me = Invoke-RestMethod -Uri ($GatewayBaseUrl.TrimEnd('/') + '/api/v1/auth/me') -TimeoutSec 3
        if ($me.id -cne 'default' -or $me.email -cne 'default@test.local' -or
            $me.system_role -cne 'admin' -or $me.needs_setup -ne $false) {
            throw 'Haifa gateway auth-disabled principal does not match the frozen frontend contract.'
        }
    }
}
catch {
    throw "Haifa gateway bootstrap preflight failed at $GatewayBaseUrl. Start the W02 gateway first. $($_.Exception.Message)"
}

Push-Location $frontendRoot
try {
    & $launch.executable @($launch.arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "Frozen frontend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
