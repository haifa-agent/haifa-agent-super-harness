#Requires -Version 7
<#
.SYNOPSIS
    Build stamps that decide whether -SkipBuild may reuse an existing build output.

.DESCRIPTION
    A runner writes a stamp right after a successful build and asserts it before
    reusing that output. A stamp records only what makes the output reusable:

      frontend (frontend/.next):   HEAD:frontend tree, pnpm-lock.yaml hash and the
                                   DEER_FLOW_INTERNAL_GATEWAY_BASE_URL and the non-static
                                   NEXT_PUBLIC_STATIC_WEBSITE_ONLY=false build profile; the
                                   repository HEAD is deliberately not part of it, because the
                                   build output does not depend on commits outside frontend/
      server (exec JAR directory): the Java input fingerprint (see source-fingerprint.ps1):
                                   the exclusion-filtered source tree, the published SDK JAR
                                   identity, and the JDK and Maven versions. sourceSha and the
                                   SDK version are recorded too but do not decide reuse.

    Output built from uncommitted sources gets no stamp and therefore cannot be reused.
    A failed assertion never falls back to rebuilding; the caller reruns without -SkipBuild.
#>
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'source-fingerprint.ps1')

$script:ServerSourcePaths = @('pom.xml', 'super-harness-app', 'super-harness-events', 'super-harness-harness', 'super-harness-server', 'super-harness-workspace')

function Get-FrontendBuildStampPath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    return (Join-Path $RepositoryRoot 'frontend/.next/haifa-build-stamp.json')
}

function Get-ServerBuildStampPath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    return (Join-Path $RepositoryRoot 'super-harness-server/target/haifa-build-stamp.json')
}

function Remove-BuildStamp {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        Remove-Item -LiteralPath $Path -Force
    }
}

function Invoke-StampGit {
    param([string]$RepositoryRoot, [string[]]$Arguments)
    $output = & git -C $RepositoryRoot @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
    return (@($output) -join "`n").Trim()
}

function Test-StampSourcesDirty {
    param([string]$RepositoryRoot, [string[]]$Paths)
    return -not [string]::IsNullOrWhiteSpace((Invoke-StampGit $RepositoryRoot (@('status', '--porcelain', '--') + $Paths)))
}

function Get-FrontendStampFields {
    param([string]$RepositoryRoot, [string]$InternalGatewayBaseUrl)
    return [ordered]@{
        frontendTree = Invoke-StampGit $RepositoryRoot @('rev-parse', 'HEAD:frontend')
        lockfileSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $RepositoryRoot 'frontend/pnpm-lock.yaml')).Hash.ToLowerInvariant()
        internalGatewayBaseUrl = $InternalGatewayBaseUrl
        staticWebsiteOnly = 'false'
    }
}

function Get-ServerStampFields {
    param([string]$RepositoryRoot, [string]$HaifaVersion, [string]$MavenRepo)
    $fingerprint = Get-SourceFingerprint -RepositoryRoot $RepositoryRoot -MavenRepo $MavenRepo -Version $HaifaVersion
    return [ordered]@{
        sourceSha = Invoke-StampGit $RepositoryRoot @('rev-parse', 'HEAD')
        haifaVersion = $HaifaVersion
        sourceTree = $fingerprint.sourceTree
        sdkIdentity = $fingerprint.sdkIdentity
        javaInputFingerprint = $fingerprint.javaInputFingerprint
    }
}

function Write-BuildStamp {
    param([string]$Path, [System.Collections.IDictionary]$Fields)
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)
    $Fields | ConvertTo-Json | Set-Content -LiteralPath $Path -Encoding utf8
}

function Assert-BuildStamp {
    param([string]$Kind, [string]$Path, [System.Collections.IDictionary]$Expected)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Kind build stamp is missing at '$Path'; rerun without -SkipBuild."
    }
    $stamp = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -AsHashtable
    foreach ($field in $Expected.Keys) {
        $actual = if ($stamp.ContainsKey($field)) { [string]$stamp[$field] } else { '<absent>' }
        if ($actual -cne [string]$Expected[$field]) {
            throw "$Kind build stamp field '$field' does not match: built with '$actual', current is '$($Expected[$field])'; rerun without -SkipBuild."
        }
    }
}

function Write-FrontendBuildStamp {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$InternalGatewayBaseUrl
    )
    $path = Get-FrontendBuildStampPath $RepositoryRoot
    if ([Environment]::GetEnvironmentVariable('NEXT_PUBLIC_STATIC_WEBSITE_ONLY', 'Process') -cne 'false') {
        Remove-BuildStamp $path
        throw 'Frontend production build must set NEXT_PUBLIC_STATIC_WEBSITE_ONLY=false before building and stamping.'
    }
    if (Test-StampSourcesDirty $RepositoryRoot @('frontend')) {
        Remove-BuildStamp $path
        return
    }
    Write-BuildStamp $path (Get-FrontendStampFields $RepositoryRoot $InternalGatewayBaseUrl)
}

function Assert-FrontendBuildStamp {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$InternalGatewayBaseUrl
    )
    if (Test-StampSourcesDirty $RepositoryRoot @('frontend')) {
        throw 'Frontend sources have uncommitted changes; rerun without -SkipBuild.'
    }
    Assert-BuildStamp 'Frontend' (Get-FrontendBuildStampPath $RepositoryRoot) (Get-FrontendStampFields $RepositoryRoot $InternalGatewayBaseUrl)
}

function Write-ServerBuildStamp {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$HaifaVersion,
        [string]$MavenRepo
    )
    $path = Get-ServerBuildStampPath $RepositoryRoot
    if (Test-StampSourcesDirty $RepositoryRoot $script:ServerSourcePaths) {
        Remove-BuildStamp $path
        return
    }
    Write-BuildStamp $path (Get-ServerStampFields $RepositoryRoot $HaifaVersion $MavenRepo)
}

function Assert-ServerBuildStamp {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$HaifaVersion,
        [string]$MavenRepo
    )
    if (Test-StampSourcesDirty $RepositoryRoot $script:ServerSourcePaths) {
        throw 'Server sources have uncommitted changes; rerun without -SkipBuild.'
    }
    # Comparison is by Java input fingerprint, not by commit: a docs/UI/test/script-only commit
    # keeps the same JAR reusable, while a source or SDK content change does not. An old stamp
    # without the field is a mismatch, never a fallback to sourceSha.
    $expected = Get-ServerStampFields $RepositoryRoot $HaifaVersion $MavenRepo
    # Without the published SDK JARs the fingerprint cannot tell two SDK builds apart, so the
    # JAR is not reusable. Only a fixture that passes its own -MavenRepo may compare without.
    if ($expected.sdkIdentity -ceq 'UNAVAILABLE' -and [string]::IsNullOrWhiteSpace($MavenRepo)) {
        throw 'Server build stamp cannot be checked: the SDK identity is unavailable; rerun without -SkipBuild.'
    }
    Assert-BuildStamp 'Server' (Get-ServerBuildStampPath $RepositoryRoot) ([ordered]@{
        javaInputFingerprint = $expected.javaInputFingerprint
    })
}
