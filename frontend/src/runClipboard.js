import { getBlob } from './api.js'
import { copyPreparedImage, copyText, prepareImageClipboardData } from './clipboard.js'
import { extractRunAttachments } from './runAttachments.js'

const imageClipboardCache = new Map()
const MAX_CACHED_IMAGES = 20

function imageAttachmentFor(run) {
  return extractRunAttachments(run).find((attachment) => attachment.kind === 'image')
}

function prepareRunImage(run, options = {}) {
  const attachment = imageAttachmentFor(run)
  if (!attachment) return null
  const cached = imageClipboardCache.get(attachment.url)
  if (cached) return cached

  const getBlobRef = options.getBlobRef ?? getBlob
  const prepareImageRef = options.prepareImageRef ?? prepareImageClipboardData
  const entry = { status: 'pending', data: null, error: null, promise: null }
  entry.promise = getBlobRef(attachment.url)
    .then((blob) => prepareImageRef(blob))
    .then((data) => {
      entry.status = 'ready'
      entry.data = data
      return data
    })
    .catch((error) => {
      entry.status = 'failed'
      entry.error = error
      throw error
    })
  // 预加载失败由点击时统一反馈，避免产生未处理的 Promise rejection。
  entry.promise.catch(() => {})
  imageClipboardCache.set(attachment.url, entry)
  while (imageClipboardCache.size > MAX_CACHED_IMAGES) {
    imageClipboardCache.delete(imageClipboardCache.keys().next().value)
  }
  return entry
}

export function preloadRunImages(runs, options = {}) {
  const promises = []
  for (const run of runs || []) {
    const entry = prepareRunImage(run, options)
    if (entry) promises.push(entry.promise)
  }
  return Promise.allSettled(promises)
}

export function clearRunImageClipboardCache() {
  imageClipboardCache.clear()
}

export async function copyRunResultToClipboard(run, options = {}) {
  const attachment = imageAttachmentFor(run)
  if (attachment) {
    const entry = prepareRunImage(run, options)
    if (entry.status === 'pending') {
      const error = new Error('图片仍在准备中')
      error.code = 'IMAGE_PREPARING'
      throw error
    }
    if (entry.status === 'failed') {
      imageClipboardCache.delete(attachment.url)
      throw entry.error
    }
    const copyPreparedImageRef = options.copyPreparedImageRef ?? copyPreparedImage
    await copyPreparedImageRef(entry.data)
    return 'image'
  }

  const text = run?.output && run.output !== '-'
    ? run.output
    : JSON.stringify(run?.outputData || {}, null, 2)
  const copyTextRef = options.copyTextRef ?? copyText
  await copyTextRef(text)
  return 'text'
}
