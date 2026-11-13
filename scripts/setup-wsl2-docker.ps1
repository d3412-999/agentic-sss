# =============================================================================
# setup-wsl2-docker.ps1 — 一次装好 WSL2，让 Docker Desktop 可用（幂等，可重复执行）
#
# 用法（必须管理员）：
#   右键开始菜单 -> 终端(管理员)，然后：
#     cd D:\zzb\code\a\javacode\1\agentic-sss
#     Set-ExecutionPolicy -Scope Process Bypass -Force
#     .\scripts\setup-wsl2-docker.ps1
#
# 参数（一般不用改）：
#   -Msi         WSL 安装包，默认 D:\zzb\tools\wsl\wsl-x64.msi
#   -Rootfs      Ubuntu rootfs，默认 D:\zzb\tools\wsl\ubuntu.tar.gz
#   -Distro      发行版名称，默认 Ubuntu-22.04
#   -InstallDir  发行版落盘目录，默认 D:\Wsl\Ubuntu-22.04
#   -SkipDistro  只装 WSL，不导入 Ubuntu
#   -SkipDocker  只装 WSL，不启动 Docker Desktop
# =============================================================================
param(
    [string]$Msi = 'D:\zzb\tools\wsl\wsl-x64.msi',
    [string]$Rootfs = 'D:\zzb\tools\wsl\ubuntu.tar.gz',
    [string]$Distro = 'Ubuntu-22.04',
    [string]$InstallDir = 'D:\Wsl\Ubuntu-22.04',
    [switch]$SkipDistro,
    [switch]$SkipDocker
)

$ErrorActionPreference = 'Continue'
$rebootNeeded = $false

function Write-Step {
    param([string]$Text)
    Write-Host ""
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}
function Write-Ok {
    param([string]$Text)
    Write-Host "  [OK] $Text" -ForegroundColor Green
}
function Write-Warn {
    param([string]$Text)
    Write-Host "  [!!] $Text" -ForegroundColor Yellow
}
function Write-Bad {
    param([string]$Text)
    Write-Host "  [XX] $Text" -ForegroundColor Red
}

# ------------------------------------------------------------------ 0. 管理员检查
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($isAdmin -eq $false) {
    Write-Bad '需要管理员权限'
    Write-Host '请右键开始菜单 -> 终端(管理员)，然后执行：'
    Write-Host '  cd D:\zzb\code\a\javacode\1\agentic-sss'
    Write-Host '  Set-ExecutionPolicy -Scope Process Bypass -Force'
    Write-Host '  .\scripts\setup-wsl2-docker.ps1'
    exit 1
}

$os = Get-CimInstance Win32_OperatingSystem
Write-Host "系统：$($os.Caption) build $($os.BuildNumber)" -ForegroundColor DarkGray

# --------------------------------------------------- 1. 启用 WSL 与虚拟机平台功能
Write-Step '1/6 启用 WSL 与虚拟机平台'
$features = @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')
foreach ($f in $features) {
    $state = (Get-WindowsOptionalFeature -Online -FeatureName $f).State
    if ($state -eq 'Enabled') {
        Write-Ok "$f 已启用"
    }
    else {
        Write-Host "  ... 启用 $f（当前 $state）"
        $r = Enable-WindowsOptionalFeature -Online -FeatureName $f -All -NoRestart -ErrorAction Continue
        if ($r.RestartNeeded -eq $true) {
            $rebootNeeded = $true
        }
        Write-Ok "$f 已开启（需重启后生效）"
    }
}

# ------------------------------------------------------- 2. 安装 WSL 本体（msi）
Write-Step '2/6 安装 WSL 本体'
$wslReady = $false
$verOut = & wsl.exe --version 2>$null
if ($LASTEXITCODE -eq 0) {
    $wslReady = $true
    Write-Ok ('WSL 已安装：' + ($verOut | Select-Object -First 1))
}
if ($wslReady -eq $false) {
    $msiOk = $false
    if (Test-Path $Msi) {
        $msiSize = (Get-Item $Msi).Length
        if ($msiSize -gt 10MB) {
            $msiOk = $true
            Write-Host "  ... msiexec 安装 $Msi（$([math]::Round($msiSize/1MB,1)) MB）"
            $proc = Start-Process msiexec.exe -ArgumentList "/i `"$Msi`" /qn /norestart" -Wait -PassThru
            $code = $proc.ExitCode
            if ($code -eq 0) {
                Write-Ok 'WSL 安装完成'
            }
            elseif ($code -eq 3010) {
                Write-Ok 'WSL 安装完成（需要重启）'
                $rebootNeeded = $true
            }
            else {
                Write-Warn "msiexec 退出码 $code，失败可手动双击安装该 msi"
            }
        }
        else {
            Write-Warn "安装包不完整（$([math]::Round($msiSize/1MB,1)) MB），可能还在下载"
        }
    }
    if ($msiOk -eq $false) {
        Write-Warn "没有可用的 $Msi，改走系统在线安装：wsl --install --no-distribution"
        & wsl.exe --install --no-distribution 2>&1 | Out-String | Write-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Warn '在线安装也失败，请手动下载后重跑本脚本：'
            Write-Host '   https://github.com/microsoft/WSL/releases/latest' -ForegroundColor DarkGray
            Write-Host '   https://gh-proxy.com/https://github.com/microsoft/WSL/releases/download/3.0.5/wsl.3.0.5.0.x64.msi （367MB，实测约 1.6MB/s）' -ForegroundColor DarkGray
        }
    }
}

# --------------------------------------------------------- 3. 默认版本设为 WSL2
Write-Step '3/6 设置默认版本为 2'
& wsl.exe --set-default-version 2 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -eq 0) {
    Write-Ok 'default version = 2'
}
else {
    Write-Warn '设置失败（可能需要先重启）'
}
& wsl.exe --status 2>&1 | Out-String | Write-Host

# ---------------------------------------------------------- 4. 导入 Ubuntu 发行版
Write-Step '4/6 导入 Ubuntu（可选，Docker 只需要 WSL2 引擎）'
if ($SkipDistro) {
    Write-Warn '已跳过（-SkipDistro）'
}
else {
    $listOut = & wsl.exe -l -q 2>$null
    $distroList = ($listOut -join "`n") -replace "`0", ''
    if ($distroList -match [regex]::Escape($Distro)) {
        Write-Ok "$Distro 已存在，跳过"
    }
    elseif (Test-Path $Rootfs) {
        New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
        Write-Host "  ... 导入到 $InstallDir（约 1 分钟）"
        & wsl.exe --import $Distro $InstallDir $Rootfs --version 2 2>&1 | Out-String | Write-Host
        if ($LASTEXITCODE -eq 0) {
            Write-Ok "$Distro 导入完成"
        }
        else {
            Write-Warn '导入失败，重启后可重跑本脚本'
        }
    }
    else {
        Write-Warn "找不到 rootfs：$Rootfs（不影响 Docker）"
        Write-Host '  下载：https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/wsl/jammy/current/ubuntu-jammy-wsl-amd64-ubuntu22.04lts.rootfs.tar.gz' -ForegroundColor DarkGray
    }
}

# ------------------------------------------- 5. 启动 Docker Desktop 并等待引擎就绪
Write-Step '5/6 启动 Docker Desktop'
if ($SkipDocker) {
    Write-Warn '已跳过（-SkipDocker）'
}
else {
    $engineOk = $false
    $null = docker version --format '{{.Server.Version}}' 2>$null
    if ($LASTEXITCODE -eq 0) {
        $engineOk = $true
    }
    if ($engineOk -eq $false) {
        docker desktop start 2>&1 | Out-String | Write-Host
        $exeList = @(
            "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe",
            "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
        )
        $exe = $null
        foreach ($candidate in $exeList) {
            if (Test-Path $candidate) { $exe = $candidate; break }
        }
        if ($exe -ne $null) {
            Start-Process -FilePath $exe | Out-Null
            Write-Ok "已启动 $exe"
        }
        else {
            Write-Warn '没找到 Docker Desktop.exe，请从开始菜单手动打开'
        }
        Write-Host '  ... 等待 Docker 引擎就绪（最多 5 分钟）'
        for ($i = 1; $i -le 60; $i++) {
            Start-Sleep -Seconds 5
            $null = docker version --format '{{.Server.Version}}' 2>$null
            if ($LASTEXITCODE -eq 0) {
                $engineOk = $true
                break
            }
        }
    }
    if ($engineOk -eq $true) {
        $serverVer = docker version --format '{{.Server.Version}}' 2>$null
        Write-Ok "Docker 引擎可用：server=$serverVer"
    }
    else {
        Write-Warn 'Docker 引擎仍未就绪'
        if ($rebootNeeded -eq $true) {
            Write-Warn '系统尚未重启，请重启后再运行本脚本'
        }
        else {
            Write-Warn '请打开 Docker Desktop 查看提示（Settings -> General 确认使用 WSL2 引擎）'
        }
    }
}

# ---------------------------------------------------------------- 6. 自检与收尾
Write-Step '6/6 自检'
& wsl.exe --status 2>&1 | Out-String | Write-Host
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}' 2>&1 | Out-String | Write-Host

$daemonJson = "$env:USERPROFILE\.docker\daemon.json"
if (Test-Path $daemonJson) {
    Write-Host '当前镜像加速配置（daemon.json）：' -ForegroundColor DarkGray
    Get-Content $daemonJson | Out-String | Write-Host
}

Write-Host ''
if ($rebootNeeded -eq $true) {
    Write-Host '下一步：重启电脑，然后再次运行本脚本（会续做后续步骤）' -ForegroundColor Yellow
}
else {
    Write-Host '下一步（Docker 就绪后）：' -ForegroundColor Green
    Write-Host '  docker compose -f docker-compose.full.yml up -d --build'
    Write-Host '  浏览器打开 http://127.0.0.1:8080'
}
