import { computed, shallowRef } from 'vue'
import { pickBrandPageViewerDepartment } from './brandPageViewerDepartment.js'

// The tree tracks the dialog's initial eligibility, not live checkbox mutations.
// Replacing node data on each check discards Element Plus filtering and scroll state.
export function useBrandPagePermissionTree(departments, users, buildOrganizationTree) {
  const initial = shallowRef({ departmentIds: [], userIds: [] })
  const treeData = computed(() => buildTree(departments.value, users.value, initial.value, buildOrganizationTree))
  function begin(selection = {}) {
    initial.value = { departmentIds: [...(selection.viewerDepartmentIds || [])], userIds: [...(selection.viewerUserIds || [])] }
  }
  return { treeData, begin }
}

function buildTree(allDepartments, allUsers, initialSelection, buildOrganizationTree) {
  const selectedDepartmentIds = new Set(initialSelection.departmentIds || [])
  const selectedUserIds = new Set(initialSelection.userIds || [])
  const departments = allDepartments
    .filter((department) => department.enabled || selectedDepartmentIds.has(department.id))
    .map((department) => ({
      ...department,
      label: `${department.name}${department.enabled ? '' : '（已禁用）'}`,
      disabled: !department.enabled
    }))
  const usersByDepartment = new Map()
  allUsers
    .filter((user) => user.enabled || selectedUserIds.has(user.id))
    .forEach((user) => {
      const departmentId = pickBrandPageViewerDepartment(user, departments)
      const users = usersByDepartment.get(departmentId) || []
      users.push({
        id: `user:${user.id}`,
        subjectId: user.id,
        subjectType: 'user',
        label: `${user.name || user.account}（${user.account}）${user.enabled ? '' : '（已禁用）'}`,
        disabled: !user.enabled,
        children: []
      })
      usersByDepartment.set(departmentId, users)
    })
  const decorate = (nodes) => nodes.map((node) => ({
    ...node,
    id: `department:${node.id}`,
    subjectId: node.id,
    subjectType: 'department',
    children: [
      ...decorate(node.children || []),
      ...(usersByDepartment.get(node.id) || []).sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
    ]
  }))
  const roots = decorate(buildOrganizationTree(departments))
  const unassignedUsers = usersByDepartment.get('') || []
  if (unassignedUsers.length) {
    roots.push({ id: 'group:unassigned', label: '未设置主部门', disabled: true, children: unassignedUsers })
  }
  return roots
}
