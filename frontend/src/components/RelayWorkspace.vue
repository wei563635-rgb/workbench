<template>
  <section class="relay-workspace" :class="`relay-${mode}`">
    <header class="relay-heading"><h1>{{ titles[mode] }}</h1>
      <div v-if="mode !== 'quotas'" class="relay-date-controls">
        <div class="relay-date-picker"><CalendarDays :size="15" /><span v-if="mode!=='personal'" class="relay-date-label">自选时间段</span><input v-model="range.from" type="date" aria-label="开始日期" /><span>至</span><input v-model="range.to" type="date" aria-label="结束日期" /><button :disabled="loading" @click="applyRange">{{ mode==='personal'?'筛选':'查询' }}</button></div>
        <div class="relay-periods"><button v-for="p in presets" :key="p.value" :class="{selected:preset===p.value}" :disabled="loading" @click="setRange(p.value)">{{ mode==='personal'?p.label:({today:'今日实时',week:'近 7 天',month:'本月累计'}[p.value]) }}</button></div>
      </div>
    </header>
    <p v-if="loading" role="status" class="relay-caption">正在加载…</p>
    <p v-if="error" class="relay-error" role="alert">{{ error }}</p>
    <p v-if="sync.status === 'failed'" class="relay-error" role="alert">{{ sync.lastError || '配置同步失败，系统稍后自动重试；请联系管理员检查中转服务' }}</p>
    <p v-for="a in report.alerts || []" :key="`${a.scope}:${a.scopeId}`" class="relay-warning" role="status">{{ reason(a.reason) }} · {{ a.scope === 'department' ? departmentName(a.scopeId) : (report.employees || []).find(u=>u.id===a.scopeId)?.name || a.scopeId }}</p>
    <template v-if="mode === 'quotas'">
      <div class="table-card relay-panel"><h2><Building2 :size="22" />部门限额与并发</h2>
        <div class="relay-table-scroll"><table><thead><tr><th>所属部门</th><th>部门人数</th><th>单人月度限额</th><th>单人默认并发</th><th>部门月度总限额</th><th>公共池本月已用</th><th v-if="canWrite">操作</th></tr></thead><tbody>
          <tr v-for="d in configuration.departments || []" :key="d.id" :style="departmentStyle(d.id)"><td><span class="relay-department"><component :is="departmentIcon(d.id)" :size="18" /><b>{{ d.name }}</b></span></td><td>{{ d.memberCount }} 人<small>{{ d.independentCount }} 人独立限额</small></td><td class="relay-quota-number">{{ tokens(d.personalLimit) }}</td><td><b>{{ d.concurrency == null ? '未配置' : `${d.concurrency} 并发` }}</b></td><td><b>{{ tokens(d.poolLimit) }}</b></td><td><strong>{{ tokens(d.used) }}</strong><span class="relay-limit-muted"> / {{ tokens(d.poolLimit) }} ({{ percent(d.used,d.poolLimit).toFixed(1) }}%)</span><progress :value="percent(d.used, d.poolLimit)" max="100" :aria-label="`${d.name}公共池进度`" /></td><td v-if="canWrite"><button class="relay-quota-edit" :aria-label="`编辑${d.name}限额`" :disabled="loading" @click="editDepartment(d)"><Pencil :size="16" /></button></td></tr>
          <tr v-if="!configuration.departments?.length"><td colspan="7" class="relay-empty">暂无一级部门，请先维护组织架构</td></tr>
        </tbody></table></div>
      </div>
      <div class="table-card relay-panel"><h2><UsersRound :size="22" />用户限额与并发</h2>
        <div class="relay-quota-filters"><label class="relay-quota-search"><Search :size="17" /><input v-model="search" placeholder="输入姓名搜索" aria-label="输入姓名搜索" /></label><label>所属部门<UiSelect aria-label="筛选部门" placeholder="全部部门" v-model="department" :options="departmentOptions" /></label><label>限额<UiSelect aria-label="筛选限额类型" placeholder="全部限额" v-model="quotaMode" :options="modeOptions" /></label><label>超额规则<UiSelect v-model="excessRule" placeholder="全部规则" :options="[{label:'全部规则',value:''},{label:'超额暂停调用',value:'block'}]" aria-label="筛选超额规则" /></label></div>
        <div class="relay-table-scroll"><table><thead><tr><th>姓名</th><th>所属一级部门</th><th>限额类型</th><th>月度限额</th><th>最大并发</th><th>本月已用</th><th>超额规则</th><th v-if="canWrite">操作</th></tr></thead><tbody>
          <tr v-for="u in quotaEmployees.slice((quotaPage-1)*20,quotaPage*20)" :key="u.id" :style="departmentStyle(u.departmentId)"><td><span class="relay-person"><i>{{ u.name?.slice(0,1) }}</i>{{ u.name }}</span></td><td><span class="relay-department"><b>{{ u.departmentName }}</b></span></td><td><span class="relay-badge" :class="u.mode">{{ modeLabel(u.mode) }}</span></td><td :class="{'relay-independent-number':u.mode==='independent'}"><b>{{ tokens(u.personalLimit) }}</b></td><td><b>{{ u.concurrency == null ? '未配置' : `${u.concurrency} 并发` }}</b></td><td :class="{'relay-near-limit':u.personalLimit!=null && percent(u.used,u.personalLimit)>=90}"><b>{{ tokens(u.used) }}</b><span class="relay-limit-muted"> / {{ tokens(u.personalLimit) }} ({{ percent(u.used,u.personalLimit).toFixed(1) }}%)</span><progress :value="percent(u.used,u.personalLimit)" max="100" :aria-label="`${u.name}月度用量`" /><small v-if="u.decision && !u.decision.allowed">{{ reason(u.decision.reason) }}</small></td><td><span class="relay-excess-rule"><i />超额暂停调用</span></td><td v-if="canWrite"><button class="relay-quota-edit" :aria-label="`编辑${u.name}限额`" :disabled="loading" @click="editEmployee(u)"><Pencil :size="16" /></button></td></tr>
          <tr v-if="!quotaEmployees.length"><td colspan="8" class="relay-empty">没有匹配的员工</td></tr>
        </tbody></table></div>
        <div class="relay-pagination"><span>共 {{ quotaEmployees.length }} 人</span><button class="btn-secondary" :disabled="quotaPage<=1" @click="quotaPage--">上一页</button><button v-for="p in quotaPageNumbers" :key="p" class="relay-page-number" :class="{selected:p===quotaPage}" :disabled="p===quotaPage" :aria-current="p===quotaPage?'page':undefined" @click="quotaPage=p">{{ p }}</button><button class="btn-secondary" :disabled="quotaPage>=quotaPages" @click="quotaPage++">下一页</button></div>
      </div>
    </template>
    <template v-else>
      <section v-if="mode === 'personal'" class="relay-quota-card">
        <div class="relay-quota-top"><h2><BatteryMedium :size="19" />我的本月可用 Token 额度</h2><span><CalendarCheck :size="15" /> 下次自动重置时间：<b>{{ time(report.resetsAt) }}</b></span></div>
        <div class="relay-quota-values"><div><strong>{{ tokens(report.quota?.decision?.personalRemaining) }}</strong><span>剩余可用 Tokens / 总额度 <b>{{ tokens(report.quota?.personalLimit) }} Tokens</b> (已用 {{ quotaPercent }}%)</span></div><b>已消耗：{{ tokens(report.quota?.used ?? 0) }} Tokens</b></div>
        <progress :value="quotaPercent" max="100" aria-label="个人月度额度进度" />
      </section>
      <section v-if="mode === 'dashboard'" class="relay-pool-banner">
        <article class="relay-pool-health"><div class="relay-pool-heading"><h3><ShieldCheck :size="18" />账号健康度</h3><span class="relay-pool-pill">健康率 {{ poolSummary?.total ? sharePercent(poolSummary.healthy,poolSummary.total).toFixed(1)+'%' : '—' }}</span></div><strong>{{ poolSummary?.healthy ?? '—' }} <small>/ {{ poolSummary?.total ?? '—' }} 个健康账号</small></strong><progress :value="poolSummary?.total ? sharePercent(poolSummary.healthy,poolSummary.total) : 0" max="100" aria-label="账号健康率" /><p>当前共配置 {{ poolSummary?.total ?? '—' }} 个账号 · {{ poolSummary?.healthy ?? '—' }} 个健康可用</p></article>
        <article v-for="window in poolWindows" :key="window.key"><div class="relay-pool-heading"><h3><BatteryMedium :size="18" />正常账号{{ window.label }}平均剩余</h3><span class="relay-pool-pill" :class="{'relay-pool-unavailable':!window.data?.accountCount}">{{ window.data?.accountCount ? `${window.data.accountCount} 个账号` : '暂无数据' }}</span></div><strong>{{ windowAverageValue(window.data) }} <small>平均剩余</small></strong><progress v-if="window.data?.accountCount" :value="window.data.averageRemainingPercent" max="100" :aria-label="`${window.label}平均剩余百分比`" /><div v-else class="relay-pool-track"/><p>{{ windowAverageNote(window.data) }}</p></article>
      </section>
      <div v-if="mode !== 'employees'" class="relay-metrics" :class="{'relay-metrics-three':mode==='personal'}">
        <article v-for="m in visibleMetrics" :key="m.key"><div class="relay-metric-top"><span>{{ mode==='dashboard'?m.dashboardLabel:m.label }}</span><i :class="m.key"><component :is="m.icon" :size="18" /></i></div><div class="relay-metric-value"><strong>{{ m.format ? tokens(report.totals?.[m.key] ?? 0) : (report.totals?.[m.key] ?? 0) }}</strong><span>{{ m.key==='activeUsers' ? `/ ${report.employeeTotal || 0} 人` : m.unit }}</span></div>
          <div class="relay-metric-foot"><span v-if="mode==='personal' && m.key==='requests'" :title="'当前用量来源未包含全部失败请求，不能推算成功率'">成功率 {{ report.totals?.successRate == null ? '暂无数据' : `${report.totals.successRate}%` }}</span><span v-else-if="m.key==='requests'">{{ growth('requests') }} · 峰值 {{ report.totals?.peakRequestsPerMinute ?? '—' }} 次/分</span><span v-else-if="m.key==='totalTokens'">输入 {{ tokens(report.totals?.inputTokens ?? 0) }} / 输出 {{ tokens(report.totals?.outputTokens ?? 0) }}<template v-if="report.totals?.cacheTokens"> / 缓存 {{ tokens(report.totals.cacheTokens) }}</template></span><span v-else-if="m.key==='activeUsers'">活跃率 {{ sharePercent(report.totals?.activeUsers,report.employeeTotal).toFixed(1) }}% · 人均消耗 {{ tokens((report.totals?.totalTokens || 0)/Math.max(1,report.totals?.activeUsers || 0)) }} Tokens</span><span v-else-if="mode==='dashboard' && m.key==='imageCount'">{{ growth('imageCount') }}</span></div><p v-if="mode==='dashboard' && m.key==='totalTokens'" class="relay-caption">{{ growth('totalTokens') }}</p>
        </article>
      </div>
      <template v-if="mode === 'dashboard'">
        <div class="relay-charts-row"><RelayTrend :daily="report.daily || []" :hourly="report.hourly || []" />
        <div class="table-card relay-panel"><h2>Token 流向分布</h2><p class="relay-caption">一级部门 → AI 工具或客户端调用 → 上游模型</p>
          <RelayFlow :flows="report.flows || []" :departments="configuration.departments || []" />
        </div>
        </div>
        <div class="relay-columns"><div v-for="group in [{key:'departments',title:'各部门 Token 消耗'},{key:'tools',title:'各 AI 工具 Token 消耗排行'}]" :key="group.key" class="table-card relay-panel"><h2>{{ group.title }}</h2><div v-for="row in group.key === 'departments' ? departmentConsumption.items : [...(report.tools || [])].sort((a,b)=>b.tokens-a.tokens)" :key="row.key" class="relay-breakdown"><div><span>{{ group.key === 'departments' ? departmentName(row.key) : row.key === '未关联' ? '客户端调用' : row.key }}</span><b>{{ tokens(row.tokens) }} Tokens</b></div><div><small>{{ row.requests || 0 }} 次请求<span v-if="group.key==='departments'"> · {{ row.activeUsers || 0 }} 人活跃</span></small><small>{{ sharePercent(row.tokens,report.totals?.totalTokens).toFixed(1) }}%</small></div><progress :value="sharePercent(row.tokens,report.totals?.totalTokens)" max="100" aria-label="消耗占比" /></div><p v-if="group.key === 'departments' ? !departmentConsumption.total : !report.tools?.length" class="relay-empty">暂无数据</p><div v-if="group.key === 'departments' && departmentConsumption.total > 0" class="relay-pagination"><span>共 {{ departmentConsumption.total }} 个有消耗部门</span><button class="btn-secondary" :disabled="departmentConsumption.page <= 1" @click="departmentPage = departmentConsumption.page - 1">上一页</button><button v-for="p in departmentPageNumbers" :key="p" class="relay-page-number" :class="{selected:p===departmentConsumption.page}" :disabled="p===departmentConsumption.page" :aria-current="p===departmentConsumption.page?'page':undefined" @click="departmentPage=p">{{ p }}</button><button class="btn-secondary" :disabled="departmentConsumption.page >= departmentConsumption.pages" @click="departmentPage = departmentConsumption.page + 1">下一页</button></div></div></div>
      </template>
      <div v-if="mode !== 'personal'" class="table-card relay-panel relay-employees-panel"><h2 v-if="mode === 'dashboard'">Token 消耗排行榜</h2><div v-if="mode==='employees'" class="relay-filters"><label class="relay-employee-search"><Search :size="17" /><input v-model="employeeSearch" type="search" placeholder="输入员工姓名搜索" aria-label="搜索员工姓名" /></label><label>所属部门<UiSelect aria-label="筛选部门" placeholder="全部部门" v-model="department" :options="departmentOptions" @update:model-value="page=1;load()" /></label><label>限额<UiSelect aria-label="筛选限额类型" placeholder="全部限额" v-model="quotaMode" :options="modeOptions" @update:model-value="page=1;load()" /></label><label>排序方式<UiSelect aria-label="排序方式" v-model="sort" :options="sortOptions" @update:model-value="page=1;load()" /></label></div>
        <div class="relay-table-scroll"><table><thead><tr>
          <th v-if="mode==='employees'"><input type="checkbox" :checked="allVisibleSelected" :disabled="!visibleEmployees.length" aria-label="选择当前页全部员工" @change="toggleVisible($event.target.checked)" /></th>
          <th v-if="mode==='dashboard'">排名</th><th>员工姓名</th><th>所属部门</th>
          <template v-if="mode==='dashboard'"><th>消耗占比</th><th>调用次数</th><th>Token 消耗</th><th>图片生成</th></template>
          <template v-else><th>限额</th><th>Token 消耗数 / 限额</th><th>生成图片</th><th>调用次数</th><th>最近交互时间</th></template>
          <th>操作</th>
        </tr></thead><tbody><tr v-for="(u,index) in visibleEmployees" :key="u.id">
          <td v-if="mode==='employees'"><input type="checkbox" :checked="selectedCompare.some(item=>item.id===u.id)" :aria-label="`选择${u.name}进行对比`" @change="toggleCompare(u,$event.target.checked)" /></td>
          <td v-if="mode==='dashboard'"><span class="relay-rank-number">{{ (page-1)*20+index+1 }}</span></td>
          <td><span class="relay-person"><i>{{ u.name?.slice(0,1) }}</i>{{ u.name }}</span></td><td><span :class="{'relay-department':mode==='employees'}">{{ u.departmentName }}</span></td>
          <template v-if="mode==='dashboard'"><td>{{ sharePercent(u.totalTokens,report.totals?.totalTokens).toFixed(1) }}%<progress :value="sharePercent(u.totalTokens,report.totals?.totalTokens)" max="100" aria-label="消耗占比" /></td><td>{{ u.requests }}</td><td><strong>{{ tokens(u.totalTokens) }}</strong></td><td>{{ u.imageCount }}</td></template>
          <template v-else><td><span class="relay-badge" :class="u.mode">{{ modeLabel(u.mode) }}</span></td><td class="relay-employee-usage" :class="{'relay-near-limit':u.personalLimit!=null && percent(u.used,u.personalLimit)>=90}"><strong>{{ tokens(u.used) }}</strong><span> / {{ tokens(u.personalLimit) }} ({{ u.personalLimit==null?'—':percent(u.used,u.personalLimit).toFixed(1)+'%' }})</span><progress v-if="u.personalLimit!=null" :value="percent(u.used,u.personalLimit)" max="100" :aria-label="`${u.name}本月额度进度`" /><small v-if="preset!=='month'">所选时间消耗 {{ tokens(u.totalTokens) }}</small></td><td>{{ u.imageCount }} 张</td><td>{{ u.requests }} 次</td><td>{{ time(u.lastActive) }}</td></template>
          <td><button class="btn-secondary" @click="openEmployee(u)">详情</button></td>
        </tr><tr v-if="!visibleEmployees.length"><td :colspan="mode==='employees'?9:8" class="relay-empty">暂无匹配员工</td></tr></tbody></table></div>
        <div v-if="mode==='employees'" class="relay-pagination"><span>共 {{ report.employeeTotal || 0 }} 人</span><button class="btn-secondary" :disabled="page <= 1 || loading" @click="page--;load()">上一页</button><button v-for="p in pageNumbers" :key="p" class="relay-page-number" :class="{selected:p===page}" :disabled="loading || p===page" :aria-current="p===page?'page':undefined" @click="page=p;load()">{{ p }}</button><button class="btn-secondary" :disabled="page>=employeePages || loading" @click="page++;load()">下一页</button></div>
      </div>
      <template v-if="mode === 'personal'"><RelayDetails personal :data="report" :heat="personalHeat" @filter="logFilter=$event;page=1;load()" @page="page=$event;load()" /></template>
    </template>
    <footer class="relay-data-footer"><span>{{ sync.lastSync ? `用量更新于 ${time(sync.lastSync)}` : '尚无同步用量' }} · 限额仅作用于中转站</span><button v-if="['employees','dashboard'].includes(mode)" class="relay-link" :disabled="loading || exporting" @click="exportEmployees">导出员工统计</button></footer>
    <div v-if="mode==='employees' && selectedCompare.length" class="relay-compare-bar"><strong>已选 {{ selectedCompare.length }} / 8 名员工</strong><span v-for="u in selectedCompare" :key="u.id">{{ u.name }} <button :aria-label="`移除${u.name}`" @click="toggleCompare(u,false)">×</button></span><button class="btn-secondary" @click="selectedCompare=[]">清空选择</button><button class="btn-primary" :disabled="selectedCompare.length<2" @click="compareOpen=true">开始横向对比</button></div>
    <RelayCompare v-if="compareOpen" :selected="selectedCompare" :initial-range="range" @close="compareOpen=false" @update:selected="selectedCompare=$event" />
    <DetailDrawer :open="!!selected" :title="`${selected?.name || ''} · Token 消耗详情`" wide @close="selected=null"><p v-if="detailError" role="alert" class="relay-error">{{ detailError }}</p><p v-if="detailLoading">加载中…</p><RelayDetails v-else :data="detail" :heat="detailHeat" :month-totals="detailMonth" :can-audit="permissions.includes('admin:relay-audit:read')" @page="loadDetail($event)" /></DetailDrawer>
    <AppDialog :open="!!editor" :title="editor?.kind === 'department' ? `编辑【${editor.name}】限额与并发` : `编辑【${editor?.name || ''}】限额与并发`" :close-disabled="saving" @close="editor=null">
      <template v-if="editor"><p v-if="editError" class="relay-error" role="alert">{{ editError }}</p>
        <div v-if="editor.kind === 'employee'" class="relay-policy-choices"><label v-for="option in modeOptions.slice(1)" :key="option.value" :class="{selected:editor.mode===option.value}"><input type="radio" v-model="editor.mode" :value="option.value" :disabled="saving" /><span><b>{{ option.label }}</b><small>{{ option.value==='inherited'?'使用所属一级部门的默认限额与并发，共享部门额度':'自定义个人额度与并发，不占用部门公共池' }}</small></span></label></div>
        <div class="relay-editor-grid"><label class="relay-field">{{ editor.kind === 'department' ? '单人月度限额' : '月度限额' }}（万 Tokens）<input :disabled="saving || (editor.kind==='employee' && editor.mode==='inherited')" v-model.number="editor.limitWan" type="number" min="0" max="900000000" step="0.0001" /></label><label class="relay-field">{{ editor.kind === 'department' ? '单人默认并发' : '最大并发' }}<input :disabled="saving || (editor.kind==='employee' && editor.mode==='inherited')" v-model.number="editor.concurrency" type="number" min="1" max="100" /></label></div>
        <label v-if="editor.kind === 'department'" class="relay-field">部门月度公共池（万 Tokens）<input :disabled="saving" v-model.number="editor.poolWan" type="number" min="0" max="900000000" step="0.0001" /></label>

        <label v-if="editor.kind === 'employee'" class="relay-field">超额规则<UiSelect model-value="block" :options="[{label:'暂停新调用',value:'block'}]" disabled /></label>
        <p v-if="editor.kind === 'department'" class="relay-warning">保存后将按新配置计算本月剩余额度，已用量不会清零。降低限额或公共池额度可能暂停新的调用。</p>
        <p v-else class="relay-warning">保存后将重新计算本月额度。恢复部门限额会把该员工整月用量纳入公共池，可能触发整个部门暂停新调用。个人已用不会清零。</p>
      </template>
      <template #footer><button class="btn-secondary" :disabled="saving" @click="editor=null">取消</button><button class="btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存配置' }}</button></template>
    </AppDialog>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CalendarDays, CalendarCheck, BatteryMedium, Zap, Coins, WandSparkles, Users, ShieldCheck, Building2, UsersRound, Search, Globe2, PenTool, ChartNoAxesCombined, Video, Pencil } from 'lucide-vue-next'
import { getJson, getBlob, putJson } from '../api.js'
import { tokens, percent, sharePercent, rangeFor, heatmap, readableRelayError, consumptionDepartmentPage } from '../relayView.js'
import { windowAverageValue, windowAverageNote } from '../upstreamWindowAverage.js'
import UiSelect from './UiSelect.vue'
import AppDialog from './AppDialog.vue'
import DetailDrawer from './DetailDrawer.vue'
import RelayDetails from './RelayDetails.vue'
import RelayFlow from './RelayFlow.vue'
import RelayTrend from './RelayTrend.vue'
import RelayCompare from './RelayCompare.vue'
const props=defineProps({mode:{type:String,default:'dashboard'},permissions:{type:Array,default:()=>[]}})
const titles={quotas:'限额与并发配置',dashboard:'Token 消耗看板',employees:'用户 Token 消耗',personal:'我的数据'}
const modeOptions=[{label:'全部限额',value:''},{label:'部门限额',value:'inherited'},{label:'独立限额',value:'independent'}]
const sortOptions=[{label:'Token 消耗从高到低',value:'tokens'},{label:'请求次数从高到低',value:'requests'},{label:'最近调用',value:'recent'}]
const presets=[{label:'今日',value:'today'},{label:'近 7 天',value:'week'},{label:'本月',value:'month'}]
const metrics=[{key:'requests',label:'请求次数',dashboardLabel:'请求量',unit:'次',icon:Zap},{key:'totalTokens',label:'Token 消耗',dashboardLabel:'Token 消耗总数',unit:'Tokens',format:true,icon:Coins},{key:'imageCount',label:'生成视觉图片',dashboardLabel:'图片生成数量',unit:'张',icon:WandSparkles},{key:'activeUsers',label:'活跃用户',dashboardLabel:'活跃用户数',unit:'人',icon:Users}]
const loading=ref(false),error=ref(''),configuration=ref({}),report=ref({}),range=ref(rangeFor('month')),preset=ref('month'),page=ref(1)
const departmentPage=ref(1)
const departmentConsumption=computed(()=>consumptionDepartmentPage(report.value.departments,departmentPage.value))
const employeeSearch=ref(''),selectedCompare=ref([]),compareOpen=ref(false)
const visibleEmployees=computed(()=>(report.value.employees || []))
const quotaPages=computed(()=>Math.max(1,Math.ceil(quotaEmployees.value.length/20)))
const employeePages=computed(()=>Math.max(1,Math.ceil((report.value.employeeTotal || 0)/20)))
function nearbyPages(current,total){const start=Math.max(1,Math.min(current-2,total-4));return Array.from({length:Math.min(total,5)},(_,index)=>start+index)}
const quotaPageNumbers=computed(()=>nearbyPages(quotaPage.value,quotaPages.value))
const pageNumbers=computed(()=>nearbyPages(page.value,employeePages.value))
const departmentPageNumbers=computed(()=>nearbyPages(departmentConsumption.value.page,departmentConsumption.value.pages))
const allVisibleSelected=computed(()=>visibleEmployees.value.length>0&&visibleEmployees.value.every(u=>selectedCompare.value.some(item=>item.id===u.id)))
function toggleCompare(user,checked){if(checked&&selectedCompare.value.length>=8&&!selectedCompare.value.some(item=>item.id===user.id)){error.value='每次最多对比 8 名员工';return}error.value='';selectedCompare.value=checked?selectedCompare.value.some(item=>item.id===user.id)?selectedCompare.value:[...selectedCompare.value,{id:user.id,name:user.name}]:selectedCompare.value.filter(item=>item.id!==user.id)}
function toggleVisible(checked){for(const user of visibleEmployees.value)toggleCompare(user,checked)}
const excessRule=ref('')
const logFilter=ref({search:'',tool:''})
const quotaPage=ref(1)
const exporting=ref(false),poolSummary=ref(null)
const poolWindows=computed(()=>[
  {key:'5h',label:'5小时',data:poolSummary.value?.window5h},
  {key:'7d',label:'7天',data:poolSummary.value?.window7d}
])
const visibleMetrics=computed(()=>props.mode==='personal'?metrics.slice(0,3):metrics)
const quotaPercent=computed(()=>Number(percent(report.value.quota?.used || 0,report.value.quota?.personalLimit).toFixed(1)))
function applyRange(){if(!range.value.from||!range.value.to||range.value.from>range.value.to){error.value='请选择有效的起止日期';return}preset.value='';page.value=1;departmentPage.value=1;load()}
const department=ref(''),quotaMode=ref(''),search=ref(''),sort=ref('tokens'),editor=ref(null),saving=ref(false),editError=ref('')
const detailMonth=ref({})
const selected=ref(null),detail=ref({}),detailHeat=ref([]),personalHeat=ref([]),detailError=ref(''),detailLoading=ref(false)
let generation=0,detailGeneration=0
const canWrite=computed(()=>props.permissions.includes('admin:relay-quota:write'))
const sync=computed(()=>props.mode==='quotas'?configuration.value.sync || {}:report.value.sync || {})
const departmentOptions=computed(()=>[{label:'全部部门',value:''},...(configuration.value.departments || []).map(d=>({label:d.name,value:d.id}))])
const quotaEmployees=computed(()=>(configuration.value.employees || []).filter(u=>(!department.value||u.departmentId===department.value)&&(!quotaMode.value||u.mode===quotaMode.value)&&(!search.value||u.name.includes(search.value))))
watch([department,quotaMode,search],()=>{quotaPage.value=1})
function growth(key){const prior=Number(report.value.previousTotals?.[key] || 0);if(!prior)return '较上期 —';const current=Number(report.value.totals?.[key] || 0);const change=(current-prior)/prior*100;return `较上期 ${change>=0?'+':''}${change.toFixed(1)}%`}
const departmentTones=[['#635bff','#eef2ff'],['#e91e63','#fef2f2'],['#10b981','#ecfdf5'],['#8b5cf6','#f5f3ff']]
function departmentIndex(id){return Math.max(0,(configuration.value.departments || []).findIndex(d=>d.id===id))%departmentTones.length}
function departmentStyle(id){const tone=departmentTones[departmentIndex(id)];return {'--department-color':tone[0],'--department-tint':tone[1]}}
function departmentIcon(id){return [Globe2,PenTool,ChartNoAxesCombined,Video][departmentIndex(id)]}
function modeLabel(mode){return mode==='independent'?'独立限额':'部门限额'}
function time(value){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'—'}
function reason(code){return {OK:'额度可用',PERSONAL_QUOTA_EXHAUSTED:'个人月度额度已耗尽',DEPARTMENT_QUOTA_EXHAUSTED:'部门公共池已耗尽'}[code] || code}
function departmentName(id){return configuration.value.departments?.find(d=>d.id===id)?.name || (id==='未关联'?'历史归属未知':id)}
function query(extra={}){return new URLSearchParams({...range.value,page:String(page.value),pageSize:props.mode==='dashboard'?'10':'20',departmentId:department.value,mode:quotaMode.value,sort:sort.value,employeeName:props.mode==='employees'?employeeSearch.value.trim():'',...logFilter.value,...extra}).toString()}
async function exportEmployees(){if(exporting.value)return;exporting.value=true;error.value='';try{const blob=await getBlob(`/api/admin/relay/usage/export?${query()}`);const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='员工中转消耗统计.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(e){error.value=readableRelayError(e)}finally{exporting.value=false}}
async function load(){const run=++generation;loading.value=true;error.value='';try{
 if(props.mode==='quotas'){const data=await getJson('/api/admin/relay/quotas');if(run===generation)configuration.value=data}
 else{const path=props.mode==='personal'?'/api/relay/me':'/api/admin/relay/usage';const data=await getJson(`${path}?${query()}`);if(run!==generation)return;report.value=data
  if(props.mode==='personal'){const year=await getJson(`${path}?${new URLSearchParams({...rangeFor('year'),pageSize:'1'})}`);if(run===generation)personalHeat.value=heatmap(year.daily)}
  else {configuration.value={departments:data.departmentDirectory || []};if(props.mode==='dashboard'){try{const summary=await getJson('/api/admin/relay/usage/pool-summary');if(run===generation)poolSummary.value=summary}catch{if(run===generation)poolSummary.value=null}}}
 }
}catch(e){if(run===generation)error.value=readableRelayError(e)}finally{if(run===generation)loading.value=false}}
function setRange(value){preset.value=value;range.value=rangeFor(value);page.value=1;departmentPage.value=1;load()}
function editDepartment(d){editError.value='';editor.value={...d,kind:'department',limitWan:d.personalLimit==null?'':d.personalLimit/10000,poolWan:d.poolLimit==null?'':d.poolLimit/10000,concurrency:d.concurrency || 1}}
function editEmployee(u){editError.value='';editor.value={...u,kind:'employee',limitWan:u.personalLimit==null?'':u.personalLimit/10000,concurrency:u.concurrency || 1}}
async function save(){if(saving.value||!editor.value)return;const e={...editor.value};editError.value='';const limit=Math.round(Number(e.limitWan)*10000),pool=Math.round(Number(e.poolWan)*10000)
 if((e.kind==='department'||e.mode==='independent')&&(e.limitWan===''||!Number.isSafeInteger(limit)||limit<0||!Number.isInteger(e.concurrency)||e.concurrency<1||e.concurrency>100)){editError.value='请填写有效的月度额度及 1～100 的整数并发';return}
 if(e.kind==='department'&&(e.poolWan===''||!Number.isSafeInteger(pool)||pool<0)){editError.value='请填写有效的部门公共池额度';return}
 saving.value=true;try{const body=e.kind==='department'?{id:e.id,personalLimit:limit,poolLimit:pool,concurrency:e.concurrency}:{id:e.id,mode:e.mode,personalLimit:e.mode==='independent'?limit:0,concurrency:e.concurrency || 1,relayUserId:e.relayUserId || null};configuration.value=await putJson(`/api/admin/relay/quotas/${e.kind==='department'?'departments':'employees'}/${encodeURIComponent(e.id)}`,body);editor.value=null}catch(err){editError.value=readableRelayError(err)}finally{saving.value=false}}
async function openEmployee(u){selected.value=u;detail.value={};detailHeat.value=[];detailMonth.value={};await loadDetail(1)}
async function loadDetail(p){const run=++detailGeneration;const id=selected.value?.id;if(!id)return;detailLoading.value=true;detailError.value='';try{const data=await getJson(`/api/admin/relay/usage?${query({userId:id,page:String(p),departmentId:'',mode:'',employeeName:''})}`);const year=await getJson(`/api/admin/relay/usage?${new URLSearchParams({...rangeFor('year'),userId:id,pageSize:'1'})}`);if(run===detailGeneration&&selected.value?.id===id){detail.value={...data,page:p};detailHeat.value=heatmap(year.daily);const month=await getJson(`/api/admin/relay/usage?${new URLSearchParams({...rangeFor('month'),userId:id,pageSize:'1'})}`);if(run===detailGeneration&&selected.value?.id===id)detailMonth.value=month.totals || {}}}catch(e){if(run===detailGeneration)detailError.value=readableRelayError(e)}finally{if(run===detailGeneration)detailLoading.value=false}}
watch(()=>props.mode,()=>{page.value=1;selected.value=null;selectedCompare.value=[];compareOpen.value=false;report.value={};configuration.value={};load()},{immediate:true})
watch(employeeSearch,(_value,_old,onCleanup)=>{if(props.mode!=='employees')return;const timer=setTimeout(()=>{page.value=1;load()},250);onCleanup(()=>clearTimeout(timer))})
</script>

<style src="../relay.css"></style>
