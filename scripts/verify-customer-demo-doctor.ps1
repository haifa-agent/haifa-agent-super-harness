[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRepository,
    [string]$PlanPath,
    [string]$DataDirectory,
    [string]$MavenRepository = 'D:\DevCache\maven\repository',
    [string]$ModelId = 'main-deepseek',
    [int]$GatewayPort = 18018,
    [int]$FrontendPort = 33018,
    [string]$OutputPath,
    [switch]$StaticOnly,
    [switch]$BudgetApproved
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-Doctor {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Add-Check {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Checks,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Detail
    )
    $Checks.Add([ordered]@{ id = $Id; status = $Status; detail = $Detail })
}

function Invoke-JsonGet {
    param([Parameter(Mandatory = $true)][string]$Uri)
    return Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 10
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = Join-Path $root 'deer-flow'
}
if ([string]::IsNullOrWhiteSpace($PlanPath)) {
    $PlanPath = Join-Path $root 'contracts/customer-demo/customer-demo-v0-oracle.json'
}
if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $root 'local-tmp/main-service-data'
}

$planFile = (Resolve-Path -LiteralPath $PlanPath).Path
$planText = Get-Content -Raw -LiteralPath $planFile
$plan = $planText | ConvertFrom-Json -Depth 20
$checks = [System.Collections.Generic.List[object]]::new()
$expectedSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
$expectedCases = @(
    'run-created', 'approval-pending', 'reload-reconnect', 'approval-exactly-once',
    'artifact-download', 'active-steer', 'terminal', 'cleanup'
)

Assert-Doctor ($plan.schemaVersion -ceq 'haifa.customer-demo.oracle/v1') 'Unexpected customer demo oracle schema.'
Assert-Doctor ($plan.demoId -ceq 'DEMO-V0') 'Customer demo oracle must target DEMO-V0.'
Assert-Doctor ($plan.frozenFrontend.commit -ceq $expectedSha) 'Customer demo oracle drifted from the frozen DeerFlow commit.'
Assert-Doctor ($plan.frozenFrontend.fallbackAllowed -eq $false) 'Customer demo oracle must forbid DeerFlow backend fallback.'
Assert-Doctor ([int]$plan.runtime.gatewayPort -eq 18018 -and [int]$plan.runtime.frontendPort -eq 33018) 'Customer demo oracle must use the fixed main ports.'
Assert-Doctor ($plan.runtime.sdkVersion -ceq '0.1.2-SNAPSHOT') 'Customer demo oracle must consume Haifa Agent 0.1.2-SNAPSHOT.'
Assert-Doctor ($plan.model.id -ceq $ModelId) "Customer demo model id must be $ModelId."
Assert-Doctor ($plan.model.credentialSource -ceq 'os') 'Customer demo model credential source must be os.'
Assert-Doctor ($plan.budget.authorizationRequired -eq $true -and $plan.budget.paidRunAllowedByThisFile -eq $false) 'The oracle must not authorize a paid model run.'
Assert-Doctor ([int]$plan.story.rounds -eq 2) 'Customer demo must contain exactly two consecutive rounds.'
Assert-Doctor ($plan.story.tool -ceq 'write_file') 'Customer demo must use the production write_file tool.'
Assert-Doctor ($plan.story.promptTemplate -match '\{\{round\}\}' -and $plan.story.steerInstruction -match '\{\{round\}\}') 'Round placeholders are required in the prompt and Steer instruction.'
Assert-Doctor ($plan.story.steerWindow.target -ceq 'same-run') 'The customer demo Steer target must be the same Run.'
Assert-Doctor ($plan.story.steerWindow.testOnlyDelayAllowed -eq $false -and $plan.story.steerWindow.manualRetryAllowed -eq $false) 'The Steer window cannot rely on test delays or manual retries.'
Assert-Doctor (@($plan.oracle).Count -eq $expectedCases.Count) 'Customer demo oracle case count drifted.'
Assert-Doctor ((@($plan.oracle.id) -join ',') -ceq ($expectedCases -join ',')) 'Customer demo oracle case order or identity drifted.'
Assert-Doctor (@($plan.oracle | Where-Object { $_.required -ne $true }).Count -eq 0) 'Every customer demo oracle case must be required.'
Assert-Doctor (($plan.resultVocabulary -join ',') -ceq 'PASS,FAIL,NOT_RUN,BLOCKED_EXTERNAL,UPSTREAM_UNSUPPORTED') 'Customer demo result vocabulary drifted.'
Assert-Doctor ($planText -notmatch '(?i)(api[_-]?key|secret|token)\s*[=:]\s*["''][^"'']+["'']') 'Customer demo oracle appears to contain a credential value.'
Add-Check $checks 'oracle-contract' 'PASS' "planSha256=$(Get-Sha256 $planFile); cases=$($expectedCases.Count); rounds=2"

$frontendManifestPath = Join-Path $root 'third-party/deerflow/manifest.json'
$frontendManifest = Get-Content -Raw -LiteralPath $frontendManifestPath | ConvertFrom-Json -Depth 20
Assert-Doctor ($frontendManifest.upstream.commit -ceq $expectedSha) 'Imported frontend manifest is not pinned to the customer demo commit.'
Assert-Doctor ($frontendManifest.configuration.deerFlowBackendFallbackAllowed -eq $false) 'Imported frontend permits DeerFlow backend fallback.'
$sourceGate = & (Join-Path $root 'scripts/verify-deerflow-frontend.ps1') `
    -RepositoryRoot $root -SourceRepository $SourceRepository *>&1 | Out-String
Assert-Doctor ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($sourceGate)) 'Frozen frontend source verification failed.'
Add-Check $checks 'frozen-frontend' 'PASS' "commit=$expectedSha; manifestSha256=$(Get-Sha256 $frontendManifestPath); fallback=false"

$requiredSdkFiles = @(
    'io/haifa/haifa-agent-bom/0.1.2-SNAPSHOT/haifa-agent-bom-0.1.2-SNAPSHOT.pom',
    'io/haifa/haifa-agent-runtime-api/0.1.2-SNAPSHOT/haifa-agent-runtime-api-0.1.2-SNAPSHOT.jar',
    'io/haifa/haifa-agent-artifact/0.1.2-SNAPSHOT/haifa-agent-artifact-0.1.2-SNAPSHOT.jar'
)
$sdkHashes = [ordered]@{}
foreach ($relative in $requiredSdkFiles) {
    $file = Join-Path $MavenRepository $relative
    Assert-Doctor (Test-Path -LiteralPath $file -PathType Leaf) "Required Haifa Agent SDK artifact is missing: $file"
    $sdkHashes[$relative] = Get-Sha256 $file
}
Add-Check $checks 'haifa-sdk' 'PASS' "version=0.1.2-SNAPSHOT; artifacts=$($requiredSdkFiles.Count)"

$gitSha = (& git -C $root rev-parse HEAD).Trim()
Assert-Doctor ($LASTEXITCODE -eq 0 -and $gitSha -match '^[0-9a-f]{40}$') 'Cannot resolve the Super Harness Git SHA.'
$dataRoot = [IO.Path]::GetFullPath($DataDirectory)

if ($StaticOnly) {
    Add-Check $checks 'live-services' 'NOT_RUN' 'StaticOnly was requested; no HTTP endpoint or model was called.'
    Add-Check $checks 'budget-authorization' 'NOT_RUN' 'Paid model execution remains opt-in and was not authorized.'
}
else {
    Assert-Doctor ($GatewayPort -eq 18018 -and $FrontendPort -eq 33018) 'Live customer demo doctor only accepts the fixed main ports.'
    Assert-Doctor (Test-Path -LiteralPath $dataRoot -PathType Container) "Main data root is missing: $dataRoot"
    $gatewayBase = "http://127.0.0.1:$GatewayPort"
    $frontendBase = "http://127.0.0.1:$FrontendPort"
    $health = Invoke-JsonGet "$gatewayBase/health"
    $auth = Invoke-JsonGet "$gatewayBase/api/v1/auth/me"
    $models = Invoke-JsonGet "$gatewayBase/api/models"
    $managed = Invoke-JsonGet "$gatewayBase/api/managed-models"
    $proxiedModels = Invoke-JsonGet "$frontendBase/api/models"
    $page = Invoke-WebRequest -Uri "$frontendBase/workspace/chats/new" -TimeoutSec 15 -MaximumRedirection 5

    Assert-Doctor ($health.status -ceq 'ok') 'Gateway health is not ok.'
    Assert-Doctor ($auth.id -ceq 'default' -and $auth.system_role -ceq 'admin') 'Local auth identity drifted.'
    $catalogModel = @($models.models | Where-Object id -ceq $ModelId)
    $managedModel = @($managed.models | Where-Object name -ceq $ModelId)
    $proxiedModel = @($proxiedModels.models | Where-Object id -ceq $ModelId)
    Assert-Doctor ($catalogModel.Count -eq 1 -and $proxiedModel.Count -eq 1) 'Configured model is not consistently exposed by Gateway and same-origin frontend.'
    Assert-Doctor ($managedModel.Count -eq 1 -and $managedModel[0].enabled -eq $true) 'Managed customer demo model is missing or disabled.'
    Assert-Doctor ($managedModel[0].has_credential -eq $true -and $managedModel[0].credential_source -ceq 'os') 'Managed customer demo model lacks an OS credential reference.'
    $endpoint = [Uri]$managedModel[0].base_url
    Assert-Doctor ($endpoint.Scheme -ceq 'https' -and $endpoint.Host -ceq $plan.model.endpointHost) 'Managed customer demo model endpoint drifted.'
    Assert-Doctor ($page.StatusCode -eq 200) 'Frozen customer demo page is not ready.'

    foreach ($port in @($GatewayPort, $FrontendPort)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        Assert-Doctor ($listeners.Count -eq 1) "Expected exactly one listener on port $port."
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
        Assert-Doctor ($null -ne $process -and $process.CommandLine -like "*$root*") "Port $port is not owned by the main Super Harness worktree."
    }

    Add-Check $checks 'live-services' 'PASS' "gateway=$gatewayBase; frontend=$frontendBase; model=$ModelId; credential=os; dataRoot=$dataRoot"
    if ($BudgetApproved) {
        Add-Check $checks 'budget-authorization' 'PASS' "operatorApproved=true; maxModelCallsPerRound=$($plan.budget.maxModelCallsPerRound)"
    }
    else {
        Add-Check $checks 'budget-authorization' 'NOT_RUN' 'No paid model call was made; pass -BudgetApproved only after explicit operator approval.'
    }
}

$overall = if (@($checks | Where-Object status -eq 'FAIL').Count -gt 0) {
    'FAIL'
}
elseif (@($checks | Where-Object status -ne 'PASS').Count -gt 0) {
    'NOT_RUN'
}
else {
    'PASS'
}

$report = [ordered]@{
    schemaVersion = 'haifa.customer-demo.doctor-report/v1'
    generatedAt = (Get-Date).ToString('o')
    demoId = 'DEMO-V0'
    mode = if ($StaticOnly) { 'static' } else { 'live-read-only' }
    overall = $overall
    paidModelCalled = $false
    superHarnessSha = $gitSha
    oracleSha256 = Get-Sha256 $planFile
    frozenDeerFlowSha = $expectedSha
    sdkVersion = '0.1.2-SNAPSHOT'
    sdkArtifactSha256 = $sdkHashes
    modelId = $ModelId
    gatewayPort = $GatewayPort
    frontendPort = $FrontendPort
    dataRoot = $dataRoot
    checks = $checks
}
$json = $report | ConvertTo-Json -Depth 20
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $fullOutput = [IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullOutput
    $null = New-Item -ItemType Directory -Force -Path $parent
    [IO.File]::WriteAllText($fullOutput, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
$json
