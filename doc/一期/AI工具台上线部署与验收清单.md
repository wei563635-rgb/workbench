# 一起互动 AI 工具台上线部署与验收清单

## 1. 上线前客户准备

### 必须购买

- 阿里云 ECS：部署后端服务和前端静态资源。
- PostgreSQL 数据库：存储用户、工具配置、项目、运行记录、部署门禁、AI 调用日志、操作日志。可使用客户现有 PostgreSQL 或阿里云 RDS PostgreSQL。
- 阿里云 OSS：存储工具上传文件和生成素材。
- 域名与 HTTPS 证书：正式访问入口。
- 大模型 API 额度：通过平台 AI API 网关统一消耗和记账。

### 可选购买

- API 网关：如果客户要求公网统一 API 入口、限流、鉴权前置。
- DBS 数据库备份：如果 RDS 默认备份策略不能满足客户保留周期。
- 云监控告警：用于 ECS/RDS/接口可用性告警。
- WAF：公网正式域名暴露后，如客户有安全合规要求再购买。

## 2. 生产环境变量

后端默认使用本地 H2 文件库，生产环境必须替换为客户 PostgreSQL。

```bash
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:postgresql://<pg-host>:5432/workbench
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=<pg-user>
SPRING_DATASOURCE_PASSWORD=<pg-password>
```

AI 网关先按配置位接入。客户未提供真实模型供应商前使用 `mock` 模式；客户提供后切到 `http`：

```bash
APP_AI_GATEWAY_MODE=http
```

模型供应商的 `apiBaseUrl`、`apiKey`、模型名在后台“模型配置”中维护。平台会统一通过 `/api/ai/*` 转发、记录请求参数、返回结果、Token 用量和成本估算。

Evolink Nanobanana 2 Lite 图片模型已预留接入：

- `apiBaseUrl`: `https://api.evolink.ai/v1`
- `modelName`: `gemini-3.1-flash-lite-image`
- 生成接口：平台 `/api/ai/images/generations` 转发到 Evolink `/v1/images/generations`
- 查询接口：平台 `/api/ai/tasks/{taskId}` 转发到 Evolink `/v1/tasks/{task_id}`
- Evolink 返回异步任务 ID，生成图片链接有效期为 24 小时，完成后需及时入库或转存对象存储。

文件存储先按配置位接入。客户未提供对象存储前使用本地模式；客户提供 OSS/对象存储参数后切换：

```bash
APP_STORAGE_MODE=oss
APP_OSS_ENDPOINT=<oss-endpoint>
APP_OSS_BUCKET=<oss-bucket>
APP_OSS_ACCESS_KEY_ID=<access-key-id>
APP_OSS_ACCESS_KEY_SECRET=<access-key-secret>
```

当前原型使用 JDBC 自动建表：

- `app_user`：用户账号。
- `user_department`：用户部门权限。
- `tool_config`：工具配置。
- `model_config`：模型供应商和模型配置。
- `tool_run`：工具运行记录。
- `project_board`：项目看板。
- `project_member`：项目成员。
- `project_suggestion`：项目建议。
- `project_result_link`：项目关联结果。
- `deployment_request`：部署门禁申请。
- `deployment_error`：部署门禁失败原因。
- `ai_call_log`：模型调用日志。
- `operation_log`：后台操作日志。

## 3. 部署门禁规则

客户 AI 工具进入正式服务器前，必须提交 `tool-manifest.json` 并通过服务端门禁。

正式工具必须满足：

- `callbackEnabled=true`
- `aiGatewayRequired=true`
- 声明调用 `/api/tool-runs/start`
- 声明调用 `/api/tool-runs/{runId}/finish`
- 声明调用平台 `/api/ai/*`
- 不允许直接调用 OpenAI、Claude、Gemini、通义、火山、智谱等外部模型地址
- 不允许以 `external` 外链模式进入正式部署

未通过门禁的工具只能临时演示，不能部署到正式服务器路径。

## 4. 本地构建命令

后端：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

前端：

```bash
cd frontend
npm run build
```

## 5. 验收接口

上线后至少验证以下接口：

- `POST /api/auth/login`
- `GET /api/bootstrap`
- `POST /api/tool-runs/start`
- `POST /api/tool-runs/{runId}/finish`
- `POST /api/ai/chat/completions`
- `POST /api/ai/images/generations`
- `GET /api/ai/tasks/{taskId}`
- `GET /api/admin/ai-call-logs`
- `GET /api/admin/operation-logs`
- `POST /api/admin/deployments/request`
- `POST /api/project-links`

后台管理接口 `/api/admin/**` 必须先调用 `POST /api/auth/login` 获取 `accessToken`，后续请求携带：

```http
Authorization: Bearer <accessToken>
```

## 6. 自动回归

后端启动后执行：

```bash
python3 scripts/regression_check.py http://localhost:18080
```

回归脚本覆盖：

- 后台接口无 Token 返回 401。
- 登录成功并获取 `accessToken`。
- 工作台 `bootstrap` 正常返回。
- 工具运行开始和完成回传。
- AI 网关调用和日志查询。
- Evolink 图片异步任务创建和任务查询。
- 部署门禁通过和拒绝。
- 项目结果关联。

## 7. 验收重点

- 工具必须通过平台接口创建运行记录和完成回传。
- 所有 AI 模型调用必须走统一 AI API 网关。
- AI 请求参数、返回结果、Token 用量、成本估算必须能在后台查到。
- 操作日志和模型调用日志必须进入数据库。
- 项目关联结果重启后不能丢失。
- 用户、工具状态、模型配置、部署门禁记录重启后不能丢失。
