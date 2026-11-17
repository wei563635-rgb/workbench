export const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button',
  'input',
  'select',
  'textarea',
  '[tabindex]',
  '[contenteditable]:not([contenteditable="false"])',
].join(',')

function hasNegativeTabIndex(element) {
  if (Number.isFinite(element?.tabIndex) && element.tabIndex < 0) return true
  const rawTabIndex = element?.getAttribute?.('tabindex')
  if (rawTabIndex === null || rawTabIndex === undefined) return false
  const tabIndex = Number(rawTabIndex)
  return Number.isFinite(tabIndex) && tabIndex < 0
}

export function isElementVisible(element) {
  if (!element || element.hidden || element.inert) return false
  if (element.closest?.('[inert]') || element.closest?.('[hidden]')) return false
  if (typeof element.getClientRects === 'function' && element.getClientRects().length === 0) return false

  const view = element.ownerDocument?.defaultView
  const style = view?.getComputedStyle?.(element)
  return style?.display !== 'none' && style?.visibility !== 'hidden'
}

export function isFocusableElement(element) {
  if (!isElementVisible(element) || element.disabled) return false
  if (element.getAttribute?.('aria-disabled') === 'true') return false
  return !hasNegativeTabIndex(element)
}

export function getFocusableElements(panel) {
  if (!panel?.querySelectorAll) return []
  return Array.from(panel.querySelectorAll(FOCUSABLE_SELECTOR)).filter(isFocusableElement)
}

export function focusFirstElement(panel) {
  const target = getFocusableElements(panel)[0] ?? panel
  target?.focus?.()
  return target
}

export function getFocusLoopTarget(panel, activeElement, backwards) {
  const elements = getFocusableElements(panel)
  if (elements.length === 0) return panel

  const activeIndex = elements.indexOf(activeElement)
  if (backwards && activeIndex <= 0) return elements.at(-1)
  if (!backwards && (activeIndex === -1 || activeIndex === elements.length - 1)) return elements[0]
  return null
}

export function canReceiveFocus(element) {
  return Boolean(
    element?.isConnected
    && typeof element.focus === 'function'
    && isElementVisible(element)
    && !element.disabled
    && element.getAttribute?.('aria-disabled') !== 'true'
    && !hasNegativeTabIndex(element)
  )
}

function focusAndVerify(element) {
  try {
    element.focus()
  } catch {
    return false
  }
  return !element.ownerDocument || element.ownerDocument.activeElement === element
}

export function restoreOverlayFocus(target) {
  if (!target?.isConnected) return null
  if (canReceiveFocus(target) && focusAndVerify(target)) return target

  const outerPanel = target.closest?.('[role="dialog"]')
  if (!outerPanel?.isConnected || !isElementVisible(outerPanel)) return null

  const fallback = focusFirstElement(outerPanel)
  if (fallback && (!fallback.ownerDocument || fallback.ownerDocument.activeElement === fallback)) return fallback
  if (fallback !== outerPanel && focusAndVerify(outerPanel)) return outerPanel
  return null
}
