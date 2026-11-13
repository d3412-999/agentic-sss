<#
.SYNOPSIS
    停止由 dev-up.ps1 启动的开发环境，确保端口真正释放。

.DESCRIPTION
    停止顺序很关键，不能反：

    后端：Spring Boot 的 JVM 是 Gradle Daemon 的子进程，不是 gradlew 的子进程。
          先杀 gradlew 树，端口依然被占。所以必须**先杀监听端口的那个 JVM**，
          gradlew 会因为 build 失败而自行退出。
    前端：npm -> cmd -> node(vite) 三层。先杀监听端口的 node，
          再收掉 npm/ cmd 这层外壳，避免留下挂着但不干活的父进程。

    只动三类 PID：
      1) 状态文件 .run/dev-state.json 里登记的；
      2) 端口监听者且命令行含本项目路径、且不含 IDE 特征；
      3) 上面两类进程的子进程树。
    绝不使用 taskkill /IM java.exe /F 之类的全量命令。

.PARAMETER Force
    端口监听者无法确认归属时，仍然结束它。

.PARAMETER BackendOnly / FrontendOnly
    只停一侧。典型用途：把后端让给 IDE（BACKENDONLY），前端继续留着。
    .\dev.cmd backend-off 等价于 .\dev.cmd down -BackendOnly

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-down.ps1
    .\dev.cmd down
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$BackendOnly,
    [switch]$FrontendOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$root       = Split-Path -Parent $PSScriptRoot
$runDir     = Join-Path $root '.run'
$stateFile  = Join-Path $runDir 'dev-state.json'
$rootLower  = $root.ToLowerInvariant()

$ideSignatures = @(
    'js-language-service', 'typescript-plugin', 'typingsinstaller',
    'gradle-server', 'redhat.java', 'spring-boot-language-server',
    '.trae-cn', '.workbuddy', 'intellij idea', 'vscode-gradle',
    'gradle-instrumentation-agent'
)

function Get-ProcInfo([int]$procId) {
    if ($procId -le 0) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
}

function Get-ListenerPid([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return 0
}

function Format-Cmd([string]$cmd) {
    if (-not $cmd) { return '(无命令行)' }
    $one = $cmd -replace '\s+', ' '
    if ($one.Length -gt 160) { return $one.Substring(0, 160) + ' ...' }
    return $one
}

function Test-OwnedByProject([int]$procId) {
    $p = Get-ProcInfo $procId
    if (-not $p -or -not $p.CommandLine) { return $false }
    $cmd = $p.CommandLine.ToLowerInvariant()
    foreach ($sig in $ideSignatures) {
        if ($cmd.Contains($sig)) { return $false }
    }
    return $cmd.Contains($rootLower)
}

<#
    IDE 托管判定（与 dev-up.ps1 保持一致）：
    在 IDEA / Trae 里直接 Run 的后端，命令行既有 idea_rt.jar 这类 IDE 特征，
    又有本项目 classpath。它确实是"本项目的服务"，只是由 IDE 启动的，
    所以 down 时可以结束，但要提示一句：IDE 里会显示进程已退出。

    只对"当前端口监听者"做这个判断，语言服务之类不可能成为监听者，因此不会误伤。
#>
function Test-IdeHosted([int]$procId) {
    $p = Get-ProcInfo $procId
    if (-not $p -or -not $p.CommandLine) { return $false }
    $cmd = $p.CommandLine.ToLowerInvariant()
    foreach ($sig in $ideSignatures) {
        if ($cmd.Contains($sig)) { return $cmd.Contains($rootLower) }
    }
    return $false
}

function Wait-PortFree([int]$port, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ListenerPid $port) -eq 0) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return ((Get-ListenerPid $port) -eq 0)
}

function Stop-Tree([int]$procId) {
    if ($procId -le 0) { return }
    & taskkill.exe /PID $procId /T /F 2>&1 | Out-Null
}

$backendPort  = 0
$frontendPort = 0
$aiPort       = 0
$backendPid   = 0
$frontendPid  = 0
$backendRoot  = 0
$frontendRoot = 0
$aiPid        = 0
$aiRoot       = 0
$fromState    = $false

Write-Host ''
Write-Host '=== dev down ===' -ForegroundColor Cyan
Write-Host "项目根：$root"

if (Test-Path $stateFile) {
    try {
        $s = Get-Content -Raw -Path $stateFile | ConvertFrom-Json
        if ($s.backend)  { $backendPort  = [int]$s.backend.port;  $backendPid  = [int]$s.backend.pid;  $backendRoot  = [int]$s.backend.rootPid }
        if ($s.frontend) { $frontendPort = [int]$s.frontend.port; $frontendPid = [int]$s.frontend.pid; $frontendRoot = [int]$s.frontend.rootPid }
        if ($s.ai)       { $aiPort = [int]$s.ai.port; $aiPid = [int]$s.ai.pid; $aiRoot = [int]$s.ai.rootPid }
        $fromState = $true
        Write-Host "  读取状态文件（启动于 $($s.startedAt)）"
    }
    catch {
        Write-Host '  [警告] 状态文件损坏，改用端口探测' -ForegroundColor Yellow
    }
}

if (-not $fromState -or $backendPort -eq 0) {
    # 没有状态文件：退回端口探测，端口从配置文件读，不硬编码
    $yaml = Join-Path $root 'backend\src\main\resources\application.yaml'
    if (Test-Path $yaml) {
        $m = Select-String -Path $yaml -Pattern '^\s*port:\s*\$\{SERVER_PORT:(\d+)\}' | Select-Object -First 1
        if ($m) { $backendPort = [int]$m.Matches[0].Groups[1].Value }
    }
    $envFile = Join-Path $root 'vue\.env'
    if (Test-Path $envFile) {
        $m2 = Select-String -Path $envFile -Pattern '^\s*FRONTEND_PORT\s*=\s*(\d+)' | Select-Object -First 1
        if ($m2) { $frontendPort = [int]$m2.Matches[0].Groups[1].Value }
    }
    if ($frontendPort -eq 0) { $frontendPort = 5173 }
    Write-Host '  无状态文件，按端口探测'
}

$targets = @()
if ($backendPort  -gt 0 -and -not $FrontendOnly) { $targets += @{ Label = '后端'; Port = $backendPort;  Pid = $backendPid;  RootPid = $backendRoot } }
if ($frontendPort -gt 0 -and -not $BackendOnly)  { $targets += @{ Label = '前端'; Port = $frontendPort; Pid = $frontendPid; RootPid = $frontendRoot } }
# AI 服务默认也一起停；-BackendOnly / -FrontendOnly 是单侧操作，不动 AI
if ($aiPort -gt 0 -and -not $BackendOnly -and -not $FrontendOnly) { $targets += @{ Label = 'AI'; Port = $aiPort; Pid = $aiPid; RootPid = $aiRoot } }

$failed = $false

foreach ($t in $targets) {
    $label = $t.Label
    $port  = $t.Port
    Write-Host ''
    Write-Host "--- $label :$port ---" -ForegroundColor Cyan

    # 现场重新确认监听者，状态文件里的 PID 可能已经过期
    $listener = Get-ListenerPid $port
    if ($listener -eq 0) {
        Write-Host "  端口 $port 已空闲" -ForegroundColor Green
        if ($t.RootPid -gt 0 -and (Get-ProcInfo $t.RootPid)) {
            Stop-Tree $t.RootPid
            Write-Host "  清理启动外壳 PID $($t.RootPid)"
        }
        continue
    }

    $proc      = Get-ProcInfo $listener
    $owned     = Test-OwnedByProject $listener
    $ideHosted = Test-IdeHosted $listener

    # 状态文件登记过，就直接认
    if (-not $owned -and ($listener -eq $t.Pid)) { $owned = $true }
    # IDE 里 Run 的后端同样属于本项目，允许停止
    if (-not $owned -and $ideHosted) { $owned = $true }

    if (-not $owned -and -not $Force) {
        Write-Host "  [中止] 监听 $port 的进程无法确认属于本项目：" -ForegroundColor Red
        Write-Host "    PID $listener  名称 $($proc.Name)  父 $($proc.ParentProcessId)"
        Write-Host "    命令行 $(Format-Cmd $proc.CommandLine)"
        Write-Host '  若确认可以结束它：.\dev.cmd down -Force'
        $failed = $true
        continue
    }

    if ($ideHosted) {
        Write-Host '  [提示] 该进程由 IDE 启动，结束后 IDE 里会显示应用已退出' -ForegroundColor Yellow
    }
    Write-Host "  结束监听者 PID $listener（$($proc.Name)）"
    Stop-Tree $listener

    if (-not (Wait-PortFree $port 15)) {
        Write-Host "  [失败] 端口 $port 仍未释放" -ForegroundColor Red
        $failed = $true
        continue
    }
    Write-Host "  端口 $port 已释放" -ForegroundColor Green

    # 端口释放后再收外壳：后端是 gradlew（Spring Boot JVM 不是它的子进程，所以这一步之前必须先杀 listener）
    if ($t.RootPid -gt 0 -and (Get-ProcInfo $t.RootPid)) {
        Stop-Tree $t.RootPid
        Write-Host "  清理启动外壳 PID $($t.RootPid)"
    }
}

if (Test-Path $stateFile) {
    # 只停一侧时不能整份删掉，否则另一侧的 PID 记录丢了，
    # 下次 dev.cmd up 会把还在健康的服务当残留杀掉重启。
    if ($BackendOnly -or $FrontendOnly) {
        try {
            $obj = Get-Content -Raw -Path $stateFile | ConvertFrom-Json
            if ($BackendOnly)  { $obj.PSObject.Properties.Remove('backend') }
            if ($FrontendOnly) { $obj.PSObject.Properties.Remove('frontend') }
            if (-not $obj.PSObject.Properties['backend'] -and -not $obj.PSObject.Properties['frontend']) {
                Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
                Write-Host ''
                Write-Host '  状态文件已删除（两侧均已停止）'
            }
            else {
                ($obj | ConvertTo-Json -Depth 5) | Set-Content -Path $stateFile -Encoding UTF8
                Write-Host ''
                Write-Host '  状态文件已更新（保留仍在运行的一侧）'
            }
        }
        catch {
            Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
            Write-Host ''
            Write-Host '  [警告] 状态文件处理失败，已直接删除' -ForegroundColor Yellow
        }
    }
    else {
        Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
        Write-Host ''
        Write-Host '  状态文件已删除'
    }
}

Write-Host ''
if ($failed) {
    Write-Host '[注意] 有端口未能释放，见上面的提示。' -ForegroundColor Yellow
    exit 1
}
Write-Host '[完成] 开发环境已停止' -ForegroundColor Green
exit 0
