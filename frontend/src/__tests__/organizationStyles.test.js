import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('organization editor uses Element Plus tree select for parent departments', async () => {
  const app = await readFile(new URL('../App.vue', import.meta.url), 'utf8')

  assert.match(app, /<ElTreeSelect[^>]*v-model="organizationForm\.parentId"/)
  assert.match(app, /:data="organizationParentTreeData"/)
  assert.doesNotMatch(app, /<OrganizationTreeSelect/)
  assert.doesNotMatch(app, /const OrganizationTreeSelect =/)
})

test('organization editor is hosted in the shared dialog with its complete tree-select contract', async () => {
  const app = await readFile(new URL('../App.vue', import.meta.url), 'utf8')
  const css = await readFile(new URL('../styles.css', import.meta.url), 'utf8')
  const template = app.slice(app.indexOf('<template>') + '<template>'.length, app.lastIndexOf('</template>')).replace(/<!--[\s\S]*?-->/g, '')
  const organizationDialog = template.match(/<AppDialog\b[^>]*:open="organizationDialogVisible"[^>]*>[\s\S]*?<\/AppDialog>/)?.[0] || ''

  assert.match(organizationDialog, /:close-disabled="organizationSaving"/)
  assert.match(organizationDialog, /<form\b[^>]*id="organization-editor-form"[^>]*@submit\.prevent="saveOrganizationDepartment"/)
  assert.match(organizationDialog, /<ElTreeSelect[\s\S]*?:data="organizationParentTreeData"[\s\S]*?node-key="id"[\s\S]*?check-strictly[\s\S]*?default-expand-all[\s\S]*?clearable[\s\S]*?placeholder="无上级部门（一级部门）"[\s\S]*?:render-after-expand="false"/)
  assert.match(organizationDialog, /type="submit"[^>]*form="organization-editor-form"[^>]*:disabled="organizationSaving"/)
  assert.doesNotMatch(template, /organization-editor-panel|role-editor-panel/)
  assert.doesNotMatch(css, /\.organization-editor-panel\b|\.role-editor-panel\b/)
})

test('organization management supports adding a child from every tree node', async () => {
  const app = await readFile(new URL('../App.vue', import.meta.url), 'utf8')

  assert.match(app, /<ElTree[^>]*:data="organizationTreeData"/)
  assert.match(app, /class="organization-node-add"[^>]*@click\.stop="newChildOrganizationDepartment\(node\)"/)
  assert.match(app, /function newChildOrganizationDepartment\(department\)/)
  assert.match(app, /parentId:\s*department\.id/)
})

test('shared controls are imported components instead of runtime templates', async () => {
  const app = await readFile(new URL('../App.vue', import.meta.url), 'utf8')

  assert.match(app, /import UiSelect from '.\/components\/UiSelect\.vue'/)
  assert.match(app, /import UiCheck from '.\/components\/UiCheck\.vue'/)
  assert.match(app, /import AdminPagination from '.\/components\/AdminPagination\.vue'/)
  assert.doesNotMatch(app, /const (UiSelect|UiCheck|AdminPagination) =/)
})
