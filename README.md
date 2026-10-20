# SelfDesk 智能客服平台

![ci](https://github.com/wms3643/selfdesk-platform/actions/workflows/ci.yml/badge.svg)
![license](https://img.shields.io/badge/license-AGPL--3.0-0E7A68)
![java](https://img.shields.io/badge/Java-21-0E7A68)
![spring](https://img.shields.io/badge/Spring%20Boot-4.x-0E7A68)

一套完整可部署的企业级智能客服系统：多渠道接入、坐席协同、知识库与 RAG、工单与 CRM、AI 机器人与人工协同。
AI 侧提供两套可切换实现：**Spring AI 直连**（单进程）与 **Python + LangGraph 编排**（意图分流、工具调用、转人工挂起恢复），业务代码零改动。

| 规模 | |
|---|---|
| Maven 模块 | **24** |
| Java 源文件 | **4,420** |
| REST 控制器 | **239**（约 1,840 个映射） |
| 前端入口 | **22** 个独立页面 |
| AI 实现 | Spring AI（内置）/ LangGraph（可切换） |

---

## 目录

- [能力范围](#能力范围)
- [架构](#架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置](#配置)
- [AI 链路](#ai-链路)
- [界面与运营层](#界面与运营层)
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
                  PostgreSQL   Elasticsearch  Redis
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
| 数据 | **PostgreSQL（默认）** / MySQL / H2（单机演示）/ Oracle / 达梦 / 人大金仓 |
| 检索 | Elasticsearch（全文 + 向量），可切换 pgvector |
| 缓存与消息 | Redis（可选，`bytedesk.cache.level=0` 时不需要） |
| 实时通道 | WebSocket / STOMP |
| 前端 | Ant Design（管理后台）、原生 HTML/CSS/JS（工作台与看板） |
| 构建 | Maven 多模块，产物为单一 fat jar |

## 依赖清单

| 类别 | 依赖 | 版本 | 是否必需 |
|---|---|---|---|
| 容器 | Docker + Compose | 20+ | 方式一必需 |
| 运行时 | JDK | 21 | 方式二必需 |
| 构建 | Maven（内置 wrapper `mvnw` / `mvnw.cmd`） | 3.9.9 | 方式二必需 |
| 检索 | Elasticsearch | 8.x | 必需（Docker 方式自动拉起） |
| 缓存 | Redis | 7.x | 可选（`bytedesk.cache.level=0` 时不需要） |
| 数据库 | **PostgreSQL 16**（compose 自带） | 16 | 必需（也可切 MySQL / H2） |
| 部署脚本 | Python | 3.10+ | 可选（`pip install -r deploy-selfdesk/requirements.txt`） |

## 快速开始

### 方式一：Docker 一键启动（推荐）

只需要 Docker，本机不用装 JDK、Maven、Elasticsearch：

```bash
cp .env.example .env        # 填 DEEPSEEK_API_KEY；留空也能启动，只是 AI 不回答
docker compose up -d --build
```

首次构建会编译 24 个模块，约 10 分钟（之后有缓存）。启动后打开 **http://localhost:9003/**，
这是一个落地页，三个主入口都在上面（访客端 / 客服工作台 / 管理后台）：

| 入口 | 地址 | 是否需要登录 |
|---|---|---|
| **首页（从这进）** | http://localhost:9003/ | 不需要 |
| 访客端 | http://localhost:9003/chat/ | 不需要 |
| 客服工作台 | http://localhost:9003/console/ | 不需要（本机免密） |
| 管理后台 | http://localhost:9003/admin | 不需要，打开即进入 |

**管理后台默认免登录**：演示账号 `admin@email.com` / `SelfDesk#2026` 会在页面加载时自动填入并提交，
所以克隆下来直接点 `/admin` 就能看到完整后台，不用猜密码。

需要正式的登录流程时，构建时关掉即可：

```bash
docker compose build --build-arg SELFDESK_DEMO_AUTOLOGIN=0
docker compose up -d
```

关闭后走正常登录页，账号 `admin@email.com`，密码为首次建库时的 `BYTEDESK_ADMIN_PASSWORD`（默认 `SelfDesk#2026`）。

数据落在三个命名卷里（`esdata` 索引、`pgdata` 业务库、`appdata` 上传文件），`docker compose down` 不会丢；
要清空用 `docker compose down -v`。

compose 自带 PostgreSQL 16 并设为默认业务库（表结构由 JPA 自动创建，首次启动约 40 秒建完）；
同时开启了演示模式（免验证码）；
要接 MySQL/PostgreSQL，把 `BYTEDESK_DATASOURCE_ACTIVE` 与 `SPRING_DATASOURCE_*` 换成对应值即可。

### 方式二：本机构建

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 必须 |
| Maven | 3.9+ | 构建 |
| Docker | 任意近期版本 | 跑 Elasticsearch（检索与向量库） |
| Python | 3.10+ | 仅部署脚本与本地向量服务需要 |

### 1. 构建

```bash
# Windows
.\mvnw.cmd -DskipTests -pl starter -am package
# macOS / Linux
./mvnw -DskipTests -pl starter -am package
```

产物：`starter/target/bytedesk-starter.jar`。首次执行 wrapper 会下载 Maven 3.9.9（约 9 MB），之后走本地缓存。

### 2. 启动依赖

```bash
cd deploy-selfdesk
docker compose up -d          # Elasticsearch 8.x
```

### 3. 运行

```bash
java -jar starter/target/bytedesk-starter.jar --spring.profiles.active=open
```

默认使用 PostgreSQL（`bytedesk` 库），表结构在首次启动时由 JPA 自动创建。
若想零依赖跑单机演示，可把数据源切到 H2 文件库（`--bytedesk.datasource.active=h2`）。

打开：

| 入口 | 地址 |
|---|---|
| 访客端 | http://localhost:9003/chat/ |
| 客服工作台 | http://localhost:9003/console/ |
| 管理后台 | http://localhost:9003/admin |
| 坐席移动端 | http://localhost:9003/agent/ |

初始管理员：`admin@email.com`。密码在**首次建库时**由 `BYTEDESK_ADMIN_PASSWORD` 决定（缺省为 `bytedesk.admin.password` 配置值）；
库已存在后再改环境变量不会改密码。

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
| `BYTEDESK_DATASOURCE_ACTIVE` | 数据库类型 | `postgresql` |
| `SPRING_ELASTICSEARCH_URIS` | 检索地址 | `http://localhost:19200` |
| `SPRING_AI_DEEPSEEK_API_KEY` | 模型密钥 | — |
| `SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL` | 模型名 | `deepseek-chat` |
| `SPRING_AI_DEEPSEEK_BASE_URL` | 模型地址（可指向本地编排层） | `https://api.deepseek.com` |
| `SPRING_AI_VECTORSTORE_TYPE` | 向量库类型 | `elasticsearch` |
| `BYTEDESK_CACHE_LEVEL` | 0 关闭缓存 / 1 Caffeine / 2 +Redis | `0` |

数据库切换：`bytedesk.datasource.active=postgresql|mysql|h2|oracle|kingbase`，
对应连接串在 `starter/src/main/resources/properties/open/datasource/` 下，**默认密码均为占位符 `change-me`，上线前必须改**。

## AI 链路

系统内置两条可切换的 AI 实现：

| | A · 全 Java | B · 双语言 |
|---|---|---|
| 实现 | Spring AI + DeepSeek | Java 业务 + Python LangGraph 编排 |
| 进程 | 1 个 | 2 个（9003 + 8003） |
| 切换 | `SPRING_AI_DEEPSEEK_BASE_URL` 指向本地 OpenAI 兼容端点即可 | 同左 |
| 会话状态 | PostgreSQL | 业务在 PostgreSQL，AI 状态也在 PostgreSQL（不同库） |

版本 B 的 AI 层单独开源：[selfdesk-langgraph](https://github.com/wms3643/selfdesk-langgraph)，
提供意图分流、RAG、工具调用、两阶段写操作与转人工挂起；本仓库的 `deploy-selfdesk/scripts/ai-gateway.ps1`
负责在两个版本之间切换。

AI 侧具备：

- **多厂商模型**：DeepSeek / Moonshot / OpenRouter / OpenAI / Minimax，可给不同机器人绑定不同模型
- **知识库 RAG**：文档切分 → 向量化 → Elasticsearch 检索 → 生成时附带引用
- **业务工具**：订单查询、退款申请、工单创建等以工具形式暴露给模型，写操作需二次确认
- **意图与分流**：政策类问题走知识库，业务类走工具，识别到人工诉求转坐席
- **可观测**：模型调用、token 用量、响应耗时写入日志与仪表盘

## 界面与运营层

`deploy-selfdesk/` 负责界面与部署，**不改 Java 业务代码**，只替换静态资源：

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
deploy/                 Docker / K8s / FreeSWITCH 部署样例
deploy-selfdesk/        界面与部署脚本
docs/                   架构与部署文档
```

## 构建与部署

| 场景 | 做法 |
|---|---|
| 本地开发 | `mvn -pl starter -am spring-boot:run` |
| 生产 jar | `mvn -DskipTests -pl starter -am package` |
| 容器 | 参考 `deploy/docker/one/docker-compose.yaml` |
| K8s | 参考 `deploy/k8s/`（含 secret 样例） |
| 数据库 | 默认 PostgreSQL；切换 MySQL/H2 只需改 `bytedesk.datasource.active` 与连接串 |

## 工程与质量

- **多模块边界**：每个模块自带 `pom.xml`、`application.properties`、Controller，依赖方向单向（module → core）
- **配置外置**：环境变量优先，配置文件按主题拆分（`properties/open/*`）
- **CI**：静态检查（脚本语法、密钥扫描、敏感文件、配置占位符、README 图片引用）
- **测试**：界面与部署层有 pytest 用例（补丁幂等、注入边界）；业务模块沿用内置测试集
- **数据安全**：仓库内所有默认口令为 `change-me`，真实密钥不落库

## 已知限制

- 单体部署：默认一个 jar，模块化但非微服务；超大并发场景需按模块拆分
- 默认 PostgreSQL 适合单机与中小规模；高并发或多实例部署请自行加连接池、读写分离与备份策略
- 向量检索依赖 Elasticsearch；未部署时知识库不可用（不影响会话）
- 前端部分页面为预构建产物，二次开发需改 `frontend/` 后重新构建
- 文档以中文为主，英文文档在补充中
- 示例视频使用 Git LFS，本仓库未包含这些媒体文件（不影响功能）
- `mvnw` 首次运行需要访问 Maven 仓库下载分发包；离线环境请预装 Maven 3.9+ 并设置 `MAVEN_HOME`
- 默认开启**演示免密**（后台自动登录），仅用于本地演示；对外部署请用 `--build-arg SELFDESK_DEMO_AUTOLOGIN=0` 重新构建

## 许可

本项目以 **AGPL-3.0** 发布，许可全文见 [LICENSE](LICENSE)。
第三方组件、版权归属与修改记录见 [NOTICE.md](NOTICE.md)。
对外提供网络服务时，请遵守 AGPL-3.0 关于源码开放的要求。
