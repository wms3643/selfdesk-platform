# SelfDesk 客服工作台

## 入口

```text
http://127.0.0.1:9003/console/
```

旧的 `/agent`、`/agent/chat` 入口会重定向到 `/console/`。新工作台是独立静态客户端，不加载上游客服端的许可证提示或社区版降级通知。后端原有的许可证规则没有被修改或绕过。

访客端仍使用：

```text
http://127.0.0.1:9003/chat/?org=df_org_uid&t=1&sid=df_wg_uid&lang=zh-cn
```

## 当前能力

- 使用账号密码登录。
- 复用同一浏览器里已登录客服端的 JWT，或手动粘贴访问令牌。
- 显示当前客服资料和组织。
- 读取当前组织的会话列表。
- 按访客、消息内容或会话 UID 搜索。
- 在进行中和全部会话之间切换。
- 读取文本、图片、文件和系统消息；文本消息按普通文本安全渲染。
- 通过 REST API 回复访客。
- 每 5 秒刷新会话和当前消息，页面隐藏时停止刷新。
- 跳转运营数据看板。

## 使用到的后端接口

```text
GET  /api/v1/user/profile
GET  /api/v1/agent/query/user/uid
GET  /api/v1/thread/query/org
GET  /api/v1/message/thread/uid
POST /api/v1/thread/message/meta
POST /api/v1/message/rest/send
POST /api/v1/message/thread/{threadUid}/read
POST /auth/v1/login
```

工作台没有复制上游许可证代码，也没有修改 JWT 验签、角色权限或后端许可证校验。

## 启动

```powershell
powershell -ExecutionPolicy Bypass -File .\start-selfdesk.ps1
powershell -ExecutionPolicy Bypass -File .\start-selfdesk.ps1 -Status
powershell -ExecutionPolicy Bypass -File .\start-selfdesk.ps1 -Stop
```

本机 Redis 从 WSL Ubuntu 的 `/opt/selfdesk-redis` 启动，监听 `16379`。当前应用使用 H2、`noai` 测试配置，并关闭了验证码以便本地登录测试；JWT 和角色权限仍然有效。

## 当前边界

这是可运行的自建客服台 MVP，尚未达到上游客服端的全部功能：

- 当前使用 REST 轮询，不是 STOMP/WebSocket 实时消息。
- 目前只支持发送文本；图片、文件、语音、视频上行尚未接入。
- 转接、邀请、接管、评价、工单、快捷回复、知识库辅助和客服在线状态切换尚未做 UI。
- 会话列表目前读取组织会话，后续应改为只显示当前客服已分配、被邀请或正在监听的会话，并补对象级权限测试。
- 访客端仍是上游 Web 客户端；下一步可以同样替换成自有访客组件。

## 生产上线前

1. 关闭 `bytedesk.testing.disable-captcha`，接入图形验证码或企业 SSO。
2. 将 H2 替换为 MySQL/PostgreSQL，使用正式 Liquibase 基线和备份策略。
3. 使用专用 Redis，启用认证、持久化和访问控制。
4. 用 SSE 或 WebSocket 替代轮询，并补断线重连和消息确认。
5. 按客服、工作组和组织做对象级权限过滤。
6. 补齐消息审计、敏感信息脱敏、文件安全扫描和保留期限。
7. 做并发、弱网、超时、重试和重复消息测试。
