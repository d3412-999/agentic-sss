<#
.SYNOPSIS
    启动本机 Redis（Windows 开发机辅助脚本）。

.DESCRIPTION
    只做一件事：确保 Redis 在指定端口可连。
      1. 端口已在监听 -> 直接报告 PING 结果，不重复启动；
      2. 找到 redis-server.exe（$env:REDIS_HOME 或 PATH）-> 后台启动；
      3. 都没有 -> 退回 docker 起一个带口令的 redis 容器。
    Redis 是后端硬依赖：登录限流、幂等、refresh token 都走它。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
    $env:REDIS_HOME = 'C:\Redis'; powershell -ExecutionPolicy Bypass -File scripts\start-redis.ps1
#>
param(
    [int]$Port        = $(if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }),
    [string]$Password = $(if ($env:REDIS_PASSWORD) { $env:REDIS_PASSWORD } else { '123456' }),
    [string]$RedisHome = $env:REDIS_HOME,
    [string]$Container = 'sss-redis'
)

$ErrorActionPreference = 'Stop'

function Test-RedisPort([int]$p) {
    return (Test-NetConnection 127.0.0.1 -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded
}

function Find-RedisBinary([string]$name, [string]$dirHint) {
    if ($dirHint) {
        $p = Join-Path $dirHint $name
        if (Test-Path $p) { return $p }
    }
    # 常见 Windows 安装位置；也可以用 REDIS_HOME / -RedisHome 指定
    $candidates = @(
        'C:\Redis',
        'C:\Program Files\Redis',
        'C:\Program Files (x86)\Redis',
        (Join-Path $env:LOCALAPPDATA 'Redis'),
        (Join-Path $env:ProgramData 'chocolatey\lib\redis-64\tools')
    )
    foreach ($dir in $candidates) {
        $p = Join-Path $dir $name
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

if (Test-RedisPort $Port) {
    Write-Host "[redis] port $Port already listening."
} else {
    $server = Find-RedisBinary 'redis-server.exe' $RedisHome

    if ($server) {
        $dir = Split-Path $server -Parent
        $conf = Join-Path $dir 'redis.windows.conf'
        $serverArgs = if (Test-Path $conf) { @($conf) } else { @('--port', "$Port", '--requirepass', $Password) }
        Start-Process -FilePath $server -ArgumentList $serverArgs -WorkingDirectory $dir -WindowStyle Hidden
        Write-Host "[redis] launching $server"
    } else {
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if (-not $docker) {
            throw 'No redis-server.exe found. Set $env:REDIS_HOME (folder containing redis-server.exe) or install Docker.'
        }
        # 守护进程没起时 docker run 会卡住，先做一次快速探测（3s 超时）
        $probe = Start-Process -FilePath 'docker' -ArgumentList 'info', '--format', '{{.ServerVersion}}' `
            -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\sss-docker-probe.txt" `
            -RedirectStandardError "$env:TEMP\sss-docker-probe.err"
        if (-not $probe.WaitForExit(3000)) { $probe.Kill(); $probe.WaitForExit() }
        if ($probe.ExitCode -ne 0) {
            throw 'Docker daemon is not running. Start Docker Desktop, or set $env:REDIS_HOME to your Redis folder.'
        }
        & docker run -d --name $Container -p "${Port}:6379" redis:7.2 --requirepass $Password | Out-Null
        Write-Host "[redis] started docker container $Container"
    }

    for ($i = 0; $i -lt 30 -and -not (Test-RedisPort $Port); $i++) { Start-Sleep -Milliseconds 500 }
    if (-not (Test-RedisPort $Port)) { throw "[redis] port $Port is still closed; check the Redis log or the container." }
}

$cli = Find-RedisBinary 'redis-cli.exe' $RedisHome
if ($cli) {
    $out = & $cli -a $Password ping 2>&1
    $last = ($out | Where-Object { "$_" -match '\S' } | Select-Object -Last 1)
    Write-Host "[redis] AUTH + PING -> $last"
} else {
    Write-Host "[redis] ready. Verify with: redis-cli -a <password> ping"
}