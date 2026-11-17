import { extractRunAttachments } from './runAttachments.js'

const rasterData = /^data:image\/(?:png|jpeg|webp|gif);base64,[a-z0-9+/=\s]+$/i

export function relayResultAttachments(run) {
  const attachments = extractRunAttachments(run)
  const seen = new Set(attachments.map(a => a.url))
  function visit(value) {
    if (!value || typeof value !== 'object') return
    const url = value.url || value.imageUrl
    if (typeof url === 'string' && rasterData.test(url) && !seen.has(url)) {
      seen.add(url)
      attachments.push({ key: `inline-${attachments.length}`, url, kind: 'image', name: value.name || '生成图片' })
    }
    Object.values(value).forEach(visit)
  }
  visit(run?.outputData)
  return attachments
}

export function relayResultText(run) {
  const output = run?.outputData || run?.outputSummary || {}
  if (typeof output === 'string') return output
  return JSON.stringify(output, (_key, value) => typeof value === 'string' && rasterData.test(value) ? '[图片见上方预览]' : value, 2)
}
