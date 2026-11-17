<template>
  <div v-if="node.kind === 'value'" class="return-value usage-prose" v-html="renderUsageMarkdown(node.text)"></div>
  <UsageImage v-else-if="node.kind === 'image'" :src="node.src" />
  <p v-else-if="node.kind === 'limited'" class="return-hint">内容较长，完整数据请展开下方原始请求与响应。</p>
  <div v-else class="return-fields" :class="{ 'return-list': node.kind === 'list' }">
    <p v-if="!node.children.length" class="return-hint">{{ node.kind === 'list' ? '空列表' : '空对象' }}</p>
    <div v-for="(field,index) in node.children" :key="index" class="return-field" :class="{'nested-field':field.node.kind !== 'value'}"><div class="return-label">{{ field.label }}</div><UsageStructuredValue :node="field.node" /></div>
    <p v-if="node.limited" class="return-hint">其余内容保留在下方原始数据中。</p>
  </div>
</template>
<script setup>
import UsageImage from './UsageImage.vue'
import { renderUsageMarkdown } from '../aiUsageContent.js'
defineProps({node:{type:Object,required:true}})
</script>
<style scoped>
.return-fields{min-width:0}.return-field{display:grid;grid-template-columns:minmax(90px,130px) minmax(0,1fr);gap:14px;padding:10px 0;border-bottom:1px solid #edf0f5;align-items:baseline}.return-field:last-of-type{border-bottom:0}.return-label{font-size:12px;color:#64748b;overflow-wrap:anywhere;line-height:1.8}.return-value{font-size:14px;line-height:1.8;overflow-wrap:anywhere;min-width:0}.return-value :deep(p){margin:0;white-space:pre-wrap}.return-value :deep(pre){white-space:pre;overflow:auto;max-width:100%;padding:12px;background:#f1f5f9}.return-value :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse}.return-value :deep(th),.return-value :deep(td){border:1px solid #e2e8f0;padding:8px}.return-value :deep(img){max-width:100%;max-height:320px;object-fit:contain}.return-value :deep(a){color:#4f46e5;text-decoration:underline}.nested-field{display:block}.nested-field>.return-label{font-weight:600;margin-bottom:4px;color:#475569}.nested-field>.return-fields{padding-left:12px;border-left:2px solid #eef2ff}.return-hint{font-size:12px;color:#64748b;line-height:1.7}.return-list>.return-field{display:block}.return-list>.return-field>.return-label{font-weight:600;margin-bottom:6px}@media(max-width:480px){.return-field{grid-template-columns:minmax(70px,95px) minmax(0,1fr);gap:10px}}
</style>
