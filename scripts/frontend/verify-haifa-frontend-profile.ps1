[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-RequiredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required environment variable $Name is missing."
    }
    return $value.Trim()
}

function Assert-ExactEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    $actual = Get-RequiredEnvironmentValue -Name $Name
    if ($actual -cne $Expected) {
        throw "$Name must be exactly '$Expected'; got '$actual'."
    }
}

function Assert-EnvironmentUnset {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        throw "$Name must be unset in the same-origin Haifa profile."
    }
}

function Assert-LoopbackHttpBaseUrl {
    param([Parameter(Mandatory = $true)][string]$Value)

    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri)) {
        throw "DEER_FLOW_INTERNAL_GATEWAY_BASE_URL is not an absolute URL."
    }
    if ($uri.Scheme -cne 'http') {
        throw 'The local Haifa gateway must use http on loopback.'
    }
    if ($uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
        throw 'The Haifa gateway base URL must not contain credentials, path, query, or fragment.'
    }

    $address = $null
    if (-not [Net.IPAddress]::TryParse($uri.Host, [ref]$address) -or
        -not [Net.IPAddress]::IsLoopback($address)) {
        throw 'The Haifa gateway URL must use a literal loopback IP address.'
    }
    if ($uri.Port -lt 1 -or $uri.Port -gt 65535) {
        throw 'The Haifa gateway URL must contain a valid port.'
    }
    return $uri
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$frontendRoot = (Resolve-Path -LiteralPath (Join-Path $root 'frontend')).Path

Assert-ExactEnvironmentValue -Name 'SUPER_HARNESS_GATEWAY_KIND' -Expected 'haifa'
Assert-ExactEnvironmentValue -Name 'SUPER_HARNESS_PROFILE' -Expected 'local'
Assert-ExactEnvironmentValue -Name 'SUPER_HARNESS_BIND_ADDRESS' -Expected '127.0.0.1'
Assert-ExactEnvironmentValue -Name 'DEER_FLOW_AUTH_DISABLED' -Expected '1'
Assert-ExactEnvironmentValue -Name 'DEER_FLOW_ENV' -Expected 'development'
Assert-ExactEnvironmentValue -Name 'ENVIRONMENT' -Expected 'development'
Assert-ExactEnvironmentValue -Name 'SUPER_HARNESS_ENVIRONMENT' -Expected 'development'
Assert-ExactEnvironmentValue -Name 'HAIFA_FRONTEND_BIND_ADDRESS' -Expected '127.0.0.1'
Assert-ExactEnvironmentValue -Name 'NEXT_PUBLIC_STATIC_WEBSITE_ONLY' -Expected 'false'

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
    Assert-EnvironmentUnset -Name $name
}

foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
    $name = [string]$entry.Key
    if ($name -match '^NEXT_PUBLIC_.*(TOKEN|SECRET|PASSWORD|COOKIE|CREDENTIAL|API_KEY)') {
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
            throw "Secret-like public environment variable is forbidden: $name"
        }
    }
}

$gatewayValue = Get-RequiredEnvironmentValue -Name 'DEER_FLOW_INTERNAL_GATEWAY_BASE_URL'
$gatewayUri = Assert-LoopbackHttpBaseUrl -Value $gatewayValue
$gatewayPortText = Get-RequiredEnvironmentValue -Name 'SUPER_HARNESS_PORT'
$gatewayPort = 0
if (-not [int]::TryParse($gatewayPortText, [ref]$gatewayPort) -or $gatewayPort -ne $gatewayUri.Port) {
    throw 'SUPER_HARNESS_PORT must equal the port in DEER_FLOW_INTERNAL_GATEWAY_BASE_URL.'
}
$reservedReferencePorts = @(2026, 3000, 8001, 8002)
if ($reservedReferencePorts -contains $gatewayPort) {
    throw "Gateway port $gatewayPort is reserved by the frozen DeerFlow reference topology."
}

$frontendPortText = Get-RequiredEnvironmentValue -Name 'HAIFA_FRONTEND_PORT'
$frontendPort = 0
if (-not [int]::TryParse($frontendPortText, [ref]$frontendPort) -or
    $frontendPort -lt 1 -or $frontendPort -gt 65535) {
    throw 'HAIFA_FRONTEND_PORT must be a valid TCP port.'
}
if ($frontendPort -eq $gatewayPort) {
    throw 'Frontend and gateway ports must be distinct.'
}
if ($reservedReferencePorts -contains $frontendPort) {
    throw "Frontend port $frontendPort is reserved by the frozen DeerFlow reference topology."
}
$expectedFrontendOrigin = "http://127.0.0.1:$frontendPort"
Assert-ExactEnvironmentValue -Name 'DEER_FLOW_TRUSTED_ORIGINS' -Expected $expectedFrontendOrigin

$dataRootValue = Get-RequiredEnvironmentValue -Name 'SUPER_HARNESS_DATA_ROOT'
if (-not [IO.Path]::IsPathFullyQualified($dataRootValue)) {
    throw 'SUPER_HARNESS_DATA_ROOT must be an absolute path.'
}
$dataRoot = [IO.Path]::GetFullPath($dataRootValue).TrimEnd('\', '/')
if (-not (Test-Path -LiteralPath $dataRoot -PathType Container)) {
    throw 'SUPER_HARNESS_DATA_ROOT must already exist as a directory.'
}
if ($dataRoot.StartsWith($frontendRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -or
    $dataRoot -eq $frontendRoot.TrimEnd('\', '/')) {
    throw 'SUPER_HARNESS_DATA_ROOT must not be inside the frozen frontend tree.'
}

$sourceVerifier = Join-Path $root 'scripts/frontend/verify-deerflow-frontend.ps1'
$sourceArguments = @{ RepositoryRoot = $root }
if (-not [string]::IsNullOrWhiteSpace($SourceRepository)) {
    $sourceArguments.SourceRepository = $SourceRepository
}
. (Join-Path $root 'scripts/lib/frontend-gate-cache.ps1')
$sourceGateCacheKey = Get-FrontendSourceGateCacheKey -RepositoryRoot $root -SourceRepository $SourceRepository
$sourceGateOutput = Read-FrontendSourceGateCache -RepositoryRoot $root -Key $sourceGateCacheKey
$sourceGateCache = if ($null -eq $sourceGateCacheKey) { 'OFF' } elseif ($sourceGateOutput) { 'HIT' } else { 'MISS' }
if (-not $sourceGateOutput) {
    $sourceGateOutput = (& $sourceVerifier @sourceArguments *>&1 | Out-String)
    if ([string]::IsNullOrWhiteSpace($sourceGateOutput)) {
        throw 'Frozen frontend source gate produced no verification evidence.'
    }
    Write-FrontendSourceGateCache -RepositoryRoot $root -Key $sourceGateCacheKey -Output $sourceGateOutput
}

$configurationText = @(
    'profile=haifa-local-same-origin',
    "gateway=$($gatewayUri.AbsoluteUri.TrimEnd('/'))",
    'browserBackend=(same-origin)',
    'browserLangGraph=/api/langgraph',
    'authDisabled=1',
    'frontendBind=127.0.0.1',
    "frontendPort=$frontendPort",
    "trustedOrigin=$expectedFrontendOrigin",
    "dataRoot=$dataRoot"
) -join "`n"
$configurationHash = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData(
        [Text.UTF8Encoding]::new($false).GetBytes($configurationText)
    )
).ToLowerInvariant()

[ordered]@{
    profile = 'haifa-local-same-origin'
    gatewayKind = 'haifa'
    internalGatewayBaseUrl = $gatewayUri.AbsoluteUri.TrimEnd('/')
    browserBackendBaseUrl = '(same-origin)'
    browserLangGraphBaseUrl = '/api/langgraph'
    authDisabled = $true
    frontendBindAddress = '127.0.0.1'
    frontendPort = $frontendPort
    trustedOrigin = $expectedFrontendOrigin
    dataRoot = $dataRoot
    frontendSourceGate = 'PASS'
    frontendSourceGateCache = $sourceGateCache
    deerFlowBackendFallback = $false
    publicSecrets = $false
    configurationSha256 = $configurationHash
    runtimeDifferential = 'NOT_RUN'
} | ConvertTo-Json
