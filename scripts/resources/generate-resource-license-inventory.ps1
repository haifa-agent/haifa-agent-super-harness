[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedSourceSha = 'e545c28ac301e6f9feae247c513d8ea8f83df294'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
}
$RepositoryRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $RepositoryRoot 'contracts/upstream/deerflow/resource-license-inventory.json'
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

$packageJsonPath = Join-Path $RepositoryRoot 'frontend/package.json'
if (-not (Test-Path -LiteralPath $packageJsonPath -PathType Leaf)) {
    throw "Frontend package manifest is missing: $packageJsonPath"
}
$packageJson = Get-Content -LiteralPath $packageJsonPath -Raw | ConvertFrom-Json -Depth 100

$simpleIconsLicensePath = 'frontend/public/images/plugins/SIMPLE-ICONS-LICENSE.md'
$lobeIconsLicensePath = 'frontend/public/images/plugins/LOBE-ICONS-LICENSE.md'
$pluginOriginPath = 'frontend/public/images/plugins/README.md'

function New-BundledAsset {
    param(
        [string]$Id,
        [string]$Name,
        [string]$SourcePath,
        [string]$Sha256,
        [string]$LicenseExpression,
        [string]$LicenseBasis,
        [string]$LicensePath,
        [string]$ReviewStatus,
        [string]$ReviewNote,
        [string]$Category = 'brand_asset'
    )
    if ($LicenseBasis -eq 'NONE') {
        $evidence = [ordered]@{
            kind = $LicenseBasis
            location = $SourcePath
        }
    }
    else {
        $evidence = [ordered]@{
            kind = $LicenseBasis
            location = if ([string]::IsNullOrWhiteSpace($LicensePath)) { $pluginOriginPath } else { $LicensePath }
            origin_statement = $pluginOriginPath
        }
        if (-not [string]::IsNullOrWhiteSpace($LicensePath)) {
            $evidence.license_path = $LicensePath
            $evidence.license_sha256 = $script:BundledLicenseSha[$LicensePath]
        }
    }
    return [ordered]@{
        id = $Id
        category = $Category
        display_name = $Name
        source_path = $SourcePath
        sha256 = $Sha256
        import_status = 'BUNDLED'
        license_expression = $LicenseExpression
        license_basis = $LicenseBasis
        license_basis_verified = $true
        review_status = $ReviewStatus
        review_note = $ReviewNote
        evidence = $evidence
    }
}

$script:BundledLicenseSha = [ordered]@{
    $simpleIconsLicensePath = 'cf05aaae5b87aad2245e052fbc8f19155f6b4b448c3148265ab12413dd4091c9'
    $lobeIconsLicensePath = 'add9d7531d1b21646317a8958e38fc727506fa39d24bdecb44154d943c82753a'
}

$simpleIcons = @(
    [ordered]@{ name = 'github'; sha = '476ba7aa67b86da7d6e7567b08a4bf0eb1a2fd28da5fc243f8ee39a2f1ea6773' }
    [ordered]@{ name = 'notion'; sha = 'fdddc8f06178df169036d3f5fc336eb98b15cad375afa04d0a5cd7aa61b65739' }
    [ordered]@{ name = 'hubspot'; sha = '5e3d32c06f065e541829e34c2d2429b1fee686a49020b1974b3fec228916f55e' }
    [ordered]@{ name = 'jira'; sha = 'f831a691d67e7a187a358ea91f98e5d51ef93345d905651258d0fbd1f4d5f363' }
    [ordered]@{ name = 'postgresql'; sha = 'bc45b1c56d0b1501dea387dbec4fda450d2ebc35244a620791815509d8c41dba' }
    [ordered]@{ name = 'brave'; sha = 'ab03dbd270ceb53def63a0587f41d74b286056fdbe523cfcb8e3bfe88763b02b' }
)
$lobeIcons = @(
    [ordered]@{ name = 'exa'; sha = '8d8113b2d6796bc680cccba6578a1487e49407adf08bbe20fccd949b59a8371a' }
    [ordered]@{ name = 'firecrawl'; sha = '98993b111d1cb75edd0b1ce3a1e8bd3f85dcf5548072ffb959ac97ffe69ade59' }
)
$externalFavicons = @(
    [ordered]@{ name = 'lark'; file = 'lark.ico'; sha = '69da9be6053530750202e4f674d621274bd86a9fc23f44a16c5e6c7aa56dfd9e'; origin = 'https://www.feishu.cn/favicon.ico'; note = 'Feishu website favicon fetched from the public site; the README records the URL but no reuse grant.' }
    [ordered]@{ name = 'dingtalk'; file = 'dingtalk.ico'; sha = '86a24b5e0853f6d74f1ca5614e1c41c83fb0e7032c4e246fe9e0efc5baced36c'; origin = 'https://www.dingtalk.com'; note = 'Favicon linked by the DingTalk homepage; no explicit license text is bundled.' }
    [ordered]@{ name = 'wecom'; file = 'wecom.png'; sha = '5b95029b419b45aef63f876b9bb8d86705fbff36da4c51dfdd9f2d63a1de7f2a'; origin = 'https://work.weixin.qq.com'; note = '48px favicon linked by WeCom; no explicit license text is bundled.' }
    [ordered]@{ name = 'tencent-docs'; file = 'tencent-docs.ico'; sha = 'a41f0618d62d19d7907e4976a96985c6dd459984545db5924524b4e2f1e5ff44'; origin = 'https://docs.qq.com'; note = 'Favicon linked by Tencent Docs; no explicit license text is bundled.' }
    [ordered]@{ name = 'openviking'; file = 'openviking.png'; sha = '8da7815d68dd33eb358daa1bb5ca42f67cd1e0c0e11258ab690fb08cff635ab2'; origin = 'https://github.com/volcengine/OpenViking/blob/main/docs/images/ov-logo-icon.png'; note = 'Logo fetched from a GitHub source path; the README records the URL but no reuse grant or license text.' }
)

$brandAssets = [System.Collections.Generic.List[object]]::new()
foreach ($icon in $simpleIcons) {
    $brandAssets.Add((New-BundledAsset `
        -Id "deerflow.brand.$($icon.name)" `
        -Name $icon.name `
        -SourcePath "frontend/public/images/plugins/$($icon.name).svg" `
        -Sha256 $icon.sha `
        -LicenseExpression 'CC0-1.0' `
        -LicenseBasis 'BUNDLED_LICENSE_TEXT' `
        -LicensePath $simpleIconsLicensePath `
        -ReviewStatus 'REVIEW_NEEDED' `
        -ReviewNote 'Simple Icons 16.31.0 CC0 text is bundled; the service name/logo trademark is not granted and needs human review before redistribution.'))
}
foreach ($icon in $lobeIcons) {
    $brandAssets.Add((New-BundledAsset `
        -Id "deerflow.brand.$($icon.name)" `
        -Name $icon.name `
        -SourcePath "frontend/public/images/plugins/$($icon.name).svg" `
        -Sha256 $icon.sha `
        -LicenseExpression 'MIT' `
        -LicenseBasis 'BUNDLED_LICENSE_TEXT' `
        -LicensePath $lobeIconsLicensePath `
        -ReviewStatus 'REVIEW_NEEDED' `
        -ReviewNote '@lobehub/icons-static-svg 1.95.0 MIT text is bundled; the service brand mark is not granted and needs human review.'))
}
foreach ($favicon in $externalFavicons) {
    $brandAssets.Add((New-BundledAsset `
        -Id "deerflow.brand.$($favicon.name)" `
        -Name $favicon.name `
        -SourcePath "frontend/public/images/plugins/$($favicon.file)" `
        -Sha256 $favicon.sha `
        -LicenseExpression 'UNKNOWN' `
        -LicenseBasis 'ORIGIN_STATEMENT' `
        -LicensePath '' `
        -ReviewStatus 'UNKNOWN' `
        -ReviewNote "$($favicon.note) Origin: $($favicon.origin)"))
}
$brandAssets.Add((New-BundledAsset `
    -Id 'deerflow.root-favicon' `
    -Name 'root-favicon' `
    -SourcePath 'frontend/public/favicon.ico' `
    -Sha256 'b139c92d057530040959e4e9bb88b06ab0cd294d29ec668951fc3655a0f6a459' `
    -LicenseExpression 'UNKNOWN' `
    -LicenseBasis 'NONE' `
    -LicensePath '' `
    -ReviewStatus 'UNKNOWN' `
    -ReviewNote 'Bundled root site favicon; the frozen tree carries no origin statement or license text for this file, so both the license and any trademark remain unverified.' `
    -Category 'root_favicon'))

function Get-PackageInventory {
    param([object]$PackageJson)
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($section in @('dependencies', 'devDependencies')) {
        foreach ($property in @($PackageJson.$section.PSObject.Properties)) {
            $entries.Add([pscustomobject]@{
                Name = [string]$property.Name
                Section = $section
                Range = [string]$property.Value
            })
        }
    }
    return $entries | Sort-Object Name
}

$packageEntries = @(Get-PackageInventory -PackageJson $packageJson)
if ($packageEntries.Count -eq 0) {
    throw 'frontend/package.json declares no dependencies.'
}
$dependencyEntries = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $packageEntries) {
    $dependencyEntries.Add([ordered]@{
        name = $entry.Name
        section = $entry.Section
        declared_range = $entry.Range
        import_status = 'REFERENCED'
        license_expression = 'UNKNOWN'
        license_basis = 'PACKAGE_MANIFEST_ONLY'
        license_basis_verified = $true
        review_status = 'UNKNOWN'
        review_note = 'Declared in frontend/package.json and pinned by pnpm-lock.yaml; no installed package license text or consolidated NOTICE is vendored in this repository, so the license terms remain unverified.'
    })
}

# All 23 public Skill packages under the frozen skills/public directory. The
# SKILL.md digest is the controller-provided frozen source anchor; only the two
# packages that also ship a nested LICENSE.txt carry license text evidence.
$skillSkillMdSha = [ordered]@{
    'academic-paper-review' = 'a0285baaeacc916bf45751c06db37745b195ddd4ec30e271ec1472f6cd48e8aa'
    'bootstrap' = 'd117997b877f80da7411d33b457878ad9ae0a649f43781a74c8b09707984356d'
    'chart-visualization' = 'eb44160e5a51bc0951696c65c13623bb7f686c140b6fd44e6e588b43c9911840'
    'claude-to-deerflow' = 'b05a6fa8c64ba4137b09a5f61278b6caa0384b2b4e4330ee9676e2485a53269e'
    'code-documentation' = '75721d7bc1b294592dc31a1e1d92ff9c390115af4b08ed42e7e0a2b094060676'
    'consulting-analysis' = '5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e'
    'data-analysis' = 'd9383f31df40250c7dd58b31f3518bc80c95f670eada6e8222c7925d80a0b3a1'
    'deep-research' = '04712f4daa7937cc1f8dabc1a086a9549e670eb3fa3f1a8d30abdc3ea8b31a79'
    'find-skills' = '0fccfaeacc4c71bfc93aeabf06d2d5d17480d4a09cb024c643a90b14dc024aba'
    'frontend-design' = 'f187caf13081bd3b5509f4c7424b7b507e3140020daaf3454b9181d1af4f61ca'
    'github-deep-research' = '7add6956b424fbe5d4fcf85ea977b7b663ad2d810b3157a69953401db9c7acdb'
    'image-generation' = '9e5f4d09a8440ef6cca6f3b0ec35777259b6eadd027bfa5db9baa4ee13812f4e'
    'music-generation' = 'b8b17571b82357dea768c5aa24437eada4a717a2d7c6fad082f6bb63cde6d884'
    'newsletter-generation' = 'cc66adb99053a781d6b5ce65f5e698c62384b8793421d15067d880e7b32538dc'
    'podcast-generation' = '8b5e640f8f24741a126d0a824c10a96a8635c3a9997d327200ec3e29c922059b'
    'ppt-generation' = '1c3e00433b754365390c9ffca4fcc12061f42993cc612b68bf77cd778affc090'
    'skill-creator' = '313a95a145d9926693f8c053b63047212dd1db217c061891865b6546c4e70d02'
    'skill-reviewer' = '68cff70a729660fed0fbf01b9ff4ec408bab83ea4e5997811508dc394e1c185d'
    'surprise-me' = 'e93e72ca445d112fa46c8b4d1b355cd97a51448ad20693f1279296523944d01a'
    'systematic-literature-review' = '9fe4891e86b29d0f0f192bddab4ff913b123ffd764a2af737a4d6bb334209b6a'
    'vercel-deploy-claimable' = '92e8165ce59f1da03f7850fc698ee7b4182cbd87e05e8857b555b649d7ee1965'
    'video-generation' = 'cbd6a3ef76c4388312476768fd4a34f0a9bc27ffe594215784b770d4eac7c765'
    'web-design-guidelines' = 'f4647ca866a3accf763777f83e7682954f0187cd6bea7eea0399796652414e8f'
}
# Only these two packages ship a nested LICENSE.txt in the frozen tree.
$skillNestedLicense = [ordered]@{
    'frontend-design' = [ordered]@{
        sha256 = '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594'
        license_expression = 'Apache-2.0'
    }
    'skill-creator' = [ordered]@{
        sha256 = '58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd'
        license_expression = 'Apache-2.0'
    }
}

$nonImportedSkills = [System.Collections.Generic.List[object]]::new()
foreach ($skillName in $skillSkillMdSha.Keys) {
    $skillMdSha = [string]$skillSkillMdSha[$skillName]
    $hasNestedLicense = $skillNestedLicense.Contains($skillName)
    $entry = [ordered]@{
        id = "deerflow.skill.$skillName"
        category = 'non_imported_skill'
        source_path = "skills/public/$skillName/SKILL.md"
        sha256 = $skillMdSha
        import_status = 'NOT_IMPORTED'
        intended_local_path = "skills/upstream/deerflow/$skillName"
        license_expression = 'UNKNOWN'
        license_basis = 'CONTROLLER_EXCERPT'
        license_basis_verified = $false
        review_status = 'UNKNOWN'
        review_note = 'Non-imported public Skill; only the frozen SKILL.md digest is anchored and no license text is supplied, so both the license and the full package contents remain unverified.'
        evidence = [ordered]@{
            kind = 'CONTROLLER_EXCERPT'
            location = 'controller-provided frozen Skill SKILL.md digest'
            sha256 = $skillMdSha
            sha256_verified_locally = $false
        }
        skill_md_sha256 = $skillMdSha
        manifest_reviewed = $false
    }
    if ($hasNestedLicense) {
        $nested = $skillNestedLicense[$skillName]
        $entry.license_expression = [string]$nested.license_expression
        $entry.review_status = 'REVIEW_NEEDED'
        $entry.review_note = 'Nested Apache-2.0 text is anchored through a controller-provided frozen excerpt, but the full Skill package (assets, scripts, prompts) was not imported or reviewed, so complete obligations remain open.'
        $entry.nested_license_path = "skills/public/$skillName/LICENSE.txt"
        $entry.nested_license_sha256 = [string]$nested.sha256
    }
    $nonImportedSkills.Add($entry)
}

# Every other bundled media file under frontend/public (service thumbnails,
# demo thread image outputs and the demo video). Plugin logos and the root
# favicon keep their dedicated brand_assets rows above.
$mediaExtensions = @('.svg', '.ico', '.png', '.jpg', '.jpeg', '.webp', '.mp4')
$publicRoot = Join-Path $RepositoryRoot 'frontend/public'
$rootFaviconSourcePath = 'frontend/public/favicon.ico'
$pluginAssetPrefix = 'frontend/public/images/plugins/'
if (-not (Test-Path -LiteralPath $publicRoot -PathType Container)) {
    throw "Frozen frontend public tree is missing: $publicRoot"
}
$publicMedia = [System.Collections.Generic.List[object]]::new()
$publicMediaFiles = @(Get-ChildItem -LiteralPath $publicRoot -Recurse -File |
    Where-Object { $mediaExtensions -contains $_.Extension.ToLowerInvariant() } |
    Sort-Object -Property FullName)
foreach ($file in $publicMediaFiles) {
    $sourcePath = [System.IO.Path]::GetRelativePath($RepositoryRoot, $file.FullName).Replace('\', '/')
    if ($sourcePath -eq $rootFaviconSourcePath) { continue }
    if ($sourcePath.StartsWith($pluginAssetPrefix, [System.StringComparison]::Ordinal)) { continue }
    $mediaRelativePath = $sourcePath.Substring('frontend/public/'.Length)
    $mediaId = 'deerflow.media.' + ($mediaRelativePath -replace '[^A-Za-z0-9]+', '-').Trim('-')
    $mediaSha = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $publicMedia.Add([ordered]@{
        id = $mediaId
        category = 'public_media'
        display_name = $mediaRelativePath
        source_path = $sourcePath
        sha256 = $mediaSha
        import_status = 'BUNDLED'
        license_expression = 'UNKNOWN'
        license_basis = 'NONE'
        license_basis_verified = $true
        review_status = 'UNKNOWN'
        review_note = 'Bundled frontend/public media asset with no local origin statement or license text; the license and any third-party or trademark rights remain unverified.'
        evidence = [ordered]@{
            kind = 'NONE'
            location = $sourcePath
        }
    })
}

$requiredIds = [System.Collections.Generic.List[string]]::new()
foreach ($asset in $brandAssets) { $requiredIds.Add([string]$asset.id) }
foreach ($media in $publicMedia) { $requiredIds.Add([string]$media.id) }
foreach ($skill in $nonImportedSkills) { $requiredIds.Add([string]$skill.id) }

$inventory = [ordered]@{
    schema_version = 'w00.resource-license-inventory/v1'
    scope = 'incremental_inventory'
    status = 'SOURCE_VERIFIED'
    runtime_status = 'NOT_RUN'
    legal_clearance = 'NOT_ASSERTED'
    source = [ordered]@{
        repository = 'https://github.com/bytedance/deer-flow.git'
        source_sha = $ExpectedSourceSha
        root_license = 'MIT'
    }
    status_policy = [ordered]@{
        review_status = [ordered]@{
            SOURCE_VERIFIED = 'The named provenance and any bundled license text were checked, but reuse clearance is still not asserted.'
            REVIEW_NEEDED = 'A human asset/license review is required before reuse; origin or bundled text alone is not a grant.'
            UNKNOWN = 'Neither the provenance nor the license can be confirmed from repository evidence.'
        }
        import_status = [ordered]@{
            BUNDLED = 'The asset file is present under the imported frontend/ tree.'
            REFERENCED = 'The dependency is declared and locked but its installed package contents are not vendored here.'
            NOT_IMPORTED = 'The upstream resource is identified but was never imported into this repository.'
        }
        legal_clearance = 'NOT_ASSERTED: this file is a factual inventory; the root MIT license never settles brand, favicon, package or unreviewed Skill obligations.'
    }
    provenance_sources = [ordered]@{
        plugin_origin_readme = $pluginOriginPath
        package_manifest = 'frontend/package.json'
        lockfile = 'frontend/pnpm-lock.yaml'
    }
    brand_assets = @($brandAssets)
    public_media = @($publicMedia)
    fonts = [ordered]@{
        scan_root = 'frontend'
        vendored_font_count = 0
        import_status = 'NONE_VENDORED'
        license_expression = 'UNKNOWN'
        license_basis = 'NONE'
        license_basis_verified = $true
        review_status = 'UNKNOWN'
        review_note = 'No vendored .woff/.woff2/.ttf/.otf file was found under the imported frontend/ tree at this source SHA; runtime or dependency font terms are still not established and remain unverified.'
        evidence = [ordered]@{
            kind = 'NONE'
            location = 'frontend'
        }
    }
    frontend_dependencies = [ordered]@{
        derived_from = 'frontend/package.json'
        lockfile = 'frontend/pnpm-lock.yaml'
        notice = [ordered]@{
            status = 'NOT_GENERATED'
            reason = 'No installed package license texts or consolidated NOTICE are available offline; a release distributor must generate and review a dependency NOTICE before shipping.'
        }
        packages = @($dependencyEntries)
    }
    non_imported_skills = @($nonImportedSkills)
    required_ids = @($requiredIds)
    known_not_covered = @(
        'This inventory does not import, install or download any dependency, license text, image or Skill; it is static provenance only.'
        'Root MIT does not settle dependency, font, image, brand or favicon licensing; no legal clearance is asserted.'
        'All 23 frozen public Skill packages are recorded as NOT_IMPORTED; only the two with a nested LICENSE.txt carry license text evidence, and no full package assets, scripts or prompts were reviewed or imported.'
        'Every bundled frontend/public media file (service thumbnails, logo artwork, demo thread outputs and the demo video) is recorded with its SHA-256, but only the plugin logos carry local license evidence; the rest stay UNKNOWN.'
    )
}

$json = ConvertTo-Json -InputObject $inventory -Depth 100
[System.IO.File]::WriteAllText($OutputPath, ($json -replace "`r`n", "`n").TrimEnd() + "`n",
    [System.Text.UTF8Encoding]::new($false))
Write-Host "Wrote resource license inventory: $OutputPath"
Write-Host "Brand assets=$($brandAssets.Count); public media=$($publicMedia.Count); dependencies=$($dependencyEntries.Count); non-imported skills=$($nonImportedSkills.Count); fonts=$($inventory.fonts.vendored_font_count)"
