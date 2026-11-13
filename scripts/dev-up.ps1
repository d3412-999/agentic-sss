<#
.SYNOPSIS
    一键启动本地开发环境：清残留 -> 起后端 -> 等健康检查 -> 起前端。

.DESCRIPTION
    为什么需要这个脚本（以及它到底在治什么病）：

    1) `npm run dev` 的进程链是三层的：
          npm(node) -> cmd.exe /c vite -> node.exe (真正监听端口)
       只关终端或只 Ctrl+C 掉 npm，孙子 node 仍然活着，端口就被"孤儿"占住。

    2) `./gradlew bootRun` 里 Spring Boot 的 JVM 是 **Gradle Daemon 的子进程**，
       不是 gradlew 的子进程。所以杀 gradlew 的进程树，端口依然被占。
       停止时必须认「谁在监听这个端口」，而不是「我启动了谁」。

    3) Vite 的 strictPort 已设为 true：端口被占会直接失败，不会悄悄 +1。
       清理的责任因此完全落在这个脚本上。

    安全原则：
      * 绝不使用 taskkill /IM java.exe /F 这类全量命令（本机同时开着 IDEA 与 Trae，
        各自有 Java 语言服务、Gradle Daemon，误杀会导致 IDE 抽风）；
      * 只动两类进程：状态文件里登记的、以及命令行含本项目路径且不含 IDE 特征的；
      * 无法确认归属时中止，交由使用者决定（-Force 视为已授权）。

.PARAMETER Force
    端口被无法确认归属的进程占用时，仍然强制结束它。

.PARAMETER BackendOnly / FrontendOnly
    只启动一侧。

.PARAMETER TimeoutSec
    后端健康检查的最长等待秒数，默认 180。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
    .\dev.cmd up
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$root       = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$vueDir     = Join-Path $root 'vue'
$runDir     = Join-Path $root '.run'
$logDir     = Join-Path $root 'logs'
$stateFile  = Join-Path $runDir 'dev-state.json'
$rootLower  = $root.ToLowerInvariant()

# ---------------------------------------------------------------------------
# 凭据注入：Spring Boot 不读 .env，只认环境变量；application.yaml 里只有占位符。
# 仓库不能存放真实密码，所以本地凭据写在根目录 .env（已被 .gitignore 排除），
# 这里把它灌进当前进程，`gradlew bootRun` 子进程会继承。
# 只处理凭据白名单键：.env 里的端口等配置交给下面自己的端口协商逻辑，避免打架。
# ---------------------------------------------------------------------------
$credentialKeys = @(
    'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DB_ROOT_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD',
    'JWT_SECRET', 'TEACHER_DEFAULT_PASSWORD', 'STUDENT_DEFAULT_PASSWORD',
    'STUDENT_PASSWORD_MODE'
)
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    $credLoaded = 0
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $k = $Matches[1]
            $v = ($Matches[2].Trim() -replace '^["'']', '' -replace '["'']$', '').Trim()
            if (($credentialKeys -contains $k) -and -not [string]::IsNullOrWhiteSpace($v)) {
                Set-Item -Path "Env:$k" -Value $v
                $credLoaded++
            }
        }
    }
    if ($credLoaded -gt 0) {
        Write-Host "  已从 .env 载入 $credLoaded 项凭据（数据库 / Redis / 初始密码）" -ForegroundColor DarkGray
    }
}

# 端口/地址的唯一事实来源（被 dev-status / start-local / stop-local 共用）。
# 这里只借用 AI 端口解析；后端与前端仍沿用本文件下方的读配置逻辑，
# 因为 dev-up 需要额外的交叉校验（VITE_API_TARGET 与后端端口必须一致）。
. (Join-Path $PSScriptRoot 'ports.ps1')

# 命令行里即使出现项目路径，下面这些也绝不能当成"本项目服务"
$ideSignatures = @(
    'js-language-service', 'typescript-plugin', 'typingsinstaller',
    'gradle-server', 'redhat.java', 'spring-boot-language-server',
    '.trae-cn', '.workbuddy', 'intellij idea', 'vscode-gradle',
    'gradle-instrumentation-agent'
)

$script:started = @()   # 本次启动产生的 PID（失败时用于回滚）

# ---------------------------------------------------------------- 基础工具

function Write-Section([string]$text) {
    Write-Host ''
    Write-Host "--- $text ---" -ForegroundColor Cyan
}

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

<#
    归属判定：只有一个目的——别杀错人。
    命中任一 IDE 特征直接否掉；否则要求命令行里出现本项目的绝对路径。
#>
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
    IDE 托管判定：在 IDEA / Trae 里直接 Run 后端时，命令行同时带上
    idea_rt.jar（IDE 特征）与本项目 classpath（本项目路径）。

    这类进程不是"孤儿残留"——它是用户正在调试的会话，杀了会打断断点。
    所以单独识别出来：能复用就复用，绝不当残留清理。
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

function Wait-Http([string]$url, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { return $true }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-Tree([int]$procId) {
    if ($procId -le 0) { return }
    # /T 连子进程一起收，避免留下孤儿；PID 已在上游核实过归属
    & taskkill.exe /PID $procId /T /F 2>&1 | Out-Null
}

function Show-Proc([int]$procId, [string]$prefix) {
    $p = Get-ProcInfo $procId
    if (-not $p) { Write-Host "  $prefix PID $procId  (已不存在)"; return }
    Write-Host "  $prefix PID $($p.ProcessId)  名称 $($p.Name)  父 $($p.ParentProcessId)"
    Write-Host "  $prefix 命令行 $(Format-Cmd $p.CommandLine)"
}

<#
    启动一个真正"分离"的后台进程，返回 PID。

    为什么不用 Start-Process：
      本机环境变量同时存在 HTTPS_PROXY 与 https_proxy，Start-Process 在构造环境变量
      字典时会因大小写撞键抛 "已添加项。字典中的关键字:HTTPS_PROXY"。

    为什么不用 .NET Process::Start：
      子进程会继承调用方的 stdout 句柄。一旦调用方用管道捕获输出
      （如 $out = & dev.cmd up），服务进程就握着管道写端不放，
      捕获端永远等不到 EOF，命令直接挂死。

    为什么用 Win32_Process.Create（WMI）：
      它由 WmiPrvSE 代创建，既不会继承我们的句柄，也不在调用方的 Job Object 里，
      父进程退出不会连带杀死服务。日志由 cmd 的 > 重定向直接写文件。
#>
function Start-Detached([string]$commandLine, [string]$workDir) {
    $res = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = $commandLine
        CurrentDirectory = $workDir
    }
    if ($res.ReturnValue -ne 0) {
        throw "进程创建失败（Win32_Process.Create 返回 $($res.ReturnValue)）：$commandLine"
    }
    return [int]$res.ProcessId
}

# ---------------------------------------------------------------- 配置读取
# 端口不在这里硬编码：后端读 application.yaml 的默认值，前端读 vue/.env。
# 这样"唯一真相源"仍然只有配置文件一处，脚本只是执行者。

function Get-BackendPort {
    $yaml = Join-Path $backendDir 'src\main\resources\application.yaml'
    if (-not (Test-Path $yaml)) { throw "找不到后端配置：$yaml" }
    $m = Select-String -Path $yaml -Pattern '^\s*port:\s*\$\{SERVER_PORT:(\d+)\}' | Select-Object -First 1
    if (-not $m) { throw "在 $yaml 中未找到 'port: `${SERVER_PORT:默认值}'" }
    return [int]$m.Matches[0].Groups[1].Value
}

function Get-EnvValue([string]$file, [string]$key) {
    if (-not (Test-Path $file)) { return $null }
    $m = Select-String -Path $file -Pattern "^\s*$key\s*=\s*(.+)$" | Select-Object -First 1
    if (-not $m) { return $null }
    return $m.Matches[0].Groups[1].Value.Trim()
}

# ---------------------------------------------------------------- 主流程

$backendPort  = 0
$frontendPort = 0
$apiTargetPort = 0

try {
    Write-Host ''
    Write-Host '=== dev up ===' -ForegroundColor Cyan
    Write-Host "项目根：$root"

    # ---------- 1. 读配置 ----------
    Write-Section '配置'
    $backendPort = Get-BackendPort
    Write-Host "  后端端口 $backendPort  <- backend/src/main/resources/application.yaml"

    $frontendPort = Get-EnvValue (Join-Path $vueDir '.env') 'FRONTEND_PORT'
    if (-not $frontendPort) { $frontendPort = 3002 }
    $frontendPort = [int]$frontendPort
    Write-Host "  前端端口 $frontendPort  <- vue/.env FRONTEND_PORT"

    $apiTarget = Get-EnvValue (Join-Path $vueDir '.env') 'VITE_API_TARGET'
    if ($apiTarget -and ($apiTarget -match ':(\d+)')) { $apiTargetPort = [int]$Matches[1] }
    Write-Host "  代理目标 $apiTarget  <- vue/.env VITE_API_TARGET"

    # 上次的坑：代理指 8081，后端在 8084。这里提前拦住，不让它跑到浏览器里才暴露。
    if ($apiTargetPort -gt 0 -and $apiTargetPort -ne $backendPort) {
        throw "配置错位：vue/.env 的 VITE_API_TARGET 指向 $apiTargetPort，但后端端口是 $backendPort。`n      改 vue/.env 的 VITE_API_TARGET 为 http://127.0.0.1:$backendPort 后重试。"
    }

    if ($env:SERVER_PORT -and ([int]$env:SERVER_PORT -ne $backendPort)) {
        Write-Host "  [提示] 环境变量 SERVER_PORT=$($env:SERVER_PORT) 会覆盖 yaml 默认值；" -ForegroundColor Yellow
        Write-Host "         脚本已用 --server.port=$backendPort 显式钉死，实际仍是 $backendPort。" -ForegroundColor Yellow
    }

    # ---------- 2. 环境检查 ----------
    Write-Section '环境'
    foreach ($c in @('java', 'node', 'npm')) {
        if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "未找到命令 $c，请先安装并加入 PATH。" }
    }
    Write-Host '  java / node / npm  可用'
    if (-not $BackendOnly) {
        if (-not (Test-Path (Join-Path $backendDir 'gradlew.bat'))) { throw '缺少 backend\gradlew.bat' }
    }
    if (-not $FrontendOnly) {
        if (-not (Test-Path (Join-Path $vueDir 'node_modules\vite'))) {
            throw 'vue\node_modules 不完整，请先 cd vue; npm install'
        }
    }
    Write-Host '  启动文件齐全'

    # ---------- 3. 真的已经在跑吗？ ----------
    # 只判断"监听者还活着"是不够的：关掉终端后 JVM / vite 会变孤儿，端口照样被占，
    # 但环境其实已经不可用了。所以三条同时成立才算"运行中"：
    #   ① 监听者进程存在 ② 它确实是当前端口的监听者 ③ 健康检查通 ④ 启动它的外壳还活着
    $staleReason = $null
    if (Test-Path $stateFile) {
        try {
            $old = Get-Content -Raw -Path $stateFile | ConvertFrom-Json
            $checks = New-Object System.Collections.ArrayList
            if (-not $FrontendOnly -and $old.backend) {
                [void]$checks.Add(@{ Name = '后端'; Port = [int]$old.backend.port; Pid = [int]$old.backend.pid; RootPid = [int]$old.backend.rootPid })
            }
            if (-not $BackendOnly -and $old.frontend) {
                [void]$checks.Add(@{ Name = '前端'; Port = [int]$old.frontend.port; Pid = [int]$old.frontend.pid; RootPid = [int]$old.frontend.rootPid })
            }

            $allOk = $checks.Count -gt 0
            foreach ($c in $checks) {
                $live     = Get-ListenerPid $c.Port
                $okProc   = ($c.Pid -gt 0) -and (Get-ProcInfo $c.Pid)
                $okPort   = ($live -eq $c.Pid)
                # rootPid=0 表示"复用/IDE 托管"，本来就没有启动外壳，不该判为孤儿
                $okRoot   = ($c.RootPid -le 0) -or ($null -ne (Get-ProcInfo $c.RootPid))

                $okHealth = $false
                if ($okPort) {
                    if ($c.Name -eq '后端') {
                        $okHealth = Wait-Http "http://127.0.0.1:$($c.Port)/api/v1/health" 3
                    }
                    else {
                        $okHealth = Wait-Http "http://127.0.0.1:$($c.Port)/" 3
                    }
                }

                if (-not ($okProc -and $okPort -and $okHealth -and $okRoot)) {
                    $allOk = $false
                    if ($null -eq $staleReason) {
                        if (-not $okProc -or -not $okPort) {
                            $staleReason = "$($c.Name) 端口 $($c.Port) 的监听进程已不存在"
                        }
                        elseif (-not $okHealth) {
                            $staleReason = "$($c.Name) 端口 $($c.Port) 有进程但不响应"
                        }
                        else {
                            $staleReason = "$($c.Name) 的启动外壳已退出，但进程仍占着 $($c.Port)（孤儿）"
                        }
                    }
                }
            }

            if ($allOk) {
                Write-Section '已在运行'
                Write-Host '  后端与前端均健康，跳过重复启动' -ForegroundColor Green
                Write-Host '  如需重启：.\dev.cmd restart'
                exit 0
            }

            Write-Host ''
            Write-Host "  [残留] $staleReason" -ForegroundColor Yellow
            Write-Host '         按下面的规则清理后重新启动。' -ForegroundColor Yellow
        }
        catch { }
    }

    # ---------- 4. 清理端口 ----------
    # 原则：能复用就复用，不能确认归属才中止，确认可杀才杀。
    # 「健康 + 属于本项目（含 IDE 托管）」的监听者一律保留。
    # 否则在 IDEA 里 Run 过后再跑 dev.cmd up，就会把正在调试的后端顶掉。
    Write-Section '清理端口'

    $portsToClear = @()
    if (-not $FrontendOnly) { $portsToClear += @{ Port = $backendPort;  Label = '后端' } }
    if (-not $BackendOnly)  { $portsToClear += @{ Port = $frontendPort; Label = '前端' } }

    $reuseBackend   = $false
    $reuseFrontend  = $false
    $backendBootPid = 0
    $frontendProcId = 0

    foreach ($item in $portsToClear) {
        $port  = $item.Port
        $label = $item.Label
        # 注意：$pid 是 PowerShell 内置只读变量（当前进程 ID），绝不能拿来赋值
        $holderPid = Get-ListenerPid $port

        if ($holderPid -eq 0) {
            Write-Host "  $label $port 空闲" -ForegroundColor Green
            continue
        }

        $owned     = Test-OwnedByProject $holderPid
        $ideHosted = Test-IdeHosted $holderPid

        Write-Host "  $label $port 被占用："
        Show-Proc $holderPid '   '

        if (-not $owned -and -not $ideHosted -and -not $Force) {
            throw "端口 $port 上的进程无法确认属于本项目，已中止。`n      若确认可以结束它，请执行：.\dev.cmd up -Force"
        }

        # 已归属的监听者先探活：健康就复用，别为了"启动"打断一个能用的服务
        $healthy = $false
        if ($owned -or $ideHosted) {
            if ($label -eq '后端') { $healthy = Wait-Http "http://127.0.0.1:$port/api/v1/health" 4 }
            else                   { $healthy = Wait-Http "http://127.0.0.1:$port/" 4 }
        }

        if ($healthy) {
            if ($label -eq '后端') { $reuseBackend = $true; $backendBootPid = $holderPid }
            else                   { $reuseFrontend = $true; $frontendProcId = $holderPid }
            $who = if ($ideHosted) { 'IDE 托管' } else { '本项目' }
            Write-Host "  [复用] $label 已在运行且健康（$who），保持不动" -ForegroundColor Green
            continue
        }

        if (-not $owned) {
            Write-Host '  [强制] -Force 已授权，结束该进程树' -ForegroundColor Yellow
        }
        if ($ideHosted) {
            Write-Host '  [提示] 该进程由 IDE 启动，结束它会让 IDE 里的应用显示退出' -ForegroundColor Yellow
        }

        Stop-Tree $holderPid
        if (Wait-PortFree $port 15) {
            Write-Host "  $label $port 已释放" -ForegroundColor Green
        }
        else {
            throw "端口 $port 在 15 秒内未释放，请手动检查（可能是系统级服务或权限不足）。"
        }
    }

    if (-not (Test-Path $runDir)) { New-Item -ItemType Directory -Path $runDir -Force | Out-Null }
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

    # ---------- 4.5 启动 AI 服务（可选） ----------
    # 顺序上放在后端之前：AI 起得快（1~2 秒），先就位后端第一次评审就能用；
    # 但它不是后端的硬依赖（后端只在评审时调用，失败会降级为 50301），
    # 所以这里失败只警告、不中断——本地只调前端样式时不该被它卡住。
    $aiPort = Get-SssAiPort   # langchain_service/.env 的 AI_PORT，缺省 8000

    $aiRootPid = 0
    $aiLog = $null
    $aiReused = $false
    if (-not $BackendOnly -and -not $FrontendOnly) {
        Write-Section "AI 服务 :$aiPort"
        $aiPython = Join-Path $root 'langchain_service\.venv\Scripts\python.exe'
        if (-not (Test-Path $aiPython)) {
            Write-Host '  未找到 langchain_service\.venv，跳过（源码/报告评审会返回 50301）' -ForegroundColor Yellow
        }
        elseif ((Get-ListenerPid $aiPort) -gt 0) {
            if (Wait-Http "http://127.0.0.1:$aiPort/health" 3) {
                $aiReused = $true
                Write-Host "  复用已在运行的 AI 服务（PID $(Get-ListenerPid $aiPort)）" -ForegroundColor Green
            }
            else {
                Write-Host "  [警告] $aiPort 被其它进程占用且健康检查不通，跳过启动（不会误杀）" -ForegroundColor Yellow
            }
        }
        else {
            $aiLog = Join-Path $logDir "ai-$stamp.log"
            $aiInner = '-m uvicorn app.main:app --host 127.0.0.1 --port ' + $aiPort + ' > "' + $aiLog + '" 2>&1'
            $aiCmdLine = 'cmd.exe /c ""' + $aiPython + '" ' + $aiInner + '"'
            $aiRootPid = Start-Detached $aiCmdLine (Join-Path $root 'langchain_service')
            $script:started += $aiRootPid
            Write-Host "  uvicorn 已启动（PID $aiRootPid），等待健康检查…"
            if (Wait-Http "http://127.0.0.1:$aiPort/health" 60) {
                Write-Host "  AI 就绪（PID $(Get-ListenerPid $aiPort)）" -ForegroundColor Green
            }
            else {
                Write-Host '  [警告] AI 服务未就绪，评审会返回 50301（不影响后端起停）' -ForegroundColor Yellow
                if (Test-Path $aiLog) { Get-Content $aiLog -Tail 12 | ForEach-Object { Write-Host "  $_" } }
            }
        }
    }

    # ---------- 5. 启动后端 ----------
    $backendRootPid = 0
    $backendLog = $null
    if (-not $FrontendOnly) {
        if ($reuseBackend) {
            Write-Section "复用后端 :$backendPort"
            Write-Host "  沿用已在运行的后端（PID $backendBootPid），不重启" -ForegroundColor Green
        }
        else {
            Write-Section "启动后端 :$backendPort"
            $backendLog    = Join-Path $logDir "backend-$stamp.log"
            $backendErrLog = $backendLog   # stdout/stderr 由 cmd 的 2>&1 合并

            # 显式传 --server.port，避免被系统环境变量 SERVER_PORT 悄悄改掉
            $gradlew = Join-Path $backendDir 'gradlew.bat'
            $inner   = 'bootRun --args=--server.port=' + $backendPort + ' > "' + $backendLog + '" 2>&1'
            $backendCmdLine = 'cmd.exe /c ""' + $gradlew + '" ' + $inner + '"'
            $backendRootPid = Start-Detached $backendCmdLine $backendDir
            $script:started += $backendRootPid
            Write-Host "  gradlew 已启动（PID $backendRootPid），等待健康检查…"

            if (-not (Wait-Http "http://127.0.0.1:$backendPort/api/v1/health" $TimeoutSec)) {
                Write-Host '  --- 日志尾部 ---' -ForegroundColor Red
                if (Test-Path $backendLog) { Get-Content $backendLog -Tail 30 | ForEach-Object { Write-Host "  $_" } }
                throw "后端 $TimeoutSec 秒内未就绪。完整日志：$backendLog"
            }

            $backendBootPid = Get-ListenerPid $backendPort
            if ($backendBootPid -gt 0) { $script:started += $backendBootPid }
            Write-Host "  后端就绪（Spring Boot JVM PID $backendBootPid）" -ForegroundColor Green
        }
    }

    # ---------- 6. 启动前端 ----------
    $frontendRootPid = 0
    $frontendLog = $null
    if (-not $BackendOnly) {
        if ($reuseFrontend) {
            Write-Section "复用前端 :$frontendPort"
            Write-Host "  沿用已在运行的 vite（PID $frontendProcId），不重启" -ForegroundColor Green
        }
        else {
            Write-Section "启动前端 :$frontendPort"
            $frontendLog    = Join-Path $logDir "frontend-$stamp.log"
            $frontendErrLog = $frontendLog

            $frontendCmdLine = 'cmd.exe /c npm run dev > "' + $frontendLog + '" 2>&1'
            $frontendRootPid = Start-Detached $frontendCmdLine $vueDir
            $script:started += $frontendRootPid
            Write-Host "  npm run dev 已启动（PID $frontendRootPid），等待页面可用…"

            if (-not (Wait-Http "http://127.0.0.1:$frontendPort/" 90)) {
                Write-Host '  --- 日志尾部 ---' -ForegroundColor Red
                if (Test-Path $frontendLog) { Get-Content $frontendLog -Tail 25 | ForEach-Object { Write-Host "  $_" } }
                throw "前端 90 秒内未就绪。完整日志：$frontendLog"
            }

            $frontendProcId = Get-ListenerPid $frontendPort
            if ($frontendProcId -gt 0) { $script:started += $frontendProcId }
            Write-Host "  前端就绪（vite node PID $frontendProcId）" -ForegroundColor Green
        }

        # 端到端：代理能不能真的把请求送到后端
        if (-not $BackendOnly) {
            if (Wait-Http "http://127.0.0.1:$frontendPort/api/v1/health" 20) {
                Write-Host '  代理链路 /api -> 后端 通畅' -ForegroundColor Green
            }
            else {
                Write-Host '  [警告] 前端起来了，但 /api 代理未打通，检查 vue/.env 的 VITE_API_TARGET' -ForegroundColor Yellow
            }
        }
    }

    # ---------- 7. 写状态 ----------
    $state = [ordered]@{
        startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        projectRoot = $root
        backend = [ordered]@{
            port     = $backendPort
            rootPid  = $backendRootPid          # gradlew（启动它的外层进程）
            pid      = (Get-ListenerPid $backendPort)   # 真正监听端口的 Spring Boot JVM
            log      = $backendLog
        }
        frontend = [ordered]@{
            port     = $frontendPort
            rootPid  = $frontendRootPid         # npm
            pid      = (Get-ListenerPid $frontendPort)  # 真正监听端口的 vite node
            log      = $frontendLog
        }
        ai = [ordered]@{
            port     = $aiPort
            rootPid  = $aiRootPid               # cmd 外壳
            pid      = (Get-ListenerPid $aiPort)  # uvicorn
            log      = $aiLog
        }
    }
    ($state | ConvertTo-Json -Depth 5) | Set-Content -Path $stateFile -Encoding UTF8

    # ---------- 8. 汇总 ----------
    Write-Host ''
    Write-Host '================ 开发环境已就绪 ================' -ForegroundColor Green
    if (-not $FrontendOnly) {
        $backendTag = if ($reuseBackend) { '  （沿用已在运行的实例）' } else { '' }
        Write-Host ("  后端      http://127.0.0.1:$backendPort/api/v1/health$backendTag")
        Write-Host ("  Swagger   http://127.0.0.1:$backendPort/swagger-ui.html")
    }
    if (-not $BackendOnly) {
        $frontendTag = if ($reuseFrontend) { '  （沿用已在运行的实例）' } else { '' }
        Write-Host ("  前端      http://127.0.0.1:$frontendPort/$frontendTag")
    }
    if (-not $BackendOnly -and -not $FrontendOnly) {
        $aiTag = if ($aiReused) { '  （沿用已在运行的实例）' } else { '' }
        Write-Host ("  AI        http://127.0.0.1:$aiPort/health$aiTag")
    }
    Write-Host  "  日志      $logDir"
    Write-Host  "  停止      .\dev.cmd down"
    Write-Host  "  状态      .\dev.cmd status"
    Write-Host '================================================'
    Write-Host ''
    exit 0
}
catch {
    Write-Host ''
    Write-Host "[失败] $($_.Exception.Message)" -ForegroundColor Red

    # 半启动状态必须清理，否则下次启动又要面对"端口被占"
    if ($script:started.Count -gt 0) {
        Write-Host '  正在回收本次已启动的进程…' -ForegroundColor Yellow
        foreach ($procId in ($script:started | Select-Object -Unique)) {
            if (Get-ProcInfo $procId) {
                Stop-Tree $procId
                Write-Host "    已结束 PID $procId"
            }
        }
        if (Test-Path $stateFile) { Remove-Item $stateFile -Force -ErrorAction SilentlyContinue }
    }
    exit 1
}
