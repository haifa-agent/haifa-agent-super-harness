#Requires -Version 7
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root 'scripts/lib/smoke-preflight.ps1')
. (Join-Path $root 'scripts/lib/run-fixture.ps1')
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $root "local-tmp/slot-3-eng-feedback/evidence/contracts-$(Get-Date -Format yyyyMMdd-HHmmss-fff)" }
$null = New-Item -ItemType Directory -Force $EvidenceDirectory
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Action) {
    & $Action
    $results.Add(@{ name = $Name; result = 'PASS' })
    Write-Output "PASS $Name"
}
function Reject([scriptblock]$Action, [string]$Signature) {
    try { $null = & $Action } catch {
        if ($_.Exception.Message.Contains($Signature)) { return }
        throw
    }
    throw "Expected rejection: $Signature"
}
$spec = Join-Path $EvidenceDirectory 'selection.spec.cjs'
[IO.File]::WriteAllText($spec, 'test("literal (a+b)", async () => {}); test("other", async () => {});')
Check 'literal-title-and-full-selection' {
    $all = Resolve-SmokeCaseSelection $spec
    $one = Resolve-SmokeCaseSelection $spec 'literal (a+b)'
    if ($all.Selected.Count -ne 2 -or $one.Selected.Count -ne 1 -or
        'suite literal (a+b)' -cnotmatch $one.Grep -or 'suite literal aaab' -cmatch $one.Grep) { throw 'Literal selection changed semantics' }
}
Check 'unknown-and-ambiguous-title-rejected' {
    Reject { Resolve-SmokeCaseSelection $spec 'absent' } 'exactly one'
    $ambiguous = Join-Path $EvidenceDirectory 'ambiguous.spec.cjs'
    [IO.File]::WriteAllText($ambiguous, 'test("same", async () => {}); test("prefix same", async () => {});')
    Reject { Resolve-SmokeCaseSelection $ambiguous 'same' } 'exactly one'
}
$environment = @{ DEER_FLOW_AUTH_DISABLED='1'; SUPER_HARNESS_DATA_DIR=$EvidenceDirectory; DEER_FLOW_INTERNAL_GATEWAY_BASE_URL='http://127.0.0.1:40618' }
$savedProxy = @{}
foreach ($name in @('NO_PROXY','no_proxy')) { $savedProxy[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,'127.0.0.1,localhost','Process') }
try {
    Check 'environment-and-context-before-build' {
        Assert-SmokeEnvironment $environment $EvidenceDirectory 'http://127.0.0.1:40618' $true 32768
        Reject { Assert-SmokeEnvironment $environment $EvidenceDirectory 'http://127.0.0.1:40618' $true 8192 } 'context_window'
        $bad = $environment.Clone(); $bad.Remove('SUPER_HARNESS_DATA_DIR')
        Reject { Assert-SmokeEnvironment $bad $EvidenceDirectory 'http://127.0.0.1:40618' $true 32768 } 'SUPER_HARNESS_DATA_DIR'
        $bad = $environment.Clone(); $bad.DEER_FLOW_AUTH_DISABLED='0'
        Reject { Assert-SmokeEnvironment $bad $EvidenceDirectory 'http://127.0.0.1:40618' $true 32768 } 'DEER_FLOW_AUTH_DISABLED'
    }
    Check 'proxy-wildcard-rejected-without-value' {
        foreach ($name in @('NO_PROXY','no_proxy')) {
            [Environment]::SetEnvironmentVariable($name,'*.private.invalid','Process')
            try { Assert-SmokeEnvironment $environment $EvidenceDirectory 'http://127.0.0.1:40618' $true 32768; throw 'Wildcard accepted' }
            catch {
                if ($_.Exception.Message -notlike "$name contains*" -or $_.Exception.Message.Contains('private.invalid')) { throw }
            }
            [Environment]::SetEnvironmentVariable($name,'127.0.0.1,localhost','Process')
        }
    }
} finally { foreach ($name in $savedProxy.Keys) { [Environment]::SetEnvironmentVariable($name,$savedProxy[$name],'Process') } }
Check 'missing-dependency-before-build' {
    Reject { Assert-SmokeDependencies $EvidenceDirectory } 'dependency is missing'
}
Check 'partial-report-preserves-fail-and-unselected-not-run' {
    $report = @{ suites=@(@{ specs=@(@{title='selected'; tests=@(@{expectedStatus='passed';results=@(@{status='failed';error=@{message='first-fail'};attachments=@()})})}); suites=@() }) }
    $report | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'results.json')
    Write-FixtureCaseResults $EvidenceDirectory @('selected','unselected')
    $rows = @(Get-Content -Raw (Join-Path $EvidenceDirectory 'case-results.json') | ConvertFrom-Json)
    if ($rows.Count -ne 2 -or $rows[0].result -ne 'FAIL' -or $rows[0].firstFailure -ne 'first-fail' -or $rows[1].result -ne 'NOT_RUN') { throw 'Partial report erased failure or missing case' }
}
Check 'entry-rejects-unknown-case-before-build' {
    $output = & (Get-Command pwsh).Source -NoProfile -File (Join-Path $root 'scripts/run-w18-ui-smoke.ps1') `
        -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -CaseTitle 'nonexistent-case-title' 2>&1
    $code = $LASTEXITCODE
    $output | Set-Content (Join-Path $EvidenceDirectory 'unknown-case.raw.log')
    if ($code -eq 0 -or (@($output) -join "`n") -notmatch 'exactly one') { throw 'Entry started a build or accepted the unknown case' }
}
Check 'missing-or-extra-results-cannot-pass-and-authorized-deferral-stays-not-run' {
    $empty = @(@{ name='selected'; result='NOT_RUN' })
    Reject { Assert-SmokeCaseResults $empty @('selected') } 'do not cover'
    $pass = @(@{ name='selected'; result='PASS' }, @{name='deferred';result='NOT_RUN'})
    Assert-SmokeCaseResults $pass @('selected','deferred') @('deferred')
    Reject { Assert-SmokeCaseResults $pass @('selected','deferred') } 'do not cover'
    $extra = @(@{name='selected';result='PASS'},@{name='other';result='PASS'})
    Reject { Assert-SmokeCaseResults $extra @('selected') } 'unselected case'
    $duplicate = @(@{name='selected';result='PASS'},@{name='selected';result='PASS'})
    Reject { Assert-SmokeCaseResults $duplicate @('selected') } 'do not cover'
}
Check 'classpath-profile-cannot-reuse-production-jar' {
    $output = & (Get-Command pwsh).Source -NoProfile -File (Join-Path $root 'scripts/run-w18-ui-smoke.ps1') `
        -AgentSlot 3 -GatewayPort 40618 -FrontendPort 45618 -Interaction -SkipServerBuild -PreflightOnly 2>&1
    $code = $LASTEXITCODE
    $output | Set-Content (Join-Path $EvidenceDirectory 'classpath-jar-reuse-rejected.raw.log')
    if ($code -eq 0 -or (@($output) -join "`n") -notmatch 'test-classpath profile') { throw 'Classpath profile accepted JAR reuse' }
}
ConvertTo-Json -InputObject $results.ToArray() | Set-Content (Join-Path $EvidenceDirectory 'verification-results.json')
