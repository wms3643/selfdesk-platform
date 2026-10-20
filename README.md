# SelfDesk 智能客服平台

![ci](https://github.com/wms3643/bytedesk-selfdesk/actions/workflows/ci.yml/badge.svg)
![license](https://img.shields.io/badge/license-AGPL--3.0-0E7A68)
![java](https://img.shields.io/badge/Java-21-0E7A68)
![spring](https://img.shields.io/badge/Spring%20Boot-4.x-0E7A68)

一套完整可部署的企业级智能客服系统：多渠道接入、坐席协同、知识库与 RAG、工单与 CRM、AI 机器人与人工协同。
在开源项目 [bytedesk](https://github.com/Bytedesk/bytedesk) 的基础上做了业务定制、界面重做与 AI 链路改造，源码完整开放，可直接构建运行。

| 规模 | |
|---|---|
| Maven 模块 | **24** |
| Java 源文件 | **4,420** |
| REST 控制器 | **239**（约 1,840 个映射） |
| 前端入口 | **22** 个独立页面 |

---

## 目录

- [能力范围](#能力范围)
- [架构](#架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置](#配置)
- [AI 链路](#ai-链路)
- [界面定制层](#界面定制层)
- [目录结构](#目录结构)
- [构建与部署](#构建与部署)
- [工程与质量](#工程与质量)
- [已知限制](#已知限制)
- [许可与致谢](#许可与致谢)

---

## 能力范围

| 域 | 模块 | 主要内容 |
|---|---|---|
| 在线客服 | `service` `core` | 会话接入、排队分配、坐席状态、消息路由、WebSocket 长连接 |
| 智能助手 | `ai` | Spring AI 多厂商接入、机器人配置、知识库 RAG、业务工具调用、意图识别 |
| 知识库 | `kbase` | 文档管理、切分与向量化、Elasticsearch 混合检索、飞书文档同步 |
| 工单 | `ticket` | 工单创建、流转、优先级、统计 |
| 客户与 CRM | `crm` `team` | 客户档案、组织与成员、角色权限 |
| 呼叫中心 | `call` | FreeSWITCH 对接、话务条、通话记录 |
| 视频与会议 | `webrtc` `meet` | WebRTC 通话、会议房间 |
| 内容协作 | `kanban` `forum` `office` | 看板、论坛、在线文档 |
| 商业模块 | `mall` `erp` `marketing` | 商品、订单、营销活动 |
| 数据与洞察 | `bi` `voc` `statistic` | 经营看板、客户之声、多维统计 |
| 平台能力 | `cli` `remote` `disk` `social` `training` `opinion` | 命令行、远程协助、网盘、社媒、培训、舆情 |

> 24 个模块可以按需裁剪，`starter` 是唯一的可执行入口。

## 架构

```
                     ┌───────────────────────────────┐
  访客  ──► /chat/   │        starter (Spring Boot)  │   /admin  ◄── 管理员
                     │  ┌─────────────────────────┐  │
  坐席  ──► /console │  │ service  会话/排队/坐席  │  │   /agent  ◄── 移动坐席
                     │  │ ai       机器人与 RAG    │  │
  客服主管 ► /dashboard│ │ kbase    知识库          │  │
                     │  │ ticket   工单            │  │
                     │  └─────────────────────────┘  │
                     └───┬───────────┬───────────┬───┘
                         │           │           │
                   H2/MySQL/PG   Elasticsearch  Redis
                   （业务数据）   （检索+向量）   （缓存/流）
                         │
                         ▼
                 可选：Python 编排层（OpenAI 兼容）
```

单体模块化：所有模块编译进一个可执行 jar，模块之间用 Spring 事件与接口解耦，避免分布式事务，
同时保留了按模块拆分的边界（每个模块独立 pom、独立配置、独立 Controller）。

## 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | Java 21、Spring Boot 4.x、Spring AI |
| 数据 | H2（默认，零依赖）/ MySQL / PostgreSQL / Oracle / 达梦 / 人大金仓 |
| 检索 | Elasticsearch（全文 + 向量），可切换 pgvector |
| 缓存与消息 | Redis（可选，`bytedesk.cache.level=0` 时不需要） |
| 实时通道 | WebSocket / STOMP |
| 前端 | Ant Design（管理后台）、原生 HTML/CSS/JS（工作台与看板） |
| 构建 | Maven 多模块，产物为单一 fat jar |

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 必须 |
| Maven | 3.9+ | 构建 |
| Docker | 任意近期版本 | 跑 Elasticsearch（检索与向量库） |
| Python | 3.10+ | 仅定制层脚本与本地向量服务需要 |

### 1. 构建

```bash
mvn -DskipTests -pl starter -am package
```

产物：`starter/target/bytedesk-starter.jar`

### 2. 启动依赖

```bash
cd deploy-selfdesk
docker compose up -d          # Elasticsearch 8.x
```

### 3. 运行

```bash
java -jar starter/target/bytedesk-starter.jar --spring.profiles.active=open
```

默认使用 H2 文件库，数据落在 `./data`，无需额外数据库。

打开：

| 入口 | 地址 |
|---|---|
| 访客端 | http://localhost:9003/chat/ |
| 客服工作台 | http://localhost:9003/console/ |
| 管理后台 | http://localhost:9003/admin |
| 坐席移动端 | http://localhost:9003/agent/ |

初始管理员：`admin@email.com`，密码以首次启动日志输出为准（或通过 `BYTEDESK_ADMIN_PASSWORD` 指定）。

### 4. 可选：一键脚本（Windows）

`deploy-selfdesk/scripts/selfdesk.ps1` 会依次拉起 Redis / Elasticsearch / 向量服务 / 应用 / 仪表盘：

```powershell
cd deploy-selfdesk
.\scripts\selfdesk.ps1 start
```

## 配置

核心开关都走环境变量或 `application-open.properties`：

| 变量 | 作用 | 默认 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 运行模式 | `open` |
| `BYTEDESK_DATASOURCE_ACTIVE` | 数据库类型 | `h2` |
| `SPRING_ELASTICSEARCH_URIS` | 检索地址 | `http://localhost:19200` |
| `SPRING_AI_DEEPSEEK_API_KEY` | 模型密钥 | — |
| `SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL` | 模型名 | `deepseek-chat` |
| `SPRING_AI_DEEPSEEK_BASE_URL` | 模型上游（可指向本地编排层） | `https://api.deepseek.com` |
| `SPRING_AI_VECTORSTORE_TYPE` | 向量库类型 | `elasticsearch` |
| `BYTEDESK_CACHE_LEVEL` | 0 关闭缓存 / 1 Caffeine / 2 +Redis | `0` |

数据库切换：`bytedesk.datasource.active=h2|mysql|postgresql|oracle|kingbase`，
对应连接串在 `starter/src/main/resources/properties/open/datasource/` 下，**默认密码均为占位符 `change-me`，上线前必须改**。

## AI 链路

系统内置两条可切换的 AI 实现：

| | A · 全 Java | B · 双语言 |
|---|---|---|
| 实现 | Spring AI + DeepSeek | Java 业务 + Python LangGraph 编排 |
| 进程 | 1 个 | 2 个（9003 + 8003） |
| 切换 | `SPRING_AI_DEEPSEEK_BASE_URL` 指向本地 OpenAI 兼容端点即可 | 同左 |
| 会话状态 | H2 | 业务在 H2，AI 状态在 PostgreSQL |

版本 B 的 AI 层单独开源：[selfdesk-langgraph](https://github.com/wms3643/selfdesk-langgraph)，
提供意图分流、RAG、工具调用、两阶段写操作与转人工挂起；本仓库的 `deploy-selfdesk/scripts/ai-gateway.ps1`
负责在两个版本之间切换。

AI 侧具备：

- **多厂商模型**：DeepSeek / Moonshot / OpenRouter / OpenAI / Minimax，可给不同机器人绑定不同模型
- **知识库 RAG**：文档切分 → 向量化 → Elasticsearch 检索 → 生成时附带引用
- **业务工具**：订单查询、退款申请、工单创建等以工具形式暴露给模型，写操作需二次确认
- **意图与分流**：政策类问题走知识库，业务类走工具，识别到人工诉求转坐席
- **可观测**：模型调用、token 用量、响应耗时写入日志与仪表盘

## 界面定制层

`deploy-selfdesk/` 是本仓库对上游的改造部分，**不改 Java 业务代码**，只替换静态资源：

| 目录 | 内容 |
|---|---|
| `console/` | 重写的客服工作台（原生 JS，直连 `/api/v1`） |
| `admin-patch/` | 后台主题注入、许可占位页替换、品牌脚本 |
| `dashboard/` | 独立运营仪表盘（会话、状态、AI 用量与费用） |
| `scripts/` | 一键启停、AI 网关、jar 补丁、向量服务 |
| `compose.yaml` | Elasticsearch（+ 可选 Redis） |

打补丁：

```powershell
cd deploy-selfdesk
python scripts\patch_jar.py        # 把上述静态资源写进 fat jar，可重复执行
```

## 目录结构

```
modules/                24 个业务模块（独立 pom）
  ai/                   模型接入、机器人、知识库检索、业务工具
  service/              会话、排队、坐席、消息路由
  kbase/ ticket/ crm/  知识库、工单、客户
  call/ webrtc/ meet/   呼叫中心、音视频
  ...
starter/                唯一可执行入口（配置 + 静态资源）
frontend/               前端源码（构建产物已放入 starter/static）
deploy/                 上游的 Docker / K8s / FreeSWITCH 部署样例
deploy-selfdesk/        本项目的界面与部署定制层
docs/                   架构、部署、上游文档
```

## 构建与部署

| 场景 | 做法 |
|---|---|
| 本地开发 | `mvn -pl starter -am spring-boot:run` |
| 生产 jar | `mvn -DskipTests -pl starter -am package` |
| 容器 | 参考 `deploy/docker/one/docker-compose.yaml` |
| K8s | 参考 `deploy/k8s/`（含 secret 样例） |
| 数据库 | 默认 H2；切换 MySQL/PG 只需改 `bytedesk.datasource.active` 与连接串 |

## 工程与质量

- **多模块边界**：每个模块自带 `pom.xml`、`application.properties`、Controller，依赖方向单向（module → core）
- **配置外置**：环境变量优先，配置文件按主题拆分（`properties/open/*`）
- **CI**：静态检查（脚本语法、密钥扫描、敏感文件、配置占位符、README 图片引用）
- **测试**：定制层有 pytest 用例（补丁幂等、注入边界）；业务模块使用上游测试集
- **数据安全**：仓库内所有默认口令为 `change-me`，真实密钥不落库

## 已知限制

- 单体部署：默认一个 jar，模块化但非微服务；超大并发场景需按模块拆分
- 默认 H2 适合单机演示与小规模；生产建议 MySQL/PostgreSQL
- 向量检索依赖 Elasticsearch；未部署时知识库不可用（不影响会话）
- 前端部分页面为上游预构建产物，二次开发需改 `frontend/` 后重新构建
- 文档以中文为主，英文文档在补充中
- 上游示例视频使用 Git LFS，本仓库未包含这些媒体文件（不影响功能）
- 未附带 `mvnw`（上游同样用 LFS 存 wrapper），构建请使用本机 Maven 3.9+

## 许可与致谢

本项目基于 [bytedesk](https://github.com/Bytedesk/bytedesk)（**AGPL-3.0**）修改，遵循同一许可发布。
上游版权归其作者所有；本仓库的修改包括：界面定制层、AI 链路改造、部署脚本、配置清理（口令占位符化）。
二次分发或提供网络服务时，请遵守 AGPL-3.0 的源码开放要求。详见 [NOTICE.md](NOTICE.md)。
