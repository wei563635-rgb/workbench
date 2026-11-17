# AI 工具台完整交付实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 按完整交付规格实现 AI 工具台，包括 RBAC、组织数据范围、项目 ACL、工具运行、项目结果、文件资产、AI 网关、前后台拆分和回归验收。

**架构：** 后端从单个 `ApiController` 原型拆为控制器、服务、仓储和权限上下文；数据库从原型表扩展为账号权限、组织、工具、项目、运行、结果、文件和审计模型。前端从单个 `App.vue` 拆为 `/workbench` 与 `/admin` 两套入口视图，所有权限以后端校验为准。

**技术栈：** Spring Boot 3.3.5、Java 21、JDBC、H2/PostgreSQL、Vue 3、Vite、Python 回归脚本、Node manifest 校验脚本。

---

## 文件结构

### 后端新增

- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/AuthContext.java`
  当前请求用户、Session、权限和数据范围上下文。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/AuthService.java`
  登录、退出、当前用户、改密、密码哈希、Session 失效。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/PasswordService.java`
  BCrypt 哈希、明文旧密码兼容判断、临时密码生成。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/SessionRepository.java`
  `sys_session` 持久化读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionService.java`
  权限点、角色权限、工具权限、数据范围计算。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionInterceptor.java`
  请求认证、强制改密拦截、后台权限点校验。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionCatalog.java`
  内置权限点和敏感权限元数据。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/user/UserRepository.java`
  `sys_user`、`sys_role`、`sys_permission`、用户角色关系读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/org/DepartmentRepository.java`
  `sys_department`、`sys_user_department` 读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/tool/ToolService.java`
  AI 工具分类、工具状态、工具访问权限、manifest 校验。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/tool/ToolRepository.java`
  `ai_task_category`、`tool_config`、`role_tool_permission` 读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectService.java`
  项目、成员、ACL、默认任务、项目状态。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectRepository.java`
  `project`、`project_product`、`project_member`、`project_task` 读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/run/ToolRunService.java`
  工具运行、事件、finish 后自动沉淀结果。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/run/ToolRunRepository.java`
  `tool_run`、`tool_run_event` 读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/result/ProjectResultService.java`
  项目结果快照、软删除、恢复、统计。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/result/ProjectResultRepository.java`
  `project_result` 读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetRepository.java`
  `file_asset` 元数据读写。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
  后台用户、部门、角色、工具、模型、部署、日志接口。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/workbench/WorkbenchController.java`
  前台 bootstrap、项目、运行记录、工具容器相关接口。
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/dev/DeveloperController.java`
  开发者 SDK、OpenAPI、manifest 校验接口。

### 后端修改

- 修改：`backend/pom.xml`
  增加 `spring-security-crypto` 用于 BCrypt。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
  逐步缩减为兼容入口，最终只保留必要兼容接口或删除。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WorkbenchRepository.java`
  保留旧表迁移辅助，新增 schema 创建交给新仓储。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/LogRepository.java`
  AI 日志补 `project_id`，操作日志支持筛选。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/AiGatewayService.java`
  增加 run/project 校验调用点、价格配置和日志脱敏。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/StorageService.java`
  写入 `file_asset`，下载按权限校验。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WebConfig.java`
  替换旧 `AdminAuthInterceptor` 为新权限拦截器。

### 前端新增

- 创建：`frontend/src/router.js`
  基于 `location.pathname` 的轻量入口分发。
- 创建：`frontend/src/state.js`
  登录用户、权限、数据缓存和错误状态。
- 创建：`frontend/src/api/auth.js`
  登录、退出、当前用户、改密 API。
- 创建：`frontend/src/api/admin.js`
  后台 API。
- 创建：`frontend/src/api/workbench.js`
  前台 API。
- 创建：`frontend/src/api/projects.js`
  项目、成员、任务、结果 API。
- 创建：`frontend/src/api/runs.js`
  工具运行、事件、finish、运行记录 API。
- 创建：`frontend/src/views/LoginView.vue`
  登录页。
- 创建：`frontend/src/views/ForcePasswordChangeView.vue`
  强制改密页。
- 创建：`frontend/src/views/WorkbenchShell.vue`
  前台布局。
- 创建：`frontend/src/views/AdminShell.vue`
  后台布局。
- 创建：`frontend/src/views/workbench/WorkbenchHome.vue`
  工具首页。
- 创建：`frontend/src/views/workbench/ToolContainer.vue`
  统一工具容器。
- 创建：`frontend/src/views/workbench/ProjectList.vue`
  项目列表。
- 创建：`frontend/src/views/workbench/ProjectDetail.vue`
  项目详情、任务、结果。
- 创建：`frontend/src/views/workbench/RunRecords.vue`
  运行记录与详情。
- 创建：`frontend/src/views/admin/UserAdmin.vue`
  用户管理。
- 创建：`frontend/src/views/admin/DepartmentAdmin.vue`
  组织部门管理。
- 创建：`frontend/src/views/admin/RoleAdmin.vue`
  角色权限管理。
- 创建：`frontend/src/views/admin/ToolAdmin.vue`
  工具管理。
- 创建：`frontend/src/views/admin/ModelAdmin.vue`
  模型配置。
- 创建：`frontend/src/views/admin/DeploymentAdmin.vue`
  部署门禁。
- 创建：`frontend/src/views/admin/LogAdmin.vue`
  AI 日志和操作日志。

### 前端修改

- 修改：`frontend/src/App.vue`
  只做入口分发，不承载所有页面业务。
- 修改：`frontend/src/api.js`
  拆分后保留兼容导出或删除。
- 修改：`frontend/src/styles.css`
  保留基础样式，新增前后台布局、表格、表单、工具容器、任务和结果样式。

### 文档和脚本修改

- 修改：`scripts/regression_check.py`
  覆盖完整权限、项目、运行、结果、文件、AI 网关和部署门禁回归。
- 修改：`developer-skill/ai-tool-integration/SKILL.md`
  对齐新协议。
- 修改：`developer-skill/ai-tool-integration/references/api-contract.md`
  `projectId` 必填，结果结构固定。
- 修改：`developer-skill/ai-tool-integration/references/tool-integration.md`
  工具类型、状态、项目绑定和上线规则。
- 修改：`developer-skill/ai-tool-integration/references/postmessage-contract.md`
  iframe 上报事件和完成结果。
- 修改：`developer-skill/ai-tool-integration/scripts/validate-tool-manifest.js`
  与服务端 manifest 规则一致。
- 修改：`developer-skill/ai-tool-integration/examples/static-html-tool/index.html`
  使用新 SDK 顺序。
- 修改：`developer-skill/ai-tool-integration/examples/static-html-tool/tool-manifest.json`
  增加必填声明。

---

## 任务 1：建立权限和数据模型 Schema

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionCatalog.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/user/UserRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/org/DepartmentRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/tool/ToolRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/run/ToolRunRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/result/ProjectResultRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WorkbenchRepository.java`

- [ ] **步骤 1：定义内置权限点目录**

在 `PermissionCatalog.java` 中定义不可由客户新增的权限点：

```java
package com.yiqi.aitoolbench.security;

import java.util.List;

public final class PermissionCatalog {
    private PermissionCatalog() {}

    public static final List<PermissionDefinition> PERMISSIONS = List.of(
            p("workbench:access", "工作台访问", "工作台权限", false),
            p("project:create", "创建项目", "项目权限", false),
            p("project:own", "担任项目负责人", "项目权限", false),
            p("project:read", "查看项目", "项目权限", false),
            p("project:write", "编辑项目", "项目权限", false),
            p("project:result:read", "查看项目结果", "项目权限", false),
            p("project:result:remove", "移除项目结果", "项目权限", true),
            p("project:result:restore", "恢复项目结果", "项目权限", true),
            p("project:result:download", "下载项目附件", "项目权限", true),
            p("run:self:read", "查看本人运行记录", "运行记录", false),
            p("run:department:read", "查看部门运行摘要", "运行记录", true),
            p("admin:user:read", "查看用户", "用户与组织", true),
            p("admin:user:write", "管理用户", "用户与组织", true),
            p("admin:department:read", "查看组织部门", "用户与组织", false),
            p("admin:department:write", "管理组织部门", "用户与组织", true),
            p("admin:role:read", "查看角色权限", "角色与权限", true),
            p("admin:role:write", "管理角色权限", "角色与权限", true),
            p("admin:tool:read", "查看工具配置", "工具权限", false),
            p("admin:tool:write", "管理工具配置", "工具权限", true),
            p("admin:model:read", "查看模型配置", "模型与 AI 网关", true),
            p("admin:model:write", "管理模型配置", "模型与 AI 网关", true),
            p("admin:ai-log:read", "查看 AI 调用日志", "日志审计", true),
            p("admin:log:read", "查看操作日志", "日志审计", true),
            p("admin:deploy:read", "查看部署门禁", "部署管理", false),
            p("admin:deploy:approve", "处理部署门禁", "部署管理", true)
    );

    private static PermissionDefinition p(String code, String name, String module, boolean sensitive) {
        return new PermissionDefinition(code, name, module, sensitive);
    }

    public record PermissionDefinition(String code, String name, String module, boolean sensitive) {}
}
```

- [ ] **步骤 2：创建仓储 schema 方法**

在各 Repository 构造函数中调用 `createSchema()`，建表必须包含：

```sql
sys_user, sys_role, sys_permission, sys_role_permission, sys_user_role,
sys_session, sys_department, sys_user_department,
ai_task_category, tool_config, role_tool_permission,
project, project_product, project_member, project_task,
tool_run, tool_run_event, project_result, file_asset
```

- [ ] **步骤 3：初始化种子数据**

初始化：

```text
角色：admin、department_leader、member、auditor
权限点：PermissionCatalog.PERMISSIONS
组织部门：市场部、设计部、运营部、人事部
AI 工具分类：ai-market、ai-design、ai-operation、ai-hr、ai-content
工具：沿用现有工具列表，状态映射 normal -> ONLINE，unopened -> COMING_SOON
管理员：admin/admin123，首次不强制改密
```

- [ ] **步骤 4：运行后端编译**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/yiqi/aitoolbench backend/pom.xml
git commit -m "feat: add core schema for permissions and workflow"
```

---

## 任务 2：实现账号安全、Session 入库和强制改密

**文件：**
- 修改：`backend/pom.xml`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/PasswordService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/AuthContext.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/AuthService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/SessionRepository.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/auth/AuthController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/SessionStore.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WebConfig.java`

- [ ] **步骤 1：添加 BCrypt 依赖**

在 `backend/pom.xml` 增加：

```xml
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **步骤 2：实现密码服务**

`PasswordService` 行为：

```java
public boolean matches(String rawPassword, String storedPassword) {
    if (storedPassword != null && storedPassword.startsWith("$2")) {
        return encoder.matches(rawPassword, storedPassword);
    }
    return rawPassword != null && rawPassword.equals(storedPassword);
}

public boolean needsRehash(String storedPassword) {
    return storedPassword == null || !storedPassword.startsWith("$2");
}

public void validateNewPassword(String account, String oldPassword, String newPassword) {
    if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("新密码至少 8 位");
    if (!newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) throw new IllegalArgumentException("新密码必须包含字母和数字");
    if (newPassword.equals(account)) throw new IllegalArgumentException("新密码不能与账号相同");
    if (newPassword.equals(oldPassword)) throw new IllegalArgumentException("新密码不能与旧密码相同");
}
```

- [ ] **步骤 3：实现登录和明文迁移**

登录成功后：

```text
明文旧密码匹配 -> 立即保存 BCrypt 哈希
失败次数清零
创建 sys_session
返回 accessToken、mustChangePassword、user、permissions
```

- [ ] **步骤 4：实现强制改密访问白名单**

`must_change_password=true` 时只允许：

```text
POST /api/auth/change-password
POST /api/auth/logout
GET  /api/auth/me
```

其他 `/api/**` 返回 403：

```json
{"code":"PASSWORD_CHANGE_REQUIRED","message":"请先修改初始密码"}
```

- [ ] **步骤 5：禁用用户立即失效**

管理员禁用用户时：

```text
sys_user.enabled=false
sys_user.session_version += 1
删除该用户所有 active session 或使 session_version 不匹配
```

- [ ] **步骤 6：更新回归脚本账号安全段**

在 `scripts/regression_check.py` 增加：

```python
status, created = request("POST", "/api/admin/users", {"username": "tmp_user", "displayName": "临时用户", "roleIds": ["member"]})
assert_true("create user temp password once", status == 200 and created.get("temporaryPassword"))
status, login = request("POST", "/api/auth/login", {"account": "tmp_user", "password": created["temporaryPassword"]})
assert_true("must change password", status == 200 and login.get("mustChangePassword") is True)
```

- [ ] **步骤 7：运行回归片段和编译**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 8：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add secure auth and persistent sessions"
```

---

## 任务 3：实现 RBAC、工具权限和数据范围

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionInterceptor.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/WebConfig.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：实现权限上下文**

`PermissionService` 提供：

```java
public AuthContext requireContext(String authorization);
public boolean hasPermission(AuthContext context, String permissionCode);
public void requirePermission(AuthContext context, String permissionCode);
public boolean hasToolAccess(AuthContext context, String toolKey);
public DataScope effectiveDataScope(AuthContext context);
```

- [ ] **步骤 2：实现多角色并集**

查询用户绑定的所有启用角色，合并：

```text
role_permission.permission_code
role_tool_permission.tool_key
role.data_scope
```

数据范围合并优先级：

```text
ALL > DEPARTMENT > PROJECT > SELF
```

- [ ] **步骤 3：实现角色禁用即时生效**

每次请求计算权限时排除 `sys_role.enabled=false` 的角色。角色禁用接口更新 `permission_version`。

- [ ] **步骤 4：实现后台权限接口**

`AdminController` 增加：

```http
GET  /api/admin/permissions
GET  /api/admin/roles
POST /api/admin/roles
PUT  /api/admin/roles/{roleId}
POST /api/admin/roles/{roleId}/status
```

敏感权限由前端二次确认，后端仍保存权限点；操作日志记录角色权限变更但不记录敏感数据。

- [ ] **步骤 5：实现工具访问权限**

角色工具权限保存到 `role_tool_permission`。前台工具列表只返回用户有权限且非 `DISABLED` 的工具。

- [ ] **步骤 6：更新回归脚本**

增加：

```python
assert_true("member cannot access admin", member_status == 403, member_payload)
assert_true("role disabled takes effect", forbidden_status == 403, forbidden_payload)
assert_true("tool permission required", no_tool_status == 403, no_tool_payload)
```

- [ ] **步骤 7：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 8：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add rbac and tool access control"
```

---

## 任务 4：实现组织部门和用户管理后台接口

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/org/DepartmentService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：实现组织部门 CRUD**

接口：

```http
GET  /api/admin/departments
POST /api/admin/departments
PUT  /api/admin/departments/{departmentId}
POST /api/admin/departments/{departmentId}/status
```

部门不物理删除，只启用/禁用。

- [ ] **步骤 2：实现用户多部门和主部门**

用户新增和编辑请求包含：

```json
{
  "username": "market01",
  "displayName": "李四",
  "roleIds": ["member"],
  "departmentIds": ["dept_market"],
  "primaryDepartmentId": "dept_market"
}
```

后端校验：

```text
departmentIds 非空
primaryDepartmentId 必须在 departmentIds 内
有且只有一个主部门
禁用部门不能分配给新用户
```

- [ ] **步骤 3：保留历史快照字段**

创建 `tool_run` 和 `project_result` 时保存用户部门快照，用户调岗不影响历史统计。

- [ ] **步骤 4：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 5：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add organization departments and user bindings"
```

---

## 任务 5：实现项目模型、项目 ACL 和默认任务

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/workbench/WorkbenchController.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：实现项目创建**

`POST /api/projects` 请求：

```json
{
  "name": "6月宠物饮水器投放优化",
  "projectCode": "PET-WATER-202606",
  "departmentId": "dept_market",
  "ownerUserId": "u_003",
  "memberUserIds": ["u_002"],
  "product": {
    "name": "宠物饮水器",
    "brand": "一起互动",
    "category": "宠物用品",
    "price": "159",
    "sellingPoints": "静音、易清洗、大容量",
    "goal": "优化投放和详情页策略"
  }
}
```

校验：

```text
创建人有 project:create
ownerUserId 具备 project:own
owner 自动写入 project_member PROJECT_OWNER
memberUserIds 写入 PROJECT_MEMBER
项目状态默认为 ACTIVE
```

- [ ] **步骤 2：创建默认任务**

项目创建后插入：

```text
市场情报分析 -> market-intelligence
消费者洞察分析 -> consumer-insight
竞店分析 -> competitor-analysis
推广图制作 -> promo-image
地域优化 -> geo-optimization
项目复盘汇总 -> project-dashboard
```

状态默认为 `PENDING`。

- [ ] **步骤 3：实现项目查询和数据范围过滤**

`GET /api/projects`：

```text
ALL：全部项目
DEPARTMENT：用户所属或授权部门项目
PROJECT：用户参与项目
SELF：用户创建或负责项目
```

数据库层用条件查询，不在应用层全量扫描。

- [ ] **步骤 4：实现项目成员和任务接口**

接口：

```http
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
POST   /api/projects/{projectId}/members
DELETE /api/projects/{projectId}/members/{userId}
GET    /api/projects/{projectId}/tasks
POST   /api/projects/{projectId}/tasks
PUT    /api/projects/{projectId}/tasks/{taskId}
```

- [ ] **步骤 5：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 6：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add projects acl and default tasks"
```

---

## 任务 6：实现工具运行、事件和项目结果自动沉淀

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/run/ToolRunService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/result/ProjectResultService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/workbench/WorkbenchController.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：实现 startRun 强校验**

`POST /api/tool-runs/start` 请求：

```json
{
  "projectId": "proj_001",
  "toolKey": "consumer-insight",
  "inputSummary": "问大家消费者洞察"
}
```

后端从 Session 取当前用户，不接受前端传 `userId` 作为执行人。

校验：

```text
工具存在
工具状态为 ONLINE
用户有 tool access
项目状态为 ACTIVE
用户是项目成员或有项目管理权限
```

- [ ] **步骤 2：写入 open 事件**

start 成功时写：

```text
tool_run.status = OPENED
tool_run_event.event_type = open
```

- [ ] **步骤 3：实现事件接口**

`POST /api/tool-runs/{runId}/events` 写入 `tool_run_event`，支持：

```text
open_failed
run
run_failed
project_result_created
project_result_removed
```

- [ ] **步骤 4：实现 finishRun**

成功时：

```text
tool_run.status = SUCCESS
创建 project_result ACTIVE 快照
写 project_result_created 事件
更新 project_task run_id 和状态为 DONE（如果 tool_key 匹配任务）
```

失败时：

```text
tool_run.status = FAILED
保存 error_message
写 run_failed 事件
不创建 project_result
```

- [ ] **步骤 5：实现项目结果软删除和恢复**

接口：

```http
POST /api/projects/{projectId}/results/{resultId}/remove
POST /api/projects/{projectId}/results/{resultId}/restore
```

删除原因和恢复原因必填且不少于 5 个字。

- [ ] **步骤 6：更新回归脚本**

覆盖：

```text
无 projectId 启动失败
非项目成员启动失败
无工具权限启动失败
成功 finish 自动生成 project_result
项目负责人可 remove
管理员可 restore
```

- [ ] **步骤 7：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 8：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add tool runs events and project results"
```

---

## 任务 7：实现文件资产和下载权限

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetService.java`
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/StorageService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/workbench/WorkbenchController.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：保存文件资产**

上传成功写入：

```text
file_asset_id
run_id
project_id
asset_type
file_name
mime_type
file_size
storage_key
download_url
created_by
created_at
```

- [ ] **步骤 2：项目结果保存文件快照**

`finishRun(success)` 从 `file_asset` 查询 run 相关文件，写入 `project_result.file_snapshot` 和 `image_snapshot`。

- [ ] **步骤 3：实现下载鉴权**

`GET /api/files/{fileId}/download`：

```text
管理员且有 project:result:download -> 可下载
项目负责人且结果 ACTIVE -> 可下载
项目成员且有 project:result:download -> 可下载
其他 -> 403
```

- [ ] **步骤 4：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 5：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: add file assets and download permissions"
```

---

## 任务 8：完善 AI 网关日志、模型配置和成本

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/AiGatewayService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/LogRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
- 修改：`scripts/regression_check.py`

- [ ] **步骤 1：AI 调用绑定 run/project**

AI 请求中保留：

```json
{
  "runId": "run_xxx",
  "toolKey": "consumer-insight",
  "model": "gpt-4.1",
  "params": {}
}
```

后端从 run 推导 userId、projectId，不信任前端传入 userId。

- [ ] **步骤 2：日志补 projectId 和脱敏**

敏感键：

```text
apiKey, Authorization, password, token, cookie, secret
```

统一替换为 `***REDACTED***`。

- [ ] **步骤 3：模型配置返回 masked key**

`GET /api/admin/model-configs` 不返回明文 Key，只返回 `apiKeyMasked`。

- [ ] **步骤 4：成本估算使用模型价格**

增加模型价格字段：

```text
prompt_price_per_1k
completion_price_per_1k
image_price_per_call
```

- [ ] **步骤 5：运行验证**

运行：

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：编译通过。

- [ ] **步骤 6：Commit**

```bash
git add backend scripts/regression_check.py
git commit -m "feat: secure ai gateway logs and costs"
```

---

## 任务 9：拆分前端入口和 API 层

**文件：**
- 修改：`frontend/src/App.vue`
- 创建：`frontend/src/router.js`
- 创建：`frontend/src/state.js`
- 创建：`frontend/src/api/auth.js`
- 创建：`frontend/src/api/admin.js`
- 创建：`frontend/src/api/workbench.js`
- 创建：`frontend/src/api/projects.js`
- 创建：`frontend/src/api/runs.js`
- 创建：`frontend/src/views/LoginView.vue`
- 创建：`frontend/src/views/ForcePasswordChangeView.vue`
- 创建：`frontend/src/views/WorkbenchShell.vue`
- 创建：`frontend/src/views/AdminShell.vue`

- [ ] **步骤 1：实现轻量路由**

`router.js`：

```js
export function currentRoute() {
  const path = window.location.pathname
  if (path.startsWith('/admin')) return { area: 'admin', path }
  if (path.startsWith('/workbench')) return { area: 'workbench', path }
  if (path.startsWith('/tools')) return { area: 'tool', path }
  return { area: 'workbench', path: '/workbench' }
}
```

- [ ] **步骤 2：拆分 API 层**

所有 API 请求统一处理：

```js
if (response.status === 401) logoutAndGoLogin()
if (response.status === 403 && payload.code === 'PASSWORD_CHANGE_REQUIRED') goForcePasswordChange()
```

- [ ] **步骤 3：App.vue 只分发入口**

`App.vue` 保留：

```vue
<template>
  <LoginView v-if="!authed" />
  <ForcePasswordChangeView v-else-if="mustChangePassword" />
  <AdminShell v-else-if="route.area === 'admin'" />
  <WorkbenchShell v-else />
</template>
```

- [ ] **步骤 4：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建通过。

- [ ] **步骤 5：Commit**

```bash
git add frontend/src
git commit -m "feat: split frontend workbench and admin shells"
```

---

## 任务 10：实现前台工作台、工具容器、项目和运行记录页面

**文件：**
- 创建：`frontend/src/views/workbench/WorkbenchHome.vue`
- 创建：`frontend/src/views/workbench/ToolContainer.vue`
- 创建：`frontend/src/views/workbench/ProjectList.vue`
- 创建：`frontend/src/views/workbench/ProjectDetail.vue`
- 创建：`frontend/src/views/workbench/RunRecords.vue`
- 修改：`frontend/src/styles.css`

- [ ] **步骤 1：工作台首页只展示有权限工具**

页面加载 `/api/workbench/bootstrap`，展示：

```text
AI 工具分类
用户可见工具
工具状态
最近使用
项目选择入口
```

- [ ] **步骤 2：工具容器统一状态**

`ToolContainer.vue` 支持：

```text
loading
open_failed
maintenance
coming_soon
forbidden
iframe
static
external demo warning
```

正式启动前必须选择项目。

- [ ] **步骤 3：项目详情展示默认任务和结果**

项目页展示：

```text
项目基础信息
商品信息
成员列表
默认任务
项目结果 ACTIVE 列表
REMOVED 默认隐藏
```

- [ ] **步骤 4：运行记录筛选和详情**

支持筛选：

```text
toolKey
projectId
status
date range
```

详情展示事件、输出摘要、文件快照、AI 日志摘要。

- [ ] **步骤 5：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建通过。

- [ ] **步骤 6：Commit**

```bash
git add frontend/src
git commit -m "feat: add workbench project and run pages"
```

---

## 任务 11：实现后台管理页面

**文件：**
- 创建：`frontend/src/views/admin/UserAdmin.vue`
- 创建：`frontend/src/views/admin/DepartmentAdmin.vue`
- 创建：`frontend/src/views/admin/RoleAdmin.vue`
- 创建：`frontend/src/views/admin/ToolAdmin.vue`
- 创建：`frontend/src/views/admin/ModelAdmin.vue`
- 创建：`frontend/src/views/admin/DeploymentAdmin.vue`
- 创建：`frontend/src/views/admin/LogAdmin.vue`
- 修改：`frontend/src/views/AdminShell.vue`
- 修改：`frontend/src/styles.css`

- [ ] **步骤 1：后台导航按权限点显示**

导航项映射：

```text
admin:user:read -> 用户管理
admin:department:read -> 组织部门
admin:role:read -> 角色权限
admin:tool:read -> 工具管理
admin:model:read -> 模型配置
admin:deploy:read -> 部署门禁
admin:ai-log:read/admin:log:read -> 日志审计
```

- [ ] **步骤 2：角色权限页面敏感权限二次确认**

勾选敏感权限时弹确认：

```text
你正在为该角色授予敏感权限，可能允许用户查看模型配置、AI 调用日志、用户数据或修改系统配置。请确认是否继续。
```

- [ ] **步骤 3：用户管理显示临时密码一次**

新增用户和重置密码成功后展示临时密码。刷新后不再出现。

- [ ] **步骤 4：工具管理支持状态和工具授权入口**

工具状态映射：

```text
ONLINE / MAINTENANCE / COMING_SOON / DISABLED
```

- [ ] **步骤 5：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建通过。

- [ ] **步骤 6：Commit**

```bash
git add frontend/src
git commit -m "feat: add admin management pages"
```

---

## 任务 12：更新工具接入规范、SDK 和 manifest 校验

**文件：**
- 修改：`developer-skill/ai-tool-integration/SKILL.md`
- 修改：`developer-skill/ai-tool-integration/references/api-contract.md`
- 修改：`developer-skill/ai-tool-integration/references/tool-integration.md`
- 修改：`developer-skill/ai-tool-integration/references/postmessage-contract.md`
- 修改：`developer-skill/ai-tool-integration/scripts/validate-tool-manifest.js`
- 修改：`developer-skill/ai-tool-integration/examples/static-html-tool/index.html`
- 修改：`developer-skill/ai-tool-integration/examples/static-html-tool/tool-manifest.json`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/dev/DeveloperController.java`

- [ ] **步骤 1：文档明确 projectId 必填**

`api-contract.md` 的 startRun 示例：

```json
{
  "projectId": "proj_xxx",
  "toolKey": "consumer-insight",
  "inputSummary": "消费者洞察分析"
}
```

- [ ] **步骤 2：SDK 使用当前登录态**

SDK 不要求工具传 `userId`：

```js
const run = await YiqiToolbench.startRun({ projectId, toolKey, inputSummary })
const ai = await YiqiToolbench.chat({ runId: run.runId, toolKey, model, params })
await YiqiToolbench.finishRun(run.runId, { status: 'success', outputSummary, outputData })
```

- [ ] **步骤 3：manifest 校验和服务端一致**

正式工具必须：

```text
callbackEnabled=true
aiGatewayRequired=true
requiredApis 包含 /api/tool-runs/start
requiredApis 包含 /api/tool-runs/{runId}/finish
requiredApis 包含 /api/ai/*
integrationType != external
directModelEndpoints 为空
```

- [ ] **步骤 4：运行 manifest 校验**

运行：

```bash
node developer-skill/ai-tool-integration/scripts/validate-tool-manifest.js developer-skill/ai-tool-integration/examples/static-html-tool/tool-manifest.json
```

预期：通过。

- [ ] **步骤 5：Commit**

```bash
git add developer-skill backend/src/main/java/com/yiqi/aitoolbench/dev
git commit -m "docs: align tool integration contract with project runs"
```

---

## 任务 13：完整回归脚本和端到端验证

**文件：**
- 修改：`scripts/regression_check.py`
- 修改：`doc/一期/AI工具台上线部署与验收清单.md`

- [ ] **步骤 1：重写回归主流程**

脚本流程：

```text
未登录校验
管理员登录
创建部门
创建角色
配置权限
创建用户
强制改密
普通成员登录
创建项目或加入项目
查询默认任务
启动工具
上传文件
调用 AI 网关
finishRun 成功
查询 project_result
软删除和恢复
部署门禁 approved/rejected
普通成员访问后台失败
禁用用户 session 失效
```

- [ ] **步骤 2：启动后端服务**

运行：

```bash
cd backend
java -Dserver.port=18080 -jar target/ai-toolbench-0.1.0.jar
```

预期：服务监听 `18080`。

- [ ] **步骤 3：执行回归**

运行：

```bash
python3 scripts/regression_check.py http://localhost:18080
```

预期：

```text
All regression checks passed.
```

- [ ] **步骤 4：前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建通过。

- [ ] **步骤 5：Commit**

```bash
git add scripts doc/一期/AI工具台上线部署与验收清单.md
git commit -m "test: add full delivery regression checks"
```

---

## 任务 14：最终验收和收尾

**文件：**
- 修改：`README.md`（如果仓库存在并需要）
- 修改：`doc/一期/AI工具台上线部署与验收清单.md`

- [ ] **步骤 1：运行后端打包**

```bash
cd backend
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -DskipTests package
```

预期：构建成功。

- [ ] **步骤 2：运行前端构建**

```bash
cd frontend
npm run build
```

预期：构建成功。

- [ ] **步骤 3：运行完整回归**

```bash
python3 scripts/regression_check.py http://localhost:18080
```

预期：所有检查通过。

- [ ] **步骤 4：检查敏感信息**

运行：

```bash
rg -n "sk-|apiKey|Authorization|password|token|cookie" backend frontend developer-skill scripts
```

预期：只允许出现文档示例、masked 字段、测试用临时密码逻辑，不允许真实 Key 或日志明文。

- [ ] **步骤 5：检查工作区**

```bash
git status --short
```

预期：只剩用户明确保留的未提交文件，当前实现相关文件已提交。

- [ ] **步骤 6：Commit**

```bash
git add README.md doc/一期/AI工具台上线部署与验收清单.md
git commit -m "docs: update delivery verification guide"
```

---

## 执行顺序约束

1. 任务 1 到任务 4 是权限和组织底座，必须先完成。
2. 任务 5 到任务 8 是业务闭环，依赖权限上下文。
3. 任务 9 到任务 11 是前端拆分，依赖后端接口稳定。
4. 任务 12 可以在任务 6 后并行执行。
5. 任务 13 和任务 14 必须最后执行。

## 风险控制

- 每个任务必须单独 commit，方便回滚。
- 所有权限判断必须在后端做，前端隐藏入口只作为体验。
- 不允许继续扩大 `ApiController`，新增业务必须进入对应 Controller/Service。
- 旧接口可兼容，但新主流程以 `/api/workbench/**`、`/api/admin/**` 和项目绑定工具运行为准。
- 明文密码、模型 Key、临时密码不得进入操作日志。
- `project_result` 必须保存快照，不能只引用 `tool_run`。
