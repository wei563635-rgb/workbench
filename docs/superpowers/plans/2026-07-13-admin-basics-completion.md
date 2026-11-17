# 后台基础管理闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 补齐用户编辑、用户多部门与主部门、组织层级校验，以及后台列表服务端分页。

**架构：** 以现有 `organization_department` 和 `user_department` 作为当前组织数据源，`sort_order=0` 表示主部门。后台列表接口统一返回分页对象，组织树仍一次加载完整层级，前端使用统一分页控件驱动各管理列表。

**技术栈：** Spring Boot 3、JdbcTemplate、Vue 3、Node test、JUnit 5、H2、PostgreSQL。

---

### 任务 1：分页基础契约

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/admin/PageResponse.java`
- 创建：`backend/src/test/java/com/yiqi/aitoolbench/admin/PageResponseTest.java`

- [ ] 先测试页码、每页数量、总数、总页数和越界空页。
- [ ] 实现最大每页 100 条的分页对象工厂。
- [ ] 运行 `mvn -Dtest=PageResponseTest test`。

### 任务 2：用户编辑与部门归属

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/user/UserRepository.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/ApiControllerUserManagementTest.java`

- [ ] 测试修改姓名、角色、多部门和主部门后返回值及持久化结果。
- [ ] 新增 `PUT /api/admin/users/{id}`，账号保持不可修改。
- [ ] 校验部门存在、启用、主部门属于已选部门，且至少选择一个主部门。
- [ ] 角色与部门更新写操作日志，更新后失效旧权限版本。

### 任务 3：组织层级保护

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/OrganizationHierarchyTest.java`

- [ ] 测试部门不能选择自己或后代作为上级。
- [ ] 保存前遍历父链检测循环。
- [ ] 保持一期只启停、不物理删除。

### 任务 4：后台列表服务端分页

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/admin/AdminController.java`
- 修改：`frontend/src/api.js`

- [ ] 用户、角色、工具、模型、部署、AI 日志、操作日志接口接受 `page/pageSize`。
- [ ] 所有接口返回统一 `{items,page,pageSize,total,totalPages}`。
- [ ] 保留组织树完整列表接口。

### 任务 5：用户编辑界面与统一分页控件

**文件：**
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/adminManagement.test.js`

- [ ] 用户行增加编辑按钮和编辑弹窗。
- [ ] 支持姓名、角色、多部门勾选和主部门选择，账号只读。
- [ ] 用户、角色、工具、模型、部署、AI 日志、操作日志接入统一分页控件。
- [ ] 分页切换只请求当前管理模块。

### 任务 6：构建、部署与验收

**文件：**
- 不新增业务文件。

- [ ] 运行后端完整测试与打包。
- [ ] 运行前端测试与生产构建。
- [ ] 备份线上 JAR 和前端目录。
- [ ] 部署后验收用户编辑、部门归属、循环校验和分页接口。
- [ ] 检查 systemd、nginx 和错误日志。
