const API_BASE = ''
const TOKEN_KEY = 'yiqi_ai_toolbench_token'
const USER_KEY = 'yiqi_ai_toolbench_user'

export function hasAccessToken() {
  return Boolean(localStorage.getItem(TOKEN_KEY))
}

export function getStoredUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function logoutApi() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export async function revokeLoginSessionApi() {
  const response = await fetch(`${API_BASE}/api/auth/logout`, {
    method: 'POST', headers: authHeaders()
  })
  // 已失效的登录无需再次注销；网络或服务失败时不假装已撤销服务端会话。
  if (!response.ok && response.status !== 401) throw new Error('工作台退出未完成，请稍后重试')
}

function authHeaders() {
  const token = localStorage.getItem(TOKEN_KEY)
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export async function getJson(path) {
  const response = await fetch(`${API_BASE}${path}`, { headers: authHeaders() })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function getBlob(path) {
  const target = String(path || '')
  const currentOrigin = globalThis.location?.origin
  const isRelativeUrl = /^\/(?!\/)/.test(target)
  const isSameOriginUrl = currentOrigin && new URL(target, currentOrigin).origin === currentOrigin
  const response = await fetch(target, {
    headers: isRelativeUrl || isSameOriginUrl ? authHeaders() : {}
  })
  if (!response.ok) throw new Error(await response.text())
  return response.blob()
}

export async function postJson(path, body) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(body)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function putJson(path, body) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(body)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function loginApi(account, password) {
  const result = await postJson('/api/auth/login', { account, password })
  if (result.accessToken) localStorage.setItem(TOKEN_KEY, result.accessToken)
  if (result.user) localStorage.setItem(USER_KEY, JSON.stringify(result.user))
  return result
}

export async function changePasswordApi(oldPassword, newPassword) {
  return postJson('/api/auth/change-password', { oldPassword, newPassword })
}

export async function currentUserApi() {
  const user = await getJson('/api/auth/me')
  localStorage.setItem(USER_KEY, JSON.stringify(user))
  return user
}

export async function publicStatsApi() {
  const response = await fetch('/api/public/stats')
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function loadBootstrap() {
  return getJson('/api/bootstrap')
}

export async function startToolRun(payload) {
  return postJson('/api/tool-runs/start', payload)
}

export async function createToolSessionTicket(toolKey, projectId) {
  return postJson('/api/tool-sessions/tickets', { toolKey, projectId })
}

export async function resolveDetailPageEntry(projectId) {
  return postJson('/api/tool-sessions/detail-page-entry', { projectId })
}

export async function getToolRun(runId) {
  return getJson(`/api/tool-runs/${encodeURIComponent(runId)}`)
}

export async function validateManifest(payload) {
  return postJson('/developer/validate', payload)
}

export async function requestDeployment(payload) {
  return postJson('/api/admin/deployments/request', payload)
}

export async function createUser(payload) {
  return postJson('/api/admin/users', payload)
}

export async function updateUser(userId, payload) {
  return putJson(`/api/admin/users/${userId}`, payload)
}

export async function deleteJson(path) {
  const response = await fetch(path, { method: 'DELETE', headers: authHeaders() })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function searchBrandPages(keyword = '', departmentId = '', admin = false) {
  const departmentQuery = departmentId ? `&departmentId=${encodeURIComponent(departmentId)}` : ''
  return getJson(`/api/brand-pages?keyword=${encodeURIComponent(keyword)}${departmentQuery}${admin ? '&admin=true' : ''}`)
}

export async function getBrandPageContent(pageId, admin = false) {
  const response = await fetch(`/api/brand-pages/${encodeURIComponent(pageId)}/content${admin ? '?admin=true' : ''}`, { headers: authHeaders() })
  if (!response.ok) throw new Error(await response.text())
  return response.text()
}

function appendBrandPageViewers(body, viewerDepartmentIds, viewerUserIds) {
  for (const id of viewerDepartmentIds || []) body.append('viewerDepartmentIds', id)
  for (const id of viewerUserIds || []) body.append('viewerUserIds', id)
}

export async function uploadBrandPage(brandName, departmentId, viewerDepartmentIds, viewerUserIds, file) {
  const body = new FormData()
  body.append('brandName', brandName)
  body.append('departmentId', departmentId)
  appendBrandPageViewers(body, viewerDepartmentIds, viewerUserIds)
  body.append('file', file)
  const response = await fetch('/api/admin/brand-pages', { method: 'POST', headers: authHeaders(), body })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function updateBrandPage(pageId, brandName, departmentId, viewerDepartmentIds, viewerUserIds, file) {
  const body = new FormData()
  body.append('brandName', brandName)
  body.append('departmentId', departmentId)
  appendBrandPageViewers(body, viewerDepartmentIds, viewerUserIds)
  if (file) body.append('file', file)
  const response = await fetch(`/api/admin/brand-pages/${encodeURIComponent(pageId)}`, {
    method: 'PUT',
    headers: authHeaders(),
    body
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function deleteBrandPage(pageId) {
  return deleteJson(`/api/admin/brand-pages/${encodeURIComponent(pageId)}`)
}

export async function deleteUser(userId) {
  return deleteJson(`/api/admin/users/${userId}`)
}

export async function deleteOrganizationDepartment(departmentId) {
  return deleteJson(`/api/admin/organization-departments/${departmentId}`)
}

export async function deleteRole(roleCode) {
  return deleteJson(`/api/admin/roles/${roleCode}`)
}

export async function loadAdminPage(section, page = 1, pageSize = 10, filters = {}) {
  const paths = {
    users: '/api/admin/users',
    roles: '/api/admin/roles',
    tools: '/api/admin/tools',
    models: '/api/admin/model-configs',
    upstreamAccounts: '/api/admin/upstream-accounts',
    aiLogs: '/api/admin/ai-call-logs',
    operationLogs: '/api/admin/operation-logs',
    deployments: '/api/admin/deployments'
    ,brandPages: '/api/admin/brand-pages'
  }
  if (!paths[section]) throw new Error(`不支持分页的后台模块: ${section}`)
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  Object.entries(filters || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  })
  return getJson(`${paths[section]}?${params.toString()}`)
}

export async function createProject(payload) {
  return postJson('/api/projects', payload)
}

export async function updateProject(projectId, payload) {
  const response = await fetch(`/api/projects/${projectId}`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function uploadProjectCover(projectId, file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch(`/api/projects/${projectId}/cover`, {
    method: 'POST',
    headers: authHeaders(),
    body: formData
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function deleteProjectCover(projectId) {
  return deleteJson(`/api/projects/${projectId}/cover`)
}

export async function deleteProject(projectId) {
  return deleteJson(`/api/projects/${projectId}`)
}

export async function resetPassword(userId) {
  return postJson(`/api/admin/users/${userId}/reset-password`, {})
}

export async function updateUserStatus(userId, enabled) {
  return postJson(`/api/admin/users/${userId}/status`, { enabled })
}

export async function updateUserRoles(userId, roleIds) {
  const response = await fetch(`/api/admin/users/${userId}/roles`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ roleIds })
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function createOrganizationDepartment(payload) {
  return postJson('/api/admin/organization-departments', payload)
}

export async function updateOrganizationDepartment(departmentId, payload) {
  const response = await fetch(`/api/admin/organization-departments/${departmentId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function updateOrganizationDepartmentStatus(departmentId, enabled) {
  return postJson(`/api/admin/organization-departments/${departmentId}/status`, { enabled })
}

export async function updateToolStatus(toolKey, status) {
  const response = await fetch(`/api/admin/tools/${toolKey}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ status })
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function createRole(payload) {
  return postJson('/api/admin/roles', payload)
}

export async function updateRole(roleCode, payload) {
  const response = await fetch(`/api/admin/roles/${roleCode}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function updateRoleStatus(roleCode, enabled) {
  return postJson(`/api/admin/roles/${roleCode}/status`, { enabled })
}

export async function updateProjectStatus(projectId, status) {
  return postJson(`/api/projects/${projectId}/status`, { status })
}

export async function updateModelConfig(configId, payload) {
  const response = await fetch(`/api/admin/model-configs/${configId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}

export async function loadUpstreamAccountConfiguration() {
  return getJson('/api/admin/upstream-accounts/configuration')
}

export async function loadUpstreamAccountProxies() {
  return getJson('/api/admin/upstream-accounts/proxies')
}

export async function startUpstreamAccountOAuth(payload) {
  return postJson('/api/admin/upstream-accounts/oauth/start', payload)
}

export async function completeUpstreamAccountOAuth(payload) {
  return postJson('/api/admin/upstream-accounts/oauth/complete', payload)
}

export async function updateUpstreamAccountStatus(accountId, enabled) {
  return putJson(`/api/admin/upstream-accounts/${accountId}/status`, { enabled })
}

export async function testUpstreamAccount(accountId) {
  return postJson(`/api/admin/upstream-accounts/${accountId}/test`, {})
}

export async function refreshUpstreamAccount(accountId) {
  return postJson(`/api/admin/upstream-accounts/${accountId}/refresh`, {})
}

export async function deleteUpstreamAccount(accountId) {
  return deleteJson(`/api/admin/upstream-accounts/${accountId}`)
}

export async function linkProject(payload) {
  return postJson('/api/project-links', payload)
}

export async function unlinkProject(linkId) {
  const response = await fetch(`/api/project-links/${linkId}`, { method: 'DELETE', headers: authHeaders() })
  if (!response.ok) throw new Error(await response.text())
  return response.json()
}
