<template>
  <div class="relay-details">
    <p v-if="error" class="relay-error" role="alert">{{ error }}</p>
    <section v-if="!personal && data.quota" class="relay-panel relay-employee-profile">
      <div class="relay-profile-person"><span class="relay-avatar">{{ data.quota.name?.slice(0,1) }}</span><div><h2>{{ data.quota.name }}</h2><p>{{ data.quota.departmentName }}</p><span class="relay-badge" :class="data.quota.mode">{{ data.quota.mode==='independent'?'独立限额':'部门限额' }}</span></div></div>
      <article><span>本月 Token 消耗 / 限额</span><strong>{{ tokens(data.quota.used) }} <small>/ {{ tokens(data.quota.personalLimit) }}</small></strong><progress :value="percent(data.quota.used,data.quota.personalLimit)" max="100" aria-label="本月额度进度" /><small>输入 {{ tokens(monthTotals.inputTokens ?? 0) }} / 输出 {{ tokens(monthTotals.outputTokens ?? 0) }}</small></article>
      <article><span>本月请求次数</span><strong>{{ monthTotals.requests ?? 0 }} <small>次</small></strong><small>月内日均 {{ ((monthTotals.requests || 0)/Number(chinaDate().slice(-2))).toFixed(1) }} 次</small></article>
      <article><span>本月生成图片</span><strong>{{ monthTotals.imageCount ?? 0 }} <small>张</small></strong><small>&nbsp;</small></article>
    </section>
    <RelayHeatmap :days="heat" :detailed="!personal" />
    <section v-if="!personal" class="relay-panel"><header class="relay-section-heading"><h2><ChartColumn :size="18" />按月统计消耗量</h2><span class="relay-caption">最近 6 个月</span></header><div class="relay-month-cards"><article v-for="m in months.slice(0,6)" :key="m.month"><span>{{ m.month }}</span><strong>{{ tokens(m.tokens) }} <small>Tokens</small></strong></article></div></section>
    <section class="relay-panel"><header class="relay-section-heading"><h2><History :size="18" />消耗日志</h2><form v-if="personal" class="relay-log-filters" @submit.prevent="$emit('filter',{search:searchText,tool:filterTool})"><input v-model="searchText" placeholder="搜索我用过的工具或提示词…" aria-label="搜索消耗日志" /><UiSelect v-model="filterTool" :options="[{label:'全部 AI 工具',value:''},...(data.tools || []).filter(t=>t.key!=='未关联').map(t=>({label:t.key,value:t.key}))]" @update:model-value="$emit('filter',{search:searchText,tool:filterTool})" /><button class="btn-secondary">查询</button></form></header>
      <div class="relay-table-scroll"><table><thead><tr><th>{{ personal?'调用时间':'执行时间' }}</th><th>使用工具</th><th>关联项目</th><th v-if="personal">输入 Prompt 摘要</th><th>消耗 Token / 图片</th><th>响应耗时</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="log in data.logs || []" :key="log.sourceId"><td>{{ time(log.occurredAt) }}</td><td>{{ log.toolName || log.toolKey || '客户端调用' }}</td><td>{{ log.projectName || log.projectId || '未关联项目' }}</td><td v-if="personal" class="relay-prompt-summary" :title="log.promptSummary">{{ log.promptSummary || '—' }}</td><td><b>{{ tokens(log.totalTokens) }} Tokens</b><small v-if="log.imageCount">{{ log.imageCount }} 张图片</small></td><td>{{ (log.durationMs / 1000).toFixed(1) }}s</td><td><span class="relay-status-dot" />{{ ({recorded:'已计量',success:'成功',failed:'失败',running:'运行中'})[log.status] || log.status }}</td><td><button v-if="log.runId" class="relay-result-button" :disabled="busy" @click="selectedLog=log.sourceId;showResult()"><Eye :size="14" />{{ personal?'查看生成结果':'查看具体数据' }}</button><span v-else class="relay-caption">客户端调用</span><button v-if="canAudit" class="relay-link" :disabled="busy" @click="selectedLog=log.sourceId;showAudit(false)">审计</button></td></tr>
        <tr v-if="!data.logs?.length"><td :colspan="personal?8:7" class="relay-empty">所选周期暂无调用明细</td></tr>
      </tbody></table></div><div class="relay-pagination"><span>共 {{ data.logTotal || 0 }} 条</span><button class="btn-secondary" :disabled="(data.page || 1) <= 1" @click="$emit('page',(data.page || 1)-1)">上一页</button><span>{{ data.page || 1 }}</span><button class="btn-secondary" :disabled="(data.page || 1)*20 >= (data.logTotal || 0)" @click="$emit('page',(data.page || 1)+1)">下一页</button></div>
    </section>
  </div>
  <DetailDrawer :open="!!payload" :title="payloadKind === 'audit' ? '网关完整调用审计' : personal ? '我的历史生成结果详情' : '具体输入与产出'" stacked wide @close="payload=null">
    <p v-if="payload?.complete === false" class="relay-error">此记录未完整保存，不能作为完整调用凭证。</p>
    <template v-if="payloadKind==='result'"><section class="relay-result-card"><h4>我当时输入的 Prompt 与参数</h4><pre class="relay-payload">{{ payload?.inputSummary || '暂无输入摘要' }}</pre></section><section class="relay-result-card"><h4>AI 返回的完整产出内容</h4><div v-if="resultAttachments.length" class="relay-result-attachments"><a v-for="attachment in resultAttachments" :key="attachment.key" :href="attachment.url" target="_blank" rel="noopener noreferrer" :download="attachment.name"><img v-if="attachment.kind==='image'" :src="attachment.url" :alt="attachment.name" /><span>{{ attachment.name }}</span></a></div><pre class="relay-payload">{{ relayResultText(payload) }}</pre></section></template>
    <pre v-else class="relay-payload">{{ JSON.stringify(payload,null,2) }}</pre>
    <template #footer><button class="btn-secondary" @click="payload=null">关闭</button><button v-if="payloadKind==='audit'" class="btn-secondary" @click="showAudit(true)">导出审计凭证</button><button class="btn-primary" @click="copy">{{ copied ? '已复制' : '一键复制结果' }}</button></template>
  </DetailDrawer>
</template>
<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { tokens, percent, chinaDate, readableRelayError } from '../relayView.js'
import { getJson } from '../api.js'
import { copyText } from '../clipboard.js'
import { relayResultAttachments, relayResultText } from '../relayResult.js'
import RelayHeatmap from './RelayHeatmap.vue'
import UiSelect from './UiSelect.vue'
import { ChartColumn, History, Eye } from 'lucide-vue-next'
import DetailDrawer from './DetailDrawer.vue'
const props=defineProps({data:{type:Object,default:()=>({})},heat:{type:Array,default:()=>[]},canAudit:{type:Boolean,default:false},personal:Boolean,monthTotals:{type:Object,default:()=>({})}})
defineEmits(['page','filter'])
const searchText=ref(''),filterTool=ref('')
const selectedLog=ref(''),payload=ref(null),payloadKind=ref(''),error=ref(''),busy=ref(false),copied=ref(false)
const activeLog=computed(()=>props.data.logs?.find(l=>l.sourceId===selectedLog.value))
const resultAttachments=computed(()=>relayResultAttachments(payload.value))
let generation=0
function invalidate(){generation++;payload.value=null;error.value='';busy.value=false}
watch(()=>props.data,()=>{invalidate();selectedLog.value=props.data.logs?.[0]?.sourceId || ''},{immediate:true,flush:'sync'})
watch(selectedLog,invalidate,{flush:'sync'})
onBeforeUnmount(invalidate)
async function showResult(){
 const log=activeLog.value;if(!log?.runId||busy.value)return
 const run=++generation;busy.value=true;error.value=''
 try{const result=await getJson(`/api/tool-runs/${encodeURIComponent(log.runId)}`);if(run!==generation)return;payload.value=result;payloadKind.value='result';copied.value=false}
 catch(e){if(run===generation)error.value=readableRelayError(e)}finally{if(run===generation)busy.value=false}
}
async function showAudit(download){
 const log=activeLog.value;if(!log||busy.value)return
 const run=++generation;busy.value=true;error.value=''
 try{const result=await getJson(`/api/admin/relay/audit/${encodeURIComponent(log.sourceId)}?export=${download}`);if(run!==generation)return
  if(download){const url=URL.createObjectURL(new Blob([JSON.stringify(result,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download=`relay-audit-${log.sourceId}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
  else{payload.value=result;payloadKind.value='audit';copied.value=false}
 }catch(e){if(run===generation)error.value=readableRelayError(e)}finally{if(run===generation)busy.value=false}
}
async function copy(){try{await copyText(JSON.stringify(payloadKind.value==='result' ? payload.value?.outputData || payload.value?.outputSummary : payload.value,null,2));copied.value=true}catch{error.value='复制失败，请手动选择内容复制'}}
const months=computed(()=>{const sums=new Map();for(const d of props.heat)sums.set(d.day.slice(0,7),(sums.get(d.day.slice(0,7))||0)+d.count);return [...sums].sort(([a],[b])=>b.localeCompare(a)).map(([month,tokens])=>({month,tokens}))})
function time(value){return new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}
</script>
