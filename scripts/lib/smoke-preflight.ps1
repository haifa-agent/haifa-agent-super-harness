#Requires -Version 7
Set-StrictMode -Version Latest

function Resolve-SmokeCaseSelection {
    param([string]$SpecPath, [string]$CaseTitle)
    $titles = @([regex]::Matches((Get-Content -Raw -LiteralPath $SpecPath), 'test(?:\.skip|\.only)?\(\s*"([^"]+)"') |
        ForEach-Object { $_.Groups[1].Value })
    if ($titles.Count -eq 0) { throw 'No static case titles found in the selected spec.' }
    $selected = $titles
    $grep = $null
    if ($CaseTitle) {
        $grep = [regex]::Escape($CaseTitle) + '$'
        $selected = @($titles | Where-Object { $_ -cmatch $grep })
        if ($selected.Count -ne 1 -or $selected[0] -cne $CaseTitle) {
            throw 'CaseTitle must match exactly one complete, unambiguous title in the selected spec.'
        }
    }
    return [pscustomobject]@{ All = $titles; Selected = $selected; Grep = $grep }
}

function Assert-SmokeEnvironment {
    param([System.Collections.IDictionary]$Environment, [string]$DataRoot,
        [string]$GatewayBaseUrl, [bool]$Controlled, [int]$ContextWindow)
    foreach ($entry in @(
        @('DEER_FLOW_AUTH_DISABLED', '1'),
        @('SUPER_HARNESS_DATA_DIR', $DataRoot),
        @('DEER_FLOW_INTERNAL_GATEWAY_BASE_URL', $GatewayBaseUrl))) {
        if (-not $Environment.Contains($entry[0]) -or [string]$Environment[$entry[0]] -cne $entry[1]) {
            throw "Smoke environment is missing or inconsistent: $($entry[0])."
        }
    }
    foreach ($name in @('NO_PROXY', 'no_proxy')) {
        if (([string][Environment]::GetEnvironmentVariable($name, 'Process')).Contains('*')) {
            throw "$name contains an unsupported wildcard; set loopback-only values in this launch process before retrying."
        }
    }
    if ($Controlled -and $ContextWindow -lt 32768) {
        throw 'Controlled smoke context_window must be at least 32768 for the frozen Lead prompt and production Tool catalog.'
    }
}

function Assert-SmokeDependencies {
    param([string]$RepositoryRoot)
    foreach ($relativePath in @('frontend/node_modules/next/dist/bin/next',
        'frontend/node_modules/@playwright/test/cli.js')) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepositoryRoot $relativePath) -PathType Leaf)) {
            throw "Smoke dependency is missing: $relativePath. Prepare this worktree offline before retrying."
        }
    }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $testPackage = Join-Path $RepositoryRoot 'frontend/node_modules/@playwright/test'
    $browserPath = & $node -e 'console.log(require(process.argv[1]).chromium.executablePath())' $testPackage
    if ($LASTEXITCODE -ne 0 -or @($browserPath).Count -ne 1 -or
        -not (Test-Path -LiteralPath ([string]$browserPath) -PathType Leaf)) {
        throw 'Playwright Chromium is unavailable; prepare the browser before retrying.'
    }
    return [pscustomobject]@{ next = 'PRESENT'; playwright = 'PRESENT'; chromium = 'PRESENT' }
}

function Assert-SmokeCaseResults {
    param([object[]]$Rows, [string[]]$SelectedCases, [string[]]$AllowedNotRun = @())
    foreach ($name in $SelectedCases) {
        $matches = @($Rows | Where-Object { $_.name -ceq $name })
        if ($matches.Count -ne 1 -or ($matches[0].result -ne 'PASS' -and
            -not ($matches[0].result -eq 'NOT_RUN' -and $name -cin $AllowedNotRun))) {
            throw 'Playwright results do not cover exactly the selected cases; inspect results.json.'
        }
    }
    if (@($Rows | Where-Object { $_.result -ne 'NOT_RUN' -and $_.name -cnotin $SelectedCases }).Count) {
        throw 'Playwright ran an unselected case; inspect results.json.'
    }
}
