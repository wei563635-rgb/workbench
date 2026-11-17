# 运行记录页高还原实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将运行记录页重做为设计稿中的顶部筛选栏、背景表头和独立白色记录行，并实现 A1 悬停动效及真实操作显隐。

**架构：** 保留现有运行记录数据、筛选和分页计算，只调整 `App.vue` 的页面模板与交互函数，并在 `styles.css` 中增加运行记录专属样式。时间拆分和操作可用性放入小型纯函数模块，便于独立测试；图标使用 `lucide-vue-next`，不引入新的业务接口。

**技术栈：** Vue 3、Vite、Element Plus、Lucide Vue、Node Test Runner、CSS

---

## 文件结构

- 修改：`frontend/package.json`、`frontend/package-lock.json`，增加 Lucide Vue 图标依赖。
- 修改：`frontend/src/App.vue`，重构筛选栏和记录列表模板，增加复制、下载操作。
- 修改：`frontend/src/runView.js`，增加时间拆分和操作可用性纯函数。
- 修改：`frontend/src/styles.css`，实现高还原列表结构、A1 悬停和响应式规则。
- 修改：`frontend/src/__tests__/runView.test.js`，覆盖结构、业务边界、操作显隐和时间格式。
- 修改：`frontend/src/__tests__/taskCardStyles.test.js`，覆盖悬停动效与减少动态效果。

### 任务 1：建立运行记录展示与操作回归测试

**文件：**
- 修改：`frontend/src/runView.js`
- 修改：`frontend/src/__tests__/runView.test.js`

- [ ] **步骤 1：编写失败的纯函数和模板结构测试**

```js
test('运行时间拆分为稳定的日期和时间', () => {
  assert.deepEqual(splitRunTimestamp('2026-07-07T10:51:43Z'), {
    date: '2026-07-07',
    clock: '18:51:43'
  })
})

test('运行操作根据输出和附件真实显隐', () => {
  assert.equal(hasCopyableRunResult({ output: '已完成', outputData: {} }), true)
  assert.equal(hasCopyableRunResult({ output: '-', outputData: {} }), false)
  assert.equal(hasDownloadableRunAttachment({ outputData: { attachments: [{ downloadUrl: '/api/files/demo-download/file_1' }] } }), true)
})

test('运行列表使用独立记录行且不恢复关联状态', () => {
  assert.match(app, /run-records-table/)
  assert.match(app, /run-record-row/)
  assert.doesNotMatch(app, /关联状态/)
})
```

- [ ] **步骤 2：运行测试确认失败**

运行：`cd frontend && node --test src/__tests__/runView.test.js`

预期：FAIL，提示纯函数未导出或新模板类名不存在。

- [ ] **步骤 3：实现最少纯函数**

```js
export function splitRunTimestamp(value) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).formatToParts(new Date(value))
  const valueOf = (type) => parts.find((part) => part.type === type)?.value || '--'
  return { date: `${valueOf('year')}-${valueOf('month')}-${valueOf('day')}`, clock: `${valueOf('hour')}:${valueOf('minute')}:${valueOf('second')}` }
}
```

`hasCopyableRunResult` 排除空字符串、`-` 和空对象；`hasDownloadableRunAttachment` 复用 `extractRunAttachments` 的结果判断。

- [ ] **步骤 4：运行纯函数测试确认通过**

运行：`cd frontend && node --test src/__tests__/runView.test.js src/__tests__/runAttachments.test.js`

预期：模板结构测试仍失败，纯函数与附件测试通过。

### 任务 2：重构模板与真实操作

**文件：**
- 修改：`frontend/package.json`
- 修改：`frontend/package-lock.json`
- 修改：`frontend/src/App.vue`
- 修改：`frontend/src/__tests__/runView.test.js`

- [ ] **步骤 1：安装图标依赖**

运行：`cd frontend && npm install lucide-vue-next`

预期：`package.json` 和锁文件增加 `lucide-vue-next`。

- [ ] **步骤 2：重构运行记录模板**

筛选栏使用 `.run-filter-bar` 和带标签的 `.run-filter-item`；列表使用语义化表格：

```vue
<div class="run-records-list">
  <table class="run-records-table">
    <thead>...</thead>
    <tbody>
      <tr v-for="record in pagedRunRecords" :key="record.id" class="run-record-row">
        ...
      </tr>
    </tbody>
  </table>
</div>
```

工具、员工、部门、资源和状态分别使用稳定子结构；时间使用 `record.date` 和 `record.clock`。

- [ ] **步骤 3：实现操作显隐与行为**

```js
async function copyRunResult(record) {
  const text = record.output && record.output !== '-' ? record.output : JSON.stringify(record.outputData || {}, null, 2)
  try {
    await navigator.clipboard.writeText(text)
    showNotice('运行结果已复制', '复制成功', 'success')
  } catch {
    showNotice('浏览器未允许复制，请在详情中手动复制', '复制失败')
  }
}

function downloadRunAttachments(record) {
  extractRunAttachments(record).forEach((attachment) => window.open(attachment.url, '_blank', 'noopener'))
}
```

操作按钮使用 `Eye`、`Copy`、`Download` 图标，均设置 `title` 和 `aria-label`；复制、下载按钮仅在纯函数返回 true 时显示。

- [ ] **步骤 4：运行模板与行为测试**

运行：`cd frontend && node --test src/__tests__/runView.test.js src/__tests__/runAttachments.test.js`

预期：全部通过。

### 任务 3：实现高还原样式与 A1 动效

**文件：**
- 修改：`frontend/src/styles.css`
- 修改：`frontend/src/__tests__/taskCardStyles.test.js`

- [ ] **步骤 1：编写失败的样式测试**

```js
test('运行记录行使用 A1 悬停动效并尊重减少动态效果', () => {
  assert.match(css, /\.run-record-row:hover[\s\S]*translateY\(-3px\)/)
  assert.match(css, /prefers-reduced-motion:\s*reduce/)
  assert.match(css, /\.run-records-table[\s\S]*border-spacing:\s*0 14px/)
})
```

- [ ] **步骤 2：运行样式测试确认失败**

运行：`cd frontend && node --test src/__tests__/taskCardStyles.test.js`

预期：FAIL，提示新运行记录样式不存在。

- [ ] **步骤 3：实现列表视觉样式**

```css
.run-records-table { min-width: 1160px; border-collapse: separate; border-spacing: 0 14px; }
.run-record-row { transition: transform 180ms ease, filter 180ms ease; }
.run-record-row:hover { transform: translateY(-3px); filter: drop-shadow(0 12px 24px rgba(36, 40, 72, .12)); }
.run-record-row:hover td { border-color: #dedbff; }
@media (prefers-reduced-motion: reduce) {
  .run-record-row { transition: none; }
  .run-record-row:hover { transform: none; }
}
```

同时补齐筛选栏、背景表头、白色圆角单行、工具图标、头像、部门色块、资源双行、图标按钮和分页间距。

- [ ] **步骤 4：运行前端完整验证**

运行：`cd frontend && npm test && npm run build`

预期：全部测试通过，Vite 构建成功。

- [ ] **步骤 5：视觉验收**

启动：`cd frontend && npm run dev -- --port 4174`

验收桌面与窄屏：筛选栏、表头、行间距、悬停、长摘要、成功/失败、操作显隐、横向滚动均无重叠或跳位。

- [ ] **步骤 6：提交**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/App.vue frontend/src/runView.js frontend/src/styles.css frontend/src/__tests__/runView.test.js frontend/src/__tests__/taskCardStyles.test.js
git commit -m "feat: restore run records ui fidelity"
```

### 任务 4：部署与生产验收

**文件：**
- 构建产物：`frontend/dist/**`

- [ ] **步骤 1：打包并备份生产前端**

将 `frontend/dist` 打包上传，在 `/opt/workbench/frontend.bak.<timestamp>` 备份当前静态资源后替换。

- [ ] **步骤 2：验证服务与静态资源**

检查 `nginx -t`、重新加载 Nginx，并确认 `http://120.26.221.135:18081/workbench/runs` 返回最新资源。

- [ ] **步骤 3：生产页面验收**

确认真实运行记录筛选、分页、查看、复制、附件下载及 A1 悬停动效可用，页面无关联状态筛选和关联项目操作。
