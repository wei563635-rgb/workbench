<template>
  <svg v-if="layout.total" class="relay-flow" :viewBox="`0 0 980 ${layout.height}`" role="img" aria-label="部门、工具和模型的 Token 桑基图">
    <path v-for="link in layout.links" :key="link.id" :d="link.path" :stroke-width="Math.max(.5,link.width)" stroke="#a5b4fc" stroke-opacity=".38" fill="none"><title>{{ name(link.from) }} → {{ name(link.to) }}：{{ tokens(link.value) }} Tokens</title></path>
    <g v-for="node in layout.nodes" :key="node.id" tabindex="0" :aria-label="`${name(node.label)}：${tokens(node.value)} Tokens`"><title>{{ name(node.label) }}：{{ tokens(node.value) }} Tokens</title><rect :x="node.x" :y="node.y" width="12" :height="Math.max(1,node.height)" rx="2" :fill="colors[node.column]" /><text :x="node.x+18" :y="node.y+node.height/2+4" stroke="white" stroke-width="3" paint-order="stroke">{{ name(node.label).slice(0,23) }}</text></g>
  </svg>
  <p v-else class="relay-empty">暂无可展示的流向</p>
</template>
<script setup>
import {computed} from 'vue'
import {layoutRelayFlow} from '../relayFlow.js'
import {tokens} from '../relayView.js'
const props=defineProps({flows:{type:Array,default:()=>[]},departments:{type:Array,default:()=>[]}})
const layout=computed(()=>layoutRelayFlow(props.flows))
const colors=['#6366f1','#0d9488','#8b5cf6']
function name(id){return String(props.departments.find(d=>d.id===id)?.name||id||'未关联')}
</script>
