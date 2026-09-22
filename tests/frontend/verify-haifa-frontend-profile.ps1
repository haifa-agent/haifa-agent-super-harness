[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$verifier = Join-Path $root 'scripts/verify-haifa-frontend-profile.ps1'
$launcher = Join-Path $root 'scripts/start-haifa-frontend.ps1'
$dataRoot = Join-Path $root 'target/w18-haifa-config-test-data'
$null = New-Item -ItemType Directory -Force -Path $dataRoot

$managedNames = @(
    'SUPER_HARNESS_GATEWAY_KIND',
    'SUPER_HARNESS_PROFILE',
    'SUPER_HARNESS_BIND_ADDRESS',
    'SUPER_HARNESS_PORT',
    'SUPER_HARNESS_DATA_ROOT',
    'SUPER_HARNESS_ENVIRONMENT',
    'DEER_FLOW_INTERNAL_GATEWAY_BASE_URL',
    'DEER_FLOW_AUTH_DISABLED',
    'DEER_FLOW_ENV',
    'ENVIRONMENT',
    'DEER_FLOW_TRUSTED_ORIGINS',
    'NEXT_PUBLIC_STATIC_WEBSITE_ONLY',
    'HAIFA_FRONTEND_BIND_ADDRESS',
    'HAIFA_FRONTEND_PORT',
    'NEXT_PUBLIC_BACKEND_BASE_URL',
    'NEXT_PUBLIC_LANGGRAPH_BASE_URL',
    'NEXT_PUBLIC_API_URL',
    'LANGGRAPH_API_URL',
    'DEER_FLOW_REFERENCE_BACKEND_URL',
    'REFERENCE_BACKEND_URL',
    'DEER_FLOW_BACKEND_URL',
    'SKIP_ENV_VALIDATION',
    'NEXT_PUBLIC_API_TOKEN'
)

$baseline = @{
    SUPER_HARNESS_GATEWAY_KIND = 'haifa'
    SUPER_HARNESS_PROFILE = 'local'
    SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
    SUPER_HARNESS_PORT = '18018'
    SUPER_HARNESS_DATA_ROOT = $dataRoot
    SUPER_HARNESS_ENVIRONMENT = 'development'
    DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = 'http://127.0.0.1:18018'
    DEER_FLOW_AUTH_DISABLED = '1'
    DEER_FLOW_ENV = 'development'
    ENVIRONMENT = 'development'
    DEER_FLOW_TRUSTED_ORIGINS = 'http://127.0.0.1:33018'
    NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'
    HAIFA_FRONTEND_BIND_ADDRESS = '127.0.0.1'
    HAIFA_FRONTEND_PORT = '33018'
}

function Invoke-Case {
    param([hashtable]$Overrides = @{})

    $pwsh = (Get-Command pwsh -ErrorAction Stop).Source
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $pwsh
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($name in $managedNames) {
        $null = $start.Environment.Remove($name)
    }
    foreach ($entry in $baseline.GetEnumerator()) {
        $start.Environment[$entry.Key] = [string]$entry.Value
    }
    foreach ($entry in $Overrides.GetEnumerator()) {
        if ($null -eq $entry.Value) {
            $null = $start.Environment.Remove($entry.Key)
        }
        else {
            $start.Environment[$entry.Key] = [string]$entry.Value
        }
    }
    foreach ($argument in @('-NoProfile', '-File', $verifier, '-RepositoryRoot', $root)) {
        $start.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Output = ($stdout + [Environment]::NewLine + $stderr).Trim()
    }
}

function Assert-Pass {
    param([string]$Name, [hashtable]$Overrides = @{})

    $result = Invoke-Case -Overrides $Overrides
    if ($result.ExitCode -ne 0) {
        throw "$Name expected PASS but exited $($result.ExitCode): $($result.Output)"
    }
    Write-Host "PASS $Name"
    return $result
}

function Assert-Rejected {
    param([string]$Name, [hashtable]$Overrides, [string]$Pattern)

    $result = Invoke-Case -Overrides $Overrides
    if ($result.ExitCode -eq 0) {
        throw "$Name expected rejection but passed: $($result.Output)"
    }
    if ($result.Output -notmatch $Pattern) {
        throw "$Name rejected for the wrong reason: $($result.Output)"
    }
    Write-Host "PASS rejected $Name"
}

function Invoke-LauncherDryRun {
    $pwsh = (Get-Command pwsh -ErrorAction Stop).Source
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $pwsh
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($name in $managedNames) {
        $null = $start.Environment.Remove($name)
    }
    foreach ($argument in @(
        '-NoProfile',
        '-File', $launcher,
        '-RepositoryRoot', $root,
        '-DryRun'
    )) {
        $start.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Launcher dry-run exited $($process.ExitCode): $stdout$([Environment]::NewLine)$stderr"
    }
    return $stdout | ConvertFrom-Json
}

$valid = Assert-Pass -Name 'valid Haifa same-origin profile'
$profile = $valid.Output | ConvertFrom-Json
if ($profile.browserLangGraphBaseUrl -cne '/api/langgraph' -or
    $profile.deerFlowBackendFallback -ne $false -or
    [string]::IsNullOrWhiteSpace($profile.configurationSha256) -or
    $profile.runtimeDifferential -cne 'NOT_RUN') {
    throw 'Valid profile report lost its same-origin/fallback/NOT_RUN evidence.'
}

Assert-Rejected -Name 'missing auth-disabled flag' `
    -Overrides @{ DEER_FLOW_AUTH_DISABLED = $null } `
    -Pattern 'DEER_FLOW_AUTH_DISABLED is missing'
Assert-Rejected -Name 'non-loopback gateway' `
    -Overrides @{
        DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = 'http://192.0.2.10:18018'
    } `
    -Pattern 'literal loopback'
Assert-Rejected -Name 'public backend override' `
    -Overrides @{
        NEXT_PUBLIC_BACKEND_BASE_URL = 'http://127.0.0.1:8001'
    } `
    -Pattern 'must be unset'
Assert-Rejected -Name 'reference backend override' `
    -Overrides @{
        DEER_FLOW_REFERENCE_BACKEND_URL = 'http://127.0.0.1:8001'
    } `
    -Pattern 'must be unset'
Assert-Rejected -Name 'frozen reference gateway port' `
    -Overrides @{
        DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = 'http://127.0.0.1:8001'
        SUPER_HARNESS_PORT = '8001'
    } `
    -Pattern 'reserved by the frozen DeerFlow reference topology'
Assert-Rejected -Name 'production marker' `
    -Overrides @{ DEER_FLOW_ENV = 'production' } `
    -Pattern "must be exactly 'development'"
Assert-Rejected -Name 'secret in NEXT_PUBLIC' `
    -Overrides @{ NEXT_PUBLIC_API_TOKEN = 'must-not-leak' } `
    -Pattern 'Secret-like public'

$containedDataRoot = Join-Path $root 'frontend/.next/w18-profile-test-data'
$containedCleanupRoot = [IO.Path]::GetFullPath((Join-Path $root 'frontend/.next'))
$null = New-Item -ItemType Directory -Force -Path $containedDataRoot
try {
    Assert-Rejected -Name 'data root inside vendored frontend' `
        -Overrides @{ SUPER_HARNESS_DATA_ROOT = $containedDataRoot } `
        -Pattern 'must not be inside'
}
finally {
    $resolvedContainedDataRoot = [IO.Path]::GetFullPath($containedDataRoot)
    $cleanupPrefix = $containedCleanupRoot.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedContainedDataRoot.StartsWith(
            $cleanupPrefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Refusing to clean unexpected test data path: $resolvedContainedDataRoot"
    }
    Remove-Item -LiteralPath $resolvedContainedDataRoot -Recurse -Force
}

$dryRun = Invoke-LauncherDryRun
$expectedArguments = @('dev', '--', '--hostname', '127.0.0.1', '--port', '33018')
if ([string]$dryRun.launch.workingDirectory -cne (Join-Path $root 'frontend') -or
    [string]$dryRun.launch.packageManager -cne 'pnpm@10.26.2' -or
    (Compare-Object -SyncWindow 0 -ReferenceObject $expectedArguments `
        -DifferenceObject @($dryRun.launch.arguments))) {
    throw 'Launcher dry-run did not preserve the pinned pnpm command and 127.0.0.1:33018 arguments.'
}
Write-Host 'PASS launcher dry-run pins pnpm dev to 127.0.0.1:33018'

Write-Host 'All Haifa frontend profile tests passed.'
