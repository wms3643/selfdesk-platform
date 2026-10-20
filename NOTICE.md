# NOTICE

## 上游

本仓库是 [bytedesk](https://github.com/Bytedesk/bytedesk) 的定制发行版。

- 版权：归 bytedesk 原作者所有
- 许可：**GNU Affero General Public License v3.0（AGPL-3.0）**
- 本仓库保留完整源码与许可文本（见 `LICENSE`），并遵循同一许可发布

## 本仓库的修改

1. **界面定制层**（`deploy-selfdesk/`）
   - 重写客服工作台（`console/`）
   - 独立运营仪表盘（`dashboard/`）
   - 管理后台主题注入与占位页替换（`admin-patch/`）
2. **部署与运维**
   - 一键启停、AI 网关切换、jar 补丁、向量服务（`deploy-selfdesk/scripts/`）
   - Elasticsearch/Redis 编排（`deploy-selfdesk/compose.yaml`）
3. **AI 链路**
   - 支持将 Spring AI 的模型请求转发到外部 OpenAI 兼容编排层（版本 B）
4. **安全清理**
   - 源码中硬编码的数据库 / Redis 默认口令替换为 `change-me`
   - 移除本地环境文件与密钥（不随仓库分发）

## AGPL 义务

如果你修改本仓库并通过网络向用户提供服务，需要按 AGPL-3.0 公开对应的完整源码。
商业使用前请阅读 `LICENSE` 全文，或联系上游作者获取商业授权。
