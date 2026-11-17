import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const app = fs.readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const styles = fs.readFileSync(new URL('../styles.css', import.meta.url), 'utf8')

test('无可用工具的部门在侧栏置灰且不可点击', () => {
  assert.match(app, /:aria-disabled="!departmentHasAvailableTools\(dept\)"/)
  assert.match(app, /:tabindex="departmentHasAvailableTools\(dept\) \? 0 : -1"/)
  assert.match(app, /'department-disabled': !departmentHasAvailableTools\(dept\)/)
  assert.match(app, /if \(!departmentHasAvailableTools\(department\)\) return/)
  assert.match(styles, /\.nav-links li\.department-disabled/)
})

test('未接入工具卡片显示状态并保持不可操作', () => {
  assert.match(app, /v-if="tool\.status !== 'normal'" class="tool-unavailable-badge">未接入<\/span>/)
  assert.match(app, /:tabindex="tool\.status === 'normal' \? 0 : -1"/)
  assert.match(app, /:aria-disabled="tool\.status !== 'normal'"/)
  assert.match(styles, /\.tool-unavailable-badge/)
})

test('当前部门不可用时自动回退到第一个可用部门', () => {
  assert.match(app, /function ensureActiveDepartmentAvailable\(\)/)
  assert.match(app, /departments\.value\.find\(departmentHasAvailableTools\)/)
  assert.match(app, /ensureActiveDepartmentAvailable\(\)/)
})
