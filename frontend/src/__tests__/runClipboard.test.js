import test from 'node:test'
import assert from 'node:assert/strict'

import { clearRunImageClipboardCache, copyRunResultToClipboard, preloadRunImages } from '../runClipboard.js'

test('图片运行记录预加载完成后使用已准备的 PNG 数据复制第一张图片', async () => {
  clearRunImageClipboardCache()
  const calls = []
  const blob = new Blob(['image'], { type: 'image/png' })
  const prepared = { blob, dataUrl: 'data:image/png;base64,aW1hZ2U=' }
  const run = {
    toolKey: 'promo-image',
    output: '生成 2 张推广图',
    outputData: {
      files: [
        { imageUrl: '/images/first.png' },
        { imageUrl: '/images/second.png' }
      ]
    }
  }
  await preloadRunImages([run], {
    getBlobRef: async (url) => {
      calls.push(['fetch', url])
      return blob
    },
    prepareImageRef: async (source) => {
      calls.push(['prepare', source])
      return prepared
    }
  })
  const result = await copyRunResultToClipboard(run, {
    copyPreparedImageRef: async (data) => calls.push(['copy-image', data])
  })

  assert.equal(result, 'image')
  assert.deepEqual(calls, [
    ['fetch', '/images/first.png'],
    ['prepare', blob],
    ['copy-image', prepared]
  ])
})

test('没有图片的运行记录继续复制文本', async () => {
  clearRunImageClipboardCache()
  const calls = []
  const result = await copyRunResultToClipboard({
    output: '分析已完成',
    outputData: { summary: '结果摘要' }
  }, {
    getBlobRef: async () => calls.push(['fetch']),
    copyTextRef: async (text) => calls.push(['copy-text', text])
  })

  assert.equal(result, 'text')
  assert.deepEqual(calls, [['copy-text', '分析已完成']])
})

test('图片尚未预加载完成时返回明确的准备中状态', async () => {
  clearRunImageClipboardCache()
  const run = { outputData: { imageUrl: '/images/pending.png' } }
  let resolveBlob
  preloadRunImages([run], {
    getBlobRef: () => new Promise((resolve) => { resolveBlob = resolve }),
    prepareImageRef: async (blob) => ({ blob, dataUrl: 'data:image/png;base64,eA==' })
  })
  await assert.rejects(
    () => copyRunResultToClipboard(run),
    (error) => error.code === 'IMAGE_PREPARING'
  )
  resolveBlob(new Blob(['x'], { type: 'image/png' }))
})
