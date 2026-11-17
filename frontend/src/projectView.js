export function filterProjects(projects, tasks, tools, searchQuery, departmentKey) {
  const keyword = String(searchQuery || '').trim().toLocaleLowerCase()
  const selectedDepartment = departmentKey || 'all'
  const toolDepartments = new Map((tools || []).map((tool) => [tool.key, tool.departmentKey]))
  const projectDepartments = new Map()

  if (selectedDepartment !== 'all') {
    for (const task of tasks || []) {
      const taskDepartment = toolDepartments.get(task.toolKey)
      if (!taskDepartment) continue
      if (!projectDepartments.has(task.projectId)) projectDepartments.set(task.projectId, new Set())
      projectDepartments.get(task.projectId).add(taskDepartment)
    }
  }

  return (projects || []).filter((project) => {
    const matchesName = !keyword || String(project.name || '').toLocaleLowerCase().includes(keyword)
    const matchesDepartment = selectedDepartment === 'all'
      || projectDepartments.get(project.id)?.has(selectedDepartment)
    return matchesName && matchesDepartment
  })
}

export function countActiveProjectResults(results, projectId) {
  return activeProjectResults(results, projectId).length
}

export function activeProjectResults(results, projectId) {
  if (!projectId) return []
  return (results || []).filter((result) => {
    const run = result.resultSnapshot?.run
    return result.projectId === projectId
      && result.status === 'ACTIVE'
      && run?.status?.toLocaleLowerCase() === 'success'
      && run?.eventType?.toLocaleLowerCase() === 'run'
      && Boolean(run?.finishedAt)
  })
}
