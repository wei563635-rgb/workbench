import test from 'node:test'
import assert from 'node:assert/strict'
import { pickBrandPageViewerDepartment } from '../brandPageViewerDepartment.js'

test('品牌授权树把兼任父子部门的员工放在最具体的部门', () => {
  const departments = [
    { id: 'ai', parentId: null },
    { id: 'design', parentId: 'ai' },
    { id: 'business', parentId: 'ai' }
  ]
  assert.equal(pickBrandPageViewerDepartment({ departmentIds: ['ai', 'design'] }, departments), 'design')
  assert.equal(pickBrandPageViewerDepartment({ departmentIds: ['business'] }, departments), 'business')
})
