import test from 'node:test'
import assert from 'node:assert/strict'
import {supportsWindow,validateUpstreamForm} from '../upstreamAccountForm.js'
const base={name:'QA',concurrency:5,threshold5h:90,threshold7d:95}
test('四种接入方式只对 OpenAI 订阅显示窗口',()=>{
 for(const platform of ['openai','gemini'])for(const type of ['oauth','apikey']){
  const f={...base,platform,type,apiKey:'sample-key'}
  assert.equal(supportsWindow(f),platform==='openai'&&type==='oauth')
  assert.equal(validateUpstreamForm(f),'')
 }
})
test('新增 API 必填密钥，编辑空值保留，Google 密钥不要求 sk 前缀',()=>{
 const f={...base,platform:'gemini',type:'apikey',apiKey:''}
 assert.notEqual(validateUpstreamForm(f),'');assert.equal(validateUpstreamForm(f,true),'')
 assert.equal(validateUpstreamForm({...f,apiKey:'AIzaSample'}),'')
 assert.notEqual(validateUpstreamForm({...f,apiKey:'wrong key'}),'')
})
test('窗口和并发使用整数边界，不接受小数或越界',()=>{
 const f={...base,platform:'openai',type:'oauth'}
 for(const value of [0,101,1.5,''])assert.notEqual(validateUpstreamForm({...f,concurrency:value}),'')
 assert.notEqual(validateUpstreamForm({...f,threshold5h:101}),'')
 assert.equal(validateUpstreamForm({...f,platform:'gemini',threshold5h:101}),'')
})
