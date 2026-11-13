<#
.SYNOPSIS
    端口一致性体检：一句话判断「前端代理打的是不是后端真正在听的端口」。

.DESCRIPTION
    只读脚本，不启动也不杀任何进程。

    判断端口的关键不是「哪个 java 进程在监听」——Gradle daemon、IDE 都会监听端口，
    靠进程名猜必然误判。真正可靠的做法只有一条：
    对候选端口发一次真实请求，看它答不答得出本项目的身份（app = sss-backend）。

    输出四段事实 + 一个结论 + 两次端到端探测。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\port-doctor.ps1
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # 关掉 Invoke-WebRequest 的进度条，避免污染输出
$root = Split-Path -Parent $PSScriptRoot

function Probe-Backend([int]$port) {
    # 返回 $true 表示该端口确实是本项目的 Spring Boot 后端
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/v1/health" -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200 -and $r.Content -like '*sss-backend*') { return $true }
    }
    catch { }
    return $false
}

function Probe-Frontend([int]$port) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200) { return $true }
    }
    catch { }
    return $false
}

Write-Host ''
Write-Host '=== 端口体检 ===' -ForegroundColor Cyan
Write-Host "项目根: $root"
Write-Host ''

# ---------- 1. 后端声明端口 ----------
$yamlPath = Join-Path $root 'backend\src\main\resources\application.yaml'
$declared = 0
if (Test-Path $yamlPath) {
    $line = Select-String -Path $yamlPath -Pattern '^\s*port:\s*\$\{SERVER_PORT:(\d+)\}' | Select-Object -First 1
    if ($line) { $declared = [int]$line.Matches[0].Groups[1].Value }
}
Write-Host ("  {0,-10} {1}" -f '后端声明', $(if ($declared) { "$declared  (application.yaml 默认值)" } else { '未识别' }))

# ---------- 2. 前端代理目标 ----------
$targetPort = 0
$envPath = Join-Path $root 'vue\.env'
if (Test-Path $envPath) {
    $m = Select-String -Path $envPath -Pattern '^\s*VITE_API_TARGET\s*=\s*(.+)$' | Select-Object -First 1
    if ($m -and $m.Matches[0].Groups[1].Value -match ':(\d+)') { $targetPort = [int]$Matches[1] }
}
Write-Host ("  {0,-10} {1}" -f '前端代理', $(if ($targetPort) { "http://127.0.0.1:$targetPort  (vue/.env VITE_API_TARGET)" } else { 'vue/.env 未配置，vite.config.js 会兜底' }))

# ---------- 3. 后端实际端口（靠身份验证，不靠进程名） ----------
$candidates = New-Object System.Collections.ArrayList
if ($declared -gt 0)   { [void]$candidates.Add($declared) }
if ($targetPort -gt 0) { [void]$candidates.Add($targetPort) }
# 兜底：本机所有 java 进程监听的端口也纳入候选（可能用 -Dserver.port 覆盖过）
Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
    $procId = $_.Id
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $procId } |
        ForEach-Object { [void]$candidates.Add([int]$_.LocalPort) }
}
$candidates = $candidates | Sort-Object -Unique

$backendPort = 0
foreach ($p in $candidates) {
    if (Probe-Backend $p) { $backendPort = $p; break }
}

if ($backendPort -gt 0) {
    Write-Host ("  {0,-10} {1}" -f '后端实际', "$backendPort  (已用 /api/v1/health 验证是本项目)")
}
else {
    Write-Host ("  {0,-10} {1}" -f '后端实际', '没找到！候选端口全部探测失败')
}

# ---------- 4. 前端实际端口 ----------
$frontPort = 0
$nodePids = Get-Process node -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id
if ($nodePids) {
    $nodePorts = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
                 Where-Object { $nodePids -contains $_.OwningProcess } |
                 Select-Object -ExpandProperty LocalPort -Unique | Sort-Object
    foreach ($p in $nodePorts) {
        if (Probe-Frontend $p) { $frontPort = $p; break }
    }
}
Write-Host ("  {0,-10} {1}" -f '前端实际', $(if ($frontPort) { "$frontPort  (Vite dev server 在跑)" } else { '没有可访问的 dev server' }))

# ---------- 结论 ----------
Write-Host ''
Write-Host '--- 结论 ---' -ForegroundColor Cyan
$problems = @()

if ($backendPort -eq 0) {
    $problems += '后端没启动：先 cd backend; .\gradlew bootRun'
}
elseif ($declared -gt 0 -and $backendPort -ne $declared) {
    $problems += "后端跑在 $backendPort，但 yaml 声明 $declared —— 有 SERVER_PORT 环境变量在覆盖它（去 IDE 启动配置里查）"
}

if ($targetPort -gt 0 -and $backendPort -gt 0 -and $targetPort -ne $backendPort) {
    $problems += "前端代理指向 $targetPort，后端却在 $backendPort —— 这就是 ECONNREFUSED 的根因。改 vue/.env 的 VITE_API_TARGET，然后重启 Vite"
}

if ($frontPort -eq 0) {
    $problems += 'Vite dev server 没启动：cd vue; npm run dev'
}

if ($problems.Count -eq 0) {
    Write-Host '  [OK] 端口一致' -ForegroundColor Green
}
else {
    foreach ($p in $problems) { Write-Host "  [!!] $p" -ForegroundColor Yellow }
}

# ---------- 端到端 ----------
if ($frontPort -gt 0) {
    Write-Host ''
    Write-Host "--- 端到端: 前端 $frontPort -> /api/v1/health ---" -ForegroundColor Cyan
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$frontPort/api/v1/health" -TimeoutSec 8 -UseBasicParsing
        Write-Host "  [OK] HTTP $($r.StatusCode)，代理链路通畅" -ForegroundColor Green
    }
    catch {
        Write-Host "  [!!] 代理失败: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host '       Vite 终端里会出现 http proxy error ... ECONNREFUSED'
    }
}

if ($backendPort -gt 0) {
    Write-Host ''
    Write-Host "--- 直连: 后端 $backendPort ---" -ForegroundColor Cyan
    try {
        $r2 = Invoke-WebRequest -Uri "http://127.0.0.1:$backendPort/api/v1/health" -TimeoutSec 5 -UseBasicParsing
        Write-Host "  [OK] HTTP $($r2.StatusCode)，后端本身健康" -ForegroundColor Green
    }
    catch {
        Write-Host "  [!!] 端口开着但请求失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ''
