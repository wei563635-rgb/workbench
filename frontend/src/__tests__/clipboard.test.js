import test from 'node:test'
import assert from 'node:assert/strict'

import { copyImageBlob, copyText } from '../clipboard.js'

test('非安全上下文使用文本框回退复制', async () => {
  const calls = []
  const textarea = {
    style: {},
    select: () => calls.push('select'),
    setSelectionRange: () => calls.push('range')
  }
  const documentRef = {
    createElement: () => textarea,
    body: {
      appendChild: () => calls.push('append'),
      removeChild: () => calls.push('remove')
    },
    execCommand: (command) => {
      calls.push(command)
      return true
    }
  }

  await copyText('运行结果', { navigatorRef: {}, documentRef })

  assert.equal(textarea.value, '运行结果')
  assert.deepEqual(calls, ['append', 'select', 'range', 'copy', 'remove'])
})

test('安全上下文优先使用 Clipboard API', async () => {
  const calls = []
  await copyText('运行结果', {
    navigatorRef: { clipboard: { writeText: async (text) => calls.push(text) } },
    documentRef: null
  })
  assert.deepEqual(calls, ['运行结果'])
})

test('PNG 图片直接写入系统剪贴板', async () => {
  const writes = []
  class ClipboardItemStub {
    constructor(data) {
      this.data = data
    }
  }
  const blob = new Blob(['png'], { type: 'image/png' })

  await copyImageBlob(blob, {
    navigatorRef: { clipboard: { write: async (items) => {
      writes.push(items)
      await items[0].data['image/png']
    } } },
    ClipboardItemRef: ClipboardItemStub
  })

  assert.equal(writes.length, 1)
  assert.equal(await writes[0][0].data['image/png'], blob)
})

test('非 PNG 图片转换后再写入剪贴板', async () => {
  const calls = []
  const pngBlob = new Blob(['png'], { type: 'image/png' })
  const bitmap = { width: 800, height: 600, close: () => calls.push('close') }
  const canvas = {
    getContext: () => ({ drawImage: () => calls.push('draw') }),
    toBlob: (callback, type) => {
      calls.push(type)
      callback(pngBlob)
    }
  }
  class ClipboardItemStub {
    constructor(data) {
      this.data = data
    }
  }

  await copyImageBlob(new Blob(['jpg'], { type: 'image/jpeg' }), {
    navigatorRef: { clipboard: { write: async (items) => {
      calls.push('write')
      await items[0].data['image/png']
    } } },
    ClipboardItemRef: ClipboardItemStub,
    createImageBitmapRef: async () => bitmap,
    documentRef: { createElement: () => canvas }
  })

  assert.equal(canvas.width, 800)
  assert.equal(canvas.height, 600)
  assert.deepEqual(calls, ['draw', 'image/png', 'close', 'write'])
})
