<template>
  <section class="relay-panel relay-calendar">
    <header class="relay-section-heading"><h2><CalendarDays :size="18" />Token消耗热力图</h2><span v-if="detailed" class="relay-caption">{{ days[0]?.day }} 至 {{ days.at(-1)?.day }}</span></header>
    <div class="relay-calendar-scroll">
      <div class="relay-month-axis" :style="columns"><span v-for="m in layout.months" :key="m.key" :style="{ gridColumn: m.column }">{{ m.label }}</span></div>
      <div class="relay-calendar-body"><div class="relay-week-axis"><span v-for="(label,i) in weekdays" :key="i">{{ detailed || i % 2 === 0 ? label : '' }}</span></div>
        <div class="relay-calendar-grid" :style="columns"><span v-for="(day,i) in layout.cells" :key="day?.day || i" :class="day ? `heat-${level(day.count)}` : 'heat-padding'" :tabindex="day ? 0 : undefined" :title="day ? `${day.day}：${tokens(day.count)} Tokens` : undefined" :aria-label="day ? `${day.day}：${day.count} Tokens` : undefined" /></div>
      </div>
    </div>
    <footer class="relay-calendar-footer"><span><Info :size="13" /> 颜色深浅代表当日个人消耗 Token 数量级（0 ~ {{ detailed ? '8' : '5' }}万+ Tokens）</span><span class="relay-scale">少 <i v-for="n in 5" :key="n" :class="`heat-${n-1}`" /> 多</span></footer>
    <div v-if="detailed" class="relay-calendar-facts"><span>消耗峰值日：<b>{{ peak.count ? peak.day : '—' }}</b> · {{ tokens(peak.count) }} Tokens</span><span>最长连续活跃工作日：<b>{{ streak }}</b> 天</span></div>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import { CalendarDays, Info } from 'lucide-vue-next'
import { tokens, calendarLayout, longestWorkdayStreak } from '../relayView.js'
const props=defineProps({days:{type:Array,default:()=>[]},detailed:Boolean})
const weekdays=['周一','周二','周三','周四','周五','周六','周日']
const layout=computed(()=>calendarLayout(props.days))
const columns=computed(()=>({'--weeks':layout.value.weeks}))
const peak=computed(()=>props.days.reduce((max,d)=>d.count>max.count?d:max,{day:'',count:0}))
const streak=computed(()=>longestWorkdayStreak(props.days))
function level(n){const upper=props.detailed?80000:50000;return n===0?0:n<upper*.2?1:n<upper*.4?2:n<upper?3:4}
</script>
