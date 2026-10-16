[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository = 'D:\workspace\haifa-agent-super-harness\deer-flow',
    [ValidateRange(1, 5)]
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
    [switch]$W08Catalog,
    [switch]$W08Delegation,
    # W05 live-rendering: hold a controlled Run mid-flight and assert the page.
    [switch]$LiveRendering,
    [switch]$LiveDeepSeek,
    # Real frozen Ultra delegation, using the same managed live provider.
    [switch]$W08LiveDelegation,
    # W15 representative managed Skill and evaluation-result chain using the real provider.
    [switch]$W15LiveSkill,
    [switch]$W15PackageDifferential,
    # W04 skill admission seed: host shell Tool on, isolated baoyu-image-gen seed directory,
    # the deferred 038-32 assertion table, and the live DeepSeek managed model.
    [switch]$W04ImageGen,
    # UX-V1 joint acceptance: the live DeepSeek model with the DeerFlow default tool chains.
    [switch]$UxV1DefaultTools,
    # UX-V1 M3: the frozen new-agent conversation/save flow with the live managed model.
    [switch]$UxV1AgentSoul,
    # Human-authorized DDG deferral; leaves the required Web case visibly NOT_RUN.
    [switch]$DeferUxV1Web,
    # Reuse the same-source reactor JAR after the controller's full gate; still build Next cold.
    [switch]$SkipServerBuild,
    [switch]$SkipBuild,
    [ValidateNotNullOrEmpty()]
    [string]$CaseTitle,
    # Repeat the selected spec in one service start. Only valid on a warm round.
    [ValidateRange(1, 5)]
    [int]$Passes = 1,
    [switch]$PreflightOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($DeferUxV1Web -and -not $UxV1DefaultTools) {
    throw 'DeferUxV1Web requires the UX-V1 default-tools profile.'
}
if ($SkipServerBuild -and $Interaction) {
    throw 'SkipServerBuild cannot reuse an executable JAR for the Interaction test-classpath profile.'
}

$expectedGatewayPort = 10618 + (10000 * $AgentSlot)
$expectedFrontendPort = 15618 + (10000 * $AgentSlot)
# W15 controller: additional isolated slot authorized by the user on 2026-10-03.
if ($AgentSlot -eq 4) { $expectedGatewayPort = 11618; $expectedFrontendPort = 16618 }
if ($AgentSlot -eq 5) { $expectedGatewayPort = 12618; $expectedFrontendPort = 17618 }
if ($GatewayPort -ne $expectedGatewayPort -or $FrontendPort -ne $expectedFrontendPort -or $ProviderPort -ne 0) {
    throw "W18 slot $AgentSlot requires Gateway=$expectedGatewayPort and Frontend=$expectedFrontendPort; controlled Provider must use OS-assigned port 0."
}
if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $W08Catalog, $W08Delegation, $LiveRendering, $LiveDeepSeek, $W08LiveDelegation, $W15PackageDifferential, $W04ImageGen).Where({ $_ }).Count -gt 1) {
    throw 'Active reconnect, Interaction, demo-artifact, title/suggestions, input-polish, V2 model-switch, V2 file-context, V2 Skill, V2 diagnostics, W08 catalog, W08 delegation, live rendering, W15 package differential, the W04 imagegen profile, and live DeepSeek are mutually exclusive.'
}
if ($W15PackageDifferential) { $V2Skill = $true }
if (@($UxV1DefaultTools, $UxV1AgentSoul, $W15LiveSkill, $W04ImageGen).Where({ $_ }).Count -gt 1) {
    throw 'The UX-V1, W15 live-skill and W04 imagegen profiles are mutually exclusive.'
}
if ($UxV1DefaultTools -or $UxV1AgentSoul -or $W15LiveSkill) {
    if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $W08Catalog, $W08Delegation, $LiveRendering, $LiveDeepSeek, $W08LiveDelegation).Where({ $_ }).Count -gt 0) {
        throw 'The UX-V1 profiles are mutually exclusive with the other profiles.'
    }
    # Reuses the live DeepSeek managed model and skips the controlled loopback provider.
    $LiveDeepSeek = $true
}
if ($W08LiveDelegation) { $LiveDeepSeek = $true }
if ($W04ImageGen) {
    if (@($ActiveReconnect, $Interaction, $DemoArtifact, $TitleSuggestions, $InputPolish, $V2ModelSwitch, $V2FileContext, $V2Skill, $V2Diagnostics, $W08Catalog, $W08Delegation, $LiveRendering, $W08LiveDelegation, $W15PackageDifferential).Where({ $_ }).Count -gt 0) {
        throw 'The W04 imagegen profile is mutually exclusive with the other profiles.'
    }
    # Reuses the live DeepSeek managed model and skips the controlled loopback provider. With the
    # declared bun runtime A1-A8 all assert real behaviour; without it only A0/A8 run and the rest
    # stay in the AllowedNotRun list sourced from w04-imagegen-deferred.json.
    $LiveDeepSeek = $true
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
. (Join-Path $PSScriptRoot '../lib/sdk-selection.ps1')
. (Join-Path $PSScriptRoot '../lib/build-stamp.ps1')
. (Join-Path $PSScriptRoot '../lib/preflight.ps1')
Use-SafeNoProxy
. (Join-Path $PSScriptRoot '../lib/smoke-preflight.ps1')
$controlledContextWindow = 32768
$sdk = [pscustomobject]@{ Status = 'NOT_RUN'; HaifaVersion = $null }
$internalGatewayBaseUrl = "http://127.0.0.1:$GatewayPort"
 . (Join-Path $PSScriptRoot '../lib/run-fixture.ps1')
$tag = if ($W15PackageDifferential) { 'w15-package-differential' } elseif ($W15LiveSkill) { 'w15-live-skill' } elseif ($UxV1AgentSoul) { 'ux-v1-soul' } elseif ($UxV1DefaultTools) { 'ux-v1' }
    elseif ($W04ImageGen) { 'w04-imagegen' }
    elseif ($W08LiveDelegation) { 'w08-live-delegation' }
    elseif ($LiveDeepSeek) { 'w18-deepseek' } elseif ($ActiveReconnect) { 'w05-active-reconnect' }
    elseif ($Interaction) { 'w05-interaction' } elseif ($DemoArtifact) { 'w05-demo-artifact' }
    elseif ($TitleSuggestions) { 'w03-title-suggestions' } elseif ($InputPolish) { 'w03-input-polish-ui' }
    elseif ($V2ModelSwitch) { 'w03-v2-model-switch' } elseif ($V2FileContext) { 'w04-v2-file-context' }
    elseif ($V2Skill) { 'w15-v2-skill' } elseif ($V2Diagnostics) { 'w21-diagnostics' }
    elseif ($W08Catalog) { 'w08-catalog' } elseif ($W08Delegation) { 'w08-delegation' }
    elseif ($LiveRendering) { 'w05-live-rendering' } else { 'w18-ui-smoke' }
if ($Passes -gt 1) {
    # The planner owns the option rules (warm only; never with -CaseTitle/-PreflightOnly) so
    # the runner and its unit tests cannot drift.
    $validate = @('-B', (Join-Path $PSScriptRoot '../lib/ui_round_plan.py'), 'validate', '--passes', [string]$Passes)
    if ($CaseTitle) { $validate += @('--case-title', $CaseTitle) }
    if ($PreflightOnly) { $validate += @('--preflight-only') }
    if ($SkipBuild) { $validate += @('--skip-build') }
    $validate += @('--profile', $tag)
    & python @validate
    if ($LASTEXITCODE -ne 0) { throw "Invalid -Passes $Passes options for the W18 UI runner." }
}
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

# W04 imagegen setup, before any service or build: materialize the real upstream
# baoyu-image-gen package (the controller-staged local-tmp/w04-real-skill copy) inside
# this profile's isolated data root (never the user home), record the cached bun
# directory and the codex CLI directory for the Gateway PATH, and snapshot the profile
# facts for the spec. The deferred A1-A8 gate and the runner AllowedNotRun list read
# tests/ui/smoke/w04-imagegen-deferred.json.
$w04 = $null
$w04Deferred = @()
if ($W04ImageGen) {
    $w04SeedSource = Join-Path $root 'local-tmp/w04-real-skill/baoyu-image-gen'
    $w04SourceManifest = Join-Path $root 'local-tmp/w04-real-skill/MANIFEST.sha256'
    if (-not (Test-Path -LiteralPath (Join-Path $w04SeedSource 'scripts/main.ts') -PathType Leaf) -or
        -not (Test-Path -LiteralPath $w04SourceManifest -PathType Leaf)) {
        throw 'The staged real baoyu-image-gen copy is missing: the controller must stage local-tmp/w04-real-skill inside this worktree.'
    }
    $w04SkillsDirectory = Join-Path $dataRoot 'skills-fixture'
    $w04SeedPackage = Join-Path $w04SkillsDirectory 'baoyu-image-gen'
    $w04SeedManifest = Join-Path $dataRoot 'skill-seed-manifest.json'
    $w04ExtendDirectory = Join-Path $dataRoot '.baoyu-skills'
    Push-Location $root
    try {
        & python -B (Join-Path $root 'tests/ui/fixtures/w04/baoyu_imagegen_seed.py') `
            --package $w04SeedPackage --extend $w04ExtendDirectory --manifest $w04SeedManifest `
            --source $w04SeedSource --source-manifest $w04SourceManifest 2>&1 |
            Tee-Object -FilePath (Join-Path $evidenceRoot 'w04-seed.raw.log')
        if ($LASTEXITCODE -ne 0) { throw 'W04 imagegen seed materialization failed; inspect w04-seed.raw.log.' }
    }
    finally { Pop-Location }
    if (-not (Test-Path -LiteralPath (Join-Path $w04SeedPackage 'SKILL.md') -PathType Leaf)) {
        throw 'W04 imagegen seed is incomplete: SKILL.md is missing after materialization.'
    }
    $w04BunHome = $null
    $w04BunCandidate = [Environment]::GetEnvironmentVariable('W04_BUN_HOME', 'Process')
    if ($w04BunCandidate) {
        # The controller supplies an already-provisioned bun directory; the runner never installs,
        # fetches or substitutes one and only passes it through to the Gateway (its PATH prefix,
        # plus the declared HAIFA_SUPER_HARNESS_BUN_PATH executable below).
        $candidate = [IO.Path]::GetFullPath($w04BunCandidate).TrimEnd('\', '/')
        if (Test-Path -LiteralPath (Join-Path $candidate 'bun.exe') -PathType Leaf) { $w04BunHome = $candidate }
    }
    $w04BunPath = if ($w04BunHome) { (Join-Path $w04BunHome 'bun.exe') } else { $null }
    # The real package's codex-cli provider resolves `bun` and the wrapper resolves `codex`
    # from the process PATH, so the codex CLI directory joins the same one-round PATH prefix.
    # The controller may pin it through W04_CODEX_HOME; otherwise this session's PATH decides.
    $w04CodexHome = $null
    $w04CodexCandidate = [Environment]::GetEnvironmentVariable('W04_CODEX_HOME', 'Process')
    if (-not $w04CodexCandidate) {
        $codexResolved = Get-Command codex -ErrorAction SilentlyContinue
        if ($codexResolved) { $w04CodexCandidate = Split-Path -Parent $codexResolved.Source }
    }
    if ($w04CodexCandidate) {
        $codexCandidate = [IO.Path]::GetFullPath($w04CodexCandidate).TrimEnd('\', '/')
        if (Test-Path -LiteralPath (Join-Path $codexCandidate 'codex.exe') -PathType Leaf) { $w04CodexHome = $codexCandidate }
    }
    if ($w04BunHome -and -not $w04CodexHome) {
        throw 'W04 imagegen round requires the codex CLI next to the declared bun runtime: set W04_CODEX_HOME or put codex.exe on PATH.'
    }
    # The W04 profile starts a brand-new data root every round. The per-data-root Credential
    # Manager provisioning can be unavailable on a host whose Credential Manager is full, so this
    # profile injects the Runtime continuation key as an external process-environment reference:
    # one random 256-bit key per round, referenced through HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF
    # and carried only in the Gateway's process environment. The value is never logged, printed,
    # recorded in evidence or written to disk by this script.
    $w04KeyBytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($w04KeyBytes)
    $w04 = [ordered]@{
        hostShell = 'on'
        runtime = $(if ($w04BunPath) { 'declared' } else { 'undeclared' })
        toolName = 'baoyu_image_gen'
        skillsDirectory = $w04SkillsDirectory
        seedPackage = $w04SeedPackage
        extendPath = (Join-Path $w04ExtendDirectory 'baoyu-image-gen/EXTEND.md')
        manifest = $w04SeedManifest
        bunHome = $w04BunHome
        bunPath = $w04BunPath
        bunHomeCandidate = $w04BunCandidate
        codexHome = $w04CodexHome
        codexPath = $(if ($w04CodexHome) { Join-Path $w04CodexHome 'codex.exe' } else { $null })
        stagedSource = $w04SeedSource
        stagedSourceManifest = $w04SourceManifest
        codexCacheDirectory = $(if ($W04ImageGen) { Join-Path $dataRoot 'baoyu-codex-cache' } else { $null })
        codexImagegenLog = $(if ($W04ImageGen) { Join-Path $evidenceRoot 'w04-codex-imagegen.jsonl' } else { $null })
        workspaceStorageRoot = (Join-Path $dataRoot 'workspace')
    }
    $w04 | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'w04-profile.json') -Encoding utf8
    $w04SeedStatus = & python -B (Join-Path $root 'tests/ui/fixtures/w04/baoyu_imagegen_seed.py') `
        --package $w04SeedPackage --extend $w04ExtendDirectory --manifest $w04SeedManifest `
        --source $w04SeedSource --source-manifest $w04SourceManifest --verify 2>&1
    if ($LASTEXITCODE -ne 0) { throw "W04 imagegen seed verification failed: $w04SeedStatus" }
    $w04DeferredManifest = Get-Content -Raw -LiteralPath (Join-Path $root 'tests/ui/smoke/w04-imagegen-deferred.json') |
        ConvertFrom-Json
    # Declared rounds may not defer anything; the undeclared-runtime round defers exactly the
    # execution cases while A8 alone proves the fail-closed surface.
    $w04Deferred = if ($w04BunPath) { @() } else { @($w04DeferredManifest.undeclaredRuntimeCases) }
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
    W15_DIFFERENTIAL_BACKEND = $(if ($W15PackageDifferential) { 'haifa' } else { $null })
    # W04 imagegen profile: host shell Tool on plus the isolated Skill directory. The recorded bun
    # directory joins the Gateway PATH below and the declared HAIFA_SUPER_HARNESS_BUN_PATH carries
    # the exact executable into the product configuration; neither goes through this shared table.
    HAIFA_SUPER_HARNESS_HOST_SHELL = $(if ($W04ImageGen) { 'on' } else { $null })
    HAIFA_SUPER_HARNESS_SKILLS_DIR = $(if ($W04ImageGen) { $w04.skillsDirectory } else { $null })
    HAIFA_SUPER_HARNESS_BUN_PATH = $(if ($W04ImageGen) { $w04.bunPath } else { $null })
    # The real codex-cli provider resolves bun and codex from the process PATH (the runner joins
    # both directories there); these BAOYU_* variables only redirect the wrapper's own idempotency
    # cache and JSONL log into this round's data root and evidence directory, so no user home is
    # written and no cached image from an earlier round can ever be returned.
    BAOYU_CODEX_IMAGEGEN_CACHE_DIR = $(if ($W04ImageGen) { Join-Path $dataRoot 'baoyu-codex-cache' } else { $null })
    BAOYU_CODEX_IMAGEGEN_LOG_FILE = $(if ($W04ImageGen) { Join-Path $evidenceRoot 'w04-codex-imagegen.jsonl' } else { $null })
    # W04 profile continuation key: external env reference (see the seed block above). The value
    # table is never persisted; the key itself is only tracked in the process environment.
    HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF = $(if ($W04ImageGen) { 'env://W04_IMAGEGEN_CONTINUATION_KEY' } else { $null })
    W04_IMAGEGEN_CONTINUATION_KEY = $(if ($W04ImageGen) { [Convert]::ToBase64String($w04KeyBytes) } else { $null })
    W18_MODEL_NAME = $(if ($LiveDeepSeek) { 'w18-deepseek-live' } else { 'w18-controlled' })
    W18_PLAYWRIGHT_SPEC = $(if ($W15PackageDifferential) {
        'w15-package-differential.spec.cjs'
    } elseif ($W15LiveSkill) {
        'w15-live-skill.spec.cjs'
    } elseif ($UxV1AgentSoul) {
        'ux-v1-agent-soul.spec.cjs'
    } elseif ($UxV1DefaultTools) {
        'ux-v1-default-tools-live.spec.cjs'
    } elseif ($W04ImageGen) {
        'w04-skill-imagegen.spec.cjs'
    } elseif ($W08LiveDelegation) {
        'w08-delegation-live.spec.cjs'
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
    } elseif ($W08Catalog) {
        'w08-subagent-catalog.spec.cjs'
    } elseif ($W08Delegation) {
        'w08-delegation-chat.spec.cjs'
    } elseif ($LiveRendering) {
        'w05-live-rendering.spec.cjs'
    } else {
        'w18-ui-smoke.spec.cjs'
    })
    W18_PLAYWRIGHT_TIMEOUT_MS = $(if ($UxV1DefaultTools -or $UxV1AgentSoul -or $W15LiveSkill -or $W04ImageGen) { '360000' } elseif ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics -or $W08Catalog -or $W08Delegation -or $LiveRendering) { '120000' } else { '60000' })
    W18_PLAYWRIGHT_TRACE = $(if ($LiveDeepSeek -or $ActiveReconnect -or $Interaction -or $DemoArtifact -or $TitleSuggestions -or $InputPolish -or $V2ModelSwitch -or $V2FileContext -or $V2Skill -or $V2Diagnostics -or $W08Catalog -or $W08Delegation -or $LiveRendering) { 'on' } else { 'retain-on-failure' })
    # Pass index handed to the spec; restored with the rest at the end.
    W18_PASS_INDEX = '1'
    # Kept disabled through the ordinary build/test gate. The runner enables it
    # only immediately before launching the dedicated guarded test process.
    W05_INTERACTION_ACCEPTANCE = $null
}
$savedEnvironment = @{}
$testExitCode = 1
$expectedCases = @()
$selectedCases = @()
$passesSummary = @()
$frontendBuild = 'NOT_RUN'
$providerProcess = $null
$serverBuild = $(if ($SkipBuild -or $SkipServerBuild) { 'REUSED' } else { 'NOT_RUN' })
$preflight = [ordered]@{ result = 'NOT_RUN'; assertions = 'NOT_RUN'; servicesStartedDuringPreflight = $false }
try {
    $selection = Resolve-SmokeCaseSelection -SpecPath (Join-Path $root "tests/ui/smoke/$($managedEnvironment.W18_PLAYWRIGHT_SPEC)") -CaseTitle $CaseTitle
    $expectedCases = $selection.All
    $selectedCases = $selection.Selected
    Assert-SmokeEnvironment -Environment $managedEnvironment -DataRoot $dataRoot -GatewayBaseUrl $internalGatewayBaseUrl `
        -Controlled (-not $LiveDeepSeek) -ContextWindow $controlledContextWindow
    $preflight.dependencies = Assert-SmokeDependencies -RepositoryRoot $root
    # Reject the complete pair before starting any process, including build commands.
    Assert-FixturePortsBindable -Ports @($GatewayPort, $FrontendPort) -EvidenceDirectory $evidenceRoot
    $sdk = Resolve-HaifaSdkSelection -RepositoryRoot $root
    $null = New-Item -ItemType Directory -Force -Path $dataRoot
    foreach ($name in $managedEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $managedEnvironment[$name], 'Process')
    }
    if ($W04ImageGen -and $w04.bunHome) {
        # Only ever a PATH prefix for this round; the save/restore loop below restores it. The
        # codex directory joins the prefix so the package's codex-cli provider can resolve `bun`
        # and its wrapper can resolve `codex` from the process PATH.
        $savedEnvironment['PATH'] = [Environment]::GetEnvironmentVariable('PATH', 'Process')
        $pathPrefix = $w04.bunHome
        if ($w04.codexHome) { $pathPrefix = "$($w04.codexHome);$pathPrefix" }
        [Environment]::SetEnvironmentVariable('PATH', "$pathPrefix;$($savedEnvironment['PATH'])", 'Process')
    }
    $profile = & (Join-Path $root 'scripts/frontend/verify-haifa-frontend-profile.ps1') `
        -RepositoryRoot $root `
        -SourceRepository $SourceRepository
    if ($LASTEXITCODE -ne 0) {
        throw 'W18 Haifa frontend profile verification failed.'
    }
    $profile | Set-Content -LiteralPath (Join-Path $evidenceRoot "profile-$stamp.json") -Encoding utf8
    # Warm-build checks are also preflight checks; no build or process has started.
    $reuseServerBuild = ($SkipBuild -or $SkipServerBuild) -and -not $Interaction
    if ($reuseServerBuild) {
        Assert-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
    }
    if ($SkipBuild) {
        Assert-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $internalGatewayBaseUrl
    }
    $preflight.result = 'PASS'
    $preflight.sdkSelection = $sdk.Status
    $preflight.haifaVersion = $sdk.HaifaVersion
    $preflight.selectedCases = $selectedCases
    $preflight.w04 = $(if ($W04ImageGen) { $w04 } else { $null })
    if ($PreflightOnly) {
        $preflight | ConvertTo-Json -Depth 5 | Write-Output
        return
    }
    Set-FixturePhase $fixture 'build'

    # The Interaction profile runs the Gateway from the test classpath, not the exec JAR.
    if (-not $SkipBuild) {
        $serverReusable = $false
        if (-not $SkipServerBuild -and -not $Interaction) {
            # Same source and SDK as the stamped JAR (for example the previous round or the full
            # gate): packaging again would only reproduce it.
            try {
                Assert-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
                $serverReusable = $true
                $serverBuild = 'REUSED'
            }
            catch {
                $serverReusable = $false
            }
        }
        if (-not $SkipServerBuild -and -not $serverReusable) {
            $serverBuild = 'BUILT'
        Remove-BuildStamp (Get-ServerBuildStampPath $root)
        Push-Location $root
        try {
            # Unit tests belong to the full gate (scripts/verify-full.ps1); the cold round only packages.
            & mvn.cmd -o -ntp -pl super-harness-server -am -DskipTests @(@($sdk.MavenArgument) -ne $null) package 2>&1 | Tee-Object -FilePath (Join-Path $evidenceRoot 'maven-build.raw.log')
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
            Write-ServerBuildStamp -RepositoryRoot $root -HaifaVersion $sdk.HaifaVersion
        }

        # The frozen frontend almost never changes: reuse this worktree's build when its stamp still
        # matches, else restore the shared cached build, and only run `pnpm build` when neither exists.
        try {
            Assert-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $internalGatewayBaseUrl
            $frontendBuild = 'REUSED'
        }
        catch {
            Remove-BuildStamp (Get-FrontendBuildStampPath $root)
            $buildCache = Join-Path $root 'scripts/lib/frontend_build_cache.py'
            $null = & python $buildCache restore --repo $root --gateway $internalGatewayBaseUrl
            if ($LASTEXITCODE -eq 0) {
                $frontendBuild = 'RESTORED'
            }
            else {
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
                $null = & python $buildCache store --repo $root --gateway $internalGatewayBaseUrl
                $frontendBuild = 'BUILT'
            }
            Write-FrontendBuildStamp -RepositoryRoot $root -InternalGatewayBaseUrl $internalGatewayBaseUrl
        }
    }
    else {
        $frontendBuild = 'REUSED'
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
    $providerProcess = $null
    if (-not $LiveDeepSeek) {
        $providerScript = Join-Path $root 'tests/ui/smoke/loopback-openai-provider.cjs'
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
        $gatewayArgs = @('-o', '-ntp', '-pl', 'super-harness-server', '-am', '-Dtest=InteractionAcceptanceServerTest', '-Dsurefire.failIfNoSpecifiedTests=false', '-DforkCount=1') + @(@($sdk.MavenArgument) -ne $null) + @('test')
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
                context_window = $controlledContextWindow
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
                context_window = $controlledContextWindow
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

    if ($Passes -gt 1) {
        # One service start, several independent Playwright processes. A failing pass does not
        # stop the later ones unless the service itself is no longer healthy (a pass that cannot
        # launch still only fails itself; readiness was already asserted before the loop).
        $allowedNotRun = @($(if ($DeferUxV1Web) { 'C web: web_search -> web_fetch without approval' }) + $w04Deferred)
        for ($passIndex = 1; $passIndex -le $Passes; $passIndex++) {
            $passDir = Join-Path $evidenceRoot "pass-$passIndex"
            $null = New-Item -ItemType Directory -Force -Path $passDir
            [Environment]::SetEnvironmentVariable('W18_PASS_INDEX', [string]$passIndex, 'Process')
            # Playwright's own report (results.json, artifacts/) goes to this pass's directory so
            # one pass cannot overwrite another. W18_EVIDENCE_DIR stays the round directory:
            # several specs read the Provider log that the runner writes there.
            [Environment]::SetEnvironmentVariable('W18_PLAYWRIGHT_OUTPUT_DIR', $passDir, 'Process')
            $passStartMs = $fixture.Watch.ElapsedMilliseconds
            $passExit = $null
            $passFailure = $null
            Push-Location $root
            try {
                $playwrightArguments = @((Join-Path $root 'frontend/node_modules/@playwright/test/cli.js'), 'test', '--config', 'tests/ui/smoke/playwright.config.cjs')
                if ($selection.Grep) { $playwrightArguments += @('--grep', $selection.Grep) }
                $playwright = Start-FixtureProcess -Fixture $fixture -Name "Playwright$passIndex" -FilePath (Get-Command node.exe).Source `
                    -ArgumentList $playwrightArguments `
                    -WorkingDirectory $root -StdoutPath (Join-Path $passDir 'playwright.raw.log') -StderrPath (Join-Path $passDir 'playwright.stderr.log')
                $passExit = Wait-FixtureExit -Entry $playwright `
                    -TimeoutSec ([Math]::Max(1, $selectedCases.Count) * ([int]$managedEnvironment.W18_PLAYWRIGHT_TIMEOUT_MS / 1000) + 60)
            }
            catch { $passFailure = $_.Exception.Message }
            finally { Pop-Location }
            try {
                Write-FixtureCaseResults -EvidenceDirectory $passDir -ExpectedCases $expectedCases
                $passCases = @(Get-Content -Raw (Join-Path $passDir 'case-results.json') | ConvertFrom-Json)
                Assert-SmokeCaseResults -Rows $passCases -SelectedCases $selectedCases -AllowedNotRun $allowedNotRun
                if ($passExit -ne 0) { throw "Playwright exit code $passExit." }
            }
            catch {
                if ($null -eq $passFailure) { $passFailure = $_.Exception.Message }
                if ($null -eq $fixture.FirstFailure) { $fixture.FirstFailure = [ordered]@{ phase = 'assertions'; message = $passFailure; pass = $passIndex } }
            }
            # The round's exit code must stay non-zero once any pass failed: a later green pass
            # must not make flow-health report testExitCode 0 for a failed repeated run.
            $passCode = if ($null -ne $passFailure -and ($null -eq $passExit -or $passExit -eq 0)) { 1 } else { $passExit }
            if ($passIndex -eq 1 -or $null -eq $fixture.TestExitCode -or $fixture.TestExitCode -eq 0) {
                $fixture.TestExitCode = $passCode
            }
            $passesSummary += [ordered]@{
                profile = $tag; pass = $passIndex
                assertionMs = $fixture.Watch.ElapsedMilliseconds - $passStartMs
                exitCode = $passExit; firstFailure = $passFailure
                caseResults = "pass-$passIndex/case-results.json"
            }
        }
        [Environment]::SetEnvironmentVariable('W18_PLAYWRIGHT_OUTPUT_DIR', $null, 'Process')
        [ordered]@{ root = $evidenceRoot; runs = @($passesSummary | ForEach-Object {
            [ordered]@{ profile = $_.profile; pass = $_.pass; directory = (Join-Path $evidenceRoot "pass-$($_.pass)")
                relativeDirectory = "pass-$($_.pass)"; caseResults = $_.caseResults
                assertionMs = $_.assertionMs; exitCode = $_.exitCode; firstFailure = $_.firstFailure } }) } |
            ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'ui-round-plan.json') -Encoding utf8
        $failedPasses = @($passesSummary | Where-Object { $_.exitCode -ne 0 -or $_.firstFailure })
        if ($failedPasses.Count) { throw "W18 UI smoke: $($failedPasses.Count) of $Passes passes failed." }
        [ordered]@{ passes = $Passes; results = @($passesSummary | ForEach-Object { [ordered]@{ pass = $_.pass; exitCode = $_.exitCode; firstFailure = $_.firstFailure; caseResults = $_.caseResults } }) } | ConvertTo-Json -Compress | Write-Output
    }
    else {
        Push-Location $root
        try {
            $playwrightArguments = @((Join-Path $root 'frontend/node_modules/@playwright/test/cli.js'), 'test', '--config', 'tests/ui/smoke/playwright.config.cjs')
            if ($selection.Grep) { $playwrightArguments += @('--grep', $selection.Grep) }
            $playwright = Start-FixtureProcess -Fixture $fixture -Name Playwright -FilePath (Get-Command node.exe).Source `
                -ArgumentList $playwrightArguments `
                -WorkingDirectory $root -StdoutPath (Join-Path $evidenceRoot 'playwright.raw.log') -StderrPath (Join-Path $evidenceRoot 'playwright.stderr.log')
            $testExitCode = Wait-FixtureExit -Entry $playwright `
                -TimeoutSec ([Math]::Max(1, $selectedCases.Count) * ([int]$managedEnvironment.W18_PLAYWRIGHT_TIMEOUT_MS / 1000) + 60)
            $fixture.TestExitCode = $testExitCode
            if ($testExitCode -ne 0) { throw "W18 UI smoke failed with exit code $testExitCode." }
            Write-FixtureCaseResults -EvidenceDirectory $evidenceRoot -ExpectedCases $expectedCases
            $actualCases = @(Get-Content -Raw (Join-Path $evidenceRoot 'case-results.json') | ConvertFrom-Json)
            $allowedNotRun = @($(if ($DeferUxV1Web) { 'C web: web_search -> web_fetch without approval' }) + $w04Deferred)
            Assert-SmokeCaseResults -Rows $actualCases -SelectedCases $selectedCases -AllowedNotRun $allowedNotRun
            [ordered]@{ selected = $selectedCases.Count; passed = @($actualCases | Where-Object result -eq 'PASS').Count
                notRun = @($actualCases | Where-Object result -eq 'NOT_RUN').Count; exitCode = $testExitCode
                rawLog = (Join-Path $evidenceRoot 'playwright.raw.log') } | ConvertTo-Json -Compress | Write-Output
        }
        finally {
            Pop-Location
        }
    }
}
catch {
    if ($preflight.result -ne 'PASS') { $preflight.result = 'FAIL' }
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
        $preflight | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'preflight.json') -Encoding utf8
        foreach ($name in $savedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
        Set-FixturePhase $fixture 'evidenceArchive'
        Write-FixtureFlowHealth $fixture -ExpectedCases $expectedCases -Extra @{
            acceptanceProfile = $tag; sdkSelection = $sdk.Status; haifaVersion = $sdk.HaifaVersion
            serverBuildReused = [bool]($SkipBuild -or $SkipServerBuild); webDeferred = [bool]$DeferUxV1Web
            frontendBuild = $frontendBuild
            serverBuild = $serverBuild
            dataRoot = $dataRoot
            executionScope = $(if ($PreflightOnly) { 'preflight-only' } elseif ($CaseTitle) { 'selected-case' } else { 'full-profile' })
            selectedCases = $selectedCases
            passes = $passesSummary
        }
        if ($Passes -gt 1) {
            # Merge the per-pass case results into the top-level file and record the passes array
            # in flow-health (already set from $passesSummary above when the file is valid JSON).
            & python -B (Join-Path $root 'scripts/lib/ui_round_plan.py') merge --root $evidenceRoot --runs (Join-Path $evidenceRoot 'ui-round-plan.json')
            if ($LASTEXITCODE -ne 0) { Write-Host 'run-w18-ui-smoke: repeated-pass evidence merge failed.' }
        }
        Write-Output "flowHealth=$evidenceRoot/flow-health.json"
    }
}
