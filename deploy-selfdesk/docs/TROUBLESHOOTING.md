# 故障排查

按现象查，不用通读。

## 打补丁失败

**`PermissionError: [WinError 5] 拒绝访问`**

jar 被运行中的 JVM 占用。先停应用再打：

```powershell
.\scripts\selfdesk.ps1 stop
python scripts\patch_jar.py
```

**`找不到 jar：...`**

用环境变量指定路径，或先跑 `bootstrap.ps1` 构建上游：

```powershell
$env:SELFDESK_JAR = "D:\src\bytedesk\starter\target\bytedesk-starter.jar"
python scripts\patch_jar.py
```

## 启动失败

**`App 未在 180 秒内监听 9003`**

看日志：`runtime\logs\app.log`。常见原因：

| 日志关键字 | 原因 | 处理 |
|---|---|---|
| `Port 9003 was already in use` | 端口被占 | `netstat -ano \| findstr :9003`，杀掉旧进程 |
| `Connection refused` + elasticsearch | ES 未就绪 | `docker compose ps`，等 healthcheck 通过 |
| `OutOfMemoryError` | 堆太小 | 调大 `-Xmx`（`selfdesk.ps1` 里默认 1280m） |
| `H2 ... locked` | 上一个进程没退干净 | 确认没有残留 `java.exe` |

**`Elasticsearch 启动失败`**

ES 需要 `vm.max_map_count >= 262144`（Linux/WSL）。Docker 方式先确认容器健康：

```bash
docker compose logs -f elasticsearch
```

## 页面异常

**`/admin` 打开是登录页，之前不是免密吗**

免密需要两个条件：本地有 `.selfdesk-autologin.json`，且打补丁时注入了。检查：

```powershell
Select-String -Path <jar> -Pattern "SELFDESK_AUTOLOGIN"   # 或解压查看 brand.js
```

**后台首页空白**

首页是「许可检测」占位页，`selfdesk-admin-dash.js` 会把它替换成仪表盘。
如果 iframe 没出现，通常是判断条件没命中（页面结构变了），或 8002 没起：

```powershell
curl http://127.0.0.1:8002/
```

**样式没生效 / 只变了一半**

强刷（`Ctrl+Shift+R`）。仍是旧样式说明 jar 里还是旧文件——重新打补丁并重启。

**成员/角色页一片空白**

这是早期 bug 的典型表现：脚本误把内容容器设了 `display:none`。
当前版本已白名单保护结构性容器；如果复现，检查 `selfdesk-brand.js` 的 `hideUpgradeEntry`。

## 仪表盘

**`/dashboard.html` 跳到了 8002 打不开**

`dashboard.html` 只是兼容跳转页，真正服务在 8002。
启动它：`.\scripts\selfdesk.ps1 start` 会拉起；单独调试可直接 `python dashboard\server.py`。

**数字不动**

仪表盘 30 秒刷新一次，且页面在后台标签页时会暂停。切回前台或点「刷新」。

**AI 费用为 0**

费用来自日志里的 `Published AI token usage event`。
如果 AI 一次都没调用，或日志被清空，就会是 0。日志路径见 `runtime\logs\app.log`。

## 代理与网络

**`Failed to connect to github.com:443 over proxy`**

本机 git 配了代理但代理没开：

```powershell
git config --global --get http.proxy
# 临时绕过
git -c http.proxy= -c https.proxy= push
```
