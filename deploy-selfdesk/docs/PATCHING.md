# 打补丁机制

## 一句话

`scripts/patch_jar.py` 直接改写 fat jar 里的静态资源条目，不需要重新编译 Java。

## 它具体改哪些文件

| jar 内路径 | 来源 | 动作 |
|---|---|---|
| `BOOT-INF/classes/static/console/index.html` | `console/index.html` | 覆盖 |
| `BOOT-INF/classes/static/console/styles.css` | `console/styles.css` | 覆盖 |
| `BOOT-INF/classes/static/selfdesk-brand.js` | `admin-patch/selfdesk-brand.js` | 覆盖/新增 |
| `BOOT-INF/classes/static/selfdesk-admin-dash.js` | `admin-patch/selfdesk-admin-dash.js` | 覆盖/新增 |
| `BOOT-INF/classes/static/admin/index.html` | 原文件 | 追加 `<script src="/selfdesk-brand.js" defer>`；`theme-color` 改品牌色 |

其余条目原样复制，压缩格式不变。

## 为什么不能热更

Spring Boot 从 jar 里读 classpath 静态资源，进程启动后文件句柄被占用：

- Windows 下重写 jar 会报 `WinError 5 拒绝访问`
- 必须先停应用 → 打补丁 → 再启动

脚本会自动备份为 `*.jar.bak-selfdesk`（只备份一次，避免覆盖最初的干净版本）。

## 升级上游的正确姿势

```powershell
# 1. 停应用
.\scripts\selfdesk.ps1 stop

# 2. 重新构建上游
cd ..\bytedesk
git pull
mvn -DskipTests -pl starter -am package

# 3. 重新打补丁（读的是新 jar）
cd ..\selfdesk-springai
.\scripts\bootstrap.ps1 -SkipBuild

# 4. 启动
.\scripts\selfdesk.ps1 start
```

上游改了 AntD 组件结构时，`admin-patch/selfdesk-brand.js` 里的选择器可能需要补，
但不会影响应用启动——最坏情况只是个别控件回到默认样式。

## 凭据怎么处理

`admin-patch/selfdesk-brand.js` 里有一段自动登录逻辑，读取 `window.__SELFDESK_AUTOLOGIN__`。
这个变量**只在本地存在 `.selfdesk-autologin.json` 时**由打包脚本注入：

```json
{ "username": "admin@email.com", "password": "你的密码" }
```

- 文件已在 `.gitignore` 中，不会被提交
- 仓库里的 `brand.js` 不含任何凭据
- 想恢复手动登录：删掉该文件，重新打补丁

## 幂等性

脚本可重复执行：

- 注入前会检查 `<script src="/selfdesk-brand.js">` 是否已存在
- 备份文件存在时不再覆盖
- 覆盖操作用的是同一份源码，结果稳定

CI 里有一项专门验证这一点（`selfdesk-brand.js` 在 `admin/index.html` 中只出现一次）。
