import test from 'node:test'
import assert from 'node:assert/strict'
import { extractRunAttachments } from '../runAttachments.js'

test('运行附件支持平台同域相对下载地址', () => {
  const attachments = extractRunAttachments({
    toolKey: 'market-intelligence',
    outputData: {
      attachments: [{ fileName: '市场报告.pdf', downloadUrl: '/api/files/demo-download/file_123' }]
    }
  })

  assert.deepEqual(attachments, [{
    key: '/api/files/demo-download/file_123-0',
    url: '/api/files/demo-download/file_123',
    name: '市场报告.pdf',
    kind: 'file'
  }])
})

test('推广图文件库地址没有扩展名时按图片预览', () => {
  const attachments = extractRunAttachments({
    toolKey: 'promo-image',
    outputData: {
      files: [{ downloadUrl: '/api/files/demo-download/file_9d55a53b25364f328838558e65c8bff5' }]
    }
  })

  assert.equal(attachments[0].kind, 'image')
})

test('历史 HTTP 文件地址在当前工作台域名下打开', () => {
  const fileId = 'file_9d55a53b25364f328838558e65c8bff5'
  const attachments = extractRunAttachments({
    toolKey: 'cloud-ai',
    outputData: { files: [{ contentType: 'image/png', downloadUrl: `http://120.26.221.135:18081/api/files/demo-download/${fileId}` }] }
  })

  assert.equal(attachments[0].url, `/api/files/demo-download/${fileId}`)
  assert.equal(attachments[0].kind, 'image')
})

test('消费者洞察 dashboardId 映射为可下载的 HTML 产出附件', () => {
  const attachments = extractRunAttachments({
    toolKey: 'consumer-insight',
    outputData: { dashboardId: 'dashboard_123' }
  })

  assert.equal(attachments.length, 1)
  assert.equal(attachments[0].name, '消费者洞察看板.html')
  assert.equal(attachments[0].url, '/tools/consumer-insight/api/dashboards/dashboard_123/download')
})

test('运行附件从完整嵌套返回中提取图片和下载链接', () => {
  const attachments = extractRunAttachments({
    toolKey: 'promo-image',
    outputData: {
      result: {
        imageUrl: '/api/files/demo-download/poster_1',
        files: [{ name: '推广图说明.txt', url: '/api/files/demo-download/notes_1' }]
      }
    }
  })

  assert.deepEqual(attachments.map((attachment) => ({ name: attachment.name, url: attachment.url, kind: attachment.kind })), [
    { name: '附件 1', url: '/api/files/demo-download/poster_1', kind: 'file' },
    { name: '推广图说明.txt', url: '/api/files/demo-download/notes_1', kind: 'file' }
  ])
})
