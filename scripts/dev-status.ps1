<#
.SYNOPSIS
    查看开发环境当前状态：端口、PID、健康检查、日志位置。

.EXAMPLE
    .\dev.cmd status
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$root      = Split-Path -Parent $PSScriptRoot
$stateFile = Join-Path $root '\.run\dev-state.json'

# 端口来源与 dev-up / dev-down 完全一致，避免「状态页显示 8000、实际起在 8010」。
. (Join-Path $PSScriptRoot 'ports.ps1')
$cfg = Get-SssPorts

function Get-ListenerPid([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return 0
}

function Probe([string]$url) {
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec 4 -UseBasicParsing
        return "HTTP $($r.StatusCode)"
    }
    catch {
        return '不可达'
    }
}

Write-Host ''
Write-Host '=== dev status ===' -ForegroundColor Cyan

if (-not (Test-Path $stateFile)) {
    Write-Host '  无状态文件：服务不是由 dev.cmd up 启动的，或已执行过 down。' -ForegroundColor Yellow
    Write-Host ("  配置层端口：后端 {0} / 前端 {1} / AI {2}" -f $cfg.Backend, $cfg.Frontend, $cfg.Ai) -ForegroundColor DarkGray
    Write-Host '  想按端口直接体检：powershell -ExecutionPolicy Bypass -File scripts\port-doctor.ps1'
    Write-Host ''
    exit 0
}

$s = Get-Content -Raw -Path $stateFile | ConvertFrom-Json
Write-Host "  启动于 $($s.startedAt)"
Write-Host ''

if ($s.backend -and $s.backend.port) {
    $port = [int]$s.backend.port
    $live = Get-ListenerPid $port
    $state = if ($live -eq 0) { '未监听' } elseif ($live -eq [int]$s.backend.pid) { '运行中' } else { "被其他进程占用($live)" }
    Write-Host "  后端  :$port   $state   /api/v1/health -> $(Probe "http://127.0.0.1:$port/api/v1/health")"
    Write-Host "        日志 $($s.backend.log)"
}

if ($s.frontend -and $s.frontend.port) {
    $port = [int]$s.frontend.port
    $live = Get-ListenerPid $port
    $state = if ($live -eq 0) { '未监听' } elseif ($live -eq [int]$s.frontend.pid) { '运行中' } else { "被其他进程占用($live)" }
    Write-Host "  前端  :$port   $state   / -> $(Probe "http://127.0.0.1:$port/")"
    Write-Host "        日志 $($s.frontend.log)"
}


if ($s.ai -and $s.ai.port) {
    $port = [int]$s.ai.port
    $live = Get-ListenerPid $port
    $state = if ($live -eq 0) { '未监听' } elseif ($s.ai.pid -and $live -eq [int]$s.ai.pid) { '运行中' } else { '运行中(复用)' }
    Write-Host "  AI    :$port   $state   /health -> $(Probe "http://127.0.0.1:$port/health")"
    if ($s.ai.log) { Write-Host "        日志 $($s.ai.log)" }
}
else {
    Write-Host "  AI    :$($cfg.Ai)   未纳入本次启动（$($cfg.Ai) 若在监听则为外部实例）" -ForegroundColor DarkGray
}

Write-Host ''
Write-Host '  重启  .\dev.cmd restart'
Write-Host '  停止  .\dev.cmd down'
Write-Host ''
