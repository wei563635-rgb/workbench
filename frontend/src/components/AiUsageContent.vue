<template>
  <div class="usage-content">
    <section v-for="section in sections" :key="section.key" class="content-section" :aria-label="section.title">
      <h4>{{ section.title }}</h4>
      <article v-for="(entry, index) in section.rows" :key="index" class="content-message" :class="{ 'input-message': section.key === 'input' }">
        <div class="message-role">{{ entry.role }}</div>
        <UsageStructuredValue v-if="entry.structured" :node="entry.structured" />
        <div v-else-if="entry.text" class="usage-prose" v-html="renderUsageMarkdown(entry.text)"></div>
        <p v-if="entry.text.length > USAGE_TEXT_LIMIT" class="content-caption">正文较长，当前渲染前 {{ USAGE_TEXT_LIMIT.toLocaleString() }} 个字符，完整内容保留在下方原始数据中。</p>
        <div v-if="entry.images?.length" class="content-images"><UsageImage v-for="(url,index) in entry.images" :key="index" :src="url" alt="输入图片" /></div>
      </article>
      <article v-if="section.fallback != null" class="content-message"><p class="content-caption">{{ section.key === 'output' ? (content.metadataOnly ? '此历史记录仅保存任务状态和用量，未保存 AI 回答正文。' : '返回信息') : '暂未识别此内容格式，以下保留原文。' }}</p><UsageStructuredValue v-if="section.structured" :node="section.structured" /><pre v-else class="content-fallback">{{ usageRawText(section.fallback) }}</pre></article>
      <p v-else-if="!section.rows.length && !(section.key === 'output' && (images.length || content.errors.length))" class="content-caption">{{ section.key === 'input' ? '此记录未保存可展示的输入正文。' : '此记录未保存可展示的回答正文。' }}</p>
      <template v-if="section.key === 'output'">
        <article v-for="(entry, index) in content.errors" :key="`error-${index}`" class="content-error"><div class="message-role">调用错误</div><div class="usage-prose" v-html="renderUsageMarkdown(entry.text)"></div></article>
        <div v-if="images.length" class="content-images"><UsageImage v-for="(url,index) in images" :key="index" :src="url" alt="本次 AI 调用的图片结果" /></div>
        <p v-if="images.length" class="content-caption">图片支持点击放大；外部图片链接过期后可能无法预览。</p>
      </template>
    </section>
    <p v-if="payload.attemptsMayBeTruncated" class="content-caption">上游请求尝试超过保存上限，原始数据可能只包含前 100 次。</p>
    <details class="content-raw" @toggle="rawOpen = $event.target.open">
      <summary>原始请求与响应</summary>
      <template v-if="rawOpen"><h5>输入与请求参数</h5><pre class="content-fallback">{{ usageRawText(payload.request) }}</pre><h5>完整响应与审计数据</h5><pre class="content-fallback">{{ usageRawText(rawResponse) }}</pre></template>
    </details>
  </div>
</template>
<script setup>
import { computed, ref } from 'vue'
import UsageStructuredValue from './UsageStructuredValue.vue'
import UsageImage from './UsageImage.vue'
import { usageReadableContent, renderUsageMarkdown, usageRawText, USAGE_TEXT_LIMIT, usageOutputImages, usageStructuredContent } from '../aiUsageContent.js'
const props = defineProps({ payload: { type: Object, default: () => ({}) } })
const rawOpen = ref(false)
const content = computed(() => usageReadableContent(props.payload))
const images = computed(() => usageOutputImages(props.payload))
const rawResponse = computed(() => { const { request, ...rest } = props.payload; return rest })
const sections = computed(() => [
  { key: 'input', title: '输入内容', rows: content.value.input, fallback: content.value.inputFallback },
  { key: 'output', title: 'AI 返回内容', rows: content.value.output, fallback: images.value.length ? null : content.value.outputFallback }
].map(section => ({...section, structured: section.key === 'output' ? usageStructuredContent(section.fallback) : null, rows: section.rows.map(entry => ({...entry, structured: section.key === 'output' ? usageStructuredContent(entry.text) : null}))})))
</script>
<style scoped>
.usage-content{--content-ink:#334155;--content-muted:#64748b;--content-border:#e2e8f0;color:var(--content-ink);min-width:0}.content-section{margin-top:24px}.content-section h4{font-size:15px;font-weight:650;margin:0 0 12px;color:#1e293b}.content-message{padding:16px 18px;border:1px solid var(--content-border);border-radius:8px;margin-bottom:12px;background:#fff;min-width:0}.input-message{background:#f8fafc}.message-role{font-size:12px;font-weight:600;color:var(--content-muted);margin-bottom:10px}.usage-prose{font-size:14px;line-height:1.85;overflow-wrap:anywhere}.usage-prose :deep(>:first-child){margin-top:0}.usage-prose :deep(>:last-child){margin-bottom:0}.usage-prose :deep(p){margin:0 0 12px;white-space:pre-wrap}.usage-prose :deep(h1),.usage-prose :deep(h2),.usage-prose :deep(h3){line-height:1.5;margin:20px 0 10px;color:#1e293b}.usage-prose :deep(h1){font-size:22px}.usage-prose :deep(h2){font-size:19px}.usage-prose :deep(h3){font-size:16px}.usage-prose :deep(ul),.usage-prose :deep(ol){padding-left:24px;margin:10px 0}.usage-prose :deep(li){margin:4px 0}.usage-prose :deep(pre){overflow:auto;max-height:460px;padding:14px;background:#f1f5f9;border-radius:6px;white-space:pre;font-size:12px;line-height:1.7}.usage-prose :deep(code){font-family:'JetBrains Mono',monospace;font-size:.9em;background:#f1f5f9;padding:2px 4px;border-radius:3px}.usage-prose :deep(pre code){padding:0;background:none}.usage-prose :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse;font-size:13px;margin:12px 0}.usage-prose :deep(th),.usage-prose :deep(td){padding:9px 12px;border:1px solid var(--content-border);text-align:left;min-width:65px}.usage-prose :deep(th){background:#f8fafc}.usage-prose :deep(blockquote){margin:12px 0;padding:6px 14px;border-left:3px solid #cbd5e1;color:var(--content-muted)}.usage-prose :deep(a){color:#4f46e5;text-decoration:underline;text-underline-offset:3px}.usage-prose :deep(img){max-width:100%;max-height:420px;object-fit:contain}.content-error{padding:14px 18px;background:#fef2f2;border:1px solid #fecaca;color:#991b1b;border-radius:8px;margin:12px 0}.content-error .message-role{color:#991b1b}.content-caption{font-size:12px;color:var(--content-muted);line-height:1.7}.content-images{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0}.content-images img{width:180px;height:180px;object-fit:contain;border:1px solid var(--content-border);border-radius:6px;background:#f8fafc}.content-raw{margin-top:24px;border-top:1px solid var(--content-border);padding:16px 0}.content-raw summary{font-size:13px;color:var(--content-muted);cursor:pointer}.content-raw h5{font-size:13px;margin:16px 0 8px}.content-fallback{white-space:pre-wrap;overflow-wrap:anywhere;max-height:480px;overflow:auto;font-size:12px;line-height:1.7;background:#f8fafc;padding:14px;border:1px solid var(--content-border);border-radius:6px}.usage-content :deep(a:focus-visible),.content-raw summary:focus-visible{outline:2px solid #4f46e5;outline-offset:3px}@media(max-width:600px){.content-message{padding:14px}.content-images img{width:140px;height:140px}}
</style>
