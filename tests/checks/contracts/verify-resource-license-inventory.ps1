[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
$Verifier = Join-Path $RepositoryRoot 'scripts/resources/verify-resource-license-inventory.ps1'
$Generator = Join-Path $RepositoryRoot 'scripts/resources/generate-resource-license-inventory.ps1'
$Inventory = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-license-inventory.json'
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("haifa-w00-resource-tests-" + [guid]::NewGuid().ToString('N'))

function Invoke-Verifier {
    param([string]$ManifestPath, [string[]]$ExtraArgs = @())
    $arguments = @('-NoProfile', '-File', $Verifier, '-RepositoryRoot', $RepositoryRoot, '-InventoryPath', $ManifestPath) + $ExtraArgs
    $output = & pwsh @arguments 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

function Read-Inventory {
    return Get-Content -LiteralPath $Inventory -Raw | ConvertFrom-Json -Depth 100
}

function Write-Inventory {
    param([object]$Data, [string]$Path)
    $Data | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $Path -Encoding utf8NoBOM
}

function Assert-Rejected {
    param(
        [string]$Name,
        [scriptblock]$Mutate,
        [string]$Pattern,
        [string[]]$ExtraArgs = @()
    )
    $data = Read-Inventory
    & $Mutate $data
    $path = Join-Path $TempRoot "$Name.json"
    Write-Inventory -Data $data -Path $path
    $result = Invoke-Verifier -ManifestPath $path -ExtraArgs $ExtraArgs
    if ($result.ExitCode -eq 0) {
        throw "Negative case '$Name' unexpectedly passed verification:`n$($result.Output)"
    }
    if ($result.Output -notmatch $Pattern) {
        throw "Negative case '$Name' failed for the wrong reason (expected '$Pattern'):`n$($result.Output)"
    }
    return $Name
}

$passed = [System.Collections.Generic.List[string]]::new()
try {
    $null = New-Item -ItemType Directory -Force -Path $TempRoot

    $generated = Join-Path $TempRoot 'generated-inventory.json'
    & pwsh -NoProfile -File $Generator -RepositoryRoot $RepositoryRoot -OutputPath $generated | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Resource license inventory generator failed to run.'
    }
    $committedHash = (Get-FileHash -LiteralPath $Inventory -Algorithm SHA256).Hash
    $generatedHash = (Get-FileHash -LiteralPath $generated -Algorithm SHA256).Hash
    if ($committedHash -ne $generatedHash) {
        throw 'Committed resource license inventory is not reproducible from the generator.'
    }
    $passed.Add('committed inventory is reproducible from frontend/package.json and frontend/public')

    $valid = Invoke-Verifier -ManifestPath $Inventory
    if ($valid.ExitCode -ne 0) {
        throw "Valid resource license inventory failed verification:`n$($valid.Output)"
    }
    $passed.Add('valid inventory passes')

    $passed.Add((Assert-Rejected -Name 'source-sha' -Pattern 'source SHA drifted' -Mutate {
        param($data)
        $data.source.source_sha = '0000000000000000000000000000000000000000'
    }))
    $passed.Add((Assert-Rejected -Name 'legal-clearance' -Pattern 'must not assert legal clearance' -Mutate {
        param($data)
        $data.legal_clearance = 'ASSERTED'
    }))
    $passed.Add((Assert-Rejected -Name 'missing-source' -Pattern "Resource 'deerflow.brand.github' source is missing" -Mutate {
        param($data)
        ($data.brand_assets | Where-Object id -eq 'deerflow.brand.github')[0].source_path = 'frontend/public/images/plugins/not-there.svg'
    }))
    $passed.Add((Assert-Rejected -Name 'hash-drift' -Pattern "Resource 'deerflow.brand.github' hash drifted" -Mutate {
        param($data)
        ($data.brand_assets | Where-Object id -eq 'deerflow.brand.github')[0].sha256 = '0000000000000000000000000000000000000000000000000000000000000000'
    }))
    $passed.Add((Assert-Rejected -Name 'false-source-verified' -Pattern 'without bundled license evidence' -Mutate {
        param($data)
        $asset = ($data.brand_assets | Where-Object id -eq 'deerflow.brand.lark')[0]
        $asset.license_basis = 'ORIGIN_STATEMENT'
        $asset.review_status = 'SOURCE_VERIFIED'
    }))
    $passed.Add((Assert-Rejected -Name 'duplicate-id' -Pattern 'duplicate id' -Mutate {
        param($data)
        $data.brand_assets[1].id = $data.brand_assets[0].id
    }))
    $passed.Add((Assert-Rejected -Name 'asset-row-removed' -Pattern 'Bundled frontend asset is absent from the inventory' -Mutate {
        param($data)
        $data.brand_assets = @($data.brand_assets | Where-Object id -ne 'deerflow.brand.github')
        $data.required_ids = @($data.required_ids | Where-Object { $_ -ne 'deerflow.brand.github' })
    }))
    $passed.Add((Assert-Rejected -Name 'root-favicon-omitted' -Pattern 'Bundled frontend asset is absent from the inventory' -Mutate {
        param($data)
        $data.brand_assets = @($data.brand_assets | Where-Object id -ne 'deerflow.root-favicon')
        $data.required_ids = @($data.required_ids | Where-Object { $_ -ne 'deerflow.root-favicon' })
    }))
    $passed.Add((Assert-Rejected -Name 'thumbnail-omitted' -Pattern 'Public media asset is absent from the inventory' -Mutate {
        param($data)
        $thumbnail = @($data.public_media | Where-Object { $_.source_path -like 'frontend/public/images/*' -and $_.source_path -notlike '*/plugins/*' })[0]
        $data.public_media = @($data.public_media | Where-Object id -ne $thumbnail.id)
        $data.required_ids = @($data.required_ids | Where-Object { $_ -ne $thumbnail.id })
    }))
    $passed.Add((Assert-Rejected -Name 'demo-media-omitted' -Pattern 'Public media asset is absent from the inventory' -Mutate {
        param($data)
        $demo = @($data.public_media | Where-Object { $_.source_path -like 'frontend/public/demo/*' })[0]
        $data.public_media = @($data.public_media | Where-Object id -ne $demo.id)
        $data.required_ids = @($data.required_ids | Where-Object { $_ -ne $demo.id })
    }))
    $passed.Add((Assert-Rejected -Name 'unexpected-media' -Pattern 'Inventory media asset is absent from the frozen public tree' -Mutate {
        param($data)
        $clone = $data.public_media[0].PSObject.Copy()
        $clone.id = 'deerflow.media.not-real'
        $clone.source_path = 'frontend/public/images/plugins/README.md'
        $clone.sha256 = (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot 'frontend/public/images/plugins/README.md') -Algorithm SHA256).Hash.ToLowerInvariant()
        $data.public_media = @($data.public_media) + @($clone)
    }))
    $passed.Add((Assert-Rejected -Name 'required-skill-removed' -Pattern 'Frozen public Skill is absent from the inventory' -Mutate {
        param($data)
        $data.non_imported_skills = @($data.non_imported_skills | Where-Object id -ne 'deerflow.skill.bootstrap')
        $data.required_ids = @($data.required_ids | Where-Object { $_ -ne 'deerflow.skill.bootstrap' })
    }))
    $passed.Add((Assert-Rejected -Name 'unexpected-skill' -Pattern 'Inventory Skill is not part of the frozen skills/public set' -Mutate {
        param($data)
        $clone = $data.non_imported_skills[0].PSObject.Copy()
        $clone.id = 'deerflow.skill.not-in-frozen-set'
        $data.non_imported_skills = @($data.non_imported_skills) + @($clone)
    }))
    $passed.Add((Assert-Rejected -Name 'skill-mislabeled-imported' -Pattern 'was mislabeled as imported' -Mutate {
        param($data)
        $data.non_imported_skills[0].import_status = 'BUNDLED'
    }))
    $passed.Add((Assert-Rejected -Name 'skill-sha-drift' -Pattern 'SKILL.md SHA drifted from the frozen excerpt' -Mutate {
        param($data)
        $data.non_imported_skills[0].sha256 = '0000000000000000000000000000000000000000000000000000000000000000'
    }))
    $passed.Add((Assert-Rejected -Name 'skill-local-copy' -Pattern 'has a local copy but is marked NOT_IMPORTED' -Mutate {
        param($data)
        $data.non_imported_skills[0].intended_local_path = 'frontend'
    }))
    $passed.Add((Assert-Rejected -Name 'dependency-missing' -Pattern "Dependency 'react' declared in frontend/package.json is missing from the inventory" -Mutate {
        param($data)
        $data.frontend_dependencies.packages = @($data.frontend_dependencies.packages | Where-Object name -ne 'react')
    }))
    $passed.Add((Assert-Rejected -Name 'dependency-extra' -Pattern "not declared in frontend/package.json" -Mutate {
        param($data)
        $data.frontend_dependencies.packages = @($data.frontend_dependencies.packages) + @([pscustomobject]@{
            name = 'not-a-real-dependency'
            section = 'dependencies'
            declared_range = '^1.0.0'
            import_status = 'REFERENCED'
            license_expression = 'UNKNOWN'
            license_basis = 'PACKAGE_MANIFEST_ONLY'
            license_basis_verified = $true
            review_status = 'UNKNOWN'
        })
    }))
    $passed.Add((Assert-Rejected -Name 'dependency-range-drift' -Pattern 'declared range drifted' -Mutate {
        param($data)
        $data.frontend_dependencies.packages[0].declared_range = '^9.9.9'
    }))
    $passed.Add((Assert-Rejected -Name 'dependency-false-verified' -Pattern 'claims SOURCE_VERIFIED with an unknown license' -Mutate {
        param($data)
        $data.frontend_dependencies.packages[0].review_status = 'SOURCE_VERIFIED'
    }))
    $passed.Add((Assert-Rejected -Name 'notice-claims-generated' -Pattern 'claims GENERATED without a notice file' -Mutate {
        param($data)
        $data.frontend_dependencies.notice.status = 'GENERATED'
    }))
    $fontProbeRoot = Join-Path $TempRoot 'font-probe'
    $null = New-Item -ItemType Directory -Force -Path $fontProbeRoot
    Set-Content -LiteralPath (Join-Path $fontProbeRoot 'rogue.woff') -Value 'not-a-real-font'
    $passed.Add((Assert-Rejected -Name 'unaccounted-font' -Pattern 'Vendored font files are unaccounted' -ExtraArgs @('-FontScanRoot', $fontProbeRoot) -Mutate {
        param($data)
    }))

    foreach ($check in $passed) {
        Write-Host "PASS $check"
    }
    Write-Host "W00 resource license inventory tests passed: $($passed.Count) checks."
}
finally {
    $resolved = [System.IO.Path]::GetFullPath($TempRoot)
    $tempParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if ($resolved.StartsWith($tempParent + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
