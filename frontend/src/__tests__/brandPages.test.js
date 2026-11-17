import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const app = fs.readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const api = fs.readFileSync(new URL('../api.js', import.meta.url), 'utf8')
const styles = fs.readFileSync(new URL('../styles.css', import.meta.url), 'utf8')
const permissions = fs.readFileSync(new URL('../adminPermissions.js', import.meta.url), 'utf8')

test('品牌 HTML 同时提供后台管理和前台检索预览入口', () => {
  assert.match(permissions, /key: 'brandPages'/)
  assert.match(app, /currentPage === 'brand-pages'/)
  assert.match(app, /adminSection === 'brandPages'/)
})

test('管理员在普通工作台显示管理员身份并由服务端获得全部品牌 HTML', () => {
  assert.match(app, /roleName\(currentUser\.role, currentUser\.permissions\)/)
  assert.match(app, /permissions\.includes\('admin:user:read'\)/)
  assert.match(app, /searchBrandPages\(brandPageSearch\.value, brandPageDepartmentFilter\.value\)/)
})

test('品牌页面允许上传的 HTML 执行全部脚本和交互能力', () => {
  assert.match(app, /<iframe v-else-if="brandPageHtml"[^>]+:srcdoc="brandPageHtml"/)
  assert.doesNotMatch(app, /<iframe v-else-if="brandPageHtml"[^>]+\bsandbox=/)
  assert.match(app, /getBrandPageContent\(page\.id, (?:returnTo === 'admin'[^\n]*admin:brand-page:write[^\n]*|admin)\)/)
})

test('品牌 HTML 预览兼容 srcdoc 内的片段锚点导航', () => {
  assert.match(app, /bindBrandPageAnchorNavigation\(previewDocument\)/)
})

test('品牌 HTML 上传界面不显示文件大小限制', () => {
  assert.doesNotMatch(app, /最大 10MB/)
})

test('品牌 HTML 上传必须关联组织部门，查询同时传递部门筛选', () => {
  assert.match(app, /brandPageForm\.departmentId/)
  assert.match(app, /brandPageDepartmentFilter/)
  assert.match(app, /brandPageUploadDepartmentTreeData/)
  assert.match(api, /body\.append\('departmentId', departmentId\)/)
  assert.match(api, /departmentId=\$\{encodeURIComponent\(departmentId\)\}/)
})

test('品牌 HTML 查看权限支持多选并过滤已删除的授权对象', () => {
  assert.match(app, /brandPagePermissionTreeData/)
  assert.match(app, /show-checkbox check-strictly/)
  assert.match(app, /viewerDepartmentIds: \(page\.viewerDepartmentIds \|\| \[\]\)\.filter/)
  assert.match(app, /viewerUserIds: \(page\.viewerUserIds \|\| \[\]\)\.filter/)
  assert.match(api, /body\.append\('viewerDepartmentIds', id\)/)
  assert.match(api, /body\.append\('viewerUserIds', id\)/)
})

test('品牌 HTML 预览隐藏平台框架并支持浏览器全屏', () => {
  assert.match(app, /'brand-preview-mode': currentPage === 'brand-page-preview'/)
  assert.match(app, /toggleBrandPageFullscreen/)
  assert.match(app, /requestFullscreen/)
  assert.match(app, /brandPageFullscreen \? '退出全屏' : '浏览器全屏'/)
  assert.match(styles, /\.brand-preview-mode/)
})

test('品牌 HTML 平台操作默认收起为左侧工具条且支持自动收起', () => {
  assert.match(app, /brandPageControlsExpanded/)
  assert.match(app, /:aria-expanded="brandPageControlsExpanded"/)
  assert.match(app, /toggleBrandPageControls/)
  assert.match(app, /bindBrandPagePreviewInteractions/)
  assert.match(app, /closeBrandPageControls/)
  assert.match(styles, /\.brand-page-preview-controls \{[^}]*top: 50%[^}]*left: 0[^}]*translateY\(-50%\)/)
  assert.match(styles, /\.brand-page-preview-controls\.expanded/)
})

test('后台品牌页面支持编辑且预览返回后台来源', () => {
  assert.match(app, /openBrandPageEditor\(page\)/)
  assert.match(app, /editingBrandPageId/)
  assert.match(api, /updateBrandPage/)
  assert.match(app, /from=admin/)
  assert.match(app, /returnFromBrandPagePreview/)
  assert.match(app, /navigateTo\('\/admin\/brandPages'/)
})

test('品牌页面只读账号仍显示预览操作', () => {
  assert.match(app, /<th>操作<\/th>/)
  assert.match(app, /<td class="table-actions">[\s\S]*?<button class="icon-btn" type="button" @click="previewBrandPage\(page\)">预览<\/button>/)
  assert.match(app, /v-if="canWriteAdminSection\(currentUser\.permissions, 'brandPages'\)" class="icon-btn" type="button" @click="openBrandPageEditor\(page\)">编辑<\/button>/)
})

test('品牌部门筛选保留组织树祖先但只允许选择本人直属部门', () => {
  assert.match(app, /const includedDepartmentIds = new Set\(assignedDepartmentIds\)/)
  assert.match(app, /while \(parentId && departmentById\.has\(parentId\)/)
  assert.match(app, /disabled: !assignedDepartmentIds\.has\(department\.id\)/)
})
