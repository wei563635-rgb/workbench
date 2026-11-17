# 一起互动 AI 工作台 UI 重构实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在不改变接口和业务逻辑的前提下，让平台自有页面逐页对齐 `doc/一期/前端demo/一起互动AI工作台` UI 稿，并将后台新增/编辑统一为居中弹窗、详情统一为右侧抽屉。

**架构：** 保留 `App.vue` 中现有状态、API 调用和页面分支，只抽取无业务状态的弹窗与抽屉外壳组件。视觉令牌和页面样式集中在 `styles.css`，通过源文件契约测试保护关键 DOM、弹窗/抽屉语义及 UI 稿令牌，最终使用同视口浏览器逐页比对 UI 稿与实现。

**技术栈：** Vue 3、Vite 6、Element Plus、Lucide Vue、Node.js `node:test`、CSS3。

---

## 文件结构

### 新建文件

- `frontend/src/components/AppDialog.vue`：新增、编辑和确认操作共用的无业务状态居中弹窗外壳。
- `frontend/src/components/DetailDrawer.vue`：查看详情共用的无业务状态右侧抽屉外壳。
- `frontend/src/__tests__/uiFidelity.test.js`：验证 UI 稿关键结构、视觉令牌、居中弹窗和右侧抽屉契约。

### 修改文件

- `frontend/src/App.vue`：调整平台自有页面 DOM 结构；接入公共弹窗和抽屉；把组织、角色右侧常驻编辑区迁移到弹窗；保持业务函数和 API 调用不变。
- `frontend/src/styles.css`：按 UI 稿统一全局令牌、登录页、主框架、首页、项目、项目看板、运行记录、后台列表、弹窗、抽屉及响应式样式。
- `frontend/src/__tests__/projectView.test.js`：锁定项目新增/编辑仍为弹窗且字段不变。
- `frontend/src/__tests__/adminManagement.test.js`：锁定组织与角色改为弹窗，列表默认不渲染常驻编辑区。
- `frontend/src/__tests__/runView.test.js`：锁定运行详情继续使用右侧抽屉。

### 正式视觉基准（只读）

- `doc/一期/前端demo/一起互动AI工作台/登陆页面.html`
- `doc/一期/前端demo/一起互动AI工作台/工作台主页.html`
- `doc/一期/前端demo/一起互动AI工作台/项目页面.html`
- `doc/一期/前端demo/一起互动AI工作台/项目看板页面.html`
- `doc/一期/前端demo/一起互动AI工作台/运行记录页面.html`
- `doc/一期/前端demo/一起互动AI工作台/login.css`
- `doc/一期/前端demo/一起互动AI工作台/dashboard.css`
- `doc/一期/前端demo/一起互动AI工作台/styles.css`

## 任务 1：建立 UI 稿契约测试

**文件：**

- 创建：`frontend/src/__tests__/uiFidelity.test.js`
- 修改：`frontend/src/__tests__/runView.test.js`

- [ ] **步骤 1：编写失败的 UI 契约测试**

创建 `frontend/src/__tests__/uiFidelity.test.js`：

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')

test('平台使用 UI 稿的核心视觉令牌', () => {
  assert.match(css, /--ui-brand:\s*#5b55ea/i)
  assert.match(css, /--ui-canvas:\s*#f4f6f9/i)
  assert.match(css, /--ui-ink:\s*#171a2b/i)
  assert.match(css, /--ui-border:\s*#e7e9f2/i)
})

test('工作台保留 UI 稿的标志性欢迎卡结构', () => {
  assert.match(app, /hero-card-container/)
  assert.match(app, /bg-tabs/)
  assert.match(app, /hero-main-card/)
  assert.match(app, /tools-grid-new/)
})

test('新增编辑使用居中弹窗且详情使用右侧抽屉', () => {
  assert.match(app, /<AppDialog/)
  assert.match(app, /<DetailDrawer/)
  assert.match(css, /\.app-dialog-shell\s*\{[^}]*left:\s*50%[^}]*top:\s*50%[^}]*translate/s)
  assert.match(css, /\.detail-drawer-shell\s*\{[^}]*right:\s*0[^}]*height:\s*100(?:vh|dvh)/s)
})
```

在 `runView.test.js` 追加以下断言：

```js
test('运行详情继续使用右侧抽屉', () => {
  assert.match(app, /<DetailDrawer[^>]*:open="Boolean\(selectedRun\)"/s)
})
```

- [ ] **步骤 2：运行测试并确认新增契约失败**

运行：

```bash
cd frontend
npm test
```

预期：现有业务测试通过；新增测试因缺少 `--ui-brand`、`AppDialog` 和 `DetailDrawer` 而失败。

- [ ] **步骤 3：提交失败测试**

```bash
git add frontend/src/__tests__/uiFidelity.test.js frontend/src/__tests__/runView.test.js
git commit -m "test: define workbench ui fidelity contract"
```

## 任务 2：实现统一弹窗和详情抽屉外壳

**文件：**

- 创建：`frontend/src/components/AppDialog.vue`
- 创建：`frontend/src/components/DetailDrawer.vue`
- 修改：`frontend/src/App.vue:628-799`
- 修改：`frontend/src/styles.css:293-375`

- [ ] **步骤 1：实现无业务状态的居中弹窗组件**

创建 `frontend/src/components/AppDialog.vue`：

```vue
<template>
  <Teleport to="body">
    <div v-if="open" class="app-overlay" @click.self="closeFromBackdrop">
      <section class="app-dialog-shell" :class="`size-${size}`" role="dialog" aria-modal="true" :aria-labelledby="titleId">
        <header class="app-dialog-header">
          <div><h2 :id="titleId">{{ title }}</h2><p v-if="description">{{ description }}</p></div>
          <button type="button" class="app-close-button" aria-label="关闭" @click="$emit('close')">×</button>
        </header>
        <div class="app-dialog-body"><slot /></div>
        <footer v-if="$slots.footer" class="app-dialog-footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  size: { type: String, default: 'medium' },
  closeOnBackdrop: { type: Boolean, default: false }
})

const emit = defineEmits(['close'])
const titleId = computed(() => `dialog-${props.title.replace(/\s+/g, '-')}`)
function closeFromBackdrop() { if (props.closeOnBackdrop) emit('close') }
</script>
```

- [ ] **步骤 2：实现无业务状态的右侧抽屉组件**

创建 `frontend/src/components/DetailDrawer.vue`：

```vue
<template>
  <Teleport to="body">
    <div v-if="open" class="app-overlay drawer-overlay-layout" @click.self="$emit('close')">
      <aside class="detail-drawer-shell" :class="{ wide }" role="dialog" aria-modal="true" :aria-labelledby="titleId">
        <header class="detail-drawer-header">
          <div><h2 :id="titleId">{{ title }}</h2><p v-if="description">{{ description }}</p></div>
          <button type="button" class="app-close-button" aria-label="关闭" @click="$emit('close')">×</button>
        </header>
        <div class="detail-drawer-body"><slot /></div>
        <footer v-if="$slots.footer" class="detail-drawer-footer"><slot name="footer" /></footer>
      </aside>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  wide: { type: Boolean, default: false }
})

defineEmits(['close'])
const titleId = computed(() => `drawer-${props.title.replace(/\s+/g, '-')}`)
</script>
```

- [ ] **步骤 3：在 App.vue 注册组件并迁移现有通用浮层**

在 `App.vue` 的 import 区增加：

```js
import AppDialog from './components/AppDialog.vue'
import DetailDrawer from './components/DetailDrawer.vue'
```

把运行详情包裹替换为：

```vue
<DetailDrawer :open="Boolean(selectedRun)" title="运行结果详情" wide @close="selectedRun = null">
  <template v-if="selectedRun">
    <section class="result-card detail-section">
      <h4><span>i</span>基本信息</h4>
      <div class="detail-grid">
        <p><b>所属工具：</b>{{ selectedRun.tool }}</p><p><b>员工：</b>{{ selectedRun.user }}</p>
        <p><b>开始时间：</b>{{ selectedRun.time }}</p><p><b>耗时：</b>{{ selectedRun.duration }}</p>
        <p><b>模型：</b>{{ selectedRun.model }}</p><p><b>所属项目：</b>{{ selectedRun.project || '-' }}</p>
      </div>
    </section>
    <section class="result-card detail-section">
      <h4><span>≡</span>提示词输入（Prompt）</h4><pre class="prompt-box">{{ selectedRun.input }}</pre>
    </section>
    <section class="result-card detail-section">
      <h4><span>▧</span>产出附件（{{ selectedRunAttachments.length }}）</h4>
      <div v-if="selectedRunAttachments.length" class="attachment-grid">
        <a v-for="attachment in selectedRunAttachments" :key="attachment.key" :href="attachment.url" target="_blank" rel="noopener" class="attachment-item">
          <img v-if="attachment.kind === 'image'" :src="attachment.url" :alt="attachment.name" />
          <div v-else class="file-attachment">{{ attachment.name }}</div>
        </a>
      </div>
      <p v-else class="attachment-empty">该运行记录未上报产出附件</p>
      <button v-if="selectedRunAttachments.length" class="btn-secondary attachment-download" @click="openAllAttachments">全部打开</button>
    </section>
    <section class="result-card detail-section">
      <h4><span>{}</span>完整 JSON 返回</h4><pre class="json-box">{{ selectedRunJson }}</pre>
    </section>
  </template>
  <template #footer><button class="btn-secondary" @click="selectedRun = null">关闭</button></template>
</DetailDrawer>
```

本任务只迁移运行详情，其他现有居中浮层留到任务 6 一次性接入 `AppDialog`，避免同一块模板跨提交重复修改。

- [ ] **步骤 4：添加公共浮层样式**

在 `styles.css` 中加入：

```css
.app-overlay { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center; padding: 24px; background: rgba(23, 26, 43, .42); }
.app-dialog-shell { position: fixed; left: 50%; top: 50%; translate: -50% -50%; width: min(560px, calc(100vw - 32px)); max-height: 88dvh; display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--ui-border); border-radius: 16px; background: #fff; box-shadow: 0 28px 80px rgba(23, 26, 43, .22); }
.app-dialog-shell.size-large { width: min(840px, calc(100vw - 32px)); }
.app-dialog-header, .app-dialog-footer, .detail-drawer-header, .detail-drawer-footer { flex: 0 0 auto; padding: 20px 24px; background: #fff; }
.app-dialog-header, .detail-drawer-header { display: flex; justify-content: space-between; gap: 20px; border-bottom: 1px solid var(--ui-border); }
.app-dialog-footer, .detail-drawer-footer { display: flex; justify-content: flex-end; gap: 10px; border-top: 1px solid var(--ui-border); }
.app-dialog-body, .detail-drawer-body { min-height: 0; overflow: auto; padding: 24px; }
.detail-drawer-shell { position: fixed; top: 0; right: 0; width: min(520px, 96vw); height: 100dvh; display: flex; flex-direction: column; background: var(--ui-canvas); box-shadow: -12px 0 48px rgba(23, 26, 43, .18); }
.detail-drawer-shell.wide { width: min(720px, 96vw); }
.app-close-button { width: 36px; height: 36px; border-radius: 10px; background: #f2f3f7; color: var(--ui-muted); font-size: 22px; }
```

- [ ] **步骤 5：运行测试和构建**

```bash
cd frontend
npm test
npm run build
```

预期：与组织、角色弹窗有关的契约仍失败；其他测试通过；Vite 构建成功。

- [ ] **步骤 6：提交公共浮层组件**

```bash
git add frontend/src/components/AppDialog.vue frontend/src/components/DetailDrawer.vue frontend/src/App.vue frontend/src/styles.css
git commit -m "feat: unify dialogs and detail drawers"
```

## 任务 3：对齐登录页、主框架和工作台首页

**文件：**

- 修改：`frontend/src/App.vue:2-125`
- 修改：`frontend/src/styles.css:1-136,376-425`
- 测试：`frontend/src/__tests__/uiFidelity.test.js`

- [ ] **步骤 1：写入 UI 稿核心视觉令牌**

将 `styles.css` 顶部变量统一为：

```css
:root {
  --ui-brand: #5b55ea;
  --ui-canvas: #f4f6f9;
  --ui-ink: #171a2b;
  --ui-muted: #667085;
  --ui-border: #e7e9f2;
  --ui-success: #16a36a;
  --ui-danger: #e5484d;
  --ui-card: #ffffff;
  --ui-sidebar-width: 240px;
  font-family: "PingFang SC", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: var(--ui-ink);
  background: var(--ui-canvas);
}
```

- [ ] **步骤 2：逐项对齐登录页 UI 稿**

保留当前登录、首次改密、显示密码和记住登录逻辑；只调整 DOM 类名和 CSS，使其对应 `登陆页面.html` 的 `login-split-layout`、`login-left-brand`、`login-right-form`、`brand-stats` 和 `login-box`。不得复制 UI 稿中的模拟登录 JavaScript。

- [ ] **步骤 3：逐项对齐公共侧边栏与工作台首页**

以 `工作台主页.html` 为准调整：

```css
.sidebar-primary { width: var(--ui-sidebar-width); padding: 28px 16px 22px; background: #fff; border-right: 1px solid var(--ui-border); box-shadow: 4px 0 24px rgba(23, 26, 43, .035); }
.nav-links li:not(.section-title) { min-height: 46px; border-radius: 12px; }
.nav-links li.active { color: #fff; background: var(--ui-brand); box-shadow: 0 10px 24px rgba(91, 85, 234, .24); }
.main-content { padding: 42px; background: var(--ui-canvas); }
.hero-card-container { width: min(100%, 1280px); padding-top: 40px; }
.hero-main-card { border-radius: 28px; background: #fff; box-shadow: 0 18px 50px rgba(23, 26, 43, .06); }
.tools-grid-new { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 20px; }
```

真实统计字段继续调用 `statValue(...)` 和 `tokenUsageLabel`；部门切换继续调用 `openDept(dept.key)`；工具卡点击继续调用 `openTool(tool)`，不替换为静态 UI 稿数据。

- [ ] **步骤 4：运行契约与业务测试**

```bash
cd frontend
npm test
npm run build
```

预期：核心视觉令牌和首页结构测试通过；所有业务测试通过；构建成功。

- [ ] **步骤 5：提交登录页与工作台样式**

```bash
git add frontend/src/App.vue frontend/src/styles.css frontend/src/__tests__/uiFidelity.test.js
git commit -m "feat: align shell and dashboard with ui draft"
```

## 任务 4：对齐项目、项目看板和运行记录页面

**文件：**

- 修改：`frontend/src/App.vue:127-320,746-786`
- 修改：`frontend/src/styles.css:77-215,237-253,335-375`
- 测试：`frontend/src/__tests__/projectView.test.js`
- 测试：`frontend/src/__tests__/runView.test.js`

- [ ] **步骤 1：增加项目弹窗契约测试并确认失败**

在 `projectView.test.js` 追加：

```js
test('项目新增编辑继续使用居中弹窗', () => {
  assert.match(app, /<AppDialog[^>]*:open="showProjectCreate"/s)
  assert.match(app, /@submit\.prevent="submitProject"/)
})
```

运行 `node --test src/__tests__/projectView.test.js`，预期因项目表单尚未接入 `AppDialog` 而失败。

- [ ] **步骤 2：对齐运行记录列表**

以 `运行记录页面.html` 为准统一筛选栏、表头、记录类型、工具图标、人员头像、部门标签、资源消耗、状态点和操作按钮。保留 `filterRunRecords`、分页、复制与附件下载逻辑，不恢复已移除的“关联项目”业务。

- [ ] **步骤 3：对齐运行详情抽屉**

使用 `DetailDrawer wide` 承载现有运行详情四个区块，统一标题、字段网格、附件卡片、JSON 等宽字体和底部关闭按钮；不得改变 `selectedRunAttachments`、`selectedRunJson` 和下载事件。

- [ ] **步骤 4：对齐项目列表和项目详情**

以 `项目页面.html` 为准保留二级项目列表和主详情结构；将项目新建/编辑表单放入：

```vue
<AppDialog
  :open="showProjectCreate"
  :title="editingProjectId ? '编辑项目' : '新建项目'"
  description="项目字段与详情页保持一致"
  size="large"
  @close="closeProjectEditor"
>
  <form id="project-editor-form" class="project-form-grid" @submit.prevent="submitProject">
    <label>项目名称<input v-model="projectForm.name" placeholder="例如：夏季新品推广" /></label>
    <label>商品名称<input v-model="projectForm.productName" placeholder="请输入商品名称" /></label>
    <label>所属部门<UiSelect v-model="projectForm.departmentId" :options="projectDepartmentOptions.filter((item) => item.value !== 'all')" /></label>
    <label>品牌<input v-model="projectForm.brand" placeholder="请输入品牌" /></label>
    <label>负责人<input v-model="projectForm.owner" placeholder="请输入负责人" /></label>
    <label>类目<input v-model="projectForm.category" placeholder="请输入商品类目" /></label>
    <label>售价<input v-model="projectForm.salePrice" type="number" min="0" step="0.01" placeholder="0.00" /></label>
    <label>立项时间<input v-model="projectForm.establishedDate" type="date" /></label>
    <label class="wide">商品卖点<textarea v-model="projectForm.sellingPoints" rows="3" placeholder="请输入商品卖点"></textarea></label>
    <label class="wide">项目封面<input type="file" accept="image/jpeg,image/png,image/webp" @change="selectProjectCover" /><small>支持 JPG、PNG、WebP，不超过 5MB；重新选择会替换原封面</small></label>
    <label v-if="editingProjectId && selectedProject?.coverUrl" class="wide project-cover-remove"><input v-model="projectRemoveCover" type="checkbox" />移除当前封面</label>
    <div v-if="projectCreateMessage" class="check-result wide">{{ projectCreateMessage }}</div>
  </form>
  <template #footer>
    <button class="btn-secondary" type="button" @click="closeProjectEditor">取消</button>
    <button class="btn-primary" form="project-editor-form" :disabled="projectCreating">{{ projectCreating ? '保存中...' : editingProjectId ? '保存修改' : '创建项目' }}</button>
  </template>
</AppDialog>
```

- [ ] **步骤 5：对齐项目看板**

以 `项目看板页面.html` 为准调整统计卡、筛选栏、表格行、进度和空状态；保留 `projectBoardSearch`、部门/品牌/创建人筛选、分页和点击项目行为。

- [ ] **步骤 6：运行项目与运行记录测试**

```bash
cd frontend
node --test src/__tests__/projectView.test.js src/__tests__/runView.test.js src/__tests__/runAttachments.test.js
npm test
npm run build
```

预期：专项测试、全部测试和生产构建均通过。

- [ ] **步骤 7：提交三类页面改造**

```bash
git add frontend/src/App.vue frontend/src/styles.css frontend/src/__tests__/projectView.test.js frontend/src/__tests__/runView.test.js
git commit -m "feat: align project and run views with ui draft"
```

## 任务 5：把组织和角色编辑迁移为居中弹窗

**文件：**

- 修改：`frontend/src/App.vue:407-521,860-880,1680-1998`
- 修改：`frontend/src/styles.css:216-292`
- 修改：`frontend/src/__tests__/adminManagement.test.js`
- 测试：`frontend/src/__tests__/uiFidelity.test.js`

- [ ] **步骤 1：增加失败的后台弹窗契约测试**

在 `adminManagement.test.js` 追加：

```js
test('组织与角色新增编辑使用居中弹窗', () => {
  assert.match(app, /organizationDialogVisible/)
  assert.match(app, /roleDialogVisible/)
  assert.match(app, /@submit\.prevent="saveOrganizationDepartment"/)
  assert.match(app, /@submit\.prevent="saveRole"/)
  assert.doesNotMatch(app, /class="[^"]*organization-editor-panel/)
  assert.doesNotMatch(app, /class="[^"]*role-editor-panel/)
})
```

运行 `node --test src/__tests__/adminManagement.test.js`，预期因组织和角色仍使用常驻右侧编辑区而失败。

- [ ] **步骤 2：增加显式弹窗状态**

在现有组织与角色状态附近增加：

```js
const organizationDialogVisible = ref(false)
const roleDialogVisible = ref(false)
```

让 `newOrganizationDepartment`、`newChildOrganizationDepartment` 和 `selectOrganizationDepartment` 在完成原有表单赋值后设置 `organizationDialogVisible.value = true`；让 `newRole` 和 `selectRole` 在完成原有表单赋值后设置 `roleDialogVisible.value = true`。保存成功后关闭对应弹窗，失败时保持打开并保留输入。

- [ ] **步骤 3：组织页面只保留树形列表**

删除常驻 `organization-editor-panel`，将原表单放入：

```vue
<AppDialog
  :open="organizationDialogVisible"
  :title="organizationForm.id ? '编辑部门' : organizationForm.parentId ? '新增下级部门' : '新增一级部门'"
  :description="organizationForm.parentId && !organizationForm.id ? `将在“${organizationParentName}”下创建新部门` : '部门可随时调整，禁用后不再用于新用户分配'"
  @close="organizationDialogVisible = false"
>
  <form id="organization-editor-form" class="organization-form-grid" @submit.prevent="saveOrganizationDepartment">
    <label>部门名称<ElInput v-model="organizationForm.name" placeholder="例如：业务一组" /></label>
    <div class="organization-parent-field">
      <span class="organization-field-label">上级部门</span>
      <ElTreeSelect
        v-model="organizationForm.parentId"
        class="organization-parent-select"
        :data="organizationParentTreeData"
        node-key="id"
        check-strictly
        default-expand-all
        clearable
        placeholder="无上级部门（一级部门）"
        :render-after-expand="false"
      />
    </div>
    <div v-if="organizationMessage" class="check-result" :class="{ passed: organizationMessage.includes('已保存') || organizationMessage.includes('已更新') }">{{ organizationMessage }}</div>
  </form>
  <template #footer>
    <button class="btn-secondary" type="button" @click="organizationDialogVisible = false">取消</button>
    <button class="btn-primary" form="organization-editor-form" :disabled="organizationSaving">{{ organizationSaving ? '保存中...' : '保存部门' }}</button>
  </template>
</AppDialog>
```

禁用和删除仍由原函数处理；编辑状态下可放在弹窗正文底部的“危险操作”区，不能改变内置校验和确认流程。

- [ ] **步骤 4：角色页面只保留角色列表**

删除常驻 `role-editor-panel`，将基本信息、权限点、工具权限和错误提示整体放入 `AppDialog size="large"`。弹窗底部保留取消和保存；禁用、启用、删除仍调用 `toggleRoleEnabled`、`deleteSelectedRole`，内置角色继续隐藏删除按钮并显示原因。

- [ ] **步骤 5：清理双栏编辑样式并统一列表**

将 `.organization-layout`、`.role-admin-layout` 改为单列；移除仅服务于常驻右栏的 `.organization-editor-panel`、`.role-editor-panel` 定位规则，权限网格在大弹窗中使用两列并在窄屏降为一列。

- [ ] **步骤 6：运行后台专项测试**

```bash
cd frontend
node --test src/__tests__/adminManagement.test.js src/__tests__/organizationStyles.test.js src/__tests__/uiFidelity.test.js
npm test
npm run build
```

预期：组织与角色弹窗契约通过；内置角色、组织树、用户管理和业务测试保持通过；构建成功。

- [ ] **步骤 7：提交后台交互改造**

```bash
git add frontend/src/App.vue frontend/src/styles.css frontend/src/__tests__/adminManagement.test.js frontend/src/__tests__/uiFidelity.test.js
git commit -m "feat: move admin editors into dialogs"
```

## 任务 6：统一其余后台列表、表单和反馈样式

**文件：**

- 修改：`frontend/src/App.vue:321-610,628-744,788-807`
- 修改：`frontend/src/styles.css:57-136,216-375`
- 测试：`frontend/src/__tests__/adminManagement.test.js`

- [ ] **步骤 1：统一后台页面标题和列表结构**

部署、用户、工具、模型、模型日志和操作日志统一使用 UI 稿的白色列表容器、表头、行高、状态标签、操作按钮和分页。保留所有 `v-for` 数据源、分页参数、启停、重置密码、删除和模型脱敏字段。

- [ ] **步骤 2：统一用户、凭据和确认弹窗**

用户新增/编辑、临时密码结果和危险确认全部使用 `AppDialog`；保持组织树多选、角色多选、主部门、复制临时密码及确认回调不变。普通表单使用 560px，用户复杂表单使用 `size="large"`，确认框使用默认尺寸。

- [ ] **步骤 3：统一反馈和状态组件样式**

将 `.check-result`、`.status-badge`、`.app-toast`、空状态、禁用按钮和加载按钮统一到视觉令牌；API 错误文本和 `showNotice` 调用不变。

- [ ] **步骤 4：运行后台与全量验证**

```bash
cd frontend
node --test src/__tests__/adminManagement.test.js src/__tests__/dashboardStats.test.js src/__tests__/promoImageLaunch.test.js
npm test
npm run build
```

预期：后台专项测试、外部推广图跳转测试、全部测试和生产构建通过。

- [ ] **步骤 5：提交后台视觉统一**

```bash
git add frontend/src/App.vue frontend/src/styles.css frontend/src/__tests__/adminManagement.test.js
git commit -m "feat: unify admin list and feedback styling"
```

## 任务 7：响应式、可访问性和动效收尾

**文件：**

- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/components/AppDialog.vue`
- 修改：`frontend/src/components/DetailDrawer.vue`
- 修改：`frontend/src/styles.css`
- 修改：`frontend/src/__tests__/uiFidelity.test.js`

- [ ] **步骤 1：补充键盘和语义契约测试**

在 `uiFidelity.test.js` 读取两个组件，并增加：

```js
const dialog = readFileSync(new URL('../components/AppDialog.vue', import.meta.url), 'utf8')
const drawer = readFileSync(new URL('../components/DetailDrawer.vue', import.meta.url), 'utf8')

test('浮层提供对话框语义和关闭按钮标签', () => {
  assert.match(dialog, /role="dialog"/)
  assert.match(dialog, /aria-modal="true"/)
  assert.match(dialog, /aria-label="关闭"/)
  assert.match(drawer, /role="dialog"/)
  assert.match(drawer, /aria-label="关闭"/)
  assert.match(dialog, /tabindex="-1"/)
  assert.match(drawer, /tabindex="-1"/)
  assert.match(dialog, /@keydown\.esc/)
  assert.match(drawer, /@keydown\.esc/)
})

test('界面尊重减少动态效果偏好', () => {
  assert.match(css, /@media\s*\(prefers-reduced-motion:\s*reduce\)/)
})
```

- [ ] **步骤 2：实现响应式规则**

在 `styles.css` 末尾统一设置：

```css
@media (max-width: 1024px) {
  .sidebar-primary { width: 220px; }
  .tools-grid-new { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 768px) {
  .app-container { min-height: 100dvh; height: auto; flex-direction: column; }
  .sidebar-primary { width: 100%; max-height: none; }
  .main-content { padding: 20px; }
  .tools-grid-new, .project-board-stats, .permission-board { grid-template-columns: 1fr; }
  .table-card, .run-records-list { overflow-x: auto; }
  .app-overlay { padding: 12px; }
  .app-dialog-shell { width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }
  .detail-drawer-shell, .detail-drawer-shell.wide { width: 100vw; max-width: none; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
}
```

- [ ] **步骤 3：补齐浮层焦点管理和图标按钮标签**

在两个浮层组件的 `section/aside` 上添加 `ref="panel" tabindex="-1" @keydown.esc="$emit('close')"`。组件脚本使用以下一致逻辑，打开后把焦点移入浮层，关闭后归还给触发元素：

```js
import { computed, nextTick, ref, watch } from 'vue'

const panel = ref(null)
let returnFocusTo = null

watch(() => props.open, async (open) => {
  if (open) {
    returnFocusTo = document.activeElement
    await nextTick()
    panel.value?.focus()
  } else if (returnFocusTo instanceof HTMLElement) {
    returnFocusTo.focus()
    returnFocusTo = null
  }
})
```

所有只有图标的按钮添加明确 `aria-label`；使用 `:focus-visible` 为导航、按钮、输入框、选择器、弹窗关闭按钮提供 3px 紫色半透明焦点环。不得通过移除 outline 隐藏键盘焦点。

- [ ] **步骤 4：运行全量测试和构建**

```bash
cd frontend
npm test
npm run build
```

预期：所有测试通过，Vite 生产构建成功且无 Vue 模板错误。

- [ ] **步骤 5：提交响应式和可访问性收尾**

```bash
git add frontend/src/App.vue frontend/src/components/AppDialog.vue frontend/src/components/DetailDrawer.vue frontend/src/styles.css frontend/src/__tests__/uiFidelity.test.js
git commit -m "feat: finish responsive and accessible ui"
```

## 任务 8：浏览器逐页视觉验收

**文件：**

- 只读：`doc/一期/前端demo/一起互动AI工作台/*`
- 验证：`frontend/dist/*`

- [ ] **步骤 1：启动前端并记录访问地址**

```bash
cd frontend
npm run dev -- --host 127.0.0.1
```

预期：Vite 输出本地地址，例如 `http://127.0.0.1:5173/`。如平台页面依赖后端数据，同时启动现有后端服务，不创建模拟业务接口。

- [ ] **步骤 2：使用相同视口对比登录与工作台**

使用浏览器控制技能分别打开 UI 稿 HTML 和实现页面，在相同桌面视口检查：登录页、侧边栏、欢迎卡、统计区、部门工具卡。记录所有可见差异并直接修复 `App.vue` 或 `styles.css`。

- [ ] **步骤 3：使用相同视口对比项目、项目看板和运行记录**

逐页检查结构比例、筛选区、表格、状态、操作按钮、项目详情和运行详情抽屉；验证真实筛选、分页、查看、复制、下载入口仍可操作。

- [ ] **步骤 4：检查后台列表、弹窗和抽屉**

依次检查用户、组织、角色、工具、模型和日志页面。验证页面默认只显示列表；新增与编辑居中；查看详情从右侧进入；保存失败不关闭弹窗；危险操作仍有确认。

- [ ] **步骤 5：检查窄屏与减少动效**

在 768px 以下检查侧边栏、横向表格、近全屏弹窗和全屏抽屉；启用减少动态效果后确认没有明显位移或缩放动画。

- [ ] **步骤 6：执行最终自动化验证**

```bash
cd frontend
npm test
npm run build
git diff --check
```

预期：全部测试通过；构建成功；`git diff --check` 无输出。

- [ ] **步骤 7：提交视觉验收修正**

```bash
git add frontend/src/App.vue frontend/src/components/AppDialog.vue frontend/src/components/DetailDrawer.vue frontend/src/styles.css frontend/src/__tests__
git commit -m "fix: close ui draft visual gaps"
```
