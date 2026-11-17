export const platforms = [{label:'OpenAI (ChatGPT Plus / Pro / API)',value:'openai'},{label:'Google Gemini (订阅 / API)',value:'gemini'}]
export const priorities = [{label:'P1 主力（优先承接流量）',value:1},{label:'P2 备选（主力满载时启用）',value:2},{label:'P3 兜底（应急备用）',value:3}]
export function supportsWindow(form) { return form.platform === 'openai' && form.type === 'oauth' }
export function validateUpstreamForm(form, editing=false) {
  if (!form.name?.trim() || form.name.length>100) return '请填写 100 字以内的账号标识'
  if (!Number.isInteger(form.concurrency) || form.concurrency<1 || form.concurrency>100) return '并发槽位必须为 1～100 的整数'
  if (supportsWindow(form) && [form.threshold5h,form.threshold7d].some(n=>!Number.isInteger(n)||n<1||n>100)) return '熔断水位必须为 1～100 的整数'
  if (form.type==='apikey' && (!editing || form.apiKey) && (!form.apiKey || /\s/.test(form.apiKey) || form.apiKey.length>8192)) return '请填写有效的 API Key，不能包含空白字符'
  return ''
}
