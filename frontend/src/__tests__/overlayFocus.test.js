import test from 'node:test'
import assert from 'node:assert/strict'
import * as overlayFocus from '../components/overlayFocus.js'

const { focusFirstElement, getFocusableElements, getFocusLoopTarget } = overlayFocus

function element(name, options = {}) {
  const item = {
    name,
    disabled: Boolean(options.disabled),
    hidden: Boolean(options.hidden),
    inert: Boolean(options.inert),
    isConnected: options.connected !== false,
    ownerDocument: options.ownerDocument,
    getAttribute(attribute) {
      if (attribute === 'aria-disabled') return options.ariaDisabled ? 'true' : null
      if (attribute === 'tabindex') return options.tabindex ?? null
      if (attribute === 'contenteditable') return options.contenteditable ?? null
      return null
    },
    getClientRects() {
      return options.visible === false ? [] : [{}]
    },
    focus() {
      this.focused = true
      if (this.ownerDocument && options.focusWorks !== false) this.ownerDocument.activeElement = this
    },
    closest(selector) {
      this.closestCalls = (this.closestCalls ?? 0) + 1
      return selector === '[role="dialog"]' ? options.closestDialog ?? null : null
    },
  }
  if (options.actualTabIndex !== undefined) item.tabIndex = options.actualTabIndex
  const originalClosest = item.closest
  item.closest = function closest(selector) {
    if (selector === '[inert]') return options.inert || options.ancestorInert ? options.inertAncestor ?? item : null
    if (selector === '[hidden]') return options.hidden || options.ancestorHidden ? options.hiddenAncestor ?? item : null
    return originalClosest.call(item, selector)
  }
  return item
}

function panel(elements = [], options = {}) {
  const root = element('panel', options)
  root.querySelectorAll = () => elements
  return root
}

test('焦点候选过滤 disabled、aria-disabled、tabindex -1 与不可见元素', () => {
  const enabled = element('enabled')
  const candidates = [
    enabled,
    element('disabled', { disabled: true }),
    element('aria-disabled', { ariaDisabled: true }),
    element('negative-tabindex', { tabindex: '-1' }),
    element('hidden', { hidden: true }),
    element('invisible', { visible: false }),
  ]

  assert.deepEqual(getFocusableElements(panel(candidates)), [enabled])
})

test('焦点候选过滤任意有限负 tabindex', () => {
  const enabled = element('enabled', { tabindex: '0' })
  const candidates = [enabled, element('minus-two', { tabindex: '-2' }), element('minus-ninety-nine', { tabindex: '-99' })]

  assert.deepEqual(getFocusableElements(panel(candidates)), [enabled])
})

test('焦点候选使用实际 tabIndex 并支持可编辑内容', () => {
  assert.match(overlayFocus.FOCUSABLE_SELECTOR, /\[contenteditable\]:not\(\[contenteditable="false"\]\)/)
  const editable = element('editable', { contenteditable: 'true', actualTabIndex: 0 })
  const browserNegative = element('browser-negative', { tabindex: '0', actualTabIndex: -1 })

  assert.deepEqual(getFocusableElements(panel([editable, browserNegative])), [editable])
})

test('焦点候选排除 aria-disabled、inert 与不可见元素', () => {
  const enabled = element('enabled')
  const candidates = [
    enabled,
    element('aria-disabled', { ariaDisabled: true }),
    element('self-inert', { inert: true }),
    element('ancestor-inert', { ancestorInert: true }),
    element('self-hidden', { hidden: true }),
    element('ancestor-hidden', { ancestorHidden: true, visible: false }),
    element('display-none', { visible: false }),
  ]

  assert.deepEqual(getFocusableElements(panel(candidates)), [enabled])
})

test('正向 Tab 在末尾循环到首个候选', () => {
  const first = element('first')
  const last = element('last')
  const root = panel([first, last])

  assert.equal(getFocusLoopTarget(root, last, false), first)
  assert.equal(getFocusLoopTarget(root, first, false), null)
})

test('反向 Tab 在开头循环到末尾候选', () => {
  const first = element('first')
  const last = element('last')
  const root = panel([first, last])

  assert.equal(getFocusLoopTarget(root, first, true), last)
  assert.equal(getFocusLoopTarget(root, last, true), null)
})

test('没有可交互元素时首选聚焦和循环目标都是 panel', () => {
  const root = panel([element('disabled', { disabled: true })])

  assert.equal(focusFirstElement(root), root)
  assert.equal(root.focused, true)
  assert.equal(getFocusLoopTarget(root, root, false), root)
})

function restoreOverlayFocus(target) {
  assert.equal(typeof overlayFocus.restoreOverlayFocus, 'function')
  return overlayFocus.restoreOverlayFocus(target)
}

test('已连接但 disabled 的触发元素回退到外层 dialog 首个候选', () => {
  const first = element('first')
  const outer = panel([first])
  const trigger = element('trigger', { disabled: true, closestDialog: outer })

  assert.equal(restoreOverlayFocus(trigger), first)
  assert.equal(trigger.focused, undefined)
  assert.equal(first.focused, true)
})

test('外层 dialog 没有候选时回退聚焦 panel', () => {
  const outer = panel([])
  const trigger = element('trigger', { disabled: true, closestDialog: outer })

  assert.equal(restoreOverlayFocus(trigger), outer)
  assert.equal(outer.focused, true)
})

test('触发元素已断开时不查询全局或外层 dialog', () => {
  const outer = panel([element('first')])
  const trigger = element('trigger', { connected: false, closestDialog: outer })

  assert.equal(restoreOverlayFocus(trigger), null)
  assert.equal(trigger.closestCalls, undefined)
  assert.equal(outer.focused, undefined)
})

test('aria-disabled 与负 tabindex 的触发元素均回退到外层 dialog', () => {
  for (const options of [{ ariaDisabled: true }, { tabindex: '-2' }, { tabindex: '-99' }]) {
    const first = element('first')
    const outer = panel([first])
    const trigger = element('trigger', { ...options, closestDialog: outer })

    assert.equal(restoreOverlayFocus(trigger), first)
    assert.equal(first.focused, true)
    assert.equal(trigger.focused, undefined)
  }
})

test('已连接但隐藏的触发元素回退到可见外层 dialog', () => {
  const first = element('first')
  const outer = panel([first])
  const trigger = element('trigger', { hidden: true, closestDialog: outer })

  assert.equal(restoreOverlayFocus(trigger), first)
  assert.equal(first.focused, true)
})

test('隐藏或 inert 的外层 dialog 不接收回退焦点', () => {
  for (const options of [{ hidden: true }, { inert: true }]) {
    const outer = panel([element('first')], options)
    const trigger = element('trigger', { hidden: true, closestDialog: outer })

    assert.equal(restoreOverlayFocus(trigger), null)
    assert.equal(outer.focused, undefined)
  }
})

test('直接 focus 未改变 activeElement 时回退外层 dialog', () => {
  const ownerDocument = { activeElement: null }
  const first = element('first', { ownerDocument })
  const outer = panel([first], { ownerDocument })
  const trigger = element('trigger', { ownerDocument, focusWorks: false, closestDialog: outer })

  assert.equal(restoreOverlayFocus(trigger), first)
  assert.equal(ownerDocument.activeElement, first)
})
