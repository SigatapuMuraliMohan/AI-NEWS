param([switch]$NoBrowser, [switch]$Docker)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Read-LocalEnv([string]$Path) {
    $settings = @{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
                $settings[$Matches[1]] = $Matches[2].Trim('"', "'")
            }
        }
    }
    return $settings
}

function DockerReady {
    $ErrorActionPreference = 'Continue'
    docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

try {
    if (!(Test-Path -LiteralPath '.env')) {
        Copy-Item -LiteralPath '.env.example' -Destination '.env'
    }
    if (!(Test-Path -LiteralPath '.runtime.env')) {
        $runtime = foreach ($key in 'DB_PASSWORD','DB_ROOT_PASSWORD','INTERNAL_TOKEN') {
            $bytes = New-Object byte[] 24
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            "$key=$([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())"
        }
        [IO.File]::WriteAllLines((Join-Path $PSScriptRoot '.runtime.env'), [string[]]$runtime)
    }

    $local = Read-LocalEnv '.env'
    $old = Read-LocalEnv 'D:\ai-personalized-education\ai-service\.env'
    foreach ($key in 'NEWS_PROVIDER','GNEWS_API_KEY','INCEPTION_API_KEY','INCEPTION_BASE_URL','INCEPTION_MODEL') {
        $value = $local[$key]
        if (!$value) { $value = [Environment]::GetEnvironmentVariable($key) }
        if (!$value -and $key.StartsWith('INCEPTION_')) { $value = $old[$key] }
        if ($value) { [Environment]::SetEnvironmentVariable($key, $value) }
    }
    if (!$env:INCEPTION_API_KEY) { Write-Host 'Set INCEPTION_API_KEY in .env to enable analysis.' }
    if (!$env:GNEWS_API_KEY) { Write-Host 'Set GNEWS_API_KEY in .env to enable news evidence search.' }

    if (!$Docker) {
        & (Join-Path $PSScriptRoot 'run_native.ps1') -NoBrowser:$NoBrowser
        return
    }
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Install Docker Desktop with Docker Compose to start the container services.'
    }

    if (!(DockerReady)) {
        $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
        if (!(Test-Path -LiteralPath $desktop)) { throw 'The Docker engine is offline. Start Docker Desktop.' }
        Write-Host 'Starting Docker Desktop...'
        if (!(Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
            Start-Process -FilePath $desktop -WindowStyle Hidden
        }
        $deadline = (Get-Date).AddMinutes(2)
        do {
            Start-Sleep -Seconds 3
            $ready = DockerReady
        } until ($ready -or (Get-Date) -ge $deadline)
        if (!$ready) { throw 'Docker did not become ready. Check Docker Desktop and its Linux engine, then retry.' }
    }

    Write-Host 'Building and starting MySQL, FastAPI, Spring Boot, and React in dependency order...'
    docker compose --env-file .runtime.env up -d --build --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) {
        throw 'A service failed to build or start. Run docker compose --env-file .runtime.env logs --tail=100 for details.'
    }
    Write-Host 'News Meter is ready at http://localhost:5174'
    if (!$NoBrowser) { Start-Process 'http://localhost:5174' }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
