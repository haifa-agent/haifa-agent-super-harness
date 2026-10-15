[CmdletBinding()]
param(
    [string]$SourceRepository
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = (& mvn -q help:evaluate '-Dexpression=settings.localRepository' -DforceStdout).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve the configured Maven local repository.'
    }
}

$sourcePath = [System.IO.Path]::GetFullPath($SourceRepository)
$haifaBom = Join-Path $sourcePath 'io\haifa\haifa-agent-bom\0.1.3-SNAPSHOT\haifa-agent-bom-0.1.3-SNAPSHOT.pom'
if (-not (Test-Path -LiteralPath $haifaBom -PathType Leaf)) {
    throw "Published Haifa BOM not found in Maven repository: $haifaBom"
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempBase ("haifa-super-harness-w01-" + [guid]::NewGuid().ToString('N'))
$consumerRepository = Join-Path $tempRoot 'repository'
$publishedRepository = Join-Path $tempRoot 'published-repository'
$settingsPath = Join-Path $tempRoot 'settings.xml'
$resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
if (-not $resolvedTempRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create temporary repository outside the system temp directory: $resolvedTempRoot"
}

[System.IO.Directory]::CreateDirectory($consumerRepository) | Out-Null
[System.IO.Directory]::CreateDirectory((Join-Path $publishedRepository 'io')) | Out-Null
Copy-Item -LiteralPath (Join-Path $sourcePath 'io\haifa') -Destination (Join-Path $publishedRepository 'io') -Recurse
Get-ChildItem $publishedRepository -Filter 'maven-metadata-local.xml' -Recurse -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $_.DirectoryName 'maven-metadata.xml')
}
Get-ChildItem $publishedRepository -Recurse -File | Where-Object {
    $_.Name -eq '_remote.repositories' -or $_.Name -eq 'maven-metadata-local.xml'
} | Remove-Item -Force
Get-ChildItem $publishedRepository -Recurse -File | Where-Object {
    $_.Extension -in @('.jar', '.pom', '.xml')
} | ForEach-Object {
    $sha1 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA1).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText($_.FullName + '.sha1', $sha1, [System.Text.Encoding]::ASCII)
}

$sourceUri = ([System.Uri]::new($sourcePath + [System.IO.Path]::DirectorySeparatorChar)).AbsoluteUri
$publishedUri = ([System.Uri]::new($publishedRepository + [System.IO.Path]::DirectorySeparatorChar)).AbsoluteUri
$settings = @"
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <localRepository>$consumerRepository</localRepository>
  <mirrors>
    <mirror>
      <id>cached-third-party-repository</id>
      <name>Read-only third-party artifact source</name>
      <url>$sourceUri</url>
      <mirrorOf>*,!haifa-published</mirrorOf>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>published-haifa-artifacts</id>
      <repositories>
        <repository>
          <id>haifa-published</id>
          <name>Temporary remote-shaped view of published Haifa artifacts</name>
          <url>$publishedUri</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled><updatePolicy>never</updatePolicy><checksumPolicy>warn</checksumPolicy></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>published-haifa-artifacts</activeProfile>
  </activeProfiles>
</settings>
"@
[System.IO.File]::WriteAllText($settingsPath, $settings, [System.Text.UTF8Encoding]::new($false))

Write-Host "Starting with empty consumer cache: $consumerRepository"
Write-Host "Resolving Haifa artifacts from temporary published view: $publishedUri"
Write-Host "Resolving cached third-party artifacts from: $sourceUri"

Push-Location $repoRoot
try {
    & mvn -s $settingsPath -ntp verify
    if ($LASTEXITCODE -ne 0) {
        throw "Clean-consumer Maven verification failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
    $cleanupTarget = [System.IO.Path]::GetFullPath($tempRoot)
    if ($cleanupTarget.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $cleanupTarget)) {
        Remove-Item -LiteralPath $cleanupTarget -Recurse -Force
    }
}
