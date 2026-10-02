#Requires -Version 7
<#
.SYNOPSIS
    Resolves which Haifa SDK version this worktree consumes.

.DESCRIPTION
    The tracked root pom.xml always pins the baseline SDK version. A work-package
    worktree that must consume the SDK published by one upstream worktree gets an
    untracked local-tmp/sdk-selection.json written by the controller:

        { "haifaVersion": "0.1.2-<upstream worktree directory>-SNAPSHOT" }

    Build entry points dot-source this file, call Resolve-HaifaSdkSelection and
    append the returned MavenArgument (if any) to their Maven command line.

    Only the version is checked. SDK commits and artifact hashes are recorded by
    the upstream publish manifest and are not verified here.
#>
Set-StrictMode -Version Latest

$script:HaifaBaselineVersion = '0.1.2-SNAPSHOT'
$script:HaifaSelectedVersionPattern = '^0\.1\.2-[A-Za-z0-9.-]+-SNAPSHOT$'

function Get-HaifaBaselineVersion {
    return $script:HaifaBaselineVersion
}

function Get-MavenLocalRepository {
    # Mirrors Maven's own lookup: user settings.xml <localRepository>, else ~/.m2/repository.
    $settings = Join-Path $HOME '.m2/settings.xml'
    if (Test-Path -LiteralPath $settings -PathType Leaf) {
        $configured = ([xml](Get-Content -Raw -LiteralPath $settings)).settings['localRepository']
        if ($null -ne $configured -and -not [string]::IsNullOrWhiteSpace($configured.InnerText)) {
            return $configured.InnerText.Trim()
        }
    }
    return (Join-Path $HOME '.m2/repository')
}

function Resolve-HaifaSdkSelection {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot,
        [string]$LocalRepository
    )

    $selectionPath = Join-Path $RepositoryRoot 'local-tmp/sdk-selection.json'
    if (-not (Test-Path -LiteralPath $selectionPath -PathType Leaf)) {
        return [pscustomobject]@{
            Status = 'ABSENT'
            HaifaVersion = $script:HaifaBaselineVersion
            MavenArgument = $null
            Message = "sdkSelection=ABSENT haifaVersion=$script:HaifaBaselineVersion"
        }
    }

    $selection = Get-Content -Raw -LiteralPath $selectionPath | ConvertFrom-Json
    $haifaVersion = @($selection.PSObject.Properties | Where-Object { $_.Name -eq 'haifaVersion' } | ForEach-Object { [string]$_.Value })
    if ($haifaVersion.Count -ne 1 -or [string]::IsNullOrWhiteSpace($haifaVersion[0])) {
        throw "SDK selection '$selectionPath' must contain a non-empty 'haifaVersion'."
    }
    $version = $haifaVersion[0].Trim()
    if ($version -ne $script:HaifaBaselineVersion -and $version -notmatch $script:HaifaSelectedVersionPattern) {
        throw "SDK selection '$selectionPath' has unsupported haifaVersion '$version'; expected $script:HaifaBaselineVersion or 0.1.2-<worktree>-SNAPSHOT."
    }

    if ([string]::IsNullOrWhiteSpace($LocalRepository)) {
        $LocalRepository = Get-MavenLocalRepository
    }
    $bomPom = Join-Path $LocalRepository "io/haifa/haifa-agent-bom/$version/haifa-agent-bom-$version.pom"
    if (-not (Test-Path -LiteralPath $bomPom -PathType Leaf)) {
        throw "SDK selection '$selectionPath' names haifaVersion '$version', but its BOM is not published at '$bomPom'."
    }

    return [pscustomobject]@{
        Status = 'SELECTED'
        HaifaVersion = $version
        MavenArgument = "-Dhaifa.version=$version"
        Message = "sdkSelection=SELECTED haifaVersion=$version"
    }
}
