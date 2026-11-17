import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')

test('task card footer keeps metadata readable above the full-width action', () => {
  assert.match(css, /\.node-footer\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s)
  assert.match(css, /\.nf-info\s*\{[^}]*justify-content:\s*space-between;/s)
  assert.match(css, /\.node-author\s*\{[^}]*white-space:\s*nowrap;/s)
})

test('运行记录行使用 A1 悬停动效并尊重减少动态效果', () => {
  assert.match(css, /\.run-record-row:hover[\s\S]*translateY\(-3px\)/)
  assert.match(css, /prefers-reduced-motion:\s*reduce/)
  assert.match(css, /\.run-records-table[\s\S]*border-spacing:\s*0 12px/)
})
