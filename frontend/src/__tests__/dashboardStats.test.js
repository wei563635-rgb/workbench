import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')

test('工作台统计使用真实业务口径字段', () => {
  assert.match(app, /statValue\('normalToolCount'\)/)
  assert.match(app, /statValue\('enabledDepartmentCount'\)/)
  assert.doesNotMatch(app, /key === 'enabledDepartmentCount'/)
  assert.match(app, /statValue\('enabledUserCount'\)/)
  assert.match(app, /statValue\('generatedImageCount'\)/)
})
