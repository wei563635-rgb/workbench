# 推广图制作工具接入一起互动 AI 工作台设计规格

## 1. 背景与目标

客户工具仓库 `electronicZ/my-ai-workbench-cloud` 是一个独立部署的 Next.js 应用，内部包含文案生成、提示词反推和 AI 生图三个页面。客户将其视为一个完整的“推广图制作”功能，因此平台只注册一个工具，不拆分工具卡片、manifest 或权限项。

本次目标是在不修改客户业务流程和 UI 的前提下，将该应用通过 Docker 独立运行并以 iframe 方式接入一起互动 AI 工作台，同时完成以下平台化改造：

- 用户只登录一起互动工作台，不再登录客户工具自身账号；
- 客户工具内部 API 使用平台身份和项目上下文鉴权；
- 客户使用的 Coze、豆包和 Evolink 模型保持不变，但调用和密钥统一收口到平台 AI 网关；
- 每次实际生成或提交创建一条独立运行记录；
- 输入文件、生成图片和附件通过平台文件接口登记和持久化；
- 客户工具原有页面、字段、交互、返回结构和业务语义保持不变；
- 提供 Docker、健康检查和同域 Nginx 反向代理配置。

本次不做并发压测，不重做客户 UI，不合并三个内部页面，不替换客户模型，不重构与平台接入无关的代码。

## 2. 工具定义

平台复用已有工具配置：

| 字段 | 值 |
|---|---|
| `toolKey` | `promo-image` |
| 名称 | 推广图制作 |
| 部门 | `ai-design`（AI 设计部） |
| 接入类型 | `iframe` |
| 状态 | `normal` |
| 回调 | 启用 |
| AI 网关 | 强制 |
| 同域入口 | `/tools/promo-image/platform-entry` |

目标工具新增 `tool-manifest.json`，至少声明：

```json
{
  "toolKey": "promo-image",
  "name": "推广图制作",
  "version": "1.0.0",
  "departmentKey": "ai-design",
  "entry": "/tools/promo-image/platform-entry",
  "integrationType": "iframe",
  "status": "normal",
  "callbackEnabled": true,
  "aiGatewayRequired": true,
  "modelTypes": ["text", "vision", "image"],
  "resultTypes": ["json", "file", "image"],
  "requiredApis": [
    "/api/tool-runs/start",
    "/api/tool-runs/{runId}/finish",
    "/api/ai/*",
    "/api/files/upload"
  ],
  "directModelEndpoints": [],
  "owner": "AI 设计部"
}
```

## 3. 总体架构

目标工具保留两种运行模式：

- `standalone`：保留现有登录和积分，供原项目维护和本地调试；AI 调用仍使用开发环境的平台网关，不保留供应商直连或本地供应商 Key；
- `platform`：正式接入模式，使用平台鉴权、项目上下文、运行记录、AI 网关和文件存储。

```text
工作台登录与项目选择
  -> 平台签发一次性 iframe Ticket
  -> iframe 打开目标工具 platform-entry
  -> 目标工具后端兑换 Ticket 并建立 HttpOnly 工具会话
  -> 用户进入原有 UI
  -> 用户提交一次业务操作
  -> 创建 promo-image 运行记录
  -> PlatformClient 调用平台 AI 网关和文件接口
  -> 平台网关适配 Coze / 豆包 / Evolink
  -> 完成运行记录
  -> postMessage 通知父页面刷新运行数据
```

工作台仓库承担 iframe 容器、票据与工具会话、AI 供应商适配、运行记录、文件存储和回传校验。目标工具仓库承担平台登录入口、平台鉴权适配、PlatformClient、调用结果兼容、outbox 和 Docker 交付。

## 4. iframe 登录与鉴权

### 4.1 票据签发

工作台前端在用户选择进行中的项目并点击“推广图制作”后，请求：

```http
POST /api/tool-sessions/tickets
Authorization: Bearer $PLATFORM_ACCESS_TOKEN
Content-Type: application/json

{
  "toolKey": "promo-image",
  "projectId": "proj_xxx"
}
```

后端不得相信请求中的用户 ID，而是从当前平台登录态获取用户。后端校验用户状态、部门/工具权限、项目状态和项目成员权限后，生成 256 位随机不透明 Ticket。Ticket 绑定 `toolKey`、`userId`、`projectId`、来源平台会话、创建时间和 60 秒过期时间。

数据库只保存 Ticket 的 SHA-256 摘要。兑换操作使用条件更新或事务锁保证单次消费，防止并发重放。

响应：

```json
{
  "entryUrl": "/tools/promo-image/platform-entry?ticket=$OPAQUE_TICKET",
  "expiresAt": "2026-07-15T12:00:00Z"
}
```

### 4.2 票据兑换

目标工具的 `platform-entry` 服务端路由读取 Ticket，并使用服务间凭证调用：

```http
POST /api/tool-sessions/exchange
Authorization: Bearer $PLATFORM_SERVICE_TOKEN
Content-Type: application/json

{
  "ticket": "$OPAQUE_TICKET",
  "toolKey": "promo-image"
}
```

平台原子消费 Ticket，签发受限工具会话 Token。数据库仅保存 Token 摘要，并关联原平台会话、用户、项目、工具和过期时间。目标工具将原始 Token 写入以下 Cookie 后重定向到原首页：

```text
HttpOnly; Secure; SameSite=Lax; Path=/tools/promo-image
```

### 4.3 会话内省

目标工具服务端页面和 API 通过以下接口获取可信上下文：

```http
POST /api/tool-sessions/introspect
Authorization: Bearer $PLATFORM_SERVICE_TOKEN
X-Tool-Session: $TOOL_SESSION_TOKEN
```

响应只返回接入所需字段：`active`、`userId`、`account`、`name`、`role`、`departmentKeys`、`toolKey`、`projectId` 和 `expiresAt`。

内省必须同时确认原平台会话仍有效。用户退出、账号禁用、项目归档、工具停用或来源会话失效后，工具会话立即不可用。

### 4.4 目标工具兼容

`platform` 模式下：

- `requireUser()` 和 `guardToolUsage()` 使用平台内省结果；
- 现有 CSRF Cookie/Header 校验继续使用；
- 浏览器提交的 `userId`、`projectId` 和 `toolKey` 一律忽略；
- 不读取本地用户数据库，不执行本地积分扣减或退款；
- 平台用户映射到现有前端用户结构，积分为 `null`，现有 UI 显示“无上限”；
- 平台身份失效时返回 401，禁止自动回退到本地身份。

`standalone` 模式保留现有登录、积分和页面行为，但 AI 请求仍通过开发环境的平台网关；目标仓库在任何模式下都不保存供应商 Key，也不包含供应商直连实现。

## 5. 运行记录

运行记录按“每次实际业务提交”创建，不按 iframe 打开创建。

### 5.1 文案生成

1. 校验输入和平台会话；
2. `POST /api/tool-runs/start`；
3. 通过平台网关调用 Coze；
4. 成功时保存回复、会话 ID 和摘要并完成运行；
5. 失败时完成失败运行；
6. 按原 `/api/copywriting` 响应结构返回页面。

### 5.2 提示词反推

一个 `runId` 包含两次豆包视觉调用和一次豆包文本融合调用。每个阶段通过 `/events` 上报进度，最终 `outputData` 保存 `productPrompt`、`stylePrompt`、`llmResponse` 和 `finalPrompt`，保持现有前端结构。

### 5.3 AI 生图

创建运行后上传参考图、提交 Evolink 异步任务，并持久化 `runId` 与本地 `taskId`/远端任务 ID 的关系。后台轮询成功后转存生成图片并结束运行；失败或超时则结束失败运行。现有轮询 API 和前端任务体验不变。

### 5.4 失败回调

所有业务失败都必须尝试 `finish(status=failed)`。如果平台回调暂时失败，目标工具将待发送事件写入 SQLite `platform_outbox`，后台按指数退避重试。outbox 只保存业务事件和摘要，不保存 Cookie、Ticket、会话 Token、供应商 Key 或 Authorization。

## 6. AI 网关供应商适配

平台扩展 `ModelConfig`：

- `provider`：`coze`、`doubao-openai-compatible` 或 `evolink`；
- `modelType`：`text`、`vision` 或 `image`；
- `modelName`：平台内唯一模型标识；
- `apiBaseUrl`：供应商基础地址；
- `apiKeyEncrypted`：加密后的供应商凭证；
- `providerOptions`：Bot ID、供应商模型 ID、默认质量等非密钥配置。

新增统一的 `AiProviderAdapter` 边界：

```text
supports(ModelConfig)
chat(AiGatewayRequest, ModelConfig)
image(AiGatewayRequest, ModelConfig)
task(taskId, ModelConfig)
```

具体映射：

| 客户链路 | 平台适配 |
|---|---|
| Coze 文案 Bot | 网关将统一请求翻译为 Coze Bot 请求，并把回复恢复为目标工具现有格式 |
| 豆包视觉 | 使用 OpenAI 兼容消息格式，保留 data URI/图片 URL 和客户模型 ID |
| 豆包文本 | 使用 OpenAI 兼容文本请求，保留现有 Prompt 和采样参数 |
| Evolink 生图 | 转发图片生成参数，保存远端任务 ID，并通过统一任务接口查询 |

供应商凭证使用平台主密钥进行 AES-GCM 加密后入库，主密钥仅从部署环境变量读取。后台展示只返回脱敏值。目标工具源码、环境变量、接口响应和日志中不再出现供应商 Key。

## 7. 文件与图片

所有输入图片、生成图片和附件都通过 `/api/files/upload` 登记，并绑定当前 `runId`。

输入参考图流程：

```text
浏览器 -> 目标工具 API -> 平台文件接口 -> 可供供应商读取的 URL
```

生成图片流程：

```text
Evolink 临时 URL -> 目标工具后端下载 -> 平台文件接口 -> 平台永久 URL
```

目标工具原有图片历史继续保存，但 URL 替换为平台永久 URL。运行结果中的 `images`、`files` 和 `attachments` 使用平台文件元数据，供运行详情和项目结果快照展示。

生产环境必须配置 OSS，或确保平台本地文件下载 URL 能被 Evolink 从公网访问。生成图片必须在供应商临时 URL 过期前完成转存；转存失败时本次运行失败，不得把临时 URL 当作永久结果。

## 8. iframe 回传

完成运行后，目标工具向精确配置的工作台 Origin 发送 `AI_TOOL_RUN_RESULT`。禁止使用 `*`。

父页面校验：

- `event.origin` 等于允许域名；
- `type` 为 `AI_TOOL_RUN_RESULT`；
- `toolKey` 为 `promo-image`；
- `runId` 属于当前登录用户、当前项目和当前工具。

消息只作为刷新提示，平台以服务端运行记录为事实来源，不直接信任 iframe 中的结果数据。

## 9. Docker 与同域部署

目标工具使用 Debian Node 20 多阶段 Docker 镜像，避免 `better-sqlite3` 在 Alpine 环境下的原生模块兼容问题。

```text
构建：npm ci -> npm run build
运行端口：3000
数据卷：/app/data
健康检查：/tools/promo-image/api/health
```

平台模式环境变量：

```text
INTEGRATION_MODE=platform
PLATFORM_BASE_URL=http://workbench-backend:8080
PLATFORM_TOOL_KEY=promo-image
PLATFORM_SERVICE_TOKEN=$SERVICE_SECRET
PLATFORM_ALLOWED_ORIGIN=$WORKBENCH_ORIGIN
NEXT_PUBLIC_BASE_PATH=/tools/promo-image
```

Nginx 将 `/tools/promo-image/` 反向代理到目标容器 3000 端口，并保留 `Host`、`X-Forwarded-Proto` 和 `X-Forwarded-For`。Next.js 的 `basePath` 在构建时设置为 `/tools/promo-image`；独立开发模式不设置该值，继续运行在根路径。

## 10. 错误处理与安全

- Ticket 过期、重放、跨工具或跨项目使用返回 401/403；
- 平台会话失效后内部 API 不回退本地登录；
- AI 网关错误转换为现有业务 API 错误结构；
- 文件转存失败导致本次运行失败；
- 回调失败进入 outbox，避免运行永久停留在进行中；
- 日志对 `apiKey`、`Authorization`、`password`、`token`、`cookie` 和 Ticket 统一脱敏；
- iframe 响应配置合适的 `Content-Security-Policy frame-ancestors`；
- 平台 Ticket URL 使用 `Referrer-Policy: no-referrer`；
- 服务间凭证只存在于容器环境变量，不下发浏览器；
- Docker 停止时停止接收新任务，等待当前任务安全退出，未发送事件保留在 outbox。

## 11. 验收与测试

本次不执行并发压测，验收聚焦接入正确性和业务回归：

1. Ticket 正常兑换、过期、重复兑换、跨工具和跨项目测试；
2. 用户退出平台后目标工具接口立即失效；
3. iframe 内不出现目标工具二次登录；
4. 三个原有页面布局、字段和操作流程不变；
5. 文案生成继续使用 Coze；
6. 提示词反推继续执行两次豆包视觉和一次豆包文本；
7. AI 生图继续使用 Evolink 异步任务；
8. 每次实际提交生成独立运行记录；
9. 输入图片和生成图片均登记到平台；
10. AI 成功、失败和超时均正确结束运行；
11. `tool-manifest.json` 通过内部校验脚本；
12. 工作台后端测试、目标工具 lint/build 和 Docker build 通过；
13. 源码扫描确认目标工具不存在供应商直连地址和供应商 Key；
14. 日志测试确认敏感字段已脱敏。

关键适配函数标注复杂度：会话、Ticket、运行状态和 outbox 索引操作为均摊 `O(1)`；文件上传、下载和哈希计算相对文件大小为 `O(n)` 时间，流式传输时额外内存为 `O(1)`，不得把大文件完整复制多份到内存。

## 12. 分支与交付边界

工作台基线为 `feature/ai-toolbench-full-delivery@323a4f9`，改造分支为 `feat/promo-image-integration`。

目标工具基线为 `main@c91823f`，改造分支为 `feat/yiqi-toolbench-integration`。

实现仅修改平台接入边界、鉴权适配、模型网关、文件流、运行回调和部署文件。客户业务页面与 UI 文件原则上不改；如果构建期 `basePath` 必须调整公共链接，只允许通过公共路由辅助函数完成，不修改视觉结构或业务交互。
