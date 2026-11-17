import test from 'node:test'
import assert from 'node:assert/strict'
import {relayReportRoute,canViewRelayReports} from '../relayNavigation.js'
import {visibleAdminSections,hasAdminAccess} from '../adminPermissions.js'
test('消耗页面旧后台链接转为前台入口，刷新与尾斜杠可识别',()=>{
  assert.equal(relayReportRoute('/admin/relayDashboard').path,'/workbench/token-dashboard')
  assert.equal(relayReportRoute('/workbench/employee-tokens/').mode,'employees')
  assert.equal(relayReportRoute('/admin/relayQuotas'),null)
})
test('移动前台不扩大统计权限，也不授予后台入口',()=>{
  const reports=['workbench:access','admin:relay-usage:read']
  assert.equal(canViewRelayReports(reports),true)
  assert.equal(canViewRelayReports(['workbench:access']),false)
  assert.equal(hasAdminAccess(reports),false)
  assert.deepEqual(visibleAdminSections(reports),[])
  assert.deepEqual(visibleAdminSections([...reports,'admin:relay-quota:read']).map(s=>s.key),['relayQuotas','overview'])
})
