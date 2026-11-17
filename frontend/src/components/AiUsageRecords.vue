<template>
  <section class="usage-records admin-panel table-card" aria-label="AI 使用记录">
    <header class="panel-header"><div><h3>使用记录</h3><span>查看 Key 与工作台的 AI 调用、输入和产出</span></div><button class="btn-secondary" :disabled="loading" @click="load(1)">刷新记录</button></header>
    <div class="usage-tabs" role="group" aria-label="调用来源"><button v-for="tab in channels" :key="tab.value" :aria-pressed="channel === tab.value" :class="{ selected: channel === tab.value }" @click="channel=tab.value;load(1)">{{ tab.label }}</button></div>
    <form class="usage-filters" @submit.prevent="load(1)">
      <label>开始日期<input v-model="filters.from" type="date" required /></label><label>结束日期<input v-model="filters.to" type="date" required /></label>
      <label>用户<input v-model="filters.user" placeholder="姓名或账号" maxlength="100" /></label><label>部门<select v-model="filters.departmentId"><option value="">全部部门</option><option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option></select></label>
      <label>模型<input v-model="filters.model" placeholder="全部模型" maxlength="200" /></label><label>工具标识<input v-model="filters.toolKey" placeholder="全部工具" maxlength="100" /></label>
      <label>状态<select v-model="filters.status"><option value="">全部状态</option><option v-for="(label,key) in usageStatusLabels" :key="key" :value="key">{{ label }}</option></select></label>
      <label>请求 ID<input v-model="filters.requestId" placeholder="精确查询" maxlength="180" /></label><button class="btn-primary" :disabled="loading">查询</button>
    </form>
    <p v-if="error" class="usage-error" role="alert">{{ error }}</p>
    <p class="usage-caption">{{ result.coverage || '历史记录仅展示已保存的数据；缺失正文和用量会明确标注。' }}</p>
    <p class="usage-caption">中转最近同步：{{ usageTime(result.sync?.last_sync) }}<span v-if="result.sync?.last_error" class="usage-error"> · {{ result.sync.last_error }}</span></p>
    <div class="usage-table-scroll" :aria-busy="loading"><table><thead><tr><th>调用时间</th><th>用户 / 部门</th><th>来源 / 工具</th><th>模型 / 供应商</th><th>状态</th><th>Token（输入 / 输出 / 缓存）</th><th>图片</th><th>耗时</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in result.items || []" :key="row.id"><td>{{ usageTime(row.occurredAt) }}</td><td>{{ row.userName || (row.userId ? row.userId : '身份未识别') }}<small>{{ row.departmentName || '部门未记录' }}</small></td><td>{{ row.channel === 'key' ? 'Key 调用' : '工作台调用' }}<small>{{ row.toolKey || '未关联工具' }}</small></td><td>{{ row.model || '未提供' }}<small>{{ row.provider || '未记录' }}</small></td><td><span class="usage-state" :class="row.status">{{ usageStatusLabels[row.status] || '结果未知' }}</span></td><td>{{ usageValue(row.inputTokens) }} / {{ usageValue(row.outputTokens) }} / {{ usageValue(row.cacheTokens) }}</td><td>{{ usageValue(row.imageCount, ' 张') }}</td><td>{{ row.durationMs == null ? '未提供' : (row.durationMs/1000).toFixed(2)+' 秒' }}</td><td><button class="usage-detail-button" @click="showDetail(row.id)">查看详情</button></td></tr>
      <tr v-if="!result.items?.length"><td colspan="9" class="table-empty">{{ loading ? '正在加载使用记录…' : error ? '记录加载失败，请重试' : '所选条件下暂无使用记录' }}</td></tr>
    </tbody></table></div>
    <footer class="usage-pagination"><span>共 {{ result.total || 0 }} 条</span><button class="btn-secondary" :disabled="loading || page <= 1" @click="load(page-1)">上一页</button><span>第 {{ page }} 页</span><button class="btn-secondary" :disabled="loading || page*20 >= (result.total || 0)" @click="load(page+1)">下一页</button></footer>
  </section>
  <DetailDrawer :open="detailOpen" title="AI 调用详情" wide @close="closeDetail">
    <p v-if="detailError" class="usage-error" role="alert">{{ detailError }}</p><p v-else-if="detailLoading">正在加载详情…</p>
    <template v-else-if="detail">
      <div class="usage-overview"><strong>{{ detail.model || '模型未记录' }}</strong><span class="usage-state" :class="detail.status">{{ usageStatusLabels[detail.status] || '结果未知' }}</span><span>{{ detail.userName || detail.userId || '身份未识别' }}</span></div>
      <details class="usage-metadata"><summary>调用信息</summary>
      <dl class="usage-facts"><template v-for="fact in detailFacts" :key="fact[0]"><dt>{{ fact[0] }}</dt><dd>{{ fact[1] }}</dd></template></dl>
      </details>
      <p v-if="!detail.complete || detail.payload?.complete === false" class="usage-notice">此记录正文不完整或完整性无法核实，不能作为完整调用凭证。</p>
      <p v-if="detail.payload?.reason && detail.payload?.available !== false" class="usage-notice">{{ detail.payload.reason }}</p>
      <p v-if="detail.payload?.available === false" class="usage-notice">{{ detail.payload.reason }}</p>
      <AiUsageContent v-else :key="detail.id" :payload="detail.payload || {}" />
    </template>
  </DetailDrawer>
</template>
<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { getJson } from '../api.js'
import DetailDrawer from './DetailDrawer.vue'
import AiUsageContent from './AiUsageContent.vue'
import { usageStatusLabels, usageValue, usageTime, usageRange, usageCreditDetail } from '../aiUsageView.js'
defineProps({departments:{type:Array,default:()=>[]}})
const chinaDate = date => new Date(date.getTime()+8*3600000).toISOString().slice(0,10)
const now = new Date()
const filters=ref({from:chinaDate(new Date(now.getTime()-6*86400000)),to:chinaDate(now),user:'',departmentId:'',model:'',toolKey:'',status:'',requestId:''})
const channels=[{value:'',label:'全部'},{value:'key',label:'Key 调用'},{value:'workbench',label:'工作台调用'}]
const channel=ref(''),result=ref({}),page=ref(1),loading=ref(false),error=ref('')
const detailOpen=ref(false),detail=ref(null),detailLoading=ref(false),detailError=ref('')
let generation=0,detailGeneration=0
async function load(nextPage=1){const run=++generation;loading.value=true;error.value='';try{const {from,to,...rest}=filters.value;const query=new URLSearchParams({...rest,...usageRange(from,to),channel:channel.value,page:String(nextPage),pageSize:'20'});const response=await getJson(`/api/admin/ai-usage-records?${query}`);if(run!==generation)return;result.value=response;page.value=nextPage}catch(e){if(run===generation){error.value=e.message || '使用记录加载失败';result.value={}}}finally{if(run===generation)loading.value=false}}
async function showDetail(id){const run=++detailGeneration;detailOpen.value=true;detail.value=null;detailError.value='';detailLoading.value=true;try{const response=await getJson(`/api/admin/ai-usage-records/${encodeURIComponent(id)}`);if(run===detailGeneration)detail.value=response}catch(e){if(run===detailGeneration)detailError.value=e.message || '详情加载失败'}finally{if(run===detailGeneration)detailLoading.value=false}}
function closeDetail(){detailGeneration++;detailOpen.value=false;detail.value=null;detailLoading.value=false}
const detailFacts=computed(()=>{const r=detail.value||{};return [['调用 ID',r.id],['请求 ID',r.requestId||'未记录'],['来源',r.channel==='key'?'Key 调用':'工作台调用'],['用户',r.userName || r.userId || '身份未识别'],['账号',r.account || '未记录'],['部门',r.departmentName || '未记录'],['模型',r.model||'未提供'],['供应商',r.provider||'未记录'],['状态',usageStatusLabels[r.status]||'结果未知'],['HTTP 状态',r.httpStatus??'未提供'],['调用时间',usageTime(r.occurredAt)],['输入 Token',usageValue(r.inputTokens)],['输出 Token',usageValue(r.outputTokens)],['缓存 Token',usageValue(r.cacheTokens)],['图片',usageValue(r.imageCount)],['积分消耗',usageCreditDetail(r.payload)],['耗时',r.durationMs==null?'未提供':`${r.durationMs} 毫秒`],['项目',r.projectId||'未关联'],['运行记录',r.runId||'未关联']]})
onMounted(()=>load(1))
onBeforeUnmount(()=>{generation++;detailGeneration++})
</script>
<style scoped>
.usage-records{padding:24px;min-width:0}.usage-tabs{display:flex;gap:8px;margin:20px 0}.usage-tabs button{padding:9px 18px;border:1px solid #dce3ec;background:#fff;border-radius:6px;color:#475569;cursor:pointer}.usage-tabs button.selected{background:#eef4ff;border-color:#2563eb;color:#1d4ed8}.usage-filters{display:flex;flex-wrap:wrap;gap:12px;align-items:end;margin-bottom:16px}.usage-filters label{display:grid;gap:6px;font-size:12px;color:#64748b}.usage-filters input,.usage-filters select{box-sizing:border-box;width:155px;min-height:36px;border:1px solid #dce3ec;border-radius:5px;padding:7px;background:#fff;color:#334155}.usage-caption{font-size:12px;color:#64748b;line-height:1.7}.usage-error{color:#b91c1c}.usage-table-scroll{overflow:auto;margin-top:16px}.usage-table-scroll table{min-width:1100px;width:100%;font-size:12px}.usage-table-scroll td,.usage-table-scroll th{padding:14px 10px;text-align:left;white-space:nowrap;border-bottom:1px solid #e8edf3}.usage-table-scroll th{color:#64748b;background:#f8fafc}.usage-table-scroll small{display:block;color:#64748b;margin-top:5px}.usage-state{padding:4px 8px;background:#f1f5f9;border-radius:4px}.usage-state.success{color:#166534;background:#ecfdf5}.usage-state.failed,.usage-state.rejected{color:#b91c1c;background:#fef2f2}.usage-state.running{color:#1d4ed8;background:#eff6ff}.usage-detail-button{border:0;background:transparent;color:#2563eb;cursor:pointer}.usage-pagination{display:flex;justify-content:end;align-items:center;gap:14px;margin-top:20px;font-size:13px}.usage-overview{display:flex;flex-wrap:wrap;align-items:center;gap:12px;font-size:13px;margin-bottom:14px}.usage-overview strong{font-size:16px}.usage-metadata{padding:12px 0;border-bottom:1px solid #e2e8f0}.usage-metadata summary{color:#64748b;font-size:13px;cursor:pointer}.usage-facts{display:grid;grid-template-columns:100px minmax(0,1fr);gap:10px;font-size:13px}.usage-facts dt{color:#64748b}.usage-facts dd{margin:0;overflow-wrap:anywhere}.usage-notice{padding:12px;background:#fffbeb;color:#92400e;font-size:13px}.usage-records button:focus-visible,.usage-records input:focus-visible,.usage-records select:focus-visible{outline:2px solid #2563eb;outline-offset:2px}@media(max-width:600px){.usage-records{padding:14px}.usage-filters label{flex:1 1 42%}.usage-filters input,.usage-filters select{width:100%}.usage-pagination{justify-content:start;flex-wrap:wrap}}
</style>
