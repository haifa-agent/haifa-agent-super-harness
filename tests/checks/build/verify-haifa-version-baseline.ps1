#Requires -Version 7
<#
.SYNOPSIS
  Pre-commit gate: tracked POMs may only pin the baseline Haifa SDK version.

.DESCRIPTION
  A work-package worktree consumes a worktree-labelled SDK version through the untracked
  local-tmp/sdk-selection.json and -Dhaifa.version, never by editing a POM. This gate
  fails when the root pom.xml haifa.version is not the baseline, or when any reactor
  module pins an io.haifa SDK dependency version itself.

.EXAMPLE
  pwsh -NoProfile -File tests/checks/build/verify-haifa-version-baseline.ps1
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))) 'scripts/lib/sdk-selection.ps1')
$baseline = Get-HaifaBaselineVersion
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$failures = @()

$rootPomPath = Join-Path $root 'pom.xml'
$rootPom = [xml](Get-Content -Raw -LiteralPath $rootPomPath)
$declared = @($rootPom.project.properties.ChildNodes | Where-Object { $_.Name -eq 'haifa.version' } | ForEach-Object { $_.InnerText.Trim() })
if ($declared.Count -ne 1 -or $declared[0] -cne $baseline) {
    $failures += "pom.xml haifa.version is '$($declared -join ',')', expected exactly '$baseline'."
}

$pomPaths = @($rootPomPath) + @($rootPom.project.modules.ChildNodes |
    Where-Object { $_.Name -eq 'module' } |
    ForEach-Object { Join-Path $root "$($_.InnerText.Trim())/pom.xml" })
foreach ($pomPath in $pomPaths) {
    $pom = [xml](Get-Content -Raw -LiteralPath $pomPath)
    $relative = [IO.Path]::GetRelativePath($root, $pomPath)
    foreach ($node in $pom.SelectNodes("//*[local-name()='groupId' and normalize-space(text())='io.haifa']")) {
        $versionNode = $node.ParentNode.SelectSingleNode("*[local-name()='version']")
        if ($null -ne $versionNode -and $versionNode.InnerText.Trim() -cne '${haifa.version}') {
            $artifact = $node.ParentNode.SelectSingleNode("*[local-name()='artifactId']").InnerText.Trim()
            $failures += "$relative pins io.haifa:$artifact to '$($versionNode.InnerText.Trim())'; only `${haifa.version} managed by the root pom.xml is allowed."
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}
Write-Output "PASS: tracked POMs pin Haifa SDK baseline $baseline ($($pomPaths.Count) POMs checked)."
