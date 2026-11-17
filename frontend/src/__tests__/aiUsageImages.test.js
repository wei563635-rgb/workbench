import test from 'node:test'
import assert from 'node:assert/strict'
import { usageBase64Image, usageImages } from '../aiUsageView.js'
import { usageStructuredContent, usageOutputImages } from '../aiUsageContent.js'
const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJXcAAAAASUVORK5CYII='
test('截图中的 b64_json 格式按实际 PNG 魔数转换为图片，不再展示编码',()=>{
 const source=usageBase64Image(png)
 assert.equal(source,`data:image/png;base64,${png}`)
 assert.deepEqual(usageOutputImages({response:{background:'opaque',created:1794220072,data:[{b64_json:png}]}}),[source])
 const node=usageStructuredContent({b64_json:png}).children[0].node
 assert.equal(node.kind,'image');assert.equal(node.src,source);assert.equal(node.text,undefined)
})
test('支持带 MIME 的图片与任务包装，去重且拒绝 SVG、脚本和无效编码',()=>{
 const source=usageBase64Image(png)
 assert.equal(usageBase64Image(source),source)
 assert.deepEqual(usageImages({result:{data:[{b64_json:png},{b64_json:png}]},inlineData:{mimeType:'image/png',data:png},output:[{type:'image_generation_call',result:png}]}),[source])
 for(const value of ['javascript:alert(1)','PHN2Zz48c2NyaXB0PmFsZXJ0KDEpPC9zY3JpcHQ+PC9zdmc+', 'data:image/svg+xml;base64,'+png,'data:image/jpeg;base64,'+png,'not base64'])assert.equal(usageBase64Image(value),null)
 assert.equal(usageStructuredContent({b64_json:'invalid'}).children[0].node.text.includes('图片编码无效'),true)
})
test('Unix 秒、毫秒和 ISO 创建时间显示为北京时间，普通数字保持数值',()=>{
 const values=[1794220072,1794220072000,'2026-11-09T10:27:52Z']
 const dates=values.map(created=>usageStructuredContent({created}).children[0].node.text)
 assert.equal(dates[0],dates[1]);assert.equal(dates[1],dates[2]);assert.match(dates[0],/2026/);assert.match(dates[0],/18:27:52/)
 assert.equal(usageStructuredContent({count:1794220072}).children[0].node.text,'1,794,220,072')
})
