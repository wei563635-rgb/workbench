import test from 'node:test'
import assert from 'node:assert/strict'
import { windowAverageValue, windowAverageNote } from '../upstreamWindowAverage.js'

test('窗口剩余平均值明确标记样本和参考口径', () => {
  const window = { accountCount: 3, averageRemainingPercent: 46.6666667 }
  assert.equal(windowAverageValue(window), '46.7%')
  assert.match(windowAverageNote(window), /3 个正常账号有窗口读数/)
  assert.match(windowAverageNote(window), /仅供参考/)
})

test('无有效窗口读数时不显示虚假的零额度', () => {
  assert.equal(windowAverageValue({ accountCount: 0, averageRemainingPercent: null }), '—')
  assert.match(windowAverageNote({ accountCount: 0 }), /暂无正常账号/)
})
