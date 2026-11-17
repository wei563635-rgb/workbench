import test from 'node:test'
import assert from 'node:assert/strict'
import { rangeFor, heatmap, percent, sharePercent, tokens, consumptionDepartmentPage } from '../relayView.js'
test('北京时间月初和跨年七日范围',()=>{
  const now=new Date('2026-12-31T16:00:00Z')
  assert.deepEqual(rangeFor('month',now),{from:'2027-01-01',to:'2027-01-01'})
  assert.deepEqual(rangeFor('week',now),{from:'2026-12-26',to:'2027-01-01'})
})
test('缺失额度、零额度与超额分别显示',()=>{
  assert.equal(tokens(null),'未配置');assert.equal(tokens(0),'0')
  assert.equal(percent(10,null),0);assert.equal(percent(0,0),100);assert.equal(percent(110,100),100)
})
test('无总消耗时各项份额为零且不改变零额度语义',()=>{
  assert.equal(sharePercent(0,0),0)
  assert.equal(sharePercent(0,null),0)
  assert.equal(sharePercent(0,undefined),0)
  assert.equal(sharePercent(25,100),25)
  assert.equal(sharePercent(110,100),100)
  assert.equal(percent(0,0),100)
})
test('部门消耗按降序分页并覆盖所有有消耗部门',()=>{
  const rows=Array.from({length:12},(_,index)=>({key:`d${index+1}`,tokens:index+1}))
  rows.push({key:'unused',tokens:0})
  const first=consumptionDepartmentPage(rows,1)
  const second=consumptionDepartmentPage(rows,2)
  const third=consumptionDepartmentPage(rows,3)
  assert.equal(first.total,12)
  assert.equal(first.pages,3)
  assert.deepEqual([...first.items,...second.items,...third.items].map(row=>row.tokens),[12,11,10,9,8,7,6,5,4,3,2,1])
  assert.deepEqual(consumptionDepartmentPage(rows,99).items.map(row=>row.tokens),[2,1])
  assert.deepEqual(consumptionDepartmentPage([],1),{items:[],total:0,pages:1,page:1})
})
test('年度热力图跨闰日连续且不重复累计',()=>{
  const days=heatmap([{key:'2024-02-29',tokens:12000}],new Date('2024-03-01T00:00:00Z'))
  assert.equal(days.length,365);assert.equal(new Set(days.map(d=>d.day)).size,365)
  assert.equal(days.find(d=>d.day==='2024-02-29').count,12000)
})

test('热力图按周一对齐，跨月坐标不偏移',async()=>{
  const {calendarLayout,longestWorkdayStreak}=await import('../relayView.js')
  const days=[{day:'2026-09-30',count:10},{day:'2026-10-01',count:20},{day:'2026-10-02',count:0}]
  const layout=calendarLayout(days)
  assert.equal(layout.cells[0],null)
  assert.equal(layout.cells[2].day,'2026-09-30')
  assert.equal(layout.cells.length%7,0)
  assert.equal(longestWorkdayStreak([{day:'2026-09-25',count:1},{day:'2026-09-26',count:0},{day:'2026-09-27',count:0},{day:'2026-09-28',count:1}]),2)
})
