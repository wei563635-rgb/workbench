import test from 'node:test'
import assert from 'node:assert/strict'
import { buildModelConfigUpdatePayload, emptyModelConfigForm, modelConfigFormFrom, validateModelConfigForm } from '../modelConfigEditor.js'

test('回填模型配置不会回填新 Token', () => {
  const form = modelConfigFormFrom({ id: 'm1', provider: 'openai', modelType: 'chat', modelName: 'gpt', apiBaseUrl: 'https://api.example.com', apiKeyMasked: 'sk-***', apiKey: 'secret', timeoutSeconds: 30, retryCount: 2 })
  assert.equal(form.apiKey, '')
  assert.equal(form.apiKeyMasked, 'sk-***')
  assert.equal(form.modelName, 'gpt')
})

test('空白 Token 不写入更新载荷', () => {
  const payload = buildModelConfigUpdatePayload({ ...emptyModelConfigForm(), modelName: ' gpt ', apiBaseUrl: ' https://api.example.com ', apiKey: '  ', timeoutSeconds: '30', retryCount: '2' })
  assert.deepEqual(payload, { modelName: 'gpt', apiBaseUrl: 'https://api.example.com', timeoutSeconds: 30, retryCount: 2 })
})

test('非空 Token 原样写入更新载荷', () => {
  const payload = buildModelConfigUpdatePayload({ ...emptyModelConfigForm(), modelName: 'gpt', apiKey: ' token-with-spaces ' })
  assert.equal(payload.apiKey, ' token-with-spaces ')
})

test('模型配置表单校验覆盖每种错误', () => {
  const valid = { ...emptyModelConfigForm(), modelName: 'gpt', apiBaseUrl: 'https://api.example.com' }
  assert.equal(validateModelConfigForm({ ...valid, modelName: ' ' }), '模型名称不能为空')
  assert.equal(validateModelConfigForm({ ...valid, apiBaseUrl: 'ftp://api.example.com' }), 'API 中转地址必须是 http 或 https URL')
  assert.equal(validateModelConfigForm({ ...valid, timeoutSeconds: 0 }), '超时必须是 1 到 600 的整数')
  assert.equal(validateModelConfigForm({ ...valid, retryCount: 6 }), '重试次数必须是 0 到 5 的整数')
  assert.equal(validateModelConfigForm({ ...valid, apiKey: 'a'.repeat(8193) }), '新 Token 最长 8192 个字符')
  assert.equal(validateModelConfigForm(valid), '')
})
