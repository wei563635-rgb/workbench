export function emptyModelConfigForm() {
  return {
    id: '',
    provider: '',
    modelType: '',
    modelName: '',
    apiBaseUrl: '',
    apiKeyMasked: '',
    apiKey: '',
    timeoutSeconds: 60,
    retryCount: 0
  }
}

export function modelConfigFormFrom(config = {}) {
  return {
    ...emptyModelConfigForm(),
    id: config.id || '',
    provider: config.provider || '',
    originalProvider: config.provider || '',
    modelType: config.modelType || '',
    modelName: config.modelName || '',
    apiBaseUrl: config.apiBaseUrl || '',
    apiKeyMasked: config.apiKeyMasked || '',
    timeoutSeconds: config.timeoutSeconds ?? 60,
    retryCount: config.retryCount ?? 0
  }
}

export function validateModelConfigForm(form) {
  if (!String(form.modelName || '').trim()) return '模型名称不能为空'
  const apiBaseUrl = String(form.apiBaseUrl || '').trim()
  if (apiBaseUrl) {
    try {
      const url = new URL(apiBaseUrl)
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return 'API 中转地址必须是 http 或 https URL'
    } catch {
      return 'API 中转地址必须是 http 或 https URL'
    }
  }
  if (!Number.isInteger(Number(form.timeoutSeconds)) || Number(form.timeoutSeconds) < 1 || Number(form.timeoutSeconds) > 600) return '超时必须是 1 到 600 的整数'
  if (!Number.isInteger(Number(form.retryCount)) || Number(form.retryCount) < 0 || Number(form.retryCount) > 5) return '重试次数必须是 0 到 5 的整数'
  if (String(form.apiKey || '').length > 8192) return '新 Token 最长 8192 个字符'
  return ''
}

export function buildModelConfigUpdatePayload(form) {
  const payload = {
    modelName: String(form.modelName || '').trim(),
    apiBaseUrl: String(form.apiBaseUrl || '').trim(),
    timeoutSeconds: Number(form.timeoutSeconds),
    retryCount: Number(form.retryCount)
  }
  if (String(form.apiKey || '').trim()) payload.apiKey = form.apiKey
  if(form.provider && form.originalProvider !== undefined && form.provider !== form.originalProvider)payload.provider=form.provider
  return payload
}
