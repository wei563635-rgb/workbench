import test from 'node:test'
import assert from 'node:assert/strict'

import { bindBrandPageAnchorNavigation } from '../brandPagePreview.js'

function fixture({ href = '#qa2', targetExists = true } = {}) {
  let clickHandler
  let removedHandler
  let prevented = false
  let scrollOptions
  const target = { scrollIntoView: (options) => { scrollOptions = options } }
  const document = {
    addEventListener(type, handler) {
      if (type === 'click') clickHandler = handler
    },
    removeEventListener(type, handler) {
      if (type === 'click') removedHandler = handler
    },
    getElementById(id) {
      return targetExists && id === 'qa2' ? target : null
    }
  }
  const anchor = { getAttribute: (name) => name === 'href' ? href : null }
  const event = {
    button: 0,
    defaultPrevented: false,
    target: { closest: () => anchor },
    preventDefault: () => { prevented = true }
  }

  return {
    document,
    event,
    click: (overrides = {}) => clickHandler({ ...event, ...overrides }),
    state: () => ({ prevented, scrollOptions, clickHandler, removedHandler })
  }
}

test('品牌报告预览在当前 iframe 文档内即时处理片段锚点', () => {
  const page = fixture()
  const cleanup = bindBrandPageAnchorNavigation(page.document)

  page.click()

  assert.equal(page.state().prevented, true)
  assert.deepEqual(page.state().scrollOptions, { behavior: 'instant', block: 'start' })
  cleanup()
  assert.equal(page.state().removedHandler, page.state().clickHandler)
})

test('不存在目标的片段锚点保留报告原始行为', () => {
  const page = fixture({ targetExists: false })
  bindBrandPageAnchorNavigation(page.document)

  page.click()

  assert.equal(page.state().prevented, false)
  assert.equal(page.state().scrollOptions, undefined)
})

test('已处理事件和组合键点击不被预览层重复接管', () => {
  const page = fixture()
  bindBrandPageAnchorNavigation(page.document)

  page.click({ defaultPrevented: true })
  page.click({ ctrlKey: true })

  assert.equal(page.state().prevented, false)
  assert.equal(page.state().scrollOptions, undefined)
})
