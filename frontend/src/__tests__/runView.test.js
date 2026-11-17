import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { buildRunDetailPayload, filterRunRecords, formatRunDuration, hasCopyableRunResult, hasDownloadableRunAttachment, paginateRecords, sortRunRecordsNewestFirst, splitRunTimestamp } from '../runView.js'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')

function extractTemplate(source) {
  const tagPattern = /<\/?template\b[^>]*>/gi
  const openingTag = tagPattern.exec(source)
  if (!openingTag || openingTag[0].startsWith('</')) return ''

  const contentStart = tagPattern.lastIndex
  let depth = 1
  let tag
  while ((tag = tagPattern.exec(source))) {
    depth += tag[0].startsWith('</') ? -1 : 1
    if (depth === 0) {
      return source.slice(contentStart, tag.index).replace(/<!--[\s\S]*?-->/g, '')
    }
  }
  return ''
}

const template = extractTemplate(app)

const records = [
  { id: 'run_001', toolKey: 'promo-image', tool: '推广图制作', deptKey: 'ai-design', dept: 'AI 设计部', status: '成功', statusKey: 'success', user: '张三', project: '测试项目', input: '主图', output: '已生成' },
  { id: 'run_002', toolKey: 'consumer-insight', tool: '消费者洞察', deptKey: 'ai-market', dept: 'AI 市场部', status: '进行中', statusKey: 'running', user: '李四', project: '测试项目', input: '洞察', output: '-' },
  { id: 'run_003', toolKey: 'consumer-insight', tool: '消费者洞察', deptKey: 'ai-market', dept: 'AI 市场部', status: '失败', statusKey: 'failed', user: '王五', project: '其他项目', input: '分析', output: '解析失败' }
]

test('运行记录支持关键词搜索', () => {
  assert.deepEqual(filterRunRecords(records, 'run_001', { tool: 'all', department: 'all', status: 'all' }).map((item) => item.id), ['run_001'])
  assert.deepEqual(filterRunRecords(records, '不存在', { tool: 'all', department: 'all', status: 'all' }), [])
})

test('工具、部门和状态组合筛选使用真实字段', () => {
  assert.deepEqual(filterRunRecords(records, '', {
    tool: 'consumer-insight',
    department: 'ai-market',
    status: 'failed'
  }).map((item) => item.id), ['run_003'])
})

test('员工筛选使用运行员工字段', () => {
  assert.deepEqual(filterRunRecords(records, '', {
    employee: '王五',
    tool: 'all',
    department: 'all',
    status: 'all'
  }).map((item) => item.id), ['run_003'])
})

test('运行记录部门取运行员工的主部门，不取工具部门', () => {
  assert.match(app, /const employee = runUsers\.value\[run\.userId\] \|\| users\.value\.find\(\(item\) => item\.id === run\.userId\)/)
  assert.match(app, /const primaryDepartmentId = employee\?\.primaryDepartmentId \|\| employee\?\.departmentKeys\?\.\[0\] \|\| ''/)
  assert.match(app, /deptKey: primaryDepartmentId/)
})

test('运行记录按固定每页数分页', () => {
  const items = Array.from({ length: 23 }, (_, index) => ({ id: `run_${index + 1}` }))
  assert.deepEqual(paginateRecords(items, 2, 10).map((item) => item.id), items.slice(10, 20).map((item) => item.id))
})

test('运行记录按完成时间从新到旧排列', () => {
  const sorted = sortRunRecordsNewestFirst([
    { runId: 'older', finishedAt: '2026-07-07T10:00:00Z' },
    { runId: 'newer', finishedAt: '2026-07-13T10:00:00Z' }
  ])
  assert.deepEqual(sorted.map((item) => item.runId), ['newer', 'older'])
})

test('运行详情始终展示产出附件区和未上报空状态', () => {
  assert.match(app, /产出附件（\{\{ selectedRunAttachments\.length \}\}）/)
  assert.match(app, /该运行记录未上报产出附件/)
})

test('运行详情 JSON 保留完整提示词和原始返回', () => {
  assert.deepEqual(buildRunDetailPayload({
    input: '生成夏季新品海报，强调清爽口感与限时优惠。',
    raw: { runId: 'run_001', outputData: { conversationId: 'conversation_001' } }
  }), {
    runId: 'run_001',
    inputPrompt: '生成夏季新品海报，强调清爽口感与限时优惠。',
    outputData: { conversationId: 'conversation_001' }
  })
})

test('运行时间拆分为稳定的日期和时间', () => {
  assert.deepEqual(splitRunTimestamp('2026-07-07T10:51:43Z'), {
    date: '2026-07-07',
    clock: '18:51:43'
  })
})

test('运行耗时根据开始和结束时间计算', () => {
  assert.equal(formatRunDuration('2026-07-07T10:51:41Z', '2026-07-07T10:51:43.4Z'), '2.4s')
  assert.equal(formatRunDuration(null, '2026-07-07T10:51:43.4Z'), '-')
})

test('运行操作根据输出和附件真实显隐', () => {
  assert.equal(hasCopyableRunResult({ output: '已完成', outputData: {} }), true)
  assert.equal(hasCopyableRunResult({ output: '-', outputData: {} }), false)
  assert.equal(hasCopyableRunResult({ output: '-', outputData: {}, hasOutputData: true }), true)
  assert.equal(hasDownloadableRunAttachment({
    toolKey: 'market-intelligence',
    outputData: { attachments: [{ downloadUrl: '/api/files/demo-download/file_1' }] }
  }), true)
  assert.equal(hasDownloadableRunAttachment({ hasAttachments: true, outputData: {} }), true)
})

test('图片运行记录的复制按钮明确标注复制图片', () => {
  assert.match(app, /:title="runCopyActionLabel\(record\)"/)
  assert.match(app, /return record\.hasImage \|\| extractRunAttachments\(record\)\.some\(\(attachment\) => attachment\.kind === 'image'\)[\s\S]*\? '复制图片'/)
})

test('运行列表使用独立记录行且不恢复关联状态', () => {
  assert.match(app, /run-records-table/)
  assert.match(app, /run-record-row/)
  assert.doesNotMatch(app, /runFilters\.association/)
  assert.doesNotMatch(app, /重新关联项目/)
})

test('运行详情继续使用右侧抽屉', () => {
  assert.match(template, /<DetailDrawer(?=[\s/>])[^>]*:open="Boolean\(selectedRun\)"/s)
})

test('运行详情使用高层抽屉避免被项目详情遮挡', () => {
  assert.match(template, /<DetailDrawer(?=[\s/>])[^>]*:open="Boolean\(selectedRun\)"[^>]*\bstacked\b/s)
})
