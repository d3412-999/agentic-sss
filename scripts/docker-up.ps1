# =============================================================================
# docker-up.ps1 —— 一条命令：本机打 jar → 构建镜像 → 全栈启动
#
# 用法：
#   .\scripts\docker-up.ps1              # 构建 + 启动（首次约 5~15 分钟）
#   .\scripts\docker-up.ps1 -NoBuild     # 只启动，不重新构建
#   .\scripts\docker-up.ps1 -SkipJar     # 跳过 bootJar（jar 没改动时）
#
# 说明：
#   后端镜像用的是"宿主机先打包、镜像只跑 jar"的快路径，
#   避免容器内下载 Gradle 发行包（国内网络经常卡死/EOF）。
# =============================================================================
param(
    [switch]$NoBuild,
    [switch]$SkipJar
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$compose = 'docker-compose.full.yml'

function Ok([string]$m) { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad([string]$m) { Write-Host "  [XX] $m" -ForegroundColor Red }

# 0. Docker 引擎检查
$ver = docker version --format '{{.Server.Version}}' 2>$null
if (-not $ver) {
    Bad 'Docker 引擎没起来：先打开 Docker Desktop，等鲸鱼图标变绿'
    exit 1
}
Ok "Docker 引擎 $ver"

# 1. 后端打 jar（镜像里不再跑 Gradle）
if (-not $SkipJar) {
    Write-Host "`n[1/3] 打包后端 jar（宿主机 Gradle，约 15 秒）" -ForegroundColor Cyan
    Push-Location (Join-Path $root 'backend')
    & .\gradlew.bat bootJar -x test 2>&1 | Select-Object -Last 3
    $jar = Get-ChildItem 'build\libs\*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    Pop-Location
    if (-not $jar) { Bad 'jar 没打出来'; exit 1 }
    Ok ("jar: {0} ({1:N1} MB)" -f $jar.Name, ($jar.Length / 1MB))
}

# 2. 构建 + 启动
Write-Host "`n[2/3] 构建镜像并启动" -ForegroundColor Cyan
if ($NoBuild) {
    docker compose -f $compose up -d 2>&1 | Select-Object -Last 15
} else {
    docker compose -f $compose up -d --build 2>&1 | Select-Object -Last 25
}
if ($LASTEXITCODE -ne 0) { Bad '启动失败，看上面的报错'; exit 1 }

# 3. 等后端健康
Write-Host "`n[3/3] 等待服务就绪（最多 3 分钟）" -ForegroundColor Cyan
$ok = $false
for ($i = 1; $i -le 36; $i++) {
    Start-Sleep -Seconds 5
    try {
        $r = Invoke-RestMethod 'http://127.0.0.1:18081/api/v1/health' -TimeoutSec 3
        if ($r.code -eq 0) { $ok = $true; break }
    } catch { }
}
docker compose -f $compose ps

Write-Host ""
if ($ok) {
    Ok '后端已就绪'
} else {
    Write-Host '  后端还没起来，看日志：docker compose -f docker-compose.full.yml logs backend' -ForegroundColor Yellow
}
Write-Host "`n入口：" -ForegroundColor Cyan
Write-Host '  前端     http://127.0.0.1:8088'
Write-Host '  后端     http://127.0.0.1:18081/api/v1/health'
Write-Host '  Swagger  http://127.0.0.1:18081/swagger-ui.html'
Write-Host '  AI       http://127.0.0.1:18000/health'
Write-Host "`n账号：2023010101 / 123456（学生）  T2019008 / 123456（教师）"
