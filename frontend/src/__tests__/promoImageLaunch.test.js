import test from 'node:test'
import assert from 'node:assert/strict'

import { buildPromoImageEntry, openPromoImageWindow } from '../promoImageLaunch.js'

test('推广图入口使用工作台 cloud-ai 票据进入新工具', () => {
  assert.equal(buildPromoImageEntry({
    entryUrl: '/tools/cloud-ai/platform-entry?ticket=x'
  }, 'https://www.sharpseek.cn/tools/cloud-ai/'), 'https://www.sharpseek.cn/tools/cloud-ai/platform-entry?ticket=x')
  assert.throws(() => buildPromoImageEntry({
    entryUrl: '/tools/promo-image/platform-entry?ticket=x'
  }, 'https://www.sharpseek.cn/tools/cloud-ai/'), /无效/)
})

test('推广图在用户点击时先打开新页面，票据签发后再跳转', async () => {
  const calls = []
  const toolWindow = {
    opener: {},
    location: { href: '' },
    close() { calls.push('close') }
  }

  await openPromoImageWindow(
    () => { calls.push('open'); return toolWindow },
    async () => {
      calls.push('ticket')
      return { entryUrl: '/tools/cloud-ai/platform-entry?ticket=x' }
    },
    'https://www.sharpseek.cn/tools/cloud-ai/'
  )

  assert.deepEqual(calls, ['open', 'ticket'])
  assert.equal(toolWindow.opener, null)
  assert.equal(toolWindow.location.href, 'https://www.sharpseek.cn/tools/cloud-ai/platform-entry?ticket=x')
})

test('推广图票据签发失败时关闭空白页，弹窗被拦截时不消耗票据', async () => {
  let closed = false
  let issued = 0
  const ticketFailure = new Error('ticket failed')

  await assert.rejects(
    openPromoImageWindow(
      () => ({ opener: {}, location: { href: '' }, close() { closed = true } }),
      async () => { throw ticketFailure },
      'https://www.sharpseek.cn/tools/cloud-ai/'
    ),
    ticketFailure
  )
  assert.equal(closed, true)

  await assert.rejects(
    openPromoImageWindow(() => null, async () => { issued += 1 }, 'https://www.sharpseek.cn/tools/cloud-ai/'),
    /浏览器阻止了新页面/
  )
  assert.equal(issued, 0)
})
