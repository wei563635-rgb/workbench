// Report permissions are independent of the UI entry; existing API permissions stay unchanged.
export const RELAY_REPORT_PAGES = Object.freeze([
  { page: 'relay-dashboard', path: '/workbench/token-dashboard', legacy: '/admin/relayDashboard', mode: 'dashboard', label: 'Token 消耗看板', icon: '◫' },
  { page: 'relay-employees', path: '/workbench/employee-tokens', legacy: '/admin/relayEmployees', mode: 'employees', label: '用户 Token 消耗', icon: '◉' }
])
export function relayReportRoute(path) {
  const normalized = path.replace(/\/$/, '')
  return RELAY_REPORT_PAGES.find(item => item.path === normalized || item.legacy === normalized) || null
}
export function canViewRelayReports(permissions) {
  return Array.isArray(permissions) && permissions.includes('admin:relay-usage:read')
}
