[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaoyuSource,
    [Parameter(Mandatory)][string]$BaoyuManifest,
    [Parameter(Mandatory)][string]$BunPath,
    [Parameter(Mandatory)][string]$FrozenSource,
    [string]$Model = 'deepseek-v4-pro',
    [ValidateSet(5, 6)][int]$Slot = 5,
    [switch]$DevelopmentFrontend
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$ports = (Get-Content -Raw (Join-Path $root '.shpm/slots.json') | ConvertFrom-Json -AsHashtable)[[string]$Slot]
$gateway = "http://127.0.0.1:$($ports.gateway)"
$data = Join-Path $root ("local-tmp/poster-assistant/data/" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$bun = (Resolve-Path -LiteralPath $BunPath).Path
$codex = (Get-Command codex.exe -ErrorAction Stop).Source
foreach ($port in @($ports.gateway, $ports.frontend)) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$skillDir = Join-Path $data 'skills'
$manifest = Join-Path $data 'skill-seed.json'
$seed = Join-Path $root 'tests/ui/fixtures/w04/baoyu_imagegen_seed.py'
$seedArgs = @('--source', $BaoyuSource, '--source-manifest', $BaoyuManifest, '--package',
    (Join-Path $skillDir 'baoyu-image-gen'), '--extend', (Join-Path $data '.baoyu-skills'), '--manifest', $manifest)
& python -B $seed @seedArgs
if ($LASTEXITCODE -ne 0) { throw 'Frozen baoyu package setup failed.' }
& python -B $seed @seedArgs --verify
if ($LASTEXITCODE -ne 0) { throw 'Frozen baoyu package verification failed.' }

$env:PATH = "$(Split-Path $bun);$(Split-Path $codex);$env:PATH"
$env:HAIFA_SUPER_HARNESS_BUN_PATH = $bun
$env:HAIFA_SUPER_HARNESS_SKILLS_DIR = $skillDir
$env:HAIFA_SUPER_HARNESS_HOST_SHELL = 'on'
$env:HAIFA_SUPER_HARNESS_SKILL_PROVIDER = 'codex-cli'
$env:HAIFA_SUPER_HARNESS_APPROVAL_MODE = 'auto'
$env:HAIFA_SUPER_HARNESS_SUGGESTIONS_ENABLED = '0'
$env:BAOYU_CODEX_IMAGEGEN_CACHE_DIR = Join-Path $data 'baoyu-cache'
$env:BAOYU_CODEX_IMAGEGEN_LOG_FILE = Join-Path $data 'baoyu-provider.jsonl'
$env:JAVA_TOOL_OPTIONS = '-Xmx512m -XX:+UseSerialGC -Djava.awt.headless=true'
$env:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF = 'env://POSTER_LOCAL_CONTINUATION_KEY'
$key = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($key)
$env:POSTER_LOCAL_CONTINUATION_KEY = [Convert]::ToBase64String($key)

$start = Join-Path $root 'scripts/start-main-super-harness.ps1'
$service = & $start -RepositoryRoot $root -SourceRepository $FrozenSource `
    -AllowSlot -GatewayPort $ports.gateway -FrontendPort $ports.frontend -DataDirectory $data `
    -ProductionFrontend:(-not $DevelopmentFrontend)
if ($LASTEXITCODE -ne 0) { throw 'Local poster instance failed readiness.' }
$service | Set-Content -Encoding utf8 -LiteralPath (Join-Path $data 'poster-service.json')
$service
& python -B (Join-Path $PSScriptRoot 'configure_local.py') --base $gateway --model $Model
if ($LASTEXITCODE -ne 0) { throw 'Local poster Agent registration failed.' }
