# Spring AI 侧：模型接入与两条链路

Java 业务系统用 **Spring AI** 负责业务侧 AI（应答、知识库、机器人），Python 编排层则用 LangGraph 做另一套完整实现。两者可以任选一条，也可以随时切换。

## 1. Spring AI 直连模型

通过环境变量注入，不改业务代码：

| 变量 | 作用 |
|---|---|
| `SPRING_AI_DEEPSEEK_API_KEY` | 模型密钥 |
| `SPRING_AI_DEEPSEEK_BASE_URL` | 上游地址，默认 `https://api.deepseek.com` |
| `SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL` | 模型名，默认走后台「大模型」页配置 |

后台「智能助手 → 大模型」支持多厂商（DeepSeek / Moonshot / OpenRouter / OpenAI / Minimax），每个厂商单独配 Key 与模型，机器人可以绑定不同模型。

## 2. 切到 Python 编排层

`ai-gateway.ps1` 做一件事：把 Spring AI 的 `base-url` 指到本地 OpenAI 兼容端点，然后重启。

```powershell
.\scripts\ai-gateway.ps1 on -Restart     # Java 的模型请求 → http://127.0.0.1:8003
.\scripts\ai-gateway.ps1 status          # 查看当前模式与两端健康状况
.\scripts\ai-gateway.ps1 off -Restart    # 回退直连 DeepSeek
```

原理：

```
Spring AI  ──(OpenAI 协议)──►  :8003/v1/chat/completions  ──►  LangGraph 编排
                                     │
                                     └─ 会话 ID 通过 X-SelfDesk-Session 透传
```

切换是可逆的：删掉 `.ai-gateway` 标记文件重启即回退，Python 层挂掉不影响业务侧。

## 3. 两条链路怎么选

| | 直连 Spring AI | 经 Python 编排层 |
|---|---|---|
| 会话数据 | 全在 Java（H2） | Java 仍是会话主库，AI 决策在 Python |
| 工具调用 | 业务服务内直调 | Python 工具注册表 + 两阶段确认 |
| 转人工 | 排队/分配 | LangGraph `interrupt` 挂起，坐席回复后 resume |
| 运维 | 一个进程 | 多一个 Python 服务 |
| 适用 | 演示业务闭环 | 演示 AI 编排、可观测性、写操作安全 |

> 默认是直连模式。开启前先确认 `http://127.0.0.1:8003/api/health` 正常，否则 AI 会整体不可用。
