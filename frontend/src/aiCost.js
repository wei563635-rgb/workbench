function finiteNonNegativeNumber(value) {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

function trimFixed(value, digits) {
  return value.toFixed(digits).replace(/\.?0+$/, '')
}

export function formatResourceUsage(totalTokens, credits, emptyValue = '—') {
  const normalizedTokens = finiteNonNegativeNumber(totalTokens)
  const normalizedCredits = finiteNonNegativeNumber(credits)
  const parts = []
  if (normalizedTokens > 0) parts.push(`${Math.round(normalizedTokens)} Token`)
  if (normalizedCredits > 0) parts.push(`${trimFixed(normalizedCredits, 4)} 积分`)
  return parts.length ? parts.join(' / ') : emptyValue
}

export function formatResourceUsageMetrics(totalTokens, credits) {
  const normalizedTokens = finiteNonNegativeNumber(totalTokens)
  const normalizedCredits = finiteNonNegativeNumber(credits)
  return {
    tokens: normalizedTokens == null ? '0' : Math.round(normalizedTokens).toLocaleString('en-US'),
    credits: normalizedCredits == null ? '0' : trimFixed(normalizedCredits, 4)
  }
}

export function formatAiLogBilling(log) {
  if (log?.responseBody?._platform?.billingSource === 'relay') {
    const usage = log.responseBody.usage || {}
    const input = finiteNonNegativeNumber(usage.prompt_tokens ?? usage.input_tokens)
    const output = finiteNonNegativeNumber(usage.completion_tokens ?? usage.output_tokens)
    const total = finiteNonNegativeNumber(usage.total_tokens) ?? (input != null && output != null ? input + output : null)
    return {
      promptTokens: log.promptTokens || input || 0,
      completionTokens: log.completionTokens || output || 0,
      totalTokens: log.totalTokens || total || 0,
      cost: '中转站实际计量'
    }
  }
  const provider = String(log?.responseBody?._platform?.provider || '').toLocaleLowerCase()
  const billingUnit = String(log?.responseBody?._platform?.billingUnit || '').toLocaleLowerCase()
  const cozeBilled = provider === 'coze' || String(log?.modelName || '').toLocaleLowerCase().startsWith('coze-')
  const creditBilled = log?.modelType === 'image' || billingUnit === 'credits' || cozeBilled
  if (!creditBilled) {
    return {
      promptTokens: log?.promptTokens ?? '—',
      completionTokens: log?.completionTokens ?? '—',
      totalTokens: log?.totalTokens ?? '—',
      cost: log?.costEstimate == null ? '—' : `¥${log.costEstimate}`
    }
  }

  const completionUsage = log?.responseBody?.completionResponse?.data?.usage
  const rawCredits = log?.responseBody?.usage?.credits_reserved
    ?? completionUsage?.credits_reserved
    ?? completionUsage?.credits
    ?? completionUsage?.credit_count
    ?? completionUsage?.point_count
  const credits = finiteNonNegativeNumber(rawCredits)
  const cost = credits == null
    ? (cozeBilled ? '积分未返回' : '—')
    : `${trimFixed(credits, 4)} 积分`

  // 积分计费的供应商不在 Token 列中混用技术计数。
  return {
    promptTokens: '—',
    completionTokens: '—',
    totalTokens: '—',
    cost
  }
}
