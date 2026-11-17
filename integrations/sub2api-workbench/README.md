# Workbench 中转执行扩展

此目录是 Sub2API 的源码扩展，用于构建完整中转制品，不是运行时二进制补丁。基线：上游提交 `86f93c2`。工程源文件只保存协议和源码，不保存任何环境连接值。

Evolink 受控备用上游使用独立入口 `apply_evolink.py`，基于已核验的生产源码提交 `9a2511261` 保留全部既有扩展，再加入故障准入和图片异步适配。它不能仅靠普通账号优先级启用，也不能与面向干净上游的 `apply.py` 混用。范围、触发条件、开关及发布验收见 [Evolink 受控兜底说明](../../doc/二期/Evolink受控兜底说明.md)。

执行 `python3 integrations/sub2api-workbench/apply.py <固定提交源码根目录>` 将扩展加入独立构建目录；脚本先校验所有受影响源文件及 go.mod 的 SHA256，再检查插入点，不支持任意上游版本或重复应用。之后按照上游 `backend/Makefile` 运行单元测试和 `make build`，记录完整提交、扩展提交及制品 SHA256。固定源码声明 Go 1.27.0；构建默认版本字符串不能替代源码提交身份。

扩展管理接口位于 `/api/v1/admin/workbench`，沿用上游管理员认证、审计和合规中间件。请求准入挂在两种 API Key 认证路径之后、计费和调度之前。没有配置映射的原有中转用户保持原行为；已映射用户若策略无效则拒绝调用。

统一使用记录扩展见 [AI 使用记录说明](../../doc/二期/AI使用记录说明.md)。生产定制源码使用 `apply_usage.py`，固定完整基线 `278d7090a`，保留 Evolink 兜底；使用前核对完整源码及校验清单。该扩展不改变配额计量。

API 契约版本 1：

- `GET /capabilities`：扩展版本，`callRecords=true` 表示支持独立调用记录。
- `GET /calls` / `GET /calls/:id/audit`：按更新时间和变更游标同步所有已采集调用，按事件 ID 读取加密详情；不要求存在原生用量 ID。
- `PUT /policy`：带单调 revision 的完整部门/员工策略快照，原子应用。配置缺失拒绝已映射员工的新请求。
- `GET /usage?from=...&until=...&after=...`：按来源 ID 分页的用量事实；Token 与上游 `UsageLog.TotalTokens()` 口径一致。
- `POST /keys` / `DELETE /keys`：以原生 Key 服务创建、停用指定员工的 internal / client Key，重试校验所有者、用途和分组。
- `GET /usage/:id/audit`：读取指定原生用量记录的加密审计，工作台另行限制为内置超级管理员访问。

并发采用数据库租约，定期续约，释放失败由租约到期补偿；成功响应的租约在原生异步用量落库后释放，防止连续请求利用记账延迟绕过额度。用量检查直接读取中转数据库。配额只检查新生成请求，查询已生成结果不扣并发、不因额度耗尽被拒。两类 Key 均按员工原生 ID 共用额度、并发；原生 Key 自身的有效性检查仍保留。

托管员工按 Token 配额计费，跳过钱包余额和订阅金额门槛，不通过虚增余额实现；原生用量与账号成本继续记录。未托管用户保留原生计费方式。同一策略 revision 的重试必须内容一致，不一致返回冲突；已绑定员工不能更换原生用户 ID。

OpenAI 账号可分别配置 5 小时、7 天窗口阈值，达到阈值暂停该账号调度，窗口恢复后重新参与。判定使用原生可信的窗口读数与重置时间；Google 无对应原生指标时显示不可用。

图片接口在尚未收到上游响应头时遇到连接复位等传输错误，会交由原生图片调度器尝试其他账号；客户端已取消的请求不换号。此类错误无法证明上游未处理请求，复验时应同时核对上游用量，关注可能的重复计费。

## 安装与联调前置

工作台服务端注入 `APP_SUB2API_BASE_URL`、`APP_SUB2API_ADMIN_API_KEY`、`APP_RELAY_GROUP_ID`、`APP_AI_MASTER_KEY`；分组必须已启用且允许 OpenAI / Gemini / composite。默认 group-id 为 0，不自动创建员工。中转服务端单独注入 `WORKBENCH_AUDIT_KEY`（Base64 编码的随机 32 字节密钥）。所有实际入口及密钥通过个人环境配置和凭据设施管理，不提交到工程。

原生管理员接口受上游 `AdminComplianceGuard` 约束。全新实例返回 `423 ADMIN_COMPLIANCE_ACK_REQUIRED` 时，须由管理员阅读固定版本的 `docs/legal/admin-compliance.zh.md` 并明确同意后才能继续。不得改数据库、关闭中间件或自动代签绕过。

建议在独立 PostgreSQL 上运行以下验证，`WORKBENCH_TEST_DSN` 仅指向可创建、删除测试 schema 的隔离库：

```bash
# 以下命令位于扩展后的 Sub2API/backend 目录
go test ./internal/workbench -count=1 -v
go test ./internal/service -run 'TestWorkbenchWindowThreshold|TestEvaluateAccountSchedulingThreshold' -count=1
make build
```

## 当前边界

- 已托管员工的 Responses WebSocket 多轮会话暂不支持，返回明确错误要求使用 HTTP / SSE，以免多轮请求绕过逐次配额检查。
- 请求正文超过 64 MiB 拒绝；响应审计超过 64 MiB 标记不完整，页面禁止将其解释为完整凭证。审计不保存鉴权头，正文中的凭据字段脱敏。
- 审计读取限制在 180 天，过期审计清理由用量同步触发；同步中断期间可能延迟物理清理。失败且没有原生用量 ID 的调用，仍不计入用量统计；安装独立记录扩展后可通过 `/calls` 及事件详情读取。
- 初次回补覆盖最近 180 天，回补前的未知部门归属不猜测；365 天热力图仅呈现工作台实际累积的数据。
- 当前统计次数为已计量调用，不代表所有发起请求或成功率。并发与数据库锁的性能上限仍须按目标流量压测。

需部署完整扩展制品后，工作台才可显示策略已生效。严禁在未安装扩展的服务器上把配置保存成功解释为额度生效。
