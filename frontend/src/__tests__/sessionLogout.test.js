import test from 'node:test'
import assert from 'node:assert/strict'
import { revokeLoginSessionApi } from '../api.js'

test('工作台退出调用真实注销接口，已失效登录视为已退出', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
  const calls = []
  globalThis.localStorage = { getItem: () => 'isolated-test-session' }
  try {
    for (const status of [200, 401]) {
      globalThis.fetch = async (url, options) => {
        calls.push([url, options.method, options.headers.Authorization])
        return { ok: status === 200, status }
      }
      await revokeLoginSessionApi()
    }
    assert.deepEqual(calls, Array(2).fill(['/api/auth/logout', 'POST', 'Bearer isolated-test-session']))
  } finally { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})

test('注销服务或网络失败必须保留失败状态，不能宣称服务端已退出', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => 'isolated-test-session' }
  try {
    globalThis.fetch = async () => ({ ok: false, status: 503 })
    await assert.rejects(revokeLoginSessionApi(), /退出未完成/)
    globalThis.fetch = async () => { throw new Error('offline') }
    await assert.rejects(revokeLoginSessionApi(), /offline/)
  } finally { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})
