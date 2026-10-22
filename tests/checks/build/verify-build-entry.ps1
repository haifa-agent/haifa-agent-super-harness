#Requires -Version 7
<#
.SYNOPSIS
  Regression for the build entry points: SDK selection, baseline gate, build stamps
  and scripts/test-targeted.ps1.

.DESCRIPTION
  Fast cases use temporary directories and a throwaway git repository. Cases that run
  Maven are opt-in:
    -Maven                  runs test-targeted.ps1 for real (T1, T2).
    -SelectedHaifaVersion   additionally runs it against that worktree-labelled SDK
                            version, which must already be published locally (V2).
  local-tmp/sdk-selection.json of this worktree must not exist when the script starts;
  the script creates and removes it itself.

.EXAMPLE
  pwsh -NoProfile -File tests/checks/build/verify-build-entry.ps1 -Maven
#>
[CmdletBinding()]
param(
    [switch]$Maven,
    [string]$SelectedHaifaVersion,
    [string]$FastTestClass = 'LocalServerConfigurationTest'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$pwshExe = (Get-Command pwsh -ErrorAction Stop).Source
$testTargeted = Join-Path $repoRoot 'scripts/test-targeted.ps1'
$baselineGate = Join-Path $PSScriptRoot 'verify-haifa-version-baseline.ps1'
$selectionPath = Join-Path $repoRoot 'local-tmp/sdk-selection.json'
$scratch = Join-Path ([IO.Path]::GetTempPath()) "verify-build-entry-$([guid]::NewGuid().ToString('N'))"
$null = New-Item -ItemType Directory -Force -Path $scratch
$previousStaticWebsiteOnly = [Environment]::GetEnvironmentVariable('NEXT_PUBLIC_STATIC_WEBSITE_ONLY', 'Process')

. (Join-Path $repoRoot 'scripts/lib/sdk-selection.ps1')
. (Join-Path $repoRoot 'scripts/lib/build-stamp.ps1')

$script:results = [ordered]@{}
function Set-Case {
    param([string]$Name, [string]$Result, [string]$Detail = '')
    $script:results[$Name] = $Result
    Write-Output ("  {0,-8} {1} {2}" -f $Result, $Name, $Detail)
}
function Test-Throws {
    param([scriptblock]$Action, [string]$ExpectedFragment)
    try { $null = & $Action }
    catch { return $_.Exception.Message.Contains($ExpectedFragment) }
    return $false
}
function Invoke-Entry {
    param([string]$Script, [string[]]$Arguments)
    $output = & $pwshExe -NoLogo -NoProfile -File $Script @Arguments 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = (@($output) -join "`n") }
}
function Get-EntryJson {
    param([string]$Text)
    return ($Text.Substring($Text.IndexOf('{')) | ConvertFrom-Json)
}

if (Test-Path -LiteralPath $selectionPath) {
    throw "Refusing to run: '$selectionPath' exists. This regression needs to create and remove it."
}

try {
    Write-Output 'SDK selection'
    $fakeRoot = Join-Path $scratch 'selection-root'
    $fakeRepo = Join-Path $scratch 'maven-repo'
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $fakeRoot 'local-tmp'), $fakeRepo

    $absent = Resolve-HaifaSdkSelection -RepositoryRoot $fakeRoot -LocalRepository $fakeRepo
    Set-Case 'V1-absent-uses-baseline' $(if ($absent.Status -eq 'ABSENT' -and $absent.HaifaVersion -eq '0.1.4-SNAPSHOT' -and $null -eq $absent.MavenArgument) { 'PASS' } else { 'FAIL' })

    $labelled = '0.1.4-some-worktree-SNAPSHOT'
    $fakeSelection = Join-Path $fakeRoot 'local-tmp/sdk-selection.json'
    @{ haifaVersion = $labelled } | ConvertTo-Json | Set-Content -LiteralPath $fakeSelection -Encoding utf8
    Set-Case 'V3-unpublished-version-rejected' $(if (Test-Throws { Resolve-HaifaSdkSelection -RepositoryRoot $fakeRoot -LocalRepository $fakeRepo } 'is not published') { 'PASS' } else { 'FAIL' })

    $bomDirectory = Join-Path $fakeRepo "io/haifa/haifa-agent-bom/$labelled"
    $null = New-Item -ItemType Directory -Force -Path $bomDirectory
    Set-Content -LiteralPath (Join-Path $bomDirectory "haifa-agent-bom-$labelled.pom") -Value '<project/>' -Encoding utf8
    $selected = Resolve-HaifaSdkSelection -RepositoryRoot $fakeRoot -LocalRepository $fakeRepo
    Set-Case 'V2a-published-version-selected' $(if ($selected.Status -eq 'SELECTED' -and $selected.MavenArgument -eq "-Dhaifa.version=$labelled") { 'PASS' } else { 'FAIL' })

    @{ haifaVersion = '0.1.1-SNAPSHOT' } | ConvertTo-Json | Set-Content -LiteralPath $fakeSelection -Encoding utf8
    Set-Case 'V3b-foreign-version-rejected' $(if (Test-Throws { Resolve-HaifaSdkSelection -RepositoryRoot $fakeRoot -LocalRepository $fakeRepo } 'unsupported haifaVersion') { 'PASS' } else { 'FAIL' })
    '{}' | Set-Content -LiteralPath $fakeSelection -Encoding utf8
    Set-Case 'V3c-missing-field-rejected' $(if (Test-Throws { Resolve-HaifaSdkSelection -RepositoryRoot $fakeRoot -LocalRepository $fakeRepo } "non-empty 'haifaVersion'") { 'PASS' } else { 'FAIL' })

    Write-Output 'Baseline gate'
    $gate = Invoke-Entry $baselineGate @()
    Set-Case 'V4-baseline-gate-passes' $(if ($gate.ExitCode -eq 0) { 'PASS' } else { 'FAIL' }) $gate.Text
    $gateRoot = Join-Path $scratch 'gate-root'
    $rootPom = [xml](Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'pom.xml'))
    $modules = @($rootPom.project.modules.ChildNodes | Where-Object { $_.Name -eq 'module' } | ForEach-Object { $_.InnerText.Trim() })
    foreach ($module in $modules) {
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $gateRoot $module)
        Copy-Item -LiteralPath (Join-Path $repoRoot "$module/pom.xml") -Destination (Join-Path $gateRoot "$module/pom.xml")
    }
    (Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'pom.xml')).Replace('<haifa.version>0.1.4-SNAPSHOT</haifa.version>', "<haifa.version>$labelled</haifa.version>") |
        Set-Content -LiteralPath (Join-Path $gateRoot 'pom.xml') -Encoding utf8
    $gate = Invoke-Entry $baselineGate @('-RepositoryRoot', $gateRoot)
    Set-Case 'V4-labelled-root-version-fails' $(if ($gate.ExitCode -ne 0 -and $gate.Text.Contains($labelled)) { 'PASS' } else { 'FAIL' })
    Copy-Item -LiteralPath (Join-Path $repoRoot 'pom.xml') -Destination (Join-Path $gateRoot 'pom.xml') -Force
    $modulePom = Join-Path $gateRoot 'super-harness-server/pom.xml'
    (Get-Content -Raw -LiteralPath $modulePom) -replace '(<artifactId>haifa-agent-sdk-starter</artifactId>)', "`$1<version>$labelled</version>" |
        Set-Content -LiteralPath $modulePom -Encoding utf8
    $gate = Invoke-Entry $baselineGate @('-RepositoryRoot', $gateRoot)
    Set-Case 'V4-module-pinned-version-fails' $(if ($gate.ExitCode -ne 0 -and $gate.Text.Contains('haifa-agent-sdk-starter')) { 'PASS' } else { 'FAIL' })

    Write-Output 'Build stamps'
    $stampRoot = Join-Path $scratch 'stamp-root'
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $stampRoot 'frontend'), (Join-Path $stampRoot 'super-harness-server')
    Set-Content -LiteralPath (Join-Path $stampRoot 'frontend/pnpm-lock.yaml') -Value 'lockfileVersion: 1' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $stampRoot 'frontend/.gitignore') -Value '/.next/' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $stampRoot '.gitignore') -Value 'target/' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $stampRoot 'pom.xml') -Value '<project/>' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $stampRoot 'super-harness-server/pom.xml') -Value '<project/>' -Encoding utf8
    foreach ($gitArguments in @(@('init', '-q'), @('config', 'core.autocrlf', 'false'), @('add', '-A'), @('-c', 'user.name=t', '-c', 'user.email=t@example.invalid', 'commit', '-q', '-m', 'seed'))) {
        & git -C $stampRoot @gitArguments
        if ($LASTEXITCODE -ne 0) { throw "git $($gitArguments -join ' ') failed in the stamp fixture." }
    }
    $gatewayA = 'http://127.0.0.1:20618'
    $gatewayB = 'http://127.0.0.1:30618'
    $stampMavenRepo = Join-Path $scratch 'stamp-maven-repo'
    $null = New-Item -ItemType Directory -Force -Path $stampMavenRepo
    $env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'

    Set-Case 'S4-missing-frontend-stamp-fails' $(if (Test-Throws { Assert-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA } 'stamp is missing') { 'PASS' } else { 'FAIL' })
    Write-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA
    Write-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion '0.1.4-SNAPSHOT' -MavenRepo $stampMavenRepo
    $stampWritten = (Test-Path -LiteralPath (Get-FrontendBuildStampPath $stampRoot)) -and (Test-Path -LiteralPath (Get-ServerBuildStampPath $stampRoot))
    Set-Case 'S1-stamps-written' $(if ($stampWritten) { 'PASS' } else { 'FAIL' })
    $reuseError = ''
    try {
        Assert-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA
        Assert-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion '0.1.4-SNAPSHOT' -MavenRepo $stampMavenRepo
    }
    catch { $reuseError = $_.Exception.Message }
    Set-Case 'S2-same-inputs-reusable' $(if ($reuseError -eq '') { 'PASS' } else { 'FAIL' }) $reuseError
    $env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'true'
    $staticRejected = Test-Throws { Write-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA } 'NEXT_PUBLIC_STATIC_WEBSITE_ONLY=false'
    Set-Case 'S2b-static-build-cannot-be-stamped' $(if ($staticRejected -and -not (Test-Path -LiteralPath (Get-FrontendBuildStampPath $stampRoot))) { 'PASS' } else { 'FAIL' })
    $env:NEXT_PUBLIC_STATIC_WEBSITE_ONLY = 'false'
    Write-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA
    $frontendStampPath = Get-FrontendBuildStampPath $stampRoot
    $frontendStamp = Get-Content -Raw -LiteralPath $frontendStampPath | ConvertFrom-Json -AsHashtable
    $null = $frontendStamp.Remove('staticWebsiteOnly')
    $frontendStamp | ConvertTo-Json | Set-Content -LiteralPath $frontendStampPath -Encoding utf8
    Set-Case 'S2c-old-stamp-without-profile-fails' $(if (Test-Throws { Assert-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA } "field 'staticWebsiteOnly'") { 'PASS' } else { 'FAIL' })
    Write-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA
    $proxyMismatch = Test-Throws { Assert-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayB } "built with '$gatewayA', current is '$gatewayB'"
    Set-Case 'S3-proxy-target-mismatch-fails' $(if ($proxyMismatch) { 'PASS' } else { 'FAIL' })
    Set-Case 'S3b-sdk-version-mismatch-fails' $(if (Test-Throws { Assert-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion $labelled -MavenRepo $stampMavenRepo } "field 'javaInputFingerprint'") { 'PASS' } else { 'FAIL' })

    Set-Content -LiteralPath (Join-Path $stampRoot 'frontend/pnpm-lock.yaml') -Value 'lockfileVersion: 2' -Encoding utf8
    Set-Case 'S5-dirty-frontend-not-reusable' $(if (Test-Throws { Assert-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA } 'uncommitted changes') { 'PASS' } else { 'FAIL' })
    Write-FrontendBuildStamp -RepositoryRoot $stampRoot -InternalGatewayBaseUrl $gatewayA
    Set-Case 'S5b-dirty-build-leaves-no-stamp' $(if (-not (Test-Path -LiteralPath (Get-FrontendBuildStampPath $stampRoot))) { 'PASS' } else { 'FAIL' })
    & git -C $stampRoot -c user.name=t -c user.email=t@example.invalid commit -q -am 'lockfile'
    Write-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion '0.1.4-SNAPSHOT' -MavenRepo $stampMavenRepo
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $stampRoot 'docs')
    Set-Content -LiteralPath (Join-Path $stampRoot 'docs/guide.md') -Value 'doc' -Encoding utf8
    & git -C $stampRoot add -A
    & git -C $stampRoot -c user.name=t -c user.email=t@example.invalid commit -q -m 'docs only'
    Set-Case 'S6-docs-only-commit-keeps-server-stamp' $(if (Test-Throws { Assert-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion '0.1.4-SNAPSHOT' -MavenRepo $stampMavenRepo } 'does not match') { 'FAIL' } else { 'PASS' })
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $stampRoot 'super-harness-server/src/main/java')
    Set-Content -LiteralPath (Join-Path $stampRoot 'super-harness-server/src/main/java/App.java') -Value 'class App {}' -Encoding utf8
    & git -C $stampRoot add -A
    & git -C $stampRoot -c user.name=t -c user.email=t@example.invalid commit -q -m 'java input'
    Set-Case 'S7-java-input-commit-invalidates-server-stamp' $(if (Test-Throws { Assert-ServerBuildStamp -RepositoryRoot $stampRoot -HaifaVersion '0.1.4-SNAPSHOT' -MavenRepo $stampMavenRepo } "field 'javaInputFingerprint'") { 'PASS' } else { 'FAIL' })

    Write-Output 'test-targeted.ps1 argument guards'
    foreach ($forbidden in @('install', 'clean', '-DskipTests', '-Dhaifa.version=0.1.4-x-SNAPSHOT')) {
        $run = Invoke-Entry $testTargeted @('-Test', $FastTestClass, $forbidden)
        Set-Case "T3-rejects-$forbidden" $(if ($run.ExitCode -ne 0 -and -not $run.Text.Contains('"status"')) { 'PASS' } else { 'FAIL' })
    }

    if ($Maven) {
        Write-Output 'test-targeted.ps1 with Maven'
        $run = Invoke-Entry $testTargeted @('-Test', $FastTestClass, '-LowMemory')
        $json = Get-EntryJson $run.Text
        $ok = $run.ExitCode -eq 0 -and $json.status -eq 'PASS' -and $json.tests -gt 0 -and $json.command.Contains(' -am ') -and $json.command.Contains('-DargLine=-Xmx768m -XX:+UseSerialGC') -and $json.sdkSelection -eq 'ABSENT' -and -not $json.command.Contains('-Dhaifa.version')
        Set-Case 'T1-fast-class-passes-with-am' $(if ($ok) { 'PASS' } else { 'FAIL' }) "tests=$($json.tests) log=$($json.logPath)"

        $run = Invoke-Entry $testTargeted @('-Test', 'NoSuchTestClassForBuildEntryRegression')
        $json = Get-EntryJson $run.Text
        Set-Case 'T2-zero-matches-fail' $(if ($run.ExitCode -ne 0 -and $json.status -eq 'FAIL_NO_TESTS_MATCHED') { 'PASS' } else { 'FAIL' }) "log=$($json.logPath)"

        if ($SelectedHaifaVersion) {
            $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $selectionPath)
            @{ haifaVersion = $SelectedHaifaVersion } | ConvertTo-Json | Set-Content -LiteralPath $selectionPath -Encoding utf8
            try {
                $run = Invoke-Entry $testTargeted @('-Test', $FastTestClass)
                $json = Get-EntryJson $run.Text
                $ok = $run.ExitCode -eq 0 -and $json.status -eq 'PASS' -and $json.tests -gt 0 -and $json.command.Contains("-Dhaifa.version=$SelectedHaifaVersion")
                Set-Case 'V2-labelled-version-builds' $(if ($ok) { 'PASS' } else { 'FAIL' }) "tests=$($json.tests) log=$($json.logPath)"
            }
            finally {
                Remove-Item -LiteralPath $selectionPath -Force
            }
        }
        else {
            Set-Case 'V2-labelled-version-builds' 'NOT_RUN' '(-SelectedHaifaVersion not given)'
        }
    }
    else {
        foreach ($name in @('T1-fast-class-passes-with-am', 'T2-zero-matches-fail', 'V2-labelled-version-builds')) {
            Set-Case $name 'NOT_RUN' '(-Maven not given)'
        }
    }
}
finally {
    [Environment]::SetEnvironmentVariable('NEXT_PUBLIC_STATIC_WEBSITE_ONLY', $previousStaticWebsiteOnly, 'Process')
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

$failed = @($script:results.GetEnumerator() | Where-Object { $_.Value -eq 'FAIL' })
$notRun = @($script:results.GetEnumerator() | Where-Object { $_.Value -eq 'NOT_RUN' })
Write-Output "SUMMARY pass=$($script:results.Count - $failed.Count - $notRun.Count) fail=$($failed.Count) not_run=$($notRun.Count)"
if ($failed.Count -gt 0) { exit 1 }
