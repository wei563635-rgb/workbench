import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { activeProjectResults, countActiveProjectResults, filterProjects } from '../projectView.js'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const appDialog = readFileSync(new URL('../components/AppDialog.vue', import.meta.url), 'utf8')

const projects = [
  { id: 'p1', name: '测试项目' },
  { id: 'p2', name: '新品设计' },
  { id: 'p3', name: '运营复盘' }
]

const tasks = [
  { projectId: 'p1', toolKey: 'market-intelligence' },
  { projectId: 'p2', toolKey: 'promo-image' },
  { projectId: 'p3', toolKey: 'project-dashboard' }
]

const tools = [
  { key: 'market-intelligence', departmentKey: 'ai-market' },
  { key: 'promo-image', departmentKey: 'ai-design' },
  { key: 'project-dashboard', departmentKey: 'ai-operation' }
]

test('项目列表同时按名称关键字和任务所属部门筛选', () => {
  assert.deepEqual(filterProjects(projects, tasks, tools, ' 新品 ', 'ai-design').map((item) => item.id), ['p2'])
  assert.deepEqual(filterProjects(projects, tasks, tools, '项目', 'ai-design'), [])
})

test('空条件不过滤项目', () => {
  assert.deepEqual(filterProjects(projects, tasks, tools, '', 'all'), projects)
})

test('产出物只统计当前项目的有效记录', () => {
  const results = [
    { id: 'r1', projectId: 'p1', status: 'ACTIVE', resultSnapshot: { run: { status: 'success', eventType: 'run', finishedAt: '2026-07-13T00:00:00Z' } } },
    { id: 'r2', projectId: 'p1', status: 'ACTIVE', resultSnapshot: { run: { status: 'running', eventType: 'open', finishedAt: null } } },
    { id: 'r3', projectId: 'p1', status: 'REMOVED', resultSnapshot: { run: { status: 'success', eventType: 'run', finishedAt: '2026-07-13T00:00:00Z' } } },
    { id: 'r4', projectId: 'p2', status: 'ACTIVE', resultSnapshot: { run: { status: 'success', eventType: 'run', finishedAt: '2026-07-13T00:00:00Z' } } }
  ]

  assert.equal(countActiveProjectResults(results, 'p1'), 1)
  assert.deepEqual(activeProjectResults(results, 'p1').map((item) => item.id), ['r1'])
  assert.equal(countActiveProjectResults(results, 'missing'), 0)
})

test('项目删除和生成记录解除关联都保留历史记录语义', () => {
  assert.match(app, /deleteProject\(project\.id\)/)
  assert.match(app, /历史生成记录、附件和 AI 日志仍会保留/)
  assert.match(app, /unlinkProject\(result\.id\)/)
  assert.match(app, /记录、附件和运行详情仍会保留在运行记录中/)
  assert.match(app, /projectGenerationRecords\(selectedBoardProject\.id\)/)
  assert.match(app, /runProject\.deleted \? '（已删除）'/)
})

test('项目看板是独立导航和路由', () => {
  assert.match(app, /currentPage === 'project-board'/)
  assert.match(app, /\/workbench\/project-board/)
  assert.match(app, /整体项目统计/)
})

test('项目详情任务携带当前项目直接打开已接入工具', () => {
  assert.match(app, /toolKey: task\.toolKey/)
  assert.match(app, /toolStatus: tool\?\.status \|\| 'unopened'/)
  assert.match(app, /@click="openProjectTaskTool\(task, selectedBoardProject\)"/)
  assert.match(app, /!selectedBoardProject\.canRun/)
  assert.match(app, /async function openProjectTaskTool\(task, project\)/)
  assert.match(app, /await openTool\(tool, project\)/)
})

test('项目创建弹窗包含详情字段和封面上传', () => {
  assert.match(app, /productName/)
  assert.match(app, /departmentId/)
  assert.match(app, /salePrice/)
  assert.match(app, /establishedDate/)
  assert.match(app, /projectCoverFile/)
})

test('项目创建和编辑使用真实用户负责人及成员 ID', () => {
  assert.match(app, /<ElSelect v-model="projectForm\.ownerUserId"[^>]*filterable/)
  assert.match(app, /<ElSelect v-model="projectForm\.memberUserIds"[^>]*multiple[^>]*filterable/)
  assert.match(app, /ownerUserId: project\.ownerUserId \|\| ''/)
  assert.match(app, /memberUserIds: \[\.\.\.\(project\.memberUserIds \|\| \[\]\)\]/)
  assert.match(app, /const memberUserIds = Array\.from\(new Set\(\[/)
  assert.doesNotMatch(app, /members: \[currentUser\.value\.name/)
})

test('项目所属部门使用组织架构中的启用部门数据', () => {
  assert.match(app, /const projectDepartmentTreeData = computed\(\(\) => buildOrganizationTree\(enabledOrganizationDepartments\.value\)\)/)
  assert.match(app, /const projectEditorDepartmentTreeData = computed/)
  assert.match(app, /<ElTreeSelect v-model="projectForm\.departmentId"/)
  assert.match(app, /<ElTreeSelect v-model="projectDepartmentFilter"/)
  assert.match(app, /function emptyProjectForm\(\) \{\s*return \{\s*name: '', productName: '', departmentId: ''/)
})

test('项目新增编辑使用公共对话框和可关联 footer 的表单', () => {
  assert.match(app, /<AppDialog\b(?=[^>]*:open="showProjectCreate")[^>]*>/)
  assert.match(app, /<form\b(?=[^>]*id="project-editor-form")(?=[^>]*@submit\.prevent="submitProject")[^>]*>/)
  assert.match(app, /<button\b(?=[^>]*form="project-editor-form")(?=[^>]*type="submit")[^>]*>/)
})

test('项目编辑器不再使用旧 modal backdrop', () => {
  assert.doesNotMatch(app, /class="modal-backdrop"/)
})

test('公共对话框支持在提交期间禁用所有关闭入口', () => {
  assert.match(appDialog, /closeDisabled:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(appDialog, /<button\b(?=[^>]*class="app-overlay-close")(?=[^>]*:disabled="closeDisabled")[^>]*>/)
  assert.match(appDialog, /if\s*\(props\.closeOnBackdrop\s*&&\s*!props\.closeDisabled\)\s*emit\('close'\)/)
})

test('项目保存期间禁用对话框关闭和取消按钮', () => {
  assert.match(app, /<AppDialog\b(?=[^>]*:open="showProjectCreate")(?=[^>]*:close-disabled="projectCreating")[^>]*>/)
  assert.match(app, /<button\b(?=[^>]*class="btn-secondary")(?=[^>]*:disabled="projectCreating")[^>]*>取消<\/button>/)
})
