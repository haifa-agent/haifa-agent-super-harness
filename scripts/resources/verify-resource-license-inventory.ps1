[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$InventoryPath,
    [string]$PackageJsonPath,
    [string]$FrontendRoot,
    [string]$FontScanRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedSourceSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'
$RootFaviconPath = 'frontend/public/favicon.ico'
$PluginAssetPrefix = 'frontend/public/images/plugins/'
$BrandAssetExtensions = @('.svg', '.ico', '.png', '.jpg', '.jpeg', '.webp')
$PublicMediaExtensions = @('.svg', '.ico', '.png', '.jpg', '.jpeg', '.webp', '.mp4')
$AllowedReviewStatuses = @('SOURCE_VERIFIED', 'REVIEW_NEEDED', 'UNKNOWN')
$AllowedImportStatuses = @('BUNDLED', 'REFERENCED', 'NOT_IMPORTED', 'NONE_VENDORED')
$AllowedLicenseBasis = @('BUNDLED_LICENSE_TEXT', 'ORIGIN_STATEMENT', 'PACKAGE_MANIFEST_ONLY', 'CONTROLLER_EXCERPT', 'NONE')
$AllowedBrandCategories = @('brand_asset', 'root_favicon')
$FontExtensions = @('.woff', '.woff2', '.ttf', '.otf')

# Independently fixed frozen skills/public set: SKILL.md digest from the
# controller-provided frozen source, not derived from the inventory manifest.
$ExpectedSkillMdSha = [ordered]@{
    'deerflow.skill.academic-paper-review' = 'a0285baaeacc916bf45751c06db37745b195ddd4ec30e271ec1472f6cd48e8aa'
    'deerflow.skill.bootstrap' = 'd117997b877f80da7411d33b457878ad9ae0a649f43781a74c8b09707984356d'
    'deerflow.skill.chart-visualization' = 'eb44160e5a51bc0951696c65c13623bb7f686c140b6fd44e6e588b43c9911840'
    'deerflow.skill.claude-to-deerflow' = 'b05a6fa8c64ba4137b09a5f61278b6caa0384b2b4e4330ee9676e2485a53269e'
    'deerflow.skill.code-documentation' = '75721d7bc1b294592dc31a1e1d92ff9c390115af4b08ed42e7e0a2b094060676'
    'deerflow.skill.consulting-analysis' = '5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e'
    'deerflow.skill.data-analysis' = 'd9383f31df40250c7dd58b31f3518bc80c95f670eada6e8222c7925d80a0b3a1'
    'deerflow.skill.deep-research' = '04712f4daa7937cc1f8dabc1a086a9549e670eb3fa3f1a8d30abdc3ea8b31a79'
    'deerflow.skill.find-skills' = '0fccfaeacc4c71bfc93aeabf06d2d5d17480d4a09cb024c643a90b14dc024aba'
    'deerflow.skill.frontend-design' = 'f187caf13081bd3b5509f4c7424b7b507e3140020daaf3454b9181d1af4f61ca'
    'deerflow.skill.github-deep-research' = '7add6956b424fbe5d4fcf85ea977b7b663ad2d810b3157a69953401db9c7acdb'
    'deerflow.skill.image-generation' = '9e5f4d09a8440ef6cca6f3b0ec35777259b6eadd027bfa5db9baa4ee13812f4e'
    'deerflow.skill.music-generation' = 'b8b17571b82357dea768c5aa24437eada4a717a2d7c6fad082f6bb63cde6d884'
    'deerflow.skill.newsletter-generation' = 'cc66adb99053a781d6b5ce65f5e698c62384b8793421d15067d880e7b32538dc'
    'deerflow.skill.podcast-generation' = '8b5e640f8f24741a126d0a824c10a96a8635c3a9997d327200ec3e29c922059b'
    'deerflow.skill.ppt-generation' = '1c3e00433b754365390c9ffca4fcc12061f42993cc612b68bf77cd778affc090'
    'deerflow.skill.skill-creator' = '313a95a145d9926693f8c053b63047212dd1db217c061891865b6546c4e70d02'
    'deerflow.skill.skill-reviewer' = '68cff70a729660fed0fbf01b9ff4ec408bab83ea4e5997811508dc394e1c185d'
    'deerflow.skill.surprise-me' = 'e93e72ca445d112fa46c8b4d1b355cd97a51448ad20693f1279296523944d01a'
    'deerflow.skill.systematic-literature-review' = '9fe4891e86b29d0f0f192bddab4ff913b123ffd764a2af737a4d6bb334209b6a'
    'deerflow.skill.vercel-deploy-claimable' = '92e8165ce59f1da03f7850fc698ee7b4182cbd87e05e8857b555b649d7ee1965'
    'deerflow.skill.video-generation' = 'cbd6a3ef76c4388312476768fd4a34f0a9bc27ffe594215784b770d4eac7c765'
    'deerflow.skill.web-design-guidelines' = 'f4647ca866a3accf763777f83e7682954f0187cd6bea7eea0399796652414e8f'
}
$ExpectedNestedSkillLicenseSha = [ordered]@{
    'deerflow.skill.frontend-design' = '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594'
    'deerflow.skill.skill-creator' = '58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd'
}

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
}
$RepositoryRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
if ([string]::IsNullOrWhiteSpace($InventoryPath)) {
    $InventoryPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-license-inventory.json'
}
if ([string]::IsNullOrWhiteSpace($PackageJsonPath)) {
    $PackageJsonPath = Join-Path $RepositoryRoot 'frontend/package.json'
}
if ([string]::IsNullOrWhiteSpace($FrontendRoot)) {
    $FrontendRoot = Join-Path $RepositoryRoot 'frontend'
}
if ([string]::IsNullOrWhiteSpace($FontScanRoot)) {
    $FontScanRoot = $FrontendRoot
}
$InventoryPath = [System.IO.Path]::GetFullPath($InventoryPath)
$PackageJsonPath = [System.IO.Path]::GetFullPath($PackageJsonPath)
$FrontendRoot = [System.IO.Path]::GetFullPath($FrontendRoot)
$FontScanRoot = [System.IO.Path]::GetFullPath($FontScanRoot)
# Relative frontend/... resource paths resolve against the parent of FrontendRoot
# so the inventory manifest can be verified from a different location.
$AssetBase = [System.IO.Path]::GetDirectoryName($FrontendRoot)

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected '$Expected', actual '$Actual')"
    }
}

function Assert-OneOf {
    param([object]$Actual, [object[]]$Allowed, [string]$Message)
    if ($Allowed -notcontains $Actual) {
        throw "$Message (allowed '$($Allowed -join ', ')', actual '$Actual')"
    }
}

function Assert-FileHash {
    param([string]$Path, [string]$ExpectedSha, [string]$Message)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Message (missing file: $Path)"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal $actual $ExpectedSha.ToLowerInvariant() $Message
}

if (-not (Test-Path -LiteralPath $InventoryPath -PathType Leaf)) {
    throw "Resource license inventory is missing: $InventoryPath"
}
$inventory = Get-Content -LiteralPath $InventoryPath -Raw | ConvertFrom-Json -Depth 100

Assert-Equal $inventory.schema_version 'w00.resource-license-inventory/v1' 'Unexpected resource license inventory schema'
Assert-Equal $inventory.scope 'incremental_inventory' 'Resource license inventory must remain an incremental inventory'
Assert-Equal $inventory.status 'SOURCE_VERIFIED' 'Resource license inventory static status must be SOURCE_VERIFIED'
Assert-Equal $inventory.runtime_status 'NOT_RUN' 'Resource license inventory must not claim runtime PASS or FAIL'
Assert-Equal $inventory.legal_clearance 'NOT_ASSERTED' 'Resource inventory must not assert legal clearance'
Assert-Equal $inventory.source.repository 'https://github.com/bytedance/deer-flow.git' 'Resource license inventory repository drifted'
Assert-Equal $inventory.source.source_sha $ExpectedSourceSha 'Resource inventory source SHA drifted'

$seenIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($resource in @($inventory.brand_assets) + @($inventory.public_media) + @($inventory.non_imported_skills)) {
    $id = [string]$resource.id
    if (-not $seenIds.Add($id)) {
        throw "Resource inventory contains duplicate id: $id"
    }
}

foreach ($resource in @($inventory.brand_assets)) {
    $id = [string]$resource.id
    Assert-OneOf $resource.category $AllowedBrandCategories "Resource '$id' has an invalid category"
    Assert-Equal $resource.import_status 'BUNDLED' "Bundled brand asset '$id' must record import_status BUNDLED"
    Assert-OneOf $resource.review_status $AllowedReviewStatuses "Resource '$id' has an invalid review_status"
    Assert-OneOf $resource.license_basis $AllowedLicenseBasis "Resource '$id' has an invalid license_basis"
    $licenseBasis = [string]$resource.license_basis
    if ($licenseBasis -ne 'BUNDLED_LICENSE_TEXT' -and [string]$resource.review_status -eq 'SOURCE_VERIFIED') {
        throw "Resource '$id' declares SOURCE_VERIFIED without bundled license evidence"
    }
    $sourcePath = [string]$resource.source_path
    if (-not $sourcePath.StartsWith('frontend/', [System.StringComparison]::Ordinal)) {
        throw "Brand asset '$id' is not rooted in the imported frontend/"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $AssetBase $sourcePath) -PathType Leaf)) {
        throw "Resource '$id' source is missing at frozen frontend import"
    }
    Assert-FileHash -Path (Join-Path $AssetBase $sourcePath) -ExpectedSha ([string]$resource.sha256) -Message "Resource '$id' hash drifted"
    if ($licenseBasis -eq 'BUNDLED_LICENSE_TEXT') {
        $licensePath = [string]$resource.evidence.license_path
        if ([string]::IsNullOrWhiteSpace($licensePath) -or
            -not (Test-Path -LiteralPath (Join-Path $AssetBase $licensePath) -PathType Leaf)) {
            throw "Resource '$id' bundled license text is missing"
        }
        Assert-FileHash -Path (Join-Path $AssetBase $licensePath) -ExpectedSha ([string]$resource.evidence.license_sha256) -Message "Resource '$id' bundled license hash drifted"
        Assert-Equal $resource.evidence.kind 'BUNDLED_LICENSE_TEXT' "Resource '$id' evidence kind does not match its bundled license basis"
    }
    else {
        Assert-OneOf $resource.review_status @('REVIEW_NEEDED', 'UNKNOWN') "Resource '$id' without bundled license text cannot be marked reviewed"
    }
}

foreach ($media in @($inventory.public_media)) {
    $id = [string]$media.id
    Assert-Equal $media.category 'public_media' "Media '$id' has an invalid category"
    Assert-Equal $media.import_status 'BUNDLED' "Bundled media '$id' must record import_status BUNDLED"
    Assert-OneOf $media.review_status $AllowedReviewStatuses "Media '$id' has an invalid review_status"
    Assert-OneOf $media.license_basis $AllowedLicenseBasis "Media '$id' has an invalid license_basis"
    $mediaBasis = [string]$media.license_basis
    if ($mediaBasis -ne 'BUNDLED_LICENSE_TEXT' -and [string]$media.review_status -eq 'SOURCE_VERIFIED') {
        throw "Media '$id' declares SOURCE_VERIFIED without bundled license evidence"
    }
    $sourcePath = [string]$media.source_path
    if (-not $sourcePath.StartsWith('frontend/public/', [System.StringComparison]::Ordinal)) {
        throw "Media '$id' is not rooted in frontend/public/"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $AssetBase $sourcePath) -PathType Leaf)) {
        throw "Media '$id' source is missing at frozen frontend import"
    }
    Assert-FileHash -Path (Join-Path $AssetBase $sourcePath) -ExpectedSha ([string]$media.sha256) -Message "Media '$id' hash drifted"
    if ($mediaBasis -ne 'BUNDLED_LICENSE_TEXT') {
        Assert-OneOf $media.review_status @('REVIEW_NEEDED', 'UNKNOWN') "Media '$id' without bundled license text cannot be marked reviewed"
    }
}

# Independent media completeness: scan the whole frozen frontend/public tree.
# Plugin logos plus the root favicon stay in brand_assets; every other media
# file (service thumbnails, demo image/video outputs) belongs to public_media.
$publicRoot = Join-Path $FrontendRoot 'public'
if (-not (Test-Path -LiteralPath $publicRoot -PathType Container)) {
    throw "Frozen frontend public tree is missing: $publicRoot"
}
$frozenMediaPaths = @(Get-ChildItem -LiteralPath $publicRoot -Recurse -File |
    Where-Object { $PublicMediaExtensions -contains $_.Extension.ToLowerInvariant() } |
    ForEach-Object { [System.IO.Path]::GetRelativePath($AssetBase, $_.FullName).Replace('\', '/') })
$frozenBrandPaths = @($frozenMediaPaths | Where-Object {
    $_ -eq $RootFaviconPath -or $_.StartsWith($PluginAssetPrefix, [System.StringComparison]::Ordinal)
})
$frozenPublicMediaPaths = @($frozenMediaPaths | Where-Object {
    $_ -ne $RootFaviconPath -and -not $_.StartsWith($PluginAssetPrefix, [System.StringComparison]::Ordinal)
})
if (-not (Test-Path -LiteralPath (Join-Path $AssetBase $RootFaviconPath) -PathType Leaf)) {
    throw "Frozen root favicon is missing from the imported frontend: $RootFaviconPath"
}
$manifestAssetPaths = @($inventory.brand_assets | ForEach-Object { [string]$_.source_path })
foreach ($frozenAsset in $frozenBrandPaths) {
    if ($manifestAssetPaths -notcontains $frozenAsset) {
        throw "Bundled frontend asset is absent from the inventory: $frozenAsset"
    }
}
foreach ($manifestAsset in $manifestAssetPaths) {
    if ($frozenBrandPaths -notcontains $manifestAsset) {
        throw "Inventory asset is absent from the frozen frontend import: $manifestAsset"
    }
}
$manifestMediaPaths = @($inventory.public_media | ForEach-Object { [string]$_.source_path })
foreach ($frozenMedia in $frozenPublicMediaPaths) {
    if ($manifestMediaPaths -notcontains $frozenMedia) {
        throw "Public media asset is absent from the inventory: $frozenMedia"
    }
}
foreach ($manifestMedia in $manifestMediaPaths) {
    if ($frozenPublicMediaPaths -notcontains $manifestMedia) {
        throw "Inventory media asset is absent from the frozen public tree: $manifestMedia"
    }
}

# Independent Skill completeness: the 23 frozen public Skill packages are fixed
# here, so removing a row together with its required_ids entry is still rejected.
$ExpectedSkillIds = @($ExpectedSkillMdSha.Keys)
$manifestSkillIds = @($inventory.non_imported_skills | ForEach-Object { [string]$_.id })
foreach ($expectedSkillId in $ExpectedSkillIds) {
    if ($manifestSkillIds -notcontains $expectedSkillId) {
        throw "Frozen public Skill is absent from the inventory: $expectedSkillId"
    }
}
foreach ($manifestSkillId in $manifestSkillIds) {
    if ($ExpectedSkillIds -notcontains $manifestSkillId) {
        throw "Inventory Skill is not part of the frozen skills/public set: $manifestSkillId"
    }
}

$fontEntry = $inventory.fonts
Assert-OneOf $fontEntry.import_status @('NONE_VENDORED') 'Font inventory has an invalid import_status'
Assert-OneOf $fontEntry.review_status $AllowedReviewStatuses 'Font inventory has an invalid review_status'
if (-not (Test-Path -LiteralPath $FontScanRoot -PathType Container)) {
    throw "Root for the font scan is missing: $FontScanRoot"
}
$fontFiles = @(Get-ChildItem -LiteralPath $FontScanRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $FontExtensions -contains $_.Extension.ToLowerInvariant() } |
    Where-Object { $_.FullName -notmatch '[\\/](node_modules|\.next|target)[\\/]' })
$declaredFontCount = [int]$fontEntry.vendored_font_count
if ($fontFiles.Count -ne $declaredFontCount) {
    throw "Vendored font files are unaccounted (declared $declaredFontCount, found $($fontFiles.Count): $(@($fontFiles | Select-Object -ExpandProperty FullName) -join ', '))"
}

if (-not (Test-Path -LiteralPath $PackageJsonPath -PathType Leaf)) {
    throw "Frontend package manifest is missing: $PackageJsonPath"
}
$packageJson = Get-Content -LiteralPath $PackageJsonPath -Raw | ConvertFrom-Json -Depth 100
$declaredDependencies = @{}
foreach ($section in @('dependencies', 'devDependencies')) {
    foreach ($property in @($packageJson.$section.PSObject.Properties)) {
        $declaredDependencies[[string]$property.Name] = [pscustomobject]@{
            Section = $section
            Range = [string]$property.Value
        }
    }
}
if ($declaredDependencies.Count -eq 0) {
    throw 'frontend/package.json declares no dependencies to audit.'
}

$dependencyNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($dependency in @($inventory.frontend_dependencies.packages)) {
    $name = [string]$dependency.name
    if (-not $dependencyNames.Add($name)) {
        throw "Resource inventory contains duplicate dependency: $name"
    }
    if (-not $declaredDependencies.ContainsKey($name)) {
        throw "Inventory dependency '$name' is not declared in frontend/package.json"
    }
    $declared = $declaredDependencies[$name]
    if ([string]$dependency.section -ne $declared.Section) {
        throw "Dependency '$name' section drifted"
    }
    if ([string]$dependency.declared_range -ne $declared.Range) {
        throw "Dependency '$name' declared range drifted"
    }
    Assert-Equal $dependency.import_status 'REFERENCED' "Dependency '$name' must record import_status REFERENCED"
    Assert-OneOf $dependency.review_status $AllowedReviewStatuses "Dependency '$name' has an invalid review_status"
    if ([string]$dependency.review_status -eq 'SOURCE_VERIFIED' -and [string]$dependency.license_expression -eq 'UNKNOWN') {
        throw "Dependency '$name' claims SOURCE_VERIFIED with an unknown license"
    }
}
foreach ($name in $declaredDependencies.Keys) {
    if (-not $dependencyNames.Contains($name)) {
        throw "Dependency '$name' declared in frontend/package.json is missing from the inventory"
    }
}
$notice = $inventory.frontend_dependencies.notice
Assert-OneOf ([string]$notice.status) @('NOT_GENERATED', 'GENERATED') 'Dependency notice inventory has an invalid status'
if ([string]$notice.status -eq 'GENERATED') {
    $hasNoticePath = $notice.PSObject.Properties.Name -contains 'path'
    $noticePath = if ($hasNoticePath) { [string]$notice.path } else { '' }
    if ([string]::IsNullOrWhiteSpace($noticePath) -or
        -not (Test-Path -LiteralPath (Join-Path $RepositoryRoot $noticePath) -PathType Leaf)) {
        throw 'Dependency notice inventory claims GENERATED without a notice file'
    }
}

foreach ($skill in @($inventory.non_imported_skills)) {
    $id = [string]$skill.id
    Assert-Equal $skill.category 'non_imported_skill' "Skill '$id' has an invalid category"
    if ([string]$skill.import_status -ne 'NOT_IMPORTED') {
        throw "Non-imported skill '$id' was mislabeled as imported"
    }
    if (-not ([string]$skill.source_path).StartsWith('skills/public/', [System.StringComparison]::Ordinal) -or
        -not ([string]$skill.source_path).EndsWith('/SKILL.md', [System.StringComparison]::Ordinal)) {
        throw "Non-imported skill '$id' has an invalid frozen source path"
    }
    $intendedLocalPath = [string]$skill.intended_local_path
    if ([string]::IsNullOrWhiteSpace($intendedLocalPath)) {
        throw "Non-imported skill '$id' has no intended local path"
    }
    if (Test-Path -LiteralPath (Join-Path $RepositoryRoot $intendedLocalPath)) {
        throw "Non-imported skill '$id' has a local copy but is marked NOT_IMPORTED"
    }
    if (-not $ExpectedSkillMdSha.Contains($id)) {
        throw "Non-imported skill '$id' has no frozen SKILL.md digest to verify"
    }
    Assert-Equal ([string]$skill.sha256) $ExpectedSkillMdSha[$id] "Non-imported skill '$id' SKILL.md SHA drifted from the frozen excerpt"
    Assert-Equal $skill.license_basis 'CONTROLLER_EXCERPT' "Non-imported skill '$id' must record a controller-provided excerpt"
    Assert-Equal $skill.license_basis_verified $false "Non-imported skill '$id' cannot claim local license verification"
    Assert-OneOf $skill.review_status @('REVIEW_NEEDED', 'UNKNOWN') "Non-imported skill '$id' cannot be marked reviewed"
    $hasNestedLicense = $skill.PSObject.Properties.Name -contains 'nested_license_sha256'
    if ($ExpectedNestedSkillLicenseSha.Contains($id)) {
        if (-not $hasNestedLicense) {
            throw "Non-imported skill '$id' lost its nested license evidence"
        }
        Assert-Equal ([string]$skill.nested_license_sha256) $ExpectedNestedSkillLicenseSha[$id] "Non-imported skill '$id' nested license SHA drifted"
        Assert-Equal $skill.review_status 'REVIEW_NEEDED' "Non-imported skill '$id' with nested license text must stay REVIEW_NEEDED"
        Assert-Equal $skill.license_expression 'Apache-2.0' "Non-imported skill '$id' nested license expression drifted"
    }
    else {
        if ($hasNestedLicense) {
            throw "Non-imported skill '$id' claims nested license evidence that was not supplied"
        }
        Assert-Equal $skill.license_expression 'UNKNOWN' "Non-imported skill '$id' without license evidence must stay UNKNOWN"
        Assert-Equal $skill.review_status 'UNKNOWN' "Non-imported skill '$id' without license evidence must stay UNKNOWN"
    }
}

foreach ($requiredId in @($inventory.required_ids)) {
    if (-not $seenIds.Contains([string]$requiredId)) {
        throw "Required resource is missing: $requiredId"
    }
}
foreach ($expectedSkillId in $ExpectedSkillIds) {
    if (@($inventory.required_ids) -notcontains $expectedSkillId) {
        throw "Frozen public Skill is absent from required_ids: $expectedSkillId"
    }
}

$brandCount = @($inventory.brand_assets).Count
$mediaCount = @($inventory.public_media).Count
$dependencyCount = @($inventory.frontend_dependencies.packages).Count
$skillCount = @($inventory.non_imported_skills).Count
Write-Host "Verified resource license inventory at $ExpectedSourceSha"
Write-Host "Brand/favicon assets: $brandCount (review=$((@($inventory.brand_assets | Group-Object review_status | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })) -join ', ')); public media: $mediaCount; fonts=$declaredFontCount"
Write-Host "Frontend dependencies: $dependencyCount (notice=$($notice.status)); non-imported skills: $skillCount (review=$((@($inventory.non_imported_skills | Group-Object review_status | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })) -join ', '))"
Write-Host "legal_clearance=$($inventory.legal_clearance); runtime_status=$($inventory.runtime_status)"
