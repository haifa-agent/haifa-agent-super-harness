[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [ValidateRange(1, 3)]
    [int]$AgentSlot = 2,
    [int]$GatewayPort = 30618,
    [int]$FrontendPort = 35618,
    [int]$ProviderPort = 0,
    [string]$DataDirectory,
    [switch]$ActiveReconnect,
    [switch]$Interaction,
    [switch]$DemoArtifact,
    [switch]$TitleSuggestions,
    [switch]$InputPolish,
    [switch]$V2ModelSwitch,
    [switch]$V2FileContext,
    [switch]$V2Skill,
    [switch]$V2Diagnostics,
    [switch]$LiveDeepSeek,
    # UX-V1 joint acceptance: the live DeepSeek model with the DeerFlow default tool chains.
    [switch]$UxV1DefaultTools,
    # UX-V1 M3: the frozen new-agent conversation/save flow with the live managed model.
    [switch]$UxV1AgentSoul,
    # Human-authorized DDG deferral; leaves the required Web case visibly NOT_RUN.
    [switch]$DeferUxV1Web,
    # Reuse the same-source reactor JAR after the controller's full gate; still build Next cold.
    [switch]$SkipServerBuild,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($DeferUxV1Web -and -not $UxV1DefaultTools) {
    throw 'DeferUxV1Web requires the UX-V1 default-tools profile.'
}
if ($SkipServerBuild -and -not ($UxV1DefaultTools -or $UxV1AgentSoul)) {
    throw 'SkipServerBuild requires a UX-V1 profile and a verified same-source JAR.'
}

$expectedGatewayPort = 10618 + (10000 * $AgentSlot)
$expectedFrontendPort = 15618 + (10000 * $AgentSlot)
if ($GatewayPort -ne $expectedGatewayPort -or $FrontendPort -ne $expectedFrontendPort -or $ProviderPort -ne 0) {
    throw "W18 slot $AgentSlot requires Gateway=$expectedGatewayPort and Frontend=$expectedFrontendPort; controlled Provider must use OS-assigned port 0."
}
if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $LiveDeepSeek).Where({ $_ }).Count -gt 1) {
    throw 'Active reconnect, Interaction, demo-artifact, title/suggestions, input-polish, V2 model-switch, V2 file-context, V2 Skill, V2 diagnostics, and live DeepSeek profiles are mutually exclusive.'
}
if ($UxV1DefaultTools -and $UxV1AgentSoul) {
    throw 'The UX-V1 default-tools and Agent SOUL profiles are mutually exclusive.'
}
if ($UxV1DefaultTools -or $UxV1AgentSoul) {
    if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $LiveDeepSeek).Where({ $_ }).Count -gt 0) {
        throw 'The UX-V1 profiles are mutually exclusive with the other profiles.'
    }
    # Reuses the live DeepSeek managed model and skips the controlled loopback provider.
    $LiveDeepSeek = $true
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
. (Join-Path $PSScriptRoot 'lib/sdk-selection.ps1')
. (Join-Path $PSScriptRoot 'lib/build-stamp.ps1')
$sdk = [pscustomobject]@{ Status = 'NOT_RUN'; HaifaVersion = $null }
$internalGatewayBaseUrl = "http://127.0.0.1:$GatewayPort"
 . (Join-Path $PSScriptRoot 'lib/run-fixture.ps1')
$tag = if ($UxV1AgentSoul) { 'ux-v1-soul' } elseif ($UxV1DefaultTools) { 'ux-v1' }
    elseif ($LiveDeepSeek) { 'w18-deepseek' } elseif ($ActiveReconnect) { 'w05-active-reconnect' }
    elseif ($Interaction) { 'w05-interaction' } elseif ($DemoArtifact) { 'w05-demo-artifact' }
    elseif ($TitleSuggestions) { 'w03-title-suggestions' } elseif ($InputPolish) { 'w03-input-polish-ui' }
    elseif ($V2ModelSwitch) { 'w03-v2-model-switch' } elseif ($V2FileContext) { 'w04-v2-file-context' }
    elseif ($V2Skill) { 'w15-v2-skill' } elseif ($V2Diagnostics) { 'w21-diagnostics' } else { 'w18-ui-smoke' }
$fixture = New-RunFixture -RepositoryRoot $root -Slot $AgentSlot -TaskTag $tag `
    -BuildMode $(if ($SkipBuild) { 'warm-reuse' } else { 'cold-build' }) -Ports @($GatewayPort, $FrontendPort)
$evidenceRoot = $fixture.Evidence
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root "local-tmp/slot-$AgentSlot-$tag/data/$(Split-Path -Leaf $evidenceRoot)"
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\', '/')
$rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $dataRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'W18 data directory must remain inside this worktree.'
}
$serverOut = Join-Path $evidenceRoot "server-$stamp.stdout.log"
$serverErr = Join-Path $evidenceRoot "server-$stamp.stderr.log"
$frontendOut = Join-Path $evidenceRoot "frontend-$stamp.stdout.log"
$frontendErr = Join-Path $evidenceRoot "frontend-$stamp.stderr.log"
$providerOut = Join-Path $evidenceRoot "provider-$stamp.stdout.log"
$providerErr = Join-Path $evidenceRoot "provider-$stamp.stderr.log"
$providerReady = Join-Path $evidenceRoot "provider-$stamp.ready.json"

$managedEnvironment = @{
    SUPER_HARNESS_GATEWAY_KIND = 'haifa'
    SUPER_HARNESS_PROFILE = 'local'
    SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
    SUPER_HARNESS_PORT = [string]$GatewayPort
    SUPER_HARNESS_DATA_ROOT = $dataRoot
    SUPER_HARNESS_DATA_DIR = $dataRoot
    SUPER_HARNESS_ENVIRONMENT = 'development'
    DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = $internalGatewayBaseUrl
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
    W18_EVIDENCE_DIR = $evidenceRoot
    COREPACK_ENABLE_NETWORK = '0'
    W18_PROVIDER_URL = $null
    UXV1_DEFER_WEB = $(if ($DeferUxV1Web) { '1' } else { $null })
    W18_MODEL_NAME = $(if ($LiveDeepSeek) { 'w18-deepseek-live' } else { 'w18-controlled' })
    W18_PLAYWRIGHT_SPEC = $(if ($UxV1AgentSoul) {
        'ux-v1-agent-soul.spec.cjs'
    } elseif ($UxV1DefaultTools) {
        'ux-v1-default-tools-live.spec.cjs'
    } elseif ($LiveDeepSeek) {
        'w18-deepseek-live.spec.cjs'
    } elseif ($ActiveReconnect) {
        'w05-active-reconnect.spec.cjs'
    } elseif ($Interaction) {
        'w05-interaction.spec.cjs'
    } elseif ($DemoArtifact) {
        'w05-demo-artifact.spec.cjs'
    } elseif ($TitleSuggestions) {
        'w03-title-suggestions.spec.cjs'
    } elseif ($InputPolish) {
        'w03-input-polish.spec.cjs'
    } elseif ($V2ModelSwitch) {
        'w03-v2-model-switch.spec.cjs'
    } elseif ($V2FileContext) {
        'w04-v2-file-context.spec.cjs'
    } elseif ($V2Skill) {
        'w15-v2-skill.spec.cjs'
    } elseif ($V2Diagnostics) {
        'w21-v2-diagnostics.spec.cjs'
    } else {
        'w18-ui-smoke.spec.cjs'
    })
    W18_PLAYWRIGHT_TIMEOUT_MS = $(if ($UxV1DefaultTools -or $UxV1AgentSoul) { '360000' } elseif ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics) { '120000' } else { '60000' })
    W18_PLAYWRIGHT_TRACE = $(if ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics) { 'on' } else { 'retain-on-failure' })
    # Kept disabled through the ordinary build/test gate. The runner enables it
    # only immediately before launching the dedicated guarded test process.
    W05_INTERACTION_ACCEPTANCE = $null
}
$savedEnvironment = @{}
$testExitCode = 1
$expectedCases = @([regex]::Matches((Get-Content -Raw (Join-Path $root "tests/w18-ui-smoke/$($managedEnvironment.W18_PLAYWRIGHT_SPEC)")), 'test(?:\.skip|\.only)?\(\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
try {
    # Reject the complete pair before starting any process, including build commands.
    Assert-FixturePortsBindable -Ports @($GatewayPort, $FrontendPort) -EvidenceDirectory $evidenceRoot
    $sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root
    $null = New-Item -ItemType Directory -Force -Path $dataRoot
    foreach ($name in $managedEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $managedEnvironment[$name], 'Process')
    }
    $profile = & (Join-Path $root 'scripts/verify-haifa-frontend-profile.ps1') `
        -RepositoryRoot $root `
        -SourceRepository $SourceRepository
    if ($LASTEXITCODE -ne 0) {
        throw 'W18 Haifa frontend profile verification failed.'
    }
    $profile | Set-Content -LiteralPath (Join-Path $evidenceRoot "profile-$stamp.json") -Encoding utf8
    Set-FixturePhase $fixture 'build'

    # The Interaction profile runs the Gateway from the test classpath, not the exec JAR.
    $reuseServerBuild = ($SkipBuild -or $SkipServerBuild) -and -not $Interaction
    if ($reuseServerBuild) {
        Assert-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
    }
    if ($SkipBuild) {
        Assert-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $internalGatewayBaseUrl
    }
    if (-not $SkipBuild) {
        if (-not $SkipServerBuild) {
        Remove-BuildStamp (Get-ServerBuildStampPath $root)
        Push-Location $root
        try {
            & mvn.cmd -o -ntp -pl super-harness-server -am @(@($sdk.MavenArgument) -ne $null) package 2>&1 | Tee-Object -FilePath (Join-Path $evidenceRoot 'maven-build.raw.log')
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
        Write-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
        }

        Remove-BuildStamp (Get-FrontendBuildStampPath $root)
        Push-Location (Join-Path $root 'frontend')
        try {
            & corepack pnpm build 2>&1 | Tee-Object -FilePath (Join-Path $evidenceRoot 'frontend-build.raw.log')
            if ($LASTEXITCODE -ne 0) {
                throw "Frozen frontend build failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
        Write-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $internalGatewayBaseUrl
    }
    Set-FixturePhase $fixture 'readiness'

    $jar = Join-Path $root 'super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar'
    $next = Join-Path $root 'frontend/node_modules/next/dist/bin/next'
    if (-not $Interaction -and -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'Executable Haifa server JAR is missing; run without -SkipBuild.'
    }
    if (-not (Test-Path -LiteralPath $next -PathType Leaf)) {
        throw 'Repo-local Next.js executable is missing; run pnpm install --offline --frozen-lockfile.'
    }

    $providerUrl = $null
    if (-not $LiveDeepSeek) {
        $providerScript = Join-Path $root 'tests/w18-ui-smoke/loopback-openai-provider.cjs'
        $providerProcess = Start-FixtureProcess -Fixture $fixture -Name Provider -FilePath (Get-Command node.exe).Source `
            -ArgumentList @($providerScript, [string]$ProviderPort, $providerReady) -WorkingDirectory $root `
            -StdoutPath $providerOut -StderrPath $providerErr
        $providerMetadata = Wait-FixtureReady -Entry $providerProcess -TimeoutSec 15 -ObjectName $providerReady `
            -Observe { if (Test-Path -LiteralPath $providerReady) { Get-Content -Raw $providerReady | ConvertFrom-Json } } `
            -Accept { param($value) $null -ne $value -and $value.type -eq 'ready' }
        $providerUrl = [string]$providerMetadata.address
        $providerUri = [Uri]$providerUrl
        if ($providerUri.Scheme -ne 'http' -or $providerUri.Host -ne '127.0.0.1' -or $providerUri.Port -lt 1) {
            throw "Controlled provider published unsafe ready address '$providerUrl'."
        }
        if ([int]$providerMetadata.pid -ne $providerProcess.Id) {
            throw 'Controlled provider ready metadata PID does not match the started process.'
        }
        $fixture.Ports.Add($providerUri.Port)
        $providerProcess.Ports = @($providerUri.Port)
        [Environment]::SetEnvironmentVariable('W18_PROVIDER_URL', $providerUrl, 'Process')
        $providerHealth = Invoke-RestMethod "$providerUrl/health" -TimeoutSec 2
        if ($providerHealth.status -ne 'ok') {
            throw "Controlled provider health check failed (PID $($providerProcess.Id))."
        }
    }

    if ($Interaction) {
        [Environment]::SetEnvironmentVariable('W05_INTERACTION_ACCEPTANCE', '1', 'Process')
        $gatewayFile = (Get-Command mvn.cmd).Source
        $gatewayArgs = @('-o', '-ntp', '-pl', 'super-harness-server', '-am', '-Dtest=InteractionAcceptanceServerTest', '-Dsurefire.failIfNoSpecifiedTests=false', '-DforkCount=0') + @(@($sdk.MavenArgument) -ne $null) + @('test')
    } else {
        $gatewayFile = (Get-Command java.exe).Source
        $gatewayArgs = @('-jar', $jar)
    }
    $gatewayProcess = Start-FixtureProcess -Fixture $fixture -Name Gateway -FilePath $gatewayFile `
        -ArgumentList $gatewayArgs -WorkingDirectory $root -StdoutPath $serverOut -StderrPath $serverErr -Ports @($GatewayPort)
    $health = Wait-FixtureReady -Entry $gatewayProcess -TimeoutSec 30 -ObjectName "http://127.0.0.1:$GatewayPort/health" `
        -Observe { Invoke-RestMethod "http://127.0.0.1:$GatewayPort/health" -TimeoutSec 1 } `
        -Accept { param($value) $null -ne $value -and $value.status -eq 'ok' }

    $managedModels = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/managed-models" -TimeoutSec 3
    $modelName = if ($LiveDeepSeek) { 'w18-deepseek-live' } else { 'w18-controlled' }
    $existingModel = @($managedModels.models) | Where-Object { $_.name -eq $modelName } | Select-Object -First 1
    $expectedRevision = if ($null -eq $existingModel) { $null } else { $existingModel.revision }
    $modelEnvelope = [ordered]@{
        config = if ($LiveDeepSeek) {
            [ordered]@{
                name = $modelName
                display_name = 'W18 DeepSeek Live'
                provider = 'openai-compatible'
                model = 'deepseek-v4-pro'
                base_url = 'https://api.deepseek.com'
                credential_ref = 'os://DEEPSEEK_API_KEY'
                enabled = $true
                supports_vision = $false
                context_window = 1048576
                max_tokens = 2048
            }
        } else {
            [ordered]@{
                name = $modelName
                display_name = 'W18 Controlled Loopback'
                provider = 'openai-compatible'
                model = 'w18-controlled-model'
                base_url = "$providerUrl/v1"
                enabled = $true
                supports_vision = $false
                # The full frozen Lead prompt, production Tool catalog and optional Skill
                # body must fit together. 8192 rejects even the default smoke before
                # any Provider request with MODEL_CONTEXT_TOO_LONG.
                context_window = 32768
                max_tokens = 1024
                api_key = 'w18-loopback-only'
            }
        }
        expected_revision = $expectedRevision
    }
    $modelBody = $modelEnvelope | ConvertTo-Json -Depth 6 -Compress
    $savedModel = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$GatewayPort/api/managed-models" `
        -Method Put `
        -ContentType 'application/json' `
        -Body $modelBody `
        -TimeoutSec 5
    $probe = $null
    if (-not $LiveDeepSeek) {
        # PUT advances the optimistic-lock revision. Probe the exact revision just
        # saved so repeated evidence runs remain deterministic against persisted
        # slot data instead of reusing the pre-save expected_revision.
        $modelEnvelope.expected_revision = $savedModel.revision
        $modelBody = $modelEnvelope | ConvertTo-Json -Depth 6 -Compress
        $probe = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$GatewayPort/api/managed-models/test" `
            -Method Post `
            -ContentType 'application/json' `
            -Body $modelBody `
            -TimeoutSec 10
        if ($probe.ok -ne $true) {
            throw "Controlled managed-model probe failed: $($probe.message)"
        }
    }
    $runCatalog = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/models" -TimeoutSec 3
    if (@($runCatalog.models.name) -notcontains $modelName) {
        throw 'Managed model did not enter the executable Run catalog.'
    }
    if ($V2ModelSwitch) {
        $secondName = 'w18-controlled-b'
        $currentManaged = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/managed-models" -TimeoutSec 3
        $secondExisting = @($currentManaged.models) | Where-Object { $_.name -eq $secondName } | Select-Object -First 1
        $secondEnvelope = [ordered]@{
            config = [ordered]@{
                name = $secondName
                display_name = 'W18 Controlled Loopback B'
                provider = 'openai-compatible'
                model = 'w18-controlled-model-b'
                base_url = "$providerUrl/v1"
                enabled = $true
                supports_vision = $false
                context_window = 32768
                max_tokens = 1024
                api_key = 'w18-loopback-only'
            }
            expected_revision = $(if ($secondExisting) { $secondExisting.revision } else { $null })
        }
        $secondSaved = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$GatewayPort/api/managed-models" `
            -Method Put -ContentType 'application/json' `
            -Body ($secondEnvelope | ConvertTo-Json -Depth 6 -Compress) -TimeoutSec 5
        if (-not $secondSaved.enabled -or -not $secondSaved.has_api_key) {
            throw 'Second controlled model did not save correctly.'
        }
        $runCatalog = Invoke-RestMethod "http://127.0.0.1:$GatewayPort/api/models" -TimeoutSec 3
        if (@($runCatalog.models.name) -notcontains $secondName) {
            throw 'Second controlled model did not enter the executable Run catalog.'
        }
    }
    [ordered]@{
        name = $savedModel.name
        revision = $savedModel.revision
        enabled = $savedModel.enabled
        hasApiKey = $savedModel.has_api_key
        hasCredential = $savedModel.has_credential
        credentialSource = $savedModel.credential_source
        probe = $probe
        executableCatalogNames = @($runCatalog.models.name)
        provider = $(if ($LiveDeepSeek) { 'https://api.deepseek.com' } else { "$providerUrl/v1" })
        dialect = $(if ($LiveDeepSeek) { 'deepseek-openai-chat' } else { 'openai-standard' })
    } | ConvertTo-Json -Depth 5 | Set-Content `
        -LiteralPath (Join-Path $evidenceRoot "managed-model-$stamp.json") `
        -Encoding utf8

    $frontendProcess = Start-FixtureProcess -Fixture $fixture -Name Frontend -FilePath (Get-Command node.exe).Source `
        -ArgumentList @($next, 'start', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort) `
        -WorkingDirectory (Join-Path $root 'frontend') -StdoutPath $frontendOut -StderrPath $frontendErr -Ports @($FrontendPort)
    $page = Wait-FixtureReady -Entry $frontendProcess -TimeoutSec 60 -ObjectName "http://127.0.0.1:$FrontendPort/workspace/chats/new" `
        -Observe { Invoke-WebRequest "http://127.0.0.1:$FrontendPort/workspace/chats/new" -UseBasicParsing -TimeoutSec 2 } `
        -Accept { param($value) $null -ne $value -and $value.StatusCode -eq 200 }
    Set-FixturePhase $fixture 'assertions'

    [ordered]@{
        gateway = "http://127.0.0.1:$GatewayPort"
        gatewayPid = $gatewayProcess.Id
        provider = $(if ($LiveDeepSeek) { 'https://api.deepseek.com' } else { $providerUrl })
        controlledProviderPid = $(if ($null -eq $providerProcess) { $null } else { $providerProcess.Id })
        frontend = "http://127.0.0.1:$FrontendPort"
        frontendPid = $frontendProcess.Id
        dataRoot = $dataRoot
        evidenceRoot = $evidenceRoot
    } | ConvertTo-Json

    Push-Location $root
    try {
        $playwright = Start-FixtureProcess -Fixture $fixture -Name Playwright -FilePath (Get-Command node.exe).Source `
            -ArgumentList @((Join-Path $root 'frontend/node_modules/@playwright/test/cli.js'), 'test', '--config', 'tests/w18-ui-smoke/playwright.config.cjs') `
            -WorkingDirectory $root -StdoutPath (Join-Path $evidenceRoot 'playwright.raw.log') -StderrPath (Join-Path $evidenceRoot 'playwright.stderr.log')
        $testExitCode = Wait-FixtureExit -Entry $playwright `
            -TimeoutSec ([Math]::Max(1, $expectedCases.Count) * ([int]$managedEnvironment.W18_PLAYWRIGHT_TIMEOUT_MS / 1000) + 60)
        Get-Content -LiteralPath (Join-Path $evidenceRoot 'playwright.raw.log')
        $fixture.TestExitCode = $testExitCode
        if ($testExitCode -ne 0) { throw "W18 UI smoke failed with exit code $testExitCode." }
    }
    finally {
        Pop-Location
    }
}
catch {
    $fixture.FirstFailure = [ordered]@{ phase = $fixture.Phase; message = $_.Exception.Message }
    throw
}
finally {
    Set-FixturePhase $fixture 'cleanup'
    try { Stop-FixtureProcesses $fixture }
    catch {
        if ($null -eq $fixture.FirstFailure) { $fixture.FirstFailure = @{ phase = 'cleanup'; message = $_.Exception.Message } }
        throw
    }
    finally {
        foreach ($name in $savedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
        Set-FixturePhase $fixture 'evidenceArchive'
        Write-FixtureFlowHealth $fixture -ExpectedCases $expectedCases -Extra @{
            acceptanceProfile = $tag; sdkSelection = $sdk.Status; haifaVersion = $sdk.HaifaVersion
            serverBuildReused = [bool]($SkipBuild -or $SkipServerBuild); webDeferred = [bool]$DeferUxV1Web
            dataRoot = $dataRoot
        }
        Write-Output "flowHealth=$evidenceRoot/flow-health.json"
    }
}
