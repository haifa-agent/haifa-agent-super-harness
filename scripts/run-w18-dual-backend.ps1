[CmdletBinding()]
param(
    [ValidateSet('reference', 'haifa')]
    [string]$Backend,
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [string]$DataDirectory,
    [int]$GatewayPort = 40618,
    [int]$FrontendPort = 45618,
    [int]$ProviderPort = 46618,
    [ValidateRange(1, 2)]
    [int]$Round = 1,
    [switch]$SkipBuild,
    # Bounded diagnosis aid: run a subset of case titles by Playwright grep.
    # Never used to hide a failing case; a filtered run is not a round result.
    [string]$Grep,
    # Bounded diagnosis aid only: register model A without the V2-M1 model B to
    # A/B an existing-case timing question. Not a V2-M1 round configuration.
    [switch]$DiagnosticSingleModel
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib/w18-differential-results.ps1')

function Wait-Endpoint([string]$Url, [string]$ExpectedStatus, [System.Diagnostics.Process]$Process, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            $body = Invoke-RestMethod -Uri $Url -TimeoutSec 2
            if ($body.status -ceq $ExpectedStatus) { return }
        }
        catch { }
        if ($Process.HasExited) { throw "Process $($Process.Id) exited before $Url became ready." }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out after ${Seconds}s waiting for $Url (expected status=$ExpectedStatus)."
}

function Stop-OwnedTree([System.Diagnostics.Process]$Process) {
    if ($null -eq $Process -or $Process.HasExited) { return }
    $queue = [Collections.Generic.Queue[int]]::new()
    $descendants = @{}
    $queue.Enqueue($Process.Id)
    while ($queue.Count -gt 0) {
        $parentPid = $queue.Dequeue()
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentPid" -ErrorAction SilentlyContinue)) {
            if (-not $descendants.ContainsKey([int]$child.ProcessId)) {
                $descendants[[int]$child.ProcessId] = $child
                $queue.Enqueue([int]$child.ProcessId)
            }
        }
    }
    foreach ($child in @($descendants.Values | Sort-Object CreationDate -Descending)) {
        Stop-Process -Id ([int]$child.ProcessId) -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    $null = $Process.WaitForExit(10000)
}

function Set-W18ManagedModel([int]$Port, [string]$Name, [string]$Display, [string]$Model, [bool]$Enabled) {
    $models = Invoke-RestMethod "http://127.0.0.1:$Port/api/managed-models" -TimeoutSec 5
    $existing = @($models.models) | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    $body = @{
        config = @{
            name = $Name; display_name = $Display
            provider = 'openai-compatible'; model = $Model
            base_url = "http://127.0.0.1:$ProviderPort/v1"; enabled = $Enabled
            supports_vision = $false; context_window = 8192; max_tokens = 1024
            api_key = 'w18-loopback-only'
        }
        expected_revision = $(if ($existing) { $existing.revision } else { $null })
    } | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod "http://127.0.0.1:$Port/api/managed-models" -Method Put -ContentType 'application/json' -Body $body -TimeoutSec 5
}

if ([string]::IsNullOrWhiteSpace($Backend)) { throw '-Backend reference|haifa is required.' }
if ($GatewayPort -ne 40618 -or $FrontendPort -ne 45618 -or $ProviderPort -ne 46618) {
    throw 'W18 slot 3 requires Gateway=40618, Frontend=45618 and the main-controller-assigned test-only Provider=46618.'
}
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$source = (Resolve-Path -LiteralPath $SourceRepository).Path
if ((& git -C $source rev-parse HEAD).Trim() -cne 'e545c28ac301e6f9feae247c513d8ea8f83df294') {
    throw 'Frozen DeerFlow source SHA drifted.'
}
$currentBranch = (& git -C $root branch --show-current).Trim()
Assert-W18DifferentialBranch -Branch $currentBranch
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'local-tmp/slot-3-demo-l2-differential'
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\', '/')
if (-not $dataRoot.StartsWith($root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'DataDirectory must remain inside the W18 worktree.'
}
foreach ($port in @($GatewayPort, $FrontendPort, $ProviderPort)) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Slot-3 port $port already has listener PID $($listener.OwningProcess -join ',')." }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() }
    catch { throw "Slot-3 port $port cannot bind on loopback (check Windows excluded port ranges): $($_.Exception.Message)" }
    finally { $probe.Stop() }
}
$frontendRoot = Join-Path $root 'frontend'
if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules/.bin/next.cmd'))) {
    throw 'Missing repo-local Next.js; run Corepack pnpm 10.26.2 offline install in this worktree.'
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules/.bin/playwright.cmd'))) {
    throw 'Missing repo-local Playwright.'
}

$phaseRoot = Join-Path $dataRoot $Backend
$evidenceRoot = Join-Path $phaseRoot ("evidence-r$Round-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$null = New-Item -ItemType Directory -Force -Path $evidenceRoot
$providerReady = Join-Path $evidenceRoot 'provider.ready.json'
$node = (Get-Command node.exe).Source
$providerProcess = $null
$gatewayProcess = $null
$frontendProcess = $null
$testExitCode = 1
$started = Get-Date
$providerReadyAt = $null
$gatewayReadyAt = $null
$frontendReadyAt = $null
$testStartedAt = $null
$testFinishedAt = $null
# V2-M1 "disabled B" negative: a configured-but-disabled profile name. Haifa's
# managed catalog always supports it; the frozen reference uses the same
# managed-models API, so the runner attempts it there too and records the
# outcome (a profile that cannot express a disabled model leaves the case
# NOT_RUN on that side rather than silently claiming coverage).
$disabledModelName = 'w18-controlled-b-disabled'
$disabledModelConfigured = $false
try {
    $providerProcess = Start-Process -FilePath $node `
        -ArgumentList @((Join-Path $root 'tests/w18-ui-smoke/loopback-openai-provider.cjs'), [string]$ProviderPort, $providerReady) `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $evidenceRoot 'provider.stdout.log') `
        -RedirectStandardError (Join-Path $evidenceRoot 'provider.stderr.log')
    Wait-Endpoint "http://127.0.0.1:$ProviderPort/health" 'ok' $providerProcess 15
    $providerReadyAt = Get-Date

    $env:DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = "http://127.0.0.1:$GatewayPort"
    $env:DEER_FLOW_AUTH_DISABLED = '1'
    $env:DEER_FLOW_ENV = 'development'
    $env:ENVIRONMENT = 'development'
    $env:DEER_FLOW_TRUSTED_ORIGINS = "http://127.0.0.1:$FrontendPort"
    $env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'
    $env:COREPACK_ENABLE_NETWORK = '0'
    if ($Backend -eq 'reference') {
        $skillsRoot = Join-Path $phaseRoot 'skills'
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $skillsRoot 'public'), (Join-Path $skillsRoot 'custom'), (Join-Path $phaseRoot 'db')
        @{ mcpServers = @{}; skills = @{} } | ConvertTo-Json -Compress |
            Set-Content -LiteralPath (Join-Path $phaseRoot 'extensions_config.json') -Encoding utf8
        $env:DEER_FLOW_PROJECT_ROOT = $source
        $env:DEER_FLOW_CONFIG_PATH = Join-Path $root 'tests/w18-dual-backend/reference-config.yaml'
        $env:DEER_FLOW_EXTENSIONS_CONFIG_PATH = Join-Path $phaseRoot 'extensions_config.json'
        $env:DEER_FLOW_HOME = $phaseRoot
        $env:W18_PROVIDER_BASE_URL = "http://127.0.0.1:$ProviderPort/v1"
        $env:W18_REFERENCE_SKILLS_ROOT = $skillsRoot
        $env:W18_REFERENCE_DB_ROOT = Join-Path $phaseRoot 'db'
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $env:PYTHONPATH = @(
            (Join-Path $source 'backend/packages/extension-api'),
            (Join-Path $source 'backend/packages/harness')
        ) -join [IO.Path]::PathSeparator
        $python = Join-Path $source 'backend/.venv/Scripts/python.exe'
        if (-not (Test-Path -LiteralPath $python)) { throw 'Frozen DeerFlow backend local Python 3.12 venv is absent.' }
        $gatewayProcess = Start-Process -FilePath $python `
            -ArgumentList @('-m', 'uvicorn', 'app.gateway.app:app', '--host', '127.0.0.1', '--port', [string]$GatewayPort) `
            -WorkingDirectory (Join-Path $source 'backend') -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $evidenceRoot 'gateway.stdout.log') `
            -RedirectStandardError (Join-Path $evidenceRoot 'gateway.stderr.log')
        Wait-Endpoint "http://127.0.0.1:$GatewayPort/health" 'healthy' $gatewayProcess 60
        # Best-effort "disabled B": the frozen reference exposes the same
        # managed-models API. If the managed store is unavailable in this local
        # profile the case is left NOT_RUN on the reference side, recorded in
        # topology, instead of pretending a disabled model was exercised.
        try {
            $disabledSaved = Set-W18ManagedModel -Port $GatewayPort -Name $disabledModelName `
                -Display 'W18 Controlled Loopback B (disabled)' -Model 'w18-controlled-model-b' -Enabled $false
            $disabledModelConfigured = -not $disabledSaved.enabled
        }
        catch {
            Write-Warning "Reference disabled-model profile could not be configured: $($_.Exception.Message)"
        }
    }
    else {
        $env:SUPER_HARNESS_GATEWAY_KIND = 'haifa'
        $env:SUPER_HARNESS_PROFILE = 'local'
        $env:SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
        $env:SUPER_HARNESS_PORT = [string]$GatewayPort
        $env:SUPER_HARNESS_DATA_ROOT = $phaseRoot
        $env:SUPER_HARNESS_DATA_DIR = $phaseRoot
        $env:SUPER_HARNESS_ENVIRONMENT = 'development'
        $env:HAIFA_FRONTEND_BIND_ADDRESS = '127.0.0.1'
        $env:HAIFA_FRONTEND_PORT = [string]$FrontendPort
        & (Join-Path $root 'scripts/verify-haifa-frontend-profile.ps1') -RepositoryRoot $root -SourceRepository $source |
            Set-Content -LiteralPath (Join-Path $evidenceRoot 'frontend-profile.json') -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw 'Frozen Haifa frontend profile gate failed.' }
        $jar = Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Haifa executable JAR is missing; build this worktree after SDK snapshot publication.' }
        $gatewayProcess = Start-Process -FilePath (Get-Command java.exe).Source `
            -ArgumentList @('-jar', $jar) -WorkingDirectory $root -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $evidenceRoot 'gateway.stdout.log') `
            -RedirectStandardError (Join-Path $evidenceRoot 'gateway.stderr.log')
        Wait-Endpoint "http://127.0.0.1:$GatewayPort/health" 'ok' $gatewayProcess 45
        # V2-M1 needs two enabled models with distinct provider ids so the same
        # Thread can switch A -> B on the next Turn and the provider request log
        # proves which model actually served each Run.
        $controlledProfiles = @(
            @{ name = 'w18-controlled'; display = 'W18 Controlled Loopback'; model = 'w18-controlled-model' },
            @{ name = 'w18-controlled-b'; display = 'W18 Controlled Loopback B'; model = 'w18-controlled-model-b' }
        )
        if ($DiagnosticSingleModel) { $controlledProfiles = @($controlledProfiles[0]) }
        foreach ($controlled in $controlledProfiles) {
            $saved = Set-W18ManagedModel -Port $GatewayPort -Name $controlled.name `
                -Display $controlled.display -Model $controlled.model -Enabled $true
            if (-not $saved.enabled -or -not $saved.has_api_key) { throw "Controlled model profile $($controlled.name) did not save correctly." }
        }
        if (-not $DiagnosticSingleModel) {
            $disabledSaved = Set-W18ManagedModel -Port $GatewayPort -Name $disabledModelName `
                -Display 'W18 Controlled Loopback B (disabled)' -Model 'w18-controlled-model-b' -Enabled $false
            if ($disabledSaved.enabled) { throw "Disabled model profile $disabledModelName saved as enabled." }
            $disabledModelConfigured = $true
        }
        $visible = @((Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/models" -TimeoutSec 5).models.name)
        foreach ($expectedName in @($controlledProfiles | ForEach-Object { $_.name })) {
            if ($visible -cnotcontains $expectedName) { throw "Controlled model $expectedName is not visible from /api/models." }
        }
        if ($visible -ccontains $disabledModelName) { throw "Disabled model $disabledModelName must not be visible from /api/models." }
    }
    $gatewayReadyAt = Get-Date

    if (-not $SkipBuild -or -not (Test-Path -LiteralPath (Join-Path $frontendRoot '.next/BUILD_ID'))) {
        Push-Location $frontendRoot
        try {
            & corepack pnpm build
            if ($LASTEXITCODE -ne 0) { throw "Frozen frontend build failed: $LASTEXITCODE" }
        }
        finally { Pop-Location }
    }
    # SSR/BFF guard: every built rewrite must target only this slot's gateway,
    # so neither combination can silently fall back to another backend.
    $gatewayOrigin = "http://127.0.0.1:$GatewayPort"
    $routes = Get-Content -Raw -LiteralPath (Join-Path $frontendRoot '.next/routes-manifest.json') | ConvertFrom-Json
    $rewriteList = if ($routes.rewrites -is [array]) { @($routes.rewrites) } else {
        @($routes.rewrites.beforeFiles) + @($routes.rewrites.afterFiles) + @($routes.rewrites.fallback)
    }
    $bffTargets = @($rewriteList | Where-Object { $_ -and $_.destination -match '^https?://' } |
        ForEach-Object { ([Uri]$_.destination).GetLeftPart([UriPartial]::Authority) } | Sort-Object -Unique)
    if ($bffTargets.Count -eq 0 -or @($bffTargets | Where-Object { $_ -cne $gatewayOrigin }).Count -gt 0) {
        throw "Frozen frontend BFF rewrites target [$($bffTargets -join ', ')], expected only $gatewayOrigin; rebuild without -SkipBuild."
    }
    $frontendProcess = Start-Process -FilePath $node `
        -ArgumentList @((Join-Path $frontendRoot 'node_modules/next/dist/bin/next'), 'start', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort) `
        -WorkingDirectory $frontendRoot -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $evidenceRoot 'frontend.stdout.log') `
        -RedirectStandardError (Join-Path $evidenceRoot 'frontend.stderr.log')
    $deadline = (Get-Date).AddSeconds(60)
    $ready = $false
    do {
        try {
            $page = Invoke-WebRequest "http://127.0.0.1:$FrontendPort/workspace/chats/new" -UseBasicParsing -TimeoutSec 2
            if ($page.StatusCode -eq 200) { $ready = $true; break }
        }
        catch { }
        if ($frontendProcess.HasExited) { throw "Frozen frontend exited before readiness: PID $($frontendProcess.Id)." }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if (-not $ready) { throw 'Frozen frontend readiness timed out after 60s.' }
    $frontendReadyAt = Get-Date

    [ordered]@{
        backend = $Backend
        round = $Round
        sourceSha = (& git -C $source rev-parse HEAD).Trim()
        harnessSha = (& git -C $root rev-parse HEAD).Trim()
        gatewayUrl = "http://127.0.0.1:$GatewayPort"
        gatewayPid = $gatewayProcess.Id
        frontendUrl = "http://127.0.0.1:$FrontendPort"
        frontendPid = $frontendProcess.Id
        providerUrl = "http://127.0.0.1:$ProviderPort"
        providerPid = $providerProcess.Id
        dataRoot = $phaseRoot
        bffRewriteTargets = $bffTargets
        frontendBuildId = (Get-Content -Raw -LiteralPath (Join-Path $frontendRoot '.next/BUILD_ID')).Trim()
        frozenManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'third-party/deerflow/manifest.json')).Hash.ToLowerInvariant()
        frontendRoutesSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $frontendRoot '.next/routes-manifest.json')).Hash.ToLowerInvariant()
        referenceConfigSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'tests/w18-dual-backend/reference-config.yaml')).Hash.ToLowerInvariant()
        runnerSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
        specSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'tests/w18-dual-backend/differential.spec.cjs')).Hash.ToLowerInvariant()
        v2m1SpecSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'tests/w18-dual-backend/v2-m1-model-file.spec.cjs')).Hash.ToLowerInvariant()
        providerSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root 'tests/w18-ui-smoke/loopback-openai-provider.cjs')).Hash.ToLowerInvariant()
        disabledModelName = $disabledModelName
        disabledModelConfigured = $disabledModelConfigured
        startedAt = $started.ToString('o')
        readinessSeconds = [ordered]@{
            provider = [math]::Round(($providerReadyAt - $started).TotalSeconds, 1)
            gateway = [math]::Round(($gatewayReadyAt - $started).TotalSeconds, 1)
            frontend = [math]::Round(($frontendReadyAt - $started).TotalSeconds, 1)
        }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'topology.json') -Encoding utf8

    $env:E2E_APP_URL = "http://127.0.0.1:$FrontendPort"
    $env:W18_DIFFERENTIAL_BACKEND = $Backend
    $env:W18_DIFFERENTIAL_EVIDENCE = $evidenceRoot
    $env:W18_DIFFERENTIAL_ROUND = [string]$Round
    $env:W18_DISABLED_MODEL = $disabledModelName
    $env:W18_DISABLED_MODEL_CONFIGURED = $(if ($disabledModelConfigured) { '1' } else { '0' })
    $env:W18_DIFFERENTIAL_GATEWAY = "http://127.0.0.1:$GatewayPort"
    $testStartedAt = Get-Date
    Push-Location $root
    try {
        $playwrightArgs = @(
            'frontend/node_modules/@playwright/test/cli.js',
            'test',
            '--config', 'tests/w18-dual-backend/playwright.config.cjs'
        )
        if (-not [string]::IsNullOrWhiteSpace($Grep)) { $playwrightArgs += @('--grep', $Grep) }
        & $node @playwrightArgs
        $testExitCode = $LASTEXITCODE
    }
    finally { Pop-Location }
    $testFinishedAt = Get-Date
}
finally {
    Stop-OwnedTree $frontendProcess
    Stop-OwnedTree $gatewayProcess
    Stop-OwnedTree $providerProcess
    $remaining = @(Get-NetTCPConnection -LocalPort $GatewayPort, $FrontendPort, $ProviderPort -State Listen -ErrorAction SilentlyContinue)
    $logHashes = @{}
    foreach ($name in @('provider.stdout.log', 'provider.stderr.log', 'gateway.stdout.log', 'gateway.stderr.log', 'frontend.stdout.log', 'frontend.stderr.log')) {
        $file = Join-Path $evidenceRoot $name
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            $logHashes[$name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
        }
    }
    # Proxy failures the Next server logged, by target origin (fallback evidence).
    $proxyTargets = @()
    foreach ($name in @('frontend.stdout.log', 'frontend.stderr.log')) {
        $file = Join-Path $evidenceRoot $name
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            $proxyTargets += @(Select-String -LiteralPath $file -Pattern 'Failed to proxy (https?://[^/\s]+)' -AllMatches |
                ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value })
        }
    }
    $cases = @()
    $casesError = $null
    $resultsFile = Join-Path $evidenceRoot 'results.json'
    # Playwright JSON omits empty arrays (leaf suites have no "suites"), which
    # StrictMode rejects as missing properties; read them defensively.
    $prop = { param($o, $n) if ($o -and $o.PSObject.Properties[$n]) { @($o.$n) } else { @() } }
    if (Test-Path -LiteralPath $resultsFile -PathType Leaf) {
      try {
        $walk = {
            param($suite)
            foreach ($spec in (& $prop $suite 'specs')) {
                foreach ($t in (& $prop $spec 'tests')) {
                    $last = @(& $prop $t 'results')[-1]
                    $classification = Get-W18DifferentialClassification `
                        -PlaywrightStatus ([string]$last.status) `
                        -Annotations @(& $prop $t 'annotations')
                    [ordered]@{
                        title = $spec.title
                        oracleCaseId = Get-W18OracleCaseId -Title $spec.title
                        status = $classification.status
                        seconds = [math]::Round($last.duration / 1000, 1)
                        retry = $last.retry
                        classificationReason = $classification.reason
                    }
                }
            }
            foreach ($child in (& $prop $suite 'suites')) { & $walk $child }
        }
        $report = Get-Content -Raw -LiteralPath $resultsFile | ConvertFrom-Json
        $cases = @((& $prop $report 'suites') | ForEach-Object { & $walk $_ })
      }
      catch { $casesError = $_.Exception.Message }
    }
    $summary = [ordered]@{
        backend = $Backend; testExitCode = $testExitCode
        cases = $cases
        # Per Oracle case id: the case status for this backend/round. Legacy W18
        # differential cases without an Oracle id are omitted here (still in
        # `cases`); this never promotes another backend's result.
        oracleCases = @($cases | Where-Object { $_['oracleCaseId'] } | ForEach-Object {
                [ordered]@{
                    id = $_['oracleCaseId']
                    status = $_['status']
                    seconds = $_['seconds']
                    retry = $_['retry']
                    classificationReason = $_['classificationReason']
                }
            })
        casesError = $casesError
        frontendProxyFailureTargets = @($proxyTargets | Sort-Object -Unique)
        elapsedSeconds = [int]((Get-Date) - $started).TotalSeconds
        testSeconds = $(if ($testStartedAt -and $testFinishedAt) { [math]::Round(($testFinishedAt - $testStartedAt).TotalSeconds, 1) } else { $null })
        readinessSeconds = [ordered]@{
            provider = $(if ($providerReadyAt) { [math]::Round(($providerReadyAt - $started).TotalSeconds, 1) } else { $null })
            gateway = $(if ($gatewayReadyAt) { [math]::Round(($gatewayReadyAt - $started).TotalSeconds, 1) } else { $null })
            frontend = $(if ($frontendReadyAt) { [math]::Round(($frontendReadyAt - $started).TotalSeconds, 1) } else { $null })
        }
        evidenceRoot = $evidenceRoot
        logSha256 = $logHashes
        remainingListeners = @($remaining | ForEach-Object { "port=$($_.LocalPort) pid=$($_.OwningProcess)" })
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'summary.json') -Encoding utf8
    $summary | ConvertTo-Json -Depth 4
}
if ($testExitCode -ne 0) { throw "W18 $Backend differential smoke failed (exit $testExitCode)." }
