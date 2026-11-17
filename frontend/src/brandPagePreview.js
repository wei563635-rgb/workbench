function plainPrimaryClick(event) {
  return !event.defaultPrevented
    && (!Number.isInteger(event.button) || event.button === 0)
    && !event.metaKey
    && !event.ctrlKey
    && !event.shiftKey
    && !event.altKey
}

function fragmentId(href) {
  if (typeof href !== 'string' || href.length <= 1 || !href.startsWith('#')) return ''
  try {
    return decodeURIComponent(href.slice(1))
  } catch {
    return ''
  }
}

export function bindBrandPageAnchorNavigation(previewDocument) {
  if (!previewDocument?.addEventListener) return () => {}

  const handleClick = (event) => {
    if (!plainPrimaryClick(event)) return
    const anchor = event.target?.closest?.('a[href^="#"]')
    const targetId = fragmentId(anchor?.getAttribute?.('href'))
    if (!targetId) return
    const target = previewDocument.getElementById?.(targetId)
    if (!target?.scrollIntoView) return

    event.preventDefault()
    // 使用即时定位，避免报告自身的滚动监听在平滑滚动过程中重新定位旧的激活菜单，
    // 导致目标滚动被打断并回到页面顶部。
    target.scrollIntoView({ behavior: 'instant', block: 'start' })
  }

  previewDocument.addEventListener('click', handleClick)
  return () => previewDocument.removeEventListener?.('click', handleClick)
}
