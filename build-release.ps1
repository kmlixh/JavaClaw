# JavaClaw 部署包打包脚本(Windows / PowerShell)。
#
# 用法:
#   .\build-release.ps1                    # 完整打包 backend + frontend
#   .\build-release.ps1 -SkipBackend       # 只重建 frontend(用于改前端文案后快速产包)
#   .\build-release.ps1 -SkipFrontend      # 只重建 backend(改 Java 后跳过 vite)
#   .\build-release.ps1 -SkipBuild         # 直接打包当前已存在的 build/libs 和 web-console/dist
#
# 产物:
#   dist\javaclaw-deploy-YYYYMMDD-HHmmss\   # 解压目录
#   dist\javaclaw-deploy-YYYYMMDD-HHmmss.zip
#
# 文件名精确到**秒**,避免同一天反复打包互相覆盖。

[CmdletBinding()]
param(
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# 始终从脚本所在目录(项目根)运行,不管用户在哪个目录调
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

function Write-Step($message) {
    Write-Host ""
    Write-Host "==== $message ====" -ForegroundColor Cyan
}

function Require-File($path, $hint) {
    if (-not (Test-Path $path)) {
        Write-Host "[FATAL] 缺文件:$path" -ForegroundColor Red
        Write-Host "       $hint" -ForegroundColor Yellow
        exit 1
    }
}

# ============== Step 1: 后端 bootJar ==============
if ($SkipBackend -or $SkipBuild) {
    Write-Step "[1/4] 跳过后端构建(--SkipBackend / --SkipBuild)"
} else {
    Write-Step "[1/4] 构建后端 fat jar (agent-app:bootJar)"
    & "$ProjectRoot\gradlew.bat" ":agent-app:bootJar" "-x" "test" "-x" "smokeTest"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FATAL] gradle bootJar 失败,exit=$LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# ============== Step 2: 前端 vite build ==============
if ($SkipFrontend -or $SkipBuild) {
    Write-Step "[2/4] 跳过前端构建(--SkipFrontend / --SkipBuild)"
} else {
    Write-Step "[2/4] 构建前端 (web-console / vite build)"
    Push-Location "$ProjectRoot\web-console"
    try {
        if (-not (Test-Path "node_modules")) {
            Write-Host "[INFO] web-console 没有 node_modules,先 npm install" -ForegroundColor Yellow
            & npm install
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
        & npm run build
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[FATAL] npm run build 失败,exit=$LASTEXITCODE" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

# ============== Step 3: 检查产物 ==============
Write-Step "[3/4] 检查产物"
$BackendJar = "$ProjectRoot\agent-app\build\libs\agent-app.jar"
$FrontendDist = "$ProjectRoot\web-console\dist"
$DeployDir = "$ProjectRoot\deploy"

Require-File $BackendJar "运行 .\gradlew.bat :agent-app:bootJar 重新构建"
Require-File $FrontendDist "运行 cd web-console; npm run build 重新构建"
Require-File "$DeployDir\install.sh" "deploy/ 目录缺失;它应该在仓库里"
Require-File "$DeployDir\javaclaw-backend.service" ""
Require-File "$DeployDir\nginx-javaclaw.conf" ""
Require-File "$DeployDir\README.md" ""

$JarSize = [math]::Round((Get-Item $BackendJar).Length / 1MB, 1)
$DistFiles = (Get-ChildItem $FrontendDist -Recurse -File).Count
Write-Host "  backend jar : $JarSize MB"
Write-Host "  frontend    : $DistFiles 个文件"

# ============== Step 4: 打包 ==============
Write-Step "[4/4] 组装 deploy 包"
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Stem = "javaclaw-deploy-$Timestamp"
$DistRoot = "$ProjectRoot\dist"
$Staging = "$DistRoot\$Stem"
$Zip = "$DistRoot\$Stem.zip"

if (-not (Test-Path $DistRoot)) {
    New-Item -ItemType Directory -Path $DistRoot | Out-Null
}
if (Test-Path $Staging) { Remove-Item -Recurse -Force $Staging }
if (Test-Path $Zip) { Remove-Item -Force $Zip }

New-Item -ItemType Directory -Path "$Staging\backend" | Out-Null
New-Item -ItemType Directory -Path "$Staging\web-console" | Out-Null

# 后端 jar 复制到 backend/agent-app.jar(install.sh / systemd 都按这个路径找)
Copy-Item $BackendJar "$Staging\backend\agent-app.jar"

# 前端整个 dist 内容(不含 dist 目录本身)复制到 web-console/
Copy-Item -Recurse "$FrontendDist\*" "$Staging\web-console"

# 部署辅助文件
Copy-Item "$DeployDir\README.md" $Staging
Copy-Item "$DeployDir\install.sh" $Staging
Copy-Item "$DeployDir\javaclaw-backend.service" $Staging
Copy-Item "$DeployDir\nginx-javaclaw.conf" $Staging

Write-Host "  staging dir : $Staging"

# Zip(PowerShell 自带 Compress-Archive,无需额外工具)
Compress-Archive -Path "$Staging\*" -DestinationPath $Zip -CompressionLevel Optimal

$ZipSize = [math]::Round((Get-Item $Zip).Length / 1MB, 1)

Write-Host ""
Write-Host "==== 打包完成 ====" -ForegroundColor Green
Write-Host "  解压目录 : $Staging"
Write-Host "  压缩包   : $Zip ($ZipSize MB)"
Write-Host ""
Write-Host "scp 上目标机后:" -ForegroundColor Yellow
Write-Host "  unzip $Stem.zip -d /data/javaclaw"
Write-Host "  cd /data/javaclaw && bash install.sh"
