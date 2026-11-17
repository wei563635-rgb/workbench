# AI 工作台问题修复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 统一项目真实数据模型，补齐后台逻辑删除、项目封面、项目看板和运行记录，并让所有统计来自数据库事实数据。

**架构：** 后端以 `project/project_product/project_task/project_result/tool_run/ai_call_log` 为唯一业务来源，提供面向页面的分页查询 DTO；前端将大页面中的项目和运行逻辑拆入独立视图辅助模块及组件。逻辑删除通过仓储层默认过滤和服务层引用校验实现。

**技术栈：** Java 17、Spring Boot、JdbcTemplate、PostgreSQL/H2、Vue 3、Element Plus、Vite、Node Test Runner。

---

## 文件结构

- `backend/src/main/java/com/yiqi/aitoolbench/project/ProjectRepository.java`：项目主数据、商品、任务和聚合查询。
- `backend/src/main/java/com/yiqi/aitoolbench/project/ProjectQueryService.java`：项目页面和项目看板分页 DTO 与统计口径。
- `backend/src/main/java/com/yiqi/aitoolbench/run/RunQueryService.java`：完成运行、员工主部门和资源消耗查询。
- `backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetRepository.java`：封面元数据创建、替换和逻辑删除。
- `backend/src/main/java/com/yiqi/aitoolbench/user/UserRepository.java`：用户、角色逻辑删除和有效数据过滤。
- `backend/src/main/java/com/yiqi/aitoolbench/org/DepartmentRepository.java`：部门逻辑删除和引用校验。
- `backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`：角色删除和自动编码接口。
- `backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`：项目、看板、封面和运行分页接口。
- `frontend/src/api.js`：新增分页、删除、项目 multipart 和看板接口。
- `frontend/src/projectView.js`：项目筛选、统计和任务分栏映射。
- `frontend/src/runView.js`：运行筛选、资源消耗和详情映射。
- `frontend/src/App.vue`：页面结构和交互编排。
- `frontend/src/styles.css`：设计稿对应布局及响应式样式。

### 任务 1：后台逻辑删除

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/user/UserRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/org/DepartmentRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/AdminLogicalDeleteTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void deletedUserCannotLoginAndIsExcludedFromAdminList() {
    delete("/api/admin/users/u_demo");
    assertThat(login("demo", "demo123").status()).isEqualTo(401);
    assertThat(adminUsers()).noneMatch(user -> user.id().equals("u_demo"));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd backend && mvn -Dtest=AdminLogicalDeleteTest test`
预期：FAIL，删除接口不存在或已删除记录仍可查询。

- [ ] **步骤 3：实现逻辑删除和引用阻断**

为 `sys_user`、`sys_role`、`organization_department` 增加 `deleted_at/deleted_by`；所有有效查询补 `deleted_at is null`；部门和角色删除前检查有效引用；删除用户时递增 session version 并禁用账号。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd backend && mvn -Dtest=AdminLogicalDeleteTest test`
预期：PASS。

### 任务 2：统一项目主数据和封面

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/file/FileAssetRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/StorageService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/ProjectCrudTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void projectCreatePersistsProductFieldsAndCover() {
    var project = createProject("新品项目", "商品 A", "image/webp", 1024);
    assertThat(project.productName()).isEqualTo("商品 A");
    assertThat(project.coverUrl()).isNotBlank();
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd backend && mvn -Dtest=ProjectCrudTest test`
预期：FAIL，当前请求只支持名称、负责人、成员和摘要。

- [ ] **步骤 3：实现正式项目 DTO 与 multipart 上传**

扩展项目和商品字段，添加封面引用；校验 MIME、扩展名和 5MB 限制；项目更新支持替换和删除封面；停用 `project_board` 读写路径并迁移现有测试数据。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd backend && mvn -Dtest=ProjectCrudTest test`
预期：PASS。

### 任务 3：实现真实项目统计和独立项目看板

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectQueryService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/project/ProjectRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/ProjectStatisticsTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void statisticsAggregateTasksResultsTokensAndImages() {
    seedCompletedTask();
    seedProjectResult();
    seedAiCall(120, List.of("a.png", "b.png"));
    assertThat(projectDetail().progress()).isEqualTo(50);
    assertThat(projectDetail().resultCount()).isEqualTo(1);
    assertThat(projectDetail().tokenCount()).isEqualTo(120);
    assertThat(projectDetail().imageCount()).isEqualTo(2);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd backend && mvn -Dtest=ProjectStatisticsTest test`
预期：FAIL，现有项目统计来自静态字段。

- [ ] **步骤 3：实现聚合和分页接口**

实现项目详情聚合、看板总计和列表分页；支持时间范围、关键词、部门、品牌、创建人；无任务进度返回 0；查询遵守当前用户数据权限。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd backend && mvn -Dtest=ProjectStatisticsTest test`
预期：PASS。

### 任务 4：实现运行记录查询模型

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/run/RunQueryService.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/run/ToolRunRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/RunQueryServiceTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void runListOnlyContainsFinishedAiRunsAndUsesPrimaryDepartment() {
    seedRunningRun();
    seedFinishedAiRun("u_001", "dept_primary");
    var page = queryRuns("u_001", "dept_primary", "success");
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().getFirst().departmentId()).isEqualTo("dept_primary");
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd backend && mvn -Dtest=RunQueryServiceTest test`
预期：FAIL，当前运行列表包含进行中记录并按工具部门映射。

- [ ] **步骤 3：实现运行聚合**

只查询 success/failed 且存在 AI 调用或生成结果的运行；按 run_id 聚合 Token 和图片；员工部门使用运行快照，旧数据回退到用户主部门；实现关键词、员工、部门、状态和分页。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd backend && mvn -Dtest=RunQueryServiceTest test`
预期：PASS。

### 任务 5：补齐后台前端交互

**文件：**
- 修改：`frontend/src/api.js`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/adminManagement.test.js`

- [ ] **步骤 1：添加逻辑删除、角色隐藏编码和创建防重测试**
- [ ] **步骤 2：运行 `cd frontend && npm test -- adminManagement.test.js`，确认失败**
- [ ] **步骤 3：实现删除确认、自动角色编码、中文权限、成功弹窗和提交锁**
- [ ] **步骤 4：运行 `cd frontend && npm test -- adminManagement.test.js`，确认通过**

### 任务 6：重做项目页面和项目看板

**文件：**
- 修改：`frontend/src/api.js`
- 修改：`frontend/src/projectView.js`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/projectView.test.js`
- 测试：`frontend/src/__tests__/dashboardStats.test.js`

- [ ] **步骤 1：添加默认选择、真实统计、筛选分页和封面表单测试**
- [ ] **步骤 2：运行 `cd frontend && npm test -- projectView.test.js dashboardStats.test.js`，确认失败**
- [ ] **步骤 3：按设计稿实现项目列表、详情、任务分栏及独立看板页面**
- [ ] **步骤 4：运行对应前端测试，确认通过**

### 任务 7：重做运行记录页面

**文件：**
- 修改：`frontend/src/api.js`
- 修改：`frontend/src/runView.js`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/runView.test.js`

- [ ] **步骤 1：添加员工/主部门/状态筛选、服务端分页和详情操作测试**
- [ ] **步骤 2：运行 `cd frontend && npm test -- runView.test.js`，确认失败**
- [ ] **步骤 3：实现设计稿表格、筛选、分页和详情抽屉，移除关联状态筛选**
- [ ] **步骤 4：运行对应前端测试，确认通过**

### 任务 8：全量验证和测试环境部署

**文件：**
- 修改：`scripts/regression_check.py`
- 修改：`doc/一期/AI工具台上线部署与验收清单.md`

- [ ] **步骤 1：运行后端全量测试**

运行：`cd backend && mvn test`
预期：全部通过。

- [ ] **步骤 2：运行前端测试和构建**

运行：`cd frontend && npm test && npm run build`
预期：测试全部通过且 Vite 构建成功。

- [ ] **步骤 3：运行回归脚本**

运行：`python3 scripts/regression_check.py`
预期：登录、管理、项目、统计和运行记录检查全部通过。

- [ ] **步骤 4：独立端口部署并视觉检查**

在测试服务器现有独立端口部署前后端，验证 `/workbench/projects`、`/workbench/project-board`、`/workbench/runs` 和 `/admin`；使用桌面及窄屏截图检查设计稿一致性。

