param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

function Find-Tool([string]$Name, [string]$Fallback = '') {
    $tool = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($tool) { return $tool.Source }
    if ($Fallback -and (Test-Path -LiteralPath $Fallback)) { return $Fallback }
    throw "The Windows launcher could not find $Name."
}

function Run-Step([string]$Executable, [string[]]$Arguments, [string]$Directory) {
    Push-Location -LiteralPath $Directory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
}

function Start-ServiceProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory) {
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime "$Name.log") `
        -RedirectStandardError (Join-Path $runtime "$Name.error.log")
    $started.Add($process)
    return $process
}

function Wait-ServiceReady($Process, [string]$Name, [scriptblock]$Ready) {
    $deadline = (Get-Date).AddSeconds(85)
    do {
        if ($Process.HasExited) { throw "$Name stopped. See .native\$Name.error.log." }
        if (& $Ready) { Write-Host "$Name is ready."; return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready. See .native\$Name.error.log."
}

function Http-Ready([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 }
    catch { return $false }
}

$runtime = Join-Path $root '.native'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$started = New-Object 'System.Collections.Generic.List[System.Diagnostics.Process]'
$init = Join-Path $runtime 'mysql-init.sql'
$frontend = Join-Path $root 'frontend'
$backend = Join-Path $root 'backend'
$ai = Join-Path $root 'ai-service'
$venv = Join-Path $ai '.venv'
$python = Join-Path $venv 'Scripts\python.exe'
$mysqlActive = $false
$lock = $null
$buildPath = $null

try {
    $lock = [IO.File]::Open((Join-Path $runtime 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $occupied = foreach ($port in 3317, 8001, 8086, 5174) {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $connection = $client.ConnectAsync('127.0.0.1', $port)
            if ($connection.Wait(500) -and $client.Connected) { $port }
        } catch [AggregateException] {
            # A refused connection means nothing is listening.
        } finally { $client.Dispose() }
    }
    if ($occupied) {
        throw "Local ports still in use: $($occupied -join ', '). Stop the earlier News Meter launcher with Ctrl+C. If its window was closed and its services are still running, restart Windows before launching again."
    }
    $maven = Find-Tool 'mvn.cmd'
    $java = Find-Tool 'java.exe'
    $npm = Find-Tool 'npm.cmd'
    $node = Find-Tool 'node.exe'
    $mysql = Find-Tool 'mysql.exe' (Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.0\bin\mysql.exe')
    $mysqld = Join-Path (Split-Path $mysql) 'mysqld.exe'
    $mysqladmin = Join-Path (Split-Path $mysql) 'mysqladmin.exe'
    if (!(Test-Path -LiteralPath $mysqld)) { throw 'Install MySQL Server 8.0 or run run_platform.bat -Docker.' }

    $settings = Read-LocalEnv (Join-Path $root '.runtime.env')
    foreach ($name in 'DB_PASSWORD','DB_ROOT_PASSWORD','INTERNAL_TOKEN') {
        if ($settings[$name] -notmatch '^[a-f0-9]{48}$') { throw "Invalid $name in .runtime.env." }
    }
    $env:DB_PASSWORD = $settings.DB_PASSWORD
    $env:DB_USER = 'news_meter'
    $env:DB_URL = 'jdbc:mysql://127.0.0.1:3317/news_meter?sslMode=DISABLED&allowPublicKeyRetrieval=true'
    $env:INTERNAL_TOKEN = $settings.INTERNAL_TOKEN
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:AI_URL = 'http://127.0.0.1:8001'

    Write-Host 'Preparing application dependencies...'
    if (!(Test-Path -LiteralPath $python)) {
        $reference = 'D:\ai-personalized-education\ai-service\venv\Scripts\python.exe'
        if (Test-Path -LiteralPath $reference) {
            Run-Step $reference @('-m', 'venv', $venv) $ai
        } elseif (Get-Command py.exe -ErrorAction SilentlyContinue) {
            Run-Step 'py.exe' @('-3', '-m', 'venv', $venv) $ai
        } else {
            Run-Step (Find-Tool 'python.exe') @('-m', 'venv', $venv) $ai
        }
    }
    $requirements = Join-Path $ai 'requirements.txt'
    if (!(Test-Path -LiteralPath (Join-Path $venv 'installed.txt')) -or
        (Get-Item -LiteralPath $requirements).LastWriteTimeUtc -gt (Get-Item -LiteralPath (Join-Path $venv 'installed.txt')).LastWriteTimeUtc) {
        Run-Step $python @('-m', 'pip', 'install', '-r', $requirements) $ai
        Set-Content -LiteralPath (Join-Path $venv 'installed.txt') -Value 'ready'
    }
    $npmStamp = Join-Path $frontend 'node_modules\.news-meter-ready'
    if (!(Test-Path -LiteralPath $npmStamp) -or
        (Get-Item -LiteralPath (Join-Path $frontend 'package-lock.json')).LastWriteTimeUtc -gt (Get-Item -LiteralPath $npmStamp).LastWriteTimeUtc) {
        Run-Step $npm @('ci', '--no-audit', '--no-fund') $frontend
        Set-Content -LiteralPath $npmStamp -Value 'ready'
    }
    # Build into a new directory so a JAR from a previous Windows process
    # cannot block Spring Boot's in-place repackaging.
    $buildPath = Join-Path $runtime ('backend-build-' + [guid]::NewGuid().ToString('N'))
    Run-Step $maven @('-q', "-Dnews.build.directory=$buildPath", '-DskipTests', 'package') $backend

    $data = Join-Path $runtime 'mysql'
    if (!(Test-Path -LiteralPath (Join-Path $data 'mysql'))) {
        Write-Host 'Initializing the News Meter MySQL data directory...'
        Run-Step $mysqld @('--no-defaults', '--initialize-insecure', '--console', "--datadir=$data") $runtime
    }

    @(
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($settings.DB_ROOT_PASSWORD)';"
        "CREATE DATABASE IF NOT EXISTS news_meter CHARACTER SET utf8mb4;"
        "CREATE USER IF NOT EXISTS 'news_meter'@'localhost' IDENTIFIED BY '$($settings.DB_PASSWORD)';"
        "ALTER USER 'news_meter'@'localhost' IDENTIFIED BY '$($settings.DB_PASSWORD)';"
        "GRANT ALL PRIVILEGES ON news_meter.* TO 'news_meter'@'localhost';"
    ) | Set-Content -LiteralPath $init -Encoding ascii

    Write-Host 'Starting MySQL...'
    $mysqlProcess = Start-ServiceProcess 'mysql' $mysqld @('--no-defaults','--console','--bind-address=127.0.0.1',
        '--port=3317','--mysqlx=OFF', ('--datadir="{0}"' -f $data), ('--init-file="{0}"' -f $init)) $runtime
    Wait-ServiceReady $mysqlProcess 'mysql' {
        $env:MYSQL_PWD = $settings.DB_PASSWORD
        $argsForMySql = '--no-defaults','--protocol=tcp','--host=127.0.0.1','--port=3317','--user=news_meter',
            '--database=news_meter','--execute=SELECT 1'
        try {
            $ErrorActionPreference = 'Continue'
            & $mysql @argsForMySql 2>$null | Out-Null
            return $LASTEXITCODE -eq 0
        } finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    }
    $mysqlActive = $true
    Remove-Item -LiteralPath $init -Force

    Write-Host 'Starting FastAPI...'
    $aiProcess = Start-ServiceProcess 'ai' $python @('-m','uvicorn','main:app','--host','127.0.0.1','--port','8001') $ai
    Wait-ServiceReady $aiProcess 'ai' { Http-Ready 'http://127.0.0.1:8001/health' }

    Write-Host 'Starting Spring Boot...'
    $jar = Join-Path $buildPath 'news-meter-1.0.0.jar'
    $backendProcess = Start-ServiceProcess 'backend' $java @('-jar', ('"{0}"' -f $jar)) $backend
    Wait-ServiceReady $backendProcess 'backend' { Http-Ready 'http://127.0.0.1:8086/api/health' }

    Write-Host 'Starting React...'
    $uiProcess = Start-ServiceProcess 'frontend' $node @('node_modules/vite/bin/vite.js') $frontend
    Wait-ServiceReady $uiProcess 'frontend' { Http-Ready 'http://127.0.0.1:5174/api/health' }
    Write-Host 'News Meter is ready at http://localhost:5174. Keep this window open. Press Ctrl+C to stop.'
    if (!$NoBrowser) { Start-Process 'http://localhost:5174' }

    while ($true) {
        Start-Sleep -Seconds 3
        foreach ($entry in $started) {
            if ($entry.HasExited) { throw 'A service stopped. See the logs under .native.' }
        }
    }
} finally {
    if ($lock) {
        if (Test-Path -LiteralPath $init) { Remove-Item -LiteralPath $init -Force }
        for ($index = $started.Count - 1; $index -ge 1; $index--) {
            if (!$started[$index].HasExited) { Stop-Process -Id $started[$index].Id -ErrorAction SilentlyContinue }
        }
        if ($mysqlActive -and !$started[0].HasExited) {
            $env:MYSQL_PWD = $settings.DB_ROOT_PASSWORD
            & $mysqladmin --no-defaults --protocol=tcp --host=127.0.0.1 --port=3317 --user=root shutdown
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
        if ($started.Count -and !$started[0].HasExited) { Stop-Process -Id $started[0].Id -ErrorAction SilentlyContinue }
        if ($buildPath -and (Test-Path -LiteralPath $buildPath)) {
            $resolved = (Resolve-Path -LiteralPath $buildPath).ProviderPath
            $runtimeRoot = (Resolve-Path -LiteralPath $runtime).ProviderPath
            if ((Split-Path -Parent $resolved) -eq $runtimeRoot -and
                (Split-Path -Leaf $resolved) -match '^backend-build-[a-f0-9]{32}$') {
                Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        $lock.Dispose()
    }
}
