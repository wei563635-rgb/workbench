# 管理后台前端权限对齐实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让后台菜单、页面请求和写操作入口严格服从当前用户的后端权限码，避免无权菜单和 403 请求。

**架构：** 新建纯函数权限模块，集中维护后台模块的读写权限映射；`App.vue` 只消费该映射生成菜单、守卫页面请求并控制写按钮。后端拦截器保持最终授权边界，不修改权限数据和接口规则。

**技术栈：** Vue 3 Composition API、JavaScript ES Modules、Node.js `node:test`、Vite

---

## 文件结构

- 创建：`frontend/src/adminPermissions.js`——后台模块元数据和纯权限判断函数。
- 创建：`frontend/src/__tests__/adminPermissions.test.js`——权限映射的单元测试。
- 修改：`frontend/src/App.vue`——使用可见菜单、读取守卫和写按钮权限。
- 修改：`frontend/src/__tests__/adminManagement.test.js`——模板与请求守卫的集成回归测试。

### 任务 1：建立唯一后台权限映射

**文件：**
- 创建：`frontend/src/adminPermissions.js`
- 创建：`frontend/src/__tests__/adminPermissions.test.js`

- [ ] **步骤 1：编写失败的权限映射测试**

创建测试，覆盖普通成员、审计员、管理员语义和未知模块：

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ADMIN_SECTION_DEFINITIONS,
  canReadAdminSection,
  canWriteAdminSection,
  visibleAdminSections
} from '../adminPermissions.js'

test('普通成员只看到概览、组织架构和工具管理', () => {
  const permissions = ['workbench:access', 'admin:department:read', 'admin:tool:read']
  assert.deepEqual(
    visibleAdminSections(permissions).map((item) => item.key),
    ['overview', 'organization', 'tools']
  )
})

test('读写权限分别控制模块可见性和修改能力', () => {
  assert.equal(canReadAdminSection(['admin:model:read'], 'models'), true)
  assert.equal(canWriteAdminSection(['admin:model:read'], 'models'), false)
  assert.equal(canWriteAdminSection(['admin:model:write'], 'models'), true)
  assert.equal(canReadAdminSection(['admin:model:read'], 'missing'), false)
  assert.equal(ADMIN_SECTION_DEFINITIONS.length, 9)
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd frontend
node --test src/__tests__/adminPermissions.test.js
```

预期：FAIL，报错 `ERR_MODULE_NOT_FOUND`，因为 `adminPermissions.js` 尚未创建。

- [ ] **步骤 3：实现权限映射纯函数**

创建 `frontend/src/adminPermissions.js`：

```js
export const ADMIN_SECTION_DEFINITIONS = Object.freeze([
  { key: 'overview', icon: '▣', label: '概览', readPermission: null, writePermission: null },
  { key: 'deployments', icon: '⇪', label: '部署门禁', readPermission: 'admin:deploy:read', writePermission: 'admin:deploy:approve' },
  { key: 'users', icon: '◉', label: '用户管理', readPermission: 'admin:user:read', writePermission: 'admin:user:write' },
  { key: 'organization', icon: '▤', label: '组织架构', readPermission: 'admin:department:read', writePermission: 'admin:department:write' },
  { key: 'roles', icon: '◆', label: '角色权限', readPermission: 'admin:role:read', writePermission: 'admin:role:write' },
  { key: 'tools', icon: '□', label: '工具管理', readPermission: 'admin:tool:read', writePermission: 'admin:tool:write' },
  { key: 'models', icon: '◇', label: '模型配置', readPermission: 'admin:model:read', writePermission: 'admin:model:write' },
  { key: 'aiLogs', icon: '≋', label: 'AI 日志', readPermission: 'admin:ai-log:read', writePermission: null },
  { key: 'operationLogs', icon: '☷', label: '操作日志', readPermission: 'admin:log:read', writePermission: null }
])

function permissionSet(permissions) {
  return new Set(Array.isArray(permissions) ? permissions : [])
}

export function hasAdminAccess(permissions) {
  return [...permissionSet(permissions)].some((code) => code.startsWith('admin:'))
}

export function canReadAdminSection(permissions, section) {
  const definition = ADMIN_SECTION_DEFINITIONS.find((item) => item.key === section)
  if (!definition) return false
  if (section === 'overview') return hasAdminAccess(permissions)
  return permissionSet(permissions).has(definition.readPermission)
}

export function canWriteAdminSection(permissions, section) {
  const definition = ADMIN_SECTION_DEFINITIONS.find((item) => item.key === section)
  return Boolean(definition?.writePermission && permissionSet(permissions).has(definition.writePermission))
}

export function visibleAdminSections(permissions) {
  return ADMIN_SECTION_DEFINITIONS.filter((item) => canReadAdminSection(permissions, item.key))
}
```

- [ ] **步骤 4：运行单元测试验证通过**

运行：

```bash
cd frontend
node --test src/__tests__/adminPermissions.test.js
```

预期：全部 PASS。

### 任务 2：对齐菜单、请求和写操作入口

**文件：**
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/__tests__/adminManagement.test.js`

- [ ] **步骤 1：编写失败的 App 权限集成测试**

在 `adminManagement.test.js` 增加以下契约检查：

```js
test('后台菜单和分页请求共享读取权限守卫', () => {
  assert.match(script, /const visibleAdminNavItems = computed\(\(\) => visibleAdminSections\(currentUser\.value\.permissions\)\)/)
  assert.match(template, /v-for="item in visibleAdminNavItems"/)
  assert.match(functionSource('openAdminSection'), /canReadAdminSection\(currentUser\.value\.permissions, section\)/)
  assert.match(functionSource('loadAdminPage'), /canReadAdminSection\(currentUser\.value\.permissions, section\)/)
})

test('后台写操作入口按模块写权限隐藏', () => {
  for (const section of ['deployments', 'users', 'organization', 'roles', 'tools', 'models']) {
    assert.match(template, new RegExp(`canWriteAdminSection\\(currentUser\\.permissions, '${section}'\\)`))
  }
})

test('无权模块在改变状态和请求接口前立即返回', () => {
  const openSection = functionSource('openAdminSection')
  assert.match(openSection, /if \(!canReadAdminSection\(currentUser\.value\.permissions, section\)\) return[\s\S]*adminSection\.value = section/)

  const loadPage = functionSource('loadAdminPage')
  assert.match(loadPage, /if \(!canReadAdminSection\(currentUser\.value\.permissions, section\)\) return[\s\S]*loadAdminPageApi\(/)
})
```

- [ ] **步骤 2：运行定向测试验证失败**

运行：

```bash
cd frontend
node --test --test-name-pattern='后台菜单和分页请求|后台写操作入口' src/__tests__/adminManagement.test.js
```

预期：FAIL，因为模板仍遍历固定 `adminNavItems`，函数尚无权限守卫。

- [ ] **步骤 3：接入可见菜单和读取守卫**

在 `App.vue` 导入权限模块：

```js
import {
  canReadAdminSection,
  canWriteAdminSection,
  hasAdminAccess,
  visibleAdminSections
} from './adminPermissions'
```

删除本地固定 `adminNavItems`，增加：

```js
const visibleAdminNavItems = computed(() => visibleAdminSections(currentUser.value.permissions))
const canAccessAdmin = computed(() => hasAdminAccess(currentUser.value.permissions))
```

模板菜单改为：

```vue
<li v-for="item in visibleAdminNavItems" ...>
```

在两个入口最前面加入守卫：

```js
function openAdminSection(section) {
  if (!canReadAdminSection(currentUser.value.permissions, section)) return
  // 保留现有切换和加载逻辑
}

async function loadAdminPage(section, page = 1) {
  if (!canReadAdminSection(currentUser.value.permissions, section)) return
  // 保留现有分页逻辑
}
```

- [ ] **步骤 4：按写权限隐藏操作入口**

为部署、用户、组织、角色、工具、模型的写操作容器或按钮增加对应条件，例如：

```vue
<button v-if="canWriteAdminSection(currentUser.permissions, 'users')" ...>新增用户</button>
<button v-if="canWriteAdminSection(currentUser.permissions, 'roles')" ...>编辑</button>
<td v-if="canWriteAdminSection(currentUser.permissions, 'tools')" class="table-actions">...</td>
```

如果整列都是写操作，表头和单元格必须使用同一个权限条件，避免只读用户看到空操作列。组织和角色编辑弹窗只能由已显示的写入口打开，现有后端 403 防线不变。

- [ ] **步骤 5：处理概览快捷入口和当前模块回退**

概览中的“部署门禁”和“模型配置”快捷入口分别使用 `admin:deploy:read`、`admin:model:read` 控制。新增同步函数：

```js
function ensureAccessibleAdminSection() {
  if (canReadAdminSection(currentUser.value.permissions, adminSection.value)) return
  adminSection.value = visibleAdminSections(currentUser.value.permissions)[0]?.key || 'overview'
}
```

在登录、恢复会话和路由应用完成权限载入后调用，确保不会停留在无权模块。

- [ ] **步骤 6：运行定向测试验证通过**

运行：

```bash
cd frontend
node --test src/__tests__/adminPermissions.test.js src/__tests__/adminManagement.test.js
```

预期：全部 PASS，且无权请求守卫测试证明 `loadAdminPageApi` 不会执行。

### 任务 3：完整验证、提交与部署

**文件：**
- 验证：`frontend/src/**/*.js`
- 验证：`frontend/src/**/*.vue`

- [ ] **步骤 1：运行完整前端测试**

运行：

```bash
cd frontend
npm test
```

预期：所有测试 PASS，失败数为 0。

- [ ] **步骤 2：运行生产构建和差异检查**

运行：

```bash
cd frontend
npm run build
cd ..
git diff --check
```

预期：Vite 构建退出码为 0，`git diff --check` 无输出。

- [ ] **步骤 3：提交实现**

```bash
git add frontend/src/adminPermissions.js \
  frontend/src/App.vue \
  frontend/src/__tests__/adminPermissions.test.js \
  frontend/src/__tests__/adminManagement.test.js
git commit -m "fix: align admin UI with permissions"
```

- [ ] **步骤 4：部署前端并在线验证**

复用当前生产前端原子发布流程：打包 `frontend/dist`、校验 SHA-256、上传到 `ai-toolbench-prod`、备份 `/opt/workbench/frontend`、切换新目录并运行 `nginx -t`。

在线检查：

```bash
curl -fsS http://120.26.221.135:18081/
curl -fsS http://120.26.221.135:18081/api/public/stats
```

预期：首页引用本次构建的新 JS/CSS 哈希，公共统计接口返回 JSON。使用普通成员登录时只显示概览、组织架构、工具管理，Network 中不再出现无权日志接口的 403 请求。
