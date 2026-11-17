import { workbenchFileUrl } from './workbenchFileUrl.js'

function isSafeAttachmentUrl(url) {
  return /^https?:\/\//i.test(url) || /^\/(?!\/)/.test(url)
}

export function extractRunAttachments(run) {
  if (!run?.outputData) return []
  const data = run.outputData
  const sources = []
  collectAttachmentSources(data, sources)

  if (run.toolKey === 'consumer-insight' && data.dashboardId) {
    const dashboardId = encodeURIComponent(String(data.dashboardId))
    sources.push({
      fileName: '消费者洞察看板.html',
      contentType: 'text/html',
      downloadUrl: `/tools/consumer-insight/api/dashboards/${dashboardId}/download`
    })
  }

  const seenUrls = new Set()
  return sources.flatMap((item, index) => {
    const url = workbenchFileUrl(attachmentUrl(item))
    if (!url || !isSafeAttachmentUrl(url) || seenUrls.has(url)) return []
    seenUrls.add(url)
    const name = item.fileName || item.name || `附件 ${index + 1}`
    const kind = isImageAttachment(item, url, run.toolKey) ? 'image' : 'file'
    return [{ key: `${url}-${index}`, url, name, kind }]
  })
}

function isImageAttachment(item, url, toolKey) {
  if (item.contentType?.startsWith('image/') || item.type === 'image' || /\.(png|jpe?g|webp|gif|svg)(\?|$)/i.test(url)) {
    return true
  }
  // 推广图工具转存到平台文件库后，下载地址不保留扩展名；该工具的 files 产物固定为图片。
  return toolKey === 'promo-image' && /\/api\/files\/demo-download\/file_[a-f0-9]{32}(?:\?|$)/i.test(url)
}

function collectAttachmentSources(value, sources, visited = new WeakSet()) {
  if (!value || typeof value !== 'object') return
  if (visited.has(value)) return
  visited.add(value)

  if (Array.isArray(value)) {
    value.forEach((item) => collectAttachmentSources(item, sources, visited))
    return
  }

  if (attachmentUrl(value)) sources.push(value)
  Object.values(value).forEach((item) => collectAttachmentSources(item, sources, visited))
}

function attachmentUrl(item) {
  if (!item || typeof item !== 'object') return ''
  return item.url || item.downloadUrl || item.fileUrl || item.imageUrl || ''
}

