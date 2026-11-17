import test from 'node:test'
import assert from 'node:assert/strict'
import { relayResultAttachments, relayResultText } from '../relayResult.js'

test('中转结果预览兼容文件库图片和内联图片，禁止可执行链接', () => {
  const inline = 'data:image/png;base64,aGVsbG8='
  const run = { toolKey: 'promo-image', outputData: { images: [
    { name: '图.png', url: inline }, { url: inline },
    { url: 'javascript:alert(1)' }, { url: 'data:image/svg+xml;base64,aGVsbG8=' },
    { downloadUrl: '/api/files/demo-download/file_9d55a53b25364f328838558e65c8bff5' }
  ] } }
  const attachments = relayResultAttachments(run)
  assert.equal(attachments.length, 2)
  assert.ok(attachments.every(a => a.kind === 'image'))
  assert.ok(!relayResultText(run).includes(inline))
  assert.equal(run.outputData.images[0].url, inline)
  assert.equal(relayResultText({outputSummary:'文本结果'}), '文本结果')
})
