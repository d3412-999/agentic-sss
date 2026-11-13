# =============================================================================
# start-local.ps1 — 一键启动本项目（本机模式：MySQL + Redis + AI + 后端 + 前端）
#
# 用法（普通 PowerShell 即可，不需要管理员）：
#   cd D:\zzb\code\a\javacode\1\agentic-sss
#   Set-ExecutionPolicy -Scope Process Bypass -Force
#   .\scripts\start-local.ps1
#
# 会做：
#   1) 检查 MySQL（本机服务，端口 3306）
#   2) 没有 Redis 就启动便携版（D:\zzb\tools\redis）
#   3) 启动 AI 服务（mock 模式）
#   4) 启动后端（gradlew bootRun）
#   5) 启动前端（vite dev）
#
# 端口不再写死在本文件：统一从 scripts/ports.ps1 取（读 application.yaml /
# vue/.env / langchain_service/.env），所以改端口只需要改配置文件。
# 日常启停建议用 .\dev.cmd up / down（带残留清理），本脚本作为兜底保留。
#   6) 轮询健康检查并打印地址
#
# 已启动的服务会自动跳过（可重复执行）。
# =============================================================================
param(
    [string]$RedisExe = 'D:\zzb\tools\redis\redis-server.exe',
    [string]$RedisConf = 'D:\zzb\tools\redis\redis-sss.conf',
    [switch]$NoFrontend
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

. (Join-Path $PSScriptRoot 'ports.ps1')
$ports = Get-SssPorts

function Test-Port([int]$Port) {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $c)
}
function Info([string]$m) { Write-Host "  $m" -ForegroundColor DarkGray }
function Ok([string]$m) { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn2([string]$m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }

Write-Host "仓库：$root" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1. MySQL
Write-Host "`n[1/5] MySQL (3306)" -ForegroundColor Cyan
if (Test-Port 3306) {
    Ok 'MySQL 已在监听 3306'
} else {
    Warn2 '3306 没有监听：请先启动 MySQL 服务（管理员：Start-Service MySQL8.0）'
}

# ---------------------------------------------------------------- 2. Redis
Write-Host "`n[2/5] Redis (6379)" -ForegroundColor Cyan
if (Test-Port 6379) {
    Ok 'Redis 已在监听 6379'
} else {
    if ((Test-Path $RedisExe) -and (Test-Path $RedisConf)) {
        Start-Process -FilePath $RedisExe -ArgumentList $RedisConf -WorkingDirectory (Split-Path $RedisConf) -WindowStyle Minimized
        Start-Sleep -Seconds 2
        if (Test-Port 6379) { Ok 'Redis 已启动（便携版）' } else { Warn2 'Redis 启动失败，登录会 503/401' }
    } else {
        Warn2 "找不到 $RedisExe 或 $RedisConf"
    }
}

# ---------------------------------------------------------------- 3. AI 服务
Write-Host "`n[3/5] AI 服务 ($($ports.Ai))" -ForegroundColor Cyan
if (Test-Port $ports.Ai) {
    Ok 'AI 服务已在运行'
} else {
    $py = Join-Path $root 'langchain_service\.venv\Scripts\python.exe'
    if (Test-Path $py) {
        Start-Process -FilePath $py -ArgumentList 'main.py' -WorkingDirectory (Join-Path $root 'langchain_service') -WindowStyle Minimized
        Info 'AI 服务启动中（窗口已最小化）…'
    } else {
        Warn2 "找不到 $py（先建虚拟环境并 pip install -r requirements.txt）"
    }
}

# ---------------------------------------------------------------- 4. 后端
Write-Host "`n[4/5] 后端 ($($ports.Backend))" -ForegroundColor Cyan
if (Test-Port $ports.Backend) {
    Ok '后端已在运行（可能是 IDEA 里那个实例）'
} else {
    Info 'gradlew bootRun 启动中（首次约 20~40 秒）…'
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'gradlew.bat bootRun' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Minimized
}

# ---------------------------------------------------------------- 5. 前端
Write-Host "`n[5/5] 前端 ($($ports.Frontend))" -ForegroundColor Cyan
if ($NoFrontend) {
    Warn2 '已跳过（-NoFrontend）'
} elseif (Test-Port $ports.Frontend) {
    Ok '前端已在运行'
} else {
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev' -WorkingDirectory (Join-Path $root 'vue') -WindowStyle Minimized
    Info 'vite 启动中…'
}

# ---------------------------------------------------------------- 等待就绪
Write-Host "`n等待服务就绪（最多 120 秒）…" -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(120)
$backendOk = $false
$aiOk = $false
$webOk = $false
while ((Get-Date) -lt $deadline) {
    if (-not $aiOk) {
        try { $r = Invoke-RestMethod $ports.AiHealthUrl -TimeoutSec 2; if ($r.status -eq 'UP') { $aiOk = $true } } catch { }
    }
    if (-not $backendOk) {
        try { $r = Invoke-RestMethod $ports.HealthUrl -TimeoutSec 2; if ($r.code -eq 0) { $backendOk = $true } } catch { }
    }
    if (-not $webOk) { $webOk = Test-Port $ports.Frontend }
    if ($backendOk -and $aiOk -and ($webOk -or $NoFrontend)) { break }
    Start-Sleep -Seconds 3
}

Write-Host "`n===== 状态 =====" -ForegroundColor Cyan
if ($aiOk) { Ok "AI 服务  $($ports.AiHealthUrl)  (LLM=mock, 可空跑)" } else { Warn2 'AI 服务未就绪（答辩/AI 评审会返回 50301）' }
if ($backendOk) { Ok "后端     $($ports.HealthUrl)" } else { Warn2 '后端未就绪（看那个最小化的 cmd 窗口日志）' }
if ($webOk) { Ok "前端     $($ports.FrontendUrl)" } else { Warn2 '前端未就绪' }
Ok "Swagger  $($ports.BackendUrl)/swagger-ui.html"

Write-Host "`n演示账号（密码都是 123456）：" -ForegroundColor Cyan
Write-Host '  教师 T2019008 / 学生 2023010101 / 2023010102 / 2023010103 / 管理员 admin'
Write-Host "`n停止服务：关掉那三个最小化的 cmd 窗口即可（Redis 在任务栏里，右键关闭）。" -ForegroundColor DarkGray
