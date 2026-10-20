# 从上游源码到可运行 jar
#
# 做三件事：
#   1. 找到（或浅克隆）上游 bytedesk 源码并构建 fat jar
#   2. 用本仓库的定制层打补丁
#   3. 打印后续启动步骤
#
# 参数：
#   -Upstream <path>   上游源码目录，默认与仓库同级的 ..\bytedesk
#   -SkipBuild         跳过 Maven 构建，直接对已有 jar 打补丁
#
# 示例：
#   .\scripts\bootstrap.ps1
#   .\scripts\bootstrap.ps1 -Upstream D:\src\bytedesk
#   .\scripts\bootstrap.ps1 -SkipBuild

param(
    [string]$Upstream = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Upstream) {
    $Upstream = Join-Path (Split-Path -Parent $root) "bytedesk"
}
$jar = Join-Path $Upstream "starter\target\bytedesk-starter.jar"

Write-Host ""
Write-Host "SelfDesk 定制层 bootstrap" -ForegroundColor Cyan
Write-Host ("  仓库    : " + $root)
Write-Host ("  上游    : " + $Upstream)
Write-Host ("  目标 jar: " + $jar)
Write-Host ""

# 1) 构建
if ($SkipBuild) {
    Write-Host "  · 跳过构建（-SkipBuild）"
} elseif (Test-Path $jar) {
    Write-Host "  = 已有 jar，跳过构建（需要重建时加 -SkipBuild 以外的参数或手动 mvn）"
} else {
    if (-not (Test-Path $Upstream)) {
        Write-Host "  · 未找到上游源码，浅克隆 bytedesk（AGPL-3.0）..." -ForegroundColor Yellow
        git clone --depth 1 https://github.com/Bytedesk/bytedesk.git $Upstream
    }
    Write-Host "  · 构建上游（mvn -DskipTests -pl starter -am package）..." -ForegroundColor Yellow
    Push-Location $Upstream
    try {
        mvn -DskipTests -pl starter -am package
        if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $jar)) {
    throw "找不到 jar：$jar"
}

# 2) 打补丁
Write-Host ""
Write-Host "  · 注入定制层..." -ForegroundColor Yellow
$env:SELFDESK_JAR = $jar
python (Join-Path $root "scripts\patch_jar.py")
if ($LASTEXITCODE -ne 0) { throw "patch_jar.py 执行失败" }

# 3) 下一步
Write-Host ""
Write-Host "完成。接下来：" -ForegroundColor Green
Write-Host "  1) 复制 .env.example 为 .env.local 并填写 BYTEDESK_ADMIN_PASSWORD / DEEPSEEK_API_KEY"
Write-Host "  2) 启动基础设施：docker compose up -d"
Write-Host "  3) 启动服务：.\scripts\selfdesk.ps1 start"
Write-Host "  4) 打开 http://127.0.0.1:9003/admin 与 http://127.0.0.1:8002/"
Write-Host ""
Write-Host "  提示：如果 ES 跑在 Docker 里，selfdesk.ps1 里的 WSL 分支不会再启动一份；" -ForegroundColor DarkGray
Write-Host "        端口 19200 已占用时脚本会直接复用。" -ForegroundColor DarkGray
