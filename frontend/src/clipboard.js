export async function copyText(text, options = {}) {
  const navigatorRef = options.navigatorRef ?? globalThis.navigator
  const documentRef = options.documentRef ?? globalThis.document

  if (navigatorRef?.clipboard?.writeText) {
    try {
      await navigatorRef.clipboard.writeText(text)
      return
    } catch {
      // Clipboard API 不可用时继续使用 DOM 复制。
    }
  }

  if (!documentRef?.body || typeof documentRef.execCommand !== 'function') {
    throw new Error('当前浏览器不支持复制')
  }

  const textarea = documentRef.createElement('textarea')
  textarea.value = text
  textarea.setAttribute?.('readonly', '')
  Object.assign(textarea.style, {
    position: 'fixed',
    left: '-9999px',
    opacity: '0',
    pointerEvents: 'none'
  })
  documentRef.body.appendChild(textarea)
  try {
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    if (!documentRef.execCommand('copy')) throw new Error('复制命令执行失败')
  } finally {
    documentRef.body.removeChild(textarea)
  }
}

export async function prepareImageClipboardData(blob, options = {}) {
  const pngBlob = await convertImageToPng(blob, options)
  return {
    blob: pngBlob,
    dataUrl: await blobToDataUrl(pngBlob, options)
  }
}

// 保留兼容入口；正式运行列表使用 prepareImageClipboardData 预加载后再复制。
export async function copyImageBlob(blob, options = {}) {
  const pngBlob = await convertImageToPng(blob, options)
  return copyPreparedImage({ blob: pngBlob, dataUrl: '' }, options)
}

export async function copyPreparedImage(prepared, options = {}) {
  const navigatorRef = options.navigatorRef ?? globalThis.navigator
  const ClipboardItemRef = options.ClipboardItemRef ?? globalThis.ClipboardItem
  let clipboardError

  if (navigatorRef?.clipboard?.write && typeof ClipboardItemRef === 'function') {
    try {
      // 这里必须传入已经准备完成的 Blob，点击期间不再等待下载或格式转换。
      await navigatorRef.clipboard.write([
        new ClipboardItemRef({ 'image/png': prepared.blob })
      ])
      return 'clipboard'
    } catch (error) {
      clipboardError = error
    }
  }

  try {
    copyImageWithSelection(prepared.dataUrl, options)
    return 'selection'
  } catch (selectionError) {
    const error = new Error(
      `异步剪贴板失败：${clipboardError?.message || '不可用'}；选区复制失败：${selectionError.message}`
    )
    error.name = clipboardError?.name || selectionError.name || 'ClipboardError'
    throw error
  }
}

function copyImageWithSelection(dataUrl, options) {
  const documentRef = options.documentRef ?? globalThis.document
  if (!documentRef?.body || typeof documentRef.execCommand !== 'function'
    || typeof documentRef.createRange !== 'function') {
    throw new Error('当前浏览器不支持图片选区复制')
  }

  const container = documentRef.createElement('div')
  const image = documentRef.createElement('img')
  container.contentEditable = 'true'
  container.setAttribute?.('aria-hidden', 'true')
  Object.assign(container.style, {
    position: 'fixed',
    left: '-10000px',
    top: '0',
    opacity: '0',
    pointerEvents: 'none'
  })
  image.src = dataUrl
  container.appendChild(image)
  documentRef.body.appendChild(container)

  const selection = documentRef.getSelection?.()
  const range = documentRef.createRange()
  try {
    range.selectNode(image)
    selection?.removeAllRanges()
    selection?.addRange(range)
    if (!documentRef.execCommand('copy')) throw new Error('复制命令执行失败')
  } finally {
    selection?.removeAllRanges()
    documentRef.body.removeChild(container)
  }
}

async function convertImageToPng(blob, options) {
  if (blob?.type === 'image/png') return blob
  if (!blob) throw new Error('图片内容为空')

  const createImageBitmapRef = options.createImageBitmapRef ?? globalThis.createImageBitmap
  const documentRef = options.documentRef ?? globalThis.document
  if (typeof createImageBitmapRef !== 'function' || !documentRef?.createElement) {
    throw new Error('当前浏览器无法转换图片格式')
  }

  const bitmap = await createImageBitmapRef(blob)
  try {
    const canvas = documentRef.createElement('canvas')
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法创建图片画布')
    context.drawImage(bitmap, 0, 0)
    return await new Promise((resolve, reject) => {
      canvas.toBlob(
        (pngBlob) => pngBlob ? resolve(pngBlob) : reject(new Error('图片格式转换失败')),
        'image/png'
      )
    })
  } finally {
    bitmap.close?.()
  }
}

function blobToDataUrl(blob, options) {
  const FileReaderRef = options.FileReaderRef ?? globalThis.FileReader
  if (typeof FileReaderRef !== 'function') throw new Error('当前浏览器无法准备图片复制数据')
  return new Promise((resolve, reject) => {
    const reader = new FileReaderRef()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error || new Error('图片读取失败'))
    reader.readAsDataURL(blob)
  })
}
