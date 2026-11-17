import test from 'node:test'
import assert from 'node:assert/strict'
import { formatAiLogBilling, formatResourceUsage, formatResourceUsageMetrics } from '../aiCost.js'

test('生图日志直接显示供应商积分且不伪造 Token', () => {
  assert.deepEqual(formatAiLogBilling({
    modelType: 'image',
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    costEstimate: 0,
    responseBody: {
      usage: {
        billing_rule: 'per_token',
        credits_reserved: 0.3645
      }
    }
  }), {
    promptTokens: '—',
    completionTokens: '—',
    totalTokens: '—',
    cost: '0.3645 积分'
  })
})

test('资源用量按原始单位分别展示 Token 和积分', () => {
  assert.equal(formatResourceUsage(1904, 0), '1904 Token')
  assert.equal(formatResourceUsage(0, '0.3645'), '0.3645 积分')
  assert.equal(formatResourceUsage(1904, '0.3645'), '1904 Token / 0.3645 积分')
  assert.equal(formatResourceUsage(0, 0, '0'), '0')
})

test('首页资源消耗按双指标格式化', () => {
  assert.deepEqual(formatResourceUsageMetrics(47966, '10.5512'), {
    tokens: '47,966',
    credits: '10.5512'
  })
  assert.deepEqual(formatResourceUsageMetrics(null, null), { tokens: '0', credits: '0' })
})

test('文本日志保留现有 Token 与人民币成本展示', () => {
  assert.deepEqual(formatAiLogBilling({
    modelType: 'text',
    promptTokens: 8661,
    completionTokens: 2477,
    totalTokens: 11138,
    costEstimate: 0.22276
  }), {
    promptTokens: 8661,
    completionTokens: 2477,
    totalTokens: 11138,
    cost: '¥0.22276'
  })
})

test('历史中转图片日志从响应计量恢复 Token 展示', () => {
  assert.deepEqual(formatAiLogBilling({
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    responseBody: {
      _platform: { billingSource: 'relay' },
      usage: { input_tokens: 17, output_tokens: 29, total_tokens: 46 }
    }
  }), {
    promptTokens: 17,
    completionTokens: 29,
    totalTokens: 46,
    cost: '中转站实际计量'
  })
})

test('Coze 日志按积分计费且不把技术计数当成 Token 账单', () => {
  assert.deepEqual(formatAiLogBilling({
    modelName: 'coze-copywriting',
    modelType: 'chat',
    promptTokens: 8661,
    completionTokens: 2477,
    totalTokens: 11138,
    costEstimate: 0.22276,
    responseBody: {}
  }), {
    promptTokens: '—',
    completionTokens: '—',
    totalTokens: '—',
    cost: '积分未返回'
  })
})

test('Coze 新日志优先展示最终响应的积分', () => {
  assert.equal(formatAiLogBilling({
    modelName: 'coze-copywriting',
    modelType: 'chat',
    responseBody: {
      _platform: { provider: 'coze', billingUnit: 'credits' },
      completionResponse: { data: { usage: { credits: 2.75 } } }
    }
  }).cost, '2.75 积分')
})

test('生图响应缺少有效 credits 时不再误报零成本', () => {
  for (const credits of [undefined, '', -1, 'invalid']) {
    const billing = formatAiLogBilling({
      modelType: 'image',
      responseBody: { usage: { credits_reserved: credits } }
    })
    assert.equal(billing.cost, '—')
  }
})
