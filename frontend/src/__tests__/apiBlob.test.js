import test from 'node:test'
import assert from 'node:assert/strict'

import { getBlob } from '../api.js'

async function withFetchEnvironment(fetchRef, callback) {
  const originalFetch = globalThis.fetch
  const originalLocalStorage = globalThis.localStorage
  globalThis.fetch = fetchRef
  globalThis.localStorage = {
    getItem: (key) => key === 'yiqi_ai_toolbench_token' ? 'test-token' : null
  }
  try {
    await callback()
  } finally {
    globalThis.fetch = originalFetch
    if (originalLocalStorage === undefined) {
      delete globalThis.localStorage
    } else {
      globalThis.localStorage = originalLocalStorage
    }
  }
}

test('站内图片请求携带登录令牌', async () => {
  let request
  await withFetchEnvironment(async (url, options) => {
    request = { url, options }
    return {
      ok: true,
      blob: async () => new Blob(['image'], { type: 'image/png' })
    }
  }, async () => {
    await getBlob('/api/files/demo-download/file_1')
  })

  assert.equal(request.url, '/api/files/demo-download/file_1')
  assert.deepEqual(request.options.headers, { Authorization: 'Bearer test-token' })
})

test('外部图片请求不泄露登录令牌', async () => {
  let request
  await withFetchEnvironment(async (url, options) => {
    request = { url, options }
    return {
      ok: true,
      blob: async () => new Blob(['image'], { type: 'image/png' })
    }
  }, async () => {
    await getBlob('https://cdn.example.com/generated.png')
  })

  assert.equal(request.url, 'https://cdn.example.com/generated.png')
  assert.deepEqual(request.options.headers, {})
})
