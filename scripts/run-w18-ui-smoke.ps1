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
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$expectedGatewayPort = 10618 + (10000 * $AgentSlot)
$expectedFrontendPort = 15618 + (10000 * $AgentSlot)
if ($GatewayPort -ne $expectedGatewayPort -or $FrontendPort -ne $expectedFrontendPort -or $ProviderPort -ne 0) {
    throw "W18 slot $AgentSlot requires Gateway=$expectedGatewayPort and Frontend=$expectedFrontendPort; controlled Provider must use OS-assigned port 0."
}
if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $LiveDeepSeek).Where({ $_ }).Count -gt 1) {
    throw 'Active reconnect, Interaction, demo-artifact, title/suggestions, input-polish, V2 model-switch, V2 file-context, V2 Skill, V2 diagnostics, and live DeepSeek profiles are mutually exclusive.'
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root $(if ($LiveDeepSeek) {
        "target/agent-slot-$AgentSlot-deepseek-data"
    } elseif ($Interaction) {
        "target/agent-slot-$AgentSlot-interaction-data"
    } elseif ($DemoArtifact) {
        "local-tmp/slot-$AgentSlot-w05-m2"
    } elseif ($TitleSuggestions) {
        "target/agent-slot-$AgentSlot-title-suggestions-data"
    } elseif ($InputPolish) {
        "target/agent-slot-$AgentSlot-input-polish-data"
    } elseif ($V2ModelSwitch) {
        "local-tmp/slot-$AgentSlot-w03-v2-model-switch/data/ui"
    } elseif ($V2FileContext) {
        "local-tmp/slot-$AgentSlot-w04-v2-file-context/ui-data"
    } elseif ($V2Skill) {
        "local-tmp/slot-$AgentSlot-w15-v2-skill/data/ui"
    } elseif ($V2Diagnostics) {
        "local-tmp/slot-$AgentSlot-w21/data/ui"
    } else {
        "target/agent-slot-$AgentSlot-data"
    })
}
$dataRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\', '/')
$rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $dataRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'W18 data directory must remain inside this worktree.'
}

foreach ($port in @($GatewayPort, $FrontendPort)) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Refusing occupied W18 slot port $port (PID $($listener.OwningProcess -join ','))."
    }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() }
    catch { throw "W18 slot port $port cannot bind on loopback (check Windows excluded port ranges): $($_.Exception.Message)" }
    finally { $probe.Stop() }
}

$null = New-Item -ItemType Directory -Force -Path $dataRoot
$evidenceRootBase = Join-Path $root $(if ($LiveDeepSeek) {
    'target/w18-deepseek-web-l3/playwright'
} elseif ($ActiveReconnect) {
    'target/w05-active-reconnect-evidence/playwright'
} elseif ($Interaction) {
    'target/w05-interaction-evidence/playwright'
} elseif ($DemoArtifact) {
    "local-tmp/slot-$AgentSlot-w05-m2-evidence/playwright"
} elseif ($TitleSuggestions) {
    'target/w03-title-suggestions-evidence/playwright'
} elseif ($InputPolish) {
    "local-tmp/slot-$AgentSlot-w03-input-polish-ui/evidence"
} elseif ($V2ModelSwitch) {
    "local-tmp/slot-$AgentSlot-w03-v2-model-switch/evidence/ui"
} elseif ($V2FileContext) {
    "local-tmp/slot-$AgentSlot-w04-v2-file-context/evidence/ui"
} elseif ($V2Skill) {
    "local-tmp/slot-$AgentSlot-w15-v2-skill/evidence/ui"
} elseif ($V2Diagnostics) {
    "local-tmp/slot-$AgentSlot-w21/evidence/ui"
} else {
    'target/w18-ui-smoke-evidence/playwright'
})
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidenceRoot = if ($DemoArtifact -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics) { Join-Path $evidenceRootBase "run-$stamp" } else { $evidenceRootBase }
$null = New-Item -ItemType Directory -Force -Path $evidenceRoot
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
    W18_EVIDENCE_DIR = $evidenceRoot
    COREPACK_ENABLE_NETWORK = '0'
    W18_PROVIDER_URL = $null
    W18_MODEL_NAME = $(if ($LiveDeepSeek) { 'w18-deepseek-live' } else { 'w18-controlled' })
    W18_PLAYWRIGHT_SPEC = $(if ($LiveDeepSeek) {
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
    W18_PLAYWRIGHT_TIMEOUT_MS = $(if ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics) { '120000' } else { '60000' })
    W18_PLAYWRIGHT_TRACE = $(if ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics) { 'on' } else { 'retain-on-failure' })
    # Kept disabled through the ordinary build/test gate. The runner enables it
    # only immediately before launching the dedicated guarded test process.
    W05_INTERACTION_ACCEPTANCE = $null
}
$savedEnvironment = @{}
foreach ($name in $managedEnvironment.Keys) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $managedEnvironment[$name], 'Process')
}

$gatewayProcess = $null
$frontendProcess = $null
$providerProcess = $null
$testExitCode = 1
try {
    $profile = & (Join-Path $root 'scripts/verify-haifa-frontend-profile.ps1') `
        -RepositoryRoot $root `
        -SourceRepository $SourceRepository
    if ($LASTEXITCODE -ne 0) {
        throw 'W18 Haifa frontend profile verification failed.'
    }
    $profile | Set-Content -LiteralPath (Join-Path $evidenceRoot "profile-$stamp.json") -Encoding utf8

    if (-not $SkipBuild) {
        Push-Location $root
        try {
            & mvn.cmd -o -ntp -pl super-harness-server -am package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }

        Push-Location (Join-Path $root 'frontend')
        try {
            & corepack pnpm build
            if ($LASTEXITCODE -ne 0) {
                throw "Frozen frontend build failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
    }

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
        $providerProcess = Start-Process -FilePath (Get-Command node.exe).Source `
            -ArgumentList @($providerScript, [string]$ProviderPort, $providerReady) `
            -WorkingDirectory $root `
            -RedirectStandardOutput $providerOut `
            -RedirectStandardError $providerErr `
            -PassThru `
            -WindowStyle Hidden
        $providerDeadline = (Get-Date).AddSeconds(15)
        $providerMetadata = $null
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
        if ([int]$providerMetadata.pid -ne $providerProcess.Id) {
            throw 'Controlled provider ready metadata PID does not match the started process.'
        }
        [Environment]::SetEnvironmentVariable('W18_PROVIDER_URL', $providerUrl, 'Process')
        $providerHealth = Invoke-RestMethod "$providerUrl/health" -TimeoutSec 2
        if ($providerHealth.status -ne 'ok') {
            throw "Controlled provider health check failed (PID $($providerProcess.Id))."
        }
    }

    if ($Interaction) {
        # The fixture exists only on the test classpath. Surefire runs in the
        # Maven JVM so the process tree remains attributable to this runner.
        [Environment]::SetEnvironmentVariable('W05_INTERACTION_ACCEPTANCE', '1', 'Process')
        $gatewayProcess = Start-Process -FilePath (Get-Command mvn.cmd).Source `
            -ArgumentList @('-o', '-ntp', '-pl', 'super-harness-server', '-am', '-Dtest=InteractionAcceptanceServerTest', '-Dsurefire.failIfNoSpecifiedTests=false', '-DforkCount=0', 'test') `
            -WorkingDirectory $root `
            -RedirectStandardOutput $serverOut `
            -RedirectStandardError $serverErr `
            -PassThru `
            -WindowStyle Hidden
    }
    else {
        $gatewayProcess = Start-Process -FilePath (Get-Command java.exe).Source `
            -ArgumentList @('-jar', $jar) `
            -WorkingDirectory $root `
            -RedirectStandardOutput $serverOut `
            -RedirectStandardError $serverErr `
            -PassThru `
            -WindowStyle Hidden
    }
    $gatewayDeadline = (Get-Date).AddSeconds(30)
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
                # The frozen consulting-analysis entry is ~8.2k tokens by itself.
                # Its full activated body must fit alongside Run context and output.
                context_window = $(if ($V2Skill) { 32768 } else { 8192 })
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
                context_window = 8192
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

    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
        -ArgumentList @($next, 'start', '--hostname', '127.0.0.1', '--port', [string]$FrontendPort) `
        -WorkingDirectory (Join-Path $root 'frontend') `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr `
        -PassThru `
        -WindowStyle Hidden
    $frontendDeadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 250
        try {
            $page = Invoke-WebRequest "http://127.0.0.1:$FrontendPort/workspace/chats/new" `
                -UseBasicParsing `
                -TimeoutSec 2
        }
        catch {
            $page = $null
        }
    } until (($null -ne $page -and $page.StatusCode -eq 200) -or $frontendProcess.HasExited -or (Get-Date) -ge $frontendDeadline)
    if ($null -eq $page -or $page.StatusCode -ne 200) {
        throw "Frozen frontend failed readiness (PID $($frontendProcess.Id))."
    }

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
        & node.exe frontend/node_modules/@playwright/test/cli.js test `
            --config tests/w18-ui-smoke/playwright.config.cjs
        $testExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($process in @($frontendProcess, $gatewayProcess, $providerProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            $descendants = @{}
            $queue = [Collections.Generic.Queue[int]]::new()
            $queue.Enqueue($process.Id)
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
            Stop-Process -InputObject $process -Force -ErrorAction SilentlyContinue
            $null = $process.WaitForExit(10000)
        }
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}

if ($testExitCode -ne 0) {
    throw "W18 UI smoke failed with exit code $testExitCode."
}
