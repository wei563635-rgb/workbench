export const ADMIN_SECTION_DEFINITIONS = Object.freeze([
  Object.freeze({ key: 'relayQuotas', icon: '◴', label: '限额与并发配置', readPermission: 'admin:relay-quota:read', writePermission: 'admin:relay-quota:write' }),
  Object.freeze({ key: 'overview', icon: '▣', label: '概览', readPermission: null, writePermission: null }),
  Object.freeze({ key: 'deployments', icon: '⇪', label: '部署门禁', readPermission: 'admin:deploy:read', writePermission: 'admin:deploy:approve' }),
  Object.freeze({ key: 'users', icon: '◉', label: '用户管理', readPermission: 'admin:user:read', writePermission: 'admin:user:write' }),
  Object.freeze({ key: 'organization', icon: '▤', label: '组织架构', readPermission: 'admin:department:read', writePermission: 'admin:department:write' }),
  Object.freeze({ key: 'roles', icon: '◆', label: '角色权限', readPermission: 'admin:role:read', writePermission: 'admin:role:write' }),
  Object.freeze({ key: 'tools', icon: '□', label: '工具管理', readPermission: 'admin:tool:read', writePermission: 'admin:tool:write' }),
  Object.freeze({ key: 'models', icon: '◇', label: '模型配置', readPermission: 'admin:model:read', writePermission: 'admin:model:write' }),
  Object.freeze({ key: 'upstreamAccounts', icon: '◎', label: '中转账号配置与健康度', readPermission: 'admin:upstream-account:read', writePermission: 'admin:upstream-account:write' }),
  Object.freeze({ key: 'brandPages', icon: '▧', label: '品牌 HTML', readPermission: 'brand:page:read', writePermission: 'admin:brand-page:write' }),
  Object.freeze({ key: 'aiLogs', icon: '≋', label: '使用记录', readPermission: 'admin:ai-log:read', writePermission: null }),
  Object.freeze({ key: 'operationLogs', icon: '☷', label: '操作日志', readPermission: 'admin:log:read', writePermission: null })
])

const ADMIN_SECTION_BY_KEY = new Map(
  ADMIN_SECTION_DEFINITIONS.map((definition) => [definition.key, definition])
)

function permissionSet(permissions) {
  return new Set(Array.isArray(permissions) ? permissions : [])
}

function hasAdminAccessInSet(permissions) {
  for (const permission of permissions) {
    if (typeof permission === 'string' && permission.startsWith('admin:') && permission !== 'admin:relay-usage:read') return true
  }
  return false
}

function canReadAdminSectionFromSet(permissions, section, superAdmin = false) {
  const definition = ADMIN_SECTION_BY_KEY.get(section)
  if (!definition) return false
  if (section === 'aiLogs') return superAdmin === true
  if (definition.key === 'overview') return hasAdminAccessInSet(permissions)
  return permissions.has(definition.readPermission)
}

export function hasAdminAccess(permissions) {
  return hasAdminAccessInSet(permissionSet(permissions))
}

export function canReadAdminSection(permissions, section, superAdmin = false) {
  return canReadAdminSectionFromSet(permissionSet(permissions), section, superAdmin)
}

export function canWriteAdminSection(permissions, section) {
  const definition = ADMIN_SECTION_BY_KEY.get(section)
  if (!definition?.writePermission) return false
  return permissionSet(permissions).has(definition.writePermission)
}

export function visibleAdminSections(permissions, superAdmin = false) {
  const permissionsSet = permissionSet(permissions)
  return ADMIN_SECTION_DEFINITIONS.filter((section) => canReadAdminSectionFromSet(permissionsSet, section.key, superAdmin))
}
