import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')

test('详情页只通过工作台票据进入', () => {
  assert.match(app, /if \(tool\.key === 'detail-page'\)/)
  assert.match(app, /resolveDetailPageEntry\(runProjectId\)/)
  assert.match(app, /runProjectId = entry\.projectId/)
  assert.match(app, /tool\.key !== 'promo-image' && tool\.key !== 'detail-page'/)
  assert.match(app, /entry\.mode === 'workbench'/)
  assert.match(app, /internalEntry\.pathname !== '\/tools\/detail-page-sso\/platform-entry'/)
  assert.match(app, /new URL\('\/platform-entry', tool\.entryUrl\)/)
  assert.doesNotMatch(app, /\/legacy-entry/)
  assert.match(app, /return `\/tools\/\$\{encodeURIComponent\(tool\.key\)\}\/?\?\$\{params\.toString\(\)\}`/)
})
