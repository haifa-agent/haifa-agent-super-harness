[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$DeerFlowSourceRepository,
    [string]$MavenRepository,
    [string]$DataDirectory,
    [string]$ShpmDatabasePath,
    [string[]]$ModelIds,
    [string]$ManagedModelsPath,
    [string]$DecisionRecordPath,
    [string]$V2DecisionsPath,
    [int]$GatewayPort = 18018,
    [int]$FrontendPort = 33018,
    [int[]]$SubSlotGatewayPorts = @(20618, 30618, 40618),
    [int[]]$SubSlotFrontendPorts = @(25618, 35618, 45618),
    [int]$ProviderPort = 46618,
    [int]$L3McpPort = 8091,
    [string]$L3McpEndpoint,
    [string]$L3McpToolsFixture,
    [string]$L3McpSelectedTool,
    [string]$L3McpFactSpec,
    [string]$ContinuationKeyRef,
    [string]$OutputPath,
    [switch]$StaticOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'lib/v2-evidence-contract.ps1')

$FrozenCommit = 'e545c28ac301e6f9feae247c513d8ea8f83df294'

function Add-Check {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Checks,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Detail,
        [object]$Evidence
    )
    $allowed = @('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL', 'PENDING_HUMAN_DECISION')
    if ($allowed -notcontains $Status) { throw "Invalid doctor check status '$Status' for $Id" }
    $entry = [ordered]@{ id = $Id; status = $Status; detail = $Detail }
    if ($null -ne $Evidence) { $entry.evidence = $Evidence }
    $Checks.Add($entry)
}

function Get-DoctorSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$checks = [System.Collections.Generic.List[object]]::new()

# -- Decision record (read once; the doctor never guesses a path) -----------
$v2DecisionsFullPath = if ([string]::IsNullOrWhiteSpace($V2DecisionsPath)) {
    Join-Path $root 'contracts/customer-demo-v2/v2-m0-decisions.json'
}
elseif ([IO.Path]::IsPathRooted($V2DecisionsPath)) { $V2DecisionsPath }
else { Join-Path $root $V2DecisionsPath }
$v2Decisions = $null
$v2DecisionsError = $null
try { $v2Decisions = Read-V2Json $v2DecisionsFullPath }
catch { $v2DecisionsError = $_.Exception.Message }

# -- 1. Super Harness git SHA / dirty ---------------------------------------
$gitSha = (& git -C $root rev-parse HEAD 2>$null).Trim()
$gitBranch = (& git -C $root rev-parse --abbrev-ref HEAD 2>$null).Trim()
$gitDirty = @(& git -C $root status --porcelain 2>$null).Count -gt 0
if ($LASTEXITCODE -eq 0 -and $gitSha -match '^[0-9a-f]{40}$') {
    Add-Check $checks 'super-harness-git' 'PASS' "branch=$gitBranch; dirty=$gitDirty; sha=$gitSha" ([ordered]@{ sha = $gitSha; branch = $gitBranch; dirty = $gitDirty })
}
else {
    Add-Check $checks 'super-harness-git' 'FAIL' 'Cannot resolve the Super Harness Git SHA.' $null
}

# -- 2. V2 case/fixture baseline and evidence contract ----------------------
$contractPath = Get-V2ContractPath -RepositoryRoot $root
$instanceSchemaPath = Join-Path $root 'contracts/customer-demo-v2/schemas/v2-evidence-instance.schema.json'
$contractSchemaPath = Join-Path $root 'contracts/customer-demo-v2/schemas/v2-evidence-contract.schema.json'
$baselinePath = Join-Path $root 'contracts/customer-demo-v2/evidence/v2-m0-baseline.json'
$w00Verifier = Join-Path $root 'tests/customer-demo-v2/verify-v2-contract-oracle.ps1'
try {
    $contractInfo = Test-V2EvidenceContract -RepositoryRoot $root
    $csvSha = Get-DoctorSha256 (Join-Path $root 'contracts/customer-demo-v2/fixtures/customers.csv')
    $policySha = Get-DoctorSha256 (Join-Path $root 'contracts/customer-demo-v2/fixtures/reporting-policy.json')
    $baselineResult = Test-V2EvidenceInstance -RepositoryRoot $root -InstancePath $baselinePath
    $baselineOk = ($baselineResult.DeclaredDecision -eq 'NO_GO') -and (@($baselineResult.CaseResults.Values | Where-Object { $_.result -eq 'NOT_RUN' }).Count -eq $contractInfo.CaseCount)
    Add-Check $checks 'v2-case-baseline' 'PASS' "cases=$($contractInfo.CaseCount); stages=V2-M1..M6; customersSha=$csvSha; policySha=$policySha; w00Verifier=$([bool](Test-Path -LiteralPath $w00Verifier))" ([ordered]@{ oracleSha = $contractInfo.OracleSha; sourceIndexSha = $contractInfo.IndexSha; customersCsvSha256 = $csvSha; reportingPolicySha256 = $policySha; w00Verifier = (Test-Path -LiteralPath $w00Verifier) })
    Add-Check $checks 'evidence-contract' 'PASS' "contractSha=$($contractInfo.ContractSha); blankBaselineDecision=$($baselineResult.Decision); schemasPresent=$([bool]((Test-Path -LiteralPath $contractSchemaPath) -and (Test-Path -LiteralPath $instanceSchemaPath)))" ([ordered]@{ contractSha256 = $contractInfo.ContractSha; baselineDecision = $baselineResult.Decision; baselineNoGo = $baselineOk })
}
catch {
    Add-Check $checks 'v2-case-baseline' 'FAIL' $_.Exception.Message ([ordered]@{ contract = $contractPath })
    Add-Check $checks 'evidence-contract' 'FAIL' $_.Exception.Message ([ordered]@{ contract = $contractPath; baseline = $baselinePath })
}

# -- 3. Frozen DeerFlow/frontend SHA, lockfile and profile ------------------
try {
    $manifestPath = Join-Path $root 'third-party/deerflow/manifest.json'
    $manifest = Read-V2Json $manifestPath
    $lockfilePath = Join-Path $root 'frontend/pnpm-lock.yaml'
    $lockfileSha = if (Test-Path -LiteralPath $lockfilePath) { Get-DoctorSha256 $lockfilePath } else { $null }
    $manifestOk = ([string]$manifest.upstream.commit -eq $FrozenCommit) -and
        ([string]$manifest.configuration.deerFlowBackendFallbackAllowed -eq 'False') -and
        ([string]$manifest.build.packageManager -eq 'pnpm@10.26.2') -and
        ($null -ne $lockfileSha) -and ($lockfileSha -eq [string]$manifest.build.lockfileSha256)
    $sourceGateRan = $false
    $sourceGateResult = 'not-run'
    if (-not $StaticOnly -and -not [string]::IsNullOrWhiteSpace($DeerFlowSourceRepository) -and (Test-Path -LiteralPath $DeerFlowSourceRepository -PathType Container)) {
        $sourceGateRan = $true
        $gateOut = & (Join-Path $root 'scripts/verify-deerflow-frontend.ps1') -RepositoryRoot $root -SourceRepository $DeerFlowSourceRepository *>&1 | Out-String
        $sourceGateResult = if ($LASTEXITCODE -eq 0) { 'pass' } else { "fail: $($gateOut.Trim())" }
        $manifestOk = $manifestOk -and ($LASTEXITCODE -eq 0)
    }
    $manifestStatus = if ($manifestOk) { 'PASS' } else { 'FAIL' }
    Add-Check $checks 'frozen-upstream' $manifestStatus "commit=$($manifest.upstream.commit); fallback=$($manifest.configuration.deerFlowBackendFallbackAllowed); packageManager=$($manifest.build.packageManager); lockfileShaMatches=$($null -ne $lockfileSha -and $lockfileSha -eq [string]$manifest.build.lockfileSha256); sourceGateRan=$sourceGateRan; sourceGate=$sourceGateResult" ([ordered]@{ manifestCommit = [string]$manifest.upstream.commit; fallbackAllowed = $manifest.configuration.deerFlowBackendFallbackAllowed; packageManager = [string]$manifest.build.packageManager; lockfileSha256 = $lockfileSha; expectedLockfileSha256 = [string]$manifest.build.lockfileSha256; sourceGateRan = $sourceGateRan; sourceGate = $sourceGateResult })
}
catch {
    Add-Check $checks 'frozen-upstream' 'FAIL' $_.Exception.Message $null
}

# -- 4. Haifa Agent 0.1.2-SNAPSHOT BOM/JAR SHAs -----------------------------
if (-not [string]::IsNullOrWhiteSpace($MavenRepository) -and (Test-Path -LiteralPath $MavenRepository -PathType Container)) {
    try {
        $requiredSdkFiles = @(
            'io/haifa/haifa-agent-bom/0.1.2-SNAPSHOT/haifa-agent-bom-0.1.2-SNAPSHOT.pom',
            'io/haifa/haifa-agent-runtime-api/0.1.2-SNAPSHOT/haifa-agent-runtime-api-0.1.2-SNAPSHOT.jar',
            'io/haifa/haifa-agent-artifact/0.1.2-SNAPSHOT/haifa-agent-artifact-0.1.2-SNAPSHOT.jar'
        )
        $sdkHashes = [ordered]@{}
        $missingSdk = @()
        foreach ($relative in $requiredSdkFiles) {
            $file = Join-Path $MavenRepository $relative
            if (Test-Path -LiteralPath $file -PathType Leaf) { $sdkHashes[$relative] = Get-DoctorSha256 $file }
            else { $missingSdk += $relative }
        }
        if ($missingSdk.Count -gt 0) {
            Add-Check $checks 'haifa-sdk' 'FAIL' "Missing Haifa Agent 0.1.2-SNAPSHOT artifact(s): $($missingSdk -join ', ')" $sdkHashes
        }
        else {
            Add-Check $checks 'haifa-sdk' 'PASS' "version=0.1.2-SNAPSHOT; artifacts=$($requiredSdkFiles.Count); sdkCommit=record-per-round" $sdkHashes
        }
    }
    catch {
        Add-Check $checks 'haifa-sdk' 'FAIL' $_.Exception.Message $null
    }
}
else {
    Add-Check $checks 'haifa-sdk' 'NOT_RUN' 'No -MavenRepository provided (or the path does not exist). The doctor never guesses a user-level Maven repository.' $null
}

# -- 5. Toolchain availability ----------------------------------------------
$toolchain = [ordered]@{}
$pwshCmd = Get-Command pwsh -ErrorAction SilentlyContinue
$toolchain.pwsh = if ($pwshCmd) { $PSVersionTable.PSVersion.ToString() } else { $null }
$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
$toolchain.maven = if ($mvnCmd) { ((& mvn -v 2>$null) | Select-Object -First 1) } else { $null }
$nodeCmd = Get-Command node -ErrorAction SilentlyContinue
$toolchain.node = if ($nodeCmd) { (& node --version 2>$null) } else { $null }
$corepackCmd = Get-Command corepack -ErrorAction SilentlyContinue
$toolchain.corepack = if ($corepackCmd) { (& corepack --version 2>$null) } else { $null }
$playwrightCmdPath = Join-Path $root 'frontend/node_modules/.bin/playwright.cmd'
$toolchain.playwright = if (Test-Path -LiteralPath $playwrightCmdPath -PathType Leaf) { 'present' } else { $null }
$toolchain.pwshOk = ($null -ne $toolchain.pwsh) -and ([version]$toolchain.pwsh).Major -ge 7
$toolchain.mavenOk = -not [string]::IsNullOrWhiteSpace($toolchain.maven)
$toolchain.nodeOk = ($null -ne $toolchain.node) -and ([int]($toolchain.node.TrimStart('v').Split('.')[0]) -ge 22)
$toolchain.corepackOk = -not [string]::IsNullOrWhiteSpace($toolchain.corepack)
$toolchain.playwrightOk = -not [string]::IsNullOrWhiteSpace($toolchain.playwright)
if ($toolchain.pwshOk -and $toolchain.mavenOk -and $toolchain.nodeOk -and $toolchain.corepackOk -and $toolchain.playwrightOk) {
    Add-Check $checks 'toolchain' 'PASS' "pwsh=$($toolchain.pwsh); mvn=$($toolchain.maven); node=$($toolchain.node); corepack=$($toolchain.corepack); playwright=$($toolchain.playwright)" $toolchain
}
else {
    Add-Check $checks 'toolchain' 'NOT_RUN' "Missing toolchain component(s). pwshOk=$($toolchain.pwshOk); mavenOk=$($toolchain.mavenOk); nodeOk=$($toolchain.nodeOk); corepackOk=$($toolchain.corepackOk); playwrightOk=$($toolchain.playwrightOk)" $toolchain
}

# -- 6. Ports: NAT exclusions and per-port bind -----------------------------
if ($StaticOnly) {
    Add-Check $checks 'ports' 'NOT_RUN' 'StaticOnly was requested; NAT ranges and loopback binds were not read.' $null
}
else {
    try {
        $dynamicText = (netsh interface ipv4 show dynamicport tcp 2>&1 | Out-String).Trim()
        $excludedText = (netsh interface ipv4 show excludedportrange protocol=tcp 2>&1 | Out-String).Trim()
        $allPorts = @($GatewayPort, $FrontendPort) + @($SubSlotGatewayPorts) + @($SubSlotFrontendPorts) + @($ProviderPort, $L3McpPort) | Select-Object -Unique
        $mainPorts = @($GatewayPort, $FrontendPort)
        $bindResults = @()
        $natReserved = @()
        $occupied = @()
        foreach ($port in $allPorts) {
            $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
            if ($listeners.Count -gt 0) {
                $pid0 = $listeners[0].OwningProcess
                $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pid0" -ErrorAction SilentlyContinue
                $owner = if ($proc) { $proc.CommandLine } else { $null }
                $isSuperHarness = ($null -ne $owner) -and ($owner -like "*$root*" -or $owner -like '*super-harness*')
                $entry = [ordered]@{ port = $port; state = 'listening'; pid = $pid0; superHarness = $isSuperHarness }
                if ($mainPorts -contains $port) { $entry.role = 'main' }
                # The human-authorized L3 Pilot MCP is expected to listen on its own port; it is
                # recognized only by the process marker committed in the decision record.
                $l3Marker = [string](Get-V2Prop (Get-V2Prop (Get-V2Prop $v2Decisions 'decisions') 'l3McpSelection') 'processMarker')
                $isAuthorizedL3 = ($port -eq $L3McpPort) -and ($null -ne $owner) -and
                    -not [string]::IsNullOrWhiteSpace($l3Marker) -and $owner.Contains($l3Marker)
                if ($isAuthorizedL3) { $entry.role = 'l3-mcp'; $entry.l3ProcessMarker = $l3Marker }
                $bindResults += $entry
                if (-not $isSuperHarness -and -not $isAuthorizedL3) { $occupied += $port }
                continue
            }
            try {
                $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
                $listener.Start()
                $listener.Stop()
                $bindResults += [ordered]@{ port = $port; state = 'free' }
            }
            catch {
                $bindResults += [ordered]@{ port = $port; state = 'bind-failed'; error = $_.Exception.Message }
                $natReserved += $port
            }
        }
        if ($natReserved.Count -gt 0) {
            Add-Check $checks 'ports' 'FAIL' "Loopback bind failed (possible NAT/Hyper-V reservation) on: $($natReserved -join ', ')" ([ordered]@{ dynamicPort = $dynamicText; excluded = $excludedText; binds = $bindResults })
        }
        elseif ($occupied.Count -gt 0) {
            Add-Check $checks 'ports' 'NOT_RUN' "Ports held by a non-Super-Harness process: $($occupied -join ', ')" ([ordered]@{ dynamicPort = $dynamicText; excluded = $excludedText; binds = $bindResults })
        }
        else {
            Add-Check $checks 'ports' 'PASS' "All required ports bindable (main ports listening are accepted); dynamic range and exclusions captured." ([ordered]@{ dynamicPort = $dynamicText; excluded = $excludedText; binds = $bindResults })
        }
    }
    catch {
        Add-Check $checks 'ports' 'FAIL' $_.Exception.Message $null
    }
}

# -- 7. Independent data-root ------------------------------------------------
if (-not [string]::IsNullOrWhiteSpace($DataDirectory)) {
    $dataRootFull = [IO.Path]::GetFullPath($DataDirectory)
    if (Test-Path -LiteralPath $dataRootFull -PathType Container) {
        $runtimeDb = Join-Path $dataRootFull 'runtime/haifa-agent.sqlite'
        Add-Check $checks 'data-root' 'PASS' "dataRoot=$dataRootFull; runtimeDatabasePresent=$([bool](Test-Path -LiteralPath $runtimeDb))" ([ordered]@{ dataRoot = $dataRootFull; runtimeDatabase = (Test-Path -LiteralPath $runtimeDb) })
    }
    else {
        Add-Check $checks 'data-root' 'NOT_RUN' "Provided -DataDirectory does not exist: $dataRootFull" ([ordered]@{ dataRoot = $dataRootFull })
    }
}
else {
    Add-Check $checks 'data-root' 'NOT_RUN' 'No -DataDirectory provided; the doctor never falls back to a default main data root.' $null
}

# -- 8. Continuation-key reference existence --------------------------------
if ($StaticOnly -or [string]::IsNullOrWhiteSpace($ContinuationKeyRef)) {
    Add-Check $checks 'continuation-key' 'NOT_RUN' 'StaticOnly or no -ContinuationKeyRef provided; credential reference existence was not checked.' $null
}
else {
    try {
        $dbExists = -not [string]::IsNullOrWhiteSpace($DataDirectory) -and (Test-Path -LiteralPath (Join-Path ([IO.Path]::GetFullPath($DataDirectory)) 'runtime/haifa-agent.sqlite'))
        if ($ContinuationKeyRef -match '^env://(.+)$') {
            $name = $Matches[1]
            $exists = $null -ne [Environment]::GetEnvironmentVariable($name)
            if ($exists) { Add-Check $checks 'continuation-key' 'PASS' "env reference exists; value not read" ([ordered]@{ reference = $ContinuationKeyRef; exists = $true }) }
            elseif ($dbExists) { Add-Check $checks 'continuation-key' 'FAIL' "env reference is missing while the Runtime database exists; startup must fail closed" ([ordered]@{ reference = $ContinuationKeyRef; exists = $false; databasePresent = $true }) }
            else { Add-Check $checks 'continuation-key' 'NOT_RUN' "env reference is missing and no Runtime database exists yet" ([ordered]@{ reference = $ContinuationKeyRef; exists = $false; databasePresent = $false }) }
        }
        elseif ($ContinuationKeyRef -match '^os://(.+)$') {
            $target = $Matches[1]
            $cmdkey = (& cmdkey /list 2>$null | Out-String)
            $exists = $cmdkey -match [regex]::Escape($target)
            if ($exists) { Add-Check $checks 'continuation-key' 'PASS' "OS credential target exists; secret not read" ([ordered]@{ reference = $ContinuationKeyRef; exists = $true }) }
            elseif ($dbExists) { Add-Check $checks 'continuation-key' 'FAIL' "OS credential target is missing while the Runtime database exists; startup must fail closed" ([ordered]@{ reference = $ContinuationKeyRef; exists = $false; databasePresent = $true }) }
            else { Add-Check $checks 'continuation-key' 'NOT_RUN' "OS credential target is missing and no Runtime database exists yet" ([ordered]@{ reference = $ContinuationKeyRef; exists = $false; databasePresent = $false }) }
        }
        else {
            Add-Check $checks 'continuation-key' 'NOT_RUN' "Unrecognized -ContinuationKeyRef scheme; expected os://NAME or env://NAME" ([ordered]@{ reference = $ContinuationKeyRef })
        }
    }
    catch {
        Add-Check $checks 'continuation-key' 'FAIL' $_.Exception.Message $null
    }
}

# -- 9. Live managed model + credential reference ---------------------------
function Get-ManagedModels {
    param([string]$ConfigPath, [int]$Port)
    $models = $null
    if (-not [string]::IsNullOrWhiteSpace($ConfigPath) -and (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        $raw = Read-V2Json $ConfigPath
        $candidate = Get-V2Prop $raw 'models'
        if ($null -eq $candidate) { $candidate = $raw }
        $models = @($candidate)
    }
    elseif (-not $StaticOnly) {
        try {
            $response = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/api/managed-models" -TimeoutSec 10
            $candidate = Get-V2Prop $response 'models'
            if ($null -ne $candidate) { $models = @($candidate) }
        }
        catch { $models = $null }
    }
    return $models
}
$managed = Get-ManagedModels -ConfigPath $ManagedModelsPath -Port $GatewayPort
if ($null -eq $managed -or @($managed).Count -eq 0) {
    Add-Check $checks 'live-model' 'NOT_RUN' 'No managed-model source available (no -ManagedModelsPath and no reachable local Gateway /api/managed-models). No model was called.' $null
}
else {
    $enabled = @($managed | Where-Object { $null -eq (Get-V2Prop $_ 'enabled') -or (Get-V2Prop $_ 'enabled') -eq $true })
    $available = @($enabled | ForEach-Object {
        $id = Get-V2Prop $_ 'id'; if (-not $id) { $id = Get-V2Prop $_ 'name' }; if (-not $id) { $id = Get-V2Prop $_ 'model' }
        $cred = Get-V2Prop $_ 'credentialSource'; if (-not $cred) { $cred = Get-V2Prop $_ 'credential_source' }
        $hasCred = ($null -ne $cred) -or ((Get-V2Prop $_ 'has_credential') -eq $true) -or ($null -ne (Get-V2Prop $_ 'credentialRef'))
        [pscustomobject]@{ id = [string]$id; hasCredentialReference = [bool]$hasCred }
    })
    $availableCount = @($available | Where-Object { $_.hasCredentialReference }).Count
    if (@($ModelIds).Count -eq 0) {
        Add-Check $checks 'live-model' 'NOT_RUN' "No -ModelIds requested; available live models with credential references=$availableCount" ([ordered]@{ availableCount = $availableCount; models = @($available) })
    }
    else {
        $missing = @($ModelIds | Where-Object { $id = $_; @($available | Where-Object { $_.id -eq $id }).Count -eq 0 })
        $noCred = @($ModelIds | Where-Object { $id = $_; @($available | Where-Object { $_.id -eq $id -and $_.hasCredentialReference }).Count -eq 0 })
        if ($missing.Count -gt 0) { Add-Check $checks 'live-model' 'FAIL' "Requested model(s) not configured: $($missing -join ', ')" ([ordered]@{ availableCount = $availableCount; models = @($available) }) }
        elseif ($noCred.Count -gt 0) { Add-Check $checks 'live-model' 'NOT_RUN' "Requested model(s) exist but no credential reference detected: $($noCred -join ', ')" ([ordered]@{ availableCount = $availableCount; models = @($available) }) }
        else { Add-Check $checks 'live-model' 'PASS' "Requested model(s)=$($ModelIds -join ', '); availableWithCredentialRef=$availableCount" ([ordered]@{ availableCount = $availableCount; models = @($available) }) }
    }
}

# -- 10. L2 MCP transport ----------------------------------------------------
Add-Check $checks 'l2-mcp-transport' 'NOT_RUN' 'W10 minimal MCP client is not delivered; there is no real L2 HTTP MCP transport to discover. A fixture JSON is not a transport and must not be reported as one.' $null

# -- 11. L3 Pilot MCP discovery (initialize + tools/list only) --------------
function Send-L3Initialize {
    param([string]$Endpoint)
    $headers = @{ 'Accept' = 'application/json, text/event-stream'; 'MCP-Protocol-Version' = '2025-06-18' }
    $body = @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{ protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'w24-v2-doctor'; version = '1.0' } } } | ConvertTo-Json -Depth 10
    $response = Invoke-WebRequest -Method Post -Uri $Endpoint -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
    $session = $response.Headers['Mcp-Session-Id']
    $payload = $response.Content
    if ([string]$response.Headers['Content-Type'] -match 'text/event-stream') {
        $dataLines = @($payload -split "`n" | Where-Object { $_ -like 'data:*' } | ForEach-Object { $_.Substring(5).Trim() } | Where-Object { $_ })
        if ($dataLines.Count -gt 0) { $payload = $dataLines[-1] }
    }
    $parsed = $payload | ConvertFrom-Json -Depth 40
    return [pscustomobject]@{ Parsed = $parsed; SessionId = $session }
}
function Send-L3ToolsList {
    param([string]$Endpoint, [string]$SessionId)
    $headers = @{ 'Accept' = 'application/json, text/event-stream'; 'MCP-Protocol-Version' = '2025-06-18' }
    if ($SessionId) { $headers['Mcp-Session-Id'] = $SessionId }
    $body = @{ jsonrpc = '2.0'; id = 2; method = 'tools/list'; params = @{} } | ConvertTo-Json -Depth 10
    $response = Invoke-WebRequest -Method Post -Uri $Endpoint -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
    $payload = $response.Content
    if ([string]$response.Headers['Content-Type'] -match 'text/event-stream') {
        $dataLines = @($payload -split "`n" | Where-Object { $_ -like 'data:*' } | ForEach-Object { $_.Substring(5).Trim() } | Where-Object { $_ })
        if ($dataLines.Count -gt 0) { $payload = $dataLines[-1] }
    }
    return ($payload | ConvertFrom-Json -Depth 40)
}

$l3Tools = @()
$l3ServerInfo = $null
$l3ProtocolVersion = $null
$l3CredentialRef = 'none-local-profile'
$l3DiscoveryStatus = 'NOT_RUN'
$l3DiscoveryDetail = 'No -L3McpEndpoint or -L3McpToolsFixture provided; the human/master must supply the authorized read-only Pilot MCP.'
$l3ToolsCallTrace = $false

if (-not [string]::IsNullOrWhiteSpace($L3McpToolsFixture) -and (Test-Path -LiteralPath $L3McpToolsFixture -PathType Leaf)) {
    try {
        $fixtureRaw = Get-Content -Raw -LiteralPath $L3McpToolsFixture
        if ($fixtureRaw -match 'tools/call') { $l3ToolsCallTrace = $true }
        $fixture = $fixtureRaw | ConvertFrom-Json -Depth 40
        $serverInfo = Get-V2Prop $fixture 'result.serverInfo'
        if ($null -ne $serverInfo) { $l3ServerInfo = $serverInfo }
        $protocol = Get-V2Prop $fixture 'result.protocolVersion'
        if ($null -ne $protocol) { $l3ProtocolVersion = [string]$protocol }
        $tools = @((Get-V2Prop $fixture 'result.tools'))
        $l3Tools = @($tools | Where-Object { $null -ne $_ } | ForEach-Object { [pscustomobject]@{ name = [string](Get-V2Prop $_ 'name'); readOnlyHint = Get-V2Prop $_ 'annotations.readOnlyHint' } })
        $l3DiscoveryDetail = "Offline tools/list fixture only (no live initialize): tools=$($l3Tools.Count); credentialRef=$l3CredentialRef"
        if ($l3ToolsCallTrace) { $l3DiscoveryStatus = 'FAIL'; $l3DiscoveryDetail = 'tools/call trace found in the L3 fixture; only initialize/tools-list are authorized.' }
        else { $l3DiscoveryStatus = 'NOT_RUN' }
    }
    catch {
        $l3DiscoveryStatus = 'FAIL'
        $l3DiscoveryDetail = "Failed to parse the L3 tools fixture: $($_.Exception.Message)"
    }
}
if (-not [string]::IsNullOrWhiteSpace($L3McpEndpoint)) {
    if ($StaticOnly) {
        if ($l3DiscoveryStatus -ne 'FAIL') { $l3DiscoveryStatus = 'NOT_RUN'; $l3DiscoveryDetail = 'StaticOnly was requested; the live L3 endpoint was not probed.' }
    }
    else {
        try {
            $init = Send-L3Initialize -Endpoint $L3McpEndpoint
            $l3ServerInfo = Get-V2Prop $init.Parsed 'result.serverInfo'
            $l3ProtocolVersion = [string](Get-V2Prop $init.Parsed 'result.protocolVersion')
            $listResponse = Send-L3ToolsList -Endpoint $L3McpEndpoint -SessionId $init.SessionId
            $tools = @((Get-V2Prop $listResponse 'result.tools'))
            $l3Tools = @($tools | Where-Object { $null -ne $_ } | ForEach-Object { [pscustomobject]@{ name = [string](Get-V2Prop $_ 'name'); readOnlyHint = Get-V2Prop $_ 'annotations.readOnlyHint' } })
            $l3DiscoveryStatus = 'PASS'
            $l3DiscoveryDetail = "Live discovery: tools=$($l3Tools.Count); protocol=$l3ProtocolVersion; credentialRef=$l3CredentialRef"
        }
        catch {
            $l3DiscoveryStatus = 'BLOCKED_EXTERNAL'
            $l3DiscoveryDetail = "L3 MCP endpoint unreachable or refused initialize/tools-list: $($_.Exception.Message)"
        }
    }
}
if ($l3ToolsCallTrace) { $l3DiscoveryStatus = 'FAIL' }
Add-Check $checks 'l3-mcp-discovery' $l3DiscoveryStatus $l3DiscoveryDetail ([ordered]@{ endpoint = $L3McpEndpoint; fixture = $L3McpToolsFixture; serverInfo = $l3ServerInfo; protocolVersion = $l3ProtocolVersion; toolCount = $l3Tools.Count; tools = @($l3Tools); credentialRef = $l3CredentialRef; toolsCallTrace = $l3ToolsCallTrace })

# -- 12. L3 selected tool / reference fact decision -------------------------
# The selection is only valid against a committed human decision. The doctor
# compares explicit parameters against that record and, when they are omitted,
# validates the recorded values themselves. It never accepts an arbitrary
# read-only tool.
$l3SelectionRecord = Get-V2Prop $v2Decisions.decisions 'l3McpSelection'
$recordedL3Tool = [string](Get-V2Prop $l3SelectionRecord 'tool')
$recordedL3Fact = [string](Get-V2Prop $l3SelectionRecord 'factSpec')
if (($null -eq $l3SelectionRecord) -or [string]::IsNullOrWhiteSpace($recordedL3Tool) -or [string]::IsNullOrWhiteSpace($recordedL3Fact)) {
    Add-Check $checks 'l3-mcp-selection' 'PENDING_HUMAN_DECISION' "No committed l3McpSelection record (tool + factSpec) at $v2DecisionsFullPath; the tool and reference fact must be authorized by the human before the doctor can PASS." ([ordered]@{ decisionsPath = $v2DecisionsFullPath; record = $l3SelectionRecord })
}
else {
    $toolParamSupplied = -not [string]::IsNullOrWhiteSpace($L3McpSelectedTool)
    $factParamSupplied = -not [string]::IsNullOrWhiteSpace($L3McpFactSpec)
    $effectiveTool = if ($toolParamSupplied) { $L3McpSelectedTool } else { $recordedL3Tool }
    $effectiveFact = if ($factParamSupplied) { $L3McpFactSpec } else { $recordedL3Fact }
    $selectionEvidence = [ordered]@{
        decisionsPath = $v2DecisionsFullPath; recordedTool = $recordedL3Tool; recordedFactSpec = $recordedL3Fact
        effectiveTool = $effectiveTool; effectiveFactSpec = $effectiveFact
        toolParamSupplied = $toolParamSupplied; factParamSupplied = $factParamSupplied
        discoveryStatus = $l3DiscoveryStatus; discoveredToolCount = @($l3Tools).Count
    }
    if ($toolParamSupplied -and ([string]$L3McpSelectedTool -ne $recordedL3Tool)) {
        Add-Check $checks 'l3-mcp-selection' 'FAIL' "Provided -L3McpSelectedTool '$L3McpSelectedTool' does not match the human-authorized tool '$recordedL3Tool'." $selectionEvidence
    }
    elseif ($factParamSupplied -and ([string]$L3McpFactSpec -ne $recordedL3Fact)) {
        Add-Check $checks 'l3-mcp-selection' 'FAIL' "Provided -L3McpFactSpec '$L3McpFactSpec' does not match the human-authorized factSpec '$recordedL3Fact'." $selectionEvidence
    }
    elseif (@($l3Tools).Count -eq 0) {
        Add-Check $checks 'l3-mcp-selection' 'NOT_RUN' "Authorized tool '$effectiveTool' cannot be confirmed: no L3 discovery result (live tools/list or -L3McpToolsFixture) is available, and the doctor never infers tool existence." $selectionEvidence
    }
    else {
        $match = @($l3Tools | Where-Object { $_.name -eq $effectiveTool })
        if ($match.Count -eq 0) {
            Add-Check $checks 'l3-mcp-selection' 'FAIL' "Authorized tool '$effectiveTool' was not found in the discovered read-only tools." $selectionEvidence
        }
        elseif ($match[0].readOnlyHint -ne $true) {
            Add-Check $checks 'l3-mcp-selection' 'FAIL' "Authorized tool '$effectiveTool' is not readOnlyHint=true." $selectionEvidence
        }
        else {
            Add-Check $checks 'l3-mcp-selection' 'PASS' "Authorized tool '$effectiveTool' matches the decision record and is discovered with readOnlyHint=true; factSpec='$effectiveFact'." $selectionEvidence
        }
    }
}

# -- 13. Human/master decisions (W04, W03/W05) ------------------------------
if ($null -eq $v2Decisions) {
    Add-Check $checks 'w04-authorization' 'FAIL' "Cannot read the committed decision record at ${v2DecisionsFullPath}: $v2DecisionsError" ([ordered]@{ decisionsPath = $v2DecisionsFullPath })
    Add-Check $checks 'w03-w05-order' 'FAIL' "Cannot read the committed decision record at ${v2DecisionsFullPath}: $v2DecisionsError" ([ordered]@{ decisionsPath = $v2DecisionsFullPath })
}
else {
    $w04 = Get-V2Prop $v2Decisions.decisions 'w04RecoveryAuthorization'
    $w04Decision = [string](Get-V2Prop $w04 'decision')
    $w04Evidence = [ordered]@{
        decisionsPath = $v2DecisionsFullPath; decision = $w04Decision
        authority = [string](Get-V2Prop $w04 'authority'); recordedOn = [string](Get-V2Prop $w04 'recordedOn')
        supersedes = Get-V2Prop $w04 'supersedes'
    }
    if ($w04Decision -eq 'AUTHORIZED') {
        Add-Check $checks 'w04-authorization' 'PASS' "Human-authorized bounded W04 file-context slice (recorded $($w04.recordedOn)); scope: only the two proven upload FAILs may be fixed, Project Delete/Trash P0 is untouched, and this slice PASS is not a W04 whole-package PASS; supersedes the earlier NOT_AUTHORIZED record." $w04Evidence
    }
    elseif ($w04Decision -eq 'NOT_AUTHORIZED') {
        Add-Check $checks 'w04-authorization' 'BLOCKED_EXTERNAL' "Human decision $($w04.recordedOn): the bounded W04 slice is NOT authorized; dependent V2-M1+ stages are BLOCKED, not a doctor FAIL." $w04Evidence
    }
    else {
        Add-Check $checks 'w04-authorization' 'FAIL' "Missing or unrecognized W04 recovery authorization decision '$w04Decision'; expected AUTHORIZED or NOT_AUTHORIZED." $w04Evidence
    }

    $w03w05 = Get-V2Prop $v2Decisions.decisions 'w03W05Ordering'
    if ($null -eq $w03w05 -or [string]::IsNullOrWhiteSpace([string](Get-V2Prop $w03w05 'decision'))) {
        Add-Check $checks 'w03-w05-order' 'FAIL' "Missing w03W05Ordering decision in $v2DecisionsFullPath." ([ordered]@{ decisionsPath = $v2DecisionsFullPath })
    }
    else {
        Add-Check $checks 'w03-w05-order' 'PASS' "decision=$($w03w05.decision); parallel=$($w03w05.parallel); reviewedMainSha=$($w03w05.reviewedMainSha)" ([ordered]@{ decision = [string]$w03w05.decision; parallel = $w03w05.parallel; reviewedMainSha = [string]$w03w05.reviewedMainSha })
    }
}
if (-not [string]::IsNullOrWhiteSpace($DecisionRecordPath) -and (Test-Path -LiteralPath $DecisionRecordPath -PathType Leaf)) {
    $readmeText = Get-Content -Raw -LiteralPath $DecisionRecordPath
    $crossOk = ($readmeText -match '授权恢复 W04 受界切片') -and ($readmeText -match 'W05 task-3') -and ($readmeText -match 'calculate')
    $crossStatus = if ($crossOk) { 'PASS' } else { 'FAIL' }
    Add-Check $checks 'decision-record-crosscheck' $crossStatus "Cross-checked the provided roadmap README against the committed decisions file." ([ordered]@{ decisionRecord = $DecisionRecordPath; crossCheckOk = $crossOk })
}

# -- 14. W16 consulting-analysis dependency review --------------------------
try {
    $review = Read-V2Json (Join-Path $root 'contracts/customer-demo-v2/consulting-analysis-dependency-review.json')
    $ok = ([string]$review.verdict -eq 'NO_HARD_DEPENDENCY_ON_DEFERRED_CAPABILITIES') -and ($review.hardDependencyFound -eq $false) -and ($review.stopImplementation -eq $false) -and ($review.escalationRequired -eq $false)
    $reviewStatus = if ($ok) { 'PASS' } else { 'FAIL' }
    Add-Check $checks 'w16-dependency-review' $reviewStatus "verdict=$($review.verdict); hardDependencyFound=$($review.hardDependencyFound); stopImplementation=$($review.stopImplementation); escalationRequired=$($review.escalationRequired)" ([ordered]@{ verdict = [string]$review.verdict })
}
catch {
    Add-Check $checks 'w16-dependency-review' 'FAIL' $_.Exception.Message $null
}

# -- 15. SHPM database path (read-only existence) ---------------------------
if (-not [string]::IsNullOrWhiteSpace($ShpmDatabasePath)) {
    if (Test-Path -LiteralPath $ShpmDatabasePath -PathType Leaf) {
        Add-Check $checks 'shpm-database' 'PASS' 'SHPM database exists; existence only, never opened or written by this doctor.' ([ordered]@{ path = $ShpmDatabasePath })
    }
    else {
        Add-Check $checks 'shpm-database' 'NOT_RUN' 'Provided -ShpmDatabasePath does not exist; no database is created.' ([ordered]@{ path = $ShpmDatabasePath })
    }
}
else {
    Add-Check $checks 'shpm-database' 'NOT_RUN' 'No -ShpmDatabasePath provided; the doctor never opens or creates the SHPM database.' $null
}

# -- Overall ----------------------------------------------------------------
$overall = 'PASS'
if (@($checks | Where-Object status -eq 'FAIL').Count -gt 0) { $overall = 'FAIL' }
elseif (@($checks | Where-Object status -eq 'BLOCKED_EXTERNAL').Count -gt 0) { $overall = 'BLOCKED_EXTERNAL' }
elseif (@($checks | Where-Object status -eq 'PENDING_HUMAN_DECISION').Count -gt 0) { $overall = 'NOT_RUN' }
elseif (@($checks | Where-Object status -eq 'NOT_RUN').Count -gt 0) { $overall = 'NOT_RUN' }

$report = [ordered]@{
    schemaVersion    = 'haifa.customer-demo-v2.doctor-report/v1'
    generatedAt      = (Get-Date).ToString('o')
    demoId           = 'DEMO-V2'
    mode             = if ($StaticOnly) { 'static' } else { 'live-read-only' }
    overall          = $overall
    paidModelCalled  = $false
    superHarnessSha  = $gitSha
    superHarnessDirty = $gitDirty
    frozenDeerFlowSha = $FrozenCommit
    checks           = $checks
}
$json = $report | ConvertTo-Json -Depth 30
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $fullOutput = [IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullOutput
    if ($parent) { $null = New-Item -ItemType Directory -Force -Path $parent }
    [IO.File]::WriteAllText($fullOutput, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
$json
