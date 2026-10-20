<#
AI 网关开关

让 Java 业务侧的 AI 请求改为经过 Python 编排层（:8003），业务代码一行不改。
原理：Spring AI 的 deepseek 客户端支持 base-url，把上游指向本地兼容端点即可。

用法：
    .\ai-gateway.ps1 on              开启（会先探活，不通则拒绝）
    .\ai-gateway.ps1 on -Restart     开启并重启应用
    .\ai-gateway.ps1 off             回退直连 DeepSeek
    .\ai-gateway.ps1 off -Restart    回退并重启
    .\ai-gateway.ps1 status          查看当前状态

回退方式：删除本目录下的 .ai-gateway 文件，重启应用即可。
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('on', 'off', 'status')]
    [string]$Action = 'status',

    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$marker = Join-Path $root '.ai-gateway'
$gateway = 'http://127.0.0.1:8003'
$appUrl = 'http://127.0.0.1:9003'

function Test-Gateway {
    try {
        $r = Invoke-WebRequest "$gateway/api/health" -UseBasicParsing -TimeoutSec 6
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Restart-App {
    Write-Host "  重启应用以生效（约 50 秒）..." -ForegroundColor Yellow
    & (Join-Path $root 'selfdesk.ps1') restart
}

switch ($Action) {

    'on' {
        if (-not (Test-Gateway)) {
            Write-Host "  ✗ Python 编排层未就绪（$gateway）" -ForegroundColor Red
            Write-Host "    先启动 Python 编排层：cd <selfdesk-langgraph>; .\start.ps1"
            exit 1
        }
        Set-Content -LiteralPath $marker -Value $gateway -Encoding ASCII
        Write-Host "  ✓ 已开启：AI 请求将经 $gateway 转发" -ForegroundColor Green
        Write-Host "    当前 Java 仍走直连，需重启生效"
        if ($Restart) { Restart-App }
    }

    'off' {
        if (Test-Path -LiteralPath $marker) {
            Remove-Item -LiteralPath $marker -Force
            Write-Host "  ✓ 已回退：删除 .ai-gateway，AI 恢复直连 DeepSeek" -ForegroundColor Green
            Write-Host "    需重启生效"
            if ($Restart) { Restart-App }
        } else {
            Write-Host "  = 当前就是直连模式（无 .ai-gateway）"
        }
    }

    'status' {
        $mode = '直连 DeepSeek'
        $target = 'https://api.deepseek.com'
        if (Test-Path -LiteralPath $marker) {
            $mode = '经 Python 编排层'
            $target = (Get-Content -LiteralPath $marker -Raw).Trim()
        }
        Write-Host ""
        Write-Host "  AI 链路模式 : $mode" -ForegroundColor Cyan
        Write-Host "  上游地址    : $target"

        $gwOk = Test-Gateway
        Write-Host ("  编排层 8003 : " + $(if ($gwOk) { 'ok' } else { 'down' }))
        try {
            $null = Invoke-WebRequest $appUrl -UseBasicParsing -TimeoutSec 5
            Write-Host "  业务层 9003 : ok"
        } catch {
            Write-Host "  业务层 9003 : down"
        }
        if (-not $gwOk) {
            Write-Host "`n  提示：编排层不可用时不要开启网关，否则 AI 会整体失效" -ForegroundColor Yellow
        }
        Write-Host ""
    }
}
