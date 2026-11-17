<template>
  <div v-if="!authed" class="login-page">
    <div class="login-left">
      <div class="brand-logo">{{ entryBrandName }}</div>
      <h1><span>驱动业务进化</span><strong>构筑智能引擎</strong></h1>
      <p>打破部门壁垒，重构业务效能。平台深度整合前沿 AI 技术，打通市场洞察、视觉创作、运营决策与组织协同，为全域增长注入无限可能。</p>
      <div class="brand-stats">
        <div><small>效能提升</small><b>10<span>x</span></b></div>
        <div><small>视野拓展</small><b>360<span>°</span></b></div>
        <div><small>时刻响应</small><b>24<span>/7</span></b></div>
      </div>
    </div>
    <div class="login-right">
      <form v-if="passwordChangeRequired" class="login-box" @submit.prevent="submitInitialPasswordChange">
        <div class="login-header">
          <h2>首次登录修改密码</h2>
          <p>为保障账号安全，请设置新密码后再进入系统</p>
        </div>
        <label>临时密码<input v-model="passwordChangeForm.oldPassword" type="password" autocomplete="current-password" placeholder="输入管理员提供的临时密码" /></label>
        <label>新密码<input v-model="passwordChangeForm.newPassword" type="password" autocomplete="new-password" placeholder="至少 8 位，包含字母和数字" /></label>
        <label>确认新密码<input v-model="passwordChangeForm.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入新密码" /></label>
        <button class="btn-primary" type="submit" :disabled="passwordChanging">{{ passwordChanging ? '修改中...' : '修改密码并进入系统' }}</button>
        <div v-if="errorMessage" class="form-error">{{ errorMessage }}</div>
        <button type="button" class="login-back-button" @click="cancelPasswordChange">返回登录</button>
        <footer>新密码不能与临时密码或账号相同</footer>
      </form>
      <form v-else class="login-box" @submit.prevent="login">
        <div class="login-header">
          <h2>登录工作台</h2>
          <p>使用您的企业账号访问内部 AI 工具平台</p>
        </div>
        <label>账号<input v-model="loginForm.account" placeholder="输入企业邮箱或账号" autocomplete="username" /></label>
        <label>密码
          <span class="password-field">
            <input v-model="loginForm.password" :type="showPassword ? 'text' : 'password'" placeholder="输入登录密码" autocomplete="current-password" />
            <button type="button" class="password-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button>
          </span>
        </label>
        <div class="login-options">
          <UiCheck v-model="rememberLogin">7 天内免登录</UiCheck>
          <a>忘记密码？</a>
        </div>
        <button class="btn-primary" type="submit" :disabled="loading">{{ loading ? '登录中...' : '进入工作台  →' }}</button>
        <div v-if="errorMessage" class="form-error">{{ errorMessage }}</div>
        <div class="form-divider"><span>仅限一起互动内部员工使用</span></div>
        <footer>首次登录请使用初始密码，登录后需修改</footer>
      </form>
    </div>
  </div>

  <div v-else class="app-container" :class="{ 'brand-preview-mode': currentPage === 'brand-page-preview' }">
    <nav class="sidebar-primary">
      <div class="logo"><span class="logo-icon">✦</span><span>{{ entryBrandName }}</span></div>
      <ul class="nav-links">
        <template v-if="isWorkbenchEntry">
        <li class="section-title">AI工具</li>
        <li v-for="dept in departments" :key="dept.key" role="button" :tabindex="departmentHasAvailableTools(dept) ? 0 : -1" :aria-disabled="!departmentHasAvailableTools(dept)" :aria-current="currentPage === 'workbench' && activeDept === dept.key && departmentHasAvailableTools(dept) ? 'page' : undefined" :class="{ active: currentPage === 'workbench' && activeDept === dept.key && departmentHasAvailableTools(dept), 'department-disabled': !departmentHasAvailableTools(dept) }" @click="openDept(dept.key)" @keydown.enter="openDept(dept.key)" @keydown.space.prevent="openDept(dept.key)">
          <a><span class="nav-icon">{{ dept.icon }}</span>{{ dept.name }}<em v-if="!departmentHasAvailableTools(dept)">{{ dept.reserved ? '预留' : '未接入' }}</em></a>
        </li>
        <li v-if="canAccessNewChat" role="button" tabindex="0" @click="openNewChat" @keydown.enter="openNewChat" @keydown.space.prevent="openNewChat"><a><span class="nav-icon">◌</span>new chat</a></li>
        <li class="section-title">通用与个人</li>
        <li role="button" tabindex="0" :aria-current="currentPage === 'projects' ? 'page' : undefined" :class="{ active: currentPage === 'projects' }" @click="navigatePage('projects')" @keydown.enter="navigatePage('projects')" @keydown.space.prevent="navigatePage('projects')"><a><span class="nav-icon">▦</span>项目</a></li>
        <li role="button" tabindex="0" :aria-current="currentPage === 'project-board' ? 'page' : undefined" :class="{ active: currentPage === 'project-board' }" @click="navigatePage('project-board')" @keydown.enter="navigatePage('project-board')" @keydown.space.prevent="navigatePage('project-board')"><a><span class="nav-icon">◫</span>项目看板</a></li>
        <li role="button" tabindex="0" :aria-current="currentPage === 'runs' ? 'page' : undefined" :class="{ active: currentPage === 'runs' }" @click="navigatePage('runs')" @keydown.enter="navigatePage('runs')" @keydown.space.prevent="navigatePage('runs')"><a><span class="nav-icon">◷</span>运行记录</a></li>
        <li role="button" tabindex="0" :aria-current="currentPage === 'my-data' ? 'page' : undefined" :class="{ active: currentPage === 'my-data' }" @click="navigatePage('my-data')" @keydown.enter="navigatePage('my-data')" @keydown.space.prevent="navigatePage('my-data')"><a><span class="nav-icon">◴</span>我的数据</a></li>
        <li v-if="currentUser.permissions?.includes('brand:page:read')" role="button" tabindex="0" :aria-current="currentPage === 'brand-pages' || currentPage === 'brand-page-preview' ? 'page' : undefined" :class="{ active: currentPage === 'brand-pages' || currentPage === 'brand-page-preview' }" @click="navigatePage('brand-pages')" @keydown.enter="navigatePage('brand-pages')" @keydown.space.prevent="navigatePage('brand-pages')"><a><span class="nav-icon">▧</span>品牌页面</a></li>
        <template v-if="canViewRelayReports(currentUser.permissions)">
          <li class="section-title">Token 资产管理</li>
          <li v-for="item in RELAY_REPORT_PAGES" :key="item.page" role="button" tabindex="0" :class="{active:currentPage===item.page}" :aria-current="currentPage===item.page?'page':undefined" @click="navigatePage(item.page)" @keydown.enter="navigatePage(item.page)" @keydown.space.prevent="navigatePage(item.page)"><a><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}</a></li>
        </template>
        </template>
        <template v-if="isAdminEntry">
        <template v-for="group in [{label:'中转网关与算力池',items:relayAdminNavItems},{label:'系统管理',items:otherAdminNavItems}]" :key="group.label">
          <li v-if="group.items.length" class="section-title">{{ group.label }}</li>
          <li v-for="item in group.items" :key="item.key" role="button" tabindex="0" :aria-current="adminSection === item.key ? 'page' : undefined" :class="{ active: adminSection === item.key }" @click="openAdminSection(item.key)" @keydown.enter="openAdminSection(item.key)" @keydown.space.prevent="openAdminSection(item.key)"><a><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}</a></li>
        </template>
        </template>
        <template v-if="isToolEntry">
        <li class="section-title">工具执行</li>
        <li class="active"><a><span class="nav-icon">▶</span>{{ currentTool?.name || toolRouteKey }}</a></li>
        <li role="button" tabindex="0" @click="navigateEntry('workbench')" @keydown.enter="navigateEntry('workbench')" @keydown.space.prevent="navigateEntry('workbench')"><a><span class="nav-icon">←</span>返回工作台</a></li>
        </template>
      </ul>
      <div class="spacer"></div>
      <div class="user-profile"><span class="avatar">{{ currentUser.name?.slice(0, 1) || '用' }}</span><span>{{ currentUser.name || currentUser.account }} ({{ roleName(currentUser.role, currentUser.permissions) }})</span><button type="button" @click="logout">退出</button></div>
    </nav>

    <main class="main-content">
      <RelayWorkspace v-if="isWorkbenchEntry && currentPage === 'my-data'" mode="personal" :permissions="currentUser.permissions" />
      <template v-if="isWorkbenchEntry && activeRelayReport">
        <RelayWorkspace v-if="canViewRelayReports(currentUser.permissions)" :mode="activeRelayReport.mode" :permissions="currentUser.permissions" />
        <section v-else class="table-card"><h2>暂无查看权限</h2><p>此页面需要 Token 消耗查看权限。</p><button type="button" class="btn-primary" @click="navigateEntry('workbench')">返回工作台</button></section>
      </template>
      <section v-if="isWorkbenchEntry && currentPage === 'workbench'">
        <div class="hero-section">
          <div class="hero-greeting">
            <div class="greeting-icon"></div>
            <h1><span class="greeting-text">你好！</span>一起互动-AI工作台</h1>
          </div>

          <div class="hero-card-container">
            <div class="bg-tabs">
              <div class="bg-tab tab-blue">市场情报</div>
              <div class="bg-tab tab-red">推广图制作</div>
              <div class="bg-tab tab-green">竞店分析</div>
              <div class="bg-tab tab-purple">项目管理</div>
            </div>
            <div class="hero-main-card">
              <h2>开启智能工作新范式</h2>
              <p>平台已集成四大业务线核心工具，请从下方选择对应的应用开启分析之旅。</p>
              <div class="hero-stats">
                <div class="stat-item"><div class="stat-num">{{ statValue('normalToolCount') }}</div><div class="stat-label">已上线工具</div></div>
                <div class="stat-divider"></div>
                <div class="stat-item"><div class="stat-num">{{ statValue('enabledDepartmentCount') }}</div><div class="stat-label">赋能部门</div></div>
                <div class="stat-divider"></div>
                <div class="stat-item"><div class="stat-num">{{ statValue('enabledUserCount') }}</div><div class="stat-label">赋能人数</div></div>
                <div class="stat-divider"></div>
                <div class="stat-item"><div class="stat-num">{{ statValue('projectCount') }}</div><div class="stat-label">已创建项目</div></div>
                <div class="stat-divider"></div>
                <div class="stat-item resource-stat-item">
                  <div class="resource-stat-values" aria-label="资源消耗">
                    <div class="resource-metric resource-metric-token"><strong>{{ resourceUsageMetrics.tokens }}</strong><span>Token</span></div>
                    <div class="resource-metric-divider" aria-hidden="true"></div>
                    <div class="resource-metric resource-metric-credit"><strong>{{ resourceUsageMetrics.credits }}</strong><span>积分</span></div>
                  </div>
                  <div class="stat-label">资源消耗</div>
                </div>
                <div class="stat-divider"></div>
                <div class="stat-item"><div class="stat-num">{{ statValue('generatedImageCount') }}</div><div class="stat-label">已生成图片</div></div>
              </div>
            </div>
          </div>
        </div>

        <div class="dept-header">
          <h3>{{ activeDepartment.name }}导航</h3>
          <span v-if="activeDepartment.reserved" class="state-pill unopened">预留部门，不进入一期验收</span>
        </div>
        <div class="tools-grid tools-grid-new">
          <article v-for="tool in activeDepartment.tools" :key="tool.key" class="tool-item" role="button" :tabindex="tool.status === 'normal' ? 0 : -1" :aria-disabled="tool.status !== 'normal'" :class="[tool.theme, `theme-${tool.theme}`, { disabled: tool.status !== 'normal' }]" @click="openTool(tool)" @keydown.enter="openTool(tool)" @keydown.space.prevent="openTool(tool)">
            <span v-if="tool.status !== 'normal'" class="tool-unavailable-badge">未接入</span>
            <div class="tool-icon squircle-icon">{{ tool.name.slice(0, 1) }}</div>
            <div class="tool-info">
              <h4>{{ tool.name }} <span>›</span></h4>
              <p>{{ tool.desc }}</p>
            </div>
          </article>
        </div>
      </section>

      <section v-if="isWorkbenchEntry && currentPage === 'runs'" class="run-records-page">
        <div class="run-filter-bar">
          <input v-model="runSearchQuery" class="run-search-input" placeholder="搜索工具或记录..." />
          <label class="run-filter-item"><span>工具</span><UiSelect v-model="runFilters.tool" :options="runToolOptions" /></label>
          <label class="run-filter-item"><span>员工</span><UiSelect v-model="runFilters.employee" :options="runEmployeeOptions" /></label>
          <label class="run-filter-item"><span>所属部门</span><ElTreeSelect v-model="runFilters.department" class="run-department-tree-select" :data="runDepartmentTreeData" node-key="id" check-strictly default-expand-all clearable placeholder="全部部门" :render-after-expand="false" /></label>
          <label class="run-filter-item"><span>状态</span><UiSelect v-model="runFilters.status" :options="runStatusOptions" /></label>
        </div>
        <div class="run-records-list">
          <table class="run-records-table">
            <colgroup><col class="run-col-time" /><col class="run-col-type" /><col class="run-col-tool" /><col class="run-col-user" /><col class="run-col-department" /><col class="run-col-resource" /><col class="run-col-project" /><col class="run-col-status" /><col class="run-col-actions" /></colgroup>
            <thead><tr><th>时间</th><th>记录类型</th><th>工具名称</th><th>员工</th><th>所属部门</th><th>资源消耗</th><th>所属项目</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="record in pagedRunRecords" :key="record.id" class="run-record-row">
                <td class="run-time-cell"><span>{{ record.date }}</span><span>{{ record.clock }}</span></td>
                <td><span class="run-type-badge">{{ record.type }}</span></td>
                <td>
                  <div class="run-tool-cell">
                    <span class="run-tool-icon" :class="`tone-${runTone(record.toolKey)}`"><component :is="runToolIcon(record.toolKey)" :size="16" /></span>
                    <strong>{{ record.tool }}</strong>
                  </div>
                  <small class="run-output-summary" :class="{ failed: record.statusKey === 'failed' }">{{ record.output }}</small>
                </td>
                <td><div class="run-user-cell"><span class="run-user-avatar" :class="`tone-${runTone(record.userId)}`">{{ record.user?.slice(0, 1) || '用' }}</span><span>{{ record.user }}</span></div></td>
                <td><span class="run-department-tag" :class="`tone-${runTone(record.deptKey)}`">{{ record.dept }}</span></td>
                <td><strong class="run-resource-value">{{ record.tokens }}</strong><small class="run-resource-duration">{{ record.duration }}</small></td>
                <td><span class="run-project-name">{{ record.project }}</span></td>
                <td><span class="run-status"><i :class="record.statusKey === 'success' ? 'success' : 'failed'"></i>{{ record.status }}</span></td>
                <td>
                  <div class="run-actions">
                    <button class="run-action-button" type="button" title="查看详情" aria-label="查看详情" @click="openRunDetail(record)"><Eye :size="17" /></button>
                    <button v-if="hasCopyableRunResult(record)" class="run-action-button" type="button" :title="runCopyActionLabel(record)" :aria-label="runCopyActionLabel(record)" @click="copyRunResult(record)"><Copy :size="16" /></button>
                    <button v-if="hasDownloadableRunAttachment(record)" class="run-action-button" type="button" title="下载附件" aria-label="下载附件" @click="downloadRunAttachments(record)"><Download :size="17" /></button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="pagedRunRecords.length === 0" class="run-records-empty">未找到匹配的运行记录</div>
        <AdminPagination v-if="filteredRunRecords.length" :page="runPagination" @change="changeRunPage" />
      </section>

      <section v-if="isWorkbenchEntry && currentPage === 'brand-pages'" class="brand-pages-page">
        <div class="page-title"><h2>品牌页面</h2><span>按品牌搜索已上传的 HTML 页面</span></div>
        <div class="brand-page-search">
          <ElTreeSelect v-model="brandPageDepartmentFilter" class="brand-page-department-filter" :data="brandPageDepartmentTreeData" node-key="id" check-strictly default-expand-all clearable placeholder="全部可见部门" :render-after-expand="false" @change="loadBrandPages" />
          <input v-model="brandPageSearch" placeholder="输入品牌名称搜索" @input="loadBrandPages" />
          <button class="btn-primary" type="button" @click="loadBrandPages">搜索</button>
        </div>
        <div v-if="brandPagesLoading" class="brand-page-empty">加载中…</div>
        <div v-else-if="brandPages.length" class="brand-page-grid">
          <article v-for="page in brandPages" :key="page.id" class="brand-page-card" role="button" tabindex="0" @click="openBrandPage(page)" @keydown.enter="openBrandPage(page)" @keydown.space.prevent="openBrandPage(page)">
            <span class="brand-page-icon">▧</span><div><h3>{{ page.brandName }}</h3><p>{{ departmentName(page.departmentId) }} · {{ page.fileName }}</p><small>更新于 {{ formatTime(page.updatedAt) }}</small></div><b>查看 →</b>
          </article>
        </div>
        <div v-else class="brand-page-empty">暂无匹配的品牌页面</div>
      </section>

      <section v-if="isWorkbenchEntry && currentPage === 'brand-page-preview'" ref="brandPagePreviewRef" class="brand-page-preview-page">
        <div ref="brandPageControlsRef" class="brand-page-preview-controls" :class="{ expanded: brandPageControlsExpanded }">
          <button class="brand-page-preview-toggle" type="button" :aria-expanded="brandPageControlsExpanded" :aria-label="brandPageControlsExpanded ? '收起页面操作' : '展开页面操作'" :title="brandPageControlsExpanded ? '收起页面操作' : '展开页面操作'" @click.stop="toggleBrandPageControls">
            <span aria-hidden="true">{{ brandPageControlsExpanded ? '‹' : '›' }}</span><b>操作</b>
          </button>
          <div v-if="brandPageControlsExpanded" class="brand-page-preview-actions">
            <button class="btn-secondary" type="button" @click="returnFromBrandPagePreview">← {{ brandPagePreviewReturn === 'admin' ? '返回后台' : '返回搜索' }}</button>
            <button class="btn-secondary" type="button" @click="toggleBrandPageFullscreen">{{ brandPageFullscreen ? '退出全屏' : '浏览器全屏' }}</button>
          </div>
        </div>
        <div v-if="brandPagesLoading" class="brand-page-empty">正在加载页面…</div>
        <iframe v-else-if="brandPageHtml" ref="brandPageIframeRef" class="brand-page-preview" :srcdoc="brandPageHtml" title="品牌 HTML 页面预览" @load="bindBrandPagePreviewInteractions"></iframe>
        <div v-else class="brand-page-empty">页面不存在或无权访问</div>
      </section>

      <section v-if="isWorkbenchEntry && currentPage === 'project-board'" class="project-board-page">
        <div class="dashboard-stats-card card-premium">
          <div class="stats-header"><h3>整体项目统计</h3></div>
          <div class="project-board-stats">
            <div class="stat-block"><div class="stat-title">总项目数</div><div class="stat-val">{{ projects.length }}</div></div>
            <div class="stat-block"><div class="stat-title">总体项目进度</div><div class="stat-val">{{ overallProjectProgress }}%</div></div>
            <div class="stat-block"><div class="stat-title">项目产出数</div><div class="stat-val">{{ totalProjectResults }}</div></div>
            <div class="stat-block"><div class="stat-title">资源消耗</div><div class="stat-val">{{ totalProjectResources }}</div></div>
            <div class="stat-block"><div class="stat-title">图片生成数</div><div class="stat-val">{{ totalProjectImages }}</div></div>
          </div>
        </div>
        <div class="filter-bar project-board-filters">
          <input v-model="projectBoardSearch" class="project-board-search" placeholder="输入项目名称或商品名称搜索" />
          <label class="project-board-filter"><span>按部门</span><ElTreeSelect v-model="projectBoardDepartment" class="project-department-tree-select" :data="projectDepartmentTreeData" node-key="id" check-strictly default-expand-all clearable placeholder="全部部门" :render-after-expand="false" /></label>
          <label class="project-board-filter"><span>按品牌</span><input v-model="projectBoardBrand" placeholder="全部品牌" /></label>
          <label class="project-board-filter"><span>按创建人</span><input v-model="projectBoardCreator" placeholder="全部人员" /></label>
        </div>
        <div class="project-board-table-wrap">
          <table class="project-board-table">
            <thead><tr><th>创建时间</th><th>项目名称</th><th>所属部门</th><th>品牌</th><th>商品名称</th><th>创建人</th><th>总体进度</th><th>项目产出数</th><th>资源消耗</th><th>图片生成</th></tr></thead>
            <tbody>
              <tr v-for="project in pagedProjectBoard" :key="project.id" class="project-board-row">
                <td class="project-board-time">{{ formatTime(project.createdAt) }}</td>
                <td>
                  <button class="project-board-project-link" type="button" :aria-label="`查看项目：${project.name}`" @click="selectProjectFromBoard(project)"><strong class="project-board-name">{{ project.name }}</strong></button>
                </td>
                <td><span class="project-board-department">{{ projectDepartmentName(project) }}</span></td>
                <td>{{ project.brand || '-' }}</td>
                <td>{{ project.productName || '-' }}</td>
                <td><div class="project-board-creator"><span>{{ project.owner?.slice(0, 1) || '用' }}</span><b>{{ project.owner || '-' }}</b></div></td>
                <td>
                  <div class="project-board-progress">
                    <span><i :style="{ width: `${projectProgressFor(project.id)}%` }"></i></span>
                    <b>{{ projectProgressFor(project.id) }}%</b>
                  </div>
                </td>
                <td><strong class="project-board-number">{{ resultCountFor(project.id) }}</strong></td>
                <td><strong class="project-board-number">{{ formatResourceUsage(project.tokenUsage, project.creditUsage, '0') }}</strong></td>
                <td><strong class="project-board-number">{{ project.imageCount || 0 }}</strong></td>
              </tr>
              <tr v-if="pagedProjectBoard.length === 0" class="project-board-empty-row"><td colspan="10" class="table-empty">未找到匹配项目</td></tr>
            </tbody>
          </table>
        </div>
        <AdminPagination :page="projectBoardPagination" @change="projectBoardPage = $event" />
      </section>

      <section v-if="isWorkbenchEntry && currentPage === 'projects'">
        <div class="project-page-layout">
          <aside class="sidebar-secondary project-sidebar">
            <div class="sp-header"><span>项目</span><button v-if="currentUser.permissions?.includes('project:create')" class="btn-add-project" type="button" title="新建项目" aria-label="新建项目" @click="openProjectCreate">＋</button></div>
            <div v-if="projectGateMessage" class="check-result">{{ projectGateMessage }}</div>
            <AppDialog
              :open="showProjectCreate"
              :title="editingProjectId ? '编辑项目' : '新建项目'"
              description="完善商品与项目资料，字段将同步展示在项目详情中。"
              size="large"
              :close-disabled="projectCreating"
              @close="closeProjectEditor"
            >
              <form id="project-editor-form" class="project-form-grid" @submit.prevent="submitProject">
                <label>项目名称<input v-model="projectForm.name" placeholder="例如：夏季新品推广" /></label>
                <label>商品名称<input v-model="projectForm.productName" placeholder="请输入商品名称" /></label>
                <label>所属部门<ElTreeSelect v-model="projectForm.departmentId" class="project-department-tree-select" :data="projectEditorDepartmentTreeData" node-key="id" check-strictly default-expand-all placeholder="请选择所属部门" :render-after-expand="false" /></label>
                <label>品牌<input v-model="projectForm.brand" placeholder="请输入品牌" /></label>
                <label>负责人
                  <ElSelect v-model="projectForm.ownerUserId" filterable placeholder="请选择负责人">
                    <ElOption v-for="user in projectOwnerOptions" :key="user.id" :label="projectUserLabel(user)" :value="user.id" />
                  </ElSelect>
                </label>
                <label class="wide">项目成员
                  <ElSelect v-model="projectForm.memberUserIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="请选择参与成员">
                    <ElOption v-for="user in projectUsers" :key="user.id" :label="projectUserLabel(user)" :value="user.id" />
                  </ElSelect>
                  <small>负责人会自动加入项目；允许选择其他部门成员进行跨部门协作。</small>
                </label>
                <label>类目<input v-model="projectForm.category" placeholder="请输入商品类目" /></label>
                <label>售价<input v-model="projectForm.salePrice" type="number" min="0" step="0.01" placeholder="0.00" /></label>
                <label>立项时间<input v-model="projectForm.establishedDate" type="date" /></label>
                <label class="wide">商品卖点<textarea v-model="projectForm.sellingPoints" rows="3" placeholder="请输入商品卖点"></textarea></label>
                <label class="wide project-cover-field">项目封面<input type="file" accept="image/jpeg,image/png,image/webp" @change="selectProjectCover" /><small>支持 JPG、PNG、WebP，不超过 5MB；重新选择会替换原封面</small></label>
                <label v-if="editingProjectId && selectedProject?.coverUrl" class="wide project-cover-remove"><input v-model="projectRemoveCover" type="checkbox" />移除当前封面</label>
                <div v-if="projectCreateMessage" class="check-result wide">{{ projectCreateMessage }}</div>
              </form>
              <template #footer>
                <button class="btn-secondary" type="button" :disabled="projectCreating" @click="closeProjectEditor">取消</button>
                <button class="btn-primary" type="submit" form="project-editor-form" :disabled="projectCreating">{{ projectCreating ? '保存中...' : editingProjectId ? '保存修改' : '创建项目' }}</button>
              </template>
            </AppDialog>
            <div class="project-sidebar-filters">
              <input v-model="projectSearchQuery" class="side-search" placeholder="输入项目名称搜索" />
              <ElTreeSelect v-model="projectDepartmentFilter" class="side-search project-department-tree-select" :data="projectDepartmentTreeData" node-key="id" check-strictly default-expand-all clearable placeholder="全部部门" :render-after-expand="false" />
            </div>
            <ul class="sp-list">
              <li v-for="project in filteredProjects" :key="project.id" role="button" tabindex="0" :aria-current="selectedProject?.id === project.id ? 'true' : undefined" :class="{ active: selectedProject?.id === project.id }" @click="selectProject(project)" @keydown.enter="selectProject(project)" @keydown.space.prevent="selectProject(project)">
                <div class="project-name">{{ project.name }}</div>
                <div class="project-tags"><span class="ptag">{{ projectDepartmentName(project) }}</span></div>
              </li>
              <li v-if="filteredProjects.length === 0"><b>{{ projects.length ? '未找到匹配项目' : '暂无项目' }}</b><span>{{ projects.length ? '请调整搜索词或部门筛选' : '新建项目后会显示在这里' }}</span></li>
            </ul>
          </aside>
          <div v-if="selectedProject" class="project-detail-main">
            <div class="project-overview-card">
              <div class="pi-image"><img v-if="selectedProject.coverUrl" :src="workbenchFileUrl(selectedProject.coverUrl)" :alt="selectedProject.productName || selectedProject.name" /><span v-else>▰</span></div>
              <div class="po-content">
                <div class="po-title-row">
                  <div>
                    <h1>{{ selectedProject.name }}</h1>
                    <p>商品名称：{{ selectedProject.productName || '未填写商品信息' }}</p>
                  </div>
                  <div class="project-title-actions"><button v-if="selectedProject.canManage" class="btn-secondary" type="button" @click="openProjectEditor(selectedProject)">编辑项目</button><button v-if="selectedProject.canManage" class="btn-secondary danger" type="button" :disabled="projectDeletingId === selectedProject.id" @click="removeManagedProject(selectedProject)">{{ projectDeletingId === selectedProject.id ? '删除中...' : '删除项目' }}</button><span class="status-badge running">{{ selectedProject.status }}</span></div>
                </div>
                <div class="po-meta-grid">
                  <span>所属部门：{{ projectDepartmentName(selectedProject) }}</span>
                  <span>所属品牌：{{ selectedProject.brand || '-' }}</span>
                  <span>负责人：{{ selectedProject.owner }}</span>
                  <span>类目：{{ selectedProject.category || '-' }}</span>
                  <span>售价：{{ selectedProject.salePrice ? `¥${selectedProject.salePrice}` : '-' }}</span>
                  <span>立项时间：{{ selectedProject.establishedDate || '-' }}</span>
                  <span class="wide">项目成员：{{ (selectedProject.members || []).join('、') || '-' }}</span>
                  <span class="wide">商品卖点：{{ selectedProject.sellingPoints || selectedProject.summary || '-' }}</span>
                </div>
              </div>
            </div>

            <div class="dashboard-stats-card card-premium">
              <div class="stats-header"><h3>项目数据统计</h3></div>
              <div class="project-stat-grid">
                <div class="stat-block"><div class="stat-title">当前任务进度</div><div class="stat-val">{{ projectProgress }}%</div></div>
                <div class="stat-block"><div class="stat-title">累计产出物</div><div class="stat-val">{{ selectedProjectResultCount }}</div></div>
                <div class="stat-block"><div class="stat-title">资源消耗</div><div class="stat-val">{{ formatResourceUsage(selectedProject.tokenUsage, selectedProject.creditUsage, '0') }}</div></div>
                <div class="stat-block"><div class="stat-title">图片生成数</div><div class="stat-val">{{ selectedProject.imageCount }}</div></div>
              </div>
              <div v-if="projectAllDepartmentsDone" class="project-complete-banner">全部部门任务已完成</div>
            </div>

            <div class="project-results-section card-premium">
              <div class="stats-header"><h3>生成记录</h3><span>{{ selectedProjectGenerationRecords.length }} 条已关联记录</span></div>
              <div v-if="selectedProjectGenerationRecords.length" class="project-results-table-wrap">
                <table class="project-results-table">
                  <thead><tr><th>生成时间</th><th>工具</th><th>操作人</th><th>结果摘要</th><th>操作</th></tr></thead>
                  <tbody><tr v-for="record in selectedProjectGenerationRecords" :key="record.id"><td>{{ record.run?.time || formatTime(record.createdAt) }}</td><td>{{ record.run?.tool || toolName(record.toolKey) }}</td><td>{{ record.run?.user || userName(record.submittedBy) }}</td><td class="project-result-summary">{{ record.outputSummary || '-' }}</td><td class="table-actions"><button v-if="record.run" class="icon-btn" type="button" @click="openRunDetail(record.run)">查看</button><button v-if="selectedProject.canManage" class="icon-btn danger" type="button" :disabled="projectResultRemovingId === record.id" @click="removeProjectGenerationRecord(record, selectedProject)">{{ projectResultRemovingId === record.id ? '移出中...' : '移出项目' }}</button></td></tr></tbody>
                </table>
              </div>
              <div v-else class="project-results-empty">暂无已关联的生成记录</div>
            </div>

            <div class="workflow-board-section card-premium">
              <div class="stats-header"><h3>任务</h3></div>
              <div class="workflow-lanes-container">
                <div v-for="lane in projectWorkflowLanes" :key="lane.name" class="workflow-lane" :class="{ active: lane.completed === lane.tasks.length && lane.tasks.length }">
                  <div class="lane-header">
                    <h4>{{ lane.name }}</h4>
                    <span class="stage-count">{{ lane.completed }}/{{ lane.tasks.length }} 任务已完成</span>
                  </div>
                  <div v-if="lane.completed === lane.tasks.length && lane.tasks.length" class="lane-complete-tip">部门任务已完成</div>
                  <div v-for="task in lane.tasks" :key="task.id" class="task-slot">
                    <div class="node-card" :class="{ completed: task.done, empty: !task.done }">
                      <div v-if="task.done" class="node-status-badge">✓</div>
                      <div class="node-tool-title"><span class="node-tool-icon">{{ task.tool.slice(0, 1) }}</span>{{ task.tool }}</div>
                      <div class="node-preview">{{ task.summary }}</div>
                      <div class="node-footer">
                        <div class="nf-info">
                          <div class="node-author"><div class="small-avatar">{{ task.user.slice(0, 1) }}</div><span>{{ task.user }}</span></div>
                          <span class="nf-time">{{ task.time }}</span>
                        </div>
                        <button v-if="task.done" class="btn-outline-sm" type="button" @click="openRunDetail(runRecords.find((run) => run.id === task.runId))">查看</button>
                        <button v-else class="btn-run-tool" type="button" :disabled="task.toolStatus !== 'normal' || !selectedProject.canRun" @click="openProjectTaskTool(task, selectedProject)">{{ task.toolStatus !== 'normal' ? '未接入' : selectedProject.canRun ? '去执行 →' : '仅项目成员可执行' }}</button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

          </div>
          <div v-else class="project-main">
            <div class="kanban-card">
              <h3>暂无项目数据</h3>
              <div class="suggestion">请先创建项目，再从工作台打开工具。</div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="isAdminEntry && !canAccessAdmin">
        <div class="page-title"><h2>无后台权限</h2><span>当前账号没有后台管理权限，请切换管理员账号或返回前台工作台。</span></div>
        <div class="table-card">
          <div class="suggestion">后台入口需要至少一个 admin:* 权限点。</div>
          <button class="btn-primary" type="button" @click="navigateEntry('workbench')">返回前台工作台</button>
        </div>
      </section>

      <section v-if="isAdminEntry && canAccessAdmin && currentPage === 'config'">
        <RelayWorkspace v-if="adminSection === 'relayQuotas' && canReadAdminSection(currentUser.permissions, adminSection)" mode="quotas" :permissions="currentUser.permissions" />
        <div v-if="!['relayQuotas','upstreamAccounts'].includes(adminSection)" class="page-title"><h2>{{ adminSectionTitle }}</h2><span>{{ adminSectionSubtitle }}</span></div>
        <div v-if="adminSection === 'overview'" class="admin-overview">
          <div class="stats-grid admin-stats">
            <div><small>工具数</small><b>{{ statValue('toolCount') }}</b></div>
            <div><small>用户数</small><b>{{ statValue('userCount') }}</b></div>
            <div><small>运行记录</small><b>{{ statValue('runCount') }}</b></div>
            <div><small>总 Token 消耗</small><b>{{ resourceUsageLabel }}</b></div>
          </div>
          <div class="config-grid">
            <div class="config-card">
              <h3>工具部署门禁</h3>
              <p>未接入 manifest、tool-runs、AI 网关的工具不得进入正式部署。</p>
              <button v-if="canReadAdminSection(currentUser.permissions, 'deployments')" class="btn-primary" type="button" @click="openAdminSection('deployments')">查看部署门禁</button>
            </div>
            <div class="config-card">
              <h3>AI API 网关</h3>
              <p>统一代理文本、图片和向量模型，集中管理 Key、Token、成本和失败日志。</p>
              <button v-if="canReadAdminSection(currentUser.permissions, 'models')" class="btn-secondary" type="button" @click="openAdminSection('models')">查看模型配置</button>
            </div>
          </div>
        </div>
        <div v-if="adminSection === 'deployments'" class="config-grid">
          <div class="config-card">
            <h3>工具部署门禁</h3>
            <p>未接入 manifest、tool-runs、AI 网关的工具不得进入正式部署。</p>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'deployments')" class="btn-primary" type="button" @click="runManifestCheck">提交部署申请</button>
              <div v-if="manifestCheck" class="check-result" :class="{ passed: manifestCheck.passed }">
                {{ manifestCheck.passed ? '部署门禁通过，允许进入正式发布流程' : manifestCheck.errors.join('；') }}
              </div>
          </div>
          <div class="config-card">
            <h3>AI API 网关</h3>
            <p>统一代理文本、图片和向量模型，集中管理 Key、Token、成本和失败日志。</p>
            <button v-if="canReadAdminSection(currentUser.permissions, 'models')" class="btn-secondary" type="button" @click="focusModelConfig">查看模型配置</button>
          </div>
        </div>
        <div v-if="adminSection === 'deployments'" class="table-card admin-panel">
          <div class="panel-header">
            <div><h3>部署门禁记录</h3><span>服务端强制校验，未通过不得发布到正式服务器</span></div>
          </div>
          <table>
            <thead><tr><th>工具</th><th>版本</th><th>申请人</th><th>状态</th><th>失败原因</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-if="adminPages.deployments.loading"><td colspan="6" class="table-empty table-loading">加载中…</td></tr>
              <template v-if="!adminPages.deployments.loading">
              <tr v-for="request in pagedDeployments" :key="request.id">
                <td><strong>{{ request.name }}</strong><small>{{ request.toolKey }}</small></td>
                <td>{{ request.version }}</td>
                <td>{{ request.requestedBy }}</td>
                <td><span class="status-badge" :class="request.status">{{ request.status === 'approved' ? '通过' : '拒绝' }}</span></td>
                <td><small>{{ request.errors?.length ? request.errors.join('；') : '无' }}</small></td>
                <td>{{ formatTime(request.createdAt) }}</td>
              </tr>
              </template>
              <tr v-if="!adminPages.deployments.loading && pagedDeployments.length === 0"><td colspan="6" class="table-empty">暂无部署申请</td></tr>
            </tbody>
          </table>
          <AdminPagination :page="adminPages.deployments" @change="changeAdminPage('deployments', $event)" />
        </div>
        <div v-if="adminSection === 'users'" class="admin-grid">
          <div class="table-card admin-panel">
            <div class="panel-header">
              <div><h3>用户管理</h3><span>新增用户默认要求首次登录修改密码</span></div>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'users')" class="btn-primary" type="button" @click="openUserCreateDialog">新增用户</button>
            </div>
            <div class="admin-user-search">
              <input v-model="userSearch" type="search" aria-label="搜索账号或姓名" placeholder="搜索账号或姓名" />
            </div>
            <table>
              <thead><tr><th>账号</th><th>姓名</th><th>角色</th><th>部门</th><th>状态</th><th>首次改密</th><th v-if="canWriteAdminSection(currentUser.permissions, 'users')">操作</th></tr></thead>
              <tbody>
                <tr v-if="adminPages.users.loading"><td :colspan="canWriteAdminSection(currentUser.permissions, 'users') ? 7 : 6" class="table-empty table-loading">加载中…</td></tr>
                <template v-if="!adminPages.users.loading">
                <tr v-for="user in pagedUsers" :key="user.id">
                  <td>{{ user.account }}</td><td>{{ user.name }}</td>
                  <td>
                    <strong>{{ roleNames(user.roleIds || [user.role]) }}</strong>
                  </td>
                  <td>{{ departmentNames(user.departmentKeys).join('、') }}</td>
                  <td><span class="status-badge" :class="user.enabled ? 'success' : 'muted'">{{ user.enabled ? '已启用' : '已禁用' }}</span></td>
                  <td><span class="status-badge" :class="user.mustChangePassword ? 'warning' : 'success'">{{ user.mustChangePassword ? '需要修改' : '无需修改' }}</span></td>
                  <td v-if="canWriteAdminSection(currentUser.permissions, 'users')" class="table-actions">
                    <button class="icon-btn" type="button" @click="toggleUser(user)">{{ user.enabled ? '禁用' : '启用' }}</button>
                    <button class="icon-btn" type="button" @click="resetUser(user)">重置密码</button>
                    <button class="icon-btn" type="button" @click="openUserClientKeyDialog(user)">客户端 Key</button>
                    <button class="icon-btn" type="button" @click="openUserEditDialog(user)">编辑</button>
                    <button class="icon-btn danger" type="button" @click="deleteManagedUser(user)">删除</button>
                  </td>
                </tr>
                </template>
                <tr v-if="!adminPages.users.loading && pagedUsers.length === 0"><td :colspan="canWriteAdminSection(currentUser.permissions, 'users') ? 7 : 6" class="table-empty">暂无用户</td></tr>
              </tbody>
            </table>
            <AdminPagination :page="adminPages.users" @change="changeAdminPage('users', $event)" />
          </div>
        </div>
        <div v-if="adminSection === 'organization'" class="organization-layout">
          <div class="table-card admin-panel organization-tree-panel">
            <div class="panel-header">
              <div><h3>组织架构</h3><span>真实组织部门，用于用户归属和数据权限</span></div>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'organization')" class="btn-primary" type="button" :disabled="organizationSaving" @click="newOrganizationDepartment">新增一级部门</button>
            </div>
            <ElTree
              class="organization-management-tree"
              :data="organizationTreeData"
              node-key="id"
              default-expand-all
              highlight-current
              :expand-on-click-node="false"
              :class="{ 'is-mutation-locked': organizationSaving }"
              :current-node-key="organizationForm.id || undefined"
              empty-text="暂无部门，请先新增一级部门"
            >
              <template #default="{ data: node }">
                <div class="organization-tree-node" :class="{ disabled: !node.enabled }">
                  <strong>{{ node.name }}</strong>
                  <div class="organization-node-actions" @click.stop>
                    <ElTag size="small" :type="node.enabled ? 'success' : 'info'" effect="light">{{ node.enabled ? '启用' : '禁用' }}</ElTag>
                    <ElButton v-if="canWriteAdminSection(currentUser.permissions, 'organization')" link class="organization-node-edit" :disabled="organizationSaving" @click.stop="selectOrganizationDepartment(node)">编辑</ElButton>
                    <ElButton v-if="canWriteAdminSection(currentUser.permissions, 'organization')" link class="organization-node-add" :disabled="organizationSaving" @click.stop="newChildOrganizationDepartment(node)">新增下级</ElButton>
                    <ElButton v-if="canWriteAdminSection(currentUser.permissions, 'organization')" link type="danger" class="organization-node-delete" :disabled="organizationSaving" @click.stop="deleteSelectedOrganizationDepartment(node)">删除</ElButton>
                  </div>
                </div>
              </template>
            </ElTree>
          </div>
          <AppDialog
            v-if="canWriteAdminSection(currentUser.permissions, 'organization')"
            :open="organizationDialogVisible"
            :title="organizationForm.id ? '编辑部门' : organizationForm.parentId ? '新增下级部门' : '新增一级部门'"
            :description="!organizationForm.id && organizationForm.parentId ? `将在“${organizationParentName}”下创建新部门` : '部门可随时调整，禁用后不再用于新用户分配'"
            :close-disabled="organizationSaving"
            @close="closeOrganizationDialog"
          >
            <form id="organization-editor-form" class="role-form-grid organization-form-grid" @submit.prevent="saveOrganizationDepartment">
              <label>部门名称<ElInput v-model="organizationForm.name" :disabled="organizationSaving" placeholder="例如：业务一组" /></label>
              <div class="organization-parent-field">
                <span class="organization-field-label">上级部门</span>
                <ElTreeSelect
                  v-model="organizationForm.parentId"
                  class="organization-parent-select"
                  :data="organizationParentTreeData"
                  node-key="id"
                  check-strictly
                  default-expand-all
                  clearable
                  placeholder="无上级部门（一级部门）"
                  :render-after-expand="false"
                  :disabled="organizationSaving"
                />
              </div>
              <div v-if="organizationMessage" class="check-result" :class="{ passed: organizationMessage.includes('已保存') || organizationMessage.includes('已更新') }">{{ organizationMessage }}</div>
            </form>
            <template #footer>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'organization') && organizationForm.id" class="btn-secondary" type="button" :disabled="organizationSaving" @click="toggleOrganizationDepartment">{{ selectedOrganizationDepartment?.enabled ? '禁用部门' : '启用部门' }}</button>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'organization') && organizationForm.id" class="btn-secondary danger" type="button" :disabled="organizationSaving" @click="deleteSelectedOrganizationDepartment">删除部门</button>
              <button class="btn-secondary" type="button" :disabled="organizationSaving" @click="closeOrganizationDialog">取消</button>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'organization')" class="btn-primary" type="submit" form="organization-editor-form" :disabled="organizationSaving">{{ organizationSaving ? '保存中...' : '保存部门' }}</button>
            </template>
          </AppDialog>
        </div>
        <div v-if="adminSection === 'roles'" class="role-admin-layout">
          <div class="table-card admin-panel role-list-panel">
            <div class="panel-header">
              <div><h3>角色列表</h3><span>角色是权限和工具授权的配置单元</span></div>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'roles')" class="btn-primary" type="button" :disabled="roleSaving" @click="newRole">新增角色</button>
            </div>
            <div class="role-list-table-wrap">
            <table class="role-list-table">
              <thead><tr><th>角色名称</th><th>状态</th><th>数据范围</th><th>权限点</th><th>工具权限</th><th v-if="canWriteAdminSection(currentUser.permissions, 'roles')">操作</th></tr></thead>
              <tbody>
                <tr v-if="adminPages.roles.loading"><td :colspan="canWriteAdminSection(currentUser.permissions, 'roles') ? 6 : 5" class="table-empty table-loading">加载中…</td></tr>
                <template v-if="!adminPages.roles.loading">
                  <tr v-for="role in pagedRoles" :key="role.code">
                    <td><strong>{{ role.name }}</strong><small>{{ role.description || role.code }}</small></td>
                    <td><span class="status-badge" :class="role.enabled ? 'success' : 'muted'">{{ role.enabled ? '已启用' : '已禁用' }}</span></td>
                    <td>{{ dataScopeLabel(role.dataScope) }}</td>
                    <td>{{ (role.permissionCodes || []).length }}</td>
                    <td>{{ (role.toolKeys || []).length }}</td>
                    <td v-if="canWriteAdminSection(currentUser.permissions, 'roles')" class="table-actions">
                      <button class="icon-btn role-row-edit" type="button" :disabled="roleSaving" @click="selectRole(role)">编辑</button>
                      <button class="icon-btn danger role-row-delete" type="button" :disabled="roleSaving || role.builtIn" :title="role.builtIn ? '系统内置角色不可删除' : '删除角色'" @click="deleteSelectedRole(role)">删除</button>
                    </td>
                  </tr>
                </template>
                <tr v-if="!adminPages.roles.loading && pagedRoles.length === 0"><td :colspan="canWriteAdminSection(currentUser.permissions, 'roles') ? 6 : 5" class="table-empty">暂无角色</td></tr>
              </tbody>
            </table>
            </div>
            <AdminPagination :page="adminPages.roles" @change="changeAdminPage('roles', $event)" />
          </div>
          <AppDialog
            v-if="canWriteAdminSection(currentUser.permissions, 'roles')"
            :open="roleDialogVisible"
            :title="selectedRole ? '编辑角色' : '新增角色'"
            description="权限点由系统内置，客户不能新增权限编码"
            size="large"
            :close-disabled="roleSaving"
            @close="closeRoleDialog"
          >
            <form id="role-editor-form" class="role-editor-form" @submit.prevent="saveRole">
              <div class="role-form-grid">
              <label>角色名称<input v-model="roleForm.name" :disabled="roleSaving" placeholder="例如 市场负责人" /></label>
              <label>数据范围
                <UiSelect v-model="roleForm.dataScope" :options="dataScopeOptions" :disabled="roleSaving" />
              </label>
              <label class="wide">角色说明<input v-model="roleForm.description" :disabled="roleSaving" placeholder="说明这个角色适用的人群和边界" /></label>
              </div>
              <div class="permission-board">
                <section v-for="group in groupedPermissions" :key="group.module" class="permission-group">
                  <h4>{{ group.module }}</h4>
                  <button v-for="permission in group.items" :key="permission.code" type="button" class="check-row" :disabled="roleSaving" :class="{ sensitive: permission.sensitive, checked: roleForm.permissionCodes.includes(permission.code) }" @click="togglePermission(permission.code)">
                    <span class="custom-check-mark">{{ roleForm.permissionCodes.includes(permission.code) ? '✓' : '' }}</span>
                    <span><b>{{ permission.name }}</b><small>{{ permission.sensitive ? '敏感权限' : '普通权限' }}</small></span>
                  </button>
                </section>
              </div>
              <div class="tool-permission-panel">
                <h4>工具访问权限</h4>
                <div class="tool-check-grid">
                  <button v-for="tool in roleTools" :key="tool.key" type="button" class="check-row" :disabled="roleSaving" :class="{ checked: roleForm.toolKeys.includes(tool.key) }" @click="toggleRoleTool(tool.key)">
                    <span class="custom-check-mark">{{ roleForm.toolKeys.includes(tool.key) ? '✓' : '' }}</span>
                    <span><b>{{ tool.name }}</b><small>{{ departmentName(tool.departmentKey) }}</small></span>
                  </button>
                </div>
              </div>
              <div v-if="roleMessage" class="check-result" :class="{ passed: roleMessage.includes('已保存') }">{{ roleMessage }}</div>
            </form>
            <template #footer>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'roles') && selectedRole" class="btn-secondary" type="button" :disabled="roleSaving" @click="toggleRoleEnabled(selectedRole)">{{ selectedRole.enabled ? '禁用角色' : '启用角色' }}</button>
              <span v-if="selectedRole && selectedRole.builtIn" class="role-delete-hint">系统内置角色不可删除</span>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'roles') && selectedRole && !selectedRole.builtIn" class="btn-secondary danger" type="button" :disabled="roleSaving" @click="deleteSelectedRole">删除角色</button>
              <button class="btn-secondary" type="button" :disabled="roleSaving" @click="closeRoleDialog">取消</button>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'roles')" class="btn-primary" type="submit" form="role-editor-form" :disabled="roleSaving">{{ roleSaving ? '保存中...' : '保存角色' }}</button>
            </template>
          </AppDialog>
        </div>
        <div v-if="adminSection === 'tools'" class="admin-grid">
          <div class="table-card admin-panel">
            <div class="panel-header">
              <div><h3>工具配置</h3><span>状态会影响工具是否允许进入运行页</span></div>
            </div>
            <table>
              <thead><tr><th>工具</th><th>部门</th><th>状态</th><th>回传</th><th v-if="canWriteAdminSection(currentUser.permissions, 'tools')">操作</th></tr></thead>
              <tbody>
                <tr v-if="adminPages.tools.loading"><td :colspan="canWriteAdminSection(currentUser.permissions, 'tools') ? 5 : 4" class="table-empty table-loading">加载中…</td></tr>
                <template v-if="!adminPages.tools.loading">
                <tr v-for="tool in pagedTools" :key="tool.key">
                  <td>{{ tool.name }}</td>
                  <td>{{ departmentName(tool.departmentKey) }}</td>
                  <td><span class="status-badge" :class="tool.status === 'normal' ? 'success' : 'warning'">{{ toolStatusLabel(tool.status) }}</span></td>
                  <td><span class="status-badge" :class="tool.callbackEnabled ? 'success' : 'muted'">{{ tool.callbackEnabled ? '支持回传' : '不支持回传' }}</span></td>
                  <td v-if="canWriteAdminSection(currentUser.permissions, 'tools')" class="table-actions">
                    <button class="icon-btn" type="button" @click="changeToolStatus(tool, tool.status === 'normal' ? 'maintenance' : 'normal')">
                      {{ tool.status === 'normal' ? '设为维护' : '设为正常' }}
                    </button>
                  </td>
                </tr>
                </template>
                <tr v-if="!adminPages.tools.loading && pagedTools.length === 0"><td :colspan="canWriteAdminSection(currentUser.permissions, 'tools') ? 5 : 4" class="table-empty">暂无工具配置</td></tr>
              </tbody>
            </table>
            <AdminPagination :page="adminPages.tools" @change="changeAdminPage('tools', $event)" />
          </div>
        </div>
        <div v-if="adminSection === 'models'" class="table-card admin-panel" id="model-configs">
          <div class="panel-header">
            <div><h3>模型/API 配置</h3><span>API Key 仅脱敏展示，更新时后端保存新值</span></div>
          </div>
          <table>
            <thead><tr><th>名称</th><th>类型</th><th>模型</th><th>中转地址</th><th>Key</th><th>超时</th><th>重试</th><th>状态</th><th v-if="canWriteAdminSection(currentUser.permissions, 'models')">操作</th></tr></thead>
            <tbody>
              <tr v-if="adminPages.models.loading"><td :colspan="canWriteAdminSection(currentUser.permissions, 'models') ? 9 : 8" class="table-empty table-loading">加载中…</td></tr>
              <template v-if="!adminPages.models.loading">
              <tr v-for="config in pagedModels" :key="config.id">
                <td>{{ config.name }}</td><td>{{ config.modelType }}</td><td>{{ config.modelName }}</td>
                <td>{{ config.apiBaseUrl }}</td><td>{{ config.apiKeyMasked }}</td>
                <td>{{ config.timeoutSeconds }}s</td><td>{{ config.retryCount }}</td><td><span class="status-badge" :class="config.enabled ? 'success' : 'muted'">{{ config.enabled ? '已启用' : '已停用' }}</span></td>
                <td v-if="canWriteAdminSection(currentUser.permissions, 'models')" class="table-actions">
                  <button class="icon-btn" type="button" :disabled="modelConfigSaving" @click="openModelConfigEditor(config)">编辑</button>
                  <button class="icon-btn" type="button" @click="toggleModelConfig(config)">{{ config.enabled ? '停用' : '启用' }}</button>
                </td>
              </tr>
              </template>
              <tr v-if="!adminPages.models.loading && pagedModels.length === 0"><td :colspan="canWriteAdminSection(currentUser.permissions, 'models') ? 9 : 8" class="table-empty">暂无模型配置</td></tr>
            </tbody>
          </table>
          <AdminPagination :page="adminPages.models" @change="changeAdminPage('models', $event)" />
        </div>
        <div v-if="adminSection === 'upstreamAccounts'" class="table-card admin-panel upstream-account-panel">
          <div class="panel-header upstream-account-header">
            <div>
              <h3>中转订阅账号配置与实时健康度监控</h3>
              <span>统一管理 OpenAI 与 Gemini 的订阅和 API 账号</span>
            </div>
            <div class="panel-actions">
              <button v-if="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts')" class="btn-secondary" type="button" :disabled="!upstreamConfiguration.configured || upstreamSnapshotBusy" @click="refreshAllUpstreamSnapshots">{{ upstreamSnapshotBusy ? '刷新中…' : '立即刷新配额快照' }}</button>
              <button v-if="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts')" class="btn-primary" type="button" :disabled="!upstreamConfiguration.configured" @click="openUpstreamAccountDialog">添加上游 AI 账号</button>
            </div>
          </div>
          <div class="upstream-prototype-metrics">
            <article><span><ShieldCheck :size="17" />正常调度账号</span><strong>{{ upstreamSummary?.healthy ?? '—' }} <small>/ {{ upstreamSummary?.total ?? '—' }}</small></strong><p>健康率 {{ upstreamSummary?.total ? `${(upstreamSummary.healthy/upstreamSummary.total*100).toFixed(1)}%` : '—' }}</p></article>
            <article><span><TriangleAlert :size="17" />冷却 / 异常账号</span><strong>{{ upstreamSummary?.cooling ?? '—' }} <small>/ {{ upstreamSummary?.abnormal ?? '—' }}</small></strong><p>冷却账号 / 异常账号</p></article>
            <article><span><Gauge :size="17" />全局实时并发负载</span><strong>{{ upstreamSummary?.current ?? '—' }} <small>/ {{ upstreamSummary?.capacity ?? '—' }}</small></strong><p>排队 — · 峰值 —</p></article>
            <article><span><BatteryMedium :size="17" />正常账号 5h 平均剩余</span><strong>{{ windowAverageValue(upstreamSummary?.window5h) }}</strong><p>{{ windowAverageNote(upstreamSummary?.window5h) }}</p></article>
            <article><span><CalendarClock :size="17" />正常账号 7Day 平均剩余</span><strong>{{ windowAverageValue(upstreamSummary?.window7d) }}</strong><p>{{ windowAverageNote(upstreamSummary?.window7d) }}</p></article>
          </div>
          <div class="filter-bar upstream-account-filters">
            <input v-model="upstreamFilters.search" placeholder="搜索订阅账号名称、邮箱或备注" aria-label="搜索账号" @keyup.enter="reloadUpstreamAccounts" />
            <UiSelect v-model="upstreamFilters.platform" :options="[{label:'全部平台',value:''},...upstreamPlatformFilterOptions]" placeholder="全部平台" @update:model-value="reloadUpstreamAccounts" />
            <UiSelect v-model="upstreamFilters.status" :options="upstreamStatusFilterOptions" placeholder="全部状态" @update:model-value="reloadUpstreamAccounts" />
            <button class="btn-secondary" type="button" @click="reloadUpstreamAccounts">查询</button>
          </div>
          <div class="upstream-account-table-scroll"><table>
            <thead><tr><th>订阅账号 / 平台</th><th>调度优先级</th><th>健康状态</th><th>负载率</th><th>5h 剩余</th><th>7Day 剩余</th><th>代理</th><th>最近使用</th><th v-if="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts')">操作</th></tr></thead>
            <tbody>
              <tr v-if="adminPages.upstreamAccounts.loading"><td :colspan="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts') ? 9 : 8" class="table-empty table-loading">加载中…</td></tr>
              <template v-if="!adminPages.upstreamAccounts.loading">
                <tr v-for="account in pagedUpstreamAccounts" :key="account.id">
                  <td><span class="upstream-account-identity"><span class="upstream-account-avatar"><Sparkles v-if="account.platform==='gemini'" :size="17" /><Zap v-else :size="17" /></span><span><strong>{{ account.name }}</strong><small>{{ upstreamPlatformLabel(account.platform) }} · {{ account.type==='apikey'?'API Key':'订阅' }} · #{{ account.id }}</small></span></span></td>
                  <td><span class="relay-badge">{{ account.priority == null ? '—' : `P${account.priority}` }}</span></td>
                  <td>
                    <span class="status-badge" :class="upstreamStatusTone(account)">{{ upstreamStatusLabel(account) }}</span>
                    <small v-if="account.errorMessage" class="upstream-error">{{ account.errorMessage }}</small>
                  </td>
                  <td class="upstream-meter-cell"><strong>{{ upstreamLoadRate(account) }}</strong><small>{{ account.currentConcurrency ?? '—' }} / {{ account.concurrency }} 并发</small><progress :value="account.concurrency>0 ? Math.min(100,(account.currentConcurrency || 0)/account.concurrency*100) : 0" max="100" aria-label="账号并发负载" /></td>
                  <td class="upstream-meter-cell"><strong>{{ supportsWindow(account)?upstreamRemaining(account.usage?.codex5hUsedPercent):'不适用' }}</strong><progress v-if="supportsWindow(account) && account.usage?.codex5hUsedPercent!=null" :value="Math.max(0,100-Number(account.usage.codex5hUsedPercent))" max="100" aria-label="5小时剩余额度" /><small v-if="account.usage?.codex5hResetAt">重置 {{ formatTime(account.usage.codex5hResetAt) }}</small></td>
                  <td class="upstream-meter-cell"><strong>{{ supportsWindow(account)?upstreamRemaining(account.usage?.codex7dUsedPercent):'不适用' }}</strong><progress v-if="supportsWindow(account) && account.usage?.codex7dUsedPercent!=null" :value="Math.max(0,100-Number(account.usage.codex7dUsedPercent))" max="100" aria-label="7天剩余额度" /><small v-if="account.usage?.codex7dResetAt">重置 {{ formatTime(account.usage.codex7dResetAt) }}</small></td>
                  <td>{{ account.proxy?.name || account.proxyId || '直连' }}</td>
                  <td>{{ formatTime(account.lastUsedAt) }}</td>
                  <td v-if="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts')" class="table-actions upstream-row-actions">
                    <button class="icon-btn" type="button" aria-label="编辑账号" title="编辑账号" :disabled="upstreamAccountBusyId === account.id" @click="upstreamEditingAccount=account"><Pencil :size="16" /></button>
                    <button class="icon-btn" type="button" aria-label="刷新窗口" title="刷新窗口" :disabled="upstreamAccountBusyId === account.id" v-if="supportsWindow(account)" @click="refreshUpstreamWindow(account)"><RefreshCw :size="16" /></button>
                    <button v-if="account.status === 'error' || account.schedulable === false" class="icon-btn" type="button" aria-label="恢复异常" title="恢复异常" :disabled="upstreamAccountBusyId === account.id" @click="recoverManagedUpstreamAccount(account)"><CircleCheck :size="16" /></button>
                    <button class="icon-btn" type="button" aria-label="测试账号" title="测试账号" :disabled="upstreamAccountBusyId === account.id" @click="testManagedUpstreamAccount(account)"><FlaskConical :size="16" /></button>
                    <button class="icon-btn" type="button" aria-label="刷新凭据" title="刷新凭据" :disabled="upstreamAccountBusyId === account.id || account.type !== 'oauth'" v-if="account.type==='oauth'" @click="refreshManagedUpstreamAccount(account)"><RotateCw :size="16" /></button>
                    <button class="icon-btn" type="button" :aria-label="account.status === 'active' ? '停用账号' : '启用账号'" :title="account.status === 'active' ? '停用账号' : '启用账号'" :disabled="upstreamAccountBusyId === account.id" @click="toggleManagedUpstreamAccount(account)"><Pause v-if="account.status==='active'" :size="16" /><Play v-else :size="16" /></button>
                    <button class="icon-btn danger" type="button" aria-label="删除账号" title="删除账号" :disabled="upstreamAccountBusyId === account.id" @click="deleteManagedUpstreamAccount(account)"><Trash2 :size="16" /></button>
                  </td>
                </tr>
              </template>
              <tr v-if="!adminPages.upstreamAccounts.loading && pagedUpstreamAccounts.length === 0"><td :colspan="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts') ? 9 : 8" class="table-empty">暂无上游账号</td></tr>
            </tbody>
          </table></div>
          <AdminPagination :page="adminPages.upstreamAccounts" @change="changeAdminPage('upstreamAccounts', $event)" />
        </div>
        <div v-if="adminSection === 'brandPages'" class="table-card admin-panel">
          <div class="panel-header"><div><h3>品牌 HTML 页面</h3><span>同一组织部门下，一个品牌保留一个最新 HTML 页面</span></div><button v-if="canWriteAdminSection(currentUser.permissions, 'brandPages')" class="btn-primary" type="button" @click="openBrandPageUpload">上传 HTML</button></div>
          <table><thead><tr><th>品牌</th><th>归属部门</th><th>查看权限</th><th>文件名</th><th>上传人</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
            <tr v-if="adminPages.brandPages.loading"><td colspan="7" class="table-empty table-loading">加载中…</td></tr>
            <tr v-for="page in pagedBrandPages" :key="page.id"><td><strong>{{ page.brandName }}</strong></td><td>{{ page.departmentId ? departmentName(page.departmentId) : '未关联' }}</td><td>{{ brandPageViewerSummary(page) }}</td><td>{{ page.fileName }}</td><td>{{ page.createdBy }}</td><td>{{ formatTime(page.updatedAt) }}</td><td class="table-actions"><button v-if="canWriteAdminSection(currentUser.permissions, 'brandPages')" class="icon-btn" type="button" @click="openBrandPageEditor(page)">编辑</button><button class="icon-btn" type="button" @click="previewBrandPage(page)">预览</button><button v-if="canWriteAdminSection(currentUser.permissions, 'brandPages')" class="icon-btn danger" type="button" @click="removeBrandPage(page)">删除</button></td></tr>
            <tr v-if="!adminPages.brandPages.loading && !pagedBrandPages.length"><td colspan="7" class="table-empty">暂无品牌 HTML 页面</td></tr>
          </tbody></table><AdminPagination :page="adminPages.brandPages" @change="changeAdminPage('brandPages', $event)" />
        </div>
        <AiUsageRecords v-if="adminSection === 'aiLogs' && currentUser.superAdmin" :departments="organizationDepartments" />
        <div v-if="adminSection === 'operationLogs'" class="table-card admin-panel">
          <div class="panel-header">
            <div><h3>后台操作日志</h3><span>用户、工具、模型、项目等关键变更留痕</span></div>
          </div>
          <table>
            <thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>摘要</th><th>详情</th></tr></thead>
            <tbody>
              <tr v-if="adminPages.operationLogs.loading"><td colspan="5" class="table-empty table-loading">加载中…</td></tr>
              <template v-if="!adminPages.operationLogs.loading">
              <tr v-for="log in pagedOperationLogs" :key="log.id">
                <td>{{ formatTime(log.createdAt) }}</td><td>{{ log.operator }}</td><td>{{ log.action }}</td><td>{{ log.summary }}</td>
                <td><small>{{ JSON.stringify(log.detail) }}</small></td>
              </tr>
              </template>
              <tr v-if="!adminPages.operationLogs.loading && pagedOperationLogs.length === 0"><td colspan="5" class="table-empty">暂无操作日志</td></tr>
            </tbody>
          </table>
          <AdminPagination :page="adminPages.operationLogs" @change="changeAdminPage('operationLogs', $event)" />
        </div>
      </section>

      <section v-if="isToolEntry">
        <div class="tool-run-shell">
          <div class="tool-run-head">
            <div>
              <small>工具执行容器</small>
              <h2>{{ currentTool?.name || toolRouteKey }}</h2>
              <p>{{ currentTool?.desc || '该工具已通过平台创建运行记录，请按接入协议完成分析并回传结果。' }}</p>
            </div>
            <button class="btn-secondary" type="button" @click="navigateEntry('workbench')">返回工作台</button>
          </div>
          <div class="tool-run-grid">
            <div class="tool-run-panel">
              <h3>运行上下文</h3>
              <dl>
                <dt>runId</dt><dd>{{ toolRouteQuery.runId || '-' }}</dd>
                <dt>projectId</dt><dd>{{ toolRouteQuery.projectId || '-' }}</dd>
                <dt>userId</dt><dd>{{ toolRouteQuery.userId || currentUser.id || '-' }}</dd>
                <dt>toolKey</dt><dd>{{ toolRouteKey }}</dd>
              </dl>
            </div>
            <div class="tool-run-panel">
              <h3>接入状态</h3>
              <p>当前页面是平台统一工具执行入口。正式工具应使用 SDK 调用 `/api/tool-runs/{runId}/finish` 和 `/api/ai/*`，结果会自动沉淀到项目任务流程。</p>
              <div v-if="toolRouteQuery.runId" class="check-result passed">运行记录已创建，可以开始执行工具。</div>
              <div v-else class="check-result">缺少运行上下文，请从工作台打开工具。</div>
            </div>
          </div>
        </div>
      </section>
    </main>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'upstreamAccounts')"
      :open="upstreamAccountDialogVisible"
      size="upstream"
      :title="upstreamOAuthStep === 'authorize' ? '完成账号授权' : '添加上游 AI 账号（算力节点）'"
      :description="upstreamOAuthStep === 'authorize' ? '在新窗口完成登录后，将浏览器最终跳转地址完整粘贴回来。' : ''"
      :close-disabled="upstreamAccountSaving"
      @close="closeUpstreamAccountDialog"
    >
      <div class="admin-dialog-form upstream-account-form">
        <template v-if="upstreamOAuthStep === 'setup'">
          <UpstreamAccountFields v-model="upstreamAccountForm" :proxies="upstreamProxyOptions" :disabled="upstreamAccountSaving" />
        </template>
        <template v-else>
          <div class="upstream-oauth-guide">
            <b>1. 已为 {{ upstreamPlatformLabel(upstreamAccountForm.platform) }} 生成授权链接</b>
            <p>窗口空白或无法打开时，可复制链接到外部 Chrome 完成授权，再把最终跳转地址粘贴回本页。</p>
            <label class="drawer-field">授权链接<input ref="upstreamAuthUrlInput" :value="upstreamAccountForm.authUrl" readonly @focus="$event.target.select()" /></label>
            <button class="btn-secondary" type="button" :disabled="!upstreamAccountForm.authUrl" @click="copyUpstreamAuthorizationLink">复制授权链接</button>
            <button class="btn-secondary" type="button" @click="openUpstreamAuthorizationWindow">打开授权页面</button>
          </div>
          <label class="drawer-field">授权完成后的跳转地址<input v-model="upstreamAccountForm.authorizationResult" :disabled="upstreamAccountSaving" placeholder="粘贴完整 URL；也可只填授权码" /></label>
          <label v-if="!upstreamAccountForm.authorizationResult.startsWith('http')" class="drawer-field">State<input v-model="upstreamAccountForm.state" :disabled="upstreamAccountSaving" placeholder="只填授权码时需要" /></label>
        </template>
        <div v-if="upstreamAccountMessage" class="check-result">{{ upstreamAccountMessage }}</div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" :disabled="upstreamAccountSaving" @click="closeUpstreamAccountDialog">取消</button>
        <button v-if="upstreamOAuthStep === 'authorize'" class="btn-secondary" type="button" :disabled="upstreamAccountSaving" @click="upstreamOAuthStep = 'setup'">返回修改</button>
        <button class="btn-primary" type="button" :disabled="upstreamAccountSaving" @click="submitUpstreamAccount">{{ upstreamAccountSaving ? '处理中…' : upstreamAccountForm.type==='apikey' || upstreamOAuthStep==='authorize' ? '保存并同步至调度池' : '生成订阅授权链接' }}</button>
      </template>
    </AppDialog>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'brandPages')"
      :open="brandPageUploadVisible" :title="editingBrandPageId ? '编辑品牌 HTML' : '上传品牌 HTML'" :description="editingBrandPageId ? '可修改归属部门、品牌和查看权限；不选择新文件则保留原 HTML。' : '同一部门和品牌再次上传将替换旧页面；查看权限支持多选部门与员工。'" :close-disabled="brandPageUploading" @close="closeBrandPageUpload">
      <div class="admin-dialog-form brand-page-dialog-form">
        <label class="drawer-field">报告归属部门<ElTreeSelect v-model="brandPageForm.departmentId" :data="brandPageUploadDepartmentTreeData" node-key="id" check-strictly default-expand-all placeholder="请选择报告归属部门" :render-after-expand="false" :disabled="brandPageUploading" /></label>
        <label class="drawer-field">品牌名称<input v-model="brandPageForm.brandName" :disabled="brandPageUploading" placeholder="例如：川井" /></label>
        <label class="drawer-field">HTML 文件{{ editingBrandPageId ? '（可选）' : '' }}<input :key="brandPageFileInputKey" type="file" accept=".html,text/html" :disabled="brandPageUploading" @change="selectBrandPageFile" /></label>
        <div class="drawer-field brand-page-permission-field" role="group" aria-labelledby="brand-page-permission-title"><span id="brand-page-permission-title">查看权限（部门或员工，至少选择一项）</span>
          <ElInput v-model="brandPagePermissionSearch" aria-label="搜索可查看的部门或员工" clearable placeholder="搜索部门或员工" :disabled="brandPageUploading" @input="filterBrandPagePermissionTree" />
          <ElTree :key="brandPagePermissionTreeKey" ref="brandPagePermissionTreeRef" class="brand-page-permission-tree user-department-tree" :data="brandPagePermissionTreeData" node-key="id" show-checkbox check-strictly default-expand-all :default-checked-keys="brandPagePermissionCheckedKeys" :expand-on-click-node="false" :check-on-click-node="true" :filter-node-method="filterBrandPagePermissionNode" :props="{ disabled: (node) => node.disabled || brandPageUploading }" @check="syncBrandPagePermissionSelection" />
        </div>
        <div v-if="brandPageMessage" class="check-result">{{ brandPageMessage }}</div>
      </div>
      <template #footer><button class="btn-secondary" type="button" :disabled="brandPageUploading" @click="closeBrandPageUpload">取消</button><button class="btn-primary" type="button" :disabled="brandPageUploading" @click="saveBrandPage">{{ brandPageUploading ? '保存中…' : editingBrandPageId ? '保存修改' : '上传并保存' }}</button></template>
    </AppDialog>

    <AppDialog
      :open="projectPickerVisible"
      title="选择项目"
      description="工具运行记录、模型调用和生成结果必须挂载到项目下。"
      :close-on-backdrop="true"
      @close="closeProjectPicker"
    >
      <div class="admin-dialog-form">
      <label class="drawer-field">项目
        <UiSelect :key="projectPickerKey" v-model="projectPickerProjectId" :options="projectSelectOptions" placeholder="请选择项目" />
      </label>
      <div v-if="projectGateMessage" class="check-result">{{ projectGateMessage }}</div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" @click="goCreateProjectFromPicker">新建项目</button>
        <button class="btn-primary" type="button" :disabled="!projectPickerProjectId" @click="confirmProjectForTool">继续使用工具</button>
      </template>
    </AppDialog>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'models')"
      :open="modelConfigDialogVisible"
      title="编辑模型配置"
      :description="modelConfigForm.provider === 'sub2api' ? '使用员工专属的中转内部 Key。' : '留空新 Token 将保留当前 Token。'"
      :close-disabled="modelConfigSaving"
      :close-on-backdrop="true"
      @close="closeModelConfigEditor"
    >
      <div class="admin-dialog-form">
      <label class="drawer-field">配置 ID<input :value="modelConfigForm.id" disabled /></label>
      <label class="drawer-field">调用路径<UiSelect v-model="modelConfigForm.provider" :disabled="modelConfigSaving" :options="modelProviderOptions" /></label>
      <label class="drawer-field">模型类型<input :value="modelConfigForm.modelType" disabled /></label>
      <label class="drawer-field">模型名称<input v-model="modelConfigForm.modelName" :disabled="modelConfigSaving" /></label>
      <p v-if="modelConfigForm.provider === 'sub2api'" class="relay-caption">使用统一中转地址和员工内部 Key，受中转站个人与部门额度约束。请确认此模型在中转分组内可用。</p>
      <label v-else class="drawer-field">API 中转地址<input v-model="modelConfigForm.apiBaseUrl" :disabled="modelConfigSaving" placeholder="https://api.example.com" /></label>
      <label v-if="modelConfigForm.provider !== 'sub2api'" class="drawer-field">当前 Token<input :value="modelConfigForm.apiKeyMasked" disabled /></label>
      <label v-if="modelConfigForm.provider !== 'sub2api'" class="drawer-field">新 Token<input v-model="modelConfigForm.apiKey" type="password" autocomplete="off" spellcheck="false" :disabled="modelConfigSaving" placeholder="留空保留原 Token" /></label>
      <label class="drawer-field">超时（秒）<input v-model="modelConfigForm.timeoutSeconds" type="number" min="1" max="600" :disabled="modelConfigSaving" /></label>
      <label class="drawer-field">重试次数<input v-model="modelConfigForm.retryCount" type="number" min="0" max="5" :disabled="modelConfigSaving" /></label>
      <div v-if="modelConfigMessage" class="check-result">{{ modelConfigMessage }}</div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" :disabled="modelConfigSaving" @click="closeModelConfigEditor">取消</button>
        <button class="btn-primary" type="button" :disabled="modelConfigSaving" @click="submitModelConfigEdit">{{ modelConfigSaving ? '保存中...' : '保存修改' }}</button>
      </template>
    </AppDialog>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'users')"
      :open="userRoleDialogVisible"
      title="变更用户角色"
      description="请确认后再保存。角色变更会立即影响该用户后续接口权限。"
      :close-disabled="userRoleSaving"
      :close-on-backdrop="true"
      @close="closeUserRoleDialog"
    >
      <div class="admin-dialog-form">
      <label class="drawer-field">用户<input :value="selectedRoleUser?.account || ''" disabled /></label>
      <div class="drawer-field">角色</div>
      <div class="role-choice-list">
        <button v-for="role in roles" :key="role.code" type="button" class="role-choice" :disabled="userRoleSaving"
          :class="{ selected: userRoleForm.roleId === role.code }"
          @click="userRoleForm.roleId = role.code">
          <span class="choice-check">{{ userRoleForm.roleId === role.code ? '✓' : '' }}</span>
          <span>
            <b>{{ role.name }}</b>
            <small>{{ dataScopeLabel(role.dataScope) }}</small>
          </span>
        </button>
      </div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" :disabled="userRoleSaving" @click="closeUserRoleDialog">取消</button>
        <button class="btn-primary" type="button" :disabled="userRoleSaving" @click="saveUserRoleChange">{{ userRoleSaving ? '保存中...' : '确认变更' }}</button>
      </template>
    </AppDialog>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'users')"
      :open="userCreateDialogVisible"
      title="新增用户"
      description="创建后系统生成临时密码，用户首次登录需要修改密码。"
      size="large"
      :close-disabled="userCreating"
      :close-on-backdrop="true"
      @close="closeUserCreateDialog"
    >
      <div class="admin-dialog-form user-dialog-form">
      <label class="drawer-field">账号
        <input v-model="userCreateForm.account" :disabled="userCreating" placeholder="例如：market02" />
      </label>
      <label class="drawer-field">姓名
        <input v-model="userCreateForm.name" :disabled="userCreating" placeholder="例如：张三" />
      </label>
      <div class="drawer-field">角色</div>
      <div class="department-choice-list">
        <UiCheck v-for="role in enabledRoles" :key="role.code"
          :disabled="userCreating"
          :model-value="userCreateForm.roleIds.includes(role.code)"
          @update:model-value="toggleCreateUserRole(role.code)">{{ role.name }}</UiCheck>
      </div>
      <div class="drawer-field">所属部门</div>
      <ElTree class="user-department-tree user-create-department-tree"
        :data="userCreateDepartmentTreeData" node-key="id" show-checkbox check-strictly default-expand-all
        :props="{ disabled: () => userCreating }" :aria-disabled="userCreating"
        :default-checked-keys="userCreateForm.departmentKeys" :expand-on-click-node="false"
        empty-text="暂无可选部门" @check="updateCreateUserDepartments" />
      <label class="drawer-field">主部门
        <UiSelect v-model="userCreateForm.primaryDepartmentId" :options="createPrimaryDepartmentOptions" :disabled="userCreating" placeholder="请选择主部门" />
      </label>
      <div v-if="userCreateMessage" class="check-result" :class="{ passed: userCreateMessage.includes('已创建') }">{{ userCreateMessage }}</div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" :disabled="userCreating" @click="closeUserCreateDialog">取消</button>
        <button class="btn-primary" type="button" :disabled="userCreating" @click="submitUserCreate">{{ userCreating ? '创建中...' : '创建用户' }}</button>
      </template>
    </AppDialog>

    <AppDialog
      v-if="canWriteAdminSection(currentUser.permissions, 'users')"
      :open="userEditDialogVisible"
      title="编辑用户"
      description="调整用户姓名、角色和组织归属，账号不可修改。"
      size="large"
      :close-disabled="userEditing"
      :close-on-backdrop="true"
      @close="closeUserEditDialog"
    >
      <div class="admin-dialog-form user-dialog-form">
      <label class="drawer-field">账号<input :value="userEditForm.account" disabled /></label>
      <label class="drawer-field">姓名<input v-model="userEditForm.name" :disabled="userEditing" /></label>
      <div class="drawer-field">角色</div>
      <div class="department-choice-list">
        <UiCheck v-for="role in enabledRoles" :key="role.code"
          :disabled="userEditing"
          :model-value="userEditForm.roleIds.includes(role.code)"
          @update:model-value="toggleUserRole(role.code)">{{ role.name }}</UiCheck>
      </div>
      <div class="drawer-field">所属部门</div>
      <ElTree class="user-department-tree user-edit-department-tree"
        :data="userEditDepartmentTreeData" node-key="id" show-checkbox check-strictly default-expand-all
        :props="{ disabled: () => userEditing }" :aria-disabled="userEditing"
        :default-checked-keys="userEditForm.departmentKeys" :expand-on-click-node="false"
        empty-text="暂无可选部门" @check="updateEditUserDepartments" />
      <label class="drawer-field">主部门
        <UiSelect v-model="userEditForm.primaryDepartmentId" :options="editPrimaryDepartmentOptions" :disabled="userEditing" placeholder="请选择主部门" />
      </label>
      <div v-if="userEditMessage" class="check-result">{{ userEditMessage }}</div>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" :disabled="userEditing" @click="closeUserEditDialog">取消</button>
        <button class="btn-primary" type="button" :disabled="userEditing" @click="submitUserEdit">{{ userEditing ? '保存中...' : '保存修改' }}</button>
      </template>
    </AppDialog>

    <AppDialog
      :open="credentialDialog.visible"
      :title="credentialDialog.title"
      :description="credentialDialog.description"
      :close-on-backdrop="false"
      @close="closeCredentialDialog"
    >
      <div class="admin-dialog-form credential-dialog">
      <label class="drawer-field">账号<input :value="credentialDialog.account" readonly /></label>
      <label class="drawer-field">临时密码<input :value="credentialDialog.temporaryPassword" readonly /></label>
      </div>
      <template #footer>
        <button class="btn-secondary" type="button" @click="copyTemporaryPassword">复制临时密码</button>
        <button class="btn-primary" type="button" @click="closeCredentialDialog">我知道了</button>
      </template>
    </AppDialog>

    <AppDialog
      :open="clientKeyDialog.visible"
      :title="`${clientKeyDialog.account} · 客户端 Key`"
      description="由管理员为用户创建、轮换或吊销中转客户端 Key。"
      :close-disabled="clientKeyDialog.busy"
      :close-on-backdrop="false"
      @close="closeUserClientKeyDialog"
    >
      <div class="admin-dialog-form">
        <p v-if="clientKeyDialog.error" class="relay-error" role="alert">{{ clientKeyDialog.error }}</p>
        <p v-if="clientKeyDialog.loading">正在读取 Key 状态…</p>
        <template v-else-if="clientKeyDialog.status">
          <p>当前状态：{{ clientKeyDialog.status.clientKeyStatus === 'active' ? '已生成' : '未生成' }}</p>
          <p v-if="!clientKeyDialog.status.userEnabled" class="relay-warning">用户已禁用，不能生成新 Key。</p>
          <p v-else-if="!clientKeyDialog.status.identityReady" class="relay-warning">中转身份尚未就绪，请先完成用户中转配置与同步。</p>
          <template v-if="clientKeyDialog.key">
            <p>新 Key 仅展示一次。请复制后通过安全渠道交给该用户，关闭后无法再次查看。</p>
            <label class="drawer-field">新客户端 Key<input class="relay-key-value" :value="clientKeyDialog.key" readonly aria-label="新客户端 Key，仅展示一次" /></label>
            <button class="btn-secondary" type="button" @click="copyUserClientKey">复制 Key</button>
          </template>
          <div class="relay-client-endpoints"><p>客户端填写对应的 Base URL 和管理员交付的 Key。</p><label v-for="endpoint in clientKeyEndpoints" :key="endpoint.key"><span>{{ endpoint.label }} Base URL</span><span class="relay-client-endpoint-row"><input :value="endpoint.url" readonly :aria-label="`${endpoint.label} Base URL`" /><button type="button" class="btn-secondary" @click="copyClientKeyEndpoint(endpoint)">复制链接</button></span></label><span v-if="clientKeyDialog.copyMessage" class="relay-client-copy-status" role="status">{{ clientKeyDialog.copyMessage }}</span></div>
        </template>
      </div>
      <template #footer>
        <button v-if="clientKeyDialog.status?.clientKeyStatus === 'active' && !clientKeyDialog.key" class="btn-secondary" type="button" :disabled="clientKeyDialog.busy || clientKeyDialog.loading" @click="revokeUserClientKey">吊销 Key</button>
        <button v-if="!clientKeyDialog.key" class="btn-primary" type="button" :disabled="clientKeyDialog.busy || clientKeyDialog.loading || !clientKeyDialog.status?.userEnabled || !clientKeyDialog.status?.identityReady" @click="rotateUserClientKey">{{ clientKeyDialog.status?.clientKeyStatus === 'active' ? '轮换 Key' : '生成 Key' }}</button>
        <button class="btn-secondary" type="button" :disabled="clientKeyDialog.busy" @click="closeUserClientKeyDialog">关闭</button>
      </template>
    </AppDialog>

    <DetailDrawer :open="Boolean(selectedRun)" title="运行结果详情" wide stacked @close="selectedRun = null">
      <div v-if="selectedRun" class="run-detail-body">
        <section class="result-card detail-section">
          <h4><span>i</span>基本信息</h4>
          <div class="detail-grid">
            <p><b>所属工具：</b>{{ selectedRun.tool }}</p>
            <p><b>员工：</b>{{ selectedRun.user }}</p>
            <p><b>开始时间：</b>{{ selectedRun.time }}</p>
            <p><b>耗时：</b>{{ selectedRun.duration }}</p>
            <p><b>模型：</b>{{ selectedRun.model }}</p>
            <p><b>所属项目：</b>{{ selectedRun.project || '-' }}</p>
          </div>
        </section>

        <section class="result-card detail-section">
          <h4><span>≡</span>提示词输入（Prompt）</h4>
          <pre class="prompt-box">{{ selectedRun.input }}</pre>
        </section>

        <section class="result-card detail-section">
          <h4><span>▧</span>产出附件（{{ selectedRunAttachments.length }}）</h4>
          <div v-if="selectedRunAttachments.length" class="attachment-grid">
            <a v-for="attachment in selectedRunAttachments" :key="attachment.key" :href="attachment.url" target="_blank" rel="noopener" class="attachment-item">
              <img v-if="attachment.kind === 'image'" :src="attachment.url" :alt="attachment.name" />
              <div v-else class="file-attachment">{{ attachment.name }}</div>
            </a>
          </div>
          <p v-else class="attachment-empty">{{ runDetailLoading ? '正在加载运行详情…' : '该运行记录未上报产出附件' }}</p>
          <button v-if="selectedRunAttachments.length" class="btn-secondary attachment-download" type="button" @click="openAllAttachments">全部打开</button>
        </section>

        <section class="result-card detail-section">
          <h4><span>{}</span>完整 JSON 返回</h4>
          <pre class="json-box">{{ runDetailLoading ? '正在加载运行详情…' : selectedRunJson }}</pre>
        </section>

      </div>
      <template #footer>
        <button class="btn-secondary" type="button" @click="selectedRun = null">关闭</button>
      </template>
    </DetailDrawer>

    <DetailDrawer :open="Boolean(selectedBoardProject)" title="项目详情" wide extra-wide @close="selectedBoardProject = null">
      <div v-if="selectedBoardProject" class="project-drawer-content">
        <div class="project-overview-card">
          <div class="pi-image"><img v-if="selectedBoardProject.coverUrl" :src="workbenchFileUrl(selectedBoardProject.coverUrl)" :alt="selectedBoardProject.productName || selectedBoardProject.name" /><span v-else>▰</span></div>
          <div class="po-content">
            <div class="po-title-row">
              <div><h1>{{ selectedBoardProject.name }}</h1><p>商品名称：{{ selectedBoardProject.productName || '未填写商品信息' }}</p></div>
              <div class="project-title-actions"><button v-if="selectedBoardProject.canManage" class="btn-secondary" type="button" @click="openProjectEditor(selectedBoardProject)">编辑项目</button><button v-if="selectedBoardProject.canManage" class="btn-secondary danger" type="button" :disabled="projectDeletingId === selectedBoardProject.id" @click="removeManagedProject(selectedBoardProject)">{{ projectDeletingId === selectedBoardProject.id ? '删除中...' : '删除项目' }}</button><span class="status-badge running">{{ selectedBoardProject.status }}</span></div>
            </div>
            <div class="po-meta-grid">
              <span>所属部门：{{ projectDepartmentName(selectedBoardProject) }}</span><span>所属品牌：{{ selectedBoardProject.brand || '-' }}</span><span>负责人：{{ selectedBoardProject.owner || '-' }}</span>
              <span>类目：{{ selectedBoardProject.category || '-' }}</span><span>售价：{{ selectedBoardProject.salePrice ? `¥${selectedBoardProject.salePrice}` : '-' }}</span><span>立项时间：{{ selectedBoardProject.establishedDate || '-' }}</span>
              <span class="wide">项目成员：{{ (selectedBoardProject.members || []).join('、') || '-' }}</span>
              <span class="wide">商品卖点：{{ selectedBoardProject.sellingPoints || selectedBoardProject.summary || '-' }}</span>
            </div>
          </div>
        </div>
        <div class="dashboard-stats-card card-premium">
          <div class="stats-header"><h3>项目数据统计</h3></div>
          <div class="project-stat-grid">
            <div class="stat-block"><div class="stat-title">当前任务进度</div><div class="stat-val">{{ projectProgressFor(selectedBoardProject.id) }}%</div></div>
            <div class="stat-block"><div class="stat-title">累计产出物</div><div class="stat-val">{{ resultCountFor(selectedBoardProject.id) }}</div></div>
            <div class="stat-block"><div class="stat-title">资源消耗</div><div class="stat-val">{{ formatResourceUsage(selectedBoardProject.tokenUsage, selectedBoardProject.creditUsage, '0') }}</div></div>
            <div class="stat-block"><div class="stat-title">图片生成数</div><div class="stat-val">{{ selectedBoardProject.imageCount || 0 }}</div></div>
          </div>
        </div>
        <div class="project-results-section card-premium">
          <div class="stats-header"><h3>生成记录</h3><span>{{ projectGenerationRecords(selectedBoardProject.id).length }} 条已关联记录</span></div>
          <div v-if="projectGenerationRecords(selectedBoardProject.id).length" class="project-results-table-wrap">
            <table class="project-results-table">
              <thead><tr><th>生成时间</th><th>工具</th><th>操作人</th><th>结果摘要</th><th>操作</th></tr></thead>
              <tbody><tr v-for="record in projectGenerationRecords(selectedBoardProject.id)" :key="record.id"><td>{{ record.run?.time || formatTime(record.createdAt) }}</td><td>{{ record.run?.tool || toolName(record.toolKey) }}</td><td>{{ record.run?.user || userName(record.submittedBy) }}</td><td class="project-result-summary">{{ record.outputSummary || '-' }}</td><td class="table-actions"><button v-if="record.run" class="icon-btn" type="button" @click="openRunDetail(record.run)">查看</button><button v-if="selectedBoardProject.canManage" class="icon-btn danger" type="button" :disabled="projectResultRemovingId === record.id" @click="removeProjectGenerationRecord(record, selectedBoardProject)">{{ projectResultRemovingId === record.id ? '移出中...' : '移出项目' }}</button></td></tr></tbody>
            </table>
          </div>
          <div v-else class="project-results-empty">暂无已关联的生成记录</div>
        </div>
        <div class="workflow-board-section card-premium">
          <div class="stats-header"><h3>任务</h3></div>
          <div class="workflow-lanes-container">
            <div v-for="lane in projectWorkflowLanesFor(selectedBoardProject)" :key="lane.key" class="workflow-lane" :class="{ active: lane.completed === lane.tasks.length && lane.tasks.length }">
              <div class="lane-header"><h4>{{ lane.name }}</h4><span class="stage-count">{{ lane.completed }}/{{ lane.tasks.length }} 任务已完成</span></div>
              <div v-if="lane.completed === lane.tasks.length && lane.tasks.length" class="lane-complete-tip">部门任务已完成</div>
              <div v-for="task in lane.tasks" :key="task.id" class="task-slot"><div class="node-card" :class="{ completed: task.done, empty: !task.done }">
                <div v-if="task.done" class="node-status-badge">✓</div><div class="node-tool-title"><span class="node-tool-icon">{{ task.tool.slice(0, 1) }}</span>{{ task.tool }}</div><div class="node-preview">{{ task.summary }}</div>
                <div class="node-footer"><div class="nf-info"><div class="node-author"><div class="small-avatar">{{ task.user.slice(0, 1) }}</div><span>{{ task.user }}</span></div><span class="nf-time">{{ task.time }}</span></div><button v-if="task.done" class="btn-outline-sm" type="button" @click="openRunDetail(runRecords.find((run) => run.id === task.runId))">查看</button><button v-else class="btn-run-tool" type="button" :disabled="task.toolStatus !== 'normal' || !selectedBoardProject.canRun" @click="openProjectTaskTool(task, selectedBoardProject)">{{ task.toolStatus !== 'normal' ? '未接入' : selectedBoardProject.canRun ? '去执行 →' : '仅项目成员可执行' }}</button></div>
              </div></div>
            </div>
          </div>
        </div>
      </div>
      <template #footer><button class="btn-secondary" type="button" @click="selectedBoardProject = null">关闭</button></template>
    </DetailDrawer>

    <UpstreamAccountTestDialog :account="upstreamTestingAccount" @close="upstreamTestingAccount=null" @tested="reloadUpstreamAccounts" />
    <UpstreamAccountEditor :account="upstreamEditingAccount" :proxies="upstreamProxyOptions" @close="upstreamEditingAccount=null" @saved="reloadUpstreamAccounts" />
    <AppDialog
      :open="confirmDialog.visible"
      :stacked="true"
      :title="confirmDialog.title"
      @close="resolveConfirm(false)"
    >
      <p>{{ confirmDialog.message }}</p>
      <template #footer>
        <button class="btn-secondary" type="button" @click="resolveConfirm(false)">{{ confirmDialog.cancelText }}</button>
        <button class="btn-primary danger" type="button" @click="resolveConfirm(true)">{{ confirmDialog.confirmText }}</button>
      </template>
    </AppDialog>

    <div v-if="notice.visible" class="app-toast" :class="notice.tone">
      <b>{{ notice.title }}</b>
      <span>{{ notice.message }}</span>
      <button class="app-toast-close" type="button" title="关闭通知" aria-label="关闭通知" @click="notice.visible = false">×</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElButton, ElInput, ElOption, ElSelect, ElTag, ElTree, ElTreeSelect } from 'element-plus'
import { RELAY_REPORT_PAGES, relayReportRoute, canViewRelayReports } from './relayNavigation.js'
import { Copy, Download, Eye, Globe2, Image as ImageIcon, ScanSearch, Sparkles, ShieldCheck, TriangleAlert, Gauge, BatteryMedium, CalendarClock, Zap, Pencil, RefreshCw, CircleCheck, FlaskConical, RotateCw, Pause, Play, Trash2 } from 'lucide-vue-next'
import { changePasswordApi, completeUpstreamAccountOAuth, createOrganizationDepartment, createProject, createRole, createToolSessionTicket, createUser, currentUserApi, deleteBrandPage, deleteOrganizationDepartment, deleteProject, deleteProjectCover, deleteRole, deleteUpstreamAccount, deleteUser, getBrandPageContent, getToolRun, hasAccessToken, loadAdminPage as loadAdminPageApi, loadBootstrap, loadUpstreamAccountConfiguration, loadUpstreamAccountProxies, loginApi, logoutApi, publicStatsApi, refreshUpstreamAccount, requestDeployment, resetPassword, resolveDetailPageEntry, searchBrandPages, startToolRun, startUpstreamAccountOAuth, unlinkProject, updateBrandPage, updateModelConfig, updateOrganizationDepartment, updateOrganizationDepartmentStatus, updateProject, updateProjectStatus, updateRole, updateRoleStatus, updateToolStatus, updateUpstreamAccountStatus, updateUser, updateUserRoles, updateUserStatus, uploadBrandPage, uploadProjectCover } from './api'
import { canReadAdminSection, canWriteAdminSection, hasAdminAccess, visibleAdminSections } from './adminPermissions'
import RelayWorkspace from './components/RelayWorkspace.vue'
import UpstreamAccountTestDialog from './components/UpstreamAccountTestDialog.vue'
import UpstreamAccountEditor from './components/UpstreamAccountEditor.vue'
import UpstreamAccountFields from './components/UpstreamAccountFields.vue'
import {validateUpstreamForm, supportsWindow} from './upstreamAccountForm.js'
import { windowAverageValue, windowAverageNote } from './upstreamWindowAverage.js'
import {getJson, postJson, deleteJson} from './api.js'
const modelProviderOptions=[{value:'sub2api',label:'中转站 · OpenAI / Google'},{value:'openai-compatible',label:'OpenAI 兼容直连'},{value:'doubao-openai-compatible',label:'豆包直连'},{value:'evolink',label:'Evolink 直连'},{value:'coze',label:'Coze 直连'}]
import { formatAiLogBilling, formatResourceUsage, formatResourceUsageMetrics } from './aiCost'
import { bindBrandPageAnchorNavigation } from './brandPagePreview'
import AdminPagination from './components/AdminPagination.vue'
import AppDialog from './components/AppDialog.vue'
import DetailDrawer from './components/DetailDrawer.vue'
import AiUsageRecords from './components/AiUsageRecords.vue'
import UiCheck from './components/UiCheck.vue'
import UiSelect from './components/UiSelect.vue'
import { activeProjectResults, countActiveProjectResults, filterProjects } from './projectView'
import { extractRunAttachments } from './runAttachments'
import { workbenchFileUrl } from './workbenchFileUrl'
import { useBrandPagePermissionTree } from './brandPagePermissionTree'
import { copyRunResultToClipboard, preloadRunImages } from './runClipboard'
import { copyText } from './clipboard'
import { buildRunDetailPayload, filterRunRecords, formatRunDuration, hasCopyableRunResult, hasDownloadableRunAttachment, paginateRecords, sortRunRecordsNewestFirst, splitRunTimestamp } from './runView'
import { openPromoImageWindow } from './promoImageLaunch'
import { openNewChatWindow } from './newChatLaunch'
import { revokeLoginSessionApi } from './api'
import { buildModelConfigUpdatePayload, emptyModelConfigForm, modelConfigFormFrom, validateModelConfigForm } from './modelConfigEditor'

// 已有登录令牌时直接渲染当前路由；后台校验失败后再回到登录页，避免刷新出现中间页。
const authed = ref(hasAccessToken())
const loading = ref(false)
const initialRouteState = initialRoute()
const routeMode = ref(initialRouteState.mode)
const currentPage = ref(initialRouteState.page)
const toolRouteKey = ref(initialRouteState.toolKey || '')
const toolRouteQuery = ref(initialRouteState.query || {})
const adminSection = ref(initialRouteState.adminSection || 'overview')
const activeDept = ref('ai-market')
const selectedRun = ref(null)
const runDetailLoading = ref(false)
const selectedBoardProject = ref(null)
const departments = ref([])
const roleTools = ref([])
const canAccessNewChat = ref(false)
const runRecords = ref([])
const projects = ref([])
const projectUsers = ref([])
const projectResults = ref([])
const aiLogs = ref([])
const users = ref([])
const runUsers = ref({})
const runProjects = ref({})
const runTokenUsage = ref({})
const runCreditUsage = ref({})
const runPrompts = ref({})
const projectDeletingId = ref('')
const projectResultRemovingId = ref('')
const roles = ref([])
const permissions = ref([])
const modelConfigs = ref([])
const modelConfigDialogVisible = ref(false)
const modelConfigSaving = ref(false)
const modelConfigMessage = ref('')
const modelConfigForm = ref(emptyModelConfigForm())
const upstreamConfiguration = ref({ configured: false, version: 'v0.2.5', platforms: ['openai', 'gemini'] })
const upstreamProxies = ref([])
const upstreamFilters = ref({ platform: '', status: '', search: '' })
const upstreamAccountDialogVisible = ref(false)
const upstreamAccountSaving = ref(false)
const upstreamAccountBusyId = ref(null)
const upstreamEditingAccount = ref(null)
const upstreamTestingAccount = ref(null)
const upstreamSummary = ref(null)
const upstreamSnapshotBusy = ref(false)
const upstreamAccountMessage = ref('')
const upstreamOAuthStep = ref('setup')
const upstreamAccountForm = ref(emptyUpstreamAccountForm())
const operationLogs = ref([])
const deploymentRequests = ref([])
const organizationDepartments = ref([])
const brandPageViewerUsers = ref([])
const brandPages = ref([])
const brandPageSearch = ref('')
const brandPageDepartmentFilter = ref('')
const brandPagesLoading = ref(false)
const selectedBrandPage = ref(null)
const brandPageHtml = ref('')
const brandPagePreviewRef = ref(null)
const brandPageControlsRef = ref(null)
const brandPageIframeRef = ref(null)
let cleanupBrandPagePreviewInteractions = () => {}
const brandPageFullscreen = ref(false)
const brandPageControlsExpanded = ref(false)
const brandPagePreviewReturn = ref(initialRouteState.brandPageReturn || 'workbench')
const brandPageRouteId = ref(initialRouteState.brandPageId || '')
const brandPageUploadVisible = ref(false)
const brandPageUploading = ref(false)
const brandPageMessage = ref('')
const editingBrandPageId = ref('')
const brandPageFileInputKey = ref(0)
const brandPagePermissionTreeRef = ref(null)
const brandPagePermissionTreeKey = ref(0)
const brandPagePermissionSearch = ref('')
const brandPageForm = ref({ departmentId: '', brandName: '', viewerDepartmentIds: [], viewerUserIds: [], file: null })
const stats = ref({})
const publicStats = ref({})
const projectTasks = ref([])
const currentUser = ref({})
const selectedProject = ref(projects.value[0])
const showProjectCreate = ref(false)
const projectCreating = ref(false)
const projectSelectedForTool = ref(false)
const projectPickerVisible = ref(false)
const projectPickerProjectId = ref('')
const projectPickerKey = ref(0)
const pendingTool = ref(null)
const projectGateMessage = ref('')
const projectCreateMessage = ref('')
const projectForm = ref(emptyProjectForm())
const projectCoverFile = ref(null)
const editingProjectId = ref('')
const projectRemoveCover = ref(false)
const loginForm = ref({ account: '', password: '' })
const passwordChangeRequired = ref(false)
const passwordChanging = ref(false)
const passwordChangeForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const rememberLogin = ref(true)
const showPassword = ref(false)
const errorMessage = ref('')
const dataSource = ref('等待登录后连接后端接口。')
const manifestCheck = ref(null)
const roleMessage = ref('')
const selectedRoleCode = ref('')
const roleForm = ref(emptyRoleForm())
const roleDialogVisible = ref(false)
const roleSaving = ref(false)
const userRoleDialogVisible = ref(false)
const userRoleSaving = ref(false)
const selectedRoleUser = ref(null)
const userRoleForm = ref({ roleId: '' })
const userCreateDialogVisible = ref(false)
const userCreating = ref(false)
const userCreateMessage = ref('')
const userCreateForm = ref(emptyUserCreateForm())
const userSearch = ref('')
const userEditDialogVisible = ref(false)
const userEditing = ref(false)
const userEditMessage = ref('')
const userEditForm = ref(emptyUserEditForm())
const adminPages = ref(Object.fromEntries(
  ['users', 'roles', 'tools', 'models', 'upstreamAccounts', 'brandPages', 'aiLogs', 'operationLogs', 'deployments']
    .map((key) => [key, { items: [], page: 1, pageSize: 10, total: 0, totalPages: 0, loading: false, loaded: false, requestId: 0 }])
))
const organizationSaving = ref(false)
const organizationMessage = ref('')
const organizationForm = ref(emptyOrganizationForm())
const organizationDialogVisible = ref(false)
const runFilters = ref({ tool: 'all', employee: 'all', department: '', status: 'all' })
const runSearchQuery = ref('')
const runPage = ref(1)
const runPageSize = 10
const projectDepartmentFilter = ref('')
const projectSearchQuery = ref('')
const projectBoardSearch = ref('')
const projectBoardDepartment = ref('')
const projectBoardBrand = ref('')
const projectBoardCreator = ref('')
const projectBoardPage = ref(1)
const projectBoardPageSize = 10
const notice = ref({ visible: false, title: '', message: '', tone: 'danger' })
const credentialDialog = ref({ visible: false, title: '', description: '', account: '', temporaryPassword: '' })
const emptyClientKeyDialog = () => ({ visible: false, userId: '', account: '', status: null, key: '', loading: false, busy: false, error: '', copyMessage: '' })
const clientKeyDialog = ref(emptyClientKeyDialog())
let clientKeyLoadGeneration = 0
const clientKeyEndpoints = [
  { key: 'openai', label: 'OpenAI 兼容', url: new URL('/relay/v1/', window.location.origin).href },
  { key: 'gemini', label: 'Gemini', url: new URL('/relay/v1beta/', window.location.origin).href }
]
const confirmDialog = ref({
  visible: false,
  title: '',
  message: '',
  confirmText: '确认',
  cancelText: '取消',
  resolver: null
})
const runStatusOptions = [
  { label: '全部状态', value: 'all' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' }
]
const upstreamPlatformFilterOptions = [
  { label: 'OpenAI', value: 'openai' },
  { label: 'Google Gemini', value: 'gemini' }
]
const upstreamStatusFilterOptions = [
  { label: '全部状态', value: '' },
  { label: '正常', value: 'active' },
  { label: '停用', value: 'inactive' },
  { label: '异常', value: 'error' }
]
const runToolIcons = {
  'market-intelligence': Globe2,
  'competitor-analysis': ScanSearch,
  'consumer-insight': ScanSearch,
  'promo-image': ImageIcon
}
const dataScopeOptions = [
  { label: '全平台数据', value: 'ALL' },
  { label: '部门数据', value: 'DEPARTMENT' },
  { label: '项目数据', value: 'PROJECT' },
  { label: '本人数据', value: 'SELF' }
]
const isWorkbenchEntry = computed(() => routeMode.value === 'workbench')
const isAdminEntry = computed(() => routeMode.value === 'admin')
const isToolEntry = computed(() => routeMode.value === 'tool')
const entryBrandName = computed(() => isAdminEntry.value ? '管理后台' : '一起互动AI工作台')
const visibleAdminNavItems = computed(() => visibleAdminSections(currentUser.value.permissions, currentUser.value.superAdmin))
const canAccessAdmin = computed(() => hasAdminAccess(currentUser.value.permissions))
const adminSectionTitle = computed(() => ({
  relayQuotas: '限额与并发配置',
  relayDashboard: 'Token 消耗看板',
  relayEmployees: '用户 Token 消耗',
  overview: '后台概览',
  deployments: '部署门禁',
  users: '用户管理',
  organization: '组织架构',
  roles: '角色权限',
  tools: '工具管理',
  models: '模型配置',
  upstreamAccounts: '上游账号',
  brandPages: '品牌 HTML',
  aiLogs: '使用记录',
  operationLogs: '操作日志'
})[adminSection.value] || '后台概览')
const adminSectionSubtitle = computed(() => ({
  overview: '工具接入、AI 网关、模型调用审计',
  deployments: '服务端强制校验，未通过不得发布到正式服务器',
  users: '账号、角色、状态和临时密码管理',
  organization: '组织树、部门启停和用户归属',
  roles: '角色、权限点、工具访问和数据范围配置',
  tools: '工具状态、部门归属和回传能力',
  models: '模型中转地址、Key、超时和启停配置',
  upstreamAccounts: 'OpenAI 与 Google 订阅账号的授权、健康检查和启停管理',
  brandPages: '品牌页面上传、替换与预览管理',
  aiLogs: 'Key 与工作台 AI 调用的输入、产出及用量',
  operationLogs: '用户、工具、模型、项目等关键变更留痕'
})[adminSection.value] || '')
const activeDepartment = computed(() => departments.value.find((dept) => dept.key === activeDept.value) || departments.value[0] || { key: '', name: '', tools: [] })
const flatTools = computed(() => departments.value.flatMap((dept) => dept.tools || []))
const runToolOptions = computed(() => [
  { label: '全部工具', value: 'all' },
  ...flatTools.value.map((tool) => ({ label: tool.name, value: tool.key }))
])
const runEmployeeOptions = computed(() => [
  { label: '全部员工', value: 'all' },
  ...Array.from(new Set(runRecords.value.map((record) => record.user).filter(Boolean)))
    .map((name) => ({ label: name, value: name }))
])
const filteredRunRecords = computed(() => filterRunRecords(runRecords.value, runSearchQuery.value, runFilters.value))
const pagedRunRecords = computed(() => paginateRecords(filteredRunRecords.value, runPage.value, runPageSize))
const runPagination = computed(() => ({
  page: runPage.value,
  pageSize: runPageSize,
  total: filteredRunRecords.value.length,
  totalPages: Math.ceil(filteredRunRecords.value.length / runPageSize),
  loading: false
}))
const filteredProjectBoard = computed(() => projects.value.filter((project) => {
  const keyword = projectBoardSearch.value.trim().toLocaleLowerCase()
  const matchesKeyword = !keyword || [project.name, project.productName].some((value) => String(value || '').toLocaleLowerCase().includes(keyword))
  const matchesDepartment = !projectBoardDepartment.value || projectDepartmentKey(project) === projectBoardDepartment.value
  const matchesBrand = !projectBoardBrand.value.trim() || String(project.brand || '').includes(projectBoardBrand.value.trim())
  const matchesCreator = !projectBoardCreator.value.trim() || String(project.owner || '').includes(projectBoardCreator.value.trim())
  return matchesKeyword && matchesDepartment && matchesBrand && matchesCreator
}))
const pagedProjectBoard = computed(() => paginateRecords(filteredProjectBoard.value, projectBoardPage.value, projectBoardPageSize))
const projectBoardPagination = computed(() => ({
  page: projectBoardPage.value,
  pageSize: projectBoardPageSize,
  total: filteredProjectBoard.value.length,
  totalPages: Math.ceil(filteredProjectBoard.value.length / projectBoardPageSize),
  loading: false
}))
const totalProjectResults = computed(() => projectResults.value.filter((result) => result.status === 'ACTIVE').length)
const totalProjectResources = computed(() => formatResourceUsage(
  projects.value.reduce((sum, project) => sum + parseToken(project.tokenUsage), 0),
  projects.value.reduce((sum, project) => sum + Number(project.creditUsage || 0), 0),
  '0'
))
const totalProjectImages = computed(() => projects.value.reduce((sum, project) => sum + Number(project.imageCount || 0), 0))
const overallProjectProgress = computed(() => {
  if (!projectTasks.value.length) return 0
  return Math.round(projectTasks.value.filter((task) => task.status === 'DONE').length * 100 / projectTasks.value.length)
})
const groupedPermissions = computed(() => {
  const groups = new Map()
  permissions.value.forEach((permission) => {
    const moduleName = permission.module || '其他权限'
    if (!groups.has(moduleName)) groups.set(moduleName, [])
    groups.get(moduleName).push(permission)
  })
  return Array.from(groups.entries()).map(([module, items]) => ({ module, items }))
})
const selectedRole = computed(() => roles.value.find((role) => role.code === selectedRoleCode.value) || null)
const pagedBrandPages = computed(() => adminPageItems('brandPages', []))
const currentTool = computed(() => flatTools.value.find((tool) => tool.key === toolRouteKey.value))
const selectedProjectTasks = computed(() => projectTasks.value.filter((task) => task.projectId === selectedProject.value?.id))
const filteredProjects = computed(() => filterProjects(
  projects.value,
  projectTasks.value,
  flatTools.value,
  projectSearchQuery.value,
  projectDepartmentFilter.value
))
const selectedProjectResultCount = computed(() => countActiveProjectResults(projectResults.value, selectedProject.value?.id))
const selectedProjectGenerationRecords = computed(() => projectGenerationRecords(selectedProject.value?.id))
const linkableProjects = computed(() => projects.value.filter((project) => project.canRun))
const activeProject = computed(() => {
  if (projectSelectedForTool.value && selectedProject.value?.canRun) return selectedProject.value
  return null
})
const resourceUsageLabel = computed(() => formatResourceUsage(
  stats.value.totalTokens,
  stats.value.totalCredits,
  '0'
))
const resourceUsageMetrics = computed(() => formatResourceUsageMetrics(
  stats.value.totalTokens,
  stats.value.totalCredits
))
const projectWorkflowLanes = computed(() => projectWorkflowLanesFor(selectedProject.value))
function projectWorkflowLanesFor(project) {
  const tasksForProject = projectTasks.value.filter((task) => task.projectId === project?.id)
  if (tasksForProject.length) {
    const grouped = new Map()
    tasksForProject.forEach((task) => {
      const tool = flatTools.value.find((item) => item.key === task.toolKey)
      const dept = departments.value.find((item) => item.key === tool?.departmentKey) || { key: 'project', name: '项目任务' }
      if (!grouped.has(dept.key)) {
        grouped.set(dept.key, { key: dept.key, name: dept.name, tasks: [] })
      }
      grouped.get(dept.key).tasks.push({
        id: task.id,
        departmentKey: dept.key,
        toolKey: task.toolKey,
        toolStatus: tool?.status || 'unopened',
        tool: task.title,
        runId: task.runId || '',
        done: task.status === 'DONE',
        summary: task.status === 'DONE' ? '已完成并沉淀到项目流程' : `当前状态：${taskStatusName(task.status)}`,
        user: userName(task.assigneeUserId) || '-',
        time: formatTime(task.completedAt || task.updatedAt)
      })
    })
    return Array.from(grouped.values()).map((lane) => ({
      ...lane,
      completed: lane.tasks.filter((task) => task.done).length
    }))
  }
  const visibleDepartments = departments.value.filter((dept) => !dept.reserved && (dept.tools || []).some((tool) => tool.status === 'normal')).slice(0, 3)
  return visibleDepartments.map((dept) => {
    const tasks = (dept.tools || [])
      .filter((tool) => tool.status === 'normal')
      .slice(0, 3)
      .map((tool) => {
        return {
          id: `${dept.key}-${tool.key}`,
          departmentKey: dept.key,
          toolKey: tool.key,
          toolStatus: tool.status,
          tool: tool.name,
          runId: '',
          done: false,
          summary: `待运行${tool.name}并沉淀项目结果`,
          user: '-',
          time: '-'
        }
      })
    return {
      key: dept.key,
      name: dept.name,
      completed: tasks.filter((task) => task.done).length,
      tasks
    }
  })
}
const projectProgress = computed(() => {
  const tasks = projectWorkflowLanes.value.flatMap((lane) => lane.tasks)
  if (!tasks.length) return 0
  return Math.round(tasks.filter((task) => task.done).length * 100 / tasks.length)
})
const selectedRunAttachments = computed(() => extractRunAttachments(selectedRun.value))
const selectedRunJson = computed(() => selectedRun.value ? JSON.stringify(buildRunDetailPayload(selectedRun.value), null, 2) : '{}')
const projectSelectOptions = computed(() => linkableProjects.value.map((project) => ({ label: project.name, value: project.id })))
const roleSelectOptions = computed(() => roles.value.filter((role) => role.enabled).map((role) => ({ label: role.name, value: role.code })))
const enabledRoles = computed(() => roles.value.filter((role) => role.enabled || userEditForm.value.roleIds.includes(role.code)))
const departmentSelectOptions = computed(() => organizationDepartments.value.filter((department) => department.enabled).map((department) => ({ label: department.name, value: department.id })))
const projectDepartmentTreeData = computed(() => buildOrganizationTree(enabledOrganizationDepartments.value))
const projectEditorDepartmentTreeData = computed(() => {
  const allScope = currentUser.value.dataScope === 'ALL'
  const assignedDepartmentIds = new Set(currentUser.value.departmentKeys || [])
  const departmentById = new Map(enabledOrganizationDepartments.value.map((department) => [department.id, department]))
  const includedDepartmentIds = new Set(allScope
    ? enabledOrganizationDepartments.value.map((department) => department.id)
    : assignedDepartmentIds)
  if (!allScope) {
    assignedDepartmentIds.forEach((departmentId) => {
      let parentId = departmentById.get(departmentId)?.parentId
      while (parentId && departmentById.has(parentId) && !includedDepartmentIds.has(parentId)) {
        includedDepartmentIds.add(parentId)
        parentId = departmentById.get(parentId)?.parentId
      }
    })
  }
  const departments = enabledOrganizationDepartments.value
    .filter((department) => includedDepartmentIds.has(department.id))
    .map((department) => ({
      ...department,
      disabled: !allScope && !assignedDepartmentIds.has(department.id)
    }))
  const currentDepartmentId = projectForm.value.departmentId
  if (currentDepartmentId && !departments.some((item) => item.id === currentDepartmentId)) {
    departments.unshift({ id: currentDepartmentId, parentId: null, name: `${departmentName(currentDepartmentId)}（历史部门）`, enabled: true, sortOrder: -1 })
  }
  return buildOrganizationTree(departments)
})
const projectOwnerOptions = computed(() => projectUsers.value.filter((user) => user.canOwnProject))
const enabledOrganizationDepartments = computed(() => organizationDepartments.value.filter((department) => department.enabled))
const brandPageUploadDepartmentTreeData = computed(() => buildOrganizationTree(enabledOrganizationDepartments.value))
const brandPagePermissionCheckedKeys = computed(() => [
  ...(brandPageForm.value.viewerDepartmentIds || []).map((id) => `department:${id}`),
  ...(brandPageForm.value.viewerUserIds || []).map((id) => `user:${id}`)
])
const { treeData: brandPagePermissionTreeData, begin: beginBrandPagePermissionTree } =
  useBrandPagePermissionTree(organizationDepartments, brandPageViewerUsers, buildOrganizationTree)
const brandPageDepartmentTreeData = computed(() => {
  if (currentUser.value.permissions?.includes('admin:brand-page:write')) {
    return brandPageUploadDepartmentTreeData.value
  }
  const assignedDepartmentIds = new Set(currentUser.value.departmentKeys || [])
  const departmentById = new Map(enabledOrganizationDepartments.value
    .map((department) => [department.id, department]))
  const includedDepartmentIds = new Set(assignedDepartmentIds)
  assignedDepartmentIds.forEach((departmentId) => {
    let parentId = departmentById.get(departmentId)?.parentId
    while (parentId && departmentById.has(parentId) && !includedDepartmentIds.has(parentId)) {
      includedDepartmentIds.add(parentId)
      parentId = departmentById.get(parentId)?.parentId
    }
  })
  return buildOrganizationTree(enabledOrganizationDepartments.value
    .filter((department) => includedDepartmentIds.has(department.id))
    .map((department) => ({
      ...department,
      disabled: !assignedDepartmentIds.has(department.id)
    })))
})
const runDepartmentTreeData = computed(() => buildOrganizationTree(enabledOrganizationDepartments.value))
const editableOrganizationDepartments = computed(() => organizationDepartments.value.filter((department) => department.enabled || userEditForm.value.departmentKeys.includes(department.id)))
const userCreateDepartmentTreeData = computed(() => buildOrganizationTree(enabledOrganizationDepartments.value))
const userEditDepartmentTreeData = computed(() => buildOrganizationTree(editableOrganizationDepartments.value))
const createPrimaryDepartmentOptions = computed(() => departmentSelectOptions.value.filter((option) => userCreateForm.value.departmentKeys.includes(option.value)))
const editPrimaryDepartmentOptions = computed(() => organizationDepartments.value
  .filter((department) => userEditForm.value.departmentKeys.includes(department.id))
  .map((department) => ({ label: `${department.name}${department.enabled ? '' : '（已禁用）'}`, value: department.id })))
const selectedOrganizationDepartment = computed(() => organizationDepartments.value.find((department) => department.id === organizationForm.value.id) || null)
const organizationParentName = computed(() => organizationDepartments.value.find((department) => department.id === organizationForm.value.parentId)?.name || '组织根节点')
const organizationDescendantIds = computed(() => {
  const descendants = new Set()
  const visit = (parentId) => organizationDepartments.value
    .filter((department) => department.parentId === parentId)
    .forEach((department) => {
      if (descendants.has(department.id)) return
      descendants.add(department.id)
      visit(department.id)
    })
  if (organizationForm.value.id) visit(organizationForm.value.id)
  return descendants
})
const organizationParentOptions = computed(() => organizationDepartments.value
  .filter((department) => department.id !== organizationForm.value.id && !organizationDescendantIds.value.has(department.id)))
const organizationTreeData = computed(() => buildOrganizationTree(organizationDepartments.value))
const organizationParentTreeData = computed(() => buildOrganizationTree(organizationParentOptions.value))
const pagedUsers = computed(() => adminPageItems('users', users.value))
const pagedRoles = computed(() => adminPageItems('roles', roles.value))
const pagedTools = computed(() => adminPageItems('tools', flatTools.value))
const pagedModels = computed(() => adminPageItems('models', modelConfigs.value))
const pagedUpstreamAccounts = computed(() => adminPageItems('upstreamAccounts', []))
const upstreamProxyOptions = computed(() => upstreamProxies.value
  .filter((proxy) => proxy.status === 'active')
  .map((proxy) => ({
    label: `${proxy.name}${proxy.country ? `（${proxy.country}）` : ''}`,
    value: proxy.id
  })))
const pagedAiLogs = computed(() => adminPageItems('aiLogs', aiLogs.value))
const pagedOperationLogs = computed(() => adminPageItems('operationLogs', operationLogs.value))
const pagedDeployments = computed(() => adminPageItems('deployments', deploymentRequests.value))
const departmentTaskSummary = computed(() => projectWorkflowLanes.value.map((lane) => ({
  key: lane.key,
  name: lane.name,
  completed: lane.completed,
  total: lane.tasks.length,
  done: lane.tasks.length > 0 && lane.completed === lane.tasks.length
})))
const projectAllDepartmentsDone = computed(() => {
  const items = departmentTaskSummary.value
  return items.length > 0 && items.every((item) => item.done)
})

watch([
  runSearchQuery,
  () => runFilters.value.tool,
  () => runFilters.value.employee,
  () => runFilters.value.department,
  () => runFilters.value.status
], () => {
  runPage.value = 1
})

watch([projectBoardSearch, projectBoardDepartment, projectBoardBrand, projectBoardCreator], () => {
  projectBoardPage.value = 1
})

watch(() => projectForm.value.ownerUserId, (ownerUserId) => {
  if (!showProjectCreate.value || !ownerUserId) return
  if (!projectForm.value.memberUserIds.includes(ownerUserId)) {
    projectForm.value.memberUserIds = [ownerUserId, ...projectForm.value.memberUserIds]
  }
})

watch(() => filteredRunRecords.value.length, (total) => {
  const lastPage = Math.max(1, Math.ceil(total / runPageSize))
  if (runPage.value > lastPage) runPage.value = lastPage
})

watch(pagedRunRecords, (records) => {
  preloadRunImages(records)
}, { immediate: true })

watch(() => brandPageForm.value.departmentId, (departmentId) => {
  if (!brandPageUploadVisible.value || !departmentId) return
  if (!brandPageForm.value.viewerDepartmentIds.length && !brandPageForm.value.viewerUserIds.length) {
    brandPageForm.value.viewerDepartmentIds = [departmentId]
    brandPagePermissionTreeRef.value?.setCheckedKeys(brandPagePermissionCheckedKeys.value)
  }
})

onMounted(async () => {
  normalizeInitialPath()
  window.addEventListener('popstate', applyRouteFromLocation)
  document.addEventListener('fullscreenchange', syncBrandPageFullscreenState)
  document.addEventListener('pointerdown', closeBrandPageControlsFromOutside)
  document.addEventListener('keydown', handleBrandPageControlsKeydown)
  await loadPublicStats()
  await restoreSession()
})

onUnmounted(() => {
  cleanupBrandPagePreviewInteractions()
  window.removeEventListener('popstate', applyRouteFromLocation)
  document.removeEventListener('fullscreenchange', syncBrandPageFullscreenState)
  document.removeEventListener('pointerdown', closeBrandPageControlsFromOutside)
  document.removeEventListener('keydown', handleBrandPageControlsKeydown)
})

async function loadPublicStats() {
  try {
    publicStats.value = await publicStatsApi()
  } catch {
    publicStats.value = {}
  }
}

async function restoreSession() {
  if (!hasAccessToken()) {
    authed.value = false
    return
  }
  try {
    currentUser.value = normalizeCurrentUser(await currentUserApi())
    ensureAccessibleAdminSection()
    if (routeMode.value === 'admin' && !hasAdminAccess(currentUser.value.permissions)) {
      enterWorkbenchAfterAuth()
    }
    if (currentUser.value.mustChangePassword) {
      passwordChangeRequired.value = true
      passwordChangeForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
      return
    }
    authed.value = true
    await loadServerData()
    await loadBrandPageRoute()
    if (routeMode.value === 'admin') {
      await loadAdminPage(adminSection.value, adminPages.value[adminSection.value]?.page || 1)
    }
  } catch {
    logoutApi()
    authed.value = false
    errorMessage.value = '登录已失效，请重新登录。'
  } finally {
  }
}

async function login() {
  loading.value = true
  errorMessage.value = ''
  try {
    const loginResult = await loginApi(loginForm.value.account, loginForm.value.password)
    currentUser.value = normalizeCurrentUser({
      ...(loginResult.user || {}),
      permissions: loginResult.permissions || loginResult.user?.permissions || []
    })
    ensureAccessibleAdminSection()
    if (routeMode.value === 'admin' && !hasAdminAccess(currentUser.value.permissions)) {
      enterWorkbenchAfterAuth()
    }
    if (loginResult.mustChangePassword || currentUser.value.mustChangePassword) {
      passwordChangeForm.value = { oldPassword: loginForm.value.password, newPassword: '', confirmPassword: '' }
      passwordChangeRequired.value = true
      return
    }
    authed.value = true
    await loadServerData()
    await loadBrandPageRoute()
    if (routeMode.value === 'admin') {
      await loadAdminPage(adminSection.value, adminPages.value[adminSection.value]?.page || 1)
    }
  } catch (error) {
    logoutApi()
    authed.value = false
    errorMessage.value = readableError(error, '登录失败，请检查账号、密码或后端服务。')
  } finally {
    loading.value = false
  }
}

async function submitInitialPasswordChange() {
  errorMessage.value = ''
  const { oldPassword, newPassword, confirmPassword } = passwordChangeForm.value
  if (!oldPassword || !newPassword || !confirmPassword) {
    errorMessage.value = '请完整填写临时密码和新密码'
    return
  }
  if (newPassword !== confirmPassword) {
    errorMessage.value = '两次输入的新密码不一致'
    return
  }
  if (newPassword.length < 8 || !/[A-Za-z]/.test(newPassword) || !/\d/.test(newPassword)) {
    errorMessage.value = '新密码至少 8 位，并且必须包含字母和数字'
    return
  }
  passwordChanging.value = true
  try {
    await changePasswordApi(oldPassword, newPassword)
    currentUser.value = normalizeCurrentUser(await currentUserApi())
    ensureAccessibleAdminSection()
    await loadServerData()
    enterWorkbenchAfterAuth()
    passwordChangeRequired.value = false
    passwordChangeForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    loginForm.value.password = ''
    authed.value = true
  } catch (error) {
    errorMessage.value = readableError(error, '密码修改失败，请检查临时密码和新密码')
  } finally {
    passwordChanging.value = false
  }
}

function cancelPasswordChange() {
  logoutApi()
  currentUser.value = {}
  passwordChangeRequired.value = false
  passwordChanging.value = false
  passwordChangeForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  loginForm.value.password = ''
  errorMessage.value = ''
}

async function loadServerData() {
  try {
    const data = await loadBootstrap()
    runDetailCache.clear()
    departments.value = normalizeDepartments(data.departments || [])
    roleTools.value = data.roleTools || []
    canAccessNewChat.value = (data.tools || []).some((tool) => tool.key === 'new-chat' && tool.status === 'normal')
    ensureActiveDepartmentAvailable()
    const projectCreditUsage = data.projectCreditUsage || {}
    projects.value = (data.projects || []).map((project) => ({
      ...project,
      ...(project.details || {}),
      creditUsage: projectCreditUsage[project.id] || 0
    }))
    projectUsers.value = data.projectUsers || []
    projectTasks.value = data.projectTasks || []
    projectResults.value = data.projectResults || []
    // 运行记录归一化时需要将组织部门 ID 映射为名称，因此必须先注入组织树。
    organizationDepartments.value = data.organizationDepartments || []
    brandPageViewerUsers.value = data.brandPageViewerUsers || []
    users.value = data.users || []
    runUsers.value = data.runUsers || {}
    runProjects.value = data.runProjects || {}
    runTokenUsage.value = data.runTokenUsage || {}
    runCreditUsage.value = data.runCreditUsage || {}
    runPrompts.value = data.runPrompts || {}
    runRecords.value = normalizeRuns(data.runRecords || [])
    aiLogs.value = normalizeAiLogs(data.aiLogs || [])
    roles.value = data.roles || []
    permissions.value = data.permissions || []
    modelConfigs.value = data.modelConfigs || []
    operationLogs.value = data.operationLogs || []
    deploymentRequests.value = data.deploymentRequests || []
    stats.value = data.stats || {}
    if (!selectedProject.value || !projects.value.some((project) => project.id === selectedProject.value.id)) {
      selectedProject.value = projects.value[0] || null
      projectSelectedForTool.value = false
    }
    dataSource.value = '当前已连接后端接口。'
  } catch (error) {
    dataSource.value = '后端接口连接失败。'
    throw error
  }
}

async function logout() {
  try {
    await revokeLoginSessionApi()
  } catch (error) {
    showNotice(readableError(error, '工作台退出未完成，请稍后重试'), '退出失败')
    return
  }
  syncAdminEditorDialogs('workbench', '')
  logoutApi()
  runDetailCache.clear()
  selectedRun.value = null
  currentUser.value = {}
  authed.value = false
  passwordChangeRequired.value = false
  passwordChangeForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  enterWorkbenchAfterAuth()
}

function normalizeCurrentUser(user) {
  return {
    ...(user || {}),
    permissions: user?.permissions || []
  }
}

function ensureAccessibleAdminSection() {
  if (canReadAdminSection(currentUser.value.permissions, adminSection.value, currentUser.value.superAdmin)) return
  adminSection.value = visibleAdminSections(currentUser.value.permissions, currentUser.value.superAdmin)[0]?.key || 'overview'
}

function enterWorkbenchAfterAuth() {
  window.history.replaceState({}, '', '/workbench')
  applyRouteFromLocation()
}

const activeRelayReport = computed(() => RELAY_REPORT_PAGES.find(item => item.page === currentPage.value))
const relayAdminNavItems = computed(() => ['upstreamAccounts','relayQuotas'].map(key=>visibleAdminNavItems.value.find(item=>item.key===key)).filter(Boolean))
const otherAdminNavItems = computed(() => visibleAdminNavItems.value.filter(item=>!['upstreamAccounts','relayQuotas'].includes(item.key)))
function initialRoute() {
  const path = window.location.pathname
  const report = relayReportRoute(path)
  if(report){
    if(path!==report.path)window.history.replaceState({},'',report.path+window.location.search)
    return {mode:'workbench',page:report.page}
  }
  if (path.startsWith('/workbench/my-data')) return { mode: 'workbench', page: 'my-data' }
  if (path.startsWith('/tools/')) {
    const toolKey = decodeURIComponent(path.replace(/^\/tools\/?/, '').split('/')[0] || '')
    if (!toolKey) return { mode: 'workbench', page: 'workbench' }
    return { mode: 'tool', page: 'tool', toolKey, query: Object.fromEntries(new URLSearchParams(window.location.search)) }
  }
  if (path.startsWith('/admin')) {
    const adminSection = decodeURIComponent(path.replace(/^\/admin\/?/, '').split('/')[0] || '')
    return { mode: 'admin', page: 'config', adminSection: adminSection || 'overview' }
  }
  if (path.startsWith('/workbench/brand-pages/')) {
    const brandPageId = decodeURIComponent(path.replace(/^\/workbench\/brand-pages\//, '').split('/')[0] || '')
    const brandPageReturn = new URLSearchParams(window.location.search).get('from') === 'admin' ? 'admin' : 'workbench'
    return { mode: 'workbench', page: 'brand-page-preview', brandPageId, brandPageReturn }
  }
  if (path.startsWith('/workbench/brand-pages')) return { mode: 'workbench', page: 'brand-pages' }
  if (path.startsWith('/workbench/project-board')) return { mode: 'workbench', page: 'project-board' }
  if (path.startsWith('/workbench/projects')) return { mode: 'workbench', page: 'projects' }
  if (path.startsWith('/workbench/runs')) return { mode: 'workbench', page: 'runs' }
  return { mode: 'workbench', page: 'workbench' }
}

function normalizeInitialPath() {
  if (window.location.pathname === '/') {
    window.history.replaceState({}, '', '/workbench')
  }
  applyRouteFromLocation()
}

function applyRouteFromLocation() {
  const route = initialRoute()
  routeMode.value = route.mode
  currentPage.value = route.page
  if (route.mode === 'admin') adminSection.value = route.adminSection || 'overview'
  toolRouteKey.value = route.toolKey || ''
  toolRouteQuery.value = route.query || {}
  brandPageRouteId.value = route.brandPageId || ''
  brandPagePreviewReturn.value = route.brandPageReturn || 'workbench'
  if (route.page !== 'brand-page-preview') {
    selectedBrandPage.value = null
    brandPageHtml.value = ''
  }
  syncAdminEditorDialogs(route.mode, adminSection.value)
}

function navigateEntry(mode) {
  if (mode === 'admin') {
    adminSection.value = 'overview'
    navigateTo('/admin', 'admin', 'config')
    return
  }
  navigateTo('/workbench', 'workbench', 'workbench')
}

function openAdminSection(section) {
  if (!canReadAdminSection(currentUser.value.permissions, section, currentUser.value.superAdmin)) return
  const path = section === 'overview' ? '/admin' : `/admin/${encodeURIComponent(section)}`
  navigateTo(path, 'admin', 'config')
  adminSection.value = section
  syncAdminEditorDialogs(routeMode.value, section)
  if (adminPages.value[section]) loadAdminPage(section, adminPages.value[section].page || 1)
}

function syncAdminEditorDialogs(mode = routeMode.value, section = adminSection.value) {
  if (mode !== 'admin' || section !== 'organization') organizationDialogVisible.value = false
  if (mode !== 'admin' || section !== 'roles') roleDialogVisible.value = false
  if ((mode !== 'admin' || section !== 'models') && !modelConfigSaving.value) closeModelConfigEditor(true)
  if ((mode !== 'admin' || section !== 'upstreamAccounts') && !upstreamAccountSaving.value) closeUpstreamAccountDialog()
}

function adminPageItems(section, fallback) {
  const state = adminPages.value[section]
  return state?.loaded ? state.items : fallback
}

async function loadAdminPage(section, page = 1) {
  if (section === 'aiLogs') return
  if (!canReadAdminSection(currentUser.value.permissions, section, currentUser.value.superAdmin)) return
  const state = adminPages.value[section]
  if (!state || !authed.value) return
  const requestId = ++state.requestId
  state.loading = true
  try {
    if (section === 'upstreamAccounts') await loadUpstreamAccountDependencies()
    const filters = section === 'upstreamAccounts' ? upstreamFilters.value
      : section === 'users' ? { search: userSearch.value.trim() } : undefined
    const response = await loadAdminPageApi(section, page, state.pageSize, filters)
    if (requestId !== state.requestId) return
    state.items = section === 'aiLogs' ? normalizeAiLogs(response.items || []) : (response.items || [])
    state.page = response.page || 1
    state.pageSize = response.pageSize || state.pageSize
    state.total = response.total || 0
    state.totalPages = response.totalPages || 0
    if(section === 'upstreamAccounts'){const all=upstreamFilters.value.platform || upstreamFilters.value.status || upstreamFilters.value.search ? await getJson('/api/admin/upstream-accounts?page=1&pageSize=1') : response;upstreamSummary.value=all.summary || null}
    state.loaded = true
  } catch (error) {
    if (requestId === state.requestId) {
      showNotice(readableError(error, '分页数据加载失败'), '加载后台列表失败')
    }
  } finally {
    if (requestId === state.requestId) state.loading = false
  }
}

function changeAdminPage(section, page) {
  loadAdminPage(section, page)
}

watch(userSearch, (_value, _previous, onCleanup) => {
  const timer = window.setTimeout(() => {
    if (adminSection.value === 'users') loadAdminPage('users', 1)
  }, 250)
  onCleanup(() => window.clearTimeout(timer))
})

function emptyUpstreamAccountForm() {
  return {
    platform: 'openai',
    name: '',
    proxyId: 0,
    type: 'oauth',
    apiKey: '',
    notes: '',
    threshold5h: 90,
    threshold7d: 95,
    concurrency: 5,
    priority: 1,
    oauthType: 'google_one',
    projectId: '',
    tierId: '',
    sessionId: '',
    state: '',
    authUrl: '',
    authorizationResult: ''
  }
}

async function loadUpstreamAccountDependencies() {
  const [configuration, proxies] = await Promise.all([
    loadUpstreamAccountConfiguration(),
    loadUpstreamAccountProxies().catch(() => [])
  ])
  upstreamConfiguration.value = configuration
  upstreamProxies.value = Array.isArray(proxies) ? proxies : []
}

function reloadUpstreamAccounts() {
  loadAdminPage('upstreamAccounts', 1)
}

async function openUpstreamAccountDialog() {
  upstreamAccountMessage.value = ''
  upstreamOAuthStep.value = 'setup'
  upstreamAccountForm.value = emptyUpstreamAccountForm()
  try {
    await loadUpstreamAccountDependencies()
    // Apply the default once when opening a new account; preserve later user choices.
    if (upstreamProxyOptions.value.length === 1) {
      upstreamAccountForm.value.proxyId = upstreamProxyOptions.value[0].value
    }
    upstreamAccountDialogVisible.value = true
  } catch (error) {
    showNotice(readableError(error, 'Sub2API 配置加载失败'), '无法添加账号')
  }
}

function closeUpstreamAccountDialog() {
  if (upstreamAccountSaving.value) return
  upstreamAccountDialogVisible.value = false
  upstreamOAuthStep.value = 'setup'
  upstreamAccountMessage.value = ''
  upstreamAccountForm.value = emptyUpstreamAccountForm()
}

async function submitUpstreamAccount() {
  if(upstreamAccountSaving.value)return
  upstreamAccountMessage.value=validateUpstreamForm(upstreamAccountForm.value)
  if(upstreamAccountMessage.value)return
  if(upstreamAccountForm.value.type==='oauth')return upstreamOAuthStep.value==='setup'?startUpstreamOAuth():completeUpstreamOAuth()
  upstreamAccountSaving.value=true
  try {
    const f=upstreamAccountForm.value
    await postJson('/api/admin/upstream-accounts',{platform:f.platform,name:f.name.trim(),apiKey:f.apiKey,notes:f.notes,proxyId:Number(f.proxyId)||null,concurrency:f.concurrency,priority:f.priority})
    closeUpstreamAccountDialogAfterSave()
    await loadAdminPage('upstreamAccounts',1)
    showNotice('API 账号已保存并加入中转调度池','添加成功','success')
  } catch(e){upstreamAccountMessage.value=readableError(e,'API 账号保存失败')}
  finally{upstreamAccountSaving.value=false}
}

async function startUpstreamOAuth() {
  if (!upstreamAccountForm.value.name.trim()) {
    upstreamAccountMessage.value = '请填写便于识别的账号名称'
    return
  }
  const authorizationWindow = window.open('', 'sub2api-oauth', 'popup,width=720,height=820')
  upstreamAccountSaving.value = true
  upstreamAccountMessage.value = ''
  try {
    const result = await startUpstreamAccountOAuth({
      platform: upstreamAccountForm.value.platform,
      proxyId: Number(upstreamAccountForm.value.proxyId) || undefined,
      projectId: upstreamAccountForm.value.projectId || undefined,
      oauthType: upstreamAccountForm.value.platform === 'gemini' ? upstreamAccountForm.value.oauthType : undefined
    })
    upstreamAccountForm.value.authUrl = result.authUrl
    upstreamAccountForm.value.sessionId = result.sessionId
    upstreamAccountForm.value.state = result.state
    upstreamOAuthStep.value = 'authorize'
    if (authorizationWindow) authorizationWindow.location.href = result.authUrl
    else upstreamAccountMessage.value = '浏览器拦截了新窗口，请点击“打开授权页面”'
  } catch (error) {
    if (authorizationWindow) authorizationWindow.close()
    upstreamAccountMessage.value = readableError(error, '生成授权链接失败')
  } finally {
    upstreamAccountSaving.value = false
  }
}

watch(()=>[upstreamAccountForm.value.platform,upstreamAccountForm.value.type],()=>{Object.assign(upstreamAccountForm.value,{apiKey:'',sessionId:'',state:'',authUrl:'',authorizationResult:''});upstreamOAuthStep.value='setup'})

const upstreamAuthUrlInput = ref(null)
async function copyUpstreamAuthorizationLink() {
  if (!upstreamAccountForm.value.authUrl) return
  try {
    await copyText(upstreamAccountForm.value.authUrl)
    upstreamAccountMessage.value = '授权链接已复制，请粘贴到外部浏览器打开'
  } catch {
    upstreamAuthUrlInput.value?.focus()
    upstreamAuthUrlInput.value?.select()
    upstreamAccountMessage.value = '自动复制失败，已选中链接，请按 ⌘C 或 Ctrl+C 手动复制'
  }
}

function openUpstreamAuthorizationWindow() {
  if (upstreamAccountForm.value.authUrl) {
    window.open(upstreamAccountForm.value.authUrl, 'sub2api-oauth', 'popup,width=720,height=820')
  }
}

async function completeUpstreamOAuth() {
  if (!upstreamAccountForm.value.authorizationResult.trim()) {
    upstreamAccountMessage.value = '请粘贴授权完成后的跳转地址'
    return
  }
  upstreamAccountSaving.value = true
  upstreamAccountMessage.value = ''
  try {
    await completeUpstreamAccountOAuth({
      platform: upstreamAccountForm.value.platform,
      sessionId: upstreamAccountForm.value.sessionId,
      state: upstreamAccountForm.value.state,
      authorizationResult: upstreamAccountForm.value.authorizationResult,
      proxyId: Number(upstreamAccountForm.value.proxyId) || undefined,
      name: upstreamAccountForm.value.name.trim(),
      concurrency: Number(upstreamAccountForm.value.concurrency) || 1,
      priority: Number(upstreamAccountForm.value.priority),
      threshold5h: upstreamAccountForm.value.threshold5h,
      threshold7d: upstreamAccountForm.value.threshold7d,
      notes: upstreamAccountForm.value.notes,
      oauthType: upstreamAccountForm.value.platform === 'gemini' ? upstreamAccountForm.value.oauthType : undefined
    })
    closeUpstreamAccountDialogAfterSave()
    await loadAdminPage('upstreamAccounts', 1)
    showNotice('上游账号已添加，令牌已安全写入 Sub2API', '添加成功', 'success')
  } catch (error) {
    upstreamAccountMessage.value = readableError(error, '账号授权或保存失败')
  } finally {
    upstreamAccountSaving.value = false
  }
}

function closeUpstreamAccountDialogAfterSave() {
  upstreamAccountDialogVisible.value = false
  upstreamOAuthStep.value = 'setup'
  upstreamAccountMessage.value = ''
  upstreamAccountForm.value = emptyUpstreamAccountForm()
}

async function refreshAllUpstreamSnapshots() {
  if(upstreamSnapshotBusy.value)return
  upstreamSnapshotBusy.value=true
  let succeeded=0,failed=0
  try {
    const accounts=[]
    for(let page=1;page<=200;page++){
      const result=await getJson(`/api/admin/upstream-accounts?page=${page}&pageSize=100`)
      accounts.push(...(result.items || []))
      if(page >= (result.totalPages || 1))break
      if(page===200)throw new Error('账号数量超过单次刷新范围')
    }
    for(const account of accounts.filter(supportsWindow)){
      try{await postJson(`/api/admin/upstream-accounts/${account.id}/usage-refresh`,{});succeeded++}catch{failed++}
    }
    await loadAdminPage('upstreamAccounts',1)
    showNotice(`已刷新 ${succeeded} 个账号${failed ? `，${failed} 个账号刷新失败，请检查健康状态后重试` : ''}`,failed?'部分刷新失败':'快照刷新完成',failed?'warning':'success')
  }catch(error){showNotice(readableError(error,'配额快照刷新失败'),'刷新失败')}
  finally{upstreamSnapshotBusy.value=false}
}

async function withUpstreamAccountBusy(account, action, successMessage, fallback) {
  upstreamAccountBusyId.value = account.id
  try {
    await action()
    await loadAdminPage('upstreamAccounts', adminPages.value.upstreamAccounts.page)
    showNotice(successMessage, '操作成功', 'success')
  } catch (error) {
    showNotice(readableError(error, fallback), '操作失败')
  } finally {
    upstreamAccountBusyId.value = null
  }
}

function testManagedUpstreamAccount(account) {
  upstreamTestingAccount.value=account
}

function refreshManagedUpstreamAccount(account) {
  return withUpstreamAccountBusy(account, () => refreshUpstreamAccount(account.id), '账号凭据已刷新', '账号凭据刷新失败')
}

function toggleManagedUpstreamAccount(account) {
  const enabled = account.status !== 'active'
  return withUpstreamAccountBusy(account, () => updateUpstreamAccountStatus(account.id, enabled),
    enabled ? '账号已启用' : '账号已停用', '账号状态更新失败')
}

async function deleteManagedUpstreamAccount(account) {
  const confirmed = await askConfirm({
    title: '删除上游账号',
    message: `确认删除“${account.name}”？Sub2API 中保存的授权凭据也会一并删除。`,
    confirmText: '确认删除'
  })
  if (!confirmed) return
  return withUpstreamAccountBusy(account, () => deleteUpstreamAccount(account.id), '上游账号已删除', '删除上游账号失败')
}

function upstreamPlatformLabel(platform) {
  return platform === 'gemini' ? 'Gemini' : platform === 'openai' ? 'OpenAI' : platform
}

async function recoverManagedUpstreamAccount(account) {
  if(!await askConfirm({title:'恢复账号异常',message:'将清除异常、限流及临时调度状态；真实额度阈值仍然有效。确认恢复？',confirmText:'确认恢复'}))return
  return withUpstreamAccountBusy(account,()=>postJson(`/api/admin/upstream-accounts/${account.id}/recover`,{}),'异常状态已恢复','恢复失败')
}

function upstreamCooling(account){return account.schedulable === false || ['rateLimitResetAt','tempUnschedulableUntil','overloadUntil'].some(k=>account[k] && new Date(account[k]).getTime()>Date.now())}
function refreshUpstreamWindow(account){return withUpstreamAccountBusy(account,()=>postJson(`/api/admin/upstream-accounts/${account.id}/usage-refresh`,{}),'上游窗口已刷新','窗口刷新失败')}
function upstreamStatusLabel(account) {
  if (account.status === 'error') return '异常'
  if (account.status === 'inactive') return '已停用'
  if (upstreamCooling(account)) return '冷却 / 暂停调度'
  return '正常'
}

function upstreamStatusTone(account) {
  if (account.status === 'error') return 'warning'
  if (account.status === 'inactive' || upstreamCooling(account)) return 'muted'
  return 'success'
}

function upstreamLoadRate(account) {
  if(account.currentConcurrency == null)return '不可用'
  const limit = Number(account.concurrency || 0)
  const current = Number(account.currentConcurrency || 0)
  if (!limit) return '-'
  return `${Math.min(100, Math.round(current * 100 / limit))}%（${current}/${limit}）`
}

function upstreamRemaining(usedPercent) {
  if(usedPercent == null)return '不可用'
  const used = Number(usedPercent)
  if (!Number.isFinite(used)) return '-'
  return `${Math.max(0, 100 - used).toFixed(0)}%`
}

function changeRunPage(page) {
  runPage.value = page
}

function runToolIcon(toolKey) {
  return runToolIcons[toolKey] || Sparkles
}

function runTone(value) {
  const hash = Array.from(String(value || '')).reduce((total, character) => total + character.charCodeAt(0), 0)
  return (hash % 4) + 1
}

async function copyRunResult(record) {
  let detail = record
  let hasImage = false
  try {
    detail = await ensureRunDetail(record)
    hasImage = extractRunAttachments(detail).some((attachment) => attachment.kind === 'image')
    const copiedKind = await copyRunResultToClipboard(detail)
    showNotice(copiedKind === 'image' ? '生成图片已复制' : '运行结果已复制', '复制成功', 'success')
  } catch (error) {
    const errorName = error?.name || error?.code || 'UnknownError'
    showNotice(
      hasImage
        ? (error?.code === 'IMAGE_PREPARING'
            ? '图片正在准备，请稍后再次点击复制'
            : `浏览器复制图片失败（${errorName}），请使用右侧下载按钮`)
        : '浏览器未允许复制，请在详情中手动复制',
      '复制失败'
    )
  }
}

function runCopyActionLabel(record) {
  return record.hasImage || extractRunAttachments(record).some((attachment) => attachment.kind === 'image')
    ? '复制图片'
    : '复制结果'
}

const runDetailCache = new Map()
let runDetailRequest = 0

async function ensureRunDetail(record) {
  if (!record?.raw?.runId) return record
  if (runDetailCache.has(record.id)) return runDetailCache.get(record.id)
  const fullRun = await getToolRun(record.id)
  const detail = { ...normalizeRuns([fullRun])[0], detailLoaded: true }
  runDetailCache.set(record.id, detail)
  return detail
}

async function openRunDetail(record) {
  if (!record) return
  const request = ++runDetailRequest
  selectedRun.value = record
  if (!record.raw?.runId) {
    runDetailLoading.value = false
    return
  }
  runDetailLoading.value = true
  try {
    const detail = await ensureRunDetail(record)
    if (selectedRun.value === record) selectedRun.value = detail
    preloadRunImages([detail])
  } catch {
    if (selectedRun.value === record) selectedRun.value = null
    showNotice('运行详情加载失败，请重试', '加载失败')
  } finally {
    if (runDetailRequest === request) runDetailLoading.value = false
  }
}

async function downloadRunAttachments(record) {
  try {
    const detail = await ensureRunDetail(record)
    const attachments = extractRunAttachments(detail)
    attachments.forEach((attachment) => {
      const link = document.createElement('a')
      link.href = attachment.url
      link.target = '_blank'
      link.rel = 'noopener'
      link.download = attachment.name
      document.body.appendChild(link)
      link.click()
      link.remove()
    })
  } catch {
    showNotice('附件下载未能启动，请在详情中打开附件', '下载失败')
  }
}

function navigatePage(page) {
  const report=RELAY_REPORT_PAGES.find(item=>item.page===page)
  if(report){if(canViewRelayReports(currentUser.value.permissions))navigateTo(report.path,'workbench',page);return}
  if (page === 'my-data') { navigateTo('/workbench/my-data', 'workbench', page); return }
  if (routeMode.value === 'admin') {
    navigateTo('/admin', 'admin', 'config')
    return
  }
  const path = page === 'projects' ? '/workbench/projects' : page === 'project-board' ? '/workbench/project-board' : page === 'runs' ? '/workbench/runs' : page === 'brand-pages' ? '/workbench/brand-pages' : '/workbench'
  navigateTo(path, 'workbench', page)
  if (page === 'brand-pages') loadBrandPages()
}

function navigateTo(path, mode, page) {
  if (window.location.pathname !== path) {
    window.history.pushState({}, '', path)
  }
  routeMode.value = mode
  currentPage.value = page
  if (page !== 'brand-page-preview') brandPageRouteId.value = ''
  if (mode !== 'tool') {
    toolRouteKey.value = ''
    toolRouteQuery.value = {}
  }
  syncAdminEditorDialogs(mode, adminSection.value)
}

async function loadBrandPages() {
  if (!currentUser.value.permissions?.includes('brand:page:read')) return
  brandPagesLoading.value = true
  try {
    brandPages.value = await searchBrandPages(brandPageSearch.value, brandPageDepartmentFilter.value)
  } catch (error) {
    showNotice(readableError(error, '品牌页面加载失败'), '加载失败')
  } finally {
    brandPagesLoading.value = false
  }
}

async function openBrandPage(page, returnTo = 'workbench') {
  const path = `/workbench/brand-pages/${encodeURIComponent(page.id)}${returnTo === 'admin' ? '?from=admin' : ''}`
  navigateTo(path, 'workbench', 'brand-page-preview')
  brandPageRouteId.value = page.id
  brandPagePreviewReturn.value = returnTo
  selectedBrandPage.value = page
  brandPageHtml.value = ''
  brandPageControlsExpanded.value = false
  brandPagesLoading.value = true
  try {
    brandPageHtml.value = await getBrandPageContent(page.id, returnTo === 'admin' && currentUser.value.permissions?.includes('admin:brand-page:write'))
  } catch (error) {
    showNotice(readableError(error, '品牌 HTML 页面加载失败'), '加载失败')
  } finally {
    brandPagesLoading.value = false
  }
}

function openBrandPageUpload() {
  editingBrandPageId.value = ''
  brandPageForm.value = { departmentId: '', brandName: '', viewerDepartmentIds: [], viewerUserIds: [], file: null }
  brandPageFileInputKey.value += 1
  beginBrandPagePermissionSession()
  brandPageMessage.value = ''
  brandPageUploadVisible.value = true
}

function openBrandPageEditor(page) {
  editingBrandPageId.value = page.id
  brandPageForm.value = {
    departmentId: page.departmentId || '',
    brandName: page.brandName || '',
    viewerDepartmentIds: (page.viewerDepartmentIds || []).filter((id) => organizationDepartments.value.some((item) => item.id === id)),
    viewerUserIds: (page.viewerUserIds || []).filter((id) => brandPageViewerUsers.value.some((item) => item.id === id)),
    file: null
  }
  brandPageFileInputKey.value += 1
  beginBrandPagePermissionSession()
  brandPageMessage.value = ''
  brandPageUploadVisible.value = true
}

function closeBrandPageUpload(force = false) {
  if (brandPageUploading.value && !force) return
  brandPageUploadVisible.value = false
  brandPageMessage.value = ''
  editingBrandPageId.value = ''
  brandPageForm.value = { departmentId: '', brandName: '', viewerDepartmentIds: [], viewerUserIds: [], file: null }
  brandPageFileInputKey.value += 1
  beginBrandPagePermissionSession()
}

function selectBrandPageFile(event) {
  brandPageForm.value.file = event.target.files?.[0] || null
}

async function saveBrandPage() {
  const { departmentId, brandName, viewerDepartmentIds, viewerUserIds, file } = brandPageForm.value
  if (!departmentId || !brandName.trim() || (!editingBrandPageId.value && !file)) {
    brandPageMessage.value = editingBrandPageId.value
      ? '请选择组织部门并填写品牌名称'
      : '请选择组织部门、填写品牌名称并选择 HTML 文件'
    return
  }
  if (!viewerDepartmentIds.length && !viewerUserIds.length) {
    brandPageMessage.value = '请至少选择一个可查看的部门或员工'
    return
  }
  brandPageUploading.value = true
  brandPageMessage.value = ''
  try {
    if (editingBrandPageId.value) {
      await updateBrandPage(editingBrandPageId.value, brandName.trim(), departmentId, viewerDepartmentIds, viewerUserIds, file)
    } else {
      await uploadBrandPage(brandName.trim(), departmentId, viewerDepartmentIds, viewerUserIds, file)
    }
    closeBrandPageUpload(true)
    await loadAdminPage('brandPages', adminPages.value.brandPages.page)
    showNotice('品牌 HTML 页面已保存', '保存成功', 'success')
  } catch (error) {
    brandPageMessage.value = readableError(error, '上传失败')
  } finally {
    brandPageUploading.value = false
  }
}

function beginBrandPagePermissionSession() {
  beginBrandPagePermissionTree(brandPageForm.value)
  brandPagePermissionTreeKey.value += 1
  brandPagePermissionSearch.value = ''
}

function filterBrandPagePermissionNode(keyword, data) {
  return !keyword || String(data.label || '').toLocaleLowerCase().includes(String(keyword).trim().toLocaleLowerCase())
}

function filterBrandPagePermissionTree(keyword) {
  brandPagePermissionTreeRef.value?.filter(keyword)
}

function syncBrandPagePermissionSelection(_node, checked) {
  const keys = checked.checkedKeys || []
  brandPageForm.value.viewerDepartmentIds = keys
    .filter((key) => String(key).startsWith('department:'))
    .map((key) => String(key).slice('department:'.length))
  brandPageForm.value.viewerUserIds = keys
    .filter((key) => String(key).startsWith('user:'))
    .map((key) => String(key).slice('user:'.length))
}

function brandPageViewerSummary(page) {
  const departmentCount = (page.viewerDepartmentIds || []).filter((id) => organizationDepartments.value.some((item) => item.id === id)).length
  const userCount = (page.viewerUserIds || []).filter((id) => brandPageViewerUsers.value.some((item) => item.id === id)).length
  const stale = departmentCount < (page.viewerDepartmentIds?.length || 0) || userCount < (page.viewerUserIds?.length || 0)
  return `部门 ${departmentCount} · 员工 ${userCount}${stale ? '（原授权对象已删除，需重新分配）' : ''}`
}

async function loadBrandPageRoute() {
  if (currentPage.value !== 'brand-page-preview' || !brandPageRouteId.value || !currentUser.value.permissions?.includes('brand:page:read')) return
  brandPagesLoading.value = true
  try {
    const admin = brandPagePreviewReturn.value === 'admin' && currentUser.value.permissions?.includes('admin:brand-page:write')
    const pages = await searchBrandPages('', '', admin)
    const page = pages.find((item) => item.id === brandPageRouteId.value)
    if (!page) return
    selectedBrandPage.value = page
    brandPageHtml.value = await getBrandPageContent(page.id, admin)
  } catch (error) {
    showNotice(readableError(error, '品牌 HTML 页面加载失败'), '加载失败')
  } finally {
    brandPagesLoading.value = false
  }
}

async function toggleBrandPageFullscreen() {
  if (document.fullscreenElement) {
    await document.exitFullscreen?.()
    return
  }
  await brandPagePreviewRef.value?.requestFullscreen?.()
}

function syncBrandPageFullscreenState() {
  brandPageFullscreen.value = document.fullscreenElement === brandPagePreviewRef.value
}

function toggleBrandPageControls() {
  brandPageControlsExpanded.value = !brandPageControlsExpanded.value
}

function closeBrandPageControls() {
  brandPageControlsExpanded.value = false
}

function closeBrandPageControlsFromOutside(event) {
  if (!brandPageControlsExpanded.value || brandPageControlsRef.value?.contains(event.target)) return
  closeBrandPageControls()
}

function handleBrandPageControlsKeydown(event) {
  if (event.key === 'Escape') closeBrandPageControls()
}

function bindBrandPagePreviewInteractions() {
  cleanupBrandPagePreviewInteractions()
  cleanupBrandPagePreviewInteractions = () => {}
  try {
    const previewDocument = brandPageIframeRef.value?.contentDocument
    if (!previewDocument) return
    const cleanupAnchorNavigation = bindBrandPageAnchorNavigation(previewDocument)
    previewDocument.addEventListener('pointerdown', closeBrandPageControls)
    previewDocument.addEventListener('keydown', handleBrandPageControlsKeydown)
    cleanupBrandPagePreviewInteractions = () => {
      cleanupAnchorNavigation()
      previewDocument.removeEventListener('pointerdown', closeBrandPageControls)
      previewDocument.removeEventListener('keydown', handleBrandPageControlsKeydown)
    }
  } catch {
    // 跨域跳转后的 HTML 无法读取内部文档，侧边把手仍可手动收起。
  }
}

function previewBrandPage(page) {
  openBrandPage(page, 'admin')
}

async function returnFromBrandPagePreview() {
  if (brandPagePreviewReturn.value === 'admin') {
    adminSection.value = 'brandPages'
    navigateTo('/admin/brandPages', 'admin', 'config')
    await loadAdminPage('brandPages', adminPages.value.brandPages.page)
    return
  }
  navigatePage('brand-pages')
}

async function removeBrandPage(page) {
  const confirmed = await askConfirm({ title: '删除品牌页面', message: `确认删除“${page.brandName}”的 HTML 页面？`, confirmText: '删除' })
  if (!confirmed) return
  try {
    await deleteBrandPage(page.id)
    await loadAdminPage('brandPages', adminPages.value.brandPages.page)
    showNotice('品牌 HTML 页面已删除', '删除成功', 'success')
  } catch (error) {
    showNotice(readableError(error, '删除失败'), '删除失败')
  }
}

function roleName(role, permissions = []) {
  const normalizedRole = String(role || '').toUpperCase()
  if (normalizedRole === 'ADMIN' || permissions.includes('admin:user:read')) return '管理员'
  if (normalizedRole === 'LEADER' || normalizedRole === 'DEPARTMENT_LEADER') return '负责人'
  return '成员'
}

function taskStatusName(status) {
  if (status === 'DONE') return '已完成'
  if (status === 'IN_PROGRESS') return '进行中'
  if (status === 'BLOCKED') return '阻塞'
  return '待处理'
}

function readableError(error, fallback) {
  if (!error?.message) return fallback
  try {
    const parsed = JSON.parse(error.message)
    return parsed.message || fallback
  } catch {
    return error.message || fallback
  }
}

let noticeTimer = null
function showNotice(message, title = '操作失败', tone = 'danger') {
  if (noticeTimer) window.clearTimeout(noticeTimer)
  notice.value = { visible: true, title, message, tone }
  noticeTimer = window.setTimeout(() => {
    notice.value.visible = false
    noticeTimer = null
  }, 3000)
}

function askConfirm({ title, message, confirmText = '确认', cancelText = '取消' }) {
  return new Promise((resolve) => {
    confirmDialog.value = {
      visible: true,
      title,
      message,
      confirmText,
      cancelText,
      resolver: resolve
    }
  })
}

function resolveConfirm(confirmed) {
  const resolver = confirmDialog.value.resolver
  confirmDialog.value = {
    visible: false,
    title: '',
    message: '',
    confirmText: '确认',
    cancelText: '取消',
    resolver: null
  }
  if (resolver) resolver(confirmed)
}

function emptyUserCreateForm() {
  return {
    account: '',
    name: '',
    roleIds: [],
    departmentKeys: [],
    primaryDepartmentId: ''
  }
}

function openUserCreateDialog() {
  if (userCreating.value) return
  userCreateForm.value = {
    account: '',
    name: '',
    roleIds: roleSelectOptions.value[0]?.value ? [roleSelectOptions.value[0].value] : [],
    departmentKeys: departmentSelectOptions.value[0]?.value ? [departmentSelectOptions.value[0].value] : [],
    primaryDepartmentId: departmentSelectOptions.value[0]?.value || ''
  }
  userCreateMessage.value = ''
  userCreateDialogVisible.value = true
}

function closeUserCreateDialog(force = false) {
  if (userCreating.value && !force) return
  userCreateDialogVisible.value = false
  userCreateMessage.value = ''
  userCreateForm.value = emptyUserCreateForm()
}

async function submitUserCreate() {
  if (userCreating.value) return
  userCreateMessage.value = ''
  const account = userCreateForm.value.account.trim()
  const name = userCreateForm.value.name.trim()
  const roleIds = [...userCreateForm.value.roleIds]
  const departmentKeys = [...userCreateForm.value.departmentKeys]
  const primaryDepartmentId = userCreateForm.value.primaryDepartmentId
  if (!account || !name || !roleIds.length || !departmentKeys.length || !primaryDepartmentId) {
    userCreateMessage.value = '请填写账号、姓名、角色、所属部门和主部门'
    return
  }
  userCreating.value = true
  try {
    const created = await createUser({
      account,
      name,
      role: roleIds[0],
      roleIds,
      departmentKeys,
      primaryDepartmentId
    })
    users.value = [...users.value, created]
    closeUserCreateDialog(true)
    openCredentialDialog({
      title: '用户创建成功',
      description: '请将临时密码交给用户，首次登录后系统会要求修改密码。',
      account: created.account || account,
      temporaryPassword: created.temporaryPassword || '请通过重置密码重新生成'
    })
    try {
      await loadServerData()
      await loadAdminPage('users', 1)
    } catch (error) {
      showNotice(readableError(error, '用户已创建，但列表刷新失败'), '用户已创建')
    }
  } catch (error) {
    showNotice(readableError(error, '新增用户失败'), '新增用户失败')
  } finally {
    userCreating.value = false
  }
}

function toggleCreateUserRole(roleId) {
  if (userCreating.value) return
  userCreateForm.value.roleIds = toggleListValue(userCreateForm.value.roleIds, roleId)
}

function updateCreateUserDepartments(_node, checked) {
  if (userCreating.value) return
  userCreateForm.value.departmentKeys = checked.checkedKeys
  if (!userCreateForm.value.departmentKeys.includes(userCreateForm.value.primaryDepartmentId)) {
    userCreateForm.value.primaryDepartmentId = userCreateForm.value.departmentKeys[0] || ''
  }
}

function emptyUserEditForm() {
  return { id: '', account: '', name: '', roleIds: [], departmentKeys: [], primaryDepartmentId: '' }
}

function openUserEditDialog(user) {
  if (userEditing.value) return
  const departmentKeys = [...(user.departmentKeys || [])]
  userEditForm.value = {
    id: user.id,
    account: user.account,
    name: user.name || '',
    roleIds: [...(user.roleIds || [user.role]).filter(Boolean)],
    departmentKeys,
    primaryDepartmentId: user.primaryDepartmentId || departmentKeys[0] || ''
  }
  userEditMessage.value = ''
  userEditDialogVisible.value = true
}

function closeUserEditDialog(force = false) {
  if (userEditing.value && !force) return
  userEditDialogVisible.value = false
  userEditMessage.value = ''
  userEditForm.value = emptyUserEditForm()
}

function updateEditUserDepartments(_node, checked) {
  if (userEditing.value) return
  userEditForm.value.departmentKeys = checked.checkedKeys
  if (!userEditForm.value.departmentKeys.includes(userEditForm.value.primaryDepartmentId)) {
    userEditForm.value.primaryDepartmentId = userEditForm.value.departmentKeys[0] || ''
  }
}

function toggleUserRole(roleId) {
  if (userEditing.value) return
  userEditForm.value.roleIds = toggleListValue(userEditForm.value.roleIds, roleId)
}

async function submitUserEdit() {
  if (userEditing.value) return
  const form = userEditForm.value
  userEditMessage.value = ''
  if (!form.name.trim() || !form.roleIds.length || !form.departmentKeys.length || !form.primaryDepartmentId) {
    userEditMessage.value = '请填写姓名、角色、所属部门和主部门'
    return
  }
  const targetUserId = form.id
  const payload = {
    name: form.name.trim(),
    roleIds: [...form.roleIds],
    departmentKeys: [...form.departmentKeys],
    primaryDepartmentId: form.primaryDepartmentId
  }
  userEditing.value = true
  try {
    const updated = await updateUser(targetUserId, payload)
    users.value = users.value.map((item) => item.id === targetUserId ? updated : item)
    if (userEditForm.value.id === targetUserId) closeUserEditDialog(true)
    try {
      await loadServerData()
      await loadAdminPage('users', adminPages.value.users.page)
    } catch (error) {
      showNotice(readableError(error, '用户已保存，但列表刷新失败'), '用户已保存')
    }
  } catch (error) {
    if (userEditForm.value.id === targetUserId) {
      userEditMessage.value = readableError(error, '用户保存失败')
    }
  } finally {
    userEditing.value = false
  }
}

function emptyOrganizationForm() {
  return {
    id: '',
    name: '',
    parentId: ''
  }
}

function newOrganizationDepartment() {
  if (organizationSaving.value) return
  organizationForm.value = emptyOrganizationForm()
  organizationMessage.value = ''
  organizationDialogVisible.value = true
}

function newChildOrganizationDepartment(department) {
  if (organizationSaving.value) return
  organizationForm.value = {
    ...emptyOrganizationForm(),
    parentId: department.id
  }
  organizationMessage.value = ''
  organizationDialogVisible.value = true
}

function selectOrganizationDepartment(department, force = false) {
  if (organizationSaving.value && !force) return
  organizationForm.value = {
    id: department.id,
    name: department.name,
    parentId: department.parentId || ''
  }
  organizationMessage.value = ''
  organizationDialogVisible.value = true
}

function closeOrganizationDialog(force = false) {
  if (organizationSaving.value && !force) return
  organizationDialogVisible.value = false
}

async function saveOrganizationDepartment() {
  if (organizationSaving.value) return
  organizationMessage.value = ''
  const name = organizationForm.value.name.trim()
  if (!name) {
    organizationMessage.value = '请填写部门名称'
    return
  }
  const targetDepartmentId = organizationForm.value.id
  const payload = { name, parentId: organizationForm.value.parentId || null }
  const editing = Boolean(targetDepartmentId)
  organizationSaving.value = true
  try {
    const saved = editing
      ? await updateOrganizationDepartment(targetDepartmentId, payload)
      : await createOrganizationDepartment(payload)
    organizationDepartments.value = [
      ...organizationDepartments.value.filter((department) => department.id !== saved.id),
      saved
    ].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    if (!editing || organizationForm.value.id === targetDepartmentId) {
      selectOrganizationDepartment(saved, true)
    }
    showNotice(editing ? '组织部门已保存' : '组织部门已创建', '保存成功', 'success')
    closeOrganizationDialog(true)
    try {
      await loadServerData()
    } catch (error) {
      showNotice(readableError(error, '组织部门已保存，但列表刷新失败'), '刷新失败')
    }
  } catch (error) {
    if (!editing || organizationForm.value.id === targetDepartmentId) {
      organizationMessage.value = `保存失败：${readableError(error, '组织部门保存失败')}`
    }
  } finally {
    organizationSaving.value = false
  }
}

async function toggleOrganizationDepartment() {
  if (organizationSaving.value) return
  const department = selectedOrganizationDepartment.value
  if (!department) return
  const targetDepartmentId = department.id
  organizationSaving.value = true
  try {
    const updated = await updateOrganizationDepartmentStatus(targetDepartmentId, !department.enabled)
    organizationDepartments.value = organizationDepartments.value.map((item) => item.id === updated.id ? updated : item)
    const shouldHydrateEditor = organizationDialogVisible.value && routeMode.value === 'admin' && adminSection.value === 'organization' && organizationForm.value.id === targetDepartmentId
    if (shouldHydrateEditor) {
      selectOrganizationDepartment(updated, true)
      organizationMessage.value = '组织部门状态已更新'
    }
    await loadServerData()
  } catch (error) {
    if (organizationDialogVisible.value && routeMode.value === 'admin' && adminSection.value === 'organization' && organizationForm.value.id === targetDepartmentId) {
      organizationMessage.value = `更新失败：${readableError(error, '组织部门状态更新失败')}`
    }
  } finally {
    organizationSaving.value = false
  }
}

function buildOrganizationTree(departments) {
  const nodes = new Map(departments.map((department) => [department.id, {
    ...department,
    value: department.id,
    label: department.name,
    children: []
  }]))
  const roots = []
  nodes.forEach((node) => {
    const parent = nodes.get(node.parentId)
    if (parent && parent.id !== node.id) parent.children.push(node)
    else roots.push(node)
  })
  const sortNodes = (items) => items
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((item) => ({ ...item, children: sortNodes(item.children) }))
  return sortNodes(roots)
}

async function toggleUser(user) {
  try {
    const updated = await updateUserStatus(user.id, !user.enabled)
    users.value = users.value.map((item) => item.id === updated.id ? updated : item)
    await loadAdminPage('users', adminPages.value.users.page)
  } catch (error) {
    showNotice(error.message, '更新用户状态失败')
  }
}

async function resetUser(user) {
  let reset
  try {
    reset = await resetPassword(user.id)
    if (user.id === currentUser.value.id) {
      loginForm.value = { account: user.account, password: reset.temporaryPassword }
      logout()
      errorMessage.value = '密码已重置，请使用临时密码重新登录。'
      return
    }
    users.value = users.value.map((item) => item.id === user.id ? { ...item, mustChangePassword: true } : item)
    openCredentialDialog({
      title: '密码重置成功',
      description: '原登录会话已失效，请将新的临时密码交给用户。',
      account: user.account,
      temporaryPassword: reset.temporaryPassword
    })
  } catch (error) {
    showNotice(error.message, '重置密码失败')
    return
  }
  try {
    await loadAdminPage('users', adminPages.value.users.page)
  } catch (error) {
    showNotice(readableError(error, '密码已重置，但用户列表刷新失败'), '列表刷新失败')
  }
}

async function deleteManagedUser(user) {
  const confirmed = await askConfirm({ title: '确认删除用户', message: `删除后 ${user.account} 将立即无法登录，历史记录仍会保留。`, confirmText: '确认删除' })
  if (!confirmed) return
  try {
    await deleteUser(user.id)
    await loadAdminPage('users', adminPages.value.users.page)
    showNotice('用户已逻辑删除', '删除成功', 'success')
  } catch (error) {
    showNotice(readableError(error, '用户删除失败'), '删除用户失败')
  }
}

async function deleteSelectedOrganizationDepartment(department = selectedOrganizationDepartment.value) {
  if (organizationSaving.value) return
  if (!department) return
  const targetDepartmentId = department.id
  organizationSaving.value = true
  try {
    const confirmed = await askConfirm({ title: '确认删除部门', message: `确认删除“${department.name}”？存在下级部门或关联用户时将无法删除。`, confirmText: '确认删除' })
    if (!confirmed) return
    await deleteOrganizationDepartment(targetDepartmentId)
    organizationDepartments.value = organizationDepartments.value.filter((item) => item.id !== targetDepartmentId)
    if (organizationForm.value.id === targetDepartmentId) {
      organizationForm.value = emptyOrganizationForm()
      organizationMessage.value = ''
      closeOrganizationDialog(true)
    }
    showNotice('部门已逻辑删除', '删除成功', 'success')
  } catch (error) {
    showNotice(readableError(error, '部门删除失败'), '删除部门失败')
  } finally {
    organizationSaving.value = false
  }
}

function roleLabel(roleId) {
  return roles.value.find((role) => role.code === roleId)?.name || roleId || '-'
}

function roleNames(roleIds) {
  return (roleIds || []).filter(Boolean).map(roleLabel).join('、') || '-'
}

function dataScopeLabel(dataScope) {
  if (dataScope === 'ALL') return '全平台数据'
  if (dataScope === 'DEPARTMENT') return '部门数据'
  if (dataScope === 'PROJECT' || dataScope === 'SELF_PROJECT') return '项目数据'
  return '本人数据'
}

function toolStatusLabel(status) {
  if (status === 'normal') return '正常运行'
  if (status === 'maintenance') return '维护中'
  if (status === 'unopened') return '暂未开放'
  if (status === 'disabled') return '已停用'
  return status || '-'
}

function openCredentialDialog({ title, description, account, temporaryPassword }) {
  credentialDialog.value = { visible: true, title, description, account, temporaryPassword }
}

function closeCredentialDialog() {
  credentialDialog.value = { visible: false, title: '', description: '', account: '', temporaryPassword: '' }
}

async function copyTemporaryPassword() {
  try {
    await copyText(credentialDialog.value.temporaryPassword)
    showNotice('临时密码已复制', '复制成功', 'success')
  } catch {
    showNotice('浏览器未允许自动复制，请手动选择密码复制', '复制失败')
  }
}

function clientKeyPath(userId) {
  return `/api/admin/users/${encodeURIComponent(userId)}/relay-client-key`
}

async function openUserClientKeyDialog(user) {
  const generation = ++clientKeyLoadGeneration
  clientKeyDialog.value = { ...emptyClientKeyDialog(), visible: true, userId: user.id, account: user.account, loading: true }
  try {
    const status = await getJson(clientKeyPath(user.id))
    if (generation === clientKeyLoadGeneration) clientKeyDialog.value.status = status
  } catch (error) {
    if (generation === clientKeyLoadGeneration) clientKeyDialog.value.error = readableError(error, '读取客户端 Key 状态失败')
  } finally {
    if (generation === clientKeyLoadGeneration) clientKeyDialog.value.loading = false
  }
}

function closeUserClientKeyDialog() {
  if (clientKeyDialog.value.busy) return
  clientKeyLoadGeneration++
  clientKeyDialog.value = emptyClientKeyDialog()
}

async function rotateUserClientKey() {
  const dialog = clientKeyDialog.value
  if (dialog.busy || !dialog.status?.userEnabled || !dialog.status?.identityReady) return
  if (dialog.status.clientKeyStatus === 'active') {
    const confirmed = await askConfirm({ title: '确认轮换客户端 Key', message: `轮换后 ${dialog.account} 的旧客户端 Key 会立即失效。`, confirmText: '确认轮换' })
    if (!confirmed || clientKeyDialog.value !== dialog) return
  }
  dialog.busy = true
  dialog.error = ''
  try {
    const result = await postJson(clientKeyPath(dialog.userId), {})
    dialog.key = result.key
    dialog.status = { ...dialog.status, clientKeyStatus: 'active' }
  } catch (error) {
    dialog.error = readableError(error, '生成客户端 Key 失败')
  } finally {
    dialog.busy = false
  }
}

async function revokeUserClientKey() {
  const dialog = clientKeyDialog.value
  if (dialog.busy || dialog.status?.clientKeyStatus !== 'active') return
  const confirmed = await askConfirm({ title: '确认吊销客户端 Key', message: `吊销后 ${dialog.account} 的客户端 Key 会立即失效。`, confirmText: '确认吊销' })
  if (!confirmed || clientKeyDialog.value !== dialog) return
  dialog.busy = true
  dialog.error = ''
  try {
    await deleteJson(clientKeyPath(dialog.userId))
    dialog.status = { ...dialog.status, clientKeyStatus: 'absent' }
    dialog.key = ''
  } catch (error) {
    dialog.error = readableError(error, '吊销客户端 Key 失败')
  } finally {
    dialog.busy = false
  }
}

async function copyUserClientKey() {
  try {
    await copyText(clientKeyDialog.value.key)
    clientKeyDialog.value.copyMessage = '已复制客户端 Key'
  } catch {
    clientKeyDialog.value.copyMessage = '复制失败，请手动选择 Key 复制'
  }
}

async function copyClientKeyEndpoint(endpoint) {
  try {
    await copyText(endpoint.url)
    clientKeyDialog.value.copyMessage = `已复制${endpoint.label}链接`
  } catch {
    clientKeyDialog.value.copyMessage = '复制失败，请手动选择链接复制'
  }
}

function openUserRoleDialog(user) {
  if (userRoleSaving.value) return
  selectedRoleUser.value = user
  userRoleForm.value = { roleId: (user.roleIds || [user.role]).filter(Boolean)[0] || roles.value[0]?.code || '' }
  userRoleDialogVisible.value = true
}

function closeUserRoleDialog(force = false) {
  if (userRoleSaving.value && !force) return
  userRoleDialogVisible.value = false
  selectedRoleUser.value = null
  userRoleForm.value = { roleId: '' }
}

async function saveUserRoleChange() {
  if (userRoleSaving.value) return
  const user = selectedRoleUser.value
  const roleId = userRoleForm.value.roleId
  if (!user || !roleId) return
  const targetUserId = user.id
  userRoleSaving.value = true
  try {
    const confirmed = await askConfirm({
      title: '确认变更角色',
      message: `确认将 ${user.account} 的角色变更为 ${roleLabel(roleId)}？`,
      confirmText: '确认变更'
    })
    if (!confirmed) return
    const updated = await updateUserRoles(targetUserId, [roleId])
    users.value = users.value.map((item) => item.id === targetUserId ? { ...item, roleIds: updated.roleIds || [roleId], role: roleId } : item)
    if (selectedRoleUser.value?.id === targetUserId) closeUserRoleDialog(true)
    await loadServerData()
    await loadAdminPage('users', adminPages.value.users.page)
  } catch (error) {
    showNotice(readableError(error, '角色更新失败'), '更新用户角色失败')
  } finally {
    userRoleSaving.value = false
  }
}

function emptyRoleForm() {
  return {
    code: '',
    name: '',
    description: '',
    dataScope: 'SELF',
    permissionCodes: [],
    toolKeys: []
  }
}

function selectRole(role, force = false) {
  if (roleSaving.value && !force) return
  if (!role) {
    selectedRoleCode.value = ''
    roleForm.value = emptyRoleForm()
    roleDialogVisible.value = true
    return
  }
  selectedRoleCode.value = role.code
  roleForm.value = {
    code: role.code,
    name: role.name,
    description: role.description || '',
    dataScope: normalizeRoleDataScope(role.dataScope),
    permissionCodes: [...(role.permissionCodes || [])],
    toolKeys: [...(role.toolKeys || [])]
  }
  roleMessage.value = ''
  roleDialogVisible.value = true
}

function normalizeRoleDataScope(dataScope) {
  if (dataScope === 'SELF_PROJECT') return 'PROJECT'
  return ['ALL', 'DEPARTMENT', 'PROJECT', 'SELF'].includes(dataScope) ? dataScope : 'SELF'
}

function newRole() {
  if (roleSaving.value) return
  selectedRoleCode.value = ''
  roleForm.value = emptyRoleForm()
  roleMessage.value = ''
  roleDialogVisible.value = true
}

function closeRoleDialog(force = false) {
  if (roleSaving.value && !force) return
  roleDialogVisible.value = false
}

function toggleListValue(list, value) {
  const set = new Set(list || [])
  if (set.has(value)) set.delete(value)
  else set.add(value)
  return Array.from(set)
}

function togglePermission(code) {
  if (roleSaving.value) return
  roleForm.value.permissionCodes = toggleListValue(roleForm.value.permissionCodes, code)
}

function toggleRoleTool(toolKey) {
  if (roleSaving.value) return
  roleForm.value.toolKeys = toggleListValue(roleForm.value.toolKeys, toolKey)
}

async function saveRole() {
  if (roleSaving.value) return
  roleMessage.value = ''
  const sensitiveCodes = permissions.value
    .filter((permission) => permission.sensitive && roleForm.value.permissionCodes.includes(permission.code))
    .map((permission) => permission.name)
  const targetRoleCode = selectedRole.value?.code || ''
  const payload = {
    code: targetRoleCode || `role_${Date.now().toString(36)}`,
    name: roleForm.value.name.trim(),
    description: roleForm.value.description,
    dataScope: roleForm.value.dataScope,
    permissionCodes: [...roleForm.value.permissionCodes],
    toolKeys: [...roleForm.value.toolKeys]
  }
  roleSaving.value = true
  try {
    if (sensitiveCodes.length) {
      const confirmed = await askConfirm({
        title: '确认授予敏感权限',
        message: `你正在授予敏感权限：${sensitiveCodes.join('、')}。确认继续？`,
        confirmText: '继续保存'
      })
      if (!confirmed) return
    }
    const saved = targetRoleCode ? await updateRole(targetRoleCode, payload) : await createRole(payload)
    if (!targetRoleCode || selectedRoleCode.value === targetRoleCode) {
      roleMessage.value = '角色权限已保存'
      selectRole(saved, true)
    }
    closeRoleDialog(true)
    try {
      await loadServerData()
      await loadAdminPage('roles', adminPages.value.roles.page)
    } catch (error) {
      showNotice(readableError(error, '角色已保存，但列表刷新失败'), '刷新失败')
    }
  } catch (error) {
    if (!targetRoleCode || selectedRoleCode.value === targetRoleCode) {
      roleMessage.value = `保存失败：${readableError(error, '角色保存失败')}`
    }
  } finally {
    roleSaving.value = false
  }
}

async function deleteSelectedRole(role = selectedRole.value) {
  if (roleSaving.value) return
  if (!role || role.builtIn) return
  const targetRoleCode = role.code
  roleSaving.value = true
  try {
    const confirmed = await askConfirm({ title: '确认删除角色', message: `确认删除“${role.name}”？仍被用户引用时将无法删除。`, confirmText: '确认删除' })
    if (!confirmed) return
    await deleteRole(targetRoleCode)
    roles.value = roles.value.filter((item) => item.code !== targetRoleCode)
    const rolePage = adminPages.value.roles
    if (rolePage.loaded) {
      const previousItemCount = rolePage.items.length
      rolePage.items = rolePage.items.filter((item) => item.code !== targetRoleCode)
      if (rolePage.items.length < previousItemCount) {
        rolePage.total = Math.max(0, rolePage.total - 1)
        rolePage.totalPages = Math.ceil(rolePage.total / rolePage.pageSize)
        rolePage.page = Math.min(rolePage.page, Math.max(1, rolePage.totalPages))
      }
    }
    if (selectedRoleCode.value === targetRoleCode && roleForm.value.code === targetRoleCode) {
      selectedRoleCode.value = ''
      roleForm.value = emptyRoleForm()
      roleMessage.value = ''
      closeRoleDialog(true)
    }
    showNotice('角色已逻辑删除', '删除成功', 'success')
    await loadAdminPage('roles', rolePage.page)
  } catch (error) {
    showNotice(readableError(error, '角色删除失败'), '删除角色失败')
  } finally {
    roleSaving.value = false
  }
}

async function toggleRoleEnabled(role) {
  if (roleSaving.value) return
  const targetRoleCode = role.code
  roleSaving.value = true
  try {
    const updated = await updateRoleStatus(targetRoleCode, !role.enabled)
    roles.value = roles.value.map((item) => item.code === updated.code ? updated : item)
    const shouldHydrateEditor = roleDialogVisible.value && routeMode.value === 'admin' && adminSection.value === 'roles' && selectedRoleCode.value === targetRoleCode && roleForm.value.code === targetRoleCode
    if (shouldHydrateEditor) selectRole(updated, true)
    await loadAdminPage('roles', adminPages.value.roles.page)
  } catch (error) {
    showNotice(readableError(error, '角色状态更新失败'), '更新角色状态失败')
  } finally {
    roleSaving.value = false
  }
}

async function changeToolStatus(tool, status) {
  try {
    await updateToolStatus(tool.key, status)
        await loadServerData()
        await loadAdminPage('tools', adminPages.value.tools.page)
  } catch (error) {
    showNotice(error.message, '更新工具状态失败')
  }
}

async function setProjectStatus(project, status) {
  try {
    const updated = await updateProjectStatus(project.id, status)
    projects.value = projects.value.map((item) => item.id === updated.id ? updated : item)
    selectedProject.value = updated
    await loadServerData()
  } catch (error) {
    showNotice(error.message, '更新项目状态失败')
  }
}

function selectProject(project) {
  selectedProject.value = project
  projectSelectedForTool.value = Boolean(project.canRun)
  projectGateMessage.value = projectSelectedForTool.value
    ? ''
    : project.status === '进行中'
      ? '你可以查看该部门项目，但只有项目成员才能在项目内执行工具。'
      : '只有进行中的项目才能继续使用工具，请选择其他项目。'
}

function emptyProjectForm() {
  return {
    name: '', productName: '', departmentId: '', brand: '', ownerUserId: '', memberUserIds: [], category: '',
    salePrice: '', establishedDate: '', sellingPoints: ''
  }
}

function openProjectCreate() {
  editingProjectId.value = ''
  projectForm.value = emptyProjectForm()
  projectForm.value.departmentId = currentUser.value.dataScope === 'ALL'
    ? enabledOrganizationDepartments.value[0]?.id || ''
    : (currentUser.value.departmentKeys || []).find((id) => enabledOrganizationDepartments.value.some((department) => department.id === id)) || ''
  projectForm.value.ownerUserId = projectOwnerOptions.value.some((user) => user.id === currentUser.value.id)
    ? currentUser.value.id
    : projectOwnerOptions.value[0]?.id || ''
  projectForm.value.memberUserIds = projectForm.value.ownerUserId ? [projectForm.value.ownerUserId] : []
  projectCoverFile.value = null
  projectRemoveCover.value = false
  projectCreateMessage.value = ''
  showProjectCreate.value = true
}

function openProjectEditor(project) {
  editingProjectId.value = project.id
  projectForm.value = {
    name: project.name || '', productName: project.productName || '',
    departmentId: project.departmentId || departmentSelectOptions.value[0]?.value || '', brand: project.brand || '',
    ownerUserId: project.ownerUserId || '',
    memberUserIds: [...(project.memberUserIds || [])],
    category: project.category || '', salePrice: project.salePrice || '',
    establishedDate: project.establishedDate || '', sellingPoints: project.sellingPoints || project.summary || ''
  }
  projectCoverFile.value = null
  projectRemoveCover.value = false
  projectCreateMessage.value = ''
  showProjectCreate.value = true
}

function closeProjectEditor() {
  showProjectCreate.value = false
  editingProjectId.value = ''
  projectForm.value = emptyProjectForm()
  projectCoverFile.value = null
  projectRemoveCover.value = false
  projectCreateMessage.value = ''
}

function selectProjectCover(event) {
  const file = event.target.files?.[0] || null
  projectCreateMessage.value = ''
  if (file && (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024)) {
    projectCoverFile.value = null
    event.target.value = ''
    projectCreateMessage.value = '封面只支持 JPG、PNG、WebP，且不能超过 5MB'
    return
  }
  projectCoverFile.value = file
}

function selectProjectFromBoard(project) {
  selectProject(project)
  navigatePage('projects')
}

function projectDepartmentKey(project) {
  if (project.departmentId) return project.departmentId
  const task = projectTasks.value.find((item) => item.projectId === project.id)
  return flatTools.value.find((tool) => tool.key === task?.toolKey)?.departmentKey || ''
}

function projectDepartmentName(project) {
  return departmentName(projectDepartmentKey(project)) || '-'
}

function projectProgressFor(projectId) {
  const tasks = projectTasks.value.filter((task) => task.projectId === projectId)
  if (!tasks.length) return 0
  return Math.round(tasks.filter((task) => task.status === 'DONE').length * 100 / tasks.length)
}

function resultCountFor(projectId) {
  return countActiveProjectResults(projectResults.value, projectId)
}

function projectGenerationRecords(projectId) {
  return activeProjectResults(projectResults.value, projectId).map((result) => ({
    ...result,
    run: runRecords.value.find((run) => run.id === result.runId),
    snapshotRun: result.resultSnapshot?.run || {}
  }))
}

async function removeProjectGenerationRecord(result, project) {
  if (!project?.canManage || projectResultRemovingId.value) return
  const confirmed = await askConfirm({
    title: '移出项目生成记录',
    message: `确认将该生成记录移出“${project.name}”？记录、附件和运行详情仍会保留在运行记录中。`,
    confirmText: '移出项目'
  })
  if (!confirmed) return
  projectResultRemovingId.value = result.id
  try {
    await unlinkProject(result.id)
    projectResults.value = projectResults.value.filter((item) => item.id !== result.id)
    await loadServerData()
    if (selectedBoardProject.value?.id === project.id) {
      selectedBoardProject.value = projects.value.find((item) => item.id === project.id) || null
    }
    showNotice('生成记录已移出项目，运行记录仍完整保留', '解除关联成功', 'success')
  } catch (error) {
    showNotice(readableError(error, '解除关联失败'), '操作失败')
  } finally {
    projectResultRemovingId.value = ''
  }
}

async function removeManagedProject(project) {
  if (!project?.canManage || projectDeletingId.value) return
  const confirmed = await askConfirm({
    title: '删除项目',
    message: `确认删除“${project.name}”？项目将从列表中消失，所有历史生成记录、附件和 AI 日志仍会保留。`,
    confirmText: '确认删除'
  })
  if (!confirmed) return
  projectDeletingId.value = project.id
  try {
    await deleteProject(project.id)
    projects.value = projects.value.filter((item) => item.id !== project.id)
    projectTasks.value = projectTasks.value.filter((item) => item.projectId !== project.id)
    projectResults.value = projectResults.value.filter((item) => item.projectId !== project.id)
    if (selectedProject.value?.id === project.id) selectedProject.value = projects.value[0] || null
    if (selectedBoardProject.value?.id === project.id) selectedBoardProject.value = null
    await loadServerData()
    showNotice('项目已删除，历史生成记录仍可在运行记录中查看', '删除成功', 'success')
  } catch (error) {
    showNotice(readableError(error, '项目删除失败'), '删除失败')
  } finally {
    projectDeletingId.value = ''
  }
}

async function submitProject() {
  projectCreateMessage.value = ''
  const name = projectForm.value.name.trim()
  if (!name) {
    projectCreateMessage.value = '请填写项目名称'
    return
  }
  if (!projectForm.value.departmentId) {
    projectCreateMessage.value = '请选择项目所属部门'
    return
  }
  if (!projectForm.value.ownerUserId) {
    projectCreateMessage.value = '请选择项目负责人'
    return
  }
  projectCreating.value = true
  try {
    const memberUserIds = Array.from(new Set([
      projectForm.value.ownerUserId,
      ...(projectForm.value.memberUserIds || [])
    ].filter(Boolean)))
    const payload = {
      name,
      departmentId: projectForm.value.departmentId,
      ownerUserId: projectForm.value.ownerUserId,
      memberUserIds,
      summary: projectForm.value.sellingPoints,
      details: {
        productName: projectForm.value.productName,
        departmentId: projectForm.value.departmentId,
        brand: projectForm.value.brand,
        category: projectForm.value.category,
        salePrice: projectForm.value.salePrice,
        establishedDate: projectForm.value.establishedDate,
        sellingPoints: projectForm.value.sellingPoints
      }
    }
    const saved = editingProjectId.value
      ? await updateProject(editingProjectId.value, payload)
      : await createProject(payload)
    let withCover = saved
    if (editingProjectId.value && projectRemoveCover.value && !projectCoverFile.value) {
      withCover = await deleteProjectCover(saved.id)
    }
    if (projectCoverFile.value) withCover = await uploadProjectCover(saved.id, projectCoverFile.value)
    const normalized = { ...withCover, ...(withCover.details || {}) }
    projects.value = [normalized, ...projects.value.filter((item) => item.id !== normalized.id)]
    selectedProject.value = normalized
    projectSelectedForTool.value = true
    projectGateMessage.value = ''
    const wasEditing = Boolean(editingProjectId.value)
    closeProjectEditor()
    showNotice(wasEditing ? '项目资料已更新' : '项目已创建，可以开始使用工具', wasEditing ? '保存成功' : '创建成功', 'success')
    await loadServerData()
  } catch (error) {
    projectCreateMessage.value = `${editingProjectId.value ? '保存' : '创建'}失败：${readableError(error, '项目保存失败')}`
  } finally {
    projectCreating.value = false
  }
}

function openAllAttachments() {
  selectedRunAttachments.value.forEach((attachment) => window.open(attachment.url, '_blank', 'noopener'))
}

async function toggleModelConfig(config) {
  try {
    const updated = await updateModelConfig(config.id, { enabled: !config.enabled })
    modelConfigs.value = modelConfigs.value.map((item) => item.id === updated.id ? updated : item)
        await loadServerData()
        await loadAdminPage('models', adminPages.value.models.page)
  } catch (error) {
    showNotice(error.message, '更新模型配置失败')
  }
}

function openModelConfigEditor(config) {
  if (modelConfigSaving.value || !canWriteAdminSection(currentUser.value.permissions, 'models')) return
  modelConfigForm.value = modelConfigFormFrom(config)
  modelConfigMessage.value = ''
  modelConfigDialogVisible.value = true
}

function closeModelConfigEditor(force = false) {
  if (modelConfigSaving.value && !force) return
  modelConfigDialogVisible.value = false
  modelConfigMessage.value = ''
  modelConfigForm.value = emptyModelConfigForm()
}

async function submitModelConfigEdit() {
  if (modelConfigSaving.value || !canWriteAdminSection(currentUser.value.permissions, 'models')) return
  const form = modelConfigForm.value
  const validationError = validateModelConfigForm(form)
  if (validationError) {
    modelConfigMessage.value = validationError
    return
  }
  const targetConfigId = form.id
  const payload = buildModelConfigUpdatePayload(form)
  modelConfigSaving.value = true
  modelConfigMessage.value = ''
  let updated = null
  try {
    updated = await updateModelConfig(targetConfigId, payload)
    modelConfigs.value = modelConfigs.value.map((item) => item.id === updated.id ? updated : item)
    const modelPage = adminPages.value.models
    modelPage.items = modelPage.items.map((item) => item.id === updated.id ? updated : item)
  } catch (error) {
    modelConfigMessage.value = `保存失败：${readableError(error, '模型配置保存失败')}`
  } finally {
    modelConfigSaving.value = false
  }
  if (!updated) return
  if (modelConfigForm.value.id === targetConfigId) closeModelConfigEditor()
  showNotice('模型配置已更新', '保存成功', 'success')
  try {
    await loadAdminPage('models', adminPages.value.models.page)
  } catch (error) {
    showNotice(readableError(error, '模型配置已保存，但列表刷新失败'), '刷新失败')
  }
}

function focusModelConfig() {
  openAdminSection('models')
}

function openDept(deptKey) {
  const department = departments.value.find((item) => item.key === deptKey)
  if (!departmentHasAvailableTools(department)) return
  activeDept.value = deptKey
  navigateTo('/workbench', 'workbench', 'workbench')
}

function departmentHasAvailableTools(department) {
  return Boolean(department?.tools?.some((tool) => tool.status === 'normal'))
}

function ensureActiveDepartmentAvailable() {
  const current = departments.value.find((department) => department.key === activeDept.value)
  if (departmentHasAvailableTools(current)) return
  activeDept.value = departments.value.find(departmentHasAvailableTools)?.key
    || ''
}

async function openProjectTaskTool(task, project) {
  if (!project?.canRun) {
    showNotice(project?.status === '进行中'
      ? '只有项目成员才能执行项目工具'
      : '只有进行中的项目才能执行工具', '无法执行')
    return
  }
  const tool = flatTools.value.find((item) => item.key === task.toolKey)
  if (!tool || tool.status !== 'normal') {
    showNotice('该工具尚未接入', '无法执行')
    return
  }
  await openTool(tool, project)
}

async function openNewChat() {
  if (!canAccessNewChat.value) return
  try {
    await openNewChatWindow(window.open.bind(window), () => postJson('/api/tool-sessions/new-chat-entry', {}))
  } catch (error) {
    showNotice(readableError(error, 'new chat 登录失败，请稍后重试'), '打开 new chat 失败')
  }
}

async function openTool(tool, selectedProjectForRun = null) {
  if (tool.status !== 'normal') return
  const project = selectedProjectForRun
  if (!project && tool.key !== 'promo-image' && tool.key !== 'detail-page' && tool.key !== 'cloud-ai') {
    pendingTool.value = tool
    projectPickerProjectId.value = ''
    projectPickerKey.value += 1
    projectPickerVisible.value = true
    projectGateMessage.value = linkableProjects.value.length ? '' : '当前没有可用项目，请先新建项目。'
    projectCreateMessage.value = ''
    return
  }
  selectedProject.value = project
  if (tool.key === 'promo-image') {
    try {
      await openPromoImageWindow(
        window.open.bind(window),
        () => createToolSessionTicket('cloud-ai', project?.id),
        tool.entryUrl
      )
    } catch (error) {
      showNotice(readableError(error, '推广图工具入口创建失败'), '打开工具失败')
    }
    return
  }
  if (tool.key === 'cloud-ai') {
    const toolWindow = window.open('', '_blank')
    if (toolWindow) {
      toolWindow.opener = null
      toolWindow.document.title = 'AI 工具加载中'
      toolWindow.document.body.innerHTML = '<p style="font:16px sans-serif;padding:32px">正在打开 AI 工具...</p>'
    }
    try {
      const ticket = await createToolSessionTicket(tool.key, project?.id)
      const internalEntry = new URL(ticket.entryUrl, window.location.origin)
      const rawTicket = internalEntry.searchParams.get('ticket')
      if (internalEntry.pathname !== '/tools/cloud-ai/platform-entry' || !rawTicket) {
        throw new Error('AI 工具票据入口无效')
      }
      const externalEntry = new URL('platform-entry', `${tool.entryUrl.replace(/\/+$/, '')}/`)
      externalEntry.searchParams.set('ticket', rawTicket)
      if (toolWindow && !toolWindow.closed) toolWindow.location.href = externalEntry.toString()
      else window.location.href = externalEntry.toString()
    } catch (error) {
      if (toolWindow && !toolWindow.closed) toolWindow.close()
      showNotice(readableError(error, 'AI 工具入口创建失败'), '打开工具失败')
    }
    return
  }
  let toolWindow
  let runProjectId = project?.id
  if (tool.key === 'detail-page') {
    toolWindow = window.open('', '_blank')
    if (toolWindow) {
      toolWindow.opener = null
      toolWindow.document.title = '详情页工具加载中'
      toolWindow.document.body.innerHTML = '<p style="font:16px -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;padding:32px;color:#4b5563">正在打开详情页...</p>'
    }
    try {
      const entry = await resolveDetailPageEntry(runProjectId)
      runProjectId = entry.projectId
      if (entry.mode === 'workbench') {
        const internalEntry = new URL(entry.entryUrl, window.location.origin)
        const rawTicket = internalEntry.searchParams.get('ticket')
        if (internalEntry.pathname !== '/tools/detail-page-sso/platform-entry' || !rawTicket) {
          throw new Error('详情页工具票据入口无效')
        }
        const externalEntry = new URL('/platform-entry', tool.entryUrl)
        externalEntry.searchParams.set('ticket', rawTicket)
        if (toolWindow && !toolWindow.closed) toolWindow.location.href = externalEntry.toString()
        else window.location.href = externalEntry.toString()
        return
      }
      throw new Error('详情页只允许从工作台票据进入')
    } catch (error) {
      if (toolWindow && !toolWindow.closed) toolWindow.close()
      showNotice(readableError(error, '详情页工具入口创建失败'), '打开工具失败')
      return
    }
  }
  if (!toolWindow) toolWindow = window.open('', '_blank')
  if (toolWindow) {
    toolWindow.opener = null
    toolWindow.document.title = '工具加载中'
    toolWindow.document.body.innerHTML = '<p style="font:16px -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;padding:32px;color:#4b5563">正在创建工具运行记录...</p>'
  }
  let started
  try {
    started = await startToolRun({
      toolKey: tool.key,
      userId: currentUser.value.id,
      projectId: runProjectId,
      inputSummary: `打开 ${tool.name}`
    })
  } catch (error) {
    if (toolWindow && !toolWindow.closed) toolWindow.close()
    showNotice(readableError(error, '工具访问记录创建失败'), '打开工具失败')
    return
  }
  const params = new URLSearchParams({
    runId: started.runId,
    userId: currentUser.value.id,
    projectId: runProjectId
  })
  const toolUrl = resolveToolEntryUrl(tool, params)
  if (toolWindow && !toolWindow.closed) {
    toolWindow.location.href = toolUrl
  } else {
    window.location.href = toolUrl
    return
  }
  await loadServerData()
  selectedRun.value = {
    id: started.runId,
    time: formatTime(started.startedAt),
    type: started.eventType === 'open' ? '访问' : '运行',
    tool: tool.name,
    user: currentUser.value.name || currentUser.value.account,
    dept: activeDepartment.value.name,
    tokens: tool.callback ? '等待运行' : '仅访问',
    duration: '-',
    project: project?.name || '个人工具运行',
    status: started.status === 'failed' ? '失败' : started.status === 'success' ? '成功' : '进行中',
    input: started.inputSummary || '-',
    output: tool.callback ? '该工具支持结果回传，请通过 AI 网关和 tool-runs 接口完成运行上报。' : '该工具暂不支持结果回传，仅记录访问行为。',
    model: '-',
    cost: '-'
  }
}

function resolveToolEntryUrl(tool, params) {
  return `/tools/${encodeURIComponent(tool.key)}/?${params.toString()}`
}

function closeProjectPicker() {
  projectPickerVisible.value = false
  pendingTool.value = null
  projectPickerProjectId.value = ''
  projectPickerKey.value += 1
  projectGateMessage.value = ''
}

async function confirmProjectForTool() {
  const project = linkableProjects.value.find((item) => item.id === projectPickerProjectId.value)
  if (!project) {
    projectGateMessage.value = '请先选择一个进行中的项目'
    return
  }
  selectedProject.value = project
  projectSelectedForTool.value = true
  projectPickerVisible.value = false
  projectGateMessage.value = ''
  const tool = pendingTool.value
  pendingTool.value = null
  projectPickerProjectId.value = ''
  projectPickerKey.value += 1
  if (tool) await openTool(tool, project)
}

function goCreateProjectFromPicker() {
  projectPickerVisible.value = false
  pendingTool.value = null
  projectGateMessage.value = '请创建项目，创建完成后再从工作台打开工具。'
  navigatePage('projects')
  openProjectCreate()
}

async function runManifestCheck() {
  try {
    const request = await requestDeployment({
      toolKey: 'demo-static-tool',
      name: '静态工具示例',
      departmentKey: 'ai-market',
      entry: '/tools/demo-static-tool/index.html',
      integrationType: 'static',
      status: 'normal',
      callbackEnabled: true,
      aiGatewayRequired: true,
      requiredApis: ['/api/tool-runs/start', '/api/tool-runs/{runId}/finish', '/api/ai/chat/completions'],
      directModelEndpoints: [],
      version: '0.1.1',
      owner: 'admin'
    })
    deploymentRequests.value = [request, ...deploymentRequests.value.filter((item) => item.id !== request.id)]
    manifestCheck.value = { passed: request.status === 'approved', errors: request.errors || [] }
  } catch {
    manifestCheck.value = { passed: false, errors: ['后端未启动，无法执行服务端校验'] }
  }
}

function normalizeDepartments(items) {
  return items.map((dept) => ({
    ...dept,
    tools: (dept.tools || []).map((tool) => ({
      ...tool,
      callback: tool.callback ?? tool.callbackEnabled ?? false,
      theme: tool.theme || 'blue'
    }))
  }))
}

function normalizeRuns(items) {
  return sortRunRecordsNewestFirst(items.filter((run) => ['success', 'failed'].includes(run.status) && run.finishedAt)).map((run) => {
    const employee = runUsers.value[run.userId] || users.value.find((item) => item.id === run.userId)
    const primaryDepartmentId = employee?.primaryDepartmentId || employee?.departmentKeys?.[0] || ''
    const timestamp = splitRunTimestamp(run.finishedAt || run.startedAt)
    const totalTokens = run.outputData?.tokenUsage?.totalTokens ?? runTokenUsage.value[run.runId]
    const totalCredits = runCreditUsage.value[run.runId]
    const runProject = runProjects.value[run.projectId]
    return {
      id: run.runId,
      time: formatTime(run.finishedAt || run.startedAt),
      date: timestamp.date,
      clock: timestamp.clock,
      type: '运行',
      tool: toolName(run.toolKey),
      toolKey: run.toolKey,
      user: employee?.name || userName(run.userId),
      userId: run.userId,
      dept: primaryDepartmentId ? departmentName(primaryDepartmentId) : '未设置部门',
      deptKey: primaryDepartmentId,
      tokens: formatResourceUsage(totalTokens, totalCredits),
      duration: formatRunDuration(run.startedAt, run.finishedAt),
      project: runProject ? `${runProject.name}${runProject.deleted ? '（已删除）' : ''}` : projectName(run.projectId),
      status: run.status === 'success' ? '成功' : '失败',
      statusKey: run.status,
      input: runPrompts.value[run.runId] || run.inputSummary || '-',
      output: run.outputSummary || '-',
      model: run.outputData?.modelName || '-',
      cost: run.outputData?.costEstimate ? `¥${run.outputData.costEstimate}` : '-',
      outputData: run.outputData || {},
      hasOutputData: Boolean(run.hasOutputData),
      hasAttachments: Boolean(run.hasAttachments),
      hasImage: Boolean(run.hasImage),
      raw: run
    }
  })
}

function normalizeAiLogs(items) {
  return items.map((log) => {
    const billing = formatAiLogBilling(log)
    return {
      id: log.id,
      time: formatTime(log.createdAt),
      user: userName(log.userId),
      tool: toolName(log.toolKey),
      model: log.modelName,
      promptTokens: billing.promptTokens,
      completionTokens: billing.completionTokens,
      totalTokens: billing.totalTokens,
      cost: billing.cost,
      status: log.status === 'success' ? '成功' : '失败'
    }
  })
}

function toolName(toolKey) {
  for (const dept of departments.value) {
    const tool = dept.tools?.find((item) => item.key === toolKey)
    if (tool) return tool.name
  }
  return toolKey
}

function departmentNameByTool(toolKey) {
  for (const dept of departments.value) {
    if (dept.tools?.some((item) => item.key === toolKey)) return dept.name
  }
  return '-'
}

function departmentName(departmentKey) {
  return organizationDepartments.value.find((dept) => dept.id === departmentKey)?.name
    || departments.value.find((dept) => dept.key === departmentKey)?.name
    || departmentKey
}

function projectUserLabel(user) {
  const departmentLabels = (user.departmentIds || []).map(departmentName).filter(Boolean).join('、')
  return `${user.name}${user.account ? `（${user.account}）` : ''}${departmentLabels ? ` · ${departmentLabels}` : ''}`
}

function projectName(projectId) {
  return projects.value.find((project) => project.id === projectId)?.name || '-'
}

function departmentNames(keys) {
  return (keys || []).map(departmentName)
}

function userName(userId) {
  const user = users.value.find((item) => item.id === userId)
  if (user) return user.name || user.account
  if (currentUser.value.id === userId) return currentUser.value.name || currentUser.value.account
  return userId
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatToken(value) {
  if (value >= 10000) return `${Math.round(value / 1000)}K`
  return String(value)
}

function parseToken(value) {
  if (typeof value === 'number') return value
  const text = String(value || '0').trim().toUpperCase()
  const number = Number.parseFloat(text) || 0
  if (text.endsWith('M')) return Math.round(number * 1000000)
  if (text.endsWith('K')) return Math.round(number * 1000)
  return Math.round(number)
}

function statValue(key) {
  if (key === 'projectCount') return projects.value.length
  return stats.value[key] ?? '-'
}

function rateValue(key) {
  const value = stats.value[key]
  return value === undefined || value === null ? '-' : `${value}%`
}

function publicStatValue(key) {
  return publicStats.value[key] ?? '-'
}

function publicRateValue(key) {
  const value = publicStats.value[key]
  return value === undefined || value === null ? '-' : `${value}%`
}
</script>
