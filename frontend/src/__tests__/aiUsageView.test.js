import test from 'node:test'
import assert from 'node:assert/strict'
import { usageValue, usageRange, usageImages } from '../aiUsageView.js'
import { canReadAdminSection, visibleAdminSections } from '../adminPermissions.js'
test('未知用量与真实零用量分开，日期使用北京时间且包含结束日',()=>{
 assert.equal(usageValue(null),'未提供');assert.equal(usageValue(0),'0')
 assert.deepEqual(usageRange('2026-11-01','2026-11-02'),{from:'2026-10-31T16:00:00.000Z',until:'2026-11-02T16:00:00.000Z'})
 assert.throws(()=>usageRange('2026-11-02','2026-11-01'))
})
test('图片结果仅接受 HTTP 链接且去重，不执行模型生成的脚本',()=>{
 assert.deepEqual(usageImages({data:[{url:'javascript:alert(1)'},{url:'https://example.com/image.png'},{url:'https://example.com/image.png'}]}),['https://example.com/image.png'])
})
test('普通日志授权不允许查看使用记录，内置超级管理员入口可见',()=>{
 assert.equal(canReadAdminSection(['admin:ai-log:read'],'aiLogs'),false)
 assert.equal(canReadAdminSection(['admin:ai-log:read'],'aiLogs',true),true)
 assert.equal(visibleAdminSections(['admin:ai-log:read']).some(v=>v.key==='aiLogs'),false)
})

test('预扣积分不能显示成实际消耗',async()=>{
 const { usageCreditDetail }=await import('../aiUsageView.js')
 assert.equal(usageCreditDetail({response:{usage:{credits_reserved:2}}}),'供应商仅返回预扣积分，实际消耗未提供')
 assert.equal(usageCreditDetail({response:{usage:{credits:0}}}),'0 积分（供应商上报）')
})
