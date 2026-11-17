export const usageStatusLabels = Object.freeze({ running: '处理中', success: '成功', failed: '失败', rejected: '已拒绝', cancelled: '已取消', unknown: '结果未知', recorded: '已计量' })
export function usageValue(value, suffix = '') {
  return value === null || value === undefined ? '未提供' : `${Number(value).toLocaleString('zh-CN')}${suffix}`
}
export function usageTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '未提供'
}
export function usageRange(from, to) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to) || from > to) throw new Error('请选择有效的起止日期')
  return { from: new Date(`${from}T00:00:00+08:00`).toISOString(), until: new Date(new Date(`${to}T00:00:00+08:00`).getTime() + 86400000).toISOString() }
}
// Only recognised raster signatures become inline images; arbitrary data URLs stay text.
export function usageBase64Image(raw) {
  if (typeof raw !== 'string' || raw.length > 64 * 1024 * 1024) return null
  let value=raw.trim(), declared=null
  if (value.startsWith('data:')) {
    const match=value.match(/^data:image\/(png|jpeg|gif|webp);base64,([\s\S]+)$/i)
    if (!match) return null
    declared=match[1].toLowerCase();value=match[2]
  }
  value=value.replace(/\s/g,'')
  if (value.length<16 || value.length%4===1 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null
  let head
  try { head=atob(value.slice(0,32)) } catch { return null }
  const type=head.startsWith('\x89PNG\r\n\x1a\n')?'png':head.startsWith('\xff\xd8\xff')?'jpeg':/^GIF8[79]a/.test(head)?'gif':head.startsWith('RIFF')&&head.slice(8,12)==='WEBP'?'webp':null
  return type && (!declared || declared===type) ? `data:image/${type};base64,${value}` : null
}
export function usageImages(value) {
  const found = new Set()
  function walk(node, depth = 0) {
    if (depth > 20 || !node || found.size>=50) return
    if (Array.isArray(node)) { node.forEach(v => walk(v, depth + 1)); return }
    if (typeof node !== 'object') return
    for (const [key, item] of Object.entries(node)) {
      const inline=key==='b64_json' || key==='result' && node.type==='image_generation_call' || key==='data' && /^image\//i.test(node.mimeType || node.mime_type || '')
      if (inline) { const source=usageBase64Image(item); if(source)found.add(source) }
      else if (['url', 'image_url'].includes(key) && typeof item === 'string') {
        if (/^https?:\/\//i.test(item)) found.add(item)
        else { const source=usageBase64Image(item); if(source)found.add(source) }
      } else if (['results', 'images'].includes(key) && Array.isArray(item)) item.forEach(v => { if (typeof v === 'string' && /^https?:\/\//i.test(v)) found.add(v) })
      if (typeof item === 'object') walk(item, depth + 1)
    }
  }
  walk(value)
  return [...found].slice(0, 50)
}

export function usageCreditDetail(payload) {
  const response = payload?.taskResult || payload?.providerResponse || payload?.response || {}
  const usage = response?.completionResponse?.data?.usage || response?.usage || response?.data?.usage || {}
  for (const key of ['credits', 'credit_count', 'point_count']) {
    if (usage[key] !== null && usage[key] !== undefined && Number.isFinite(Number(usage[key]))) return `${Number(usage[key])} 积分（供应商上报）`
  }
  return usage.credits_reserved != null ? '供应商仅返回预扣积分，实际消耗未提供' : '未提供'
}
