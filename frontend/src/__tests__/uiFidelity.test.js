import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')
const appDialog = readFileSync(new URL('../components/AppDialog.vue', import.meta.url), 'utf8')
const detailDrawer = readFileSync(new URL('../components/DetailDrawer.vue', import.meta.url), 'utf8')

function stripCssComments(source) {
  let result = ''
  let quote = null
  let escaped = false
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      result += char
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      result += char
      continue
    }
    if (char === '/' && source[index + 1] === '*') {
      const end = source.indexOf('*/', index + 2)
      result += ' '
      index = end < 0 ? source.length : end + 1
      continue
    }
    result += char
  }
  return result
}

function splitTopLevel(source, delimiter) {
  const parts = []
  let start = 0
  let quote = null
  let escaped = false
  let parentheses = 0
  let brackets = 0
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      continue
    }
    if (char === '(') parentheses += 1
    else if (char === ')') parentheses = Math.max(0, parentheses - 1)
    else if (char === '[') brackets += 1
    else if (char === ']') brackets = Math.max(0, brackets - 1)
    else if (char === delimiter && parentheses === 0 && brackets === 0) {
      parts.push(source.slice(start, index))
      start = index + 1
    }
  }
  parts.push(source.slice(start))
  return parts
}

function findTopLevelDelimiter(source, start, delimiters) {
  let quote = null
  let escaped = false
  let parentheses = 0
  let brackets = 0
  for (let index = start; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      continue
    }
    if (char === '(') parentheses += 1
    else if (char === ')') parentheses = Math.max(0, parentheses - 1)
    else if (char === '[') brackets += 1
    else if (char === ']') brackets = Math.max(0, brackets - 1)
    else if (parentheses === 0 && brackets === 0 && delimiters.has(char)) return index
  }
  return -1
}

function findMatchingBrace(source, openingIndex) {
  let depth = 1
  let quote = null
  let escaped = false
  for (let index = openingIndex + 1; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") quote = char
    else if (char === '{') depth += 1
    else if (char === '}') {
      depth -= 1
      if (depth === 0) return index
    }
  }
  return -1
}

function normalizeMediaCondition(condition) {
  return condition.trim().toLowerCase().replace(/\s+/g, ' ').replace(/\s*([():,])\s*/g, '$1')
}

function collectCssRules(source, mediaCondition = null, rules = []) {
  let cursor = 0
  const structuralDelimiters = new Set(['{', ';'])
  while (cursor < source.length) {
    const delimiterIndex = findTopLevelDelimiter(source, cursor, structuralDelimiters)
    if (delimiterIndex < 0) break
    if (source[delimiterIndex] === ';') {
      cursor = delimiterIndex + 1
      continue
    }

    const prelude = source.slice(cursor, delimiterIndex).trim()
    const closingIndex = findMatchingBrace(source, delimiterIndex)
    if (closingIndex < 0) break
    const body = source.slice(delimiterIndex + 1, closingIndex)
    if (/^@media\b/i.test(prelude)) {
      collectCssRules(body, normalizeMediaCondition(prelude.replace(/^@media\b/i, '')), rules)
    } else if (prelude && !prelude.startsWith('@')) {
      rules.push({
        selectors: splitTopLevel(prelude, ',').map((selector) => selector.trim()).filter(Boolean),
        body,
        mediaCondition,
      })
    }
    cursor = closingIndex + 1
  }
  return rules
}

function parseDeclarationEntries(ruleBody) {
  const declarations = []
  for (const declaration of splitTopLevel(ruleBody, ';')) {
    const separator = findTopLevelDelimiter(declaration, 0, new Set([':']))
    if (separator < 0) continue
    const property = declaration.slice(0, separator).trim()
    const value = declaration.slice(separator + 1).trim()
    if (property) declarations.push([property, value])
  }
  return declarations
}

function effectiveDeclarationEntries(source, selector, mediaCondition = null) {
  const expectedMedia = mediaCondition === null ? null : normalizeMediaCondition(mediaCondition)
  return collectCssRules(stripCssComments(source))
    .filter((rule) => rule.mediaCondition === expectedMedia && rule.selectors.includes(selector))
    .flatMap((rule) => parseDeclarationEntries(rule.body))
}

function effectiveDeclarations(source, selector, mediaCondition = null) {
  return new Map(effectiveDeclarationEntries(source, selector, mediaCondition))
}

function extractTemplate(source) {
  const tagPattern = /<\/?template\b[^>]*>/gi
  const openingTag = tagPattern.exec(source)
  if (!openingTag || openingTag[0].startsWith('</')) return ''

  const contentStart = tagPattern.lastIndex
  let depth = 1
  let tag
  while ((tag = tagPattern.exec(source))) {
    depth += tag[0].startsWith('</') ? -1 : 1
    if (depth === 0) {
      return source.slice(contentStart, tag.index).replace(/<!--[\s\S]*?-->/g, '')
    }
  }
  return ''
}

function extractElementByClass(template, className) {
  const openingTags = /<([a-z][\w-]*)\b[^>]*>/gi
  let openingTag
  while ((openingTag = openingTags.exec(template))) {
    const classValue = openingTag[0].match(/\bclass\s*=\s*"([^"]*)"/i)?.[1] ?? ''
    if (classValue.split(/\s+/).includes(className)) break
  }
  if (!openingTag) return ''

  const tagName = openingTag[1]
  const tagPattern = new RegExp(`<\\/?${tagName}\\b[^>]*>`, 'gi')
  tagPattern.lastIndex = openingTag.index
  let depth = 0
  let tag
  while ((tag = tagPattern.exec(template))) {
    depth += tag[0].startsWith('</') ? -1 : 1
    if (depth === 0) return template.slice(openingTag.index, tagPattern.lastIndex)
  }
  return ''
}

test('CSS helper 使用后置顶层规则覆盖前置规则', () => {
  const fixture = '.card { color: red; } .card { color: blue; }'
  assert.equal(effectiveDeclarations(fixture, '.card').get('color'), 'blue')
})

test('CSS helper 汇总相同媒体条件的多个规则块', () => {
  const fixture = '@media (max-width: 900px) { .card { padding: 10px; } } @media (max-width: 900px) { .card { padding: 20px; } }'
  assert.equal(effectiveDeclarations(fixture, '.card', '(max-width: 900px)').get('padding'), '20px')
})

test('窄屏管理弹窗 footer 换行且内置角色提示独占一行', () => {
  const footer = effectiveDeclarations(css, '.app-dialog-footer', '(max-width: 768px)')
  const hint = effectiveDeclarations(css, '.app-dialog-footer .role-delete-hint', '(max-width: 768px)')
  const button = effectiveDeclarations(css, '.app-dialog-footer button', '(max-width: 768px)')

  assert.equal(footer.get('flex-wrap'), 'wrap')
  assert.equal(hint.get('flex-basis'), '100%')
  assert.equal(hint.get('white-space'), 'normal')
  assert.equal(button.get('flex'), '1 1 auto')
})

test('CSS helper 不拆分字符串和 data URL 内的分号', () => {
  const fixture = '.icon { background-image: url("data:image/svg+xml;utf8,<svg></svg>"); content: "a;b"; color: red; }'
  const declarations = effectiveDeclarations(fixture, '.icon')
  assert.equal(declarations.get('background-image'), 'url("data:image/svg+xml;utf8,<svg></svg>")')
  assert.equal(declarations.get('content'), '"a;b"')
  assert.equal(declarations.get('color'), 'red')
})

test('CSS helper 的基础查询不合并媒体块内声明', () => {
  const fixture = '@media (max-width: 900px) { .other { color: white; } .card { color: red; } } .card { color: black; }'
  assert.equal(effectiveDeclarations(fixture, '.card').get('color'), 'black')
})

test('平台使用 UI 稿的核心视觉令牌', () => {
  const root = effectiveDeclarations(css, ':root')
  assert.equal(root.get('--ui-brand')?.toLowerCase(), '#5b55ea')
  assert.equal(root.get('--ui-canvas')?.toLowerCase(), '#f4f6f9')
  assert.equal(root.get('--ui-ink')?.toLowerCase(), '#171a2b')
  assert.equal(root.get('--ui-muted')?.toLowerCase(), '#667085')
  assert.equal(root.get('--ui-border')?.toLowerCase(), '#e7e9f2')
  assert.equal(root.get('--ui-success')?.toLowerCase(), '#16a36a')
  assert.equal(root.get('--ui-danger')?.toLowerCase(), '#dc2626')
  assert.equal(root.get('--ui-card')?.toLowerCase(), '#fff')
  assert.equal(root.get('--ui-sidebar-width')?.toLowerCase(), '240px')
})

test('主侧边栏应用固定宽度与品牌色选中态', () => {
  const sidebar = effectiveDeclarations(css, '.sidebar-primary')
  assert.equal(sidebar.get('width'), 'var(--ui-sidebar-width)')

  const active = effectiveDeclarations(css, '.nav-links li.active')
  assert.equal(active.get('background'), 'var(--ui-brand)')
  assert.equal(active.get('color')?.toLowerCase(), '#fff')
})

test('登录页保留双栏品牌、统计与深色表单结构', () => {
  const template = extractTemplate(app)
  const loginPage = extractElementByClass(template, 'login-page')
  const loginLeft = extractElementByClass(loginPage, 'login-left')
  const loginRight = extractElementByClass(loginPage, 'login-right')
  assert.ok(extractElementByClass(loginLeft, 'brand-logo'))
  assert.ok(extractElementByClass(loginLeft, 'brand-stats'))
  assert.ok(extractElementByClass(loginRight, 'login-box'))
  assert.ok(extractElementByClass(loginRight, 'form-divider'))
})

test('登录页使用黑色网格、三色光晕与深色表单', () => {
  const loginPage = effectiveDeclarations(css, '.login-page')
  assert.equal(loginPage.get('background')?.toLowerCase(), '#000')

  const grid = effectiveDeclarations(css, '.login-page:before')
  assert.match(grid.get('background-image') ?? '', /radial-gradient\s*\(/i)
  assert.equal(grid.get('background-size'), '32px 32px')

  const glow = effectiveDeclarations(css, '.login-page:after')
  const glowCount = (glow.get('background')?.match(/radial-gradient\s*\(/gi) ?? []).length
  assert.ok(glowCount >= 3, `期望至少 3 层径向光晕，实际为 ${glowCount}`)

  const loginBox = effectiveDeclarations(css, '.login-box')
  assert.equal(loginBox.get('background'), 'rgba(9,9,11,.86)')
  assert.equal(loginBox.get('border'), '1px solid rgba(63,63,70,.8)')
  assert.match(loginBox.get('backdrop-filter') ?? '', /^blur\(22px\)$/i)
  assert.match(loginBox.get('box-shadow') ?? '', /0\s+24px\s+90px\s+rgba\(0,0,0,.5\)/i)
})

test('登录页在 900px 和 760px 断点调整布局与间距', () => {
  const tabletCondition = '(max-width: 900px)'
  const tabletPage = effectiveDeclarations(css, '.login-page', tabletCondition)
  assert.equal(tabletPage.get('grid-template-columns'), '1fr')
  assert.equal(tabletPage.get('overflow-y'), 'auto')
  const tabletLeft = effectiveDeclarations(css, '.login-left', tabletCondition)
  const tabletRight = effectiveDeclarations(css, '.login-right', tabletCondition)
  const tabletBox = effectiveDeclarations(css, '.login-box', tabletCondition)
  assert.equal(tabletLeft.get('padding'), '52px 28px 24px')
  assert.equal(tabletRight.get('padding'), '24px 20px 48px')
  assert.equal(tabletBox.get('padding'), '34px 26px')

  const mobileCondition = '(max-width: 760px)'
  const mobileOptions = effectiveDeclarations(css, '.login-options', mobileCondition)
  assert.equal(mobileOptions.get('flex-direction'), 'column')
  const mobileTitle = effectiveDeclarations(css, '.login-left h1', mobileCondition)
  assert.equal(mobileTitle.get('font-size'), '42px')
})

test('工作台保留 UI 稿的标志性欢迎卡结构', () => {
  const template = extractTemplate(app)
  const heroCard = extractElementByClass(template, 'hero-card-container')
  assert.ok(extractElementByClass(heroCard, 'bg-tabs'))
  assert.ok(extractElementByClass(heroCard, 'hero-main-card'))
  assert.ok(extractElementByClass(template, 'tools-grid-new'))
})

test('工作台主卡和工具区对齐 UI 稿布局契约', () => {
  const heroContainer = effectiveDeclarations(css, '.hero-card-container')
  assert.equal(heroContainer.get('width'), 'min(100%, 1280px)')

  const heroCard = effectiveDeclarations(css, '.hero-main-card')
  assert.equal(heroCard.get('background'), 'var(--ui-card)')
  assert.equal(heroCard.get('border-radius'), '32px')

  const toolGrid = effectiveDeclarations(css, '.tools-grid-new')
  assert.match(toolGrid.get('grid-template-columns') ?? '', /^repeat\(4,\s*minmax\(/)

  const disabledTool = effectiveDeclarations(css, '.tool-item.disabled')
  assert.equal(disabledTool.get('cursor'), 'not-allowed')
})

test('新增编辑使用居中弹窗且详情使用右侧抽屉', () => {
  const template = extractTemplate(app)
  assert.match(template, /<AppDialog(?=[\s/>])/)
  assert.match(template, /<DetailDrawer(?=[\s/>])/)

  const dialog = effectiveDeclarations(css, '.app-dialog-shell')
  assert.equal(dialog.get('left'), '50%')
  assert.equal(dialog.get('top'), '50%')
  const translate = dialog.get('translate') ?? ''
  const transform = dialog.get('transform') ?? ''
  const centeredByTranslate = /^-50%\s+-50%$/i.test(translate)
  const centeredByTransform = /\btranslate\(\s*-50%\s*,\s*-50%\s*\)(?=\s|$)/i.test(transform)
  assert.ok(centeredByTranslate || centeredByTransform)
  const dialogMaxHeights = effectiveDeclarationEntries(css, '.app-dialog-shell')
    .filter(([property]) => property === 'max-height')
    .map(([, value]) => value)
  assert.deepEqual(dialogMaxHeights, ['88vh', '88dvh'])

  const drawer = effectiveDeclarations(css, '.detail-drawer-shell')
  assert.match(drawer.get('right') ?? '', /^0(?:px)?$/i)
  const drawerHeights = effectiveDeclarationEntries(css, '.detail-drawer-shell')
    .filter(([property]) => property === 'height')
    .map(([, value]) => value)
  assert.deepEqual(drawerHeights, ['100vh', '100dvh'])
})

test('抽屉支持显式叠层且高于普通弹层', () => {
  assert.match(detailDrawer, /:class="\{ stacked \}"/)
  assert.match(detailDrawer, /stacked:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)

  const baseLayer = Number(effectiveDeclarations(css, '.app-overlay').get('z-index'))
  const stackedLayer = Number(effectiveDeclarations(css, '.detail-drawer-overlay.stacked').get('z-index'))
  assert.ok(stackedLayer > baseLayer)
})

test('项目和运行页面使用正式 UI 稿的关键布局尺度', () => {
  const projectLayout = effectiveDeclarations(css, '.project-page-layout')
  assert.equal(projectLayout.get('grid-template-columns'), '300px minmax(0, 1fr)')

  const projectBoardTable = effectiveDeclarations(css, '.project-board-table')
  assert.equal(projectBoardTable.get('border-collapse'), 'separate')
  assert.equal(projectBoardTable.get('border-spacing'), '0 12px')

  const runTable = effectiveDeclarations(css, '.run-records-table')
  assert.equal(runTable.get('border-spacing'), '0 12px')

  const detailSection = effectiveDeclarations(css, '.detail-section')
  assert.equal(detailSection.get('padding'), '24px')
})

test('项目看板筛选控件在响应式布局中覆盖通用固定宽度', () => {
  const selector = '.project-board-filters .project-board-filter .ui-select'
  const tabletSelect = effectiveDeclarations(css, selector, '(max-width: 1100px)')
  assert.equal(tabletSelect.get('flex'), '1 1 auto')

  const mobileSelect = effectiveDeclarations(css, selector, '(max-width: 768px)')
  assert.equal(mobileSelect.get('flex-basis'), 'auto')
  assert.equal(mobileSelect.get('width'), '100%')
})

test('后台列表与反馈组件共享正式 UI 令牌', () => {
  const panel = effectiveDeclarations(css, '.admin-panel')
  assert.equal(panel.get('background'), 'var(--ui-card)')
  assert.equal(panel.get('border'), '1px solid var(--ui-border)')

  const heading = effectiveDeclarations(css, '.admin-panel > table th')
  assert.equal(heading.get('background'), '#f8f9fc')
  assert.equal(heading.get('color'), 'var(--ui-muted)')

  const cell = effectiveDeclarations(css, '.admin-panel > table td')
  assert.equal(cell.get('border-bottom'), '1px solid var(--ui-border)')

  const status = effectiveDeclarations(css, '.status-badge')
  assert.equal(status.get('border'), '1px solid var(--ui-border)')

  const result = effectiveDeclarations(css, '.check-result')
  assert.equal(result.get('border'), '1px solid rgba(220,38,38,.18)')

  const toast = effectiveDeclarations(css, '.app-toast')
  assert.equal(toast.get('border'), '1px solid var(--ui-border)')

  const empty = effectiveDeclarations(css, '.table-empty')
  assert.equal(empty.get('color'), 'var(--ui-muted)')

  const paginationStatus = effectiveDeclarations(css, '.admin-pagination-status')
  assert.equal(paginationStatus.get('color'), 'var(--ui-muted)')
})

test('后台表格在窄屏保持桌面信息密度并允许横向滚动', () => {
  const panel = effectiveDeclarations(css, '.admin-panel', '(max-width: 768px)')
  assert.equal(panel.get('overflow-x'), 'auto')

  const table = effectiveDeclarations(css, '.admin-panel > table', '(max-width: 768px)')
  assert.equal(table.get('min-width'), '760px')
})

test('窄屏仅表格面板头部保持横向宽度，组织和角色操作头部可收缩换行', () => {
  const tableHeader = effectiveDeclarations(
    css,
    '.admin-panel:not(.organization-tree-panel):not(.role-list-panel) > .panel-header',
    '(max-width: 768px)',
  )
  assert.equal(tableHeader.get('min-width'), '760px')

  for (const selector of [
    '.organization-tree-panel > .panel-header',
    '.role-list-panel > .panel-header',
  ]) {
    const header = effectiveDeclarations(css, selector, '(max-width: 768px)')
    assert.equal(header.get('min-width'), '0')
    assert.equal(header.get('flex-wrap'), 'wrap')
  }
})

test('角色列表保持表格信息密度并在窄屏独立横向滚动', () => {
  const panel = effectiveDeclarations(css, '.role-list-panel')
  assert.equal(panel.get('overflow'), 'hidden')

  const tableWrap = effectiveDeclarations(css, '.role-list-table-wrap')
  assert.equal(tableWrap.get('overflow-x'), 'auto')

  const table = effectiveDeclarations(css, '.role-list-table')
  assert.equal(table.get('min-width'), '900px')
})

test('组织树只使用组件原生展开图标且叶子节点不显示额外圆点', () => {
  const template = extractTemplate(app)
  assert.doesNotMatch(template, /class="organization-node-icon"/)
  assert.doesNotMatch(template, /node\.children\?\.length \? '▾' : '·'/)

  const node = effectiveDeclarations(css, '.organization-tree-node')
  assert.equal(node.get('grid-template-columns'), 'minmax(0,1fr) auto')
  assert.equal(effectiveDeclarations(css, '.organization-node-icon').size, 0)
})

test('两个浮层面板声明键盘焦点边界与对话框语义', () => {
  for (const source of [appDialog, detailDrawer]) {
    assert.match(source, /ref="panel"/)
    assert.match(source, /tabindex="-1"/)
    assert.match(source, /@keydown="handleKeydown"/)
    assert.match(source, /role="dialog"/)
    assert.match(source, /aria-modal="true"/)
    assert.match(source, /:aria-labelledby="titleId"/)
    assert.match(source, /event\.key === ['"]Escape['"]/)
    assert.match(source, /event\.key === ['"]Tab['"]/)
  }
})

test('两个浮层在打开时聚焦并在关闭时归还触发元素', () => {
  for (const source of [appDialog, detailDrawer]) {
    assert.match(source, /document\.activeElement/)
    assert.match(source, /returnFocusTo/)
    assert.match(source, /nextTick/)
    assert.match(source, /focusFirstElement/)
    assert.match(source, /restoreOverlayFocus/)
    assert.match(source, /onUnmounted/)
    assert.match(source, /else if \(!open && wasOpen\) \{[\s\S]*?const target = returnFocusTo[\s\S]*?await nextTick\(\)[\s\S]*?if \(cycle === focusCycle && !props\.open && returnFocusTo === target\) restoreFocus\(\)/)
    assert.match(source, /const target = returnFocusTo[\s\S]*?returnFocusTo = null[\s\S]*?restoreOverlayFocus\(target\)/)
  }
})

test('AppDialog 禁用关闭时 Escape 不触发关闭', () => {
  assert.match(appDialog, /Escape[\s\S]*?closeDisabled[\s\S]*?emit\(['"]close['"]\)/)
})

test('全局键盘焦点环覆盖原生控件和 tabindex 元素', () => {
  for (const selector of ['button:focus-visible', 'a:focus-visible', 'input:focus-visible', 'textarea:focus-visible', 'select:focus-visible', '[tabindex]:focus-visible']) {
    const declarations = effectiveDeclarations(css, selector)
    assert.match(declarations.get('outline') ?? '', /^3px solid rgba\(91,85,234,/i)
    assert.equal(declarations.get('outline-offset'), '2px')
  }
})

test('768px 下弹窗留 12px 安全边距且抽屉全宽', () => {
  const dialog = effectiveDeclarations(css, '.app-dialog-shell', '(max-width: 768px)')
  assert.equal(dialog.get('width'), 'calc(100vw - 24px)')
  const maxHeights = effectiveDeclarationEntries(css, '.app-dialog-shell', '(max-width: 768px)')
    .filter(([property]) => property === 'max-height')
    .map(([, value]) => value)
  assert.deepEqual(maxHeights, ['calc(100vh - 24px)', 'calc(100dvh - 24px)'])

  for (const selector of ['.detail-drawer-shell', '.detail-drawer-shell.wide']) {
    assert.equal(effectiveDeclarations(css, selector, '(max-width: 768px)').get('width'), '100%')
  }
})

test('减少动态效果偏好覆盖所有非必要动效与滚动', () => {
  for (const selector of ['*', '*::before', '*::after']) {
    const declarations = effectiveDeclarations(css, selector, '(prefers-reduced-motion: reduce)')
    assert.equal(declarations.get('animation-duration'), '0.01ms !important')
    assert.equal(declarations.get('animation-iteration-count'), '1 !important')
    assert.equal(declarations.get('transition-duration'), '0.01ms !important')
    assert.equal(declarations.get('scroll-behavior'), 'auto !important')
  }
})

test('危险主按钮禁用态保留原生 disabled 鼠标语义', () => {
  const declarations = effectiveDeclarations(css, '.btn-primary.danger:disabled')
  assert.equal(declarations.get('cursor'), 'not-allowed')
  assert.equal(declarations.get('opacity'), '.58')
  assert.equal(declarations.has('pointer-events'), false)
})

test('App 中业务按钮显式声明 button 或 submit 类型', () => {
  const buttonTags = extractTemplate(app).match(/<button\b[^>]*>/g) ?? []
  const missingTypes = buttonTags.filter((tag) => !/\btype=(?:"(?:button|submit)"|'(?:button|submit)')/.test(tag))
  assert.deepEqual(missingTypes, [])
})

test('App 中仅图标按钮同时提供可访问名称和悬浮提示', () => {
  const template = extractTemplate(app)
  const addProject = template.match(/<button\b[^>]*class="btn-add-project"[^>]*>/)?.[0] ?? ''
  const closeToast = template.match(/<button\b[^>]*class="app-toast-close"[^>]*>/)?.[0] ?? ''
  for (const tag of [addProject, closeToast]) {
    assert.match(tag, /\baria-label="[^"]+"/)
    assert.match(tag, /\btitle="[^"]+"/)
  }
})

test('所有非原生点击入口提供等价 Enter 和 Space 键盘操作', () => {
  const interactiveTags = (extractTemplate(app).match(/<(?:li|article|tr|div)\b[^>]*@click(?:="|\.)[^>]*>/g) ?? [])
    .filter((tag) => !/@click\.stop/.test(tag))
  assert.ok(interactiveTags.length >= 8)

  for (const tag of interactiveTags) {
    const clickAction = tag.match(/@click="([^"]+)"/)?.[1]
    assert.ok(clickAction, tag)
    assert.match(tag, /\brole="button"/)
    assert.match(tag, /(?:\btabindex="0"|:tabindex="(?:departmentHasAvailableTools\(dept\) \? 0 : -1|tool\.status === 'normal' \? 0 : -1)")/)
    assert.equal(tag.match(/@keydown\.enter="([^"]+)"/)?.[1], clickAction)
    assert.equal(tag.match(/@keydown\.space\.prevent="([^"]+)"/)?.[1], clickAction)
  }
})

test('禁用工具向辅助技术暴露禁用状态但保留同一入口守卫', () => {
  const template = extractTemplate(app)
  const tool = template.match(/<article\b[^>]*class="tool-item"[^>]*>/)?.[0] ?? ''
  assert.match(tool, /:aria-disabled="tool\.status !== 'normal'"/)
  assert.match(tool, /@click="openTool\(tool\)"/)
  assert.match(tool, /@keydown\.enter="openTool\(tool\)"/)
  assert.match(tool, /@keydown\.space\.prevent="openTool\(tool\)"/)
})

test('浮层 watch 立即处理初始 open 并用 cycle 取消过期 continuation', () => {
  for (const source of [appDialog, detailDrawer]) {
    assert.match(source, /let focusCycle = 0/)
    assert.match(source, /const cycle = \+\+focusCycle/)
    assert.match(source, /cycle === focusCycle && props\.open/)
    assert.match(source, /cycle === focusCycle && !props\.open/)
    assert.match(source, /\}, \{ immediate: true \}\)/)
    assert.match(source, /onUnmounted\(\(\) => \{[\s\S]*?focusCycle \+= 1[\s\S]*?restoreFocus\(\)/)
  }
})

test('全局紫色焦点环具有足够的不透明度', () => {
  const outline = effectiveDeclarations(css, 'button:focus-visible').get('outline') ?? ''
  const alpha = Number(outline.match(/rgba\(91,85,234,([\d.]+)\)/)?.[1])
  assert.ok(alpha >= 0.72, `焦点环 alpha 需要至少 0.72，实际为 ${alpha}`)
})

test('项目看板保留表格行语义并以项目名称按钮进入详情', () => {
  const template = extractTemplate(app)
  const row = template.match(/<tr\b[^>]*v-for="project in pagedProjectBoard"[^>]*>/)?.[0] ?? ''
  assert.doesNotMatch(row, /\brole=/)
  assert.doesNotMatch(row, /\btabindex=/)
  assert.doesNotMatch(row, /@click|@keydown/)

  const link = template.match(/<button\b[^>]*class="project-board-project-link"[^>]*>/)?.[0] ?? ''
  assert.match(link, /\btype="button"/)
  assert.match(link, /@click="selectProjectFromBoard\(project\)"/)
  assert.match(link, /:aria-label="`查看项目：\$\{project\.name\}`"/)

  const linkStyle = effectiveDeclarations(css, '.project-board-project-link')
  assert.equal(linkStyle.get('background'), 'transparent')
  assert.equal(linkStyle.get('text-align'), 'left')
  assert.equal(linkStyle.get('color'), 'inherit')
})
