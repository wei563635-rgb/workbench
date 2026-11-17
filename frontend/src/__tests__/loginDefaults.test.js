import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')

test('登录页不预填账号和密码', () => {
  assert.match(app, /const loginForm = ref\(\{ account: '', password: '' \}\)/)
  assert.doesNotMatch(app, /const loginForm = ref\(\{ account: 'admin', password: 'admin123' \}\)/)
})
