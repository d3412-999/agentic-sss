# =============================================================================
# stop-local.ps1 —— 正确停止本项目本机模式的所有服务
#
# 用法：
#   .\scripts\stop-local.ps1                  # 停 后端 / AI / 前端
#   .\scripts\stop-local.ps1 -IncludeRedis     # 连 redis 一起停（6379）
#   .\scripts\stop-local.ps1 -OnlyBackend      # 只停后端（比如让 IDEA 接管后端端口）
#
# 端口统一从 scripts/ports.ps1 取（与 dev-up / dev-status 同一来源），
# 不再在本文件里写死数字。
#   .\scripts\stop-local.ps1 -WhatIf           # 只看谁占了端口，不真停
#
# 说明：
#   * 优先按端口找 LISTEN 的进程，比按进程名杀更准确（不会误杀别的 node/java）
#   * 停完会顺手执行 gradlew --stop，清掉 Gradle 守护进程
# =============================================================================
param(
    [switch]$IncludeRedis,
    [switch]$OnlyBackend,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

. (Join-Path $PSScriptRoot 'ports.ps1')
$ports = Get-SssPorts

function Stop-ByPort([int]$Port, [string]$Name) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) {
        Write-Host ("  {0,-8} [{1}] 未被占用" -f $Name, $Port) -ForegroundColor DarkGray
        return
    }
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host ("  {0,-8} [{1}] 端口被 pid {2} 占用，但进程已不存在" -f $Name, $Port, $conn.OwningProcess) -ForegroundColor Yellow
        return
    }
    if ($WhatIf) {
        Write-Host ("  {0,-8} [{1}] 被 {2} (pid {3}) 占用" -f $Name, $Port, $proc.ProcessName, $proc.Id) -ForegroundColor Yellow
        return
    }
    try {
        Stop-Process -Id $proc.Id -Force -ErrorAction Stop
        Write-Host ("  {0,-8} [{1}] 已停止 {2} (pid {3})" -f $Name, $Port, $proc.ProcessName, $proc.Id) -ForegroundColor Green
    } catch {
        Write-Host ("  {0,-8} [{1}] 停止失败：{2}（可能需要管理员）" -f $Name, $Port, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host "停止本机服务…" -ForegroundColor Cyan
if ($OnlyBackend) {
    Stop-ByPort $ports.Backend 'backend'
} else {
    Stop-ByPort $ports.Backend 'backend'
    Stop-ByPort $ports.Ai 'ai'
    Stop-ByPort $ports.Frontend 'frontend'
    if ($IncludeRedis) { Stop-ByPort 6379 'redis' }
}

if (-not $WhatIf) {
    # 清 Gradle 守护进程（不占端口，但吃内存）
    $gradlew = Join-Path $root 'backend\gradlew.bat'
    if (Test-Path $gradlew) {
        Push-Location (Join-Path $root 'backend')
        & $gradlew --stop 2>&1 | Out-Null
        Pop-Location
        Write-Host '  gradle  守护进程已停止' -ForegroundColor DarkGray
    }
}

Write-Host ""
foreach ($p in 3306, 6379, $ports.Ai, $ports.Backend, $ports.Frontend) {
    $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    Write-Host ("  端口 {0}: {1}" -f $p, $(if ($busy) { 'LISTEN' } else { 'free' }))
}
