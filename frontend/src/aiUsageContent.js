import MarkdownIt from 'markdown-it'
import { usageImages, usageBase64Image, usageTime } from './aiUsageView.js'

// Model output is untrusted: HTML stays text and links use an explicit allowlist.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false })
export function safeUsageUrl(value) {
  return typeof value === 'string' && /^https?:\/\//i.test(value.trim()) ? value.trim() : null
}
markdown.validateLink = url => /^(https?:\/\/|mailto:)/i.test(url)
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, index, options)
}
markdown.renderer.rules.image = (tokens, index) => {
  const token = tokens[index], url = safeUsageUrl(token.attrGet('src'))
  if (!url) return markdown.utils.escapeHtml(token.content || '图片地址不可预览')
  return `<img src="${markdown.utils.escapeHtml(url)}" alt="${markdown.utils.escapeHtml(token.content)}" loading="lazy" referrerpolicy="no-referrer">`
}
export const USAGE_TEXT_LIMIT = 60000
export function renderUsageMarkdown(text) { return markdown.render(String(text).slice(0, USAGE_TEXT_LIMIT)) }
export function usageRawText(value) {
  return value == null || value === '' ? '未保存此项内容' : typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
const roles = { user: '用户', assistant: 'AI', system: '系统指令', developer: '开发者指令', tool: '工具结果', model: 'AI' }
function decoded(value) {
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { return value }
}
function message(role, text = '', images = []) { return { role: roles[role] || role || '正文', text, images } }
function hasContent(row) { return !!row.text?.trim() || row.images?.length > 0 }
function contentParts(value, role, depth = 0) {
  if (depth > 12 || value == null) return []
  if (typeof value === 'string') return value.trim() ? [message(role, value)] : []
  if (Array.isArray(value)) return value.flatMap(part => contentParts(part, role, depth + 1))
  if (typeof value !== 'object') return []
  if (value.type === 'tool_use' || value.type === 'function_call') {
    return [message(`工具调用${value.name ? `：${value.name}` : ''}`, '```json\n' + usageRawText(value.input ?? decoded(value.arguments) ?? value) + '\n```')]
  }
  if (value.type === 'tool_result' || value.type === 'function_call_output') return contentParts(value.content ?? value.output, 'tool', depth + 1)
  if (typeof value.text === 'string') return [message(role, value.text)]
  if (typeof value.thinking === 'string') return [message('思考过程', value.thinking)]
  if (typeof value.refusal === 'string') return [message(role, value.refusal)]
  const image = safeUsageUrl(value.image_url?.url ?? value.image_url ?? (value.type === 'image' ? value.source?.url : null) ?? (value.type === 'input_image' ? value.url : null) ?? value.fileData?.fileUri)
  if (image) return [message(role, '', [image])]
  if ((typeof value.type === 'string' && value.type.includes('image')) || value.inlineData || value.type === 'input_file') return [message(role, '此附件未保存可预览地址，请查看原始数据。')]
  if (value.parts) return contentParts(value.parts, role, depth + 1)
  if (value.content != null) return contentParts(value.content, value.role || role, depth + 1)
  if (Array.isArray(value.summary)) return contentParts(value.summary, '思考摘要', depth + 1)
  return []
}
function messages(value, role) {
  if (!Array.isArray(value)) return contentParts(value, role)
  return value.flatMap(item => contentParts(item, item?.role || role))
}
function requestMessages(raw) {
  const value = decoded(raw)
  if (!value || typeof value !== 'object') return contentParts(value, 'user')
  // Workbench wraps the provider body in params; Key requests already contain it.
  const body = value.params && typeof value.params === 'object' ? value.params : value
  const rows = []
  if (body.system) rows.push(...contentParts(body.system, 'system'))
  if (body.systemInstruction) rows.push(...contentParts(body.systemInstruction, 'system'))
  if (body.instructions) rows.push(...contentParts(body.instructions, 'system'))
  if (body.messages) rows.push(...messages(body.messages, 'user'))
  else if (body.contents) rows.push(...messages(body.contents, 'user'))
  else if (body.input != null) rows.push(...messages(body.input, 'user'))
  else if (body.prompt != null) rows.push(...contentParts(body.prompt, 'user'))
  else if (body.query != null) rows.push(...contentParts(body.query, 'user'))
  return rows.filter(hasContent)
}
function responseMessages(raw, depth = 0) {
  if (depth > 12) return []
  const value = decoded(raw)
  if (typeof value === 'string') return value.trim() ? [message('assistant', value)] : []
  if (!value || typeof value !== 'object') return []
  if (Array.isArray(value)) return value.flatMap(v => responseMessages(v, depth + 1))
  for (const key of ['messagesResponse', 'messageListResponse', 'completionResponse']) {
    if (value[key]) { const rows = responseMessages(value[key], depth + 1); if (rows.length) return rows }
  }
  if (Array.isArray(value.choices)) return value.choices.flatMap(choice => {
    const item = choice.message || choice.delta
    const rows = contentParts(item?.content ?? choice.text, item?.role || 'assistant')
    if (item?.refusal) rows.push(message('assistant', item.refusal))
    for (const tool of item?.tool_calls || []) rows.push(...contentParts({ type: 'function_call', ...tool.function }, 'assistant'))
    return rows
  })
  if (Array.isArray(value.candidates)) return value.candidates.flatMap(c => contentParts(c.content, 'assistant'))
  if (value.messages) return messages(value.messages, 'assistant')
  if (value.output) return messages(value.output, 'assistant')
  if (value.answer != null) return contentParts(value.answer, 'assistant')
  if (value.output_text != null) return contentParts(value.output_text, 'assistant')
  if (value.content != null) return contentParts(value.content, 'assistant')
  if (value.text != null) return contentParts(value.text, 'assistant')
  if (value.data != null) return responseMessages(value.data, depth + 1)
  if (value.response != null) return responseMessages(value.response, depth + 1)
  if (value.result != null) return responseMessages(value.result, depth + 1)
  if (value.body != null) return responseMessages(value.body, depth + 1)
  return []
}
function errorMessages(raw, depth = 0) {
  if (depth > 12) return []
  const value = decoded(raw)
  if (!value || typeof value !== 'object') return []
  const error = value.error ?? value.last_error
  const rows = []
  if (error != null && error !== '' && error !== false && error !== 0 && (typeof error !== 'object' || Object.keys(error).length > 0)) {
    const text = typeof error === 'string' ? error : error.message || error.detail || usageRawText(error)
    rows.push(message('调用错误', text))
  } else if (value.message && (value.code != null && !['0', '200', 'OK', 'SUCCESS'].includes(String(value.code).toUpperCase()) || ['failed', 'error', 'rejected'].includes(value.status))) rows.push(message('调用错误', String(value.message)))
  for (const key of ['completionResponse', 'data', 'response', 'result', 'body']) if (value[key] && typeof value[key] === 'object') rows.push(...errorMessages(value[key], depth + 1))
  return rows
}
// Single pass over stream events. Final snapshots replace deltas, avoiding duplicated answers.
function streamMessages(raw) {
  const lanes = new Map(), errors = [], unknown = []; let snapshot = []
  function put(key, text, role = 'assistant', replace = false) {
    if (typeof text !== 'string') return
    const previous = lanes.get(key)
    lanes.set(key, message(role, replace ? text : (previous?.text || '') + text))
  }
  const events = raw.replace(/\r\n/g, '\n').split(/\n\s*\n/)
  for (const event of events) {
    const data = event.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n')
    if (!data || data.trim() === '[DONE]') continue
    const value = decoded(data)
    if (typeof value !== 'object' || value === null) { unknown.push(message('未识别片段', data)); continue }
    const eventErrors = errorMessages(value)
    errors.push(...eventErrors)
    const kind = value.type || ''
    if (kind === 'response.completed' || kind === 'response.incomplete' || kind === 'response.failed') {
      const full = responseMessages(value.response); if (full.length) snapshot = full
      continue
    }
    if (kind === 'response.output_text.delta' || kind === 'response.refusal.delta') { put(`r:${value.output_index || 0}:${value.content_index || 0}`, value.delta); continue }
    if (kind === 'response.output_text.done' || kind === 'response.refusal.done') { put(`r:${value.output_index || 0}:${value.content_index || 0}`, value.text ?? value.refusal, 'assistant', true); continue }
    if (kind === 'content_block_start') {
      put(`a:${value.index || 0}`, value.content_block?.text ?? value.content_block?.thinking ?? '', value.content_block?.type === 'thinking' ? '思考过程' : 'assistant'); continue
    }
    if (kind === 'content_block_delta') { put(`a:${value.index || 0}`, value.delta?.text ?? value.delta?.thinking, value.delta?.type === 'thinking_delta' ? '思考过程' : 'assistant'); continue }
    if (value.choices) {
      for (const choice of value.choices) {
        const item = choice.message || choice.delta, key = `c:${choice.index || 0}`
        for (const part of contentParts(item?.content ?? choice.text, 'assistant')) if (part.text) put(key, part.text, 'assistant', !!choice.message)
        for (const tool of item?.tool_calls || []) {
          const key = `tool:${choice.index || 0}:${tool.index || 0}`
          put(key, tool.function?.arguments || '', tool.function?.name ? `工具调用：${tool.function.name}` : lanes.get(key)?.role || '工具调用')
        }
      }
      continue
    }
    if (value.candidates) { value.candidates.forEach((c, i) => contentParts(c.content, 'assistant').forEach(p => put(`g:${c.index ?? i}`, p.text))); continue }
    // Metadata-only events stay in raw data. Preserve unrecognised content events visibly.
    const rows = responseMessages(value)
    if (rows.length) unknown.push(...rows)
    else if (!eventErrors.length && (kind.includes('delta') || (!kind && !('usage' in value || 'id' in value || 'model' in value)))) unknown.push(message('未识别片段', data))
  }
  return { rows: [...(snapshot.length ? snapshot : [...lanes.values()]), ...unknown].filter(hasContent), errors }
}
export function usageReadableContent(payload = {}) {
  const request = payload?.request
  const candidates = [payload?.taskResult, payload?.response, payload?.providerResponse].filter(v => v != null && v !== '')
  let output = [], errors = []
  for (const raw of candidates) {
    const value = decoded(raw)
    const parsed = typeof value === 'string' && /(^|\n)data:/.test(value) ? streamMessages(value) : { rows: responseMessages(value), errors: errorMessages(value) }
    errors.push(...parsed.errors)
    if (!output.length && parsed.rows.length) output = parsed.rows
  }
  if (payload?.error) errors.push(message('调用错误', typeof payload.error === 'string' ? payload.error : usageRawText(payload.error)))
  const input = requestMessages(request)
  return {
    input, output,
    errors: errors.filter((row, i, all) => all.findIndex(other => other.text === row.text) === i),
    inputFallback: input.length ? null : request,
    outputFallback: output.length || errors.length ? null : candidates[0],
    metadataOnly: !output.length && candidates.some(value => { const body = decoded(value); return body?.completionResponse?.data?.status != null && !body?.messagesResponse && !body?.messageListResponse })
  }
}

export function usageOutputImages(payload = {}) {
  const images = [payload.taskResult, payload.response, payload.providerResponse].flatMap(value => usageImages(decoded(value)))
  return [...new Set(images)].slice(0, 50)
}

const returnFieldLabels = {
  b64_json:'图片',background:'背景',status:'状态',progress:'进度',usage:'用量',input_count:'输入 Token',output_count:'输出 Token',token_count:'总 Token',input_tokens:'输入 Token',output_tokens:'输出 Token',prompt_tokens:'输入 Token',completion_tokens:'输出 Token',total_tokens:'总 Token',cache_tokens:'缓存 Token',credits:'实际积分',credits_reserved:'预扣积分',id:'记录 ID',task_id:'任务 ID',chat_id:'对话 ID',conversation_id:'会话 ID',code:'响应码',msg:'返回消息',message:'返回消息',model:'模型',model_name:'模型',created_at:'创建时间',completed_at:'完成时间',created:'创建时间',updated_at:'更新时间',results:'结果',result:'结果',output:'输出',answer:'回答',content:'内容',text:'正文',data:'返回数据',completionResponse:'任务结果',initialResponse:'任务提交信息',_platform:'平台信息',provider:'供应商',response:'返回内容',error:'错误',reason:'原因',name:'名称',type:'类型',role:'角色',title:'标题',summary:'摘要',suggestions:'建议',items:'条目',url:'链接'
}
const returnStatuses = {completed:'已完成',succeeded:'成功',success:'成功',done:'已完成',failed:'失败',error:'失败',cancelled:'已取消',canceled:'已取消',running:'处理中',processing:'处理中',queued:'排队中',pending:'等待处理',submitted:'已提交'}
// Bounded tree for returned JSON. Values are preserved; protocol metadata never becomes an AI answer.
export function usageStructuredContent(raw) {
  let value=raw
  if (typeof value==='string') {
    const fenced=value.trim().match(/^```(?:json)?\s*\n([\s\S]*?)\n```$/i)
    value=decoded(fenced ? fenced[1] : value)
  }
  if (!value || typeof value!=='object') return null
  let budget=300
  function walk(item,key='',depth=0) {
    if (--budget<0 || depth>8) return {kind:'limited'}
    if (item===null) return {kind:'value',text:'空值'}
    if (key==='b64_json') { const src=usageBase64Image(item); return src ? {kind:'image',src} : {kind:'value',text:'图片编码无效、已截断或格式暂不支持；原始内容保留在下方原始数据中。'} }
    if (['created','created_at','completed_at','updated_at'].includes(key)) {
      const numeric=typeof item==='number'||typeof item==='string'&&/^\d+(?:\.\d+)?$/.test(item) ? Number(item) : null
      const date=numeric!==null ? new Date(numeric<100000000000?numeric*1000:numeric) : new Date(item)
      if(!Number.isNaN(date.getTime()))return {kind:'value',text:usageTime(date)}
    }
    if (Array.isArray(item)) {
      const children=[]
      for (const [i,entry] of item.slice(0,50).entries()) { if(budget<=0)break; children.push({label:`第 ${i+1} 项`,node:walk(entry,'',depth+1)}) }
      return {kind:'list',children,limited:children.length<item.length}
    }
    if (typeof item==='object') {
      const entries=Object.entries(item),children=[]
      for (const [field,entry] of entries.slice(0,50)) { if(budget<=0)break; children.push({label:Object.hasOwn(returnFieldLabels,field)?returnFieldLabels[field]:field,node:walk(entry,field,depth+1)}) }
      return {kind:'object',children,limited:children.length<entries.length}
    }
    return {kind:'value',text:typeof item==='boolean'?(item?'是':'否'):key==='status'?(Object.hasOwn(returnStatuses,item)?returnStatuses[item]:String(item)):typeof item==='number'?item.toLocaleString('zh-CN'):String(item)}
  }
  return walk(value)
}
