export function pickBrandPageViewerDepartment(user, departments) {
  const byId = new Map(departments.map((department) => [department.id, department]))
  let selected = ''
  let selectedDepth = -1
  for (const id of user.departmentIds || []) {
    if (!byId.has(id)) continue
    let depth = 0
    let current = id
    const visited = new Set()
    while (current && byId.has(current) && !visited.has(current)) {
      visited.add(current)
      depth += 1
      current = byId.get(current).parentId
    }
    if (depth > selectedDepth) {
      selected = id
      selectedDepth = depth
    }
  }
  return selected
}
