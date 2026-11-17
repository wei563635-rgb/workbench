# 品牌 HTML 管理实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 后台可维护“组织部门 + 品牌”唯一的 HTML 页面，工作台用户只能按本人直接归属部门和品牌检索并完整运行页面内容。

**架构：** `BrandPageRepository` 管理包含 `department_id` 的页面元数据，复用现有 `StorageService` 保存 HTML 文件。`BrandPageAccessPolicy` 对列表与内容直链执行统一的精确部门判断；管理员绕过部门限制，旧的未关联记录保持管理员可见。前端提供部门/品牌筛选、后台组织树关联与沉浸式全屏预览。

## 2026-07-24 部门隔离增量

- [x] 新增 `department_id`，唯一索引迁移为 `(department_id, brand_name)`。
- [x] 上传必选存在且启用的组织部门，同部门同品牌替换，不同部门可同名。
- [x] 普通用户按直接归属部门精确匹配，多部门取并集，不继承组织父子关系。
- [x] 管理员可管理全部，历史 `department_id is null` 页面仅管理员可见。
- [x] 列表、部门筛选和 `/content` 直链使用同一权限策略，越权返回 403。
- [x] 登录用户信息返回 `departmentKeys`，前端筛选只展示本人可用部门。
- [x] 品牌预览隐藏平台导航并占满视口，提供返回和浏览器全屏按钮。
- [x] 新增后端策略/仓储测试和前端结构测试。
- [x] 后台列表支持编辑部门、品牌和可选 HTML 文件，并校验联合唯一冲突。
- [x] 后台预览记录来源，返回时回到后台品牌列表而不是工作台前台。

**技术栈：** Spring Boot 3、JdbcTemplate、现有本地文件存储、Vue 3、Vite、Element Plus。

---

## 文件结构

- 创建：`backend/src/main/java/com/yiqi/aitoolbench/BrandPageRepository.java` — 品牌页面表结构、分页、按 ID 查询、同品牌替换、逻辑删除。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java` — 品牌页面 API、权限检查、上传验证、HTML 内联响应。
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionCatalog.java` — 品牌页读/管理权限。
- 创建：`backend/src/test/java/com/yiqi/aitoolbench/BrandPageApiTest.java` — 后端权限、唯一替换及 HTML 响应测试。
- 修改：`frontend/src/adminPermissions.js` — 品牌页面后台菜单定义。
- 修改：`frontend/src/api.js` — 品牌页面检索、上传、删除请求封装。
- 修改：`frontend/src/App.vue` — 后台管理页、上传弹窗、前台检索入口、预览路由。
- 修改：`frontend/src/styles.css` — 检索结果、空态与 iframe 预览布局。
- 创建：`frontend/src/__tests__/brandPages.test.js` — 前端权限、路由和 iframe 完整执行能力测试。

### 任务 1：后端持久化与权限

**文件：**
- 创建：`backend/src/main/java/com/yiqi/aitoolbench/BrandPageRepository.java`
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/security/PermissionCatalog.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/BrandPageApiTest.java`

- [ ] **步骤 1：编写失败的仓储测试**

```java
@Test
void replaceByBrandKeepsOneActivePage() {
    repository.saveOrReplace("川井", "file_abc", "brand.html", "u_admin");
    repository.saveOrReplace("川井", "file_def", "brand-v2.html", "u_admin");
    assertThat(repository.search("川井")).singleElement()
            .extracting(BrandPageRepository.BrandPage::fileId).isEqualTo("file_def");
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q test -Dtest=BrandPageApiTest`

预期：FAIL，提示 `BrandPageRepository` 不存在。

- [ ] **步骤 3：实现表和唯一替换**

```java
public record BrandPage(String id, String brandName, String fileId, String fileName,
                        String createdBy, Instant createdAt, Instant updatedAt) {}

public BrandPage saveOrReplace(String brandName, String fileId, String fileName, String createdBy) {
    // 查询有效同品牌记录；存在则 update，不存在则 insert，始终返回最新记录。
}
```

表使用 `brand_name` 活动记录唯一约束，删除采用 `deleted_at` 标记；查询按 `updated_at desc` 排序。

- [ ] **步骤 4：加入权限目录**

```java
p("brand:page:read", "查看品牌 HTML 页面", "品牌页面", false),
p("admin:brand-page:write", "管理品牌 HTML 页面", "品牌页面", true),
```

管理员角色初始化时自动得到两个权限。

- [ ] **步骤 5：运行后端单测**

运行：`mvn -q test -Dtest=BrandPageApiTest`

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/yiqi/aitoolbench/BrandPageRepository.java \
  backend/src/main/java/com/yiqi/aitoolbench/security/PermissionCatalog.java \
  backend/src/test/java/com/yiqi/aitoolbench/BrandPageApiTest.java
git commit -m "feat: persist brand html pages"
```

### 任务 2：品牌页面 API 和 HTML 安全响应

**文件：**
- 修改：`backend/src/main/java/com/yiqi/aitoolbench/ApiController.java`
- 测试：`backend/src/test/java/com/yiqi/aitoolbench/BrandPageApiTest.java`

- [ ] **步骤 1：编写失败的 API 测试**

```java
mockMvc.perform(get("/api/brand-pages").header("Authorization", adminToken))
        .andExpect(status().isOk());
mockMvc.perform(get("/api/brand-pages/{id}/content", pageId).header("Authorization", adminToken))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q test -Dtest=BrandPageApiTest`

预期：FAIL，响应为 404。

- [ ] **步骤 3：实现接口及校验**

```java
@GetMapping("/api/brand-pages")
public List<BrandPage> searchBrandPages(..., @RequestParam(required = false) String keyword) {
    permissionService.requirePermission(requireContext(authorization), "brand:page:read");
    return brandPageRepository.search(keyword);
}

@PostMapping(value = "/api/admin/brand-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public BrandPage uploadBrandPage(..., @RequestParam String brandName, @RequestParam MultipartFile file) { ... }
```

上传校验品牌非空、文件非空和 `.html` 后缀，不限制品牌 HTML 文件大小；内容接口通过 `StorageService.load` 返回 `text/html`、`inline`、`nosniff`。普通附件继续受 `StorageService` 通用大小配置限制。

- [ ] **步骤 4：运行 API 测试**

运行：`mvn -q test -Dtest=BrandPageApiTest`

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/yiqi/aitoolbench/ApiController.java backend/src/test/java/com/yiqi/aitoolbench/BrandPageApiTest.java
git commit -m "feat: expose brand html page APIs"
```

### 任务 3：后台管理和前台检索预览

**文件：**
- 修改：`frontend/src/adminPermissions.js`
- 修改：`frontend/src/api.js`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/styles.css`
- 测试：`frontend/src/__tests__/brandPages.test.js`

- [ ] **步骤 1：编写失败的前端结构测试**

```js
test('品牌页面预览允许执行上传 HTML 的全部脚本和交互', () => {
  assert.doesNotMatch(app, /iframe[^>]+sandbox=/)
  assert.match(app, /adminSection === 'brandPages'/)
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`node --test src/__tests__/brandPages.test.js`

预期：FAIL，未找到品牌页面结构。

- [ ] **步骤 3：实现前端 API 与权限菜单**

```js
Object.freeze({ key: 'brandPages', icon: '▧', label: '品牌 HTML',
  readPermission: 'brand:page:read', writePermission: 'admin:brand-page:write' })

export async function uploadBrandPage(brandName, file) {
  const form = new FormData()
  form.append('brandName', brandName)
  form.append('file', file)
  return postMultipart('/api/admin/brand-pages', form)
}
```

- [ ] **步骤 4：实现页面和路由**

工作台页面路由为 `/brand-pages`；详情路由为 `/brand-pages/{id}`。预览 iframe 不设置 `sandbox`，允许上传 HTML 的脚本和交互完整运行。后台列表使用现有表格、分页、`AppDialog` 上传弹窗和删除确认交互。

- [ ] **步骤 5：运行前端测试与构建**

运行：`node --test src/__tests__/brandPages.test.js && npm run build`

预期：测试全部 PASS，Vite 构建成功。

- [ ] **步骤 6：Commit**

```bash
git add frontend/src/adminPermissions.js frontend/src/api.js frontend/src/App.vue frontend/src/styles.css frontend/src/__tests__/brandPages.test.js
git commit -m "feat: manage and preview brand html pages"
```

### 任务 4：集成验证与发布

**文件：**
- 修改：无

- [ ] **步骤 1：运行模块回归**

运行：`mvn -q test -Dtest=BrandPageApiTest && npm run build`

预期：后端测试和前端构建均成功。

- [ ] **步骤 2：发布并验证**

将后端 JAR 和前端 `dist` 发布到生产环境；验证 `GET /api/public/stats` 返回 200，登录后台后可见“品牌 HTML”，上传一份自包含 HTML 后能在工作台按品牌搜索并预览。

- [ ] **步骤 3：Commit 状态核验**

运行：`git status --short`

预期：除已有无关 `.superpowers/` 外无未提交业务文件。
