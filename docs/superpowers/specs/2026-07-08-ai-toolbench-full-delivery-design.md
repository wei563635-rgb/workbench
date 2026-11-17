# AI 工具台完整交付设计规格

## 目标

按 `doc/一期/AI工具台一期需求对照功能清单.md` 和 `doc/一期/AI工具台下一阶段权限与数据隔离演进设计.md` 完整实现 AI 工具台工程。实现范围包含 RBAC、组织数据范围、项目 ACL、工具运行、项目产出、文件资产、AI 网关、前后台拆分、后台管理、工具接入规范和回归验收。

本规格不以静态 demo 页面作为业务来源。所有功能必须满足：

- 有数据库模型。
- 有后端接口。
- 有前端页面或明确的系统入口。
- 有后端权限校验。
- 有错误处理。
- 有操作日志或运行日志。
- 有可执行验收方式。

## 范围边界

### 本轮必须实现

1. 账号安全与 Session 入库。
2. 平台 RBAC 和权限点校验。
3. 组织部门、多部门归属、主部门和数据范围。
4. AI 工具分类与工具访问权限。
5. 完整项目模型、项目 ACL 和项目成员管理。
6. `project_task` 默认任务流程，用于项目页查询和展示。
7. `tool_run`、`tool_run_event` 和强制项目绑定。
8. `project_result`、`file_asset` 和项目结果快照。
9. AI 网关日志、脱敏、模型配置和成本统计。
10. `/workbench` 前台和 `/admin` 后台入口拆分。
11. 后台用户、部门、角色、工具、模型、部署、日志页面。
12. 前台工作台、工具容器、项目页、运行记录页。
13. 工具接入规范 skill、SDK、manifest 校验和回归脚本。

### 本轮不实现

1. 用户级单独权限授权。
2. deny 权限和拒绝覆盖。
3. 项目 ACL 角色后台配置。
4. 任务模板后台配置。
5. 复杂任务编排、依赖流转和审批流。
6. AI 工具分类后台配置。
7. 定期密码过期、历史密码复用限制、强制特殊字符。
8. 消息通知、监控告警、BI 分析。

## 核心架构

```text
浏览器
  -> /workbench 前台
  -> /admin 后台
  -> /tools/* 工具容器
      -> 平台 API
          -> AuthContext / PermissionContext
          -> RBAC 校验
          -> 数据范围校验
          -> 项目 ACL 校验
          -> 业务 Service
          -> Repository
          -> PostgreSQL/H2
          -> 本地存储/OSS
          -> AI Gateway
```

后端从当前单控制器原型演进为按职责拆分的模块：

- `AuthService`：登录、密码、Session、当前用户。
- `PermissionService`：权限点、多角色并集、敏感权限、版本即时生效。
- `DepartmentService`：组织部门、用户部门、数据范围。
- `ToolService`：AI 工具分类、工具配置、工具访问权限。
- `ProjectService`：项目、项目成员、项目 ACL、默认任务。
- `ToolRunService`：运行记录、事件、工具状态和权限校验。
- `ProjectResultService`：项目结果快照、软删除、恢复。
- `FileAssetService`：文件资产登记、下载权限、存储适配。
- `AiGatewayService`：模型调用、脱敏、日志、成本估算。
- `AdminService`：后台聚合查询和管理操作。

## 权限模型

### RBAC

系统内置权限点，后台允许角色新增、编辑、启用、禁用，并为角色勾选内置权限点。后台不允许新增权限点编码。

用户可以绑定多个角色。有效权限采用并集模型：

```text
用户有效权限 = 所有关联且启用角色的权限点合集
```

一期不做 deny 权限，不做拒绝覆盖，不做用户级权限黑名单。

权限点按模块分组：

- 工作台权限
- 项目权限
- 工具权限
- 模型与 AI 网关
- 运行记录
- 用户与组织
- 角色与权限
- 部署管理
- 日志审计
- 系统配置

敏感权限勾选时前端二次确认，后端按普通权限点保存，不做审批流。

### 权限即时生效

角色权限变更、用户角色变更、角色禁用、用户禁用必须及时生效。

实现规则：

- 用户 Session 入库。
- 用户表保存 `session_version` 或等价字段。
- 权限变更更新 `permission_version`。
- 每次请求重新构建或刷新权限上下文，不能只依赖登录时快照。
- 用户禁用后删除或失效该用户所有 Session。
- 角色禁用后立即从权限计算中排除。

### 数据范围

组织部门用于数据隔离，AI 工具分类只用于前台工具导航和工具归类，两者不得混用。

支持数据范围：

```text
SELF        本人创建或本人运行数据
PROJECT     被加入项目的数据
DEPARTMENT  所属或授权部门数据
ALL         全平台数据
```

普通成员默认 `SELF + PROJECT`。部门负责人默认 `DEPARTMENT`，但仍需功能权限配合。管理员可拥有 `ALL`。

### 项目 ACL

项目内置角色固定：

```text
PROJECT_OWNER
PROJECT_MEMBER
```

项目负责人可管理项目成员、归档项目、移除项目结果、生成 AI 汇总。项目成员可查看项目、在项目内运行自己有权限的工具、查看项目内允许展示的结果。

管理员类角色可通过平台权限管理全部项目，但不要求写入 `project_member`。

## 数据模型

### 账号与权限

新增或重构：

```text
sys_user
sys_role
sys_permission
sys_role_permission
sys_user_role
sys_session
sys_department
sys_user_department
```

要求：

- 密码使用 BCrypt。
- 管理员不可查看和编辑明文密码，只能重置。
- 新增用户和重置密码生成一次性临时密码。
- 临时密码仅在创建或重置响应中展示一次，不进入日志。
- `must_change_password=true` 时只能访问改密、退出和当前用户基础信息接口。
- 用户支持多个组织部门，必须有且只有一个主部门。
- 组织部门仅支持启用/禁用，不做物理删除。

### 工具与分类

新增或重构：

```text
ai_task_category
tool_config
role_tool_permission
```

工具状态：

```text
ONLINE       已上线，可启动
MAINTENANCE  维护中，不可启动，历史可看
COMING_SOON  未开放，不可启动，不生成运行记录
DISABLED     已停用，前台隐藏，历史可看
```

前台只展示用户有工具访问权限且非 `DISABLED` 的工具。后端启动工具时必须再次校验工具状态、工具访问权限、项目访问权限。

工具访问权限底层按单工具授权保存，后台体验可支持按 AI 任务分类批量勾选，保存结果仍为 `role_tool_permission`。

### 项目

新增或重构：

```text
project
project_member
project_product
project_task
project_result
```

项目必须字段：

```text
项目名称
项目编号
所属组织部门
负责人
项目成员
项目状态
商品名称
品牌
类目
售价
立项时间
商品卖点
项目目标
创建时间
更新时间
```

项目状态：

```text
ACTIVE      进行中，可运行工具并生成结果
COMPLETED   已完成，可查看，默认不可生成新结果
ARCHIVED    归档，可查看，默认不可生成新结果
```

只有 `ACTIVE` 项目允许运行工具并生成项目结果。管理员可恢复已归档项目。向 `COMPLETED` 项目补充结果前，项目负责人或管理员必须先恢复为 `ACTIVE`。

### Project Task

`project_task` 本轮实现为默认业务流程支撑数据，不做后台任务模板配置。

规则：

- 项目创建时按系统默认模板生成任务。
- 默认任务用于项目页查询和展示。
- 支持基础查询、创建、更新状态接口。
- 不做任务模板后台。
- 不做复杂依赖、审批、自动派发和流转引擎。

默认任务建议：

```text
市场情报分析
消费者洞察分析
竞店分析
推广图制作
地域优化
项目复盘汇总
```

默认任务可保存：

```text
task_id
project_id
task_name
ai_category_code
tool_key
assignee_user_id
status
run_id
started_at
finished_at
sort_order
created_at
updated_at
```

### Tool Run

新增或重构：

```text
tool_run
tool_run_event
```

`tool_run` 创建时必须绑定 `project_id`，创建后不可改绑。

创建运行必须满足：

- 用户已登录且未强制改密。
- 用户当前拥有该工具访问权限。
- 工具状态为 `ONLINE`。
- 项目存在且状态为 `ACTIVE`。
- 用户是项目成员，或拥有管理全部项目的权限。

事件类型：

```text
open
open_failed
run
run_failed
project_result_created
project_result_removed
```

`open_failed` 和 `run_failed` 默认不生成项目结果。

### Project Result

工具运行成功后系统自动创建 `project_result`。旧 `/api/project-links` 降级为兼容接口，不作为新主流程。

`project_result` 必须保存完整业务快照，不得只引用动态 `tool_run`：

```text
result_id
project_id
run_id
tool_key
title
summary
output_data_snapshot
file_snapshot
image_snapshot
submitter_snapshot
tool_snapshot
status
created_by
created_at
removed_by
removed_at
remove_reason
restored_by
restored_at
restore_reason
```

状态：

```text
ACTIVE
REMOVED
```

项目负责人和管理员可以软删除项目结果，删除原因必填且不少于 5 个字。管理员可以恢复 `REMOVED` 结果，恢复原因必填。普通成员不能移除或恢复项目结果。

### File Asset

新增：

```text
file_asset
```

图片、附件、HTML、JSON、报告统一作为文件资产管理，通过 `asset_type` 和 `mime_type` 区分。

`project_result` 不复制物理文件，只保存文件快照并引用 `file_asset`。已被项目结果引用的文件资产不得自动清理或物理删除。

下载接口必须鉴权，并按 `project_result` 权限判断，不直接按原始 `tool_run` 权限判断。

## 后端接口

### 认证

```http
POST /api/auth/login
POST /api/auth/logout
GET  /api/auth/me
POST /api/auth/change-password
```

改密接口从当前 Session 识别用户，不接受前端传 `userId` 代改。

### 前台

```http
GET    /api/workbench/bootstrap
GET    /api/workbench/tools
GET    /api/projects
POST   /api/projects
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
POST   /api/projects/{projectId}/members
DELETE /api/projects/{projectId}/members/{userId}
GET    /api/projects/{projectId}/tasks
POST   /api/projects/{projectId}/tasks
PUT    /api/projects/{projectId}/tasks/{taskId}
GET    /api/projects/{projectId}/results
POST   /api/projects/{projectId}/results/{resultId}/remove
POST   /api/projects/{projectId}/results/{resultId}/restore
GET    /api/runs
GET    /api/runs/{runId}/detail
POST   /api/tool-runs/start
POST   /api/tool-runs/{runId}/events
POST   /api/tool-runs/{runId}/finish
POST   /api/files/upload
GET    /api/files/{fileId}/download
POST   /api/ai/chat/completions
POST   /api/ai/images/generations
GET    /api/ai/tasks/{taskId}
```

### 后台

```http
GET    /api/admin/users
POST   /api/admin/users
PUT    /api/admin/users/{userId}
POST   /api/admin/users/{userId}/reset-password
POST   /api/admin/users/{userId}/status
GET    /api/admin/departments
POST   /api/admin/departments
PUT    /api/admin/departments/{departmentId}
POST   /api/admin/departments/{departmentId}/status
GET    /api/admin/roles
POST   /api/admin/roles
PUT    /api/admin/roles/{roleId}
POST   /api/admin/roles/{roleId}/status
GET    /api/admin/permissions
GET    /api/admin/tools
PUT    /api/admin/tools/{toolKey}
PUT    /api/admin/tools/{toolKey}/status
GET    /api/admin/model-configs
PUT    /api/admin/model-configs/{configId}
GET    /api/admin/ai-call-logs
GET    /api/admin/operation-logs
GET    /api/admin/deployments
POST   /api/admin/deployments/request
```

`/api/admin/**` 必须按权限点校验，不允许只校验登录。

## 前端页面

### 前台 `/workbench`

前台包含：

- 登录后工作台首页。
- AI 工具分类导航。
- 用户有权限的工具卡片。
- 最近使用。
- 统一工具容器页。
- 项目列表。
- 项目详情。
- 项目任务。
- 项目结果。
- 运行记录。
- 运行详情。
- 强制改密页。

工具容器页必须统一处理：

- 加载中。
- 加载失败。
- 维护中。
- 未开放。
- 无权限。
- 返回工作台。
- iframe 或静态工具。
- postMessage 结果回传。

### 后台 `/admin`

后台包含：

- 用户管理。
- 组织部门管理。
- 角色权限管理。
- 工具管理。
- 模型配置。
- 部署门禁。
- AI 调用日志。
- 操作日志。

后台使用独立导航和布局。无后台权限用户访问 `/admin` 时显示无权限或跳回工作台。

## AI 网关

所有 AI 调用必须走平台 `/api/ai/*`。AI 调用接口必须校验：

- `runId` 存在。
- run 所属项目仍对当前用户可访问。
- run 所属工具和请求工具一致。
- 请求用户和当前登录用户一致，或当前用户拥有管理权限。

日志必须记录：

- runId
- projectId
- toolKey
- userId
- modelName
- modelType
- requestParams 脱敏快照
- responseBody 脱敏或摘要
- promptTokens
- completionTokens
- totalTokens
- costEstimate
- status
- createdAt

模型 Key 不在前端明文返回，不进入操作日志。模型价格配置用于成本估算。

## 工具接入规范

更新 `developer-skill/ai-tool-integration`：

- `tool-manifest.json` 必填字段和服务端校验一致。
- 明确 `/api/tool-runs/start` 的 `projectId` 必填。
- SDK 示例覆盖 `startRun -> ai -> upload -> finish`。
- iframe 工具通过 postMessage 上报事件和完成结果。
- 正式工具必须 `callbackEnabled=true`、`aiGatewayRequired=true`。
- 正式工具不得使用 `external` 外链模式。
- 不允许直接调用外部模型供应商。

## 验收

### 自动回归

更新 `scripts/regression_check.py`，覆盖：

- 未登录访问前台和后台受保护接口返回 401。
- 登录成功。
- 强制改密用户只能访问改密相关接口。
- 管理员新增用户、重置密码、禁用用户。
- 角色新增、勾选权限、禁用角色即时生效。
- 普通成员不能访问后台。
- 部门负责人只能看到授权范围数据。
- 创建项目自动生成默认任务。
- 项目成员才能在项目内启动工具。
- 无工具权限不能启动工具。
- 非 `ONLINE` 工具不能启动。
- `tool_run` 必须绑定项目。
- 成功 `finishRun` 自动生成 `project_result`。
- 项目负责人可软删除结果。
- 管理员可恢复结果。
- 文件上传入库并可按权限下载。
- AI 网关调用入日志。
- 部署门禁 approved/rejected。
- manifest 校验脚本与服务端规则一致。

### 构建验证

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

### 复杂度标注

核心权限计算：

```text
时间复杂度：O(R + P + T)
R = 用户绑定角色数
P = 角色权限点数量
T = 角色工具权限数量
```

项目列表数据范围过滤：

```text
时间复杂度：数据库索引查询为主，应用层不做全量扫描过滤
```

项目结果快照创建：

```text
时间复杂度：O(F + I)
F = 文件数量
I = 图片数量
```

## 迁移策略

现有原型表可保留兼容读取，但新主流程使用新表。迁移原则：

- 明文密码迁移到 BCrypt。无法可靠识别的旧密码在首次登录成功后升级，或由管理员重置。
- `app_user` 迁移到 `sys_user`。
- `user_department` 迁移到 `sys_user_department`，并补主部门。
- `project_board` 迁移到 `project` 和 `project_product`。
- `project_result_link` 迁移为 `project_result` 快照。
- `tool_run` 补 `project_id`，无项目的旧运行记录只作为历史记录展示，不允许进入新项目结果主流程。

## 决策记录

- RBAC、组织数据范围、项目 ACL 均纳入本轮完整工程，不降级为简单角色判断。
- `project_task` 本轮实现查询流程和默认数据，不做后台配置。
- AI 工具分类和组织部门分离。
- 工具运行必须先绑定项目。
- 成功工具运行自动生成项目结果。
- 项目结果是快照，不是动态引用。
- 旧 `/api/project-links` 只保留兼容，不作为新主流程。
