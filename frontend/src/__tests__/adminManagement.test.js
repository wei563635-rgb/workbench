import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../api.js', import.meta.url), 'utf8')
const adminPagination = readFileSync(new URL('../components/AdminPagination.vue', import.meta.url), 'utf8')
const template = app.slice(app.indexOf('<template>') + '<template>'.length, app.lastIndexOf('</template>')).replace(/<!--[\s\S]*?-->/g, '')
const script = (app.match(/<script setup>([\s\S]*?)<\/script>/)?.[1] || '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '')

function functionSource(name) {
  const start = script.indexOf(`function ${name}(`)
  assert.notEqual(start, -1, `missing function ${name}`)
  const open = script.indexOf('{', start)
  let depth = 0
  for (let index = open; index < script.length; index += 1) {
    if (script[index] === '{') depth += 1
    if (script[index] === '}') depth -= 1
    if (depth === 0) return script.slice(start, index + 1)
  }
  return ''
}

test('用户管理提供完整编辑字段和主部门选择', () => {
  assert.match(app, /编辑用户/)
  assert.match(app, /primaryDepartmentId/)
  assert.match(app, /updateEditUserDepartments/)
  assert.match(api, /export async function updateUser/)
})

test('后台列表使用统一服务端分页入口', () => {
  assert.match(app, /AdminPagination/)
  assert.match(app, /loadAdminPage/)
  assert.match(api, /export async function loadAdminPage/)
  assert.match(api, /pageSize/)
})

test('共享后台分页在加载时提供可访问中文状态', () => {
  assert.match(adminPagination, /<span\b[^>]*v-if="page\.loading"[^>]*class="admin-pagination-status"[^>]*role="status"[^>]*aria-live="polite"[^>]*>\s*加载中…\s*<\/span>/)
  assert.match(adminPagination, /<ElPagination\b[^>]*:disabled="page\.loading"[^>]*@current-change="\$emit\('change', \$event\)"[^>]*\/>/)
})

test('后台分页表逐项区分加载与空状态', () => {
  const tables = [
    ['deployments', 'pagedDeployments', 6, '暂无部署申请'],
    ['users', 'pagedUsers', `:colspan="canWriteAdminSection(currentUser.permissions, 'users') ? 7 : 6"`, '暂无用户'],
    ['roles', 'pagedRoles', `:colspan="canWriteAdminSection(currentUser.permissions, 'roles') ? 6 : 5"`, '暂无角色'],
    ['tools', 'pagedTools', `:colspan="canWriteAdminSection(currentUser.permissions, 'tools') ? 5 : 4"`, '暂无工具配置'],
    ['models', 'pagedModels', `:colspan="canWriteAdminSection(currentUser.permissions, 'models') ? 9 : 8"`, '暂无模型配置'],
    ['operationLogs', 'pagedOperationLogs', 5, '暂无操作日志']
  ]

  for (const [key, items, colspan, emptyText] of tables) {
    const colspanAttribute = typeof colspan === 'number' ? `colspan="${colspan}"` : colspan
    const escapedColspan = colspanAttribute.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    assert.match(template, new RegExp(`<tr\\s+v-if="adminPages\\.${key}\\.loading"[^>]*>\\s*<td ${escapedColspan} class="table-empty table-loading">加载中…<\\/td>\\s*<\\/tr>`), `${key} must render its loading row`)
    assert.match(template, new RegExp(`<template\\s+v-if="!adminPages\\.${key}\\.loading">\\s*<tr v-for="[^\"]+ in ${items}"`), `${key} must hide data rows while loading`)
    assert.match(template, new RegExp(`<tr\\s+v-if="!adminPages\\.${key}\\.loading && ${items}\\.length === 0"[^>]*>\\s*<td ${escapedColspan} class="table-empty">${emptyText}<\\/td>\\s*<\\/tr>`), `${key} must guard its empty row`)
  }
})

test('后台菜单由当前用户可读权限动态生成', () => {
  assert.match(script, /import \{ canReadAdminSection, canWriteAdminSection, hasAdminAccess, visibleAdminSections \} from '\.\/adminPermissions'/)
  assert.doesNotMatch(script, /const adminNavItems\s*=/)
  assert.match(script, /const visibleAdminNavItems = computed\(\(\) => visibleAdminSections\(currentUser\.value\.permissions, currentUser\.value\.superAdmin\)\)/)
  assert.match(script, /const canAccessAdmin = computed\(\(\) => hasAdminAccess\(currentUser\.value\.permissions\)\)/)
  assert.match(template, /v-for="item in group.items"/)
})

test('后台模块切换和分页请求均在副作用前拒绝无读取权限模块', () => {
  const openSection = functionSource('openAdminSection')
  const openGuard = openSection.indexOf('canReadAdminSection(currentUser.value.permissions, section, currentUser.value.superAdmin)')
  assert.ok(openGuard >= 0 && openGuard < openSection.indexOf('adminSection.value = section'))

  const loadPage = functionSource('loadAdminPage')
  const loadGuard = loadPage.indexOf('canReadAdminSection(currentUser.value.permissions, section, currentUser.value.superAdmin)')
  assert.ok(loadGuard >= 0 && loadGuard < loadPage.indexOf('state.loading = true'))
  assert.ok(loadGuard < loadPage.indexOf('loadAdminPageApi('))
})

test('权限载入后将不可读后台模块回退到第一个可见模块', () => {
  const ensureAccessible = functionSource('ensureAccessibleAdminSection')
  assert.match(ensureAccessible, /if \(canReadAdminSection\(currentUser\.value\.permissions, adminSection\.value, currentUser\.value\.superAdmin\)\) return/)
  assert.match(ensureAccessible, /adminSection\.value = visibleAdminSections\(currentUser\.value\.permissions, currentUser\.value\.superAdmin\)\[0\]\?\.key \|\| 'overview'/)

  for (const name of ['restoreSession', 'login', 'submitInitialPasswordChange']) {
    const source = functionSource(name)
    const permissionsLoaded = source.indexOf('currentUser.value = normalizeCurrentUser(')
    const ensured = source.indexOf('ensureAccessibleAdminSection()')
    assert.ok(permissionsLoaded >= 0 && permissionsLoaded < ensured, `${name} must ensure after loading permissions`)
  }
})

test('首次改密后返回工作台，普通用户登录后台地址也返回工作台', () => {
  const changePassword = functionSource('submitInitialPasswordChange')
  assert.ok(changePassword.indexOf('await changePasswordApi(') < changePassword.indexOf('enterWorkbenchAfterAuth()'))
  assert.ok(changePassword.indexOf('enterWorkbenchAfterAuth()') < changePassword.indexOf('authed.value = true'))

  for (const name of ['restoreSession', 'login']) {
    const source = functionSource(name)
    assert.match(source, /routeMode\.value === 'admin' && !hasAdminAccess\(currentUser\.value\.permissions\)/)
    assert.match(source, /enterWorkbenchAfterAuth\(\)/)
  }

  const routes = []
  const redirect = new Function('window', 'applyRouteFromLocation',
    `${functionSource('enterWorkbenchAfterAuth')}; return enterWorkbenchAfterAuth`)(
    { history: { replaceState: (_state, _title, path) => routes.push(path) } },
    () => routes.push('route-synced')
  )
  redirect()
  assert.deepEqual(routes, ['/workbench', 'route-synced'])
})

test('从后台退出后重置为工作台入口，避免下一个成员账号继承后台地址', () => {
  const source = functionSource('logout')
  assert.ok(source.indexOf('logoutApi()') < source.indexOf('enterWorkbenchAfterAuth()'))
  assert.match(source, /enterWorkbenchAfterAuth\(\)/)
})

test('中转账号汇总与批量刷新显式引入 JSON 请求方法', () => {
  assert.match(script, /import \{getJson, postJson, deleteJson\} from '\.\/api\.js'/)
})

test('概览跨模块入口仅对对应读取权限可见', () => {
  assert.match(template, /<button\b[^>]*v-if="canReadAdminSection\(currentUser\.permissions, 'deployments'\)"[^>]*@click="openAdminSection\('deployments'\)"[^>]*>查看部署门禁<\/button>/)
  assert.match(template, /<button\b[^>]*v-if="canReadAdminSection\(currentUser\.permissions, 'models'\)"[^>]*@click="openAdminSection\('models'\)"[^>]*>查看模型配置<\/button>/)
})

test('六个可写模块均隐藏无写权限入口和操作列', () => {
  assert.match(template, /<button\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'deployments'\)"[^>]*@click="runManifestCheck"[^>]*>提交部署申请<\/button>/)

  assert.match(template, /<button\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'users'\)"[^>]*@click="openUserCreateDialog"[^>]*>新增用户<\/button>/)
  assert.match(template, /<th v-if="canWriteAdminSection\(currentUser\.permissions, 'users'\)">操作<\/th>/)
  assert.match(template, /<td v-if="canWriteAdminSection\(currentUser\.permissions, 'users'\)" class="table-actions">[\s\S]*?toggleUser\(user\)[\s\S]*?resetUser\(user\)[\s\S]*?openUserEditDialog\(user\)[\s\S]*?deleteManagedUser\(user\)[\s\S]*?<\/td>/)

  assert.match(template, /<button\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'organization'\)"[^>]*@click="newOrganizationDepartment"[^>]*>新增一级部门<\/button>/)
  for (const action of ['selectOrganizationDepartment\\(node\\)', 'newChildOrganizationDepartment\\(node\\)', 'deleteSelectedOrganizationDepartment\\(node\\)']) {
    assert.match(template, new RegExp(`<ElButton\\b[^>]*v-if="canWriteAdminSection\\(currentUser\\.permissions, 'organization'\\)"[^>]*@click\\.stop="${action}"`))
  }
  assert.match(template, /<AppDialog\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'organization'\)"[^>]*:open="organizationDialogVisible"/)

  assert.match(template, /<button\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'roles'\)"[^>]*@click="newRole"[^>]*>新增角色<\/button>/)
  assert.match(template, /<th v-if="canWriteAdminSection\(currentUser\.permissions, 'roles'\)">操作<\/th>/)
  assert.match(template, /<td v-if="canWriteAdminSection\(currentUser\.permissions, 'roles'\)" class="table-actions">[\s\S]*?selectRole\(role\)[\s\S]*?deleteSelectedRole\(role\)[\s\S]*?<\/td>/)
  assert.match(template, /<AppDialog\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'roles'\)"[^>]*:open="roleDialogVisible"/)

  for (const section of ['tools', 'models']) {
    assert.match(template, new RegExp(`<th v-if="canWriteAdminSection\\(currentUser\\.permissions, '${section}'\\)">操作<\\/th>`))
    assert.match(template, new RegExp(`<td v-if="canWriteAdminSection\\(currentUser\\.permissions, '${section}'\\)" class="table-actions">`))
  }
})

test('上级部门选项排除当前节点的全部后代', () => {
  assert.match(app, /organizationDescendantIds/)
  assert.match(app, /!organizationDescendantIds\.value\.has/)
})

test('用户部门角色提供逻辑删除入口', () => {
  assert.match(api, /export async function deleteUser/)
  assert.match(api, /export async function deleteOrganizationDepartment/)
  assert.match(api, /export async function deleteRole/)
  assert.match(app, /deleteSelectedOrganizationDepartment/)
  assert.match(app, /deleteSelectedRole/)
})

test('后台顶部品牌名与前台入口分离', () => {
  assert.match(app, /entryBrandName/)
  assert.match(app, /isAdminEntry\.value \? '管理后台' : '一起互动AI工作台'/)
  assert.match(app, /class="logo"[^>]*>.*entryBrandName/s)
})

test('后台概览使用总 Token 消耗文案', () => {
  assert.match(app, /总 Token 消耗/)
})

test('新增和编辑用户使用组织树多选部门与多选角色', () => {
  assert.match(app, /user-create-department-tree/)
  assert.match(app, /user-edit-department-tree/)
  assert.match(app, /show-checkbox/)
  assert.match(app, /userCreateForm\.roleIds/)
  assert.match(app, /toggleCreateUserRole/)
})

test('创建和重置密码通过独立结果弹窗展示临时密码', () => {
  assert.match(app, /credentialDialog\.visible/)
  assert.match(app, /credentialDialog\.temporaryPassword/)
  assert.match(app, /openCredentialDialog/)
  assert.match(app, /reset = await resetPassword/)
  assert.match(app, /await copyText\(credentialDialog\.value\.temporaryPassword\)/)
})

test('重置当前登录账号后立即清理旧会话并回到登录页', () => {
  const source = functionSource('resetUser')
  assert.match(source, /if \(user\.id === currentUser\.value\.id\)/)
  assert.match(source, /loginForm\.value = \{ account: user\.account, password: reset\.temporaryPassword \}/)
  assert.match(source, /logout\(\)/)
  assert.match(source, /errorMessage\.value = '密码已重置，请使用临时密码重新登录。'/)
  assert.match(source, /return/)
})

test('用户与工具上下文浮层统一使用 AppDialog', () => {
  assert.match(template, /<AppDialog\b[^>]*:open="projectPickerVisible"[^>]*>/)
  assert.match(template, /<AppDialog\b[^>]*:open="userRoleDialogVisible"[^>]*>/)
  assert.match(template, /<AppDialog\b[^>]*:open="userCreateDialogVisible"[^>]*size="large"[^>]*:close-disabled="userCreating"[^>]*>/)
  assert.match(template, /<AppDialog\b[^>]*:open="userEditDialogVisible"[^>]*size="large"[^>]*:close-disabled="userEditing"[^>]*>/)
  assert.match(template, /<AppDialog\b[^>]*:open="credentialDialog\.visible"[^>]*:close-on-backdrop="false"[^>]*>/)
  assert.doesNotMatch(template, /<(?:aside|div)\b[^>]*class="[^"]*(?:project-picker|drawer-overlay)[^"]*"[^>]*>/)
})

test('用户角色保存锁定目标并在 finally 释放重入状态', () => {
  assert.match(script, /const userRoleSaving = ref\(false\)/)
  const source = functionSource('saveUserRoleChange')
  assert.match(source, /^function saveUserRoleChange\(\) \{\s*if \(userRoleSaving\.value\) return/)
  const validated = source.indexOf('if (!user || !roleId) return')
  const captured = source.indexOf('const targetUserId = user.id')
  const locked = source.indexOf('userRoleSaving.value = true')
  const firstAwait = source.indexOf('await ')
  assert.ok(validated < captured && captured < locked && locked < firstAwait)
  assert.match(source, /try \{[\s\S]*await updateUserRoles\(targetUserId, \[roleId\]\)[\s\S]*catch \(error\) \{[\s\S]*finally \{\s*userRoleSaving\.value = false/)
  assert.match(source, /if \(selectedRoleUser\.value\?\.id === targetUserId\) closeUserRoleDialog\(true\)/)
})

test('用户角色弹窗在保存期间禁用关闭切换和全部业务按钮', () => {
  assert.match(functionSource('openUserRoleDialog'), /^function openUserRoleDialog\(user\) \{\s*if \(userRoleSaving\.value\) return/)
  assert.match(functionSource('closeUserRoleDialog'), /^function closeUserRoleDialog\(force = false\) \{\s*if \(userRoleSaving\.value && !force\) return/)
  assert.match(template, /<AppDialog\b[^>]*:open="userRoleDialogVisible"[^>]*:close-disabled="userRoleSaving"[^>]*>/)
  assert.match(template, /<button\b[^>]*v-for="role in roles"[^>]*type="button"[^>]*:disabled="userRoleSaving"[^>]*>/)
  assert.match(template, /<button class="btn-secondary" type="button" :disabled="userRoleSaving" @click="closeUserRoleDialog">取消<\/button>/)
  assert.match(template, /<button class="btn-primary" type="button" :disabled="userRoleSaving" @click="saveUserRoleChange">\{\{ userRoleSaving \? '保存中\.\.\.' : '确认变更' \}\}<\/button>/)
})

test('部门保存成功使用 toast 且工具状态显示中文', () => {
  assert.match(app, /showNotice\([^)]*组织部门已创建[^)]*success/s)
  assert.match(app, /toolStatusLabel\(tool\.status\)/)
  assert.match(app, /正常运行/)
  assert.match(app, /维护中/)
})

test('重置密码后的首次登录进入强制改密流程', () => {
  assert.match(api, /export async function changePasswordApi/)
  assert.match(api, /\/api\/auth\/change-password/)
  assert.match(app, /passwordChangeRequired/)
  assert.match(app, /首次登录修改密码/)
  assert.match(app, /submitInitialPasswordChange/)
  assert.match(app, /loginResult\.mustChangePassword/)
})

test('内置角色隐藏删除按钮并解释不可删除原因', () => {
  assert.match(app, /selectedRole\.builtIn/)
  assert.match(app, /系统内置角色不可删除/)
  assert.match(app, /role-delete-hint/)
})

test('组织和角色编辑器使用居中弹窗而不是常驻侧栏', () => {
  assert.match(script, /const organizationDialogVisible = ref\(false\)/)
  assert.match(script, /const roleDialogVisible = ref\(false\)/)
  assert.match(template, /<AppDialog\b[^>]*:open="organizationDialogVisible"[^>]*>/)
  assert.match(template, /<AppDialog\b[^>]*:open="roleDialogVisible"[^>]*>/)
  assert.match(template, /<form\b[^>]*id="organization-editor-form"[^>]*@submit\.prevent="saveOrganizationDepartment"[^>]*>/)
  assert.match(template, /<form\b[^>]*id="role-editor-form"[^>]*@submit\.prevent="saveRole"[^>]*>/)
  assert.doesNotMatch(template, /class="[^"]*(organization-editor-panel|role-editor-panel)[^"]*"/)
})

test('角色列表使用一行一条的表格并提供独立操作列', () => {
  const table = template.match(/<table\b[^>]*class="role-list-table"[^>]*>[\s\S]*?<\/table>/)?.[0] || ''
  assert.ok(table)
  for (const label of ['角色名称', '状态', '数据范围', '权限点', '工具权限']) {
    assert.match(table, new RegExp(`<th>${label}<\\/th>`))
  }
  assert.match(table, /<th v-if="canWriteAdminSection\(currentUser\.permissions, 'roles'\)">操作<\/th>/)

  const row = table.match(/<tr\b[^>]*v-for="role in pagedRoles"[^>]*>/)?.[0] || ''
  assert.ok(row)
  assert.doesNotMatch(row, /@click|@keydown|role="button"|tabindex=/)

  const editButton = table.match(/<button\b[^>]*class="icon-btn role-row-edit"[^>]*>/)?.[0] || ''
  assert.match(editButton, /@click="selectRole\(role\)"/)
})

test('角色表格始终展示删除入口且内置角色不可删除', () => {
  const deleteButton = template.match(/<button\b[^>]*class="icon-btn danger role-row-delete"[^>]*>/)?.[0] || ''
  assert.match(deleteButton, /:disabled="roleSaving \|\| role\.builtIn"/)
  assert.match(deleteButton, /:title="role\.builtIn \? '系统内置角色不可删除' : '删除角色'"/)
  assert.match(deleteButton, /@click="deleteSelectedRole\(role\)"/)

  assert.match(script, /async function deleteSelectedRole\(role = selectedRole\.value\)/)
})

test('组织树行不进入编辑，仅编辑按钮打开部门编辑弹窗', () => {
  const tree = template.match(/<ElTree\b[^>]*class="organization-management-tree"[^>]*>/)?.[0] || ''
  assert.ok(tree)
  assert.doesNotMatch(tree, /@node-click="selectOrganizationDepartment"/)

  const editButton = template.match(/<ElButton\b[^>]*class="organization-node-edit"[^>]*>/)?.[0] || ''
  assert.match(editButton, /@click\.stop="selectOrganizationDepartment\(node\)"/)
})

test('组织树每个部门提供独立删除按钮', () => {
  const deleteButton = template.match(/<ElButton\b[^>]*class="organization-node-delete"[^>]*>/)?.[0] || ''
  assert.match(deleteButton, /@click\.stop="deleteSelectedOrganizationDepartment\(node\)"/)
  assert.match(deleteButton, /type="danger"/)
})

test('组织和角色表单赋值完成后才打开对应弹窗', () => {
  for (const name of ['newOrganizationDepartment', 'newChildOrganizationDepartment', 'selectOrganizationDepartment']) {
    const source = functionSource(name)
    assert.ok(source.indexOf('organizationForm.value =') < source.indexOf('organizationDialogVisible.value = true'))
  }
  for (const name of ['newRole', 'selectRole']) {
    const source = functionSource(name)
    assert.ok(source.indexOf('roleForm.value =') < source.indexOf('roleDialogVisible.value = true'))
  }
})

test('保存成功关闭弹窗且失败分支保留弹窗状态', () => {
  const organizationSave = functionSource('saveOrganizationDepartment')
  const roleSave = functionSource('saveRole')
  assert.match(organizationSave, /await (create|update)OrganizationDepartment[\s\S]*closeOrganizationDialog\(true\)/)
  assert.match(roleSave, /await (createRole|updateRole)[\s\S]*closeRoleDialog\(true\)/)
  assert.doesNotMatch(organizationSave.match(/catch \(error\) \{([\s\S]*?)\}\s*finally/)?.[1] || '', /organizationDialogVisible\.value = false/)
  assert.doesNotMatch(roleSave.match(/catch \(error\) \{([\s\S]*?)\}\s*finally/)?.[1] || '', /roleDialogVisible\.value = false/)
})

test('组织和角色保存从入口到首个异步操作均有重入保护', () => {
  const organizationSave = functionSource('saveOrganizationDepartment')
  const roleSave = functionSource('saveRole')

  assert.match(organizationSave, /^function saveOrganizationDepartment\(\) \{\s*if \(organizationSaving\.value\) return/)
  assert.ok(organizationSave.indexOf('organizationSaving.value = true') < organizationSave.indexOf('await '))
  assert.match(organizationSave, /finally \{[\s\S]*organizationSaving\.value = false/)

  assert.match(roleSave, /^function saveRole\(\) \{\s*if \(roleSaving\.value\) return/)
  assert.ok(roleSave.indexOf('roleSaving.value = true') < roleSave.indexOf('await askConfirm'))
  assert.match(roleSave, /try \{[\s\S]*await askConfirm[\s\S]*finally \{[\s\S]*roleSaving\.value = false/)
})

test('统一路由和后台 section 同步函数清理不属于当前页面的编辑弹窗', () => {
  const syncDialogs = functionSource('syncAdminEditorDialogs')
  assert.match(syncDialogs, /mode !== 'admin' \|\| section !== 'organization'[\s\S]*organizationDialogVisible\.value = false/)
  assert.match(syncDialogs, /mode !== 'admin' \|\| section !== 'roles'[\s\S]*roleDialogVisible\.value = false/)

  for (const name of ['applyRouteFromLocation', 'navigateTo', 'openAdminSection', 'logout']) {
    assert.match(functionSource(name), /syncAdminEditorDialogs\(/, `${name} must synchronize admin editor dialogs`)
  }
})

test('角色删除成功先同步本地列表分页与编辑状态再刷新服务端分页', () => {
  const source = functionSource('deleteSelectedRole')
  const deleted = source.indexOf('await deleteRole(targetRoleCode)')
  const filteredRoles = source.indexOf('roles.value = roles.value.filter((item) => item.code !== targetRoleCode)')
  const filteredPage = source.indexOf('rolePage.items = rolePage.items.filter((item) => item.code !== targetRoleCode)')
  const refreshed = source.indexOf("await loadAdminPage('roles', rolePage.page)")

  assert.ok(deleted < filteredRoles)
  assert.ok(filteredRoles < filteredPage)
  assert.ok(filteredPage < refreshed)
  assert.match(source, /rolePage\.total = Math\.max\(0, rolePage\.total - 1\)/)
  assert.match(source, /if \(selectedRoleCode\.value === targetRoleCode && roleForm\.value\.code === targetRoleCode\) \{[\s\S]*selectedRoleCode\.value = ''[\s\S]*roleForm\.value = emptyRoleForm\(\)[\s\S]*closeRoleDialog\(true\)[\s\S]*\}/)
})

test('组织删除成功只在编辑器仍指向目标部门时重置并关闭', () => {
  const source = functionSource('deleteSelectedOrganizationDepartment')
  assert.match(source, /const targetDepartmentId = department\.id[\s\S]*await deleteOrganizationDepartment\(targetDepartmentId\)/)
  assert.match(source, /organizationDepartments\.value = organizationDepartments\.value\.filter\(\(item\) => item\.id !== targetDepartmentId\)/)
  assert.match(source, /if \(organizationForm\.value\.id === targetDepartmentId\) \{[\s\S]*organizationForm\.value = emptyOrganizationForm\(\)[\s\S]*closeOrganizationDialog\(true\)[\s\S]*\}/)
})

test('组织启停响应只在当前编辑器仍是目标部门时 hydrate', () => {
  const source = functionSource('toggleOrganizationDepartment')
  const awaited = source.indexOf('await updateOrganizationDepartmentStatus')
  const identityGuard = source.indexOf("const shouldHydrateEditor = organizationDialogVisible.value && routeMode.value === 'admin' && adminSection.value === 'organization' && organizationForm.value.id === targetDepartmentId")
  const selected = source.indexOf('selectOrganizationDepartment(updated, true)')

  assert.match(source, /const targetDepartmentId = department\.id[\s\S]*await updateOrganizationDepartmentStatus\(targetDepartmentId,/)
  assert.ok(awaited < identityGuard)
  assert.ok(identityGuard < selected)
  assert.match(source, /if \(shouldHydrateEditor\) \{[\s\S]*selectOrganizationDepartment\(updated, true\)[\s\S]*\}/)
})

test('角色启停响应只在当前编辑器仍是目标角色时 hydrate', () => {
  const source = functionSource('toggleRoleEnabled')
  const awaited = source.indexOf('await updateRoleStatus')
  const identityGuard = source.indexOf("const shouldHydrateEditor = roleDialogVisible.value && routeMode.value === 'admin' && adminSection.value === 'roles' && selectedRoleCode.value === targetRoleCode && roleForm.value.code === targetRoleCode")
  const selected = source.indexOf('selectRole(updated, true)')

  assert.match(source, /const targetRoleCode = role\.code[\s\S]*await updateRoleStatus\(targetRoleCode,/)
  assert.ok(awaited < identityGuard)
  assert.ok(identityGuard < selected)
  assert.match(source, /if \(shouldHydrateEditor\) selectRole\(updated, true\)/)
})

test('组织角色用户异步变更统一锁定入口和完整编辑表单', () => {
  for (const name of [
    'newOrganizationDepartment', 'newChildOrganizationDepartment', 'selectOrganizationDepartment',
    'toggleOrganizationDepartment', 'deleteSelectedOrganizationDepartment'
  ]) {
    assert.match(functionSource(name), new RegExp(`^function ${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\([^)]*\\) \\{\\s*if \\(organizationSaving\\.value(?: && !force)?\\) return`))
  }
  for (const name of ['newRole', 'selectRole', 'toggleRoleEnabled', 'deleteSelectedRole']) {
    assert.match(functionSource(name), new RegExp(`^function ${name}\\([^)]*\\) \\{\\s*if \\(roleSaving\\.value(?: && !force)?\\) return`))
  }
  for (const name of ['togglePermission', 'toggleRoleTool']) {
    assert.match(functionSource(name), /if \(roleSaving\.value\) return/)
  }
  for (const [name, flag] of [
    ['openUserCreateDialog', 'userCreating'], ['closeUserCreateDialog', 'userCreating'],
    ['submitUserCreate', 'userCreating'], ['toggleCreateUserRole', 'userCreating'],
    ['updateCreateUserDepartments', 'userCreating'], ['openUserEditDialog', 'userEditing'],
    ['closeUserEditDialog', 'userEditing'], ['submitUserEdit', 'userEditing'],
    ['toggleUserRole', 'userEditing'], ['updateEditUserDepartments', 'userEditing']
  ]) {
    assert.match(functionSource(name), new RegExp(`if \\(${flag}\\.value`), `${name} must share ${flag} guard`)
  }

  assert.match(template, /<ElInput\b[^>]*v-model="organizationForm\.name"[^>]*:disabled="organizationSaving"/)
  assert.match(template, /<ElTreeSelect\b[^>]*v-model="organizationForm\.parentId"[\s\S]*?:disabled="organizationSaving"/)
  assert.match(template, /<form\b[^>]*id="role-editor-form"[^>]*>[\s\S]*?<input\b[^>]*v-model="roleForm\.name"[^>]*:disabled="roleSaving"/)
  assert.match(template, /<button\b[^>]*v-for="permission in group\.items"[^>]*:disabled="roleSaving"/)
  assert.match(template, /<button\b[^>]*v-for="tool in roleTools"[^>]*:disabled="roleSaving"/)
  assert.match(template, /<input\b[^>]*v-model="userCreateForm\.account"[^>]*:disabled="userCreating"/)
  assert.match(template, /<UiCheck\b[^>]*v-for="role in enabledRoles"[^>]*:disabled="userCreating"/)
  assert.match(template, /<ElTree\b[^>]*user-create-department-tree[\s\S]*?:props="\{ disabled: \(\) => userCreating \}"/)
  assert.match(template, /<input\b[^>]*v-model="userEditForm\.name"[^>]*:disabled="userEditing"/)
  assert.match(template, /<UiCheck\b[^>]*v-for="role in enabledRoles"[^>]*:disabled="userEditing"/)
  assert.match(template, /<ElTree\b[^>]*user-edit-department-tree[\s\S]*?:props="\{ disabled: \(\) => userEditing \}"/)
})

test('组织角色用户 mutation 在 finally 释放并按捕获实体校验响应', () => {
  for (const [name, flag, target] of [
    ['toggleOrganizationDepartment', 'organizationSaving', 'targetDepartmentId'],
    ['deleteSelectedOrganizationDepartment', 'organizationSaving', 'targetDepartmentId'],
    ['toggleRoleEnabled', 'roleSaving', 'targetRoleCode'],
    ['deleteSelectedRole', 'roleSaving', 'targetRoleCode']
  ]) {
    const source = functionSource(name)
    assert.ok(source.indexOf(`${flag}.value = true`) < source.indexOf('await '), `${name} locks before await`)
    assert.match(source, new RegExp(`finally \\{[\\s\\S]*${flag}\\.value = false`))
    assert.match(source, new RegExp(target))
  }

  const edit = functionSource('submitUserEdit')
  assert.match(edit, /const targetUserId = form\.id/)
  assert.match(edit, /if \(userEditForm\.value\.id === targetUserId\)/)
  assert.match(edit, /finally \{[\s\S]*userEditing\.value = false/)
})

test('模型配置编辑入口和弹窗均受写权限控制，Token 使用安全输入属性', () => {
  assert.match(template, /<td v-if="canWriteAdminSection\(currentUser\.permissions, 'models'\)" class="table-actions">[\s\S]*?@click="openModelConfigEditor\(config\)"/)
  assert.match(template, /<AppDialog\b[^>]*v-if="canWriteAdminSection\(currentUser\.permissions, 'models'\)"[^>]*:open="modelConfigDialogVisible"[^>]*:close-disabled="modelConfigSaving"/)
  assert.match(template, /<input\b[^>]*v-model="modelConfigForm\.apiKey"[^>]*type="password"[^>]*autocomplete="off"[^>]*spellcheck="false"/)
})

test('模型配置保存防重入、校验、捕获目标并在 finally 解锁', () => {
  const source = functionSource('submitModelConfigEdit')
  assert.match(source, /^function submitModelConfigEdit\(\) \{\s*if \(modelConfigSaving\.value/)
  const validated = source.indexOf('validateModelConfigForm(form)')
  const captured = source.indexOf('const targetConfigId = form.id')
  const locked = source.indexOf('modelConfigSaving.value = true')
  const awaited = source.indexOf('await updateModelConfig(targetConfigId, payload)')
  assert.ok(validated >= 0 && validated < captured && captured < locked && locked < awaited)
  assert.match(source, /finally \{\s*modelConfigSaving\.value = false/)
})

test('模型配置编辑器关闭和成功保存清空敏感表单', () => {
  const close = functionSource('closeModelConfigEditor')
  const submit = functionSource('submitModelConfigEdit')
  assert.match(close, /modelConfigForm\.value = emptyModelConfigForm\(\)/)
  assert.match(submit, /if \(!updated\) return/)
  assert.match(submit, /if \(modelConfigForm\.value\.id === targetConfigId\) closeModelConfigEditor\(\)/)
})
