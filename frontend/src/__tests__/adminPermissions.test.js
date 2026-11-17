import test from 'node:test'
import assert from 'node:assert/strict'

import {
  ADMIN_SECTION_DEFINITIONS,
  canReadAdminSection,
  canWriteAdminSection,
  hasAdminAccess,
  visibleAdminSections
} from '../adminPermissions.js'

test('普通成员只看到有读取权限的后台栏目和概览', () => {
  const permissions = ['workbench:access', 'admin:department:read', 'admin:tool:read']

  assert.deepEqual(
    visibleAdminSections(permissions).map((section) => section.key),
    ['overview', 'organization', 'tools']
  )
})

test('后台栏目严格区分读取、写入和未知栏目', () => {
  assert.deepEqual(ADMIN_SECTION_DEFINITIONS, [
    { key: 'relayQuotas', icon: '◴', label: '限额与并发配置', readPermission: 'admin:relay-quota:read', writePermission: 'admin:relay-quota:write' },
    { key: 'overview', icon: '▣', label: '概览', readPermission: null, writePermission: null },
    { key: 'deployments', icon: '⇪', label: '部署门禁', readPermission: 'admin:deploy:read', writePermission: 'admin:deploy:approve' },
    { key: 'users', icon: '◉', label: '用户管理', readPermission: 'admin:user:read', writePermission: 'admin:user:write' },
    { key: 'organization', icon: '▤', label: '组织架构', readPermission: 'admin:department:read', writePermission: 'admin:department:write' },
    { key: 'roles', icon: '◆', label: '角色权限', readPermission: 'admin:role:read', writePermission: 'admin:role:write' },
    { key: 'tools', icon: '□', label: '工具管理', readPermission: 'admin:tool:read', writePermission: 'admin:tool:write' },
    { key: 'models', icon: '◇', label: '模型配置', readPermission: 'admin:model:read', writePermission: 'admin:model:write' },
    { key: 'upstreamAccounts', icon: '◎', label: '中转账号配置与健康度', readPermission: 'admin:upstream-account:read', writePermission: 'admin:upstream-account:write' },
    { key: 'brandPages', icon: '▧', label: '品牌 HTML', readPermission: 'brand:page:read', writePermission: 'admin:brand-page:write' },
    { key: 'aiLogs', icon: '≋', label: '使用记录', readPermission: 'admin:ai-log:read', writePermission: null },
    { key: 'operationLogs', icon: '☷', label: '操作日志', readPermission: 'admin:log:read', writePermission: null }
  ])
  assert.equal(canReadAdminSection(['admin:model:read'], 'models'), true)
  assert.equal(canWriteAdminSection(['admin:model:read'], 'models'), false)
  assert.equal(canWriteAdminSection(['admin:model:write'], 'models'), true)
  assert.equal(canReadAdminSection(['admin:model:read'], 'unknown'), false)
  assert.equal(canWriteAdminSection(['admin:model:write'], 'unknown'), false)
})

test('没有后台权限时概览不可见且栏目结果为空', () => {
  assert.equal(hasAdminAccess(['workbench:access']), false)
  assert.equal(canReadAdminSection(['workbench:access'], 'overview'), false)
  assert.deepEqual(visibleAdminSections(['workbench:access']), [])
  assert.deepEqual(visibleAdminSections(null), [])
})

test('审计员只能读取操作日志，使用记录仅超级管理员可见', () => {
  const permissions = ['admin:ai-log:read', 'admin:log:read']

  assert.equal(hasAdminAccess(permissions), true)
  assert.deepEqual(
    visibleAdminSections(permissions).map((section) => section.key),
    ['overview', 'operationLogs']
  )
  assert.equal(canWriteAdminSection(permissions, 'aiLogs'), false)
  assert.equal(canWriteAdminSection(permissions, 'operationLogs'), false)
})

test('后台栏目定义数组及内部对象都不可修改', () => {
  assert.equal(Object.isFrozen(ADMIN_SECTION_DEFINITIONS), true)
  for (const definition of ADMIN_SECTION_DEFINITIONS) {
    assert.equal(Object.isFrozen(definition), true, `${definition.key} definition must be frozen`)
  }

  const deployments = ADMIN_SECTION_DEFINITIONS.find((definition) => definition.key === 'deployments')
  const originalPermission = deployments.readPermission
  try {
    deployments.readPermission = 'admin:changed:read'
  } catch {
    // ES modules run in strict mode, so assigning to a frozen object throws.
  }
  assert.equal(deployments.readPermission, originalPermission)
})
