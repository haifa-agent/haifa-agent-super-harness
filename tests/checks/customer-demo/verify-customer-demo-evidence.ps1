[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Cli = Join-Path $RepositoryRoot 'scripts/evidence/verify-customer-demo-evidence.ps1'
$SourceOracle = Join-Path $RepositoryRoot 'contracts/customer-demo/customer-demo-v0-oracle.json'
$SourceBundle = Join-Path $RepositoryRoot 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'
$TempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$TempRoot = Join-Path $TempParent ("w24-demo-evidence-" + [Guid]::NewGuid().ToString('N'))
$Assertions = 0
$Passed = [System.Collections.Generic.List[string]]::new()
$ReferencedRawEvidence = @([regex]::Matches((Get-Content -LiteralPath $SourceBundle -Raw), '"(local-tmp/[^"\r\n]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)

function Assert-True([bool]$Condition, [string]$Message) {
    $script:Assertions++
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function New-FixtureRoot([string]$Name) {
    $root = Join-Path $TempRoot $Name
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $root 'contracts/customer-demo')
    Copy-Item -LiteralPath $SourceOracle -Destination (Join-Path $root 'contracts/customer-demo/customer-demo-v0-oracle.json')
    $sourceEvidence = Join-Path $RepositoryRoot 'contracts/customer-demo/evidence'
    if (Test-Path -LiteralPath $sourceEvidence -PathType Container) {
        $targetEvidence = Join-Path $root 'contracts/customer-demo/evidence'
        $null = New-Item -ItemType Directory -Force -Path $targetEvidence
        Get-ChildItem -LiteralPath $sourceEvidence -File | Copy-Item -Destination $targetEvidence -Force
    }
    # The committed bundle can reference archived real evidence outside contracts.
    # Copy its available originals; missing or stale evidence still fails validation.
    foreach ($relative in $ReferencedRawEvidence) {
        $source = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot $relative))
        if (-not $source.StartsWith($RepositoryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) { throw 'Bundle evidence escapes its source repository.' }
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            $destination = Join-Path $root $relative
            $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)
            Copy-Item -LiteralPath $source -Destination $destination
        }
    }
    return $root
}

function New-EvidenceFile([string]$Root, [string]$Relative, [string]$Content) {
    $full = Join-Path $Root $Relative
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $full)
    [IO.File]::WriteAllText($full, $Content, [Text.UTF8Encoding]::new($false))
    return [ordered]@{ path = $Relative; sha256 = Get-Sha256 $full }
}

function Write-Bundle([string]$Root, $Bundle) {
    $path = Join-Path $Root 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json'
    [IO.File]::WriteAllText($path, (($Bundle | ConvertTo-Json -Depth 64) + "`n"), [Text.UTF8Encoding]::new($false))
    return $path
}

function Set-ProvenanceField {
    param([string]$Root, [object]$Round, [string]$Field, [object]$Value)
    $ref = $Round.artifactProvenanceEvidence
    $full = Join-Path $Root $ref.path
    $record = Get-Content -LiteralPath $full -Raw | ConvertFrom-Json -Depth 10 -DateKind String
    $record | Add-Member -NotePropertyName $Field -NotePropertyValue $Value -Force
    [IO.File]::WriteAllText($full, (($record | ConvertTo-Json -Compress) + "`n"), [Text.UTF8Encoding]::new($false))
    $ref.sha256 = Get-Sha256 $full
}

function Read-SourceBundle {
    return (Get-Content -LiteralPath $SourceBundle -Raw | ConvertFrom-Json -Depth 64 -DateKind String)
}

function Read-PartialBundle {
    # The committed live bundle may advance to GO; these negatives require an
    # explicit partial fixture while keeping all existing expected results.
    $bundle = Read-SourceBundle
    $bundle.decision = 'NO_GO'
    foreach ($stage in @($bundle.stages | Where-Object id -in @('M2', 'M3', 'M4'))) {
        $stage.workStatus = 'pending'
        $stage.result = 'NOT_RUN'
        $stage.proof = $null
        $stage.mainCommit = $null
        $stage.acceptedAt = $null
        $stage.evidence = @()
    }
    foreach ($round in $bundle.rounds) { $round.status = 'NOT_RUN' }
    $bundle.humanAcceptance.present = $false
    $bundle.humanAcceptance.path = $null
    $bundle.humanAcceptance.sha256 = $null
    $bundle.humanAcceptance.acceptedBy = $null
    $bundle.humanAcceptance.acceptedAt = $null
    return $bundle
}

function New-GoBundle([string]$Root) {
    $bundle = Read-SourceBundle
    $mainSha = '1c67827580ae0b15689df045e549b40097130f68'
    $bundle.mainSha = $mainSha
    $bundle.configSha256 = 'a' * 64
    $bundle.inputSha256 = 'b' * 64
    $bundle.dataRoot = 'local-tmp/main-service-data'
    $bundle.model.paidModelCalled = $true
    $bundle.budget.authorized = $true
    $bundle.budget.authorizationRef = 'operator-approval-2026-09-27'
    $fallbackRef = New-EvidenceFile $Root 'evidence/reference-fallback.json' '{"fallbackUsed":false,"gateway":"haifa"}'
    $bundle.referenceFallbackEvidence.evidencePath = $fallbackRef.path
    $bundle.referenceFallbackEvidence.evidenceSha256 = $fallbackRef.sha256

    for ($i = 0; $i -lt 2; $i++) {
        $round = $bundle.rounds[$i]
        $n = $i + 1
        $round.status = 'PASS'
        $round.modelId = 'main-deepseek'
        $round.mainSha = $mainSha
        $round.frozenFrontendSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
        $round.sdkVersion = '0.1.2-SNAPSHOT'
        $round.configSha256 = $bundle.configSha256
        $round.inputSha256 = $bundle.inputSha256
        $round.dataRoot = $bundle.dataRoot
        $round.threadId = "thread-demo-round-$n"
        $round.runId = "run-demo-round-$n"
        $round.toolCallId = "toolcall-demo-round-$n"
        $round.interactionId = "interaction-demo-round-$n"
        $round.artifactId = "artifact-demo-round-$n"
        $round.fileName = "customer-demo-round-$n.txt"
        $downloadRef = New-EvidenceFile $Root "evidence/round-$n-download.bin" "demo-round-$n-real-download-payload"
        $round.downloadSha256 = $downloadRef.sha256
        $round.downloadByteCount = (Get-Item -LiteralPath (Join-Path $Root $downloadRef.path)).Length
        $round | Add-Member -NotePropertyName downloadEvidence -NotePropertyValue ([ordered]@{ path = $downloadRef.path; sha256 = $downloadRef.sha256 }) -Force
        $round.fileSha256 = $downloadRef.sha256
        $round.artifactProvenance = "sdk-owned-real-artifact;run=run-demo-round-$n;tool=write_file"
        $provenanceRecord = @{
            artifactId = $round.artifactId
            runId = $round.runId
            toolCallId = $round.toolCallId
            tool = 'write_file'
            artifact = 'sdk-owned-real-artifact'
            downloadSha256 = $round.downloadSha256
            downloadByteCount = $round.downloadByteCount
        } | ConvertTo-Json -Compress
        $provenanceRef = New-EvidenceFile $Root "evidence/round-$n-provenance.json" $provenanceRecord
        $round | Add-Member -NotePropertyName artifactProvenanceEvidence -NotePropertyValue ([ordered]@{ path = $provenanceRef.path; sha256 = $provenanceRef.sha256 }) -Force
        $round.approvalBefore = 'pending'
        $round.approvalAfter = 'approved'
        $round.steer.targetRunId = $round.runId
        $round.steer.request = 'extend the same run answer'
        $round.steer.response = 'accepted'
        $round.steer.ackText = "DEMO-STEER-ACK-$n"
        $round.steer.sameRun = $true
        $round.networkTargets = @('api.deepseek.com', '127.0.0.1:18018')
        $round.usage = [ordered]@{ input_tokens = 10; output_tokens = 20 }
        $round.timeline = @(
            [ordered]@{ at = "2026-09-27T15:0$n`:00+08:00"; event = 'run-created' },
            [ordered]@{ at = "2026-09-27T15:0$n`:30+08:00"; event = 'terminal' }
        )
        $playwrightRef = New-EvidenceFile $Root "evidence/round-$n-playwright.txt" "round $n playwright pass"
        $round.playwrightPath = $playwrightRef.path
        $round.playwrightSha256 = $playwrightRef.sha256
        $logRef = New-EvidenceFile $Root "evidence/round-$n-server.log" "round $n server log"
        $round.logPaths = @($logRef)
        $round.processCleanup = [ordered]@{ portsReleased = $true; processes = @('gateway:18018', 'frontend:33018') }
        $round.attempts = 1
        $round.hiddenRetry = $false
        $round.firstFailure = $null
    }

    for ($i = 0; $i -lt 5; $i++) {
        $stage = $bundle.stages[$i]
        $stageRef = New-EvidenceFile $Root "evidence/stage-$($stage.id).json" "{`"stage`":`"$($stage.id)`",`"result`":`"PASS`"}"
        $stage.workStatus = 'complete'
        $stage.result = 'PASS'
        $stage.proof = "independent gate evidence for $($stage.id)"
        $stage.mainCommit = $mainSha
        $stage.acceptedAt = '2026-09-27T16:0' + $i + ':00+08:00'
        $stage.evidence = @($stageRef)
    }

    $acceptanceRef = New-EvidenceFile $Root 'evidence/human-acceptance.txt' 'human operator accepted demo v0 two-round chain'
    $bundle.humanAcceptance.present = $true
    $bundle.humanAcceptance.path = $acceptanceRef.path
    $bundle.humanAcceptance.sha256 = $acceptanceRef.sha256
    $bundle.humanAcceptance.acceptedBy = 'human-operator'
    $bundle.humanAcceptance.acceptedAt = '2026-09-27T16:30:00+08:00'
    $bundle.decision = 'GO'
    return $bundle
}

function Invoke-Cde {
    param(
        [string]$Root,
        [string]$Bundle = 'contracts/customer-demo/customer-demo-v0-evidence-bundle.json',
        [string]$Output = 'target/cde-evidence',
        [switch]$RequireGo
    )
    $arguments = @('-NoProfile', '-File', $Cli, '-RepositoryRoot', $Root, '-BundlePath', $Bundle, '-OutputDirectory', $Output)
    if ($RequireGo) { $arguments += '-RequireGo' }
    $output = @(& pwsh @arguments 2>&1)
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join "`n") }
}

function Assert-Passes {
    param([string]$Root, [string]$Name, [switch]$RequireGo)
    $result = Invoke-Cde -Root $Root -RequireGo:$RequireGo
    Assert-True ($result.ExitCode -eq 0) "$Name must be accepted: $($result.Output)"
    $script:Passed.Add($Name)
}

function Assert-Rejected {
    param([string]$Root, [string]$Name, [string]$Pattern)
    $result = Invoke-Cde -Root $Root
    Assert-True ($result.ExitCode -ne 0) "$Name must be rejected"
    $failurePath = Join-Path $Root 'target/cde-evidence/customer-demo-evidence-failure.json'
    $text = if (Test-Path -LiteralPath $failurePath -PathType Leaf) {
        $failure = Get-Content -LiteralPath $failurePath -Raw | ConvertFrom-Json -Depth 10 -DateKind String
        (@($failure.violations) -join ' ')
    }
    else { $result.Output }
    $text = $text -replace '\s+', ' '
    Assert-True ($text -match $Pattern) "$Name rejection must match '$Pattern': $($result.Output)"
    $script:Passed.Add($Name)
}

try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    $notRunRoot = New-FixtureRoot 'not-run'
    Write-Bundle $notRunRoot (Read-PartialBundle) | Out-Null
    Assert-Passes $notRunRoot 'valid-partial-bundle'
    $notRunManifest = Get-Content -LiteralPath (Join-Path $notRunRoot 'target/cde-evidence/customer-demo-evidence-manifest.json') -Raw |
        ConvertFrom-Json -Depth 40 -DateKind String
    Assert-True ($notRunManifest.validationStatus -eq 'PASS') 'valid bundle must record validation PASS'
    Assert-True ($notRunManifest.decision -eq 'NO_GO') 'current bundle must stay NO_GO'
    Assert-True ($notRunManifest.details.stageResults.M0 -eq 'PASS') 'M0 must preserve its verified PASS'
    Assert-True ($notRunManifest.details.stageResults.M2 -eq 'NOT_RUN') 'M2 must stay NOT_RUN'
    Assert-True (@($notRunManifest.details.sensitiveFindings).Count -eq 0) 'no sensitive values may be found'
    Assert-True (@($notRunManifest.details.firstFailureHistory).Count -ge 4) 'documented first failures must be retained'
    $firstHash = Get-Sha256 (Join-Path $notRunRoot 'target/cde-evidence/customer-demo-evidence-manifest.json')
    $null = Invoke-Cde -Root $notRunRoot -Output 'target/cde-evidence-two'
    $secondHash = Get-Sha256 (Join-Path $notRunRoot 'target/cde-evidence-two/customer-demo-evidence-manifest.json')
    Assert-True ($firstHash -eq $secondHash) 'identical bundles must produce identical manifests'
    $script:Passed.Add('deterministic-manifest')

    $goRoot = New-FixtureRoot 'go'
    $goBundle = New-GoBundle $goRoot
    Write-Bundle $goRoot $goBundle | Out-Null
    Assert-Passes $goRoot 'valid-go-bundle'
    Assert-Passes $goRoot 'go-required' -RequireGo
    $goManifest = Get-Content -LiteralPath (Join-Path $goRoot 'target/cde-evidence/customer-demo-evidence-manifest.json') -Raw |
        ConvertFrom-Json -Depth 40 -DateKind String
    Assert-True ($goManifest.decision -eq 'GO') 'consistent GO bundle must be accepted'
    Assert-True ($goManifest.details.allStagesPass -eq $true) 'GO bundle must mark all stages PASS'
    Assert-True ($goManifest.details.gateStatus -eq 'PASS') 'GO bundle must record a PASS gate status'
    Assert-True (@($goManifest.details.failed).Count -eq 0) 'GO bundle cannot contain failed items'
    Assert-True (@($goManifest.details.evidenceReferences | Where-Object { $_.path -like '*download*' }).Count -eq 2) 'GO bundle must resolve both real download files'

    $mutations = @(
        @{
            Name = 'missing-m4'; Pattern = 'exactly five stages'
            Apply = { param($b) $b.stages = @($b.stages | Where-Object { $_.id -ne 'M4' }) }
        },
        @{
            Name = 'fake-artifact'; Pattern = 'artifact has no provenance'
            Apply = { param($b) $b.rounds[0].artifactProvenance = $null }
        },
        @{
            Name = 'rounds-different-sha'; Pattern = 'not bound to one mainSha'
            Apply = { param($b) $b.rounds[1].mainSha = 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef' }
        },
        @{
            Name = 'hidden-retry'; Pattern = 'declares a hidden retry'
            Apply = { param($b) $b.rounds[0].hiddenRetry = $true }
        },
        @{
            Name = 'missing-first-failure'; Pattern = 'omits the first failure'
            Apply = { param($b) $b.rounds[0].attempts = 2 }
        },
        @{
            Name = 'no-human-acceptance'; Pattern = 'M4 PASS requires explicit human acceptance'
            Apply = {
                param($b)
                $b.humanAcceptance.present = $false
                $b.humanAcceptance.path = $null
                $b.humanAcceptance.sha256 = $null
                $b.humanAcceptance.acceptedBy = $null
                $b.humanAcceptance.acceptedAt = $null
            }
        },
        @{
            Name = 'self-reported-go'; Pattern = 'GO requires every stage M0-M4 to PASS'; Base = 'notrun'
            Apply = { param($b) $b.decision = 'GO' }
        },
        @{
            Name = 'no-reference-fallback'; Pattern = 'must record reference fallback evidence'
            Apply = { param($b) $b.PSObject.Properties.Remove('referenceFallbackEvidence') }
        },
        @{
            Name = 'sensitive-value'; Pattern = 'sensitive value pattern'
            Apply = { param($b) $b.budget.authorizationRef = 'sk-' + ('a' * 24) }
        },
        @{
            Name = 'only-best-round'; Pattern = 'exactly 2 rounds'
            Apply = { param($b) $b.rounds = @($b.rounds[0]) }
        },
        @{
            Name = 'duplicate-id'; Pattern = 'duplicate id'
            Apply = { param($b) $b.rounds[1].threadId = $b.rounds[0].threadId }
        },
        @{
            Name = 'go-m4-sha-mismatch'; Pattern = 'GO requires M4 to be revalidated'
            Apply = { param($b) $b.stages[4].mainCommit = 'ffffffffffffffffffffffffffffffffffffffff' }
        },
        @{
            Name = 'oracle-hash-mismatch'; Pattern = 'oracle sha256 mismatch'
            Apply = { param($b) $b.oracle.sha256 = '0' * 64 }
        },
        @{
            Name = 'invalid-result'; Pattern = 'invalid result'
            Apply = { param($b) $b.stages[0].result = 'MAYBE' }
        },
        @{
            Name = 'missing-provenance-evidence'; Pattern = 'provenance original record is missing'
            Apply = { param($b) $b.rounds[0].artifactProvenanceEvidence = $null }
        },
        @{
            Name = 'fake-provenance-record'; Pattern = 'provenance record runId mismatch'
            Apply = { param($b) $b.rounds[0].runId = 'run-demo-mutated' }
        },
        @{
            Name = 'fake-download'; Pattern = 'download evidence points at a missing evidence file'
            Apply = {
                param($b)
                $b.rounds[0].downloadEvidence = [ordered]@{
                    path = 'evidence/round-1-download-missing.bin'
                    sha256 = $b.rounds[0].downloadEvidence.sha256
                }
            }
        },
        @{
            Name = 'hash-drift'; Pattern = 'does not match the actual downloaded bytes'
            Apply = { param($b) $b.rounds[0].downloadSha256 = ('0' * 64) }
        },
        @{
            Name = 'byte-count-drift'; Pattern = 'does not match the actual downloaded length'
            Apply = { param($b) $b.rounds[0].downloadByteCount = 4242 }
        },
        @{
            Name = 'file-vs-download-sha-drift'; Pattern = 'fileSha256'
            Apply = { param($b) $b.rounds[0].fileSha256 = ('0' * 64) }
        },
        @{
            Name = 'wrong-tool'; Pattern = 'provenance record tool mismatch'
            Apply = { param($b, $r) Set-ProvenanceField -Root $r -Round $b.rounds[0] -Field 'tool' -Value 'bash' }
        },
        @{
            Name = 'wrong-provenance'; Pattern = 'provenance record artifact mismatch'
            Apply = { param($b, $r) Set-ProvenanceField -Root $r -Round $b.rounds[0] -Field 'artifact' -Value 'fallback-artifact' }
        },
        @{
            Name = 'provenance-sha-drift'; Pattern = 'provenance evidence hash mismatch'
            Apply = { param($b) $b.rounds[0].artifactProvenanceEvidence.sha256 = ('0' * 64) }
        },
        @{
            Name = 'provenance-byte-count-drift'; Pattern = 'provenance record downloadByteCount mismatch'
            Apply = { param($b, $r) Set-ProvenanceField -Root $r -Round $b.rounds[0] -Field 'downloadByteCount' -Value (([int]$b.rounds[0].downloadByteCount) + 1) }
        }
    )

    foreach ($mutation in $mutations) {
        $root = New-FixtureRoot ("mutant-" + $mutation.Name)
        $bundle = if ($mutation.ContainsKey('Base') -and $mutation.Base -eq 'notrun') { Read-PartialBundle } else { New-GoBundle $root }
        & $mutation.Apply $bundle $root
        Write-Bundle $root $bundle | Out-Null
        Assert-Rejected -Root $root -Name $mutation.Name -Pattern $mutation.Pattern
    }

    $skippingRoot = New-FixtureRoot 'mutant-stage-skipping'
    $skippingBundle = Read-PartialBundle
    $m3Ref = New-EvidenceFile $skippingRoot 'evidence/stage-M3.json' '{"stage":"M3"}'
    $m3 = $skippingBundle.stages[3]
    $m3.workStatus = 'complete'
    $m3.result = 'PASS'
    $m3.proof = 'premature pass'
    $m3.mainCommit = '1c67827580ae0b15689df045e549b40097130f68'
    $m3.acceptedAt = '2026-09-27T16:03:00+08:00'
    $m3.evidence = @($m3Ref)
    Write-Bundle $skippingRoot $skippingBundle | Out-Null
    Assert-Rejected -Root $skippingRoot -Name 'stage-skipping' -Pattern 'cannot PASS before M2 PASS'

    Write-Host "Customer demo evidence verifier tests passed: $Assertions assertions across $($Passed.Count) cases."
}
finally {
    $resolved = [IO.Path]::GetFullPath($TempRoot)
    if ($resolved.StartsWith($TempParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
