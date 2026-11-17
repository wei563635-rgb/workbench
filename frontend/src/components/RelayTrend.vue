<template>
  <section class="relay-panel"><header class="relay-section-heading"><h2><ChartColumn :size="18" />Token 消耗趋势</h2><div class="relay-periods"><button :class="{selected:mode==='hourly'}" @click="mode='hourly'">24h 各时段趋势</button><button :class="{selected:mode==='daily'}" @click="mode='daily'">日 Token 消耗柱状图</button></div></header>
    <div class="relay-trend-chart"><div class="relay-trend-axis"><span v-for="n in [4,3,2,1,0]" :key="n">{{ tokens(max*n/4) }}</span></div><div class="relay-trend-bars"><div v-for="(row,i) in rows" :key="row.key" :title="`${row.label}：${tokens(row.tokens)} Tokens`" tabindex="0"><span :style="{height:`${Number(row.tokens)/max*100}%`}"/><small>{{ i%labelStep===0 ? row.label : '' }}</small></div><p v-if="!hasUsage" class="relay-trend-empty">所选时间内暂无中转消耗</p></div></div>
    <p class="relay-caption">{{ mode==='hourly'?'按北京时间汇总所选周期各时段用量':'按北京时间统计每日用量' }}</p>
  </section>
</template>
<script setup>
import {computed,ref} from 'vue'
import {ChartColumn} from 'lucide-vue-next'
import {tokens} from '../relayView.js'
const props=defineProps({daily:{type:Array,default:()=>[]},hourly:{type:Array,default:()=>[]}})
const mode=ref('hourly')
const rows=computed(()=>mode.value==='hourly'?Array.from({length:24},(_,i)=>({key:i,label:`${String(i).padStart(2,'0')}:00`,tokens:props.hourly.find(d=>Number(d.key)===i)?.tokens || 0})):props.daily.map(d=>({...d,label:d.key.slice(5)})))
const max=computed(()=>Math.max(1,...rows.value.map(d=>Number(d.tokens))))
const hasUsage=computed(()=>rows.value.some(d=>Number(d.tokens)>0))
const labelStep=computed(()=>Math.max(1,Math.ceil(rows.value.length/12)))
</script>
