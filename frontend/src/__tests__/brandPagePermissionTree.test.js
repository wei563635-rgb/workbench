import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useBrandPagePermissionTree } from '../brandPagePermissionTree.js'

function buildTree(rows) {
  const nodes = new Map(rows.map(row => [row.id, { ...row, children: [] }]))
  const roots = []
  for (const node of nodes.values()) {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
const departments = () => ref([
  { id: 'purchase', name: '采购部', enabled: true },
  { id: 'group', name: '采购组', parentId: 'purchase', enabled: true },
  { id: 'disabled', name: '旧部门', enabled: false }
])

test('连续勾选和取消勾选不更换树节点，避免丢失筛选与滚动状态', () => {
  const users = ref([{ id: 'buyer', name: '采购员', account: 'buyer', enabled: true, departmentIds: ['group'] }])
  const form = ref({ viewerDepartmentIds: ['purchase'], viewerUserIds: [] })
  const session = useBrandPagePermissionTree(departments(), users, buildTree)
  session.begin(form.value)
  const initialTree = session.treeData.value
  const child = initialTree[0].children[0]
  for (const selected of [['purchase', 'group'], ['group'], []]) {
    form.value.viewerDepartmentIds = selected
    form.value.viewerUserIds = selected.length ? ['buyer'] : []
    assert.equal(session.treeData.value, initialTree)
    assert.equal(session.treeData.value[0].children[0], child)
    assert.equal(child.children[0].id, 'user:buyer')
  }
})

test('本次编辑保留原有停用授权对象，重新打开按新表单重新计算', () => {
  const form = { viewerDepartmentIds: ['disabled'], viewerUserIds: ['inactive'] }
  const users = ref([{ id: 'inactive', name: '旧员工', account: 'old', enabled: false, departmentIds: ['disabled'] }])
  const session = useBrandPagePermissionTree(departments(), users, buildTree)
  session.begin(form)
  const initial = session.treeData.value
  form.viewerDepartmentIds.splice(0)
  form.viewerUserIds.splice(0)
  assert.equal(session.treeData.value, initial)
  const disabled = initial.find(node => node.id === 'department:disabled')
  assert.equal(disabled.disabled, true)
  assert.equal(disabled.children[0].id, 'user:inactive')
  session.begin(form)
  assert.notEqual(session.treeData.value, initial)
  assert.equal(session.treeData.value.some(node => node.id === 'department:disabled'), false)
})
