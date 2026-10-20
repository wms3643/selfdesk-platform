<#
SelfDesk 一键启停脚本（替代 run.ps1 / start-selfdesk.ps1 / start-selfdesk-ai.ps1 / start-embedding.cmd）

用法：
    .\selfdesk.ps1 start      # 并发拉起 Redis / Elasticsearch / Embedding / App / Dashboard
    .\selfdesk.ps1 status     # 查看五个组件与端口状态
    .\selfdesk.ps1 stop       # 停止 Dashboard / App / Embedding / Redis / ES
    .\selfdesk.ps1 logs       # 跟踪应用日志
    .\selfdesk.ps1 restart

组件与端口：App 9003 · Embedding 8000 · Dashboard 8002 · Redis 16379 · Elasticsearch 19200
仪表盘：http://localhost:8002/  （由 dashboard\server.py 聚合 /api/v1 查询接口）
#>
param(
    [ValidateSet('start', 'stop', 'status', 'logs', 'restart')]
    [string]$Action = 'status',
    [int]$Tail = 40
)

$ErrorActionPreference = 'Stop'
$root      = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)  # 仓库根目录
$workspace = if ($env:SELFDESK_HOME) { $env:SELFDESK_HOME } else { Split-Path -Parent $root }

# ── 路径与参数 ────────────────────────────────────────────────
$runtime      = Join-Path $root 'runtime'
$logDir       = Join-Path $root 'runtime\logs'
$dataDir      = Join-Path $workspace 'selfdesk-data'
$tmpDir       = Join-Path $workspace 'tmp'
$jar          = if ($env:SELFDESK_JAR) { $env:SELFDESK_JAR } else { Join-Path $workspace 'bytedesk/starter/target/bytedesk-starter.jar' }
$java         = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$embedPy      = Join-Path $root 'embedding-venv\Scripts\python.exe'
$appPidFile   = Join-Path $runtime 'app.pid'
$embedPidFile = Join-Path $runtime 'embedding.pid'
$esPidFile    = Join-Path $runtime 'es.pid'
$dashDir      = Join-Path $root 'dashboard'
$dashPidFile  = Join-Path $runtime 'dashboard.pid'
$ProgressPreference = 'SilentlyContinue'

# 读取 .env.local（存在时），不覆盖已设置的环境变量
$envFile = Join-Path $root '.env.local'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $k = $Matches[1]
            $v = $Matches[2].Trim().Trim('"').Trim("'")
            if ($v -and -not [Environment]::GetEnvironmentVariable($k, 'Process')) {
                [Environment]::SetEnvironmentVariable($k, $v, 'Process')
            }
        }
    }
}

$redisPort    = 16379
$esPort       = 19200
$embedPort    = 8000
$appPort      = 9003
$dashPort     = 8002
$redisPass    = 'change-me'

New-Item -ItemType Directory -Force -Path $runtime, $logDir, $dataDir, $tmpDir | Out-Null

# ── 通用工具 ──────────────────────────────────────────────────
function Test-Tcp([int]$port) {
    $ok = $false
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $ar = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        $ok = $ar.AsyncWaitHandle.WaitOne(600, $false) -and $c.Connected
        $c.Close()
    } catch { $ok = $false }
    return $ok
}

function Wait-Http([string]$url, [int]$timeoutSec, [string]$name) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 800
    }
    Write-Host ("  ! " + $name + " 在 " + $timeoutSec + " 秒内未就绪") -ForegroundColor Yellow
    return $false
}

function Get-AlivePid($pidFile) {
    if (-not (Test-Path -LiteralPath $pidFile)) { return $null }
    $p = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$p)) { return $null }
    if (Get-Process -Id $p -ErrorAction SilentlyContinue) { return $p }
    return $null
}

function Invoke-Wsl([string]$user, [string]$cmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return ((wsl.exe -d Ubuntu -u $user -- bash -lc $cmd) 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Test-Http([string]$url, [int]$timeoutSec = 3) {
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $timeoutSec
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500)
    } catch { return $false }
}

# ── 各组件启动 ────────────────────────────────────────────────
function Start-Redis {
    $ping = Invoke-Wsl 'root' ("REDISCLI_AUTH=" + $redisPass + " /opt/selfdesk-redis/bin/redis-cli -p " + $redisPort + " ping")
    if ($ping -match 'PONG') { Write-Host "  = Redis 已在运行" ; return }
    $cmd = "/opt/selfdesk-redis/bin/redis-server --port $redisPort --bind 0.0.0.0 --protected-mode no --requirepass $redisPass --dir /opt/selfdesk-redis/data --maxmemory 128mb --maxmemory-policy allkeys-lru --daemonize yes"
    Invoke-Wsl 'root' $cmd | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        $ping = Invoke-Wsl 'root' ("REDISCLI_AUTH=" + $redisPass + " /opt/selfdesk-redis/bin/redis-cli -p " + $redisPort + " ping")
        if ($ping -match 'PONG') { Write-Host "  + Redis 就绪 (:$redisPort)" ; return }
        Start-Sleep -Milliseconds 500
    }
    throw "Redis 启动失败"
}

function Start-ES {
    if (Test-Http "http://localhost:$esPort") { Write-Host "  = Elasticsearch 已在运行" ; return }
    # 用 Windows 侧托管 wsl.exe，进程生命周期可控；ES 自己写 /opt/selfdesk-es/logs
    $p = Start-Process -FilePath 'wsl.exe' `
        -ArgumentList '-d', 'Ubuntu', '-u', 'selfdesk', '--', '/opt/selfdesk-es/bin/elasticsearch' `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $esPidFile -Value $p.Id -Encoding ASCII
}

function Wait-ES {
    if (Wait-Http "http://localhost:$esPort" 120 'Elasticsearch') { Write-Host "  + Elasticsearch 就绪 (:$esPort)" }
    else { throw "Elasticsearch 启动失败，见 runtime\logs\es.log" }
}

function Start-Embedding {
    if (Test-Http "http://127.0.0.1:$embedPort/health") { Write-Host "  = Embedding 已在运行" ; return }
    $server = Join-Path $root 'scripts\embedding_server.py'
    if (-not (Test-Path -LiteralPath $server)) { throw ('缺少向量服务: ' + $server) }
    $py = $null
    if (Test-Path -LiteralPath $embedPy) { $py = $embedPy }
    else {
        $cmd = Get-Command python -ErrorAction SilentlyContinue
        if ($cmd) { $py = $cmd.Source }
    }
    if (-not $py) { throw '未找到 Python：请安装 Python 3.10+，或把虚拟环境放到 embedding-venv\Scripts\python.exe' }
    $p = Start-Process -FilePath $py `
        -ArgumentList @('-m','uvicorn','embedding_server:app','--host','127.0.0.1','--port',"$embedPort") `
        -WorkingDirectory (Join-Path $root 'scripts') -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $embedPidFile -Value $p.Id -Encoding ASCII
    if (Wait-Http "http://127.0.0.1:$embedPort/health" 120 'Embedding') { Write-Host "  + Embedding 就绪 (:$embedPort)" }
    else { throw "Embedding 启动失败：先执行 pip install fastembed，再看 scripts 目录下的 uvicorn 输出" }
}

function Start-App {
    $alive = Get-AlivePid $appPidFile
    if ($alive) { Write-Host ("  = App 已在运行 (PID " + $alive + ")") ; return }
    if (-not (Test-Path -LiteralPath $jar)) { throw ("找不到 jar: " + $jar) }
    if (-not (Test-Path -LiteralPath $java)) { throw ("找不到 java: " + $java) }

    $key = $env:DEEPSEEK_API_KEY
    if (-not $key) {
        $keyFile = Join-Path $root '.deepseek-key'
        if (Test-Path -LiteralPath $keyFile) { $key = (Get-Content -LiteralPath $keyFile -Raw).Trim() }
    }
    if (-not $key) { throw '缺少 DeepSeek API Key：设置环境变量 DEEPSEEK_API_KEY，或在仓库根放 .deepseek-key' }
    if (-not $key) { throw '.deepseek-key 为空' }

    $wslIp = (wsl.exe -d Ubuntu -u root -- hostname -I).Trim().Split()[0]

    $env:SPRING_PROFILES_ACTIVE = 'open'
    $env:SPRING_AI_MODEL_CHAT = 'deepseek'
    $env:SPRING_AI_MODEL_EMBEDDING = 'openai'
    $env:SPRING_AI_MODEL_VISION = 'none'
    $env:SPRING_AI_MODEL_AUDIO = 'none'
    $env:SPRING_AI_MODEL_RERANK = 'none'
    $env:SPRING_AI_MODEL_MULTIMODAL = 'none'
    $env:SPRING_AI_MODEL_IMAGE = 'none'
    $env:SPRING_AI_DEEPSEEK_CHAT_ENABLED = 'true'
    $env:SPRING_AI_DEEPSEEK_API_KEY = $key
    $env:SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL = 'deepseek-flash'

    # 可选：AI 请求经 Python 编排层转发（存在 .ai-gateway 时生效；删除该文件即回退直连）
    $gwFile = Join-Path $root '.ai-gateway'
    if (Test-Path -LiteralPath $gwFile) {
        $gw = (Get-Content -LiteralPath $gwFile -Raw).Trim()
        if ($gw) {
            $env:SPRING_AI_DEEPSEEK_BASE_URL = $gw
            Write-Host ("  · AI 网关: " + $gw)
        }
    } else {
        Remove-Item Env:SPRING_AI_DEEPSEEK_BASE_URL -ErrorAction SilentlyContinue
    }
    $env:SPRING_AI_OLLAMA_CHAT_ENABLED = 'false'
    $env:SPRING_AI_OLLAMA_EMBEDDING_ENABLED = 'false'
    $env:SPRING_AI_ZHIPUAI_CHAT_ENABLED = 'false'
    $env:SPRING_AI_ZHIPUAI_EMBEDDING_ENABLED = 'false'
    $env:SPRING_AI_OPENAI_EMBEDDING_ENABLED = 'true'
    $env:SPRING_AI_OPENAI_API_KEY = 'local-selfdesk'
    $env:SPRING_AI_OPENAI_BASE_URL = ('http://127.0.0.1:' + $embedPort)
    $env:SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL = 'BAAI/bge-small-zh-v1.5'
    $env:SPRING_AI_OPENAI_EMBEDDING_OPTIONS_DIMENSIONS = '512'
    $env:SPRING_AI_VECTORSTORE_TYPE = 'elasticsearch'
    $env:SPRING_AI_VECTORSTORE_ELASTICSEARCH_ENABLED = 'true'
    $env:SPRING_AI_VECTORSTORE_ELASTICSEARCH_INITIALIZE_SCHEMA = 'true'
    $env:SPRING_AI_VECTORSTORE_ELASTICSEARCH_INDEX_NAME = 'bytedesk_vs_index'
    $env:SPRING_AI_VECTORSTORE_ELASTICSEARCH_DIMENSIONS = '512'
    $env:SPRING_ELASTICSEARCH_URIS = ('http://' + $wslIp + ':' + $esPort)
    $env:SPRING_ELASTICSEARCH_USERNAME = ''
    $env:SPRING_ELASTICSEARCH_PASSWORD = ''
    $env:BYTEDESK_BUSINESS_TOOL_ALLOW_HTTP = 'true'
    $env:BYTEDESK_BUSINESS_TOOL_ALLOW_PRIVATE_HOSTS = 'true'
    $env:BYTEDESK_BUSINESS_TOOL_ALLOWED_HOSTS = '127.0.0.1,localhost'
    $env:BYTEDESK_DEMO_ORDER_TOKEN = 'selfdesk-local-demo-tool-2026'
    $env:TEMP = $tmpDir
    $env:TMP = $tmpDir

    $appLog = Join-Path $logDir 'app.log'
    Set-Content -LiteralPath (Join-Path $runtime 'app.current.log') -Value $appLog -Encoding ASCII

    $arguments = @(
        '-Djava.io.tmpdir=' + $tmpDir,
        '-Xms256m', '-Xmx1280m',
        '-XX:MaxMetaspaceSize=320m',
        '-XX:+UseSerialGC',
        '-jar', $jar,
        '--spring.profiles.active=open',
        '--bytedesk.datasource.active=h2',
        '--bytedesk.demo-order-api.enabled=true',
        '--spring.datasource.url=jdbc:h2:file:./h2db/selfdesk',
        '--server.port=' + $appPort,
        '--bytedesk.testing.enabled=true',
        '--bytedesk.testing.disable-captcha=true',
        '--logging.level.root=INFO',
        '--logging.level.com.bytedesk=INFO',
        ('--logging.file.name=' + (Join-Path $logDir 'app.log'))
    )
    # 日志走 Spring 的 logging.file.name，不用 -Redirect*（PS 5.1 在 Path/PATH 并存时会崩）
    $p = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $dataDir `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $appPidFile -Value $p.Id -Encoding ASCII
    Write-Host ("  · App 启动中 (PID " + $p.Id + ")，等待端口 " + $appPort + " ...")

    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        if (Test-Tcp $appPort) { Write-Host ("  + App 就绪 (:$appPort)") ; return }
        if (-not (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { throw ("App 进程已退出，见 " + (Join-Path $logDir 'app.log')) }
        Start-Sleep -Milliseconds 1000
    }
    throw ("App 未在 180 秒内监听 " + $appPort + "，见 " + (Join-Path $logDir 'app.log'))
}

function Stop-App {
    $alive = Get-AlivePid $appPidFile
    if ($alive) { Stop-Process -Id $alive -Force -ErrorAction SilentlyContinue; Write-Host ("  - App 已停止 (PID " + $alive + ")") }
    else { Write-Host "  = App 未运行" }
    Remove-Item -LiteralPath $appPidFile -Force -ErrorAction SilentlyContinue
}

function Stop-Embedding {
    $alive = Get-AlivePid $embedPidFile
    if ($alive) { Stop-Process -Id $alive -Force -ErrorAction SilentlyContinue; Write-Host ("  - Embedding 已停止 (PID " + $alive + ")") }
    else { Write-Host "  = Embedding 未运行" }
    Remove-Item -LiteralPath $embedPidFile -Force -ErrorAction SilentlyContinue
}

function Start-Dashboard {
    if (Test-Tcp $dashPort) { Write-Host "  = Dashboard 已在运行" ; return }
    $launcher = Join-Path $dashDir 'server.py'
    if (-not (Test-Path -LiteralPath $launcher)) { Write-Host "  ! 缺少 dashboard\server.py，跳过" -ForegroundColor Yellow; return }
    $p = Start-Process -FilePath $embedPy -ArgumentList $launcher -WorkingDirectory $dashDir -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $dashPidFile -Value $p.Id -Encoding ASCII
    if (Wait-Http "http://127.0.0.1:$dashPort/" 30 'Dashboard') { Write-Host "  + Dashboard 就绪 (:$dashPort)" }
}

function Stop-Dashboard {
    $alive = Get-AlivePid $dashPidFile
    if ($alive) { Stop-Process -Id $alive -Force -ErrorAction SilentlyContinue; Write-Host ("  - Dashboard 已停止 (PID " + $alive + ")") }
    else { Write-Host "  = Dashboard 未运行" }
    Remove-Item -LiteralPath $dashPidFile -Force -ErrorAction SilentlyContinue
}

function Show-Status {
    Write-Host "`nSelfDesk 组件状态" -ForegroundColor Cyan

    $appPid = Get-AlivePid $appPidFile
    $appState = if ($appPid) { "running (PID $appPid)" } else { "stopped" }
    if (Test-Tcp $appPort) { $appState += "  端口:$appPort 开" }
    Write-Host ("  App         : " + $appState)

    $embedPid = Get-AlivePid $embedPidFile
    $embedState = if ($embedPid) { "running (PID $embedPid)" } else { "stopped" }
    if (Test-Tcp $embedPort) { $embedState += "  端口:$embedPort 开" }
    Write-Host ("  Embedding   : " + $embedState)

    $ping = (Invoke-Wsl 'root' ("REDISCLI_AUTH=" + $redisPass + " /opt/selfdesk-redis/bin/redis-cli -p " + $redisPort + " ping")).Trim()
    $redisState = if ($ping -match 'PONG') { "ok (:$redisPort)" } else { "down" }
    Write-Host ("  Redis       : " + $redisState)

    $es = 'down'
    try { if ((Invoke-WebRequest "http://localhost:$esPort" -UseBasicParsing -TimeoutSec 4).StatusCode -eq 200) { $es = "ok (:$esPort)" } } catch { }
    Write-Host ("  Elasticsearch: " + $es)

    $dashPid = Get-AlivePid $dashPidFile
    $dashState = if ($dashPid) { "running (PID $dashPid)" } else { "stopped" }
    if (Test-Tcp $dashPort) { $dashState += "  端口:$dashPort 开" }
    Write-Host ("  Dashboard   : " + $dashState)

    Write-Host ""
}

# ── 主流程 ────────────────────────────────────────────────────
switch ($Action) {
    'start' {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-Host "启动 SelfDesk..." -ForegroundColor Cyan
        # 顺序有讲究：ES 点火后立即可返回，趁它冷启动的 60~90 秒并行把 Redis 和 Embedding 拉起来
        Start-ES
        Start-Redis | Out-Null
        Start-Embedding | Out-Null
        Wait-ES | Out-Null
        Start-App | Out-Null
        Start-Dashboard | Out-Null
        $sw.Stop()
        Write-Host ("`n全部就绪，耗时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + " 秒") -ForegroundColor Green
        Write-Host ("  应用: http://localhost:" + $appPort)
        Write-Host ("  后台: http://localhost:" + $appPort + "/admin")
        Write-Host ("  仪表盘: http://localhost:" + $dashPort + "/")
    }
    'stop' {
        Write-Host "停止 SelfDesk..." -ForegroundColor Cyan
        Stop-Dashboard
        Stop-App
        Stop-Embedding
        $esPid = Get-AlivePid $esPidFile
        if ($esPid) { Stop-Process -Id $esPid -Force -ErrorAction SilentlyContinue; Write-Host ("  - Elasticsearch 已停止 (PID " + $esPid + ")") }
        Remove-Item -LiteralPath $esPidFile -Force -ErrorAction SilentlyContinue
        Invoke-Wsl 'root' "/opt/selfdesk-redis/bin/redis-cli -p $redisPort -a $redisPass shutdown nosave 2>/dev/null; pkill -f 'selfdesk-es/bin/elasticsearch' 2>/dev/null; true" | Out-Null
        Write-Host "  - Redis / Elasticsearch 已停止"
    }
    'status' { Show-Status }
    'logs' {
        $cur = Join-Path $runtime 'app.current.log'
        if (Test-Path -LiteralPath $cur) {
            $f = (Get-Content -LiteralPath $cur -Raw).Trim()
            if (Test-Path -LiteralPath $f) { Get-Content -LiteralPath $f -Tail $Tail -Wait } else { Write-Host "找不到日志文件: $f" }
        } else { Write-Host "尚未启动过应用" }
    }
    'restart' {
        & $MyInvocation.MyCommand.Path stop
        Start-Sleep -Seconds 2
        & $MyInvocation.MyCommand.Path start
    }
}
