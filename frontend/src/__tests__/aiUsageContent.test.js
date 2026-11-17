import test from 'node:test'
import assert from 'node:assert/strict'
import { usageReadableContent, renderUsageMarkdown, USAGE_TEXT_LIMIT, usageOutputImages, usageStructuredContent } from '../aiUsageContent.js'
const event = value => `data: ${JSON.stringify(value)}\n\n`

test('工作台参数与 OpenAI 对话按角色提取，元数据不混进正文', () => {
  const result = usageReadableContent({request:{runId:'run_1',params:{messages:[{role:'system',content:'请简洁回答'},{role:'user',content:[{type:'text',text:'分析销量'},{type:'image_url',image_url:{url:'https://example.com/chart.png'}}]}]}},response:{choices:[{message:{role:'assistant',content:'## 结论\n销量增长。'}}],usage:{total_tokens:50}}})
  assert.deepEqual(result.input.map(r => r.role), ['系统指令','用户','用户'])
  assert.equal(result.input[1].text, '分析销量')
  assert.deepEqual(result.input[2].images,['https://example.com/chart.png'])
  assert.equal(result.output[0].text,'## 结论\n销量增长。')
  assert.equal(result.inputFallback,null)
  assert.equal(result.outputFallback,null)
})
test('支持 Responses、Claude、Gemini 及工作台供应商包装', () => {
  for (const response of [
    {output:[{type:'message',role:'assistant',content:[{type:'output_text',text:'回答'}]}]},
    {content:[{type:'text',text:'回答'}]},
    {candidates:[{content:{role:'model',parts:[{text:'回答'}]}}]},
    {completionResponse:{data:{choices:[{message:{content:'回答'}}]}}},
    JSON.stringify({output_text:'回答'})
  ]) assert.equal(usageReadableContent({response}).output[0].text,'回答')
  const result = usageReadableContent({request:{systemInstruction:{parts:[{text:'系统'}]},contents:[{role:'user',parts:[{text:'问题'}]}]}})
  assert.deepEqual(result.input.map(r => r.text),['系统','问题'])
})
test('Chat 流按候选项合并增量，最终消息不重复，保留错误', () => {
  const response = event({choices:[{index:0,delta:{content:'你'}},{index:1,delta:{content:'其'}}]}) + event({choices:[{index:0,delta:{content:'好'}},{index:1,delta:{content:'他'}}]}) + event({choices:[{index:0,message:{content:'你好'}}]}) + event({error:{message:'连接中断'}}) + 'data: [DONE]\n\n'
  const result = usageReadableContent({response})
  assert.deepEqual(result.output.map(r=>r.text).slice(0,2),['你好','其他'])
  assert.equal(result.errors[0].text,'连接中断')
})
test('Responses 流和 Claude 流合并，完成快照替换增量', () => {
  let response = event({type:'response.output_text.delta',delta:'答'}) + event({type:'response.output_text.delta',delta:'案'}) + event({type:'response.output_text.done',text:'答案'})
  assert.equal(usageReadableContent({response}).output[0].text,'答案')
  response += event({type:'response.completed',response:{output:[{type:'message',content:[{type:'output_text',text:'完整答案'}]}]}})
  assert.deepEqual(usageReadableContent({response}).output.map(r=>r.text),['完整答案'])
  response = event({type:'content_block_start',index:0,content_block:{type:'text',text:''}}) + event({type:'content_block_delta',index:0,delta:{type:'text_delta',text:'第一段'}}) + event({type:'content_block_delta',index:0,delta:{type:'text_delta',text:'第二段'}})
  assert.equal(usageReadableContent({response}).output[0].text,'第一段第二段')
})
test('工具调用和工具结果可读，失败及异步结果不被空响应覆盖', () => {
  let result=usageReadableContent({response:{choices:[{message:{content:null,tool_calls:[{function:{name:'查询库存',arguments:'{"sku":"A"}'}}]}}]}})
  assert.equal(result.output[0].role,'工具调用：查询库存')
  assert.match(result.output[0].text,/'sku'|"sku"/)
  result=usageReadableContent({taskResult:{status:'completed'},providerResponse:{output_text:'任务结果'}})
  assert.equal(result.output[0].text,'任务结果')
  result=usageReadableContent({response:{error:{code:'quota',message:'可用额度不足'}}})
  assert.equal(result.errors[0].text,'可用额度不足')
  assert.equal(result.outputFallback,null)
})
test('未知格式与损坏的流片段保留原文，空记录有明确空状态', () => {
  const raw={custom_result:{answer:'未知结构'}}
  assert.deepEqual(usageReadableContent({response:raw}).outputFallback,raw)
  assert.deepEqual(usageReadableContent({request:{custom:'问题'}}).inputFallback,{custom:'问题'})
  assert.equal(usageReadableContent({response:'data: {broken\n\n'}).output[0].text,'{broken')
  assert.equal(usageReadableContent({}).output.length,0)
  assert.equal(usageReadableContent({response:'纯文本回答'}).output[0].text,'纯文本回答')
})
test('Markdown 支持表格代码列表，禁用模型 HTML、脚本链接及属性注入', () => {
  const html=renderUsageMarkdown('# 标题\n\n- 条目\n\n| 模型 | 用量 |\n| --- | --- |\n| GPT | 12 |\n\n```js\nconst n = 1\n```\n\n<script>alert(1)</script>\n[恶意](javascript:alert(1))\n![图](https://example.com/a.png)\n[链接](https://example.com)')
  assert.match(html,/<h1>标题<\/h1>/);assert.match(html,/<table>/);assert.match(html,/<ul>/);assert.match(html,/<pre><code/)
  assert.doesNotMatch(html,/<script|href="javascript:|onerror=/)
  assert.match(html,/&lt;script&gt;/);assert.match(html,/rel="noopener noreferrer"/);assert.match(html,/referrerpolicy="no-referrer"/)
  assert.doesNotMatch(renderUsageMarkdown('![x](data:image/svg+xml;base64,PHN2Zz4=)'),/<img/)
  assert.ok(renderUsageMarkdown('a'.repeat(USAGE_TEXT_LIMIT+20)).length < USAGE_TEXT_LIMIT+30)
})

test('图片同时识别异步结果、JSON 字符串和供应商响应并去重', () => {
  assert.deepEqual(usageOutputImages({taskResult:{status:'done'},response:JSON.stringify({data:[{url:'https://example.com/a.png'}]}),providerResponse:{images:['https://example.com/a.png','https://example.com/b.png']}}),['https://example.com/a.png','https://example.com/b.png'])
})

test('历史 Coze 仅保存任务状态时不冒充回答，状态和用量可读', () => {
  const raw={completionResponse:{code:0,data:{status:'completed',usage:{input_count:123,output_count:456}}}}
  const result=usageReadableContent({response:raw})
  assert.equal(result.output.length,0);assert.equal(result.metadataOnly,true);assert.deepEqual(result.outputFallback,raw)
  const tree=usageStructuredContent(raw)
  const data=tree.children[0].node.children.find(v=>v.label==='返回数据').node
  assert.equal(data.children.find(v=>v.label==='状态').node.text,'已完成')
  assert.equal(data.children.find(v=>v.label==='用量').node.children[0].label,'输入 Token')
})
test('返回正文中的 JSON 和 JSON 代码块转换成可读字段，保留零、否和空值', () => {
  const value={title:'分析结论',items:[{name:'商品 A',count:0,enabled:false,extra:null}],summary:'**增长**'}
  for (const raw of [value,JSON.stringify(value),'```json\n'+JSON.stringify(value)+'\n```']) {
    const node=usageStructuredContent(raw)
    assert.equal(node.children[0].label,'标题')
    const row=node.children[1].node.children[0].node.children
    assert.deepEqual(row.map(v=>v.node.text),['商品 A','0','否','空值'])
  }
  assert.equal(usageStructuredContent('普通 Markdown **回答**'),null)
})
test('任务 result/body 与已保存的 Coze 消息列表可以提取返回文本', () => {
  for (const response of [{result:{output_text:'最终结果'}},{body:JSON.stringify({choices:[{message:{content:'最终结果'}}]})},{completionResponse:{code:0,data:{status:'completed'}},messagesResponse:{data:[{role:'assistant',content:'最终结果'}]}}]) assert.equal(usageReadableContent({taskResult:response}).output[0].text,'最终结果')
})
test('结构化返回有深度和节点上限，原始字段不会被原型属性覆盖', () => {
  const tree=usageStructuredContent(Array.from({length:500},(_,i)=>({id:i})))
  assert.equal(tree.children.length,50);assert.equal(tree.limited,true)
  assert.equal(usageStructuredContent(JSON.parse('{"__proto__":"value"}')).children[0].label,'__proto__')
})

test('成功任务的空错误字段和成功响应码不能显示为调用错误', () => {
  for (const error of ['',null,false,0,{}]) {
    const result=usageReadableContent({taskResult:{status:'success',error,result:{output_text:'已完成'}}})
    assert.equal(result.output[0].text,'已完成');assert.equal(result.errors.length,0)
  }
  assert.equal(usageReadableContent({response:{code:0,message:'success',data:{answer:'回答'}}}).errors.length,0)
})
