[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SourceRepository,
    [switch]$ReportOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-NormalizedPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return $Path.Substring($Root.TrimEnd('\', '/').Length + 1).Replace('\', '/')
}

function Test-ExcludedLocalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    $path = $RelativePath.Replace('\', '/')
    return (
        $path -match '^(node_modules|\.next|out|coverage|\.vercel)/' -or
        $path -match '(^|/)\.env$' -or
        ($path -match '(^|/)\.env\.' -and $path -notmatch '(^|/)\.env\.example$') -or
        $path -match '(^|/)next-env\.d\.ts$' -or
        $path -match '\.tsbuildinfo$' -or
        $path -match '\.pem$'
    )
}

function Get-FrontendInventory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FrontendRoot,
        [string[]]$RelativePaths
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $FrontendRoot).Path.TrimEnd('\', '/')
    if ($null -eq $RelativePaths -or $RelativePaths.Count -eq 0) {
        $files = @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -Force -File |
            ForEach-Object {
                $relativePath = Get-NormalizedPath -Root $resolvedRoot -Path $_.FullName
                [pscustomobject]@{
                    Path = $relativePath
                    FullName = $_.FullName
                    Length = [int64]$_.Length
                }
            } |
            Where-Object { -not (Test-ExcludedLocalPath -RelativePath $_.Path) } |
            Sort-Object -Property Path)
    }
    else {
        $files = @($RelativePaths | ForEach-Object {
            $relativePath = $_.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
            $file = Get-Item -LiteralPath (Join-Path $resolvedRoot $relativePath)
            [pscustomobject]@{
                Path = $_.Replace('\', '/')
                FullName = $file.FullName
                Length = [int64]$file.Length
            }
        } |
        Sort-Object -Property Path)
    }

    $inventoryLines = foreach ($file in $files) {
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "{0}`t{1}`n" -f $file.Path, $hash
    }

    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $payload = $utf8.GetBytes([string]::Concat($inventoryLines))
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = ([System.BitConverter]::ToString($sha256.ComputeHash($payload))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }

    return [pscustomobject]@{
        FileCount = $files.Count
        TotalBytes = [int64](($files | Measure-Object -Property Length -Sum).Sum)
        ContentInventorySha256 = $digest
        Files = $files
    }
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = & git -C $WorkingDirectory @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed in ${WorkingDirectory}: $($output -join [Environment]::NewLine)"
    }
    return @($output)
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$manifestPath = Join-Path $root "third-party/deerflow/manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$frontendRoot = Join-Path $root $manifest.import.localPath

$licensePath = Join-Path $root $manifest.upstream.licenseFile
$licenseHash = (Get-FileHash -LiteralPath $licensePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($licenseHash -ne [string]$manifest.upstream.licenseSha256) {
    throw "DeerFlow license digest mismatch: expected $($manifest.upstream.licenseSha256), got $licenseHash"
}

$lockfilePath = Join-Path $root $manifest.build.lockfile
$lockfileHash = (Get-FileHash -LiteralPath $lockfilePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($lockfileHash -ne [string]$manifest.build.lockfileSha256) {
    throw "Frontend lockfile digest mismatch: expected $($manifest.build.lockfileSha256), got $lockfileHash"
}

foreach ($embeddedLicense in $manifest.upstream.embeddedLicenseFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $embeddedLicense) -PathType Leaf)) {
        throw "Embedded frontend license record is missing: $embeddedLicense"
    }
}

$requiredFiles = @(
    "AGENTS.md",
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "next.config.js",
    ".env.example"
)
foreach ($required in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot $required) -PathType Leaf)) {
        throw "Required imported frontend file is missing: $required"
    }
}

$trackedFrontendFiles = @(Invoke-Git -WorkingDirectory $root -Arguments @(
    "ls-files",
    "--",
    $manifest.import.localPath
))
$trackedForbidden = @($trackedFrontendFiles | Where-Object {
    $relativePath = $_.Substring(([string]$manifest.import.localPath).Length).TrimStart('/', '\')
    Test-ExcludedLocalPath -RelativePath $relativePath
})
if ($trackedForbidden.Count -gt 0) {
    throw "Generated, local, or sensitive files must not be tracked: $($trackedForbidden -join ', ')"
}

$inventory = Get-FrontendInventory -FrontendRoot $frontendRoot
$report = [ordered]@{
    fileCount = $inventory.FileCount
    totalBytes = $inventory.TotalBytes
    contentInventorySha256 = $inventory.ContentInventorySha256
}

if ($ReportOnly) {
    [pscustomobject]$report | ConvertTo-Json
    return
}

if ($inventory.FileCount -ne [int]$manifest.import.fileCount) {
    throw "Frontend file count mismatch: expected $($manifest.import.fileCount), got $($inventory.FileCount)"
}
if ($inventory.TotalBytes -ne [int64]$manifest.import.totalBytes) {
    throw "Frontend byte count mismatch: expected $($manifest.import.totalBytes), got $($inventory.TotalBytes)"
}
if ($inventory.ContentInventorySha256 -ne [string]$manifest.import.contentInventorySha256) {
    throw "Frontend inventory digest mismatch: expected $($manifest.import.contentInventorySha256), got $($inventory.ContentInventorySha256)"
}
if ([bool]$manifest.configuration.deerFlowBackendFallbackAllowed) {
    throw "The Haifa frontend configuration must not allow DeerFlow backend fallback"
}

$recordedPatches = @($manifest.import.patches)
if ([bool]$manifest.import.modified) {
    if ([string]$manifest.import.patchStatus -ne "documented-patches" -or
        $recordedPatches.Count -eq 0) {
        throw "A modified frontend import requires documented-patches and at least one patch record"
    }
}
elseif ([string]$manifest.import.patchStatus -ne "zero-patch" -or $recordedPatches.Count -ne 0) {
    throw "An unmodified frontend import must retain zero-patch status and no patch records"
}

foreach ($patch in $recordedPatches) {
    $patchPath = Join-Path $root ([string]$patch.file)
    if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
        throw "Recorded frontend patch is missing: $($patch.file)"
    }
    $patchHash = (Get-FileHash -LiteralPath $patchPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($patchHash -ne [string]$patch.sha256) {
        throw "Frontend patch digest mismatch for $($patch.file): expected $($patch.sha256), got $patchHash"
    }
    if (@($patch.affectedFiles).Count -eq 0) {
        throw "Frontend patch '$($patch.id)' has no affectedFiles provenance"
    }
}

if ($SourceRepository) {
    $sourceRoot = (Resolve-Path -LiteralPath $SourceRepository).Path
    $sourceHeadOutput = @(Invoke-Git -WorkingDirectory $sourceRoot -Arguments @("rev-parse", "HEAD"))
    $sourceHead = $sourceHeadOutput[0].Trim()
    if ($sourceHead -ne [string]$manifest.upstream.commit) {
        throw "Source repository HEAD mismatch: expected $($manifest.upstream.commit), got $sourceHead"
    }

    $sourceTreeOutput = @(Invoke-Git -WorkingDirectory $sourceRoot -Arguments @(
        "rev-parse",
        "$($manifest.upstream.commit):$($manifest.upstream.sourcePath)"
    ))
    $sourceTree = $sourceTreeOutput[0].Trim()
    if ($sourceTree -ne [string]$manifest.upstream.sourceTree) {
        throw "Source frontend tree mismatch: expected $($manifest.upstream.sourceTree), got $sourceTree"
    }

    & git -C $sourceRoot diff --quiet $manifest.upstream.commit -- $manifest.upstream.sourcePath
    if ($LASTEXITCODE -ne 0) {
        throw "Source repository has tracked frontend changes relative to the pinned commit"
    }

    if ($recordedPatches.Count -eq 0) {
        $sourceFrontendRoot = Join-Path $sourceRoot $manifest.upstream.sourcePath
        $sourceFiles = @(Invoke-Git -WorkingDirectory $sourceRoot -Arguments @(
            "ls-tree",
            "-r",
            "--name-only",
            "$($manifest.upstream.commit):$($manifest.upstream.sourcePath)"
        ))
        $sourceInventory = Get-FrontendInventory -FrontendRoot $sourceFrontendRoot -RelativePaths $sourceFiles
        if ($sourceInventory.ContentInventorySha256 -ne $inventory.ContentInventorySha256) {
            throw "Imported frontend content differs from the pinned source checkout"
        }
    }
    else {
        $temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
        $temporaryRoot = Join-Path $temporaryParent ("haifa-w18-verify-" + [Guid]::NewGuid().ToString('N'))
        $archivePath = Join-Path $temporaryRoot 'frontend.tar'
        $materializedRoot = Join-Path $temporaryRoot 'materialized'
        $null = New-Item -ItemType Directory -Force -Path $materializedRoot
        try {
            $null = Invoke-Git -WorkingDirectory $sourceRoot -Arguments @(
                "archive",
                "--format=tar",
                "--output=$archivePath",
                [string]$manifest.upstream.commit,
                [string]$manifest.upstream.sourcePath
            )
            & tar -xf $archivePath -C $materializedRoot
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to extract the pinned frontend archive"
            }
            $null = Invoke-Git -WorkingDirectory $materializedRoot -Arguments @("init", "--quiet")
            foreach ($patch in $recordedPatches) {
                $patchPath = Join-Path $root ([string]$patch.file)
                $null = Invoke-Git -WorkingDirectory $materializedRoot -Arguments @(
                    "apply",
                    "--unidiff-zero",
                    "--whitespace=error-all",
                    $patchPath
                )
            }
            $patchedFrontendRoot = Join-Path $materializedRoot $manifest.upstream.sourcePath
            $patchedInventory = Get-FrontendInventory -FrontendRoot $patchedFrontendRoot
            if ($patchedInventory.FileCount -ne $inventory.FileCount -or
                $patchedInventory.TotalBytes -ne $inventory.TotalBytes -or
                $patchedInventory.ContentInventorySha256 -ne $inventory.ContentInventorySha256) {
                throw "Imported frontend content differs from the pinned source plus recorded patches"
            }
        }
        finally {
            $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
            if ($resolvedTemporaryRoot.StartsWith($temporaryParent + [IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase) -and
                (Test-Path -LiteralPath $resolvedTemporaryRoot)) {
                Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
            }
        }
    }
}

[pscustomobject]$report | ConvertTo-Json
Write-Host "DeerFlow frontend verification passed for $($manifest.upstream.commit)."
