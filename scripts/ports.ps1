# =============================================================================
# ports.ps1 —— 端口与服务地址的唯一事实来源
#
# 为什么单独抽出来：
#   改端口最容易漏改的从来不是主配置文件，而是散落在各个 .ps1 里的数字。
#   以前后端端口同时写死在 dev-up / dev-status / start-local / stop-local 里，
#   前端端口又写死在 vue/vite.config.js 的兜底值里，改一处忘一处就会得到
#   「脚本说已启动、浏览器打不开 / 代理打空」的静默错位 —— 比直接报错难查得多。
#
#   现在所有脚本都从这里取值，来源只有三层，优先级从高到低：
#     1) 环境变量（临时覆盖用，例如 $env:AI_PORT=8010）
#     2) 项目配置文件（application.yaml / vue/.env / langchain_service/.env）
#     3) 内置默认值（与上面这些文件的出厂值保持一致）
#
# 用法（在脚本里 dot-source，不要 dot-source 到全局 profile）：
#   . (Join-Path $PSScriptRoot 'ports.ps1')
#   $ports = Get-SssPorts
#   "后端 $($ports.Backend) 前端 $($ports.Frontend) AI $($ports.Ai)"
#
# 注意：本文件不定义 $root（避免 dot-source 时覆盖调用方的 $root），
#       统一使用 $PortsRoot。
# =============================================================================

$PortsRoot = Split-Path -Parent $PSScriptRoot

# 从 KEY=VALUE 形式的 .env 里取一个值；没有该键或值为空则返回 $null。
function Get-SssEnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string]$Key
    )
    if (-not (Test-Path -LiteralPath $File)) { return $null }
    $hit = Select-String -Path $File -Pattern ('^\s*' + [regex]::Escape($Key) + '\s*=\s*(.+?)\s*$') -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $hit) { return $null }
    $v = $hit.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
    if ([string]::IsNullOrWhiteSpace($v)) { return $null }
    return $v
}

# 后端端口：application.yaml 的 `port: ${SERVER_PORT:8084}`
# dev-up.ps1 启动时会显式传 --server.port=，以 yaml 里的默认值为准，
# 所以这里也读 yaml，保证「启动的端口」与「停止的端口」是同一个数。
function Get-SssBackendPort {
    $fallback = 8084
    $yaml = Join-Path $PortsRoot 'backend\src\main\resources\application.yaml'
    if (Test-Path -LiteralPath $yaml) {
        $m = Select-String -Path $yaml -Pattern '^\s*port:\s*\$\{SERVER_PORT:(\d+)\}' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return $fallback
}

# 前端端口：vue/.env 的 FRONTEND_PORT（必须与后端 CORS 白名单一致）
function Get-SssFrontendPort {
    $fallback = 3002
    $envFile = Join-Path $PortsRoot 'vue\.env'
    $v = Get-SssEnvValue -File $envFile -Key 'FRONTEND_PORT'
    if ($v) {
        $n = 0
        if ([int]::TryParse($v, [ref]$n) -and $n -gt 0) { return $n }
    }
    return $fallback
}

# AI 服务端口：环境变量 AI_PORT > langchain_service/.env 的 AI_PORT > 8000
function Get-SssAiPort {
    $fallback = 8000
    if ($env:AI_PORT) {
        $n = 0
        if ([int]::TryParse([string]$env:AI_PORT, [ref]$n) -and $n -gt 0) { return $n }
    }
    $v = Get-SssEnvValue -File (Join-Path $PortsRoot 'langchain_service\.env') -Key 'AI_PORT'
    if ($v) {
        $n = 0
        if ([int]::TryParse($v, [ref]$n) -and $n -gt 0) { return $n }
    }
    return $fallback
}

# 一次性取全，顺带拼好访问地址，避免调用方各写一份 "http://127.0.0.1:xxxx"。
function Get-SssPorts {
    $backend = Get-SssBackendPort
    $frontend = Get-SssFrontendPort
    $ai = Get-SssAiPort
    return [pscustomobject]@{
        Backend     = $backend
        Frontend    = $frontend
        Ai          = $ai
        BackendUrl  = "http://127.0.0.1:$backend"
        FrontendUrl = "http://127.0.0.1:$frontend"
        AiUrl       = "http://127.0.0.1:$ai"
        HealthUrl   = "http://127.0.0.1:$backend/api/v1/health"
        AiHealthUrl = "http://127.0.0.1:$ai/health"
    }
}
